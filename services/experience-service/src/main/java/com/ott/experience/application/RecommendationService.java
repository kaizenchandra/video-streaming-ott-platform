package com.ott.experience.application;

import com.ott.experience.adapter.outbound.search.OpenSearchCatalog;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "ott.recommendations.enabled", havingValue = "true")
public class RecommendationService {
  private final JdbcTemplate db;
  private final SearchProjection projection;
  private final RecommendationSignals signals;
  private final DiscoveryService policy;
  private final ViewerAccess viewers;
  private final OpenSearchCatalog views;
  private final Clock clock;

  public RecommendationService(
      JdbcTemplate db,
      SearchProjection projection,
      RecommendationSignals signals,
      DiscoveryService policy,
      ViewerAccess viewers,
      OpenSearchCatalog views,
      Clock clock) {
    this.db = db;
    this.projection = projection;
    this.signals = signals;
    this.policy = policy;
    this.viewers = viewers;
    this.views = views;
    this.clock = clock;
  }

  public record Rail(String name, String basis, List<OpenSearchCatalog.Hit> items) {}

  public record Recommendations(
      List<Rail> rails, boolean personalizationEnabled, boolean personalized) {}

  private record Ranked(List<CatalogProjectionSource.Snapshot> items, boolean fallback) {}

  public Recommendations recommend(
      ViewerAccess.Identity identity,
      UUID profile,
      String country,
      String locale,
      UUID seed,
      int limit) {
    if (country == null
        || !country.matches("[A-Z]{2}")
        || locale == null
        || !locale.matches("[A-Za-z0-9-]{1,35}")
        || limit < 1
        || limit > 20) throw new WatchFailure(400, "INVALID_RECOMMENDATION_INPUT");
    var owner = viewers.profile(identity, profile);
    boolean personalized = signals.enabled(profile);
    var rails = new ArrayList<Rail>();
    rails.add(rail("recent-releases", recent(), country, locale, owner.maturity(), limit));
    rails.add(ranking("popular-opt-in", 90, country, locale, owner.maturity(), limit));
    rails.add(ranking("trending-opt-in", 1, country, locale, owner.maturity(), limit));
    if (seed != null) {
      var chosen = projection.get(seed);
      if (policy
          .permitted(views.fallback(List.of(chosen), locale), country, owner.maturity())
          .isEmpty()) throw new WatchFailure(404, "CONTENT_NOT_AVAILABLE");
      rails.add(
          rail("same-genre", related(chosen, "genres"), country, locale, owner.maturity(), limit));
      rails.add(
          rail("same-actors", related(chosen, "actors"), country, locale, owner.maturity(), limit));
    }
    if (personalized) {
      var latest =
          db.queryForList(
              "SELECT content_id FROM watch_progress WHERE profile_id=? ORDER BY updated_at"
                  + " DESC,content_id LIMIT 1",
              UUID.class,
              profile);
      if (!latest.isEmpty()) {
        try {
          var chosen = projection.get(latest.getFirst());
          if (!policy
              .permitted(views.fallback(List.of(chosen), locale), country, owner.maturity())
              .isEmpty())
            rails.add(
                rail(
                    "because-you-watched",
                    related(chosen, "genres"),
                    country,
                    locale,
                    owner.maturity(),
                    limit));
        } catch (WatchFailure e) {
          if (e.status() != 404) throw e;
        }
      }
    }
    boolean applied =
        rails.stream()
            .anyMatch(r -> r.name().equals("because-you-watched") && !r.items().isEmpty());
    return new Recommendations(List.copyOf(rails), personalized, applied);
  }

  private Rail rail(
      String name,
      List<CatalogProjectionSource.Snapshot> candidates,
      String country,
      String locale,
      int maturity,
      int limit) {
    return new Rail(
        name,
        name,
        policy.permitted(views.fallback(candidates, locale), country, maturity).stream()
            .limit(limit)
            .toList());
  }

  private Rail ranking(
      String name, int days, String country, String locale, int maturity, int limit) {
    var result = ranked(days);
    var rail = rail(name, result.items(), country, locale, maturity, limit);
    return new Rail(
        name, result.fallback() ? "recent-fallback" : "opt-in-playback-starts", rail.items());
  }

  private List<CatalogProjectionSource.Snapshot> recent() {
    return db.query(
        "SELECT snapshot FROM search_document ORDER BY"
            + " COALESCE((snapshot->>'releaseDate')::date,((snapshot->>'publishedAt')::timestamptz"
            + " AT TIME ZONE 'UTC')::date) DESC,content_id LIMIT 100",
        (r, n) -> projection.decode(r.getString(1)));
  }

  private Ranked ranked(int days) {
    var results =
        db.query(
            "SELECT s.snapshot FROM search_document s JOIN(SELECT content_id,count(*) AS score FROM"
                + " daily_view WHERE last_at>=? AND last_at<=? GROUP BY content_id) v"
                + " USING(content_id) ORDER BY v.score DESC,s.content_id LIMIT 100",
            (r, n) -> projection.decode(r.getString(1)),
            java.sql.Timestamp.from(clock.instant().minus(days, ChronoUnit.DAYS)),
            java.sql.Timestamp.from(clock.instant().plusSeconds(300)));
    return new Ranked(results.isEmpty() ? recent() : results, results.isEmpty());
  }

  private List<CatalogProjectionSource.Snapshot> related(
      CatalogProjectionSource.Snapshot seed, String field) {
    var values = field.equals("genres") ? seed.genres() : seed.actors();
    if (values.isEmpty()) return List.of();
    var parameters = new ArrayList<Object>();
    parameters.add(seed.contentId());
    var conditions = new ArrayList<String>();
    for (var value : values) {
      conditions.add("snapshot->'" + field + "' @> ?::jsonb");
      try {
        parameters.add(
            new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(List.of(value)));
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalStateException("Cannot encode taxonomy", e);
      }
    }
    return db.query(
        "SELECT snapshot FROM search_document WHERE content_id<>? AND ("
            + String.join(" OR ", conditions)
            + ") ORDER BY content_id LIMIT 100",
        (r, n) -> projection.decode(r.getString(1)),
        parameters.toArray());
  }
}
