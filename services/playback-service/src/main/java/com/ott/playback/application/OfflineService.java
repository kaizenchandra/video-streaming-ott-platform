package com.ott.playback.application;

import com.ott.playback.adapter.outbound.security.PlaybackTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OfflineService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Eligibility eligibility;
  private final PlaybackTokens tokens;
  private final GrantProvider provider;
  private final Clock clock;
  private final int limit;

  public OfflineService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      Eligibility eligibility,
      PlaybackTokens tokens,
      GrantProvider provider,
      Clock clock,
      @Value("${ott.offline.max-active:5}") int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid offline policy");
    this.db = db;
    tx = new TransactionTemplate(manager);
    this.eligibility = eligibility;
    this.tokens = tokens;
    this.provider = provider;
    this.clock = clock;
    this.limit = limit;
  }

  public record Authorization(
      UUID downloadId,
      UUID deviceId,
      Instant licenseExpiresAt,
      Instant deliveryExpiresAt,
      String offlineToken,
      GrantProvider.Grant delivery) {}

  private record Row(
      UUID id,
      UUID account,
      UUID profile,
      UUID device,
      UUID content,
      String prefix,
      Instant expires,
      boolean revoked,
      String fingerprint,
      long epoch,
      String country,
      long memberEpoch) {}

  public Authorization authorize(
      Eligibility.Identity i, UUID profile, UUID device, UUID content, String country, String key) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) throw fail(400, "INVALID_INPUT");
    var check = eligibility.check(i, profile, device, content, country, true);
    if (!check.offlineAllowed()) throw fail(403, "OFFLINE_NOT_ALLOWED");
    String fingerprint =
        PlaybackService.hash(profile + ":" + device + ":" + content + ":" + country);
    return tx.execute(
        t -> {
          lock(check.accountId());
          var prior =
              db.query(
                  "SELECT * FROM offline_grant WHERE account_id=? AND idempotency_key=?",
                  this::row,
                  check.accountId(),
                  key);
          Row grant;
          if (prior.isEmpty()) {
            int count =
                db.queryForObject(
                    "SELECT count(*) FROM offline_grant WHERE account_id=? AND NOT revoked AND"
                        + " expires_at>?",
                    Integer.class,
                    check.accountId(),
                    stamp(clock.instant()));
            if (count >= limit) throw fail(409, "OFFLINE_LIMIT_REACHED");
            Instant expiry = min(clock.instant().plusSeconds(86400), check.expiresAt());
            if (!expiry.isAfter(clock.instant())) throw fail(403, "OFFLINE_NOT_ALLOWED");
            UUID id = UUID.randomUUID();
            db.update(
                "INSERT INTO"
                    + " offline_grant(id,account_id,profile_id,device_id,content_id,country,idempotency_key,fingerprint,origin_prefix,expires_at,member_epoch)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id,
                check.accountId(),
                profile,
                device,
                content,
                country,
                key,
                fingerprint,
                check.originPrefix(),
                stamp(expiry),
                check.memberEpoch());
            grant = get(id);
          } else {
            grant = prior.getFirst();
            if (!grant.fingerprint().equals(fingerprint)) throw fail(409, "IDEMPOTENCY_CONFLICT");
            if (grant.revoked() || !grant.expires().isAfter(clock.instant()))
              throw fail(409, "DOWNLOAD_EXPIRED");
          }
          if (grant.memberEpoch() != check.memberEpoch()
              || !grant.prefix().equals(check.originPrefix()))
            throw fail(409, "DOWNLOAD_REAUTH_REQUIRED");
          Instant expiry = min(grant.expires(), check.expiresAt());
          if (!expiry.isAfter(clock.instant())) throw fail(403, "OFFLINE_NOT_ALLOWED");
          Instant deliveryExpiry = min(expiry, clock.instant().plusSeconds(60));
          String token =
              tokens.issueOffline(
                  grant.account(),
                  grant.profile(),
                  grant.device(),
                  grant.id(),
                  grant.content(),
                  expiry);
          var delivery =
              provider.authorize(grant.id(), grant.device(), grant.prefix(), deliveryExpiry, token);
          db.update(
              "INSERT INTO offline_audit(id,grant_id,token_hash,expires_at) VALUES(?,?,?,?)",
              UUID.randomUUID(),
              grant.id(),
              PlaybackService.hash(token),
              stamp(expiry));
          return new Authorization(
              grant.id(), grant.device(), expiry, deliveryExpiry, token, delivery);
        });
  }

  public void revoke(Eligibility.Identity i, UUID id) {
    UUID account = eligibility.account(i);
    tx.executeWithoutResult(
        t -> {
          lock(account);
          Row r = get(id);
          if (!r.account().equals(account)) throw fail(404, "DOWNLOAD_NOT_FOUND");
          db.update("UPDATE offline_grant SET revoked=TRUE WHERE id=?", id);
        });
  }

  public PlaybackService.GrantContext context(String token, UUID device) {
    UUID id = validate(token, device);
    var r = get(id);
    return new PlaybackService.GrantContext(
        id, r.account(), r.profile(), r.content(), r.device(), r.epoch());
  }

  public UUID validate(String token, UUID device) {
    UUID id = tokens.verifyOffline(token, device);
    Row r = get(id);
    if (r.revoked() || !r.expires().isAfter(clock.instant()) || !r.device().equals(device))
      throw fail(403, "OFFLINE_TOKEN_DENIED");
    int count =
        db.queryForObject(
            "SELECT count(*) FROM offline_audit WHERE grant_id=? AND token_hash=? AND expires_at>?",
            Integer.class,
            id,
            PlaybackService.hash(token),
            stamp(clock.instant()));
    if (count != 1) throw fail(403, "OFFLINE_TOKEN_DENIED");
    if (!eligibility.issuedAllowed(
        r.account(),
        r.profile(),
        r.device(),
        r.content(),
        r.country(),
        r.prefix(),
        r.memberEpoch())) throw fail(403, "OFFLINE_TOKEN_DENIED");
    return id;
  }

  private void lock(UUID a) {
    db.update("INSERT INTO account_quota(account_id) VALUES(?) ON CONFLICT DO NOTHING", a);
    db.queryForObject(
        "SELECT account_id FROM account_quota WHERE account_id=? FOR UPDATE", UUID.class, a);
  }

  private Row get(UUID id) {
    var results = db.query("SELECT * FROM offline_grant WHERE id=?", this::row, id);
    if (results.isEmpty()) throw fail(404, "DOWNLOAD_NOT_FOUND");
    return results.getFirst();
  }

  private Row row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Row(
        r.getObject("id", UUID.class),
        r.getObject("account_id", UUID.class),
        r.getObject("profile_id", UUID.class),
        r.getObject("device_id", UUID.class),
        r.getObject("content_id", UUID.class),
        r.getString("origin_prefix"),
        r.getTimestamp("expires_at").toInstant(),
        r.getBoolean("revoked"),
        r.getString("fingerprint"),
        r.getLong("epoch"),
        r.getString("country"),
        r.getLong("member_epoch"));
  }

  private static java.sql.Timestamp stamp(Instant at) {
    return java.sql.Timestamp.from(at);
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private static PlaybackFailure fail(int status, String code) {
    return new PlaybackFailure(status, code, code.replace('_', ' '));
  }
}
