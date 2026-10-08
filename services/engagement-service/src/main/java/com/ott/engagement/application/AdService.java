package com.ott.engagement.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.engagement.adapter.outbound.ads.CreativePolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final AdAccess access;
  private final AdDelivery delivery;
  private final CreativePolicy policy;
  private final Clock clock;
  private final ObjectMapper json;
  private final MeterRegistry metrics;

  public AdService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      AdAccess access,
      AdDelivery delivery,
      CreativePolicy policy,
      Clock clock,
      ObjectMapper json,
      MeterRegistry metrics) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.access = access;
    this.delivery = delivery;
    this.policy = policy;
    this.clock = clock;
    this.json = json;
    this.metrics = metrics;
  }

  public record Campaign(
      UUID id,
      String name,
      String country,
      int maturity,
      String placement,
      Instant startsAt,
      Instant endsAt,
      int dailyCap,
      String creativeUrl,
      int durationMillis,
      boolean enabled,
      long version) {}

  public record Decision(
      UUID id,
      UUID accountId,
      UUID profileId,
      UUID deviceId,
      UUID sessionId,
      UUID campaignId,
      String state,
      Instant expiresAt,
      String adapter,
      String mediaUrl,
      int durationMillis,
      long version,
      Instant issuedAt) {}

  public record Result(Decision decision, String reason, boolean duplicate) {}

  public record Receipt(boolean duplicate, long version) {}

  private record Reservation(Decision decision, Campaign campaign, UUID claim, boolean duplicate) {}

  public Campaign create(
      String name,
      String country,
      int maturity,
      String placement,
      Instant start,
      Instant end,
      int cap,
      String url,
      int duration,
      String actor) {
    if (!text(name, 120)
        || country == null
        || !country.matches("[A-Z]{2}")
        || maturity < 0
        || maturity > 18
        || !placement(placement)
        || start == null
        || end == null
        || !start.isBefore(end)
        || java.time.Duration.between(start, end).compareTo(java.time.Duration.ofDays(366)) > 0
        || cap < 1
        || cap > 20
        || !policy.permits(url)
        || duration < 1000
        || duration > 120000
        || !text(actor, 1024)) throw fail(400, "INVALID_CAMPAIGN");
    return tx.execute(
        t -> {
          configurationLock(country, placement);
          campaignCapacity(country, placement, null);
          UUID id = UUID.randomUUID();
          db.update(
              "INSERT INTO"
                  + " ad_campaign(id,name,country,maturity,placement,starts_at,ends_at,daily_cap,creative_url,duration_millis)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?)",
              id,
              name,
              country,
              maturity,
              placement,
              stamp(start),
              stamp(end),
              cap,
              url,
              duration);
          audit(id, 1, "CREATE", actor);
          return campaign(id);
        });
  }

  public Campaign configure(UUID id, long expected, boolean enabled, String actor) {
    if (!text(actor, 1024)) throw fail(400, "INVALID_ACTOR");
    return tx.execute(
        t -> {
          var prior = campaign(id);
          configurationLock(prior.country(), prior.placement());
          if (enabled) campaignCapacity(prior.country(), prior.placement(), id);
          if (db.update(
                  "UPDATE ad_campaign SET enabled=?,version=version+1 WHERE id=? AND version=?",
                  enabled,
                  id,
                  expected)
              != 1) throw fail(409, "CAMPAIGN_VERSION_CONFLICT");
          var c = campaign(id);
          audit(id, c.version(), enabled ? "ENABLE" : "DISABLE", actor);
          return c;
        });
  }

  public List<Campaign> campaigns(UUID after, int limit) {
    if (limit < 1 || limit > 100) throw fail(400, "INVALID_QUERY");
    return db.query(
        "SELECT * FROM ad_campaign WHERE (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
        this::campaignRow,
        after,
        after,
        limit);
  }

  public Result decide(
      String viewer,
      UUID profile,
      UUID device,
      String token,
      String placement,
      String adapter,
      String key) {
    if (!placement(placement)
        || !List.of("CSAI", "SSAI").contains(adapter == null ? "" : adapter)
        || !key(key)) throw fail(400, "INVALID_AD_REQUEST");
    var context = access.verify(viewer, profile, device, token);
    if (!valid(context, profile, device)) throw fail(403, "AD_GRANT_DENIED");
    if (context.kids() || !context.adSupported())
      return outcome(
          new Result(null, context.kids() ? "KIDS_EXCLUDED" : "AD_FREE_OR_INACTIVE", false));
    String fingerprint =
        hash(profile + ":" + device + ":" + context.sessionId() + ":" + placement + ":" + adapter);
    var reserved =
        tx.execute(
            t -> {
              lock(context.accountId());
              db.update(
                  "UPDATE ad_decision SET state='SKIPPED',claim_token=NULL,claimed_until=NULL WHERE"
                      + " account_id=? AND state='PENDING' AND expires_at<=?",
                  context.accountId(),
                  stamp(clock.instant()));
              var prior =
                  db.query(
                      "SELECT * FROM ad_decision WHERE account_id=? AND idempotency_key=? FOR"
                          + " UPDATE",
                      this::decisionRow,
                      context.accountId(),
                      key);
              if (!prior.isEmpty()) {
                var d = prior.getFirst();
                String priorHash =
                    db.queryForObject(
                        "SELECT fingerprint FROM ad_decision WHERE id=?", String.class, d.id());
                if (!fingerprint.equals(priorHash)) throw fail(409, "IDEMPOTENCY_CONFLICT");
                if (!d.expiresAt().isAfter(clock.instant()) || d.state().equals("SKIPPED"))
                  throw fail(409, "AD_DECISION_EXPIRED");
                if (!eligible(campaign(d.campaignId()), context))
                  throw fail(409, "AD_CAMPAIGN_UNAVAILABLE");
                if (d.state().equals("ACTIVE")) return new Reservation(d, null, null, true);
                UUID claim = claim(d.id());
                return new Reservation(d, campaign(d.campaignId()), claim, true);
              }
              var day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
              if (db.queryForObject(
                      "SELECT count(*) FROM ad_decision WHERE account_id=? AND window_day=?",
                      Integer.class,
                      context.accountId(),
                      day)
                  >= 1000) throw fail(429, "AD_REQUEST_LIMIT_REACHED");
              var candidates =
                  db.query(
                      "SELECT * FROM ad_campaign WHERE enabled AND country=? AND placement=? AND"
                          + " maturity<=? AND starts_at<=? AND ends_at>? ORDER BY id LIMIT 100",
                      this::campaignRow,
                      context.country(),
                      placement,
                      context.maturity(),
                      stamp(clock.instant()),
                      stamp(clock.instant()));
              for (var c : candidates) {
                int count =
                    db.queryForObject(
                        "SELECT count(*) FROM ad_decision WHERE account_id=? AND campaign_id=? AND"
                            + " window_day=? AND state IN('PENDING','ACTIVE')",
                        Integer.class,
                        context.accountId(),
                        c.id(),
                        day);
                if (count >= c.dailyCap()) continue;
                UUID id = UUID.randomUUID(), claim = UUID.randomUUID();
                Instant expiry =
                    min(clock.instant().plusSeconds(300), context.expiresAt(), c.endsAt());
                db.update(
                    "INSERT INTO"
                        + " ad_decision(id,account_id,profile_id,device_id,session_id,campaign_id,idempotency_key,fingerprint,window_day,adapter,state,expires_at,created_at,claim_token,claimed_until,duration_millis)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?)",
                    id,
                    context.accountId(),
                    profile,
                    device,
                    context.sessionId(),
                    c.id(),
                    key,
                    fingerprint,
                    day,
                    adapter,
                    stamp(expiry),
                    stamp(clock.instant()),
                    claim,
                    stamp(clock.instant().plusSeconds(5)),
                    c.durationMillis());
                return new Reservation(decision(id), c, claim, false);
              }
              return null;
            });
    if (reserved == null) return outcome(new Result(null, "NO_FILL_OR_CAPPED", false));
    if (reserved.claim() == null) return outcome(new Result(reserved.decision(), "FILLED", true));
    var d = reserved.decision();
    AdDelivery.Creative creative;
    try {
      creative = delivery.deliver(adapter, d.id(), reserved.campaign(), d.expiresAt());
      if (creative == null
          || !policy.permits(creative.url())
          || creative.durationMillis() != d.durationMillis())
        throw fail(503, "AD_PROVIDER_UNAVAILABLE");
    } catch (EngagementFailure e) {
      if (e.status() != 503) throw e;
      tx.executeWithoutResult(
          t ->
              db.update(
                  "UPDATE ad_decision SET state='SKIPPED',claim_token=NULL,claimed_until=NULL WHERE"
                      + " id=? AND state='PENDING' AND claim_token=?",
                  d.id(),
                  reserved.claim()));
      return outcome(new Result(null, "PROVIDER_SKIPPED", reserved.duplicate()));
    }
    var issued =
        tx.execute(
            t -> {
              lock(context.accountId());
              var current = decision(d.id());
              if (!current.expiresAt().isAfter(clock.instant())
                  || !eligible(lockedCampaign(current.campaignId()), context)
                  || clock
                      .instant()
                      .plusMillis(current.durationMillis())
                      .isAfter(current.expiresAt())) {
                db.update(
                    "UPDATE ad_decision SET state='SKIPPED',claim_token=NULL WHERE id=? AND"
                        + " claim_token=?",
                    d.id(),
                    reserved.claim());
                return null;
              }
              if (db.update(
                      "UPDATE ad_decision SET"
                          + " state='ACTIVE',media_url=?,issued_at=?,claim_token=NULL,claimed_until=NULL"
                          + " WHERE id=? AND state='PENDING' AND claim_token=?",
                      creative.url(),
                      stamp(clock.instant()),
                      d.id(),
                      reserved.claim())
                  != 1) throw fail(409, "AD_DECISION_PENDING");
              return decision(d.id());
            });
    return outcome(
        new Result(issued, issued == null ? "NOT_ISSUED" : "FILLED", reserved.duplicate()));
  }

  public Receipt report(
      String viewer,
      UUID profile,
      UUID device,
      String token,
      UUID decision,
      UUID event,
      int quartile,
      Instant occurred) {
    if (event == null || decision == null || occurred == null || quartile < 0 || quartile > 4)
      throw fail(400, "INVALID_AD_EVENT");
    var context = access.verify(viewer, profile, device, token);
    if (!valid(context, profile, device)) throw fail(403, "AD_GRANT_DENIED");
    String fingerprint =
        hash(context.accountId() + ":" + decision + ":" + quartile + ":" + occurred);
    return tx.execute(
        t -> {
          var rows =
              db.query(
                  "SELECT * FROM ad_decision WHERE id=? FOR UPDATE", this::decisionRow, decision);
          if (rows.isEmpty()) throw fail(404, "AD_DECISION_NOT_FOUND");
          var d = rows.getFirst();
          if (!d.accountId().equals(context.accountId())
              || !d.profileId().equals(profile)
              || !d.deviceId().equals(device)
              || !d.sessionId().equals(context.sessionId())) throw fail(403, "AD_GRANT_DENIED");
          var prior =
              db.queryForList(
                  "SELECT fingerprint FROM ad_receipt WHERE event_id=?", String.class, event);
          if (!prior.isEmpty()) {
            if (!prior.getFirst().equals(fingerprint)) throw fail(409, "IDEMPOTENCY_CONFLICT");
            return new Receipt(true, d.version());
          }
          if (!d.state().equals("ACTIVE")
              || d.issuedAt() == null
              || clock.instant().isAfter(d.expiresAt().plusSeconds(60))
              || occurred.isBefore(d.issuedAt())
              || occurred.isAfter(d.expiresAt())
              || occurred.isAfter(clock.instant().plusSeconds(5))
              || quartile > 0
                  && occurred
                      .plusMillis(1000)
                      .isBefore(d.issuedAt().plusMillis((long) d.durationMillis() * quartile / 4)))
            throw fail(400, "IMPOSSIBLE_AD_EVENT_TIME");
          if (db.update(
                  "INSERT INTO ad_receipt(event_id,account_id,fingerprint) VALUES(?,?,?) ON"
                      + " CONFLICT DO NOTHING",
                  event,
                  context.accountId(),
                  fingerprint)
              == 0) {
            String priorHash =
                db.queryForObject(
                    "SELECT fingerprint FROM ad_receipt WHERE event_id=?", String.class, event);
            if (!priorHash.equals(fingerprint)) throw fail(409, "IDEMPOTENCY_CONFLICT");
            return new Receipt(true, d.version());
          }
          if (db.update(
                  "INSERT INTO ad_event VALUES(?,?,?) ON CONFLICT DO NOTHING",
                  decision,
                  quartile,
                  stamp(occurred))
              == 0) return new Receipt(true, d.version());
          long version = d.version() + 1;
          db.update("UPDATE ad_decision SET version=? WHERE id=?", version, decision);
          outbox(
              decision,
              version,
              Map.of(
                  "accountId",
                  context.accountId().toString(),
                  "decisionId",
                  decision.toString(),
                  "campaignId",
                  d.campaignId().toString(),
                  "profileRef",
                  profile.toString(),
                  "quartile",
                  quartile));
          metrics.counter("ott.ad.event", "quartile", Integer.toString(quartile)).increment();
          return new Receipt(false, version);
        });
  }

  private void configurationLock(String country, String placement) {
    db.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        "ad-config:" + country + ":" + placement);
  }

  private void campaignCapacity(String country, String placement, UUID except) {
    if (db.queryForObject(
            "SELECT count(*) FROM ad_campaign WHERE country=? AND placement=? AND enabled AND"
                + " (?::uuid IS NULL OR id<>?::uuid)",
            Integer.class,
            country,
            placement,
            except,
            except)
        >= 100) throw fail(409, "CAMPAIGN_CAPACITY_REACHED");
  }

  private boolean eligible(Campaign c, AdAccess.Context context) {
    return c.enabled()
        && c.country().equals(context.country())
        && c.maturity() <= context.maturity()
        && !c.startsAt().isAfter(clock.instant())
        && c.endsAt().isAfter(clock.instant());
  }

  private UUID claim(UUID id) {
    UUID claim = UUID.randomUUID();
    if (db.update(
            "UPDATE ad_decision SET claim_token=?,claimed_until=? WHERE id=? AND (claimed_until IS"
                + " NULL OR claimed_until<=?)",
            claim,
            stamp(clock.instant().plusSeconds(5)),
            id,
            stamp(clock.instant()))
        != 1) throw fail(409, "AD_DECISION_PENDING");
    return claim;
  }

  private boolean valid(AdAccess.Context c, UUID profile, UUID device) {
    return c != null
        && c.accountId() != null
        && c.sessionId() != null
        && profile != null
        && profile.equals(c.profileId())
        && device != null
        && device.equals(c.deviceId())
        && c.country() != null
        && c.country().matches("[A-Z]{2}")
        && c.maturity() >= 0
        && c.maturity() <= 18
        && c.expiresAt() != null
        && c.expiresAt().isAfter(clock.instant());
  }

  private Result outcome(Result result) {
    metrics.counter("ott.ad.decision", "reason", result.reason()).increment();
    return result;
  }

  private void lock(UUID account) {
    db.update("INSERT INTO ad_account VALUES(?) ON CONFLICT DO NOTHING", account);
    db.queryForList("SELECT account_id FROM ad_account WHERE account_id=? FOR UPDATE", account);
  }

  private Campaign lockedCampaign(UUID id) {
    return db.queryForObject(
        "SELECT * FROM ad_campaign WHERE id=? FOR SHARE", this::campaignRow, id);
  }

  private Campaign campaign(UUID id) {
    var rows = db.query("SELECT * FROM ad_campaign WHERE id=?", this::campaignRow, id);
    if (rows.isEmpty()) throw fail(404, "CAMPAIGN_NOT_FOUND");
    return rows.getFirst();
  }

  private Decision decision(UUID id) {
    return db.queryForObject("SELECT * FROM ad_decision WHERE id=?", this::decisionRow, id);
  }

  private Campaign campaignRow(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Campaign(
        r.getObject("id", UUID.class),
        r.getString("name"),
        r.getString("country"),
        r.getInt("maturity"),
        r.getString("placement"),
        at(r, "starts_at"),
        at(r, "ends_at"),
        r.getInt("daily_cap"),
        r.getString("creative_url"),
        r.getInt("duration_millis"),
        r.getBoolean("enabled"),
        r.getLong("version"));
  }

  private Decision decisionRow(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Decision(
        r.getObject("id", UUID.class),
        r.getObject("account_id", UUID.class),
        r.getObject("profile_id", UUID.class),
        r.getObject("device_id", UUID.class),
        r.getObject("session_id", UUID.class),
        r.getObject("campaign_id", UUID.class),
        r.getString("state"),
        at(r, "expires_at"),
        r.getString("adapter"),
        r.getString("media_url"),
        r.getInt("duration_millis"),
        r.getLong("version"),
        at(r, "issued_at"));
  }

  private void audit(UUID id, long version, String action, String actor) {
    db.update(
        "INSERT INTO ad_audit(id,campaign_id,actor_hash,action,version) VALUES(?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        hash(actor),
        action,
        version);
  }

  private void outbox(UUID id, long version, Object payload) {
    try {
      db.update(
          "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload)"
              + " VALUES(?,?,?,'ad.impression.v1',?::jsonb)",
          UUID.randomUUID(),
          id,
          version,
          json.writeValueAsString(payload));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid ad event", e);
    }
  }

  private static boolean placement(String s) {
    return s != null && List.of("PRE_ROLL", "MID_ROLL", "POST_ROLL").contains(s);
  }

  private static boolean key(String key) {
    return key != null && key.matches("[A-Za-z0-9_-]{8,128}");
  }

  private static boolean text(String s, int max) {
    return s != null
        && !s.isBlank()
        && s.length() <= max
        && s.chars().noneMatch(Character::isISOControl);
  }

  private static Instant min(Instant... at) {
    return java.util.Arrays.stream(at).min(Instant::compareTo).orElseThrow();
  }

  private static java.sql.Timestamp stamp(Instant at) {
    return java.sql.Timestamp.from(at);
  }

  private static Instant at(java.sql.ResultSet r, String field) throws java.sql.SQLException {
    var at = r.getTimestamp(field);
    return at == null ? null : at.toInstant();
  }

  public static String hash(String s) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static EngagementFailure fail(int status, String code) {
    return new EngagementFailure(status, code);
  }
}
