package com.ott.experience.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SearchProjection {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final MeterRegistry metrics;

  public SearchProjection(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      ObjectMapper json,
      MeterRegistry metrics) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.json = json;
    this.metrics = metrics;
  }

  public boolean accept(UUID event, CatalogProjectionSource.Snapshot in) {
    validate(in);
    String data = encode(in);
    String fingerprint = hash(data);
    return tx.execute(t -> acceptWithin(event, in, data, fingerprint));
  }

  private boolean acceptWithin(
      UUID event, CatalogProjectionSource.Snapshot in, String data, String fingerprint) {
    if (event != null) {
      int inserted =
          db.update(
              "INSERT INTO search_inbox(event_id,fingerprint) VALUES(?,?) ON CONFLICT DO NOTHING",
              event,
              fingerprint);
      if (inserted == 0) {
        String prior =
            db.queryForObject(
                "SELECT fingerprint FROM search_inbox WHERE event_id=?", String.class, event);
        if (!fingerprint.equals(prior))
          throw new IllegalArgumentException("Conflicting publication event");
        return false;
      }
    }
    int changed =
        db.update(
            "INSERT INTO search_document(content_id,version,snapshot) VALUES(?,?,?::jsonb) ON"
                + " CONFLICT(content_id) DO UPDATE SET"
                + " version=EXCLUDED.version,snapshot=EXCLUDED.snapshot,updated_at=clock_timestamp(),next_attempt_at=clock_timestamp(),claim_token=NULL,claimed_until=NULL"
                + " WHERE search_document.version<EXCLUDED.version",
            in.contentId(),
            in.version(),
            data);
    if (changed == 0) {
      var current =
          db.queryForMap(
              "SELECT version,snapshot FROM search_document WHERE content_id=?", in.contentId());
      if (((Number) current.get("version")).longValue() == in.version()
          && !decode(current.get("snapshot").toString()).equals(in))
        throw new IllegalArgumentException("Conflicting immutable snapshot");
      metrics.counter("ott.search.projection.stale").increment();
    } else metrics.counter("ott.search.projection.accepted").increment();
    return changed == 1;
  }

  public record Work(
      UUID content, long version, CatalogProjectionSource.Snapshot snapshot, int attempts) {}

  public List<Work> claim(UUID token) {
    return tx.execute(
        t ->
            db.query(
                """
                UPDATE search_document SET claim_token=?,claimed_until=clock_timestamp()+interval '120 seconds',attempts=attempts+1
                WHERE content_id IN(SELECT content_id FROM search_document WHERE indexed_version<version AND next_attempt_at<=clock_timestamp() AND (claimed_until IS NULL OR claimed_until<clock_timestamp()) ORDER BY content_id FOR UPDATE SKIP LOCKED LIMIT 2)
                RETURNING content_id,version,snapshot,attempts
                """,
                (r, n) ->
                    new Work(
                        r.getObject(1, UUID.class),
                        r.getLong(2),
                        decode(r.getString(3)),
                        r.getInt(4)),
                token));
  }

  public void indexed(UUID token, Work work) {
    db.update(
        "UPDATE search_document SET indexed_version=?,claim_token=NULL,claimed_until=NULL WHERE"
            + " content_id=? AND version=? AND claim_token=?",
        work.version(),
        work.content(),
        work.version(),
        token);
    metrics.counter("ott.search.index.published").increment();
  }

  public void failed(UUID token, Work work) {
    long seconds =
        Math.min(300, 1L << Math.min(8, work.attempts()))
            + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
    db.update(
        "UPDATE search_document SET"
            + " claim_token=NULL,claimed_until=NULL,next_attempt_at=clock_timestamp()+(? * interval"
            + " '1 second') WHERE content_id=? AND claim_token=?",
        seconds,
        work.content(),
        token);
    metrics.counter("ott.search.index.failed").increment();
  }

  public List<CatalogProjectionSource.Snapshot> recent(int limit) {
    return db.query(
        "SELECT snapshot FROM search_document ORDER BY (snapshot->>'publishedAt')::timestamptz"
            + " DESC,content_id LIMIT ?",
        (r, n) -> decode(r.getString(1)),
        limit);
  }

  public CatalogProjectionSource.Snapshot get(UUID id) {
    var rows =
        db.query(
            "SELECT snapshot FROM search_document WHERE content_id=?",
            (r, n) -> decode(r.getString(1)),
            id);
    if (rows.isEmpty()) throw new WatchFailure(404, "CONTENT_NOT_FOUND");
    return rows.getFirst();
  }

  public UUID rebuild() {
    return tx.execute(
        t -> {
          var existing =
              db.queryForList("SELECT id FROM search_rebuild WHERE state='RUNNING'", UUID.class);
          if (!existing.isEmpty()) return existing.getFirst();
          UUID id = UUID.randomUUID();
          db.update(
              "INSERT INTO search_rebuild(id,state) VALUES(?,'RUNNING') ON CONFLICT DO NOTHING",
              id);
          return db.queryForObject(
              "SELECT id FROM search_rebuild WHERE state='RUNNING'", UUID.class);
        });
  }

  public record RebuildStatus(UUID jobId, String state, long pendingDocuments) {}

  public RebuildStatus rebuildStatus(UUID id) {
    var state = db.queryForList("SELECT state FROM search_rebuild WHERE id=?", String.class, id);
    if (state.isEmpty()) throw new WatchFailure(404, "REBUILD_NOT_FOUND");
    long pending =
        db.queryForObject(
            "SELECT count(*) FROM search_document WHERE indexed_version<version", Long.class);
    return new RebuildStatus(
        id,
        state.getFirst().equals("RUNNING") ? "SCANNING" : pending == 0 ? "COMPLETE" : "INDEXING",
        pending);
  }

  public record Rebuild(UUID id, UUID after) {}

  public List<Rebuild> claimRebuild(UUID token) {
    return tx.execute(
        t ->
            db.query(
                "UPDATE search_rebuild SET claim_token=?,claimed_until=clock_timestamp()+interval"
                    + " '30 seconds' WHERE id IN(SELECT id FROM search_rebuild WHERE"
                    + " state='RUNNING' AND (claimed_until IS NULL OR"
                    + " claimed_until<clock_timestamp()) FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING"
                    + " id,after_id",
                (r, n) -> new Rebuild(r.getObject(1, UUID.class), r.getObject(2, UUID.class)),
                token));
  }

  public void rebuilt(UUID token, Rebuild job, CatalogProjectionSource.Page page) {
    tx.executeWithoutResult(
        t -> {
          for (var snapshot : page.items()) {
            validate(snapshot);
            acceptWithin(null, snapshot, encode(snapshot), hash(encode(snapshot)));
            db.update(
                "UPDATE search_document SET indexed_version=0,next_attempt_at=clock_timestamp()"
                    + " WHERE content_id=?",
                snapshot.contentId());
          }
          db.update(
              "UPDATE search_rebuild SET after_id=?,state=?,claim_token=NULL,claimed_until=NULL"
                  + " WHERE id=? AND claim_token=?",
              page.nextCursor(),
              page.nextCursor() == null ? "COMPLETE" : "RUNNING",
              job.id(),
              token);
        });
  }

  public CatalogProjectionSource.Snapshot decode(String data) {
    try {
      return json.readValue(data, CatalogProjectionSource.Snapshot.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Invalid projection state", e);
    }
  }

  private String encode(CatalogProjectionSource.Snapshot in) {
    try {
      return json.writer()
          .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(in);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid snapshot", e);
    }
  }

  private static void validate(CatalogProjectionSource.Snapshot in) {
    if (in == null
        || in.contentId() == null
        || in.version() < 1
        || !List.of("MOVIE", "SERIES", "SEASON", "EPISODE", "SPORT").contains(in.contentType())
        || in.maturity() < 0
        || in.maturity() > 18
        || in.localizations() == null
        || in.localizations().isEmpty()
        || in.localizations().size() > 32
        || in.genres() == null
        || in.genres().size() > 10
        || in.actors() == null
        || in.actors().size() > 20
        || in.publishedAt() == null) throw new IllegalArgumentException("Invalid snapshot");
    for (var e : in.localizations().entrySet()) {
      if (e.getKey().length() > 35
          || !e.getKey().matches("[A-Za-z0-9-]{1,35}")
          || e.getValue().title() == null
          || e.getValue().title().isBlank()
          || e.getValue().title().length() > 200
          || e.getValue().synopsis() == null
          || e.getValue().synopsis().length() > 4000)
        throw new IllegalArgumentException("Invalid snapshot localization");
    }
  }

  private static String hash(String in) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(in.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
