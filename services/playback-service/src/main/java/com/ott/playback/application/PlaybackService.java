package com.ott.playback.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.playback.adapter.outbound.security.PlaybackTokens;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PlaybackService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Eligibility eligibility;
  private final StreamLeases leases;
  private final PlaybackTokens tokens;
  private final GrantProvider provider;
  private final Clock clock;
  private final ObjectMapper json;
  private final MeterRegistry metrics;

  public PlaybackService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      Eligibility eligibility,
      StreamLeases leases,
      PlaybackTokens tokens,
      GrantProvider provider,
      Clock clock,
      ObjectMapper json,
      MeterRegistry metrics) {
    this.db = db;
    tx = new TransactionTemplate(manager);
    this.eligibility = eligibility;
    this.leases = leases;
    this.tokens = tokens;
    this.provider = provider;
    this.clock = clock;
    this.json = json;
    this.metrics = metrics;
  }

  public record Session(
      UUID id,
      UUID accountId,
      UUID profileId,
      UUID deviceId,
      UUID contentId,
      String country,
      String state,
      Instant expiresAt,
      Instant grantExpiresAt,
      String originPrefix,
      String fingerprint,
      long epoch,
      String targetKind,
      UUID targetId) {}

  public record Authorization(
      UUID sessionId, Instant expiresAt, String playbackToken, GrantProvider.Grant delivery) {}

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  public Authorization start(
      Eligibility.Identity identity,
      UUID profile,
      UUID device,
      UUID content,
      String country,
      String key) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) throw fail(400, "INVALID_INPUT");
    var check = eligibility.check(identity, profile, device, content, country, false);
    return startChecked(
        identity, profile, device, content, country, key, check, "VOD", content, null);
  }

  public Authorization startLive(
      Eligibility.Identity identity,
      UUID profile,
      UUID device,
      UUID event,
      String country,
      String key,
      String mode,
      Instant seek) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) throw fail(400, "INVALID_INPUT");
    if (mode == null
        || !java.util.Set.of("LIVE", "DVR", "REPLAY").contains(mode)
        || mode.equals("DVR") && seek == null
        || !mode.equals("DVR") && seek != null) throw fail(400, "INVALID_LIVE_INPUT");
    var result = eligibility.checkLive(identity, profile, device, event, country, mode, seek);
    if (result.contentId() == null) throw fail(503, "LIVE_UNAVAILABLE");
    return startChecked(
        identity,
        profile,
        device,
        result.contentId(),
        country,
        key,
        result.authorization(),
        mode,
        event,
        seek);
  }

  private Authorization startChecked(
      Eligibility.Identity identity,
      UUID profile,
      UUID device,
      UUID content,
      String country,
      String key,
      Eligibility.Check check,
      String targetKind,
      UUID targetId,
      Instant seek) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) throw fail(400, "INVALID_INPUT");
    validate(check, targetKind, targetId);
    // Preserve existing VOD idempotency fingerprints during the additive rollout.
    String fingerprint =
        hash(
            profile
                + ":"
                + device
                + ":"
                + content
                + ":"
                + country
                + (targetKind.equals("VOD") ? "" : ":" + targetKind + ":" + targetId + ":" + seek));
    Session session =
        tx.execute(
            t -> {
              lock(check.accountId());
              var matches =
                  db.query(
                      "SELECT * FROM playback_session WHERE account_id=? AND idempotency_key=?",
                      this::row,
                      check.accountId(),
                      key);
              if (!matches.isEmpty()) {
                var prior = matches.getFirst();
                if (!prior.fingerprint().equals(fingerprint))
                  throw fail(409, "IDEMPOTENCY_CONFLICT");
                if (!prior.originPrefix().equals(check.originPrefix())
                    || !prior.contentId().equals(content)
                    || !prior.targetKind().equals(targetKind)
                    || !prior.targetId().equals(targetId))
                  throw fail(409, "CONTENT_VERSION_CHANGED");
                if (prior.state().equals("ENDED") || !prior.expiresAt().isAfter(now()))
                  throw fail(409, "SESSION_EXPIRED");
                return prior;
              }
              if (count(check.accountId()) >= check.maxStreams())
                throw fail(409, "CONCURRENT_STREAM_LIMIT_REACHED");
              var id = UUID.randomUUID();
              Instant end = min(now().plusSeconds(90), check.expiresAt());
              db.update(
                  "INSERT INTO"
                      + " playback_session(id,account_id,profile_id,device_id,content_id,country,idempotency_key,fingerprint,state,expires_at,origin_prefix,target_kind,target_id)"
                      + " VALUES(?,?,?,?,?,?,?,?,'PENDING',?,?,?,?)",
                  id,
                  check.accountId(),
                  profile,
                  device,
                  content,
                  country,
                  key,
                  fingerprint,
                  stamp(end),
                  check.originPrefix(),
                  targetKind,
                  targetId);
              return get(id);
            });
    try {
      if (!leases.acquire(
          session.accountId(), session.id(), check.maxStreams(), duration(session.expiresAt())))
        throw fail(409, "CONCURRENT_STREAM_LIMIT_REACHED");
    } catch (org.springframework.dao.DataAccessException e) {
      cancelUnissued(session);
      metrics.counter("ott.playback.lease.failure").increment();
      throw fail(503, "PLAYBACK_UNAVAILABLE");
    } catch (PlaybackFailure e) {
      cancelUnissued(session);
      throw e;
    }
    return tx.execute(
        t -> {
          lock(session.accountId());
          var current = get(session.id());
          if (current.state().equals("ENDED") || !current.expiresAt().isAfter(now()))
            throw fail(409, "SESSION_EXPIRED");
          Instant end =
              current.grantExpiresAt() == null
                  ? min(now().plusSeconds(60), current.expiresAt(), check.expiresAt())
                  : min(current.grantExpiresAt(), check.expiresAt());
          if (!end.isAfter(now())) throw fail(409, "SESSION_EXPIRED");
          var result = grant(current, end);
          if (current.state().equals("PENDING")) {
            db.update(
                "UPDATE playback_session SET state='ACTIVE',grant_expires_at=? WHERE id=?",
                stamp(end),
                current.id());
            event(current, "playback.started.v1", 1);
          }
          metrics.counter("ott.playback.authorized").increment();
          return result;
        });
  }

  public Authorization heartbeat(Eligibility.Identity identity, UUID id, String country) {
    UUID account = eligibility.account(identity);
    Session before = owned(account, id);
    if (!before.country().equals(country)) throw fail(403, "REGION_CHANGED");
    Eligibility.Check check;
    if (before.targetKind().equals("VOD"))
      check =
          eligibility.check(
              identity, before.profileId(), before.deviceId(), before.contentId(), country, false);
    else {
      var result =
          eligibility.checkLive(
              identity,
              before.profileId(),
              before.deviceId(),
              before.targetId(),
              country,
              before.targetKind(),
              null);
      if (!before.contentId().equals(result.contentId()))
        throw fail(409, "CONTENT_VERSION_CHANGED");
      check = result.authorization();
    }
    validate(check, before.targetKind(), before.targetId());
    if (!account.equals(check.accountId()) || !before.originPrefix().equals(check.originPrefix()))
      throw fail(409, "CONTENT_VERSION_CHANGED");
    Session reserved =
        tx.execute(
            t -> {
              lock(account);
              var s = owned(account, id);
              if (!s.state().equals("ACTIVE") || !s.expiresAt().isAfter(now()))
                throw fail(409, "SESSION_EXPIRED");
              if (count(account) > check.maxStreams())
                throw fail(403, "CONCURRENT_STREAM_LIMIT_REACHED");
              db.update(
                  "UPDATE playback_session SET expires_at=? WHERE id=?",
                  stamp(min(now().plusSeconds(90), check.expiresAt())),
                  id);
              return get(id);
            });
    try {
      if (!leases.renew(account, id, duration(reserved.expiresAt()))) throw fail(409, "LEASE_LOST");
    } catch (org.springframework.dao.DataAccessException e) {
      throw fail(503, "PLAYBACK_UNAVAILABLE");
    }
    return tx.execute(
        t -> {
          lock(account);
          var s = owned(account, id);
          if (!s.state().equals("ACTIVE") || !s.expiresAt().isAfter(now()))
            throw fail(409, "SESSION_EXPIRED");
          var end = min(now().plusSeconds(60), s.expiresAt(), check.expiresAt());
          var result = grant(s, end);
          db.update("UPDATE playback_session SET grant_expires_at=? WHERE id=?", stamp(end), id);
          return result;
        });
  }

  public void end(Eligibility.Identity identity, UUID id) {
    UUID account = eligibility.account(identity);
    tx.executeWithoutResult(
        t -> {
          lock(account);
          var s = owned(account, id);
          if (!s.state().equals("ENDED")) {
            db.update("UPDATE playback_session SET state='ENDED' WHERE id=?", id);
            event(s, "playback.ended.v1", 2);
          }
        });
    try {
      leases.release(account, id);
    } catch (org.springframework.dao.DataAccessException e) {
      metrics.counter("ott.playback.lease.failure").increment();
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${ott.sessions.expiry-delay-ms:30000}")
  public void expire() {
    var accounts =
        db.queryForList(
            "SELECT DISTINCT account_id FROM playback_session WHERE state IN ('PENDING','ACTIVE')"
                + " AND expires_at<=? LIMIT 50",
            UUID.class,
            stamp(now()));
    for (var account : accounts)
      tx.executeWithoutResult(
          t -> {
            lock(account);
            var expired =
                db.query(
                    "SELECT * FROM playback_session WHERE account_id=? AND state IN"
                        + " ('PENDING','ACTIVE') AND expires_at<=?",
                    this::row,
                    account,
                    stamp(now()));
            for (var s : expired) {
              db.update("UPDATE playback_session SET state='ENDED' WHERE id=?", s.id());
              if (s.state().equals("ACTIVE")) event(s, "playback.ended.v1", 2);
            }
          });
  }

  public Session owned(UUID account, UUID id) {
    var s = get(id);
    if (!s.accountId().equals(account)) throw fail(404, "SESSION_NOT_FOUND");
    return s;
  }

  public record GrantContext(
      UUID sessionId, UUID accountId, UUID profileId, UUID contentId, UUID deviceId, long epoch) {}

  public GrantContext context(String token, UUID device) {
    UUID id = validateToken(token, device);
    var s = get(id);
    return new GrantContext(
        id, s.accountId(), s.profileId(), s.contentId(), s.deviceId(), s.epoch());
  }

  public record AdContext(
      UUID sessionId,
      UUID accountId,
      UUID profileId,
      UUID deviceId,
      String country,
      Instant expiresAt) {}

  public AdContext adContext(String token, UUID device) {
    UUID id = validateToken(token, device);
    var s = get(id);
    return new AdContext(
        id,
        s.accountId(),
        s.profileId(),
        s.deviceId(),
        s.country(),
        db.queryForObject(
                "SELECT expires_at FROM grant_audit WHERE session_id=? AND token_hash=?",
                java.sql.Timestamp.class,
                id,
                hash(token))
            .toInstant());
  }

  public UUID validateToken(String token, UUID device) {
    UUID id = tokens.verify(token, device);
    var s = get(id);
    if (!s.deviceId().equals(device)
        || !s.state().equals("ACTIVE")
        || s.grantExpiresAt() == null
        || !s.grantExpiresAt().isAfter(now())) throw fail(403, "PLAYBACK_TOKEN_DENIED");
    Integer grants =
        db.queryForObject(
            "SELECT count(*) FROM grant_audit WHERE session_id=? AND token_hash=? AND expires_at>?",
            Integer.class,
            id,
            hash(token),
            stamp(now()));
    if (grants == null || grants != 1) throw fail(403, "PLAYBACK_TOKEN_DENIED");
    return id;
  }

  private Authorization grant(Session s, Instant end) {
    String token =
        tokens.issue(s.accountId(), s.profileId(), s.deviceId(), s.id(), s.contentId(), end);
    var delivery = provider.authorize(s.id(), s.deviceId(), s.originPrefix(), end, token);
    db.update(
        "INSERT INTO grant_audit(id,session_id,token_hash,expires_at) VALUES(?,?,?,?)",
        UUID.randomUUID(),
        s.id(),
        hash(token),
        stamp(end));
    return new Authorization(s.id(), end, token, delivery);
  }

  private void cancelUnissued(Session s) {
    tx.executeWithoutResult(
        t -> {
          lock(s.accountId());
          db.update(
              "UPDATE playback_session SET state='ENDED',expires_at=? WHERE id=? AND"
                  + " state='PENDING' AND grant_expires_at IS NULL",
              stamp(now()),
              s.id());
        });
  }

  private void lock(UUID account) {
    db.update("INSERT INTO account_quota(account_id) VALUES(?) ON CONFLICT DO NOTHING", account);
    db.queryForObject(
        "SELECT account_id FROM account_quota WHERE account_id=? FOR UPDATE", UUID.class, account);
  }

  private int count(UUID account) {
    return db.queryForObject(
        "SELECT count(*) FROM playback_session WHERE account_id=? AND expires_at>? AND"
            + " (state<>'ENDED' OR grant_expires_at>?)",
        Integer.class,
        account,
        stamp(now()),
        stamp(now()));
  }

  private Session get(UUID id) {
    var found = db.query("SELECT * FROM playback_session WHERE id=?", this::row, id);
    if (found.isEmpty()) throw fail(404, "SESSION_NOT_FOUND");
    return found.getFirst();
  }

  private Session row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    var grant = r.getTimestamp("grant_expires_at");
    return new Session(
        r.getObject("id", UUID.class),
        r.getObject("account_id", UUID.class),
        r.getObject("profile_id", UUID.class),
        r.getObject("device_id", UUID.class),
        r.getObject("content_id", UUID.class),
        r.getString("country"),
        r.getString("state"),
        r.getTimestamp("expires_at").toInstant(),
        grant == null ? null : grant.toInstant(),
        r.getString("origin_prefix"),
        r.getString("fingerprint"),
        r.getLong("epoch"),
        r.getString("target_kind"),
        r.getObject("target_id", UUID.class));
  }

  private void event(Session s, String topic, long version) {
    var payload =
        new java.util.HashMap<String, Object>(
            Map.of(
                "accountId",
                s.accountId().toString(),
                "profileId",
                s.profileId().toString(),
                "sessionId",
                s.id().toString(),
                "contentId",
                s.contentId().toString()));
    if (version == 1) payload.put("deviceId", s.deviceId().toString());
    else payload.put("endedAt", now().toEpochMilli());
    try {
      db.update(
          "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload)"
              + " VALUES(?,?,?,?,?::jsonb)",
          UUID.randomUUID(),
          s.id(),
          version,
          topic,
          json.writeValueAsString(payload));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot encode playback event", e);
    }
  }

  private void validate(Eligibility.Check c, String kind, UUID target) {
    if (c.maxStreams() < 1
        || c.maxStreams() > 10
        || !c.expiresAt().isAfter(now())
        || c.originPrefix() == null
        || !(java.util.Set.of("LIVE", "DVR").contains(kind)
            ? c.originPrefix().matches("live/" + target + "/e[1-9][0-9]*/")
            : c.originPrefix().matches("vod/[a-f0-9-]{36}/v[1-9][0-9]*/")))
      throw fail(403, "PLAYBACK_DENIED");
  }

  private long duration(Instant expiry) {
    return Math.max(1, java.time.Duration.between(now(), expiry).toMillis());
  }

  private static java.sql.Timestamp stamp(Instant instant) {
    return java.sql.Timestamp.from(instant);
  }

  private static Instant min(Instant... values) {
    return java.util.Arrays.stream(values).min(Instant::compareTo).orElseThrow();
  }

  public static String hash(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PlaybackFailure fail(int status, String code) {
    return new PlaybackFailure(status, code, code.replace('_', ' '));
  }
}
