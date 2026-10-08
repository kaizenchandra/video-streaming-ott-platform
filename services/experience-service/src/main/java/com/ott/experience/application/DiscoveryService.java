package com.ott.experience.application;

import com.ott.experience.adapter.outbound.search.OpenSearchCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "ott.search.enabled", havingValue = "true")
public class DiscoveryService {
  private final ViewerAccess viewers;
  private final CatalogProjectionSource source;
  private final SearchProjection projection;
  private final OpenSearchCatalog search;
  private final MeterRegistry metrics;

  public DiscoveryService(
      ViewerAccess viewers,
      CatalogProjectionSource source,
      SearchProjection projection,
      OpenSearchCatalog search,
      MeterRegistry metrics) {
    this.viewers = viewers;
    this.source = source;
    this.projection = projection;
    this.search = search;
    this.metrics = metrics;
  }

  public record Page(List<OpenSearchCatalog.Hit> items, Integer nextOffset) {}

  public record Rail(String name, List<OpenSearchCatalog.Hit> items) {}

  public record Home(List<Rail> rails, boolean degraded) {}

  public Page search(
      ViewerAccess.Identity identity,
      UUID profile,
      String country,
      String query,
      String locale,
      String type,
      String genre,
      int offset,
      int limit,
      boolean autocomplete) {
    validate(country, query, locale, type, genre, offset, limit);
    var owner = viewers.profile(identity, profile);
    var candidates =
        search.query(query, locale, type, genre, owner.maturity(), offset, limit, autocomplete);
    var result = permitted(candidates, country, owner.maturity());
    metrics
        .counter("ott.discovery.search.requests", "mode", autocomplete ? "autocomplete" : "search")
        .increment();
    return new Page(
        result, candidates.size() == limit && offset + limit <= 1000 ? offset + limit : null);
  }

  public Home home(
      ViewerAccess.Identity identity, UUID profile, String country, String locale, int limit) {
    validate(country, "", locale, null, null, 0, limit);
    var owner = viewers.profile(identity, profile);
    List<OpenSearchCatalog.Hit> candidates;
    boolean degraded = false;
    try {
      candidates = search.query("", locale, null, null, owner.maturity(), 0, limit, false);
    } catch (WatchFailure e) {
      if (e.status() != 503 && e.status() != 429) throw e;
      degraded = true;
      metrics.counter("ott.discovery.home.fallback").increment();
      candidates = search.fallback(projection.recent(Math.min(100, limit * 3)), locale);
    }
    var allowed = permitted(candidates, country, owner.maturity()).stream().limit(limit).toList();
    return new Home(List.of(new Rail("recent-publications", allowed)), degraded);
  }

  public List<OpenSearchCatalog.Hit> permitted(
      List<OpenSearchCatalog.Hit> candidates, String country, int maturity) {
    var ids =
        candidates.stream()
            .filter(h -> h.maturity() <= maturity)
            .map(OpenSearchCatalog.Hit::contentId)
            .distinct()
            .toList();
    var allowed = new java.util.HashSet<>(source.permitted(ids, country, maturity));
    return candidates.stream()
        .filter(h -> h.maturity() <= maturity && allowed.contains(h.contentId()))
        .toList();
  }

  private static void validate(
      String country,
      String query,
      String locale,
      String type,
      String genre,
      int offset,
      int limit) {
    if (country == null
        || !country.matches("[A-Z]{2}")
        || query == null
        || query.length() > 100
        || query.chars().anyMatch(Character::isISOControl)
        || locale == null
        || !locale.matches("[A-Za-z0-9-]{1,35}")
        || type != null && !List.of("MOVIE", "SERIES", "SEASON", "EPISODE", "SPORT").contains(type)
        || genre != null && !genre.matches("[a-z0-9][a-z0-9-]{0,39}")
        || offset < 0
        || offset > 1000
        || limit < 1
        || limit > 100) throw new WatchFailure(400, "INVALID_SEARCH_INPUT");
  }
}
