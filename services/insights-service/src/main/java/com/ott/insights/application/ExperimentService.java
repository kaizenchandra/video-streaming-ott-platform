package com.ott.insights.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExperimentService {
  public record Experiment(
      UUID id,
      String name,
      String feature,
      boolean enabled,
      Instant startsAt,
      Instant endsAt,
      int control,
      int variantA,
      int variantB,
      long version) {}

  public record Assignment(UUID experimentId, long version, String variant, String reason) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final TelemetryService telemetry;
  private final Clock clock;
  private final String secret;

  public ExperimentService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      TelemetryService telemetry,
      Clock clock,
      @Value("${ott.insights.assignment-secret}") String secret) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.telemetry = telemetry;
    this.clock = clock;
    this.secret = secret;
  }

  public Experiment create(
      String name,
      String feature,
      Instant starts,
      Instant ends,
      int control,
      int a,
      int b,
      String actor) {
    validate(name, feature, starts, ends, control, a, b);
    return tx.execute(
        s -> {
          db.queryForObject("SELECT pg_advisory_xact_lock(814231)", (r, n) -> 0);
          if (db.queryForObject("SELECT count(*) FROM experiment WHERE enabled", Integer.class)
              >= 100) throw new InsightsFailure(409, "EXPERIMENT_LIMIT");
          if (db.queryForObject("SELECT count(*) FROM experiment WHERE name=?", Integer.class, name)
              > 0) throw new InsightsFailure(409, "EXPERIMENT_NAME_CONFLICT");
          UUID id = UUID.randomUUID();
          db.update(
              "INSERT INTO"
                  + " experiment(id,name,feature,starts_at,ends_at,control,variant_a,variant_b)"
                  + " VALUES(?,?,?,?,?,?,?,?)",
              id,
              name,
              feature,
              TelemetryService.timestamp(starts),
              TelemetryService.timestamp(ends),
              control,
              a,
              b);
          audit(id, actor, "CREATE", 1);
          return get(id, false);
        });
  }

  public Experiment configure(
      UUID id, long version, boolean enabled, int control, int a, int b, String actor) {
    if (control < 0
        || control > 100
        || a < 0
        || a > 100
        || b < 0
        || b > 100
        || control + a + b != 100) throw new InsightsFailure(400, "INVALID_EXPERIMENT");
    return tx.execute(
        s -> {
          db.queryForObject("SELECT pg_advisory_xact_lock(814231)", (r, n) -> 0);
          var old = get(id, true);
          if (old.version() != version)
            throw new InsightsFailure(409, "EXPERIMENT_VERSION_CONFLICT");
          if (enabled
              && !old.enabled()
              && db.queryForObject("SELECT count(*) FROM experiment WHERE enabled", Integer.class)
                  >= 100) throw new InsightsFailure(409, "EXPERIMENT_LIMIT");
          db.update(
              "UPDATE experiment SET enabled=?,control=?,variant_a=?,variant_b=?,version=version+1"
                  + " WHERE id=?",
              enabled,
              control,
              a,
              b,
              id);
          audit(id, actor, "CONFIGURE", version + 1);
          return get(id, false);
        });
  }

  public List<Experiment> list(UUID after, int limit) {
    if (limit < 1 || limit > 100) throw new InsightsFailure(400, "INVALID_PAGE");
    return db.query(
        "SELECT * FROM experiment WHERE (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?",
        this::map,
        after,
        after,
        limit);
  }

  private Experiment get(UUID id, boolean lock) {
    return db
        .query("SELECT * FROM experiment WHERE id=?" + (lock ? " FOR SHARE" : ""), this::map, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new InsightsFailure(404, "EXPERIMENT_NOT_FOUND"));
  }

  private Experiment map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Experiment(
        r.getObject("id", UUID.class),
        r.getString("name"),
        r.getString("feature"),
        r.getBoolean("enabled"),
        r.getTimestamp("starts_at").toInstant(),
        r.getTimestamp("ends_at").toInstant(),
        r.getInt("control"),
        r.getInt("variant_a"),
        r.getInt("variant_b"),
        r.getLong("version"));
  }

  public Assignment assignment(String token, UUID id) {
    UUID account;
    try {
      account = telemetry.account(token);
    } catch (InsightsFailure e) {
      if (e.status() == 503) return new Assignment(id, 0, "CONTROL", "OWNER_UNAVAILABLE");
      throw e;
    }
    return tx.execute(
        s -> {
          telemetry.lock(account);
          var e = get(id, true);
          var consent = telemetry.current(account);
          if (!consent.experiments()) return fallback(e, "CONSENT_REQUIRED");
          if (!e.enabled()
              || clock.instant().isBefore(e.startsAt())
              || !clock.instant().isBefore(e.endsAt())) return fallback(e, "INACTIVE");
          if (secret == null || !secret.matches("[a-f0-9]{64}"))
            return fallback(e, "ASSIGNMENT_KEY_UNAVAILABLE");
          var saved =
              db.queryForList(
                  "SELECT variant FROM experiment_assignment WHERE account_id=? AND experiment_id=?"
                      + " AND version=?",
                  String.class,
                  account,
                  id,
                  e.version());
          if (!saved.isEmpty())
            return new Assignment(id, e.version(), saved.getFirst(), "ASSIGNED");
          int bucket =
              Integer.parseInt(
                      TelemetryService.hmac(secret, account + ":" + id + ":" + e.version())
                          .substring(0, 6),
                      16)
                  % 100;
          String variant =
              bucket < e.control()
                  ? "CONTROL"
                  : bucket < e.control() + e.variantA() ? "VARIANT_A" : "VARIANT_B";
          db.update(
              "INSERT INTO experiment_assignment VALUES(?,?,?,?)",
              account,
              id,
              e.version(),
              variant);
          return new Assignment(id, e.version(), variant, "ASSIGNED");
        });
  }

  private Assignment fallback(Experiment e, String reason) {
    return new Assignment(e.id(), e.version(), "CONTROL", reason);
  }

  private void audit(UUID id, String actor, String action, long version) {
    db.update(
        "INSERT INTO insights_audit VALUES(?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        TelemetryService.hash(actor),
        action,
        version,
        TelemetryService.timestamp(clock.instant()));
  }

  private void validate(
      String name, String feature, Instant starts, Instant ends, int control, int a, int b) {
    if (name == null
        || name.isBlank()
        || name.length() > 100
        || feature == null
        || !Set.of("HOME_RAILS", "SEARCH_PRESENTATION", "AD_PRESENTATION").contains(feature)
        || starts == null
        || ends == null
        || !ends.isAfter(starts)
        || ends.isAfter(starts.plusSeconds(90L * 86400))
        || control > 100
        || a > 100
        || b > 100
        || control < 0
        || a < 0
        || b < 0
        || control + a + b != 100) throw new InsightsFailure(400, "INVALID_EXPERIMENT");
  }
}
