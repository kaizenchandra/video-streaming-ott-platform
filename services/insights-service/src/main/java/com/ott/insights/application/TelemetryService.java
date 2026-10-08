package com.ott.insights.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TelemetryService {
  public record Consent(
      boolean analytics,
      boolean experiments,
      Instant analyticsSince,
      Instant experimentsSince,
      long version) {}

  public record Sample(
      UUID eventId,
      String kind,
      Instant occurredAt,
      long sequence,
      long startupMillis,
      long bufferMillis,
      long playedMillis,
      long bitrateKbps,
      String regionHint,
      String deviceFamily,
      String cdn,
      String resolution,
      String failureCode) {}

  public record Receipt(int accepted, int duplicate, int ignored) {}

  private static final Set<String> KINDS =
      Set.of(
          "PLAY_STARTED",
          "PLAY_PAUSED",
          "PLAY_COMPLETED",
          "BUFFER_STARTED",
          "BUFFER_ENDED",
          "QUALITY_CHANGED",
          "SEARCH",
          "PLAY_FAILED");
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final TelemetryAudience audience;
  private final Clock clock;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final String secret;

  public TelemetryService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      TelemetryAudience audience,
      Clock clock,
      ObjectMapper json,
      MeterRegistry metrics,
      @Value("${ott.insights.telemetry-secret:}") String secret) {
    this.db = db;
    tx = new TransactionTemplate(manager);
    this.audience = audience;
    this.clock = clock;
    this.json = json;
    this.metrics = metrics;
    this.secret = secret;
  }

  public UUID account(String token) {
    return audience.account(token);
  }

  void lock(UUID account) {
    db.update("INSERT INTO insights_account(account_id) VALUES(?) ON CONFLICT DO NOTHING", account);
    db.queryForObject(
        "SELECT account_id FROM insights_account WHERE account_id=? FOR UPDATE",
        UUID.class,
        account);
  }

  public Consent consent(UUID account) {
    return tx.execute(
        s -> {
          lock(account);
          return current(account);
        });
  }

  Consent current(UUID account) {
    return db.queryForObject(
        "SELECT * FROM insights_account WHERE account_id=?",
        (r, n) ->
            new Consent(
                r.getBoolean("analytics"),
                r.getBoolean("experiments"),
                instant(r.getTimestamp("analytics_since")),
                instant(r.getTimestamp("experiments_since")),
                r.getLong("version")),
        account);
  }

  public Consent consent(UUID account, long version, boolean analytics, boolean experiments) {
    return tx.execute(
        s -> {
          lock(account);
          var old = current(account);
          if (old.version() != version) throw new InsightsFailure(409, "CONSENT_VERSION_CONFLICT");
          Instant now = clock.instant();
          if (!analytics) {
            db.update(
                "DELETE FROM outbox_event WHERE published_at IS NULL AND (aggregate_id=? OR"
                    + " aggregate_id IN(SELECT session_id FROM telemetry_event WHERE"
                    + " account_id=?))",
                account,
                account);
            db.update("DELETE FROM telemetry_event WHERE account_id=?", account);
            db.update("DELETE FROM telemetry_owner_cause WHERE account_id=?", account);
          }
          if (!experiments)
            db.update("DELETE FROM experiment_assignment WHERE account_id=?", account);
          db.update(
              "UPDATE insights_account SET"
                  + " analytics=?,experiments=?,analytics_since=?,experiments_since=?,version=version+1"
                  + " WHERE account_id=?",
              analytics,
              experiments,
              timestamp(analytics ? (old.analytics() ? old.analyticsSince() : now) : null),
              timestamp(experiments ? (old.experiments() ? old.experimentsSince() : now) : null),
              account);
          return current(account);
        });
  }

  public Receipt admit(
      String token, UUID profile, UUID device, String grantToken, List<Sample> samples) {
    if (samples == null
        || samples.isEmpty()
        || samples.size() > 20
        || (grantToken != null && (grantToken.isBlank() || grantToken.length() > 4096)))
      throw new InsightsFailure(400, "INVALID_TELEMETRY");
    for (var sample : samples) validate(sample, grantToken != null);
    var context = audience.verify(token, profile, device, grantToken);
    return tx.execute(
        s -> {
          lock(context.accountId());
          var consent = current(context.accountId());
          int accepted = 0, duplicate = 0, ignored = 0;
          for (var sample : samples) {
            String fingerprint =
                hash(
                    context.accountId()
                        + ":"
                        + profile
                        + ":"
                        + device
                        + ":"
                        + context.sessionId()
                        + ":"
                        + encode(sample));
            if (!consent.analytics() || sample.occurredAt().isBefore(consent.analyticsSince())) {
              ignored++;
              continue;
            }
            if (seen(sample.eventId(), fingerprint)) {
              duplicate++;
              continue;
            }
            if (context.sessionId() != null
                && db.queryForObject(
                        "SELECT count(*) FROM telemetry_event WHERE session_id=? AND"
                            + " sample_sequence=?",
                        Integer.class,
                        context.sessionId(),
                        sample.sequence())
                    > 0) throw new InsightsFailure(409, "TELEMETRY_SEQUENCE_CONFLICT");
            quota(context.accountId());
            store(sample.eventId(), fingerprint, context, sample, "CLIENT");
            accepted++;
          }
          metrics.counter("ott.insights.admitted", "source", "CLIENT").increment(accepted);
          return new Receipt(accepted, duplicate, ignored);
        });
  }

  private void validate(Sample r, boolean grant) {
    if (r == null
        || r.eventId() == null
        || r.occurredAt() == null
        || r.kind() == null
        || r.deviceFamily() == null
        || r.cdn() == null
        || r.resolution() == null
        || r.failureCode() == null
        || !KINDS.contains(r.kind())
        || r.sequence() < 1
        || r.sequence() > Integer.MAX_VALUE
        || r.startupMillis() < 0
        || r.startupMillis() > 120000
        || r.bufferMillis() < 0
        || r.bufferMillis() > r.playedMillis()
        || r.playedMillis() < 0
        || r.playedMillis() > 60000
        || r.bitrateKbps() < 0
        || r.bitrateKbps() > 200000
        || r.regionHint() == null
        || !r.regionHint().matches("[A-Z]{2}|UNKNOWN")
        || !Set.of("TV", "MOBILE", "DESKTOP", "TABLET", "OTHER").contains(r.deviceFamily())
        || !Set.of("CLOUDFRONT", "AKAMAI", "FASTLY", "OTHER").contains(r.cdn())
        || !Set.of("SD", "HD", "FHD", "UHD", "UNKNOWN").contains(r.resolution())
        || !Set.of("NONE", "NETWORK", "DECODE", "MANIFEST", "DRM", "OTHER")
            .contains(r.failureCode())) throw new InsightsFailure(400, "INVALID_TELEMETRY");
    if (r.occurredAt().isAfter(clock.instant().plusSeconds(5))
        || r.occurredAt().isBefore(clock.instant().minusSeconds(86400)))
      throw new InsightsFailure(400, "TELEMETRY_TIME_INVALID");
    if ((!r.kind().equals("SEARCH") && !r.kind().equals("PLAY_FAILED") && !grant)
        || (!r.kind().equals("PLAY_STARTED") && r.startupMillis() != 0)
        || (!r.kind().equals("PLAY_FAILED") && !r.failureCode().equals("NONE"))
        || (r.kind().equals("PLAY_FAILED") && r.failureCode().equals("NONE"))
        || (r.kind().equals("SEARCH")
            && (r.playedMillis() != 0 || r.bufferMillis() != 0 || r.bitrateKbps() != 0)))
      throw new InsightsFailure(400, "INVALID_TELEMETRY");
  }

  public void owner(
      UUID id, String fingerprint, UUID account, String kind, String cause, Instant occurredAt) {
    if (!Set.of("SUBSCRIPTION_PURCHASED", "PAYMENT_FAILED", "AD_IMPRESSION").contains(kind)
        || cause == null
        || cause.length() > 160
        || id == null
        || account == null
        || occurredAt == null
        || !fingerprint.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Invalid owner observation");
    tx.executeWithoutResult(
        s -> {
          lock(account);
          if (seen(id, fingerprint)) return;
          var consent = current(account);
          if (!consent.analytics()
              || occurredAt.isBefore(consent.analyticsSince())
              || occurredAt.isBefore(clock.instant().minusSeconds(30L * 86400))
              || occurredAt.isAfter(clock.instant().plusSeconds(5))) return;
          if (db.update(
                  "INSERT INTO telemetry_owner_cause(kind,cause,account_id,occurred_at)"
                      + " VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                  kind,
                  cause,
                  account,
                  timestamp(occurredAt))
              == 0) return;
          quota(account);
          store(
              id,
              fingerprint,
              new TelemetryAudience.Context(account, null, null, null),
              new Sample(
                  id,
                  kind,
                  occurredAt,
                  0,
                  0,
                  0,
                  0,
                  0,
                  "UNKNOWN",
                  "OTHER",
                  "OTHER",
                  "UNKNOWN",
                  "NONE"),
              "OWNER");
          metrics.counter("ott.insights.admitted", "source", "OWNER").increment();
        });
  }

  private boolean seen(UUID id, String fingerprint) {
    int n =
        db.update(
            "INSERT INTO telemetry_receipt VALUES(?,?,?) ON CONFLICT DO NOTHING",
            id,
            fingerprint,
            timestamp(clock.instant()));
    if (n == 1) return false;
    String stored =
        db.queryForObject(
            "SELECT fingerprint FROM telemetry_receipt WHERE event_id=?", String.class, id);
    if (!fingerprint.equals(stored)) throw new InsightsFailure(409, "TELEMETRY_EVENT_CONFLICT");
    return true;
  }

  private void quota(UUID account) {
    var day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
    db.update(
        "INSERT INTO telemetry_daily_quota VALUES(?,?,0) ON CONFLICT DO NOTHING", account, day);
    if (db.update(
            "UPDATE telemetry_daily_quota SET count=count+1 WHERE account_id=? AND day=? AND"
                + " count<10000",
            account,
            day)
        == 0) throw new InsightsFailure(429, "TELEMETRY_DAILY_LIMIT");
  }

  private void store(
      UUID id, String fingerprint, TelemetryAudience.Context c, Sample r, String trust) {
    db.update(
        "INSERT INTO"
            + " telemetry_event(id,account_id,profile_id,device_id,session_id,sample_sequence,kind,source_trust,occurred_at,startup_ms,buffer_ms,played_ms,bitrate_kbps,region_hint,device_family,cdn,resolution,failure_code,fingerprint)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        c.accountId(),
        c.profileId(),
        c.deviceId(),
        c.sessionId(),
        c.sessionId() == null ? null : r.sequence(),
        r.kind(),
        trust,
        timestamp(r.occurredAt()),
        r.startupMillis(),
        r.bufferMillis(),
        r.playedMillis(),
        r.bitrateKbps(),
        r.regionHint(),
        r.deviceFamily(),
        r.cdn(),
        r.resolution(),
        r.failureCode(),
        fingerprint);
    long version =
        db.queryForObject(
            "UPDATE insights_account SET next_event_version=next_event_version+1 WHERE account_id=?"
                + " RETURNING next_event_version",
            Long.class,
            c.accountId());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("accountRef", ref(c.accountId()));
    p.put("profileRef", c.profileId() == null ? null : ref(c.profileId()));
    p.put("sessionRef", c.sessionId() == null ? null : ref(c.sessionId()));
    p.put("eventKind", r.kind());
    p.put("sourceTrust", trust);
    p.put("sourceOccurredAt", r.occurredAt().toEpochMilli());
    dimensions(p, r);
    outbox(c.accountId(), version, "analytics.received.v1", p);
    if (c.sessionId() != null && !r.kind().equals("SEARCH")) {
      var q = new LinkedHashMap<String, Object>();
      q.put("sessionId", c.sessionId().toString());
      q.put("sampleSequence", r.sequence());
      q.put("eventKind", r.kind());
      dimensions(q, r);
      outbox(c.sessionId(), r.sequence(), "qoe.received.v1", q);
    }
  }

  private void dimensions(Map<String, Object> p, Sample r) {
    p.put("startupMillis", r.startupMillis());
    p.put("bufferMillis", r.bufferMillis());
    p.put("playedMillis", r.playedMillis());
    p.put("bitrateKbps", r.bitrateKbps());
    p.put("regionHint", r.regionHint());
    p.put("deviceFamily", r.deviceFamily());
    p.put("cdn", r.cdn());
    p.put("resolution", r.resolution());
    p.put("failureCode", r.failureCode());
  }

  private void outbox(UUID aggregate, long version, String topic, Map<String, Object> payload) {
    db.update(
        "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload,created_at)"
            + " VALUES(?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        aggregate,
        version,
        topic,
        encode(payload),
        timestamp(clock.instant()));
  }

  String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid telemetry model", e);
    }
  }

  public String ref(UUID id) {
    if (secret == null || !secret.matches("[a-f0-9]{64}"))
      throw new InsightsFailure(503, "TELEMETRY_PRIVACY_KEY_UNAVAILABLE");
    return hmac(secret, id.toString());
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hmac(String secret, String value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(HexFormat.of().parseHex(secret), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Invalid pseudonym configuration", e);
    }
  }

  static java.sql.Timestamp timestamp(Instant i) {
    return i == null ? null : java.sql.Timestamp.from(i);
  }

  static Instant instant(java.sql.Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  public List<Map<String, Object>> summary(Instant from, Instant to) {
    if (from == null
        || to == null
        || !to.isAfter(from)
        || to.isAfter(clock.instant().plusSeconds(5))
        || from.isBefore(clock.instant().minusSeconds(30L * 86400))
        || to.isAfter(from.plusSeconds(7L * 86400)))
      throw new InsightsFailure(400, "INVALID_ANALYTICS_WINDOW");
    return db.queryForList(
        """
        SELECT region_hint,device_family,cdn,resolution,count(*) AS observations,
        count(*) FILTER(WHERE kind='PLAY_STARTED') AS starts,count(*) FILTER(WHERE kind='PLAY_FAILED') AS failures,
        count(*) FILTER(WHERE kind='QUALITY_CHANGED') AS quality_switches,
        coalesce(count(*) FILTER(WHERE kind='PLAY_FAILED')::numeric/nullif(count(*) FILTER(WHERE kind IN('PLAY_FAILED','PLAY_STARTED')),0),0) AS failure_observation_ratio,
        coalesce(percentile_cont(0.95) WITHIN GROUP(ORDER BY startup_ms) FILTER(WHERE kind='PLAY_STARTED'),0) AS startup_p95_ms,
        coalesce(sum(buffer_ms)::numeric/nullif(sum(played_ms),0),0) AS rebuffer_ratio,
        coalesce(avg(bitrate_kbps) FILTER(WHERE bitrate_kbps>0),0) AS bitrate_avg_kbps
        FROM telemetry_event WHERE source_trust='CLIENT' AND occurred_at>=? AND occurred_at<?
        GROUP BY region_hint,device_family,cdn,resolution ORDER BY observations DESC,region_hint,device_family,cdn,resolution LIMIT 200
        """,
        timestamp(from),
        timestamp(to));
  }

  @Scheduled(
      fixedDelayString = "${ott.insights.retention-delay-ms:3600000}",
      initialDelayString = "${ott.insights.retention-initial-delay-ms:180000}")
  public void retention() {
    Instant cutoff = clock.instant().minusSeconds(30L * 86400);
    tx.executeWithoutResult(
        s -> {
          db.update(
              "DELETE FROM telemetry_event WHERE id IN(SELECT id FROM telemetry_event WHERE"
                  + " occurred_at<? ORDER BY occurred_at,id LIMIT 1000)",
              timestamp(cutoff));
          db.update(
              "DELETE FROM telemetry_receipt WHERE event_id IN(SELECT event_id FROM"
                  + " telemetry_receipt WHERE received_at<? ORDER BY received_at,event_id LIMIT"
                  + " 1000)",
              timestamp(cutoff));
          db.update(
              "DELETE FROM telemetry_owner_cause WHERE (kind,cause) IN(SELECT kind,cause FROM"
                  + " telemetry_owner_cause WHERE occurred_at<? ORDER BY occurred_at LIMIT 1000)",
              timestamp(cutoff));
          db.update(
              "DELETE FROM outbox_event WHERE event_id IN(SELECT event_id FROM outbox_event WHERE"
                  + " published_at<? ORDER BY published_at,event_id LIMIT 1000)",
              timestamp(cutoff));
          db.update(
              "DELETE FROM telemetry_daily_quota WHERE (account_id,day) IN(SELECT account_id,day"
                  + " FROM telemetry_daily_quota WHERE day<? ORDER BY day,account_id LIMIT 1000)",
              cutoff.atZone(ZoneOffset.UTC).toLocalDate());
        });
  }
}
