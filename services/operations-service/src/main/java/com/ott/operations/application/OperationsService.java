package com.ott.operations.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OperationsService {
  public record Case(
      UUID id,
      UUID accountId,
      String kind,
      String status,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  public record Command(
      UUID id,
      UUID caseId,
      UUID accountId,
      String action,
      String reason,
      String state,
      int attempts,
      Long ownerVersion,
      String resultCode) {}

  public record Signal(
      UUID eventId, UUID accountId, String kind, String region, Instant occurredAt) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final ObjectMapper json;
  private final OwnerControl owners;
  private final MeterRegistry metrics;

  public OperationsService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      Clock clock,
      ObjectMapper json,
      OwnerControl owners,
      MeterRegistry metrics) {
    this.db = db;
    tx = new TransactionTemplate(manager);
    this.clock = clock;
    this.json = json;
    this.owners = owners;
    this.metrics = metrics;
  }

  void lock(UUID a) {
    db.update("INSERT INTO operations_account VALUES(?,0) ON CONFLICT DO NOTHING", a);
    db.queryForObject(
        "SELECT account_id FROM operations_account WHERE account_id=? FOR UPDATE", UUID.class, a);
  }

  public Case support(UUID request, UUID account, String actor) {
    if (request == null || account == null) throw fail(400, "INVALID_SUPPORT_REQUEST");
    owners.summary(account);
    String fingerprint = hash(account + ":" + actor);
    return tx.execute(
        s -> {
          lock(account);
          var prior =
              db.queryForList(
                  "SELECT fingerprint,case_id FROM support_request WHERE id=?", request);
          if (!prior.isEmpty()) {
            if (!fingerprint.equals(prior.getFirst().get("fingerprint")))
              throw fail(409, "OPERATION_CONFLICT");
            return get((UUID) prior.getFirst().get("case_id"));
          }
          var c = open(account, "SUPPORT_REVIEW", actor);
          db.update("INSERT INTO support_request VALUES(?,?,?)", request, fingerprint, c.id());
          return c;
        });
  }

  public record SignalReceipt(boolean accepted, boolean duplicate, boolean ignored) {}

  public SignalReceipt signal(Signal input, String actor) {
    if (input == null
        || input.eventId() == null
        || input.accountId() == null
        || input.kind() == null
        || !Set.of("LOGIN_FAILURE", "DEVICE_LIMIT", "STREAM_LIMIT", "GEO_CHANGE", "PAYMENT_FAILED")
            .contains(input.kind())
        || input.region() == null
        || !input.region().matches("[A-Z]{2}|UNKNOWN")
        || input.occurredAt() == null) throw fail(400, "INVALID_SECURITY_SIGNAL");
    if (input.occurredAt().isAfter(clock.instant().plusSeconds(5)))
      throw fail(400, "SIGNAL_TIME_INVALID");
    if (input.occurredAt().isBefore(clock.instant().minusSeconds(3600)))
      return new SignalReceipt(false, false, true);
    String fingerprint = hash(encode(input) + ":" + actor);
    return tx.execute(
        s -> {
          lock(input.accountId());
          var old =
              db.queryForList(
                  "SELECT fingerprint FROM security_signal WHERE id=?",
                  String.class,
                  input.eventId());
          if (!old.isEmpty()) {
            if (!old.getFirst().equals(fingerprint)) throw fail(409, "SIGNAL_EVENT_CONFLICT");
            return new SignalReceipt(false, true, false);
          }
          var day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
          db.update(
              "INSERT INTO signal_quota VALUES(?,?,0) ON CONFLICT DO NOTHING",
              input.accountId(),
              day);
          if (db.update(
                  "UPDATE signal_quota SET count=count+1 WHERE account_id=? AND day=? AND"
                      + " count<1000",
                  input.accountId(),
                  day)
              == 0) throw fail(429, "SECURITY_SIGNAL_LIMIT");
          db.update(
              "INSERT INTO"
                  + " security_signal(id,account_id,kind,region,fingerprint,occurred_at,received_at,source_ref)"
                  + " VALUES(?,?,?,?,?,?,?,?)",
              input.eventId(),
              input.accountId(),
              input.kind(),
              input.region(),
              fingerprint,
              stamp(input.occurredAt()),
              stamp(clock.instant()),
              hash(actor));
          int window =
              input.kind().equals("LOGIN_FAILURE")
                  ? 300
                  : input.kind().equals("GEO_CHANGE") ? 3600 : 600;
          String count =
              input.kind().equals("GEO_CHANGE")
                  ? "count(DISTINCT region) FILTER(WHERE region!='UNKNOWN')"
                  : "count(*)";
          int signals =
              db.queryForObject(
                  "SELECT "
                      + count
                      + " FROM security_signal WHERE account_id=? AND kind=? AND occurred_at>=?",
                  Integer.class,
                  input.accountId(),
                  input.kind(),
                  stamp(clock.instant().minusSeconds(window)));
          int threshold =
              switch (input.kind()) {
                case "LOGIN_FAILURE" -> 10;
                case "STREAM_LIMIT" -> 5;
                default -> 3;
              };
          if (signals >= threshold) {
            String kind =
                switch (input.kind()) {
                  case "LOGIN_FAILURE" -> "CREDENTIAL_ABUSE";
                  case "DEVICE_LIMIT" -> "DEVICE_ABUSE";
                  case "STREAM_LIMIT" -> "STREAM_ABUSE";
                  case "GEO_CHANGE" -> "GEO_ABUSE";
                  default -> "PAYMENT_RISK";
                };
            open(input.accountId(), kind, "trusted-rule:" + input.kind());
          }
          metrics.counter("ott.operations.signal", "kind", input.kind()).increment();
          return new SignalReceipt(true, false, false);
        });
  }

  private Case open(UUID a, String kind, String actor) {
    var existing =
        db.queryForList(
            "SELECT id FROM support_case WHERE account_id=? AND kind=? AND status='OPEN'",
            UUID.class,
            a,
            kind);
    if (!existing.isEmpty()) return get(existing.getFirst());
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO support_case(id,account_id,kind,created_at,updated_at) VALUES(?,?,?,?,?)",
        id,
        a,
        kind,
        stamp(clock.instant()),
        stamp(clock.instant()));
    audit(a, id, actor, "CASE_OPENED", "OPEN");
    if (!kind.equals("SUPPORT_REVIEW"))
      emit(
          a,
          "abuse.signal.v1",
          Map.of("accountRef", hash(a.toString()), "signalCode", kind, "confidence", 0.0));
    metrics.counter("ott.operations.case.opened", "kind", kind).increment();
    return get(id);
  }

  public Case get(UUID id) {
    return db.query("SELECT * FROM support_case WHERE id=?", this::map, id).stream()
        .findFirst()
        .orElseThrow(() -> fail(404, "CASE_NOT_FOUND"));
  }

  private Case map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Case(
        r.getObject("id", UUID.class),
        r.getObject("account_id", UUID.class),
        r.getString("kind"),
        r.getString("status"),
        r.getLong("version"),
        r.getTimestamp("created_at").toInstant(),
        r.getTimestamp("updated_at").toInstant());
  }

  public List<Case> cases(UUID after, int limit) {
    page(limit);
    return db.query(
        "SELECT * FROM support_case WHERE (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?",
        this::map,
        after,
        after,
        limit);
  }

  public Case resolve(UUID id, long version, String actor) {
    var c = get(id);
    return tx.execute(
        s -> {
          lock(c.accountId());
          var current = get(id);
          if (current.version() != version) throw fail(409, "CASE_VERSION_CONFLICT");
          if (db.queryForObject(
                  "SELECT count(*) FROM owner_command WHERE case_id=? AND state"
                      + " IN('PENDING','RUNNING','RETRY','RECONCILE')",
                  Integer.class,
                  id)
              > 0) throw fail(409, "COMMAND_UNRESOLVED");
          if (!current.status().equals("RESOLVED")) {
            db.update(
                "UPDATE support_case SET status='RESOLVED',version=version+1,updated_at=? WHERE"
                    + " id=?",
                stamp(clock.instant()),
                id);
            audit(c.accountId(), id, actor, "CASE_RESOLVED", "RESOLVED");
          }
          return get(id);
        });
  }

  public Command enqueue(UUID id, UUID caseId, long version, String action, String actor) {
    if (id == null
        || action == null
        || !Set.of("SUSPEND_ACCOUNT", "RESUME_ACCOUNT", "REVOKE_ENTITLEMENT", "RESTORE_ENTITLEMENT")
            .contains(action)) throw fail(400, "INVALID_OWNER_ACTION");
    var c = get(caseId);
    String fingerprint = hash(caseId + ":" + version + ":" + action + ":" + actor);
    return tx.execute(
        s -> {
          lock(c.accountId());
          var prior =
              db.queryForList("SELECT fingerprint FROM owner_command WHERE id=?", String.class, id);
          if (!prior.isEmpty()) {
            if (!prior.getFirst().equals(fingerprint)) throw fail(409, "OPERATION_CONFLICT");
            return command(id);
          }
          var current = get(caseId);
          if (current.version() != version) throw fail(409, "CASE_VERSION_CONFLICT");
          if (!current.status().equals("OPEN")) throw fail(409, "CASE_CLOSED");
          String reason =
              Set.of("CREDENTIAL_ABUSE", "STREAM_ABUSE", "PAYMENT_RISK").contains(c.kind())
                  ? c.kind()
                  : "SUPPORT_REVIEW";
          db.update(
              "INSERT INTO"
                  + " owner_command(id,case_id,account_id,action,reason,fingerprint,next_attempt_at,created_at)"
                  + " VALUES(?,?,?,?,?,?,?,?)",
              id,
              caseId,
              c.accountId(),
              action,
              reason,
              fingerprint,
              stamp(clock.instant()),
              stamp(clock.instant()));
          db.update(
              "UPDATE support_case SET version=version+1,updated_at=? WHERE id=?",
              stamp(clock.instant()),
              caseId);
          audit(c.accountId(), caseId, actor, action, "QUEUED");
          return command(id);
        });
  }

  public Command command(UUID id) {
    return db
        .query(
            "SELECT * FROM owner_command WHERE id=?",
            (r, n) ->
                new Command(
                    id,
                    r.getObject("case_id", UUID.class),
                    r.getObject("account_id", UUID.class),
                    r.getString("action"),
                    r.getString("reason"),
                    r.getString("state"),
                    r.getInt("attempts"),
                    (Long) r.getObject("owner_version"),
                    r.getString("result_code")),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> fail(404, "COMMAND_NOT_FOUND"));
  }

  public List<Map<String, Object>> audit(UUID caseId, UUID after, int limit) {
    page(limit);
    get(caseId);
    return db.queryForList(
        "SELECT id,actor_hash,action,result,version,at FROM operations_audit WHERE case_id=? AND"
            + " (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?",
        caseId,
        after,
        after,
        limit);
  }

  public Map<String, Object> summary(UUID caseId) {
    return owners.summary(get(caseId).accountId());
  }

  private void audit(UUID a, UUID c, String actor, String action, String result) {
    UUID id = UUID.randomUUID();
    long version = next(a);
    String actorHash = hash(actor);
    db.update(
        "INSERT INTO operations_audit VALUES(?,?,?,?,?,?,?,?)",
        id,
        a,
        c,
        actorHash,
        action,
        result,
        version,
        stamp(clock.instant()));
    outbox(
        a,
        version,
        "audit.recorded.v1",
        Map.of(
            "actorRef",
            actorHash,
            "actionCode",
            action,
            "resourceRef",
            c.toString(),
            "resultCode",
            result));
  }

  private long next(UUID a) {
    return db.queryForObject(
        "UPDATE operations_account SET version=version+1 WHERE account_id=? RETURNING version",
        Long.class,
        a);
  }

  private void emit(UUID a, String topic, Map<String, Object> p) {
    outbox(a, next(a), topic, p);
  }

  private void outbox(UUID a, long version, String topic, Map<String, Object> payload) {
    db.update(
        "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload,created_at)"
            + " VALUES(?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        a,
        version,
        topic,
        encode(payload),
        stamp(clock.instant()));
  }

  private record Claim(
      UUID id, UUID account, UUID caseId, String action, String reason, int attempts) {}

  @Scheduled(fixedDelayString = "${ott.operations.command-delay-ms:1000}")
  public void dispatch() {
    UUID fence = UUID.randomUUID();
    List<Claim> claims =
        tx.execute(
            s ->
                db.query(
                    """
                    UPDATE owner_command SET state='RUNNING',claim_token=?,claimed_until=?,attempts=attempts+1
                    WHERE id IN(SELECT id FROM owner_command WHERE (state IN('PENDING','RETRY') AND next_attempt_at<=?) OR (state='RUNNING' AND claimed_until<?) ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 10)
                    RETURNING id,account_id,case_id,action,reason,attempts
                    """,
                    (r, n) ->
                        new Claim(
                            r.getObject(1, UUID.class),
                            r.getObject(2, UUID.class),
                            r.getObject(3, UUID.class),
                            r.getString(4),
                            r.getString(5),
                            r.getInt(6)),
                    fence,
                    stamp(clock.instant().plusSeconds(10)),
                    stamp(clock.instant()),
                    stamp(clock.instant())));
    for (var c : claims) {
      Long version = null;
      String state = "APPLIED", code = "APPLIED";
      try {
        version = owners.apply(c.id(), c.account(), c.action(), c.reason()).version();
        if (version < 1) throw fail(503, "OWNER_RESPONSE_INVALID");
      } catch (OperationsFailure e) {
        if (e.status() < 500) {
          state = "REJECTED";
          code = "OWNER_REJECTED";
        } else {
          state = c.attempts() >= 5 ? "RECONCILE" : "RETRY";
          code = e.code();
        }
      }
      String outcome = state, result = code;
      Long ownerVersion = version;
      tx.executeWithoutResult(
          s -> {
            lock(c.account());
            int updated =
                db.update(
                    "UPDATE owner_command SET"
                        + " state=?,owner_version=?,result_code=?,claim_token=NULL,claimed_until=NULL,next_attempt_at=?"
                        + " WHERE id=? AND claim_token=?",
                    outcome,
                    ownerVersion,
                    result,
                    stamp(
                        clock
                            .instant()
                            .plusSeconds(
                                Math.min(120, 2L << Math.min(6, c.attempts()))
                                    + java.util.concurrent.ThreadLocalRandom.current().nextInt(3))),
                    c.id(),
                    fence);
            if (updated == 0) return;
            audit(c.account(), c.caseId(), "owner-dispatch", c.action(), outcome);
            db.update(
                "UPDATE support_case SET version=version+1,updated_at=? WHERE id=?",
                stamp(clock.instant()),
                c.caseId());
            if (outcome.equals("APPLIED"))
              emit(
                  c.account(),
                  "abuse.action.v1",
                  Map.of(
                      "accountRef",
                      hash(c.account().toString()),
                      "actionId",
                      c.id().toString(),
                      "actionCode",
                      c.action()));
            metrics.counter("ott.operations.command", "outcome", outcome).increment();
          });
    }
  }

  @Scheduled(fixedDelayString = "${ott.operations.retention-delay-ms:3600000}")
  public void retention() {
    var cutoff = clock.instant().minusSeconds(30L * 86400);
    db.update(
        "DELETE FROM security_signal WHERE id IN(SELECT id FROM security_signal WHERE received_at<?"
            + " ORDER BY received_at,id LIMIT 1000)",
        stamp(cutoff));
    db.update(
        "DELETE FROM signal_quota WHERE (account_id,day) IN(SELECT account_id,day FROM signal_quota"
            + " WHERE day<? ORDER BY day,account_id LIMIT 1000)",
        cutoff.atZone(ZoneOffset.UTC).toLocalDate());
  }

  static java.sql.Timestamp stamp(Instant i) {
    return java.sql.Timestamp.from(i);
  }

  String encode(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid operations model", e);
    }
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

  static OperationsFailure fail(int s, String c) {
    return new OperationsFailure(s, c);
  }

  static void page(int n) {
    if (n < 1 || n > 100) throw fail(400, "INVALID_PAGE");
  }
}
