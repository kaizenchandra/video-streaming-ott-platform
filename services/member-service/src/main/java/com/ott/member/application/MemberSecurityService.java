package com.ott.member.application;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberSecurityService {
  public record Result(UUID operationId, UUID accountId, String status, long securityEpoch) {}

  private final JdbcTemplate db;
  private final Clock clock;

  public MemberSecurityService(JdbcTemplate db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> summary(UUID account) {
    var rows = db.queryForList("SELECT id,status,security_epoch FROM account WHERE id=?", account);
    if (rows.isEmpty()) throw fail(404, "ACCOUNT_NOT_FOUND");
    var result = new java.util.LinkedHashMap<String, Object>(rows.getFirst());
    result.put(
        "profiles",
        db.queryForObject(
            "SELECT count(*) FROM profile WHERE account_id=?", Integer.class, account));
    result.put(
        "activeDevices",
        db.queryForObject(
            "SELECT count(*) FROM device WHERE account_id=? AND status='ACTIVE'",
            Integer.class,
            account));
    return result;
  }

  @Transactional
  public Result command(UUID id, UUID account, String action, String reason, String actor) {
    if (id == null
        || account == null
        || action == null
        || !Set.of("SUSPEND", "RESUME").contains(action)
        || reason == null
        || !Set.of("CREDENTIAL_ABUSE", "STREAM_ABUSE", "PAYMENT_RISK", "SUPPORT_REVIEW")
            .contains(reason)) throw fail(400, "INVALID_SECURITY_COMMAND");
    String fingerprint = ParentalService.hash(account + ":" + action + ":" + reason + ":" + actor);
    if (db.queryForList("SELECT id FROM account WHERE id=? FOR UPDATE", UUID.class, account)
        .isEmpty()) throw fail(404, "ACCOUNT_NOT_FOUND");
    var prior =
        db.queryForList("SELECT fingerprint FROM member_command WHERE id=?", String.class, id);
    if (!prior.isEmpty()) {
      if (!prior.getFirst().equals(fingerprint)) throw fail(409, "OPERATION_CONFLICT");
      return result(id);
    }
    String status = action.equals("SUSPEND") ? "SUSPENDED" : "ACTIVE";
    long epoch =
        db.queryForObject(
            "UPDATE account SET status=?,security_epoch=security_epoch+1 WHERE id=? RETURNING"
                + " security_epoch",
            Long.class,
            status,
            account);
    db.update("DELETE FROM profile_selection WHERE account_id=?", account);
    db.update(
        "INSERT INTO member_command VALUES(?,?,?,?,?,?)",
        id,
        account,
        fingerprint,
        status,
        epoch,
        java.sql.Timestamp.from(clock.instant()));
    db.update(
        "INSERT INTO member_security_audit VALUES(?,?,?,?,?)",
        id,
        account,
        ParentalService.hash(actor),
        action + ":" + reason,
        java.sql.Timestamp.from(clock.instant()));
    return result(id);
  }

  private Result result(UUID id) {
    return db.queryForObject(
        "SELECT * FROM member_command WHERE id=?",
        (row, n) ->
            new Result(
                id,
                row.getObject("account_id", UUID.class),
                row.getString("status"),
                row.getLong("security_epoch")),
        id);
  }

  private static MemberFailure fail(int s, String c) {
    return new MemberFailure(s, c, c.replace('_', ' '));
  }
}
