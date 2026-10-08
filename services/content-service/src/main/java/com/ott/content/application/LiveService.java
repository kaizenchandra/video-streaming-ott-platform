package com.ott.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveService {
  private final JdbcTemplate db;
  private final CatalogService catalog;
  private final Clock clock;
  private final ObjectMapper json;
  private final MeterRegistry metrics;

  public LiveService(
      JdbcTemplate db,
      CatalogService catalog,
      Clock clock,
      ObjectMapper json,
      MeterRegistry metrics) {
    this.db = db;
    this.catalog = catalog;
    this.clock = clock;
    this.json = json;
    this.metrics = metrics;
  }

  public record Track(String language, String label) {}

  public record Event(
      UUID id,
      UUID contentId,
      String league,
      String homeTeam,
      String awayTeam,
      String referenceCountry,
      Instant startsAt,
      Instant endsAt,
      String state,
      long version,
      long feedEpoch,
      String packagerSubject,
      int dvrSeconds,
      long feedSequence,
      boolean healthy,
      boolean sealed,
      Instant firstSegmentAt,
      Instant lastSegmentAt,
      Instant healthReceivedAt,
      String manifestSha,
      List<Track> commentaries,
      List<String> subtitles,
      Instant endedAt,
      UUID replayContentId) {}

  public record Feed(
      long epoch,
      long sequence,
      boolean healthy,
      boolean sealed,
      Instant firstSegmentAt,
      Instant lastSegmentAt,
      String manifestSha,
      List<Track> commentaries,
      List<String> subtitles) {}

  public record Command(Event event, boolean duplicate) {}

  public record Highlight(
      UUID id, UUID contentId, String label, long startMillis, long endMillis) {}

  public record Page(List<Event> items, String nextCursor) {}

  public record Decision(
      boolean allowed,
      String code,
      UUID contentId,
      Instant expiresAt,
      String originPrefix,
      int maturity,
      Instant dvrFrom,
      Instant dvrUntil) {}

  @Transactional
  public Event create(
      UUID content,
      String league,
      String home,
      String away,
      String country,
      Instant start,
      Instant end,
      String packager,
      int dvr,
      String actor) {
    if (content == null
        || !text(league, 120)
        || !text(home, 120)
        || !text(away, 120)
        || country == null
        || !country.matches("[A-Z]{2}")
        || !text(packager, 255)
        || dvr < 0
        || dvr > 7200) throw fail(400, "INVALID_LIVE_INPUT");
    times(start, end);
    if (java.time.Duration.between(start, end).compareTo(java.time.Duration.ofHours(24)) > 0)
      throw fail(400, "INVALID_LIVE_TIMES");
    if (!catalog.get(content, "en", false).type().equals("SPORT"))
      throw fail(400, "SPORT_CONTENT_REQUIRED");
    UUID id = UUID.randomUUID();
    db.queryForObject("SELECT id FROM content_item WHERE id=? FOR UPDATE", UUID.class, content);
    if (db.queryForObject(
            "SELECT count(*) FROM live_event WHERE content_id=?", Integer.class, content)
        > 0) throw fail(409, "LIVE_CONTENT_ALREADY_LINKED");
    db.update(
        "INSERT INTO"
            + " live_event(id,content_id,league,home_team,away_team,reference_country,starts_at,ends_at,packager_subject,dvr_seconds)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        content,
        league,
        home,
        away,
        country,
        stamp(start),
        stamp(end),
        packager,
        dvr);
    var event = get(id);
    audit(event, "CREATE", actor);
    changed(event);
    return event;
  }

  public Event get(UUID id) {
    var rows = db.query("SELECT * FROM live_event WHERE id=?", this::row, id);
    if (rows.isEmpty()) throw fail(404, "LIVE_EVENT_NOT_FOUND");
    return rows.getFirst();
  }

  public Page schedule(Instant from, Instant to, String cursor, int limit) {
    times(from, to);
    if (java.time.Duration.between(from, to).toDays() > 366 || limit < 1 || limit > 100)
      throw fail(400, "INVALID_LIVE_QUERY");
    Instant afterAt = null;
    UUID afterId = null;
    if (cursor != null) {
      try {
        if (cursor.length() > 256) throw new IllegalArgumentException();
        var parts =
            new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                .split("\\|", -1);
        if (parts.length != 2) throw new IllegalArgumentException();
        afterAt = Instant.parse(parts[0]);
        afterId = UUID.fromString(parts[1]);
      } catch (IllegalArgumentException | java.time.DateTimeException e) {
        throw fail(400, "INVALID_CURSOR");
      }
    }
    var found =
        db.query(
            "SELECT * FROM live_event WHERE starts_at>=? AND starts_at<? AND (?::timestamptz IS"
                + " NULL OR (starts_at,id)>(?::timestamptz,?::uuid)) ORDER BY starts_at,id LIMIT ?",
            this::row,
            stamp(from),
            stamp(to),
            stamp(afterAt),
            stamp(afterAt),
            afterId,
            limit + 1);
    var items = found.subList(0, Math.min(limit, found.size()));
    var last = items.isEmpty() ? null : items.getLast();
    return new Page(
        items,
        found.size() > limit
            ? java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (last.startsAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8))
            : null);
  }

  @Transactional
  public Command transition(
      UUID id, long expected, String next, String key, UUID replay, String actor) {
    if (next == null || !List.of("READY", "LIVE", "ENDED", "ARCHIVED", "CANCELLED").contains(next))
      throw fail(400, "INVALID_LIVE_STATE");
    var e = locked(id);
    String fingerprint = hash(expected + ":" + next + ":" + replay);
    if (receipt(e, key, fingerprint)) return new Command(e, true);
    if (e.version() != expected) throw conflict();
    switch (next) {
      case "READY" -> {
        if (!e.state().equals("SCHEDULED") || !fresh(e) || e.sealed())
          throw fail(409, "LIVE_SOURCE_NOT_READY");
        var metadata = catalog.get(e.contentId(), "en", true);
        if (!metadata.type().equals("SPORT")) throw fail(409, "SPORT_CONTENT_REQUIRED");
        int rights =
            db.queryForObject(
                "SELECT count(*) FROM rights_grant WHERE content_id=? AND country=? AND NOT revoked"
                    + " AND start_at<=? AND end_at>?",
                Integer.class,
                e.contentId(),
                e.referenceCountry(),
                stamp(e.startsAt()),
                stamp(e.startsAt()));
        if (rights == 0) throw fail(409, "LIVE_SCHEDULE_RIGHTS_REQUIRED");
      }
      case "LIVE" -> {
        if (!e.state().equals("READY") || !fresh(e) || e.sealed())
          throw fail(409, "LIVE_SOURCE_NOT_READY");
        if (clock.instant().isBefore(e.startsAt()) || !clock.instant().isBefore(e.endsAt()))
          throw fail(409, "LIVE_OUTSIDE_SCHEDULE");
        if (!catalog.liveRights(e.contentId(), e.referenceCountry(), 18).allowed())
          throw fail(409, "LIVE_RIGHTS_REQUIRED");
      }
      case "ENDED" -> {
        if (!e.state().equals("LIVE") || !e.healthy() || !e.sealed() || e.lastSegmentAt() == null)
          throw fail(409, "SEALED_SOURCE_REQUIRED");
      }
      case "ARCHIVED" -> {
        if (!e.state().equals("ENDED") || replay == null || replay.equals(e.contentId()))
          throw fail(409, "REPLAY_CONTENT_REQUIRED");
        catalog.get(replay, "en", true);
      }
      case "CANCELLED" -> {
        if (!List.of("SCHEDULED", "READY").contains(e.state())) throw conflict();
      }
      default -> throw conflict();
    }
    if (replay != null && !next.equals("ARCHIVED")) throw fail(400, "INVALID_REPLAY_REFERENCE");
    db.update(
        "UPDATE live_event SET state=?,version=version+1,ended_at=CASE WHEN ?='ENDED' THEN ? ELSE"
            + " ended_at END,replay_content_id=CASE WHEN ?='ARCHIVED' THEN ? ELSE replay_content_id"
            + " END WHERE id=?",
        next,
        next,
        stamp(next.equals("ENDED") ? min(e.lastSegmentAt(), clock.instant()) : clock.instant()),
        next,
        replay,
        id);
    var result = get(id);
    audit(result, "STATE_" + next, actor);
    changed(result);
    return new Command(result, false);
  }

  @Transactional
  public Command reschedule(
      UUID id, long expected, String key, Instant start, Instant end, String actor) {
    times(start, end);
    if (java.time.Duration.between(start, end).compareTo(java.time.Duration.ofHours(24)) > 0)
      throw fail(400, "INVALID_LIVE_TIMES");
    var e = locked(id);
    if (receipt(e, key, hash(expected + ":RESCHEDULE:" + start + ":" + end)))
      return new Command(e, true);
    if (e.version() != expected || !List.of("SCHEDULED", "READY").contains(e.state()))
      throw conflict();
    db.update(
        "UPDATE live_event SET"
            + " starts_at=?,ends_at=?,state='SCHEDULED',version=version+1,feed_epoch=feed_epoch+1,feed_sequence=0,feed_fingerprint=NULL,healthy=false,sealed=false,health_received_at=NULL,first_segment_at=NULL,last_segment_at=NULL,manifest_sha=NULL,commentaries='[]'::jsonb,subtitles='[]'::jsonb"
            + " WHERE id=?",
        stamp(start),
        stamp(end),
        id);
    var result = get(id);
    audit(result, "RESCHEDULE", actor);
    changed(result);
    return new Command(result, false);
  }

  @Transactional
  public Command failover(UUID id, long expected, String key, String actor) {
    var e = locked(id);
    if (receipt(e, key, hash(expected + ":FAILOVER"))) return new Command(e, true);
    if (e.version() != expected || !List.of("SCHEDULED", "READY", "LIVE").contains(e.state()))
      throw conflict();
    db.update(
        "UPDATE live_event SET"
            + " version=version+1,feed_epoch=feed_epoch+1,feed_sequence=0,feed_fingerprint=NULL,healthy=false,sealed=false,health_received_at=NULL,first_segment_at=NULL,last_segment_at=NULL,manifest_sha=NULL"
            + " WHERE id=?",
        id);
    var result = get(id);
    audit(result, "FAILOVER", actor);
    changed(result);
    return new Command(result, false);
  }

  @Transactional
  public Event heartbeat(UUID id, String packager, Feed feed, String fingerprint) {
    if (feed == null || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
      throw fail(400, "INVALID_FEED_PROOF");
    var e = locked(id);
    if (!e.packagerSubject().equals(packager)) throw fail(403, "PACKAGER_NOT_ALLOWED");
    if (!List.of("SCHEDULED", "READY", "LIVE").contains(e.state()))
      throw fail(409, "LIVE_SOURCE_CLOSED");
    if (feed.epoch() != e.feedEpoch()) throw fail(409, "STALE_SOURCE_EPOCH");
    if (feed.sequence() < 1
        || feed.firstSegmentAt() == null
        || feed.lastSegmentAt() == null
        || feed.firstSegmentAt().isAfter(feed.lastSegmentAt())
        || feed.lastSegmentAt().isAfter(clock.instant().plusSeconds(5))
        || feed.manifestSha() == null
        || !feed.manifestSha().matches("[a-f0-9]{64}")
        || feed.commentaries() == null
        || feed.commentaries().size() > 8
        || feed.subtitles() == null
        || feed.subtitles().size() > 16) throw fail(400, "INVALID_FEED_PROOF");
    for (var t : feed.commentaries())
      if (t == null || !language(t.language()) || !text(t.label(), 120))
        throw fail(400, "INVALID_FEED_TRACK");
    for (var language : feed.subtitles())
      if (!language(language)) throw fail(400, "INVALID_FEED_TRACK");
    if (feed.commentaries().stream().map(Track::language).distinct().count()
            != feed.commentaries().size()
        || feed.subtitles().stream().distinct().count() != feed.subtitles().size())
      throw fail(400, "INVALID_FEED_TRACK");
    if (feed.sequence() == e.feedSequence()) {
      if (!fingerprint.equals(
          db.queryForObject(
              "SELECT feed_fingerprint FROM live_event WHERE id=?", String.class, id)))
        throw fail(409, "FEED_SEQUENCE_CONFLICT");
      return e;
    }
    if (feed.sequence() < e.feedSequence()) throw fail(409, "STALE_FEED_SEQUENCE");
    if (e.sealed()) throw fail(409, "SOURCE_ALREADY_SEALED");
    if (feed.healthy() && feed.lastSegmentAt().isBefore(clock.instant().minusSeconds(30)))
      throw fail(409, "STALE_MEDIA_PROOF");
    if (e.lastSegmentAt() != null && feed.lastSegmentAt().isBefore(e.lastSegmentAt()))
      throw fail(409, "MEDIA_TIMELINE_REGRESSED");
    db.update(
        "UPDATE live_event SET"
            + " feed_sequence=?,feed_fingerprint=?,healthy=?,sealed=?,first_segment_at=?,last_segment_at=?,health_received_at=?,manifest_sha=?,commentaries=?::jsonb,subtitles=?::jsonb"
            + " WHERE id=?",
        feed.sequence(),
        fingerprint,
        feed.healthy(),
        feed.sealed(),
        stamp(feed.firstSegmentAt()),
        stamp(feed.lastSegmentAt()),
        stamp(clock.instant()),
        feed.manifestSha(),
        encode(feed.commentaries()),
        encode(feed.subtitles()),
        id);
    metrics.counter("ott.live.feed.accepted").increment();
    return get(id);
  }

  @Transactional(readOnly = true)
  public Decision playback(UUID id, String mode, String country, int maturity, Instant seek) {
    if (mode == null
        || !List.of("LIVE", "DVR", "REPLAY").contains(mode)
        || country == null
        || !country.matches("[A-Z]{2}")
        || maturity < 0
        || maturity > 18) throw fail(400, "INVALID_LIVE_INPUT");
    var e = get(id);
    if (mode.equals("REPLAY")) {
      if (!e.state().equals("ARCHIVED") || e.replayContentId() == null)
        return denied("REPLAY_NOT_AVAILABLE");
      var rights = catalog.rights(e.replayContentId(), country, maturity, false);
      return rights.allowed()
          ? new Decision(
              true,
              "ALLOWED",
              e.replayContentId(),
              rights.expiresAt(),
              rights.originPrefix(),
              rights.maturity(),
              null,
              null)
          : denied(rights.code());
    }
    var rights = catalog.liveRights(e.contentId(), country, maturity);
    if (!rights.allowed()) return denied(rights.code());
    Instant end;
    if (e.state().equals("LIVE")) {
      if (!fresh(e) || mode.equals("LIVE") && e.sealed()) return denied("LIVE_SOURCE_UNAVAILABLE");
      end =
          min(
              e.healthReceivedAt().plusSeconds(30),
              e.lastSegmentAt().plusSeconds(30),
              rights.expiresAt());
    } else if (mode.equals("DVR")
        && e.state().equals("ENDED")
        && e.sealed()
        && e.endedAt() != null) {
      end = min(e.endedAt().plusSeconds(e.dvrSeconds()), rights.expiresAt());
    } else return denied("LIVE_NOT_AVAILABLE");
    if (!end.isAfter(clock.instant())) return denied("LIVE_WINDOW_EXPIRED");
    Instant from =
        e.firstSegmentAt().isAfter(e.lastSegmentAt().minusSeconds(e.dvrSeconds()))
            ? e.firstSegmentAt()
            : e.lastSegmentAt().minusSeconds(e.dvrSeconds());
    if (mode.equals("DVR")
        && (e.dvrSeconds() == 0
            || seek != null && (seek.isBefore(from) || seek.isAfter(e.lastSegmentAt()))))
      return denied("DVR_SEEK_OUTSIDE_WINDOW");
    if (mode.equals("LIVE") && seek != null) throw fail(400, "INVALID_LIVE_SEEK");
    return new Decision(
        true,
        "ALLOWED",
        e.contentId(),
        end,
        "live/" + id + "/e" + e.feedEpoch() + "/",
        rights.maturity(),
        from,
        e.lastSegmentAt());
  }

  @Transactional
  public Highlight highlight(
      UUID id, UUID content, String label, long start, long end, String actor) {
    var e = locked(id);
    if (!List.of("ENDED", "ARCHIVED").contains(e.state())
        || !text(label, 160)
        || start < 0
        || end <= start
        || end > 86400000
        || content == null
        || content.equals(e.contentId())) throw fail(400, "INVALID_HIGHLIGHT");
    catalog.get(content, "en", true);
    var existing =
        db.query(
            "SELECT * FROM live_highlight WHERE event_id=? AND content_id=?",
            this::highlightRow,
            id,
            content);
    if (!existing.isEmpty()) {
      var h = existing.getFirst();
      if (!h.label().equals(label) || h.startMillis() != start || h.endMillis() != end)
        throw fail(409, "HIGHLIGHT_CONFLICT");
      return h;
    }
    if (db.queryForObject("SELECT count(*) FROM live_highlight WHERE event_id=?", Integer.class, id)
        >= 100) throw fail(409, "HIGHLIGHT_LIMIT_REACHED");
    UUID key = UUID.randomUUID();
    db.update(
        "INSERT INTO live_highlight VALUES(?,?,?,?,?,?)", key, id, content, label, start, end);
    db.update("UPDATE live_event SET version=version+1 WHERE id=?", id);
    var updated = get(id);
    audit(updated, "HIGHLIGHT", actor);
    changed(updated);
    return new Highlight(key, content, label, start, end);
  }

  public List<Highlight> highlights(UUID id) {
    get(id);
    return db.query(
        "SELECT * FROM live_highlight WHERE event_id=? ORDER BY start_millis,id LIMIT 100",
        this::highlightRow,
        id);
  }

  private Highlight highlightRow(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Highlight(
        r.getObject("id", UUID.class),
        r.getObject("content_id", UUID.class),
        r.getString("label"),
        r.getLong("start_millis"),
        r.getLong("end_millis"));
  }

  private Event locked(UUID id) {
    db.queryForList("SELECT id FROM live_event WHERE id=? FOR UPDATE", id);
    return get(id);
  }

  private boolean receipt(Event e, String key, String fingerprint) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}"))
      throw fail(400, "INVALID_IDEMPOTENCY_KEY");
    if (db.update(
            "INSERT INTO live_command VALUES(?,?,?) ON CONFLICT DO NOTHING",
            e.id(),
            key,
            fingerprint)
        == 1) return false;
    String prior =
        db.queryForObject(
            "SELECT fingerprint FROM live_command WHERE event_id=? AND idempotency_key=?",
            String.class,
            e.id(),
            key);
    if (!fingerprint.equals(prior)) throw fail(409, "IDEMPOTENCY_CONFLICT");
    return true;
  }

  public boolean sourceHealthy(Event e) {
    return fresh(e) && !e.sealed();
  }

  private boolean fresh(Event e) {
    return e.healthy()
        && e.healthReceivedAt() != null
        && e.lastSegmentAt() != null
        && e.healthReceivedAt().plusSeconds(30).isAfter(clock.instant())
        && e.lastSegmentAt().plusSeconds(30).isAfter(clock.instant());
  }

  private void audit(Event e, String action, String actor) {
    if (!text(actor, 1024)) throw fail(400, "INVALID_ACTOR");
    db.update(
        "INSERT INTO live_audit VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",
        UUID.randomUUID(),
        e.id(),
        hash(actor),
        action,
        e.version());
  }

  private void changed(Event e) {
    db.update(
        "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload)"
            + " VALUES(?,?,?,'live.changed.v1',?::jsonb)",
        UUID.randomUUID(),
        e.id(),
        e.version(),
        encode(
            java.util.Map.of(
                "liveEventId",
                e.id().toString(),
                "state",
                e.state(),
                "startsAt",
                e.startsAt().toEpochMilli())));
    metrics.counter("ott.live.transition", "state", e.state()).increment();
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalArgumentException("Invalid live data", ex);
    }
  }

  private Event row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    try {
      return new Event(
          r.getObject("id", UUID.class),
          r.getObject("content_id", UUID.class),
          r.getString("league"),
          r.getString("home_team"),
          r.getString("away_team"),
          r.getString("reference_country"),
          at(r, "starts_at"),
          at(r, "ends_at"),
          r.getString("state"),
          r.getLong("version"),
          r.getLong("feed_epoch"),
          r.getString("packager_subject"),
          r.getInt("dvr_seconds"),
          r.getLong("feed_sequence"),
          r.getBoolean("healthy"),
          r.getBoolean("sealed"),
          at(r, "first_segment_at"),
          at(r, "last_segment_at"),
          at(r, "health_received_at"),
          r.getString("manifest_sha"),
          json.readValue(
              r.getString("commentaries"),
              new com.fasterxml.jackson.core.type.TypeReference<List<Track>>() {}),
          json.readValue(
              r.getString("subtitles"),
              new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}),
          at(r, "ended_at"),
          r.getObject("replay_content_id", UUID.class));
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Invalid live metadata", ex);
    }
  }

  private static Instant at(java.sql.ResultSet r, String field) throws java.sql.SQLException {
    var t = r.getTimestamp(field);
    return t == null ? null : t.toInstant();
  }

  private static boolean language(String value) {
    return value != null && value.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}");
  }

  private static boolean text(String value, int max) {
    return value != null
        && !value.isBlank()
        && value.length() <= max
        && value.chars().noneMatch(Character::isISOControl);
  }

  private static void times(Instant start, Instant end) {
    if (start == null
        || end == null
        || !start.isBefore(end)
        || java.time.Duration.between(start, end).toHours() > 24 * 366)
      throw fail(400, "INVALID_LIVE_TIMES");
  }

  private static java.sql.Timestamp stamp(Instant at) {
    return at == null ? null : java.sql.Timestamp.from(at);
  }

  private static Instant min(Instant... values) {
    return java.util.Arrays.stream(values).min(Instant::compareTo).orElseThrow();
  }

  private static Decision denied(String code) {
    return new Decision(false, code, null, null, null, 0, null, null);
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

  private static CatalogService.Failure fail(int status, String code) {
    return new CatalogService.Failure(status, code, code.replace('_', ' '));
  }

  private static CatalogService.Failure conflict() {
    return fail(409, "LIVE_VERSION_OR_STATE_CONFLICT");
  }
}
