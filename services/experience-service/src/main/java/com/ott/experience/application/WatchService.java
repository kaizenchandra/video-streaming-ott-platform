package com.ott.experience.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
public class WatchService {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ViewerAccess access;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final int collectionLimit;

  public WatchService(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      ViewerAccess access,
      ObjectMapper json,
      MeterRegistry metrics,
      @Value("${ott.watchlist.max-items:500}") int collectionLimit) {
    if (collectionLimit < 1 || collectionLimit > 10000)
      throw new IllegalArgumentException("Invalid collection limit");
    this.db = db;
    tx = new TransactionTemplate(manager);
    this.access = access;
    this.json = json;
    this.metrics = metrics;
    this.collectionLimit = collectionLimit;
  }

  public record Observation(
      UUID eventId,
      UUID profileId,
      UUID contentId,
      UUID deviceId,
      String grantToken,
      boolean offline,
      long sequence,
      long positionMillis,
      long durationMillis) {}

  public record Progress(
      UUID contentId,
      UUID sessionId,
      long epoch,
      long sequence,
      long positionMillis,
      long durationMillis,
      long version,
      Instant updatedAt) {}

  public record Receipt(boolean accepted, boolean duplicate, Progress progress) {}

  public record Collection(UUID contentId, Instant createdAt) {}

  public record Page<T>(List<T> items, String nextCursor) {}

  private record Cursor(Instant at, UUID id) {}

  public record History(UUID sessionId, UUID contentId, Instant startedAt, Instant lastAt) {}

  public record Reaction(UUID contentId, Integer rating, String reaction, long version) {}

  private record Row(UUID id, UUID profile, Progress progress) {}

  public Receipt observe(ViewerAccess.Identity identity, Observation in) {
    if (in.eventId() == null
        || in.profileId() == null
        || in.contentId() == null
        || in.deviceId() == null
        || in.grantToken() == null
        || in.grantToken().length() > 4096
        || in.sequence() < 1
        || in.positionMillis() < 0
        || in.durationMillis() < 1
        || in.durationMillis() > 86400000
        || in.positionMillis() > in.durationMillis()) throw fail(400, "INVALID_INPUT");
    var owner = access.profile(identity, in.profileId());
    var grant = access.grant(in.grantToken(), in.deviceId(), in.offline());
    if (!owner.accountId().equals(grant.accountId())
        || !in.profileId().equals(grant.profileId())
        || !in.contentId().equals(grant.contentId())
        || !in.deviceId().equals(grant.deviceId())
        || grant.epoch() < 1) throw fail(403, "PROGRESS_GRANT_DENIED");
    String fingerprint =
        hash(
            in.profileId()
                + ":"
                + in.contentId()
                + ":"
                + grant.sessionId()
                + ":"
                + grant.epoch()
                + ":"
                + in.sequence()
                + ":"
                + in.positionMillis()
                + ":"
                + in.durationMillis());
    return tx.execute(
        t -> {
          ensure(owner);
          lock(in.profileId(), in.contentId());
          int inserted =
              db.update(
                  "INSERT INTO progress_inbox(event_id,account_id,fingerprint,accepted)"
                      + " VALUES(?,?,?,FALSE) ON CONFLICT DO NOTHING",
                  in.eventId(),
                  owner.accountId(),
                  fingerprint);
          if (inserted == 0) {
            var prior =
                db.queryForMap("SELECT * FROM progress_inbox WHERE event_id=?", in.eventId());
            if (!owner.accountId().equals(prior.get("account_id"))
                || !fingerprint.equals(prior.get("fingerprint")))
              throw fail(409, "IDEMPOTENCY_CONFLICT");
            return new Receipt(
                (Boolean) prior.get("accepted"), true, current(in.profileId(), in.contentId()));
          }
          var sequence =
              db.queryForList(
                  "SELECT sequence,fingerprint FROM session_sequence WHERE session_id=?",
                  grant.sessionId());
          if (!sequence.isEmpty()) {
            long prior = ((Number) sequence.getFirst().get("sequence")).longValue();
            if (in.sequence() == prior
                && !fingerprint.equals(sequence.getFirst().get("fingerprint")))
              throw fail(409, "SEQUENCE_CONFLICT");
            if (in.sequence() <= prior) {
              metrics.counter("ott.watch.stale").increment();
              return new Receipt(
                  false, in.sequence() == prior, current(in.profileId(), in.contentId()));
            }
          }
          db.update(
              "INSERT INTO session_sequence(session_id,profile_id,content_id,sequence,fingerprint)"
                  + " VALUES(?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET"
                  + " sequence=EXCLUDED.sequence,fingerprint=EXCLUDED.fingerprint",
              grant.sessionId(),
              in.profileId(),
              in.contentId(),
              in.sequence(),
              fingerprint);
          db.update(
              "INSERT INTO watch_history(session_id,profile_id,content_id,epoch) VALUES(?,?,?,?) ON"
                  + " CONFLICT(session_id) DO UPDATE SET last_at=clock_timestamp()",
              grant.sessionId(),
              in.profileId(),
              in.contentId(),
              grant.epoch());
          var existing = rows(in.profileId(), in.contentId());
          boolean accepted =
              existing.isEmpty()
                  || grant.epoch() > existing.getFirst().progress().epoch()
                  || (grant.epoch() == existing.getFirst().progress().epoch()
                      && in.sequence() > existing.getFirst().progress().sequence());
          if (!existing.isEmpty()
              && grant.epoch() == existing.getFirst().progress().epoch()
              && !grant.sessionId().equals(existing.getFirst().progress().sessionId()))
            throw fail(409, "SESSION_EPOCH_CONFLICT");
          if (accepted) {
            db.update(
                "INSERT INTO"
                    + " watch_progress(profile_id,content_id,session_id,epoch,sequence,position_millis,duration_millis,version)"
                    + " VALUES(?,?,?,?,?,?,?,1) ON CONFLICT(profile_id,content_id) DO UPDATE SET"
                    + " session_id=EXCLUDED.session_id,epoch=EXCLUDED.epoch,sequence=EXCLUDED.sequence,position_millis=EXCLUDED.position_millis,duration_millis=EXCLUDED.duration_millis,version=watch_progress.version+1,updated_at=clock_timestamp()",
                in.profileId(),
                in.contentId(),
                grant.sessionId(),
                grant.epoch(),
                in.sequence(),
                in.positionMillis(),
                in.durationMillis());
            var row = rows(in.profileId(), in.contentId()).getFirst();
            event(
                row.id(),
                row.progress().version(),
                "watch.updated.v1",
                Map.of(
                    "profileId",
                    in.profileId().toString(),
                    "contentId",
                    in.contentId().toString(),
                    "sessionEpoch",
                    grant.epoch(),
                    "sequence",
                    in.sequence(),
                    "positionMillis",
                    in.positionMillis()));
            db.update("UPDATE progress_inbox SET accepted=TRUE WHERE event_id=?", in.eventId());
            metrics.counter("ott.watch.accepted").increment();
          } else metrics.counter("ott.watch.stale").increment();
          return new Receipt(accepted, false, current(in.profileId(), in.contentId()));
        });
  }

  public Progress resume(ViewerAccess.Identity identity, UUID profile, UUID content) {
    access.profile(identity, profile);
    var row = current(profile, content);
    if (row == null) throw fail(404, "PROGRESS_NOT_FOUND");
    return row;
  }

  public Page<Progress> recent(
      ViewerAccess.Identity identity, UUID profile, String after, int limit, boolean continuing) {
    access.profile(identity, profile);
    validateLimit(limit);
    var c = cursor(after);
    String extra = continuing ? " AND position_millis>0 AND position_millis<duration_millis" : "";
    var found =
        db
            .query(
                "SELECT * FROM watch_progress WHERE profile_id=?"
                    + extra
                    + " AND (?::timestamptz IS NULL OR"
                    + " (updated_at,content_id)<(?::timestamptz,?::uuid)) ORDER BY updated_at"
                    + " DESC,content_id DESC LIMIT ?",
                this::row,
                profile,
                stamp(c.at()),
                stamp(c.at()),
                c.id(),
                limit + 1)
            .stream()
            .map(Row::progress)
            .toList();
    var items = found.subList(0, Math.min(limit, found.size()));
    var last = items.isEmpty() ? null : items.getLast();
    return new Page<>(
        items, found.size() > limit ? encode(last.updatedAt(), last.contentId()) : null);
  }

  public Page<History> history(
      ViewerAccess.Identity identity, UUID profile, String after, int limit) {
    access.profile(identity, profile);
    validateLimit(limit);
    var c = cursor(after);
    var found =
        db.query(
            "SELECT * FROM watch_history WHERE profile_id=? AND (?::timestamptz IS NULL OR"
                + " (last_at,session_id)<(?::timestamptz,?::uuid)) ORDER BY last_at DESC,session_id"
                + " DESC LIMIT ?",
            (r, n) ->
                new History(
                    r.getObject("session_id", UUID.class),
                    r.getObject("content_id", UUID.class),
                    r.getTimestamp("started_at").toInstant(),
                    r.getTimestamp("last_at").toInstant()),
            profile,
            stamp(c.at()),
            stamp(c.at()),
            c.id(),
            limit + 1);
    var items = found.subList(0, Math.min(limit, found.size()));
    var last = items.isEmpty() ? null : items.getLast();
    return new Page<>(items, found.size() > limit ? encode(last.lastAt(), last.sessionId()) : null);
  }

  public void add(ViewerAccess.Identity identity, UUID profile, UUID content) {
    var owner = access.profile(identity, profile);
    access.catalog(identity, content, owner.maturity());
    tx.executeWithoutResult(
        t -> {
          ensure(owner);
          db.queryForObject(
              "SELECT profile_id FROM profile_owner WHERE profile_id=? FOR UPDATE",
              UUID.class,
              profile);
          int exists =
              db.queryForObject(
                  "SELECT count(*) FROM watchlist WHERE profile_id=? AND content_id=?",
                  Integer.class,
                  profile,
                  content);
          if (exists == 1) return;
          int count =
              db.queryForObject(
                  "SELECT count(*) FROM watchlist WHERE profile_id=?", Integer.class, profile);
          if (count >= collectionLimit) throw fail(409, "WATCHLIST_LIMIT_REACHED");
          db.update("INSERT INTO watchlist(profile_id,content_id) VALUES(?,?)", profile, content);
        });
  }

  public void remove(ViewerAccess.Identity identity, UUID profile, UUID content) {
    access.profile(identity, profile);
    db.update("DELETE FROM watchlist WHERE profile_id=? AND content_id=?", profile, content);
  }

  public Page<Collection> list(
      ViewerAccess.Identity identity, UUID profile, String after, int limit) {
    access.profile(identity, profile);
    validateLimit(limit);
    var c = cursor(after);
    var found =
        db.query(
            "SELECT * FROM watchlist WHERE profile_id=? AND (?::timestamptz IS NULL OR"
                + " (created_at,content_id)<(?::timestamptz,?::uuid)) ORDER BY created_at"
                + " DESC,content_id DESC LIMIT ?",
            (r, n) ->
                new Collection(
                    r.getObject("content_id", UUID.class),
                    r.getTimestamp("created_at").toInstant()),
            profile,
            stamp(c.at()),
            stamp(c.at()),
            c.id(),
            limit + 1);
    var items = found.subList(0, Math.min(limit, found.size()));
    var last = items.isEmpty() ? null : items.getLast();
    return new Page<>(
        items, found.size() > limit ? encode(last.createdAt(), last.contentId()) : null);
  }

  public Reaction rate(ViewerAccess.Identity identity, UUID profile, UUID content, Integer rating) {
    if (rating != null && (rating < 1 || rating > 5)) throw fail(400, "INVALID_RATING");
    return change(identity, profile, content, rating, null, true);
  }

  public Reaction react(
      ViewerAccess.Identity identity, UUID profile, UUID content, String reaction) {
    if (reaction != null && !List.of("LIKE", "DISLIKE").contains(reaction))
      throw fail(400, "INVALID_REACTION");
    return change(identity, profile, content, null, reaction, false);
  }

  private Reaction change(
      ViewerAccess.Identity identity,
      UUID profile,
      UUID content,
      Integer rating,
      String reaction,
      boolean stars) {
    var owner = access.profile(identity, profile);
    if (rating != null || reaction != null) access.catalog(identity, content, owner.maturity());
    return tx.execute(
        t -> {
          ensure(owner);
          lock(profile, content);
          db.update(
              "INSERT INTO reaction(profile_id,content_id) VALUES(?,?) ON CONFLICT DO NOTHING",
              profile,
              content);
          var stored =
              db.queryForMap(
                  "SELECT * FROM reaction WHERE profile_id=? AND content_id=? FOR UPDATE",
                  profile,
                  content);
          Integer nextRating = stars ? rating : (Integer) stored.get("rating");
          String nextReaction = stars ? (String) stored.get("reaction") : reaction;
          long version = ((Number) stored.get("version")).longValue();
          if (java.util.Objects.equals(nextRating, stored.get("rating"))
              && java.util.Objects.equals(nextReaction, stored.get("reaction")))
            return new Reaction(content, nextRating, nextReaction, version);
          version++;
          db.update(
              "UPDATE reaction SET rating=?,reaction=?,version=?,updated_at=clock_timestamp() WHERE"
                  + " profile_id=? AND content_id=?",
              nextRating,
              nextReaction,
              version,
              profile,
              content);
          var payload = new java.util.HashMap<String, Object>();
          payload.put("profileId", profile.toString());
          payload.put("contentId", content.toString());
          payload.put("rating", nextRating == null ? 0 : nextRating);
          payload.put("reaction", nextReaction);
          payload.put("removed", nextRating == null && nextReaction == null);
          event((UUID) stored.get("id"), version, "reaction.changed.v1", payload);
          return new Reaction(content, nextRating, nextReaction, version);
        });
  }

  private void ensure(ViewerAccess.Profile owner) {
    db.update(
        "INSERT INTO profile_owner VALUES(?,?) ON CONFLICT DO NOTHING",
        owner.profileId(),
        owner.accountId());
    UUID known =
        db.queryForObject(
            "SELECT account_id FROM profile_owner WHERE profile_id=?",
            UUID.class,
            owner.profileId());
    if (!owner.accountId().equals(known)) throw fail(403, "PROFILE_NOT_ALLOWED");
  }

  private void lock(UUID profile, UUID content) {
    byte[] digest = digest(profile + ":" + content);
    long key = java.nio.ByteBuffer.wrap(digest).getLong();
    db.queryForList("SELECT pg_advisory_xact_lock(?)", key);
  }

  private List<Row> rows(UUID profile, UUID content) {
    return db.query(
        "SELECT * FROM watch_progress WHERE profile_id=? AND content_id=?",
        this::row,
        profile,
        content);
  }

  private Progress current(UUID profile, UUID content) {
    var rows = rows(profile, content);
    return rows.isEmpty() ? null : rows.getFirst().progress();
  }

  private Row row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Row(
        r.getObject("id", UUID.class),
        r.getObject("profile_id", UUID.class),
        new Progress(
            r.getObject("content_id", UUID.class),
            r.getObject("session_id", UUID.class),
            r.getLong("epoch"),
            r.getLong("sequence"),
            r.getLong("position_millis"),
            r.getLong("duration_millis"),
            r.getLong("version"),
            r.getTimestamp("updated_at").toInstant()));
  }

  private void event(UUID aggregate, long version, String topic, Object payload) {
    try {
      db.update(
          "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload)"
              + " VALUES(?,?,?,?,?::jsonb)",
          UUID.randomUUID(),
          aggregate,
          version,
          topic,
          json.writeValueAsString(payload));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot encode watch event", e);
    }
  }

  private static Cursor cursor(String value) {
    if (value == null) return new Cursor(null, null);
    if (value.length() > 256) throw fail(400, "INVALID_CURSOR");
    try {
      var parts =
          new String(java.util.Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      throw fail(400, "INVALID_CURSOR");
    }
  }

  private static String encode(Instant at, UUID id) {
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((at + "|" + id).getBytes(StandardCharsets.UTF_8));
  }

  private static java.sql.Timestamp stamp(Instant at) {
    return at == null ? null : java.sql.Timestamp.from(at);
  }

  private static void validateLimit(int limit) {
    if (limit < 1 || limit > 100) throw fail(400, "INVALID_PAGE_SIZE");
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hash(String value) {
    return java.util.HexFormat.of().formatHex(digest(value));
  }

  private static WatchFailure fail(int status, String code) {
    return new WatchFailure(status, code);
  }
}
