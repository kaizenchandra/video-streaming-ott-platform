package com.ott.engagement.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.engagement.adapter.outbound.notifications.ContactCipher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationService {
  private static final List<String> CHANNELS = List.of("IN_APP", "EMAIL", "SMS", "PUSH");
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final NotificationAudience audience;
  private final ContactCipher cipher;
  private final Clock clock;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final boolean enabled;

  public NotificationService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      NotificationAudience audience,
      ContactCipher cipher,
      Clock clock,
      ObjectMapper json,
      MeterRegistry metrics,
      @Value("${ott.notifications.enabled:true}") boolean enabled) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.audience = audience;
    this.cipher = cipher;
    this.clock = clock;
    this.json = json;
    this.metrics = metrics;
    this.enabled = enabled;
  }

  public record Preference(
      String channel, boolean enabled, long version, boolean contactVerified) {}

  public record Reminder(
      UUID id,
      String kind,
      UUID targetId,
      String channel,
      Instant dueAt,
      Instant expiresAt,
      String state) {}

  public record Notice(
      UUID id, String template, UUID targetId, Instant createdAt, Instant readAt) {}

  public record Page(List<Notice> items, String nextCursor) {}

  public UUID account(String token) {
    requireEnabled();
    return audience.account(token);
  }

  public List<Preference> preferences(UUID account) {
    return tx.execute(
        t -> {
          lock(account);
          return db.query(
              "SELECT p.*,c.account_id IS NOT NULL AS verified FROM notification_preference p LEFT"
                  + " JOIN notification_contact c USING(account_id,channel) WHERE p.account_id=?"
                  + " ORDER BY p.channel",
              (r, n) ->
                  new Preference(
                      r.getString("channel"),
                      r.getBoolean("enabled"),
                      r.getLong("version"),
                      r.getBoolean("verified")),
              account);
        });
  }

  public Preference preference(UUID account, String channel, long expected, boolean enabled) {
    channel(channel);
    return tx.execute(
        t -> {
          lock(account);
          if (enabled
              && !channel.equals("IN_APP")
              && db.queryForObject(
                      "SELECT count(*) FROM notification_contact WHERE account_id=? AND channel=?",
                      Integer.class,
                      account,
                      channel)
                  == 0) throw fail(409, "VERIFIED_CONTACT_REQUIRED");
          if (db.update(
                  "UPDATE notification_preference SET enabled=?,version=version+1 WHERE"
                      + " account_id=? AND channel=? AND version=?",
                  enabled,
                  account,
                  channel,
                  expected)
              != 1) throw fail(409, "PREFERENCE_VERSION_CONFLICT");
          if (!enabled)
            db.update(
                "UPDATE notification_intent SET state='SKIPPED',claim_token=NULL,claimed_until=NULL"
                    + " WHERE account_id=? AND channel=? AND state IN('QUEUED','PENDING')",
                account,
                channel);
          return preferences(account).stream()
              .filter(p -> p.channel().equals(channel))
              .findFirst()
              .orElseThrow();
        });
  }

  public void verifiedContact(UUID account, String channel, String reference) {
    channel(channel);
    if (channel.equals("IN_APP")
        || reference == null
        || reference.isBlank()
        || reference.length() > 1024
        || reference.chars().anyMatch(Character::isISOControl)) throw fail(400, "INVALID_CONTACT");
    String encrypted = cipher.encrypt(reference, account + ":" + channel);
    tx.executeWithoutResult(
        t -> {
          lock(account);
          db.update(
              "INSERT INTO notification_contact VALUES(?,?,?,?) ON CONFLICT(account_id,channel) DO"
                  + " UPDATE SET ciphertext=EXCLUDED.ciphertext,updated_at=EXCLUDED.updated_at",
              account,
              channel,
              encrypted,
              stamp(clock.instant()));
        });
  }

  public void removeContact(UUID account, String channel) {
    channel(channel);
    tx.executeWithoutResult(
        t -> {
          lock(account);
          db.update(
              "DELETE FROM notification_contact WHERE account_id=? AND channel=?",
              account,
              channel);
          db.update(
              "UPDATE notification_preference SET enabled=false,version=version+1 WHERE"
                  + " account_id=? AND channel=?",
              account,
              channel);
          db.update(
              "UPDATE notification_intent SET state='SKIPPED',claim_token=NULL WHERE account_id=?"
                  + " AND channel=? AND state IN('QUEUED','PENDING')",
              account,
              channel);
        });
  }

  public Reminder remind(
      String token, UUID target, String kind, String channel, Instant due, String key) {
    requireEnabled();
    if (target == null
        || kind == null
        || !List.of("CONTENT", "LIVE").contains(kind)
        || key == null
        || !key.matches("[A-Za-z0-9_-]{8,128}")) throw fail(400, "INVALID_REMINDER");
    channel(channel);
    UUID account = audience.account(token);
    var metadata = audience.target(token, kind, target);
    Instant at = kind.equals("LIVE") ? metadata.startsAt() : due;
    if (at == null
        || kind.equals("CONTENT") && !at.isAfter(clock.instant())
        || at.isBefore(clock.instant().minusSeconds(300))
        || at.isAfter(clock.instant().plusSeconds(30L * 86400)))
      throw fail(400, "INVALID_REMINDER_TIME");
    String fingerprint =
        AdService.hash(
            kind + ":" + target + ":" + channel + ":" + (kind.equals("LIVE") ? "START" : at));
    return tx.execute(
        t -> {
          if (kind.equals("LIVE")) liveLock(target);
          lock(account);
          var prior =
              db.query(
                  "SELECT * FROM reminder WHERE account_id=? AND idempotency_key=?",
                  this::reminderRow,
                  account,
                  key);
          if (!prior.isEmpty()) {
            String saved =
                db.queryForObject(
                    "SELECT fingerprint FROM reminder WHERE id=?",
                    String.class,
                    prior.getFirst().id());
            if (!saved.equals(fingerprint)) throw fail(409, "IDEMPOTENCY_CONFLICT");
            return prior.getFirst();
          }
          if (!permitted(account, channel)) throw fail(409, "NOTIFICATION_CHANNEL_DISABLED");
          if (db.queryForObject(
                  "SELECT count(*) FROM reminder WHERE account_id=? AND state"
                      + " IN('WAITING','REQUESTED')",
                  Integer.class,
                  account)
              >= 100) throw fail(409, "REMINDER_LIMIT_REACHED");
          if (db.queryForObject(
                  "SELECT count(*) FROM reminder WHERE account_id=? AND kind=? AND target_id=? AND"
                      + " channel=? AND state IN('WAITING','REQUESTED')",
                  Integer.class,
                  account,
                  kind,
                  target,
                  channel)
              > 0) throw fail(409, "REMINDER_ALREADY_EXISTS");
          UUID id = UUID.randomUUID();
          db.update(
              "INSERT INTO reminder VALUES(?,?,?,?,?,?,?,'WAITING',?,?)",
              id,
              account,
              kind,
              target,
              channel,
              stamp(at),
              stamp(kind.equals("LIVE") ? at.plusSeconds(7200) : at.plusSeconds(300)),
              key,
              fingerprint);
          if (kind.equals("LIVE")) {
            liveProjection(
                target, metadata.version(), metadata.state(), metadata.startsAt(), clock.instant());
            if (metadata.state().equals("LIVE")) requestReminder(id);
          }
          return reminder(account, id);
        });
  }

  public List<Reminder> reminders(UUID account, UUID after, int limit) {
    if (limit < 1 || limit > 100) throw fail(400, "INVALID_QUERY");
    return db.query(
        "SELECT * FROM reminder WHERE account_id=? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id"
            + " LIMIT ?",
        this::reminderRow,
        account,
        after,
        after,
        limit);
  }

  public void cancel(UUID account, UUID id) {
    tx.executeWithoutResult(
        t -> {
          lock(account);
          var r = reminder(account, id);
          db.update("UPDATE reminder SET state='CANCELLED' WHERE id=?", id);
          db.update(
              "UPDATE notification_intent SET state='SKIPPED',claim_token=NULL WHERE account_id=?"
                  + " AND channel=? AND cause_id=? AND state IN('QUEUED','PENDING')",
              account,
              r.channel(),
              r.kind().equals("CONTENT") ? id : r.targetId());
        });
  }

  public void due() {
    var expired =
        db.query(
            "SELECT id,account_id FROM reminder WHERE state='WAITING' AND expires_at<=? ORDER BY"
                + " expires_at,id LIMIT 20",
            (r, n) -> new UUID[] {r.getObject(1, UUID.class), r.getObject(2, UUID.class)},
            stamp(clock.instant()));
    for (var row : expired)
      tx.executeWithoutResult(
          t -> {
            lock(row[1]);
            db.update(
                "UPDATE reminder SET state='EXPIRED' WHERE id=? AND state='WAITING' AND"
                    + " expires_at<=?",
                row[0],
                stamp(clock.instant()));
          });
    var candidates =
        db.query(
            "SELECT id,account_id FROM reminder WHERE state='WAITING' AND due_at<=? AND"
                + " (kind='CONTENT' OR EXISTS(SELECT 1 FROM live_notice_state l WHERE"
                + " l.event_id=reminder.target_id AND l.state='LIVE' AND l.changed_at>=?)) ORDER BY"
                + " due_at,id LIMIT 20",
            (r, n) -> new UUID[] {r.getObject(1, UUID.class), r.getObject(2, UUID.class)},
            stamp(clock.instant()),
            stamp(clock.instant().minusSeconds(300)));
    for (var row : candidates)
      tx.executeWithoutResult(
          t -> {
            lock(row[1]);
            db.queryForList("SELECT id FROM reminder WHERE id=? FOR UPDATE", row[0]);
            requestReminder(row[0]);
          });
  }

  public void payment(UUID event, String fingerprint, UUID account, UUID order, Instant occurred) {
    tx.executeWithoutResult(
        t -> {
          if (!source(event, fingerprint)) return;
          if (!recent(occurred, 30L * 86400)) return;
          lock(account);
          for (var channel : CHANNELS)
            if (permitted(account, channel))
              intent(
                  account,
                  "PAYMENT_FAILED",
                  order,
                  order,
                  channel,
                  clock.instant().plusSeconds(86400));
        });
  }

  public void live(
      UUID event,
      String fingerprint,
      UUID target,
      long version,
      String state,
      Instant starts,
      Instant occurred) {
    if (version < 1
        || state == null
        || !List.of("SCHEDULED", "READY", "LIVE", "ENDED", "ARCHIVED", "CANCELLED").contains(state)
        || starts == null) throw new IllegalArgumentException("Invalid live notice");
    tx.executeWithoutResult(
        t -> {
          if (!source(event, fingerprint)) return;
          if (!recent(occurred, 7L * 86400)) return;
          liveLock(target);
          if (!liveProjection(target, version, state, starts, occurred)) return;
          if (List.of("ENDED", "ARCHIVED", "CANCELLED").contains(state)) {
            db.update(
                "UPDATE reminder SET state='EXPIRED' WHERE kind='LIVE' AND target_id=? AND"
                    + " state='WAITING'",
                target);
            db.update(
                "UPDATE notification_intent SET state='SKIPPED',claim_token=NULL WHERE"
                    + " template='LIVE_STARTED' AND cause_id=? AND state IN('QUEUED','PENDING')",
                target);
            return;
          }
          db.update(
              "UPDATE reminder SET due_at=?,expires_at=? WHERE kind='LIVE' AND target_id=? AND"
                  + " state='WAITING'",
              stamp(starts),
              stamp(starts.plusSeconds(7200)),
              target);
          if (state.equals("LIVE") && recent(occurred, 300)) {
            var following =
                db.query(
                    "SELECT id,account_id FROM reminder WHERE kind='LIVE' AND target_id=? AND"
                        + " state='WAITING' ORDER BY account_id,id LIMIT 100",
                    (r, n) -> new UUID[] {r.getObject(1, UUID.class), r.getObject(2, UUID.class)},
                    target);
            for (var row : following) {
              lock(row[1]);
              requestReminder(row[0]);
            }
          }
        });
  }

  public void activate(
      UUID event, String fingerprint, UUID account, UUID intent, String template, long version) {
    if (version != 1) throw new IllegalArgumentException("Invalid notification request version");
    tx.executeWithoutResult(
        t -> {
          if (!source(event, fingerprint)) return;
          var rows =
              db.queryForList(
                  "SELECT account_id,template FROM notification_intent WHERE id=?", intent);
          if (rows.isEmpty()
              || !account.equals(rows.getFirst().get("account_id"))
              || !template.equals(rows.getFirst().get("template")))
            throw new IllegalArgumentException("Foreign notification request");
          db.update(
              "UPDATE notification_intent SET state='PENDING' WHERE id=? AND state='QUEUED'",
              intent);
        });
  }

  private boolean liveProjection(
      UUID target, long version, String state, Instant starts, Instant occurred) {
    String hash = AdService.hash(version + ":" + state + ":" + starts.toEpochMilli());
    var previous = db.queryForList("SELECT * FROM live_notice_state WHERE event_id=?", target);
    if (!previous.isEmpty()) {
      long prior = ((Number) previous.getFirst().get("version")).longValue();
      if (prior > version) return false;
      if (prior == version) {
        if (!hash.equals(previous.getFirst().get("fingerprint")))
          throw new IllegalArgumentException("Live version collision");
        return false;
      }
    }
    db.update(
        "INSERT INTO live_notice_state VALUES(?,?,?,?,?) ON CONFLICT(event_id) DO UPDATE SET"
            + " version=EXCLUDED.version,state=EXCLUDED.state,fingerprint=EXCLUDED.fingerprint,changed_at=EXCLUDED.changed_at",
        target,
        version,
        state,
        hash,
        stamp(occurred));
    return true;
  }

  private void requestReminder(UUID id) {
    var rows =
        db.queryForList("SELECT * FROM reminder WHERE id=? AND state='WAITING' FOR UPDATE", id);
    if (rows.isEmpty()) return;
    var row = rows.getFirst();
    UUID account = (UUID) row.get("account_id"), target = (UUID) row.get("target_id");
    String channel = (String) row.get("channel"), kind = (String) row.get("kind");
    Instant expires = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
    if (!expires.isAfter(clock.instant()) || !permitted(account, channel)) {
      db.update("UPDATE reminder SET state='EXPIRED' WHERE id=?", id);
      return;
    }
    if (kind.equals("LIVE")) {
      String state =
          db.queryForObject(
              "SELECT state FROM live_notice_state WHERE event_id=?", String.class, target);
      if (!state.equals("LIVE")) return;
    }
    intent(
        account,
        kind.equals("LIVE") ? "LIVE_STARTED" : "CONTENT_REMINDER",
        kind.equals("LIVE") ? target : id,
        target,
        channel,
        min(expires, clock.instant().plusSeconds(300)));
    db.update("UPDATE reminder SET state='REQUESTED' WHERE id=?", id);
  }

  private void intent(
      UUID account, String template, UUID cause, UUID target, String channel, Instant expires) {
    UUID id = UUID.randomUUID();
    if (db.update(
            "INSERT INTO"
                + " notification_intent(id,account_id,template,cause_id,target_id,channel,state,expires_at,created_at,next_attempt_at)"
                + " VALUES(?,?,?,?,?,?,'QUEUED',?,?,?) ON CONFLICT DO NOTHING",
            id,
            account,
            template,
            cause,
            target,
            channel,
            stamp(expires),
            stamp(clock.instant()),
            stamp(clock.instant()))
        == 1) {
      outbox(
          id,
          1,
          "notification.requested.v1",
          Map.of(
              "accountId", account.toString(), "templateId", template, "intentId", id.toString()));
      metrics.counter("ott.notification.requested", "template", template).increment();
    }
  }

  public Page inbox(UUID account, String cursor, int limit) {
    if (limit < 1 || limit > 100) throw fail(400, "INVALID_QUERY");
    Instant at = null;
    UUID id = null;
    if (cursor != null)
      try {
        if (cursor.length() > 256) throw new IllegalArgumentException();
        String[] parts =
            new String(
                    java.util.Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8)
                .split("\\|", -1);
        if (parts.length != 2) throw new IllegalArgumentException();
        at = Instant.parse(parts[0]);
        id = UUID.fromString(parts[1]);
      } catch (IllegalArgumentException | java.time.DateTimeException e) {
        throw fail(400, "INVALID_CURSOR");
      }
    var rows =
        db.query(
            "SELECT * FROM notification_inbox WHERE account_id=? AND (?::timestamptz IS NULL OR"
                + " (created_at,intent_id)<(?::timestamptz,?::uuid)) ORDER BY created_at"
                + " DESC,intent_id DESC LIMIT ?",
            (r, n) ->
                new Notice(
                    r.getObject("intent_id", UUID.class),
                    r.getString("template"),
                    r.getObject("target_id", UUID.class),
                    at(r, "created_at"),
                    at(r, "read_at")),
            account,
            at == null ? null : stamp(at),
            at == null ? null : stamp(at),
            id,
            limit + 1);
    var page = rows.subList(0, Math.min(limit, rows.size()));
    var last = page.isEmpty() ? null : page.getLast();
    return new Page(
        page,
        rows.size() > limit
            ? java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (last.createdAt() + "|" + last.id())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            : null);
  }

  public record Delivery(
      UUID id, String template, String channel, String state, Instant expiresAt, int attempts) {}

  public List<Delivery> deliveries(UUID account, UUID after, int limit) {
    if (limit < 1 || limit > 100) throw fail(400, "INVALID_QUERY");
    return db.query(
        "SELECT id,template,channel,state,expires_at,attempts FROM notification_intent WHERE"
            + " account_id=? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",
        (r, n) ->
            new Delivery(
                r.getObject("id", UUID.class),
                r.getString("template"),
                r.getString("channel"),
                r.getString("state"),
                at(r, "expires_at"),
                r.getInt("attempts")),
        account,
        after,
        after,
        limit);
  }

  public void read(UUID account, UUID id) {
    if (db.update(
            "UPDATE notification_inbox SET read_at=COALESCE(read_at,?) WHERE intent_id=? AND"
                + " account_id=?",
            stamp(clock.instant()),
            id,
            account)
        != 1) throw fail(404, "NOTICE_NOT_FOUND");
  }

  public void lock(UUID account) {
    db.update("INSERT INTO notification_account VALUES(?) ON CONFLICT DO NOTHING", account);
    db.queryForList(
        "SELECT account_id FROM notification_account WHERE account_id=? FOR UPDATE", account);
    for (var c : CHANNELS)
      db.update(
          "INSERT INTO notification_preference VALUES(?,?,?,1) ON CONFLICT DO NOTHING",
          account,
          c,
          c.equals("IN_APP"));
  }

  public boolean permitted(UUID account, String channel) {
    return Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT enabled FROM notification_preference WHERE account_id=? AND channel=?",
                Boolean.class,
                account,
                channel))
        && (channel.equals("IN_APP")
            || db.queryForObject(
                    "SELECT count(*) FROM notification_contact WHERE account_id=? AND channel=?",
                    Integer.class,
                    account,
                    channel)
                == 1);
  }

  public String recipient(UUID account, String channel) {
    String value =
        db.queryForObject(
            "SELECT ciphertext FROM notification_contact WHERE account_id=? AND channel=?",
            String.class,
            account,
            channel);
    return cipher.decrypt(value, account + ":" + channel);
  }

  public void outbox(UUID intent, long version, String topic, Object payload) {
    try {
      db.update(
          "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload)"
              + " VALUES(?,?,?,?,?::jsonb)",
          UUID.randomUUID(),
          intent,
          version,
          topic,
          json.writeValueAsString(payload));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid notification data", e);
    }
  }

  private boolean source(UUID event, String fingerprint) {
    if (event == null || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Invalid source identity");
    if (db.update(
            "INSERT INTO notification_source(event_id,fingerprint) VALUES(?,?) ON CONFLICT DO"
                + " NOTHING",
            event,
            fingerprint)
        == 1) return true;
    if (!fingerprint.equals(
        db.queryForObject(
            "SELECT fingerprint FROM notification_source WHERE event_id=?", String.class, event)))
      throw new IllegalArgumentException("Source event collision");
    return false;
  }

  private boolean recent(Instant at, long seconds) {
    if (at == null || at.isAfter(clock.instant().plusSeconds(300)))
      throw new IllegalArgumentException("Invalid source time");
    return !at.isBefore(clock.instant().minusSeconds(seconds));
  }

  private void liveLock(UUID id) {
    db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "notice-live:" + id);
  }

  private Reminder reminder(UUID account, UUID id) {
    var rows =
        db.query(
            "SELECT * FROM reminder WHERE id=? AND account_id=?", this::reminderRow, id, account);
    if (rows.isEmpty()) throw fail(404, "REMINDER_NOT_FOUND");
    return rows.getFirst();
  }

  private Reminder reminderRow(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Reminder(
        r.getObject("id", UUID.class),
        r.getString("kind"),
        r.getObject("target_id", UUID.class),
        r.getString("channel"),
        at(r, "due_at"),
        at(r, "expires_at"),
        r.getString("state"));
  }

  private static void channel(String channel) {
    if (channel == null || !CHANNELS.contains(channel)) throw fail(400, "INVALID_CHANNEL");
  }

  private void requireEnabled() {
    if (!enabled) throw fail(503, "NOTIFICATIONS_DISABLED");
  }

  private static java.sql.Timestamp stamp(Instant at) {
    return java.sql.Timestamp.from(at);
  }

  private static Instant at(java.sql.ResultSet r, String field) throws java.sql.SQLException {
    var t = r.getTimestamp(field);
    return t == null ? null : t.toInstant();
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private static EngagementFailure fail(int status, String code) {
    return new EngagementFailure(status, code);
  }
}
