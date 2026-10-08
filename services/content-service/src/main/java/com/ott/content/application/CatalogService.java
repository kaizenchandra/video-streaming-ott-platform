package com.ott.content.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Small owner-local JDBC use cases; no cross-owner database access. */
@Service
public class CatalogService {
  private final JdbcTemplate db;
  private final Clock clock;
  private final MeterRegistry metrics;

  public CatalogService(JdbcTemplate db, Clock clock, MeterRegistry metrics) {
    this.db = db;
    this.clock = clock;
    this.metrics = metrics;
  }

  public record Item(
      UUID id,
      String type,
      UUID parentId,
      int maturity,
      String state,
      long version,
      String locale,
      String title,
      String synopsis) {}

  public record Rights(
      UUID id,
      UUID contentId,
      String country,
      Instant startAt,
      Instant endAt,
      boolean offlineAllowed,
      long version) {}

  public record Decision(
      boolean allowed,
      String code,
      Instant expiresAt,
      boolean offlineAllowed,
      String originPrefix,
      int maturity) {}

  @Transactional
  public Item create(
      String type, UUID parent, int maturity, String locale, String title, String synopsis) {
    if (!List.of("MOVIE", "SERIES", "SEASON", "EPISODE", "SPORT").contains(type)
        || maturity < 0
        || maturity > 18) throw invalid();
    validateLocale(locale);
    if (title == null
        || title.isBlank()
        || title.length() > 200
        || synopsis == null
        || synopsis.length() > 4000) throw invalid();
    if (type.equals("SEASON") || type.equals("EPISODE")) {
      if (parent == null) throw invalid();
      String required = type.equals("SEASON") ? "SERIES" : "SEASON";
      var parents =
          db.queryForList("SELECT type FROM content_item WHERE id=?", String.class, parent);
      if (parents.isEmpty() || !required.equals(parents.getFirst()))
        throw new Failure(400, "INVALID_HIERARCHY", "Invalid catalog parent");
    } else if (parent != null) throw invalid();
    var id = UUID.randomUUID();
    db.update(
        "INSERT INTO content_item(id,type,parent_id,maturity,state,version) VALUES"
            + " (?,?,?,?,'DRAFT',1)",
        id,
        type,
        parent,
        maturity);
    db.update(
        "INSERT INTO localization(content_id,locale,title,synopsis) VALUES (?,?,?,?)",
        id,
        locale,
        title.strip(),
        synopsis);
    return get(id, locale, false);
  }

  @Transactional(readOnly = true)
  public Item get(UUID id, String locale, boolean publishedOnly) {
    validateLocale(locale);
    if (publishedOnly)
      return published(id, locale, null, null, 1).stream()
          .findFirst()
          .orElseThrow(() -> new Failure(404, "CONTENT_NOT_AVAILABLE", "Content not available"));
    var rows =
        db.query(
            """
            SELECT c.*,l.locale,l.title,l.synopsis FROM content_item c JOIN LATERAL
            (SELECT * FROM localization WHERE content_id=c.id ORDER BY (locale=?) DESC,locale LIMIT 1) l ON TRUE
            WHERE c.id=? AND (?=false OR c.state='PUBLISHED')
            """,
            (r, n) ->
                new Item(
                    r.getObject("id", UUID.class),
                    r.getString("type"),
                    r.getObject("parent_id", UUID.class),
                    r.getInt("maturity"),
                    r.getString("state"),
                    r.getLong("version"),
                    r.getString("locale"),
                    r.getString("title"),
                    r.getString("synopsis")),
            locale,
            id,
            publishedOnly);
    return rows.stream()
        .findFirst()
        .orElseThrow(() -> new Failure(404, "CONTENT_NOT_AVAILABLE", "Content not available"));
  }

  public record Page(List<Item> items, UUID nextCursor) {}

  @Transactional(readOnly = true)
  public Page browse(String locale, String type, UUID after, int limit) {
    validateLocale(locale);
    if (limit < 1
        || limit > 100
        || type != null && !List.of("MOVIE", "SERIES", "SEASON", "EPISODE", "SPORT").contains(type))
      throw invalid();
    var rows = published(null, locale, type, after, limit + 1);
    boolean more = rows.size() > limit;
    var items = List.copyOf(more ? rows.subList(0, limit) : rows);
    return new Page(items, more ? items.getLast().id() : null);
  }

  @Transactional
  public Item revise(UUID id, long expected, String locale) {
    if (db.update(
            "UPDATE content_item SET state='DRAFT',version=version+1 WHERE id=? AND"
                + " state='PUBLISHED' AND version=?",
            id,
            expected)
        != 1) throw conflict();
    return get(id, locale, false);
  }

  private List<Item> published(UUID id, String locale, String type, UUID after, int limit) {
    return db.query(
        """
        SELECT c.id,p.version,p.metadata->>'type' AS type,(p.metadata->>'parentId')::uuid AS parent_id,
         (p.metadata->>'maturity')::int AS maturity,loc.locale,loc.data->>'title' AS title,loc.data->>'synopsis' AS synopsis
        FROM content_item c JOIN LATERAL
         (SELECT * FROM published_version WHERE content_id=c.id ORDER BY version DESC LIMIT 1) p ON TRUE
        JOIN LATERAL
         (SELECT key AS locale,value AS data FROM jsonb_each(p.metadata->'localizations')
          ORDER BY (key=?) DESC,key LIMIT 1) loc ON TRUE
        WHERE (?::uuid IS NULL OR c.id=?::uuid) AND (?::varchar IS NULL OR p.metadata->>'type'=?)
         AND (?::uuid IS NULL OR c.id>?::uuid) ORDER BY c.id LIMIT ?
        """,
        (r, n) ->
            new Item(
                r.getObject("id", UUID.class),
                r.getString("type"),
                r.getObject("parent_id", UUID.class),
                r.getInt("maturity"),
                "PUBLISHED",
                r.getLong("version"),
                r.getString("locale"),
                r.getString("title"),
                r.getString("synopsis")),
        locale,
        id,
        id,
        type,
        type,
        after,
        after,
        limit);
  }

  @Transactional
  public void revokeRights(UUID grantId) {
    var content =
        db.query(
            "SELECT content_id FROM rights_grant WHERE id=? AND revoked=false FOR UPDATE",
            (r, n) -> r.getObject(1, UUID.class),
            grantId);
    if (content.isEmpty()) return;
    var id = content.getFirst();
    long version =
        db.queryForObject(
            "UPDATE content_item SET policy_version=policy_version+1 WHERE id=? RETURNING"
                + " policy_version",
            Long.class,
            id);
    db.update("UPDATE rights_grant SET revoked=true,version=? WHERE id=?", version, grantId);
    db.update(
        "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload) VALUES"
            + " (?,?,?,'rights.changed.v1',jsonb_build_object('contentId',?::text,'rightsVersion',?::bigint,'revoked',true))",
        UUID.randomUUID(),
        id,
        version,
        id,
        version);
  }

  @Transactional
  public Item localize(UUID id, String locale, String title, String synopsis, long expected) {
    validateLocale(locale);
    if (title == null
        || title.isBlank()
        || title.length() > 200
        || synopsis == null
        || synopsis.length() > 4000) throw invalid();
    changeDraft(id, expected);
    int existing =
        db.queryForObject(
            "SELECT count(*) FROM localization WHERE content_id=?", Integer.class, id);
    int same =
        db.queryForObject(
            "SELECT count(*) FROM localization WHERE content_id=? AND locale=?",
            Integer.class,
            id,
            locale);
    if (existing >= 32 && same == 0)
      throw new Failure(
          409, "LOCALIZATION_LIMIT_REACHED", "Maximum 32 locales per content version");
    db.update(
        "INSERT INTO localization(content_id,locale,title,synopsis) VALUES (?,?,?,?) ON"
            + " CONFLICT(content_id,locale) DO UPDATE SET"
            + " title=excluded.title,synopsis=excluded.synopsis",
        id,
        locale,
        title.strip(),
        synopsis);
    return get(id, locale, false);
  }

  @Transactional
  public Item review(UUID id, long expected, String locale) {
    if (db.update(
            "UPDATE content_item SET state='REVIEW',version=version+1 WHERE id=? AND state='DRAFT'"
                + " AND version=?",
            id,
            expected)
        != 1) throw conflict();
    return get(id, locale, false);
  }

  @Transactional
  public Rights grant(UUID id, String country, Instant start, Instant end, boolean offline) {
    if (country == null
        || !country.matches("[A-Z]{2}")
        || start == null
        || end == null
        || !start.isBefore(end)
        || start.getNano() % 1000 != 0
        || end.getNano() % 1000 != 0) throw invalid();
    get(id, "en", false);
    db.queryForObject("SELECT version FROM content_item WHERE id=? FOR UPDATE", Long.class, id);
    long version =
        db.queryForObject(
            "UPDATE content_item SET policy_version=policy_version+1 WHERE id=? RETURNING"
                + " policy_version",
            Long.class,
            id);
    var rightsId = UUID.randomUUID();
    db.update(
        "INSERT INTO rights_grant(id,content_id,country,start_at,end_at,offline_allowed,version)"
            + " VALUES (?,?,?,?,?,?,?)",
        rightsId,
        id,
        country,
        java.sql.Timestamp.from(start),
        java.sql.Timestamp.from(end),
        offline,
        version);
    db.update(
        "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload) VALUES"
            + " (?,?,?,'rights.changed.v1',jsonb_build_object('contentId',?::text,'rightsVersion',?::bigint,'revoked',false))",
        UUID.randomUUID(),
        id,
        version,
        id,
        version);
    return new Rights(rightsId, id, country, start, end, offline, version);
  }

  @Transactional
  public Item publish(UUID id, long expected, long assetVersion, String locale) {
    var item = get(id, locale, false);
    if (!item.state().equals("REVIEW") || item.version() != expected) throw conflict();
    if (db.queryForObject(
            "SELECT count(*) FROM ready_asset WHERE content_id=? AND asset_version=?",
            Integer.class,
            id,
            assetVersion)
        != 1) throw new Failure(409, "MEDIA_NOT_READY", "Validated media required");
    if (db.update(
            "UPDATE content_item SET state='PUBLISHED',version=version+1 WHERE id=? AND"
                + " state='REVIEW' AND version=?",
            id,
            expected)
        != 1) throw conflict();
    long version = expected + 1;
    db.update(
        """
        INSERT INTO published_version(content_id,version,asset_version,metadata)
        SELECT id,?,?,jsonb_build_object('type',type,'parentId',parent_id,'maturity',maturity,'genres',genres,'actors',actors,'releaseDate',release_date,'localizations',
         (SELECT jsonb_object_agg(locale,jsonb_build_object('title',title,'synopsis',synopsis)) FROM localization WHERE content_id=c.id))
        FROM content_item c WHERE id=?
        """,
        version,
        assetVersion,
        id);
    db.update(
        "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload) VALUES"
            + " (?,?,?,'catalog.published.v1',jsonb_build_object('contentId',?::text,'contentVersion',?::bigint,'title',?::text,'contentType',?::text,'maturity',?::int,'locales',(SELECT"
            + " jsonb_agg(locale ORDER BY locale) FROM localization WHERE content_id=?)))",
        UUID.randomUUID(),
        id,
        version,
        id,
        version,
        item.title(),
        item.type(),
        item.maturity(),
        id);
    metrics.counter("ott.catalog.published").increment();
    return get(id, locale, false);
  }

  @Transactional
  public boolean mediaReady(
      UUID eventId, UUID content, long version, String prefix, String checksum) {
    if (version < 1
        || prefix == null
        || !prefix.equals("vod/" + content + "/v" + version + "/")
        || checksum == null
        || !checksum.matches("[a-f0-9]{64}")) throw invalid();
    if (db.update("INSERT INTO inbox(event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId) == 0)
      return false;
    db.update(
        "INSERT INTO ready_asset(content_id,asset_version,origin_prefix,checksum) VALUES (?,?,?,?)"
            + " ON CONFLICT DO NOTHING",
        content,
        version,
        prefix,
        checksum);
    return true;
  }

  @Transactional(readOnly = true)
  public Decision rights(UUID content, String country, int maturity, boolean offline) {
    if (db.queryForObject(
            "SELECT count(*) FROM live_event WHERE content_id=?", Integer.class, content)
        > 0) return denied("LIVE_EVENT_REQUIRED");
    return evaluateRights(content, country, maturity, offline);
  }

  @Transactional(readOnly = true)
  public Decision liveRights(UUID content, String country, int maturity) {
    return evaluateRights(content, country, maturity, false);
  }

  private Decision evaluateRights(UUID content, String country, int maturity, boolean offline) {
    if (country == null || !country.matches("[A-Z]{2}") || maturity < 0 || maturity > 18)
      throw invalid();
    var items = published(content, "en", null, null, 1);
    if (items.isEmpty()) return denied("CONTENT_NOT_AVAILABLE");
    int rating = items.getFirst().maturity();
    if (rating > maturity) return denied("PARENTAL_CONTROL_RESTRICTED");
    var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var grants =
        db.query(
            "SELECT end_at,offline_allowed FROM rights_grant WHERE content_id=? AND country=? AND"
                + " revoked=false AND start_at<=? AND end_at>? AND (?=false OR offline_allowed)"
                + " ORDER BY end_at DESC LIMIT 1",
            (r, n) ->
                new Rights(
                    null, content, country, now, r.getTimestamp(1).toInstant(), r.getBoolean(2), 0),
            content,
            country,
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now),
            offline);
    if (grants.isEmpty()) return denied("REGION_OR_RIGHTS_NOT_ALLOWED");
    String prefix =
        db.queryForObject(
            "SELECT a.origin_prefix FROM published_version p JOIN ready_asset a ON"
                + " a.content_id=p.content_id AND a.asset_version=p.asset_version WHERE"
                + " p.content_id=? ORDER BY p.version DESC LIMIT 1",
            String.class,
            content);
    return new Decision(
        true,
        "ALLOWED",
        grants.getFirst().endAt(),
        grants.getFirst().offlineAllowed(),
        prefix,
        rating);
  }

  private static Decision denied(String code) {
    return new Decision(false, code, null, false, null, 0);
  }

  public record Localized(String title, String synopsis) {}

  public record Snapshot(
      UUID contentId,
      long version,
      String contentType,
      int maturity,
      java.util.Map<String, Localized> localizations,
      List<String> genres,
      List<String> actors,
      java.time.LocalDate releaseDate,
      Instant publishedAt) {}

  public record Snapshots(List<Snapshot> items, UUID nextCursor) {}

  @Transactional
  public Item taxonomy(
      UUID id,
      long expected,
      List<String> genres,
      List<String> actors,
      java.time.LocalDate release,
      String locale) {
    if (genres == null
        || actors == null
        || genres.size() > 10
        || actors.size() > 20
        || genres.stream().anyMatch(g -> g == null || !g.matches("[a-z0-9][a-z0-9-]{0,39}"))
        || actors.stream()
            .anyMatch(
                a ->
                    a == null
                        || a.isBlank()
                        || a.length() > 120
                        || a.chars().anyMatch(Character::isISOControl))
        || genres.stream().distinct().count() != genres.size()
        || actors.stream().distinct().count() != actors.size()) throw invalid();
    validateLocale(locale);
    changeDraft(id, expected);
    try {
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      db.update(
          "UPDATE content_item SET genres=?::jsonb,actors=?::jsonb,release_date=? WHERE id=?",
          mapper.writeValueAsString(genres),
          mapper.writeValueAsString(actors),
          release == null ? null : java.sql.Date.valueOf(release),
          id);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot encode metadata", e);
    }
    return get(id, locale, false);
  }

  @Transactional(readOnly = true)
  public Snapshot snapshot(UUID id, long version) {
    if (version < 1) throw invalid();
    var rows =
        db.query(
            "SELECT * FROM published_version WHERE content_id=? AND version=?",
            this::snapshotRow,
            id,
            version);
    if (rows.isEmpty())
      throw new Failure(404, "PUBLISHED_VERSION_NOT_FOUND", "Published version unavailable");
    return rows.getFirst();
  }

  @Transactional(readOnly = true)
  public Snapshots snapshots(UUID after, int limit) {
    if (limit < 1 || limit > 100) throw invalid();
    var rows =
        db.query(
            "SELECT DISTINCT ON(content_id) * FROM published_version WHERE (?::uuid IS NULL OR"
                + " content_id>?::uuid) ORDER BY content_id,version DESC LIMIT ?",
            this::snapshotRow,
            after,
            after,
            limit + 1);
    var items = rows.subList(0, Math.min(limit, rows.size()));
    return new Snapshots(items, rows.size() > limit ? items.getLast().contentId() : null);
  }

  @Transactional(readOnly = true)
  public List<UUID> permitted(List<UUID> ids, String country, int maturity) {
    if (ids == null
        || ids.size() > 100
        || ids.stream().anyMatch(java.util.Objects::isNull)
        || country == null
        || !country.matches("[A-Z]{2}")
        || maturity < 0
        || maturity > 18) throw invalid();
    if (ids.isEmpty()) return List.of();
    var params =
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("ids", ids)
            .addValue("country", country)
            .addValue("maturity", maturity)
            .addValue("now", java.sql.Timestamp.from(clock.instant()));
    return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(db)
        .query(
            """
            SELECT p.content_id FROM (SELECT DISTINCT ON(content_id) content_id,metadata FROM published_version WHERE content_id IN(:ids) ORDER BY content_id,version DESC) p
            WHERE (p.metadata->>'maturity')::integer<=:maturity AND EXISTS(SELECT 1 FROM rights_grant r WHERE r.content_id=p.content_id AND r.country=:country AND NOT r.revoked AND r.start_at<=:now AND r.end_at>:now)
            """,
            params,
            (r, n) -> r.getObject(1, UUID.class));
  }

  private Snapshot snapshotRow(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    try {
      var node =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getString("metadata"));
      var locales = new java.util.TreeMap<String, Localized>();
      node.path("localizations")
          .fields()
          .forEachRemaining(
              e ->
                  locales.put(
                      e.getKey(),
                      new Localized(
                          e.getValue().path("title").asText(),
                          e.getValue().path("synopsis").asText())));
      var genres = new java.util.ArrayList<String>();
      node.path("genres").forEach(v -> genres.add(v.asText()));
      var actors = new java.util.ArrayList<String>();
      node.path("actors").forEach(v -> actors.add(v.asText()));
      var release = node.path("releaseDate");
      return new Snapshot(
          r.getObject("content_id", UUID.class),
          r.getLong("version"),
          node.path("type").asText(),
          node.path("maturity").asInt(),
          java.util.Map.copyOf(locales),
          List.copyOf(genres),
          List.copyOf(actors),
          release.isMissingNode() || release.isNull()
              ? null
              : java.time.LocalDate.parse(release.asText()),
          r.getTimestamp("published_at").toInstant());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Invalid published metadata", e);
    }
  }

  private void changeDraft(UUID id, long expected) {
    if (db.update(
            "UPDATE content_item SET version=version+1 WHERE id=? AND state='DRAFT' AND version=?",
            id,
            expected)
        != 1) throw conflict();
  }

  private static void validateLocale(String locale) {
    try {
      if (locale == null || locale.isBlank() || locale.length() > 35) throw invalid();
      new Locale.Builder().setLanguageTag(locale).build();
    } catch (java.util.IllformedLocaleException e) {
      throw invalid();
    }
  }

  private static Failure invalid() {
    return new Failure(400, "INVALID_REQUEST", "Invalid catalog fields");
  }

  private static Failure conflict() {
    return new Failure(409, "VERSION_OR_STATE_CONFLICT", "Catalog version or state changed");
  }

  public static class Failure extends RuntimeException {
    private final int status;
    private final String code;

    public Failure(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }

    public int status() {
      return status;
    }

    public String code() {
      return code;
    }
  }
}
