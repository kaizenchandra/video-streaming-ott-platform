package com.ott.experience.adapter.outbound.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.ott.experience.application.CatalogProjectionSource;
import com.ott.experience.application.WatchFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(name = "ott.search.enabled", havingValue = "true")
public class OpenSearchCatalog {
  private final RestClient http;
  private final String index;
  private final int shards, replicas;
  private final Semaphore reads = new Semaphore(32);
  private final AtomicInteger failures = new AtomicInteger();
  private volatile long openUntil;

  public OpenSearchCatalog(
      io.micrometer.observation.ObservationRegistry observations,
      @Value("${ott.search.url}") String url,
      @Value("${ott.search.index}") String index,
      @Value("${ott.search.username:}") String user,
      @Value("${ott.search.password:}") String password,
      @Value("${ott.search.shards:1}") int shards,
      @Value("${ott.search.replicas:1}") int replicas,
      Environment env) {
    if (!index.matches("[a-z0-9][a-z0-9_-]{0,63}"))
      throw new IllegalArgumentException("Invalid search index");
    if (env.matchesProfiles("prod")
        && (!url.startsWith("https://") || user.isBlank() || password.isBlank()))
      throw new IllegalArgumentException("Production search requires TLS and credentials");
    if (shards < 1
        || shards > 100
        || replicas < 0
        || replicas > 5
        || env.matchesProfiles("prod") && replicas < 1)
      throw new IllegalArgumentException("Invalid search replication");
    this.shards = shards;
    this.replicas = replicas;
    this.index = index;
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(100);
    f.setReadTimeout(500);
    var builder =
        RestClient.builder().observationRegistry(observations).baseUrl(url).requestFactory(f);
    if (!user.isBlank()) builder.defaultHeaders(h -> h.setBasicAuth(user, password));
    http = builder.build();
  }

  public void ensure() {
    try {
      http.head().uri("/" + index).retrieve().toBodilessEntity();
      return;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() != 404) throw e;
    }
    var props = new java.util.LinkedHashMap<String, Object>();
    for (String field : List.of("contentId", "locale", "contentType", "genres", "actors"))
      props.put(field, Map.of("type", "keyword"));
    props.put("title", Map.of("type", "search_as_you_type"));
    props.put("synopsis", Map.of("type", "text"));
    props.put("maturity", Map.of("type", "integer"));
    props.put("version", Map.of("type", "long"));
    props.put("releaseDate", Map.of("type", "date"));
    props.put("publishedAt", Map.of("type", "date"));
    try {
      http.put()
          .uri("/" + index)
          .body(
              Map.of(
                  "settings",
                  Map.of("number_of_shards", shards, "number_of_replicas", replicas),
                  "mappings",
                  Map.of("dynamic", "strict", "properties", props)))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() != 400
          || !e.getResponseBodyAsString().contains("resource_already_exists_exception")) throw e;
    }
  }

  public void index(CatalogProjectionSource.Snapshot snapshot) {
    ensure();
    for (var entry : snapshot.localizations().entrySet()) {
      var body = new java.util.LinkedHashMap<String, Object>();
      body.put("contentId", snapshot.contentId());
      body.put("version", snapshot.version());
      body.put("locale", entry.getKey());
      body.put("contentType", snapshot.contentType());
      body.put("maturity", snapshot.maturity());
      body.put("title", entry.getValue().title());
      body.put("synopsis", entry.getValue().synopsis());
      body.put("genres", snapshot.genres());
      body.put("actors", snapshot.actors());
      body.put(
          "releaseDate", snapshot.releaseDate() == null ? null : snapshot.releaseDate().toString());
      body.put("publishedAt", snapshot.publishedAt().toString());
      try {
        http.put()
            .uri(
                "/{index}/_doc/{id}?version={version}&version_type=external",
                index,
                snapshot.contentId() + "_" + entry.getKey(),
                snapshot.version())
            .body(body)
            .retrieve()
            .toBodilessEntity();
      } catch (RestClientResponseException e) {
        if (e.getStatusCode().value() != 409) throw e;
      }
    }
  }

  public record Hit(
      UUID contentId,
      long version,
      String locale,
      String title,
      String synopsis,
      String contentType,
      int maturity,
      List<String> genres,
      List<String> actors,
      java.time.LocalDate releaseDate,
      java.time.Instant publishedAt) {}

  public List<Hit> query(
      String query,
      String locale,
      String type,
      String genre,
      int maturity,
      int offset,
      int size,
      boolean autocomplete) {
    if (System.nanoTime() < openUntil) throw unavailable();
    if (!reads.tryAcquire()) throw new WatchFailure(429, "SEARCH_OVERLOADED");
    try {
      var filters = new ArrayList<Object>();
      filters.add(Map.of("term", Map.of("locale", locale)));
      filters.add(Map.of("range", Map.of("maturity", Map.of("lte", maturity))));
      if (type != null) filters.add(Map.of("term", Map.of("contentType", type)));
      if (genre != null) filters.add(Map.of("term", Map.of("genres", genre)));
      Object match =
          query.isBlank()
              ? Map.of("match_all", Map.of())
              : autocomplete
                  ? Map.of(
                      "multi_match",
                      Map.of(
                          "query",
                          query,
                          "type",
                          "bool_prefix",
                          "fields",
                          List.of("title", "title._2gram", "title._3gram")))
                  : Map.of(
                      "multi_match",
                      Map.of(
                          "query",
                          query,
                          "fields",
                          List.of("title^4", "synopsis", "actors"),
                          "fuzziness",
                          "AUTO",
                          "max_expansions",
                          25,
                          "prefix_length",
                          1));
      Object sort =
          query.isBlank()
              ? List.of(Map.of("publishedAt", "desc"), Map.of("contentId", "asc"))
              : List.of(Map.of("_score", "desc"), Map.of("contentId", "asc"));
      var result =
          http.post()
              .uri("/" + index + "/_search")
              .body(
                  Map.of(
                      "from",
                      offset,
                      "size",
                      size,
                      "timeout",
                      "400ms",
                      "track_total_hits",
                      false,
                      "query",
                      Map.of("bool", Map.of("filter", filters, "must", match)),
                      "sort",
                      sort))
              .retrieve()
              .body(JsonNode.class);
      if (result == null
          || result.path("timed_out").asBoolean()
          || result.path("_shards").path("failed").asInt() > 0) throw unavailable();
      var hits = new ArrayList<Hit>();
      for (var item : result.path("hits").path("hits")) {
        var source = item.path("_source");
        var genres = new ArrayList<String>();
        source.path("genres").forEach(v -> genres.add(v.asText()));
        var actors = new ArrayList<String>();
        source.path("actors").forEach(v -> actors.add(v.asText()));
        var release = source.path("releaseDate");
        hits.add(
            new Hit(
                UUID.fromString(source.path("contentId").asText()),
                source.path("version").asLong(),
                source.path("locale").asText(),
                source.path("title").asText(),
                source.path("synopsis").asText(),
                source.path("contentType").asText(),
                source.path("maturity").asInt(),
                List.copyOf(genres),
                List.copyOf(actors),
                release.isNull() || release.isMissingNode()
                    ? null
                    : java.time.LocalDate.parse(release.asText()),
                java.time.Instant.parse(source.path("publishedAt").asText())));
      }
      failures.set(0);
      return List.copyOf(hits);
    } catch (RestClientException
        | IllegalArgumentException
        | java.time.DateTimeException
        | WatchFailure e) {
      if (failures.incrementAndGet() >= 5) {
        openUntil = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        failures.set(0);
      }
      throw unavailable();
    } finally {
      reads.release();
    }
  }

  public List<Hit> fallback(List<CatalogProjectionSource.Snapshot> snapshots, String locale) {
    return snapshots.stream()
        .map(
            s -> {
              String actual =
                  s.localizations().containsKey(locale)
                      ? locale
                      : s.localizations().containsKey("en")
                          ? "en"
                          : s.localizations().keySet().stream().sorted().findFirst().orElseThrow();
              var localized = s.localizations().get(actual);
              return new Hit(
                  s.contentId(),
                  s.version(),
                  actual,
                  localized.title(),
                  localized.synopsis(),
                  s.contentType(),
                  s.maturity(),
                  s.genres(),
                  s.actors(),
                  s.releaseDate(),
                  s.publishedAt());
            })
        .toList();
  }

  private static WatchFailure unavailable() {
    return new WatchFailure(503, "SEARCH_UNAVAILABLE");
  }
}
