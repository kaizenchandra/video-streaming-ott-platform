package com.ott.member.application;

import com.ott.member.domain.model.MemberModels.Identity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ParentalService {
  public record Status(boolean enabled, long version) {}

  public record Grant(String token, Instant expiresAt) {}

  private final JdbcTemplate db;
  private final MemberRepository members;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final BCryptPasswordEncoder hashes = new BCryptPasswordEncoder(10);
  private final java.security.SecureRandom random = new java.security.SecureRandom();

  public ParentalService(
      JdbcTemplate db, MemberRepository members, Clock clock, PlatformTransactionManager manager) {
    this.db = db;
    this.members = members;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public Status status(Identity i) {
    return status(account(i));
  }

  private UUID account(Identity i) {
    return members
        .account(i)
        .orElseThrow(() -> new MemberFailure(404, "RESOURCE_NOT_FOUND", "Account required"))
        .id();
  }

  private Status status(UUID a) {
    return db
        .query(
            "SELECT pin_hash IS NOT NULL,revision FROM parental_control WHERE account_id=?",
            (r, n) -> new Status(r.getBoolean(1), r.getLong(2)),
            a)
        .stream()
        .findFirst()
        .orElse(new Status(false, 0));
  }

  private void lock(UUID a) {
    members.lockAccount(a);
    db.update("INSERT INTO parental_control(account_id) VALUES(?) ON CONFLICT DO NOTHING", a);
  }

  public long guardian(Identity i, String pin) {
    return check(account(i), pin);
  }

  public void requireRevision(UUID account, long revision) {
    if (status(account).version() != revision) throw fail(409, "PARENTAL_CONFIGURATION_CHANGED");
  }

  public Status configure(Identity i, long version, String oldPin, String newPin) {
    recent(i);
    UUID a = account(i);
    if (newPin != null && !newPin.matches("[0-9]{4,8}")) throw fail(400, "INVALID_PARENTAL_PIN");
    guardian(i, oldPin);
    String encoded = newPin == null ? null : hashes.encode(newPin);
    return tx.execute(
        s -> {
          lock(a);
          var old = status(a);
          if (old.version() != version) throw fail(409, "PARENTAL_VERSION_CONFLICT");
          db.update(
              "UPDATE parental_control SET"
                  + " pin_hash=?,revision=revision+1,failures=0,blocked_until=NULL WHERE"
                  + " account_id=?",
              encoded,
              a);
          db.update("UPDATE account SET security_epoch=security_epoch+1 WHERE id=?", a);
          db.update("DELETE FROM profile_selection WHERE account_id=?", a);
          db.update(
              "INSERT INTO member_security_audit VALUES(?,?,?,?,?)",
              UUID.randomUUID(),
              a,
              hash(i.issuer() + ":" + i.subject()),
              "PARENTAL_CHANGED",
              java.sql.Timestamp.from(clock.instant()));
          return status(a);
        });
  }

  private record Checked(String rejected, long revision) {}

  private long check(UUID a, String pin) {
    Checked result =
        tx.execute(
            s -> {
              lock(a);
              var row =
                  db.queryForList(
                          "SELECT pin_hash,revision,failures,blocked_until FROM parental_control"
                              + " WHERE account_id=?",
                          a)
                      .getFirst();
              long revision = ((Number) row.get("revision")).longValue();
              if (row.get("pin_hash") == null) return new Checked(null, revision);
              var blocked = (java.sql.Timestamp) row.get("blocked_until");
              if (blocked != null && blocked.toInstant().isAfter(clock.instant()))
                return new Checked("PARENTAL_PIN_LOCKED", revision);
              if (pin == null
                  || !pin.matches("[0-9]{4,8}")
                  || !hashes.matches(pin, row.get("pin_hash").toString())) {
                int attempts = blocked == null ? ((Number) row.get("failures")).intValue() + 1 : 1;
                db.update(
                    "UPDATE parental_control SET failures=?,blocked_until=? WHERE account_id=?",
                    attempts,
                    attempts >= 5
                        ? java.sql.Timestamp.from(clock.instant().plusSeconds(900))
                        : null,
                    a);
                return new Checked(
                    attempts >= 5 ? "PARENTAL_PIN_LOCKED" : "PARENTAL_PIN_DENIED", revision);
              }
              db.update(
                  "UPDATE parental_control SET failures=0,blocked_until=NULL WHERE account_id=?",
                  a);
              return new Checked(null, revision);
            });
    if (result.rejected() != null) throw fail(403, result.rejected());
    return result.revision();
  }

  public Grant unlock(Identity i, UUID profile, UUID device, String pin) {
    UUID a = account(i);
    long checked = check(a, pin);
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expires = clock.instant().plusSeconds(1800);
    return tx.execute(
        s -> {
          lock(a);
          var p = members.profile(a, profile).orElseThrow(() -> fail(404, "PROFILE_NOT_FOUND"));
          var d = members.device(a, device).orElseThrow(() -> fail(403, "DEVICE_NOT_ALLOWED"));
          if (!d.status().equals("ACTIVE")) throw fail(403, "DEVICE_NOT_ALLOWED");
          var current = status(a);
          // The PIN check and grant must fence a concurrent configuration change.
          if (current.version() != checked) throw fail(409, "PARENTAL_CONFIGURATION_CHANGED");
          db.update(
              "INSERT INTO"
                  + " profile_selection(account_id,profile_id,device_id,token_hash,expires_at,pin_revision,profile_version)"
                  + " VALUES(?,?,?,?,?,?,?) ON CONFLICT(account_id,profile_id,device_id) DO UPDATE"
                  + " SET token_hash=excluded.token_hash,expires_at=excluded.expires_at,pin_revision=excluded.pin_revision,profile_version=excluded.profile_version",
              a,
              profile,
              device,
              hash(token),
              java.sql.Timestamp.from(expires),
              current.version(),
              p.version());
          return new Grant(token, expires);
        });
  }

  public boolean allowed(
      UUID a, UUID p, UUID d, long profileVersion, boolean kids, int maturity, String proof) {
    if (kids || maturity <= 12 || !status(a).enabled()) return true;
    if (proof == null || !proof.matches("[A-Za-z0-9_-]{43}")) return false;
    return db.queryForObject(
            "SELECT count(*) FROM profile_selection s JOIN parental_control c USING(account_id)"
                + " WHERE s.account_id=? AND profile_id=? AND device_id=? AND token_hash=? AND"
                + " expires_at>? AND s.pin_revision=c.revision AND s.profile_version=?",
            Integer.class,
            a,
            p,
            d,
            hash(proof),
            java.sql.Timestamp.from(clock.instant()),
            profileVersion)
        == 1;
  }

  public static void recent(Identity i, Clock clock) {
    Instant at = i.authenticatedAt();
    if (at == null
        || at.isAfter(clock.instant())
        || Duration.between(at, clock.instant()).compareTo(Duration.ofSeconds(120)) > 0)
      throw fail(403, "GUARDIAN_REAUTH_REQUIRED");
  }

  private void recent(Identity i) {
    recent(i, clock);
  }

  static String hash(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static MemberFailure fail(int status, String code) {
    return new MemberFailure(status, code, code.replace('_', ' '));
  }
}
