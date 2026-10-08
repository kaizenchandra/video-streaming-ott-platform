package com.ott.experience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.experience.adapter.outbound.search.OpenSearchCatalog;
import com.ott.experience.application.CatalogProjectionSource;
import com.ott.experience.application.DiscoveryService;
import com.ott.experience.application.SearchIndexer;
import com.ott.experience.application.SearchProjection;
import com.ott.experience.application.ViewerAccess;
import com.ott.experience.application.WatchFailure;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(classes = {ExperienceApplication.class, WatchIT.Ports.class, SearchIT.Ports.class})
class SearchIT {
  @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final GenericContainer<?> SEARCH =
      new GenericContainer<>("opensearchproject/opensearch:2.19.3")
          .withExposedPorts(9200)
          .withEnv("discovery.type", "single-node")
          .withEnv("DISABLE_SECURITY_PLUGIN", "true")
          .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
          .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
          .waitingFor(
              Wait.forHttp("/_cluster/health?wait_for_status=yellow&timeout=30s")
                  .forPort(9200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  static final OidcFixture OIDC = new OidcFixture();
  @Autowired SearchProjection projection;
  @Autowired SearchIndexer indexer;
  @Autowired OpenSearchCatalog search;
  @Autowired DiscoveryService discovery;
  @Autowired Source source;
  @Autowired WatchIT.TestAccess viewers;
  @Autowired JdbcTemplate db;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  ViewerAccess.Identity identity;
  UUID profile;

  @AfterAll
  static void close() {
    OIDC.close();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", DB::getJdbcUrl);
    p.add("spring.datasource.username", DB::getUsername);
    p.add("spring.datasource.password", DB::getPassword);
    p.add("ott.security.issuer", OIDC::issuer);
    p.add("ott.security.jwks-uri", OIDC::jwks);
    p.add("ott.outbox.enabled", () -> "false");
    p.add("ott.search.enabled", () -> "true");
    p.add("ott.search.url", () -> "http://" + SEARCH.getHost() + ":" + SEARCH.getMappedPort(9200));
    p.add("ott.search.index", () -> "ott-test-catalog");
    p.add("ott.search.replicas", () -> "0");
    p.add("ott.search.index-delay-ms", () -> "3600000");
    p.add("ott.search.rebuild-delay-ms", () -> "3600000");
    p.add("spring.kafka.listener.auto-startup", () -> "false");
  }

  @BeforeEach
  void reset() {
    identity = new ViewerAccess.Identity(UUID.randomUUID().toString());
    profile = UUID.randomUUID();
    viewers.profiles.put(
        profile,
        new WatchIT.Owned(
            identity.viewerToken(), new ViewerAccess.Profile(UUID.randomUUID(), profile, 18)));
    source.unavailable = false;
  }

  CatalogProjectionSource.Snapshot snapshot(
      UUID id, long version, String title, int maturity, String genre) {
    return new CatalogProjectionSource.Snapshot(
        id,
        version,
        "MOVIE",
        maturity,
        Map.of(
            "en",
            new CatalogProjectionSource.Localized(title, "A voyage through galaxies"),
            "hi",
            new CatalogProjectionSource.Localized(
                "अंतरिक्ष यात्रा", "Synthetic localized synopsis")),
        List.of(genre),
        List.of("Actor One"),
        LocalDate.of(2026, 1, 1),
        Instant.now());
  }

  void add(CatalogProjectionSource.Snapshot s) {
    source.rows.put(s.contentId() + ":" + s.version(), s);
    source.latest.put(s.contentId(), s);
    projection.accept(UUID.randomUUID(), s);
  }

  RestClient raw() {
    return RestClient.builder()
        .baseUrl("http://" + SEARCH.getHost() + ":" + SEARCH.getMappedPort(9200))
        .requestFactory(new SimpleClientHttpRequestFactory())
        .build();
  }

  void index() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    do {
      indexer.index();
      if (db.queryForObject(
              "SELECT count(*) FROM search_document WHERE indexed_version<version", Integer.class)
          == 0) break;
      Thread.sleep(50);
    } while (System.nanoTime() < deadline);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM search_document WHERE indexed_version<version",
                Integer.class))
        .isZero();
    raw().post().uri("/ott-test-catalog/_refresh").retrieve().toBodilessEntity();
  }

  @Test
  void projection_duplicateOutOfOrderAndChangedSnapshot_keepsNewestAndRejectsConflicts() {
    var id = UUID.randomUUID();
    var old = snapshot(id, 3, "Old constellation", 7, "sci-fi");
    var latest = snapshot(id, 6, "New constellation", 7, "sci-fi");
    var event = UUID.randomUUID();
    assertThat(projection.accept(event, latest)).isTrue();
    assertThat(projection.accept(event, latest)).isFalse();
    assertThat(projection.accept(UUID.randomUUID(), old)).isFalse();
    assertThat(projection.get(id).version()).isEqualTo(6);
    assertThatThrownBy(() -> projection.accept(event, old))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                projection.accept(
                    UUID.randomUUID(), snapshot(id, 6, "Different immutable data", 7, "sci-fi")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void search_realFuzzyPrefixLocaleAndFilters_returnEligibleRankedResults() throws Exception {
    var s = snapshot(UUID.randomUUID(), 3, "Interstellar Voyager", 7, "sci-fi");
    add(s);
    add(snapshot(UUID.randomUUID(), 3, "Interstellar Horror", 18, "horror"));
    index();
    var fuzzy =
        discovery.search(
            identity, profile, "IN", "Interstelar Voyager", "en", "MOVIE", "sci-fi", 0, 10, false);
    assertThat(fuzzy.items()).extracting(OpenSearchCatalog.Hit::contentId).contains(s.contentId());
    assertThat(
            discovery
                .search(identity, profile, "IN", "Interst", "en", null, "sci-fi", 0, 10, true)
                .items())
        .extracting(OpenSearchCatalog.Hit::contentId)
        .contains(s.contentId());
    assertThat(
            discovery
                .search(identity, profile, "IN", "अंतरिक्ष", "hi", null, "sci-fi", 0, 10, false)
                .items())
        .extracting(OpenSearchCatalog.Hit::locale)
        .containsOnly("hi");
  }

  @Test
  void rightsRevokedOrKidsMaturity_rechecksOwnerWithoutReindex() throws Exception {
    var child = snapshot(UUID.randomUUID(), 3, "CartoonRightsFixture", 7, "family");
    var adult = snapshot(UUID.randomUUID(), 3, "CartoonRightsFixture Adult", 18, "family");
    add(child);
    add(adult);
    index();
    var owner = viewers.profiles.get(profile);
    viewers.profiles.put(
        profile,
        new WatchIT.Owned(
            identity.viewerToken(),
            new ViewerAccess.Profile(owner.profile().accountId(), profile, 7)));
    var initial =
        discovery.search(
            identity, profile, "IN", "CartoonRightsFixture", "en", null, null, 0, 20, false);
    assertThat(initial.items())
        .extracting(OpenSearchCatalog.Hit::contentId)
        .contains(child.contentId())
        .doesNotContain(adult.contentId());
    source.denied.add(child.contentId());
    assertThat(
            discovery
                .search(
                    identity, profile, "IN", "CartoonRightsFixture", "en", null, null, 0, 20, false)
                .items())
        .isEmpty();
  }

  @Test
  void ownerUnavailableOrForeignProfile_deniesRatherThanReturningIndexHits() throws Exception {
    add(snapshot(UUID.randomUUID(), 3, "Policy outage fixture", 7, "drama"));
    index();
    source.unavailable = true;
    assertThatThrownBy(
            () ->
                discovery.search(
                    identity,
                    profile,
                    "IN",
                    "Policy outage fixture",
                    "en",
                    null,
                    null,
                    0,
                    20,
                    false))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("CATALOG_UNAVAILABLE");
    assertThatThrownBy(
            () -> discovery.home(new ViewerAccess.Identity("foreign"), profile, "IN", "en", 20))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("PROFILE_NOT_FOUND");
  }

  @Test
  void indexLost_homeFallsBackAndDurableRebuildRestoresSearch() throws Exception {
    var s = snapshot(UUID.randomUUID(), 8, "RecoverableIndexFixture", 7, "drama");
    add(s);
    index();
    raw().delete().uri("/ott-test-catalog").retrieve().toBodilessEntity();
    var home = discovery.home(identity, profile, "IN", "en", 100);
    assertThat(home.degraded()).isTrue();
    assertThat(home.rails().getFirst().items())
        .extracting(OpenSearchCatalog.Hit::contentId)
        .contains(s.contentId());
    UUID job = projection.rebuild();
    assertThat(projection.rebuild()).isEqualTo(job);
    indexer.rebuild();
    index();
    assertThat(projection.rebuildStatus(job).state()).isEqualTo("COMPLETE");
    assertThat(db.queryForObject("SELECT state FROM search_rebuild WHERE id=?", String.class, job))
        .isEqualTo("COMPLETE");
    assertThat(
            discovery
                .search(
                    identity,
                    profile,
                    "IN",
                    "RecoverableIndexFixture",
                    "en",
                    null,
                    null,
                    0,
                    20,
                    false)
                .items())
        .extracting(OpenSearchCatalog.Hit::contentId)
        .contains(s.contentId());
  }

  @Test
  void indexAcknowledgementLossOrStaleWriter_externalVersionPreventsRollback() throws Exception {
    var id = UUID.randomUUID();
    var old = snapshot(id, 2, "ExternalVersionOld", 7, "drama");
    var latest = snapshot(id, 5, "ExternalVersionNew", 7, "drama");
    search.index(latest);
    search.index(latest);
    search.index(old);
    raw().post().uri("/ott-test-catalog/_refresh").retrieve().toBodilessEntity();
    var found = search.query("ExternalVersionNew", "en", null, null, 18, 0, 20, false);
    assertThat(found)
        .filteredOn(h -> h.contentId().equals(id))
        .extracting(OpenSearchCatalog.Hit::version)
        .containsOnly(5L);
  }

  @Test
  void rebuildApis_requireOperatorScope_andReportPersistedState() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/v1/admin/search/rebuild")
                .header("Authorization", "Bearer " + OIDC.token("viewer", "", false)))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    String operator = OIDC.token("search-operator", "search.write", false);
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/v1/admin/search/rebuild")
                    .header("Authorization", "Bearer " + operator))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .isAccepted())
            .andReturn();
    var body =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response.getResponse().getContentAsString());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/v1/admin/search/rebuild/" + body.path("jobId").asText())
                .header("Authorization", "Bearer " + operator))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    // Leave no active test intent to interfere with the independent index-loss drill.
    db.update(
        "UPDATE search_rebuild SET state='COMPLETE' WHERE id=?",
        UUID.fromString(body.path("jobId").asText()));
  }

  @Test
  void input_overlongDslAndHugePagination_areBounded() {
    assertThatThrownBy(
            () ->
                discovery.search(
                    identity, profile, "IN", "x".repeat(101), "en", null, null, 0, 20, false))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("INVALID_SEARCH_INPUT");
    assertThatThrownBy(
            () -> discovery.search(identity, profile, "IN", "", "en", null, null, 1001, 20, false))
        .isInstanceOf(WatchFailure.class);
  }

  static class Source implements CatalogProjectionSource {
    final Map<String, Snapshot> rows = new ConcurrentHashMap<>();
    final Map<UUID, Snapshot> latest = new ConcurrentHashMap<>();
    final Set<UUID> denied = ConcurrentHashMap.newKeySet();
    volatile boolean unavailable;

    public Snapshot snapshot(UUID id, long version) {
      var result = rows.get(id + ":" + version);
      if (result == null || unavailable) throw new WatchFailure(503, "CATALOG_UNAVAILABLE");
      return result;
    }

    public Page snapshots(UUID after, int limit) {
      if (unavailable) throw new WatchFailure(503, "CATALOG_UNAVAILABLE");
      var rows =
          latest.values().stream()
              .filter(s -> after == null || s.contentId().compareTo(after) > 0)
              .sorted(java.util.Comparator.comparing(Snapshot::contentId))
              .limit(limit + 1L)
              .toList();
      var items = rows.subList(0, Math.min(limit, rows.size()));
      return new Page(items, rows.size() > limit ? items.getLast().contentId() : null);
    }

    public List<UUID> permitted(List<UUID> ids, String country, int maturity) {
      if (unavailable) throw new WatchFailure(503, "CATALOG_UNAVAILABLE");
      return ids.stream().filter(id -> !denied.contains(id) && country.equals("IN")).toList();
    }
  }

  @TestConfiguration
  static class Ports {
    @Bean
    @Primary
    Source source() {
      return new Source();
    }
  }
}
