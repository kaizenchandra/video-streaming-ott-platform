package com.ott.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.content.application.CatalogService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {ContentApplication.class, ContentIT.TimeConfiguration.class})
class ContentIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

  static final OidcFixture OIDC = new OidcFixture();
  static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
  @Autowired CatalogService service;
  @Autowired JdbcTemplate db;
  @Autowired TestRestTemplate http;
  @Autowired TestClock clock;

  @AfterAll
  static void close() {
    OIDC.close();
  }

  @BeforeEach
  void resetClock() {
    clock.now = NOW;
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
    p.add("ott.security.issuer", OIDC::issuer);
    p.add("ott.security.jwks-uri", OIDC::jwks);
    p.add("ott.media-consumer.enabled", () -> "false");
    p.add("ott.outbox.enabled", () -> "false");
  }

  CatalogService.Item movie() {
    return service.create("MOVIE", null, 13, "en", "Original", "Synopsis");
  }

  CatalogService.Item publish(CatalogService.Item item) {
    var review = service.review(item.id(), item.version(), "en");
    service.mediaReady(
        UUID.randomUUID(), item.id(), 1, "vod/" + item.id() + "/v1/", "a".repeat(64));
    return service.publish(item.id(), review.version(), 1, "en");
  }

  @Test
  void localization_quotaBoundsSnapshots_andFailureDoesNotAdvanceVersion() {
    var item = movie();
    for (int n = 1; n < 32; n++)
      item = service.localize(item.id(), "en-x-" + n, "Title " + n, "", item.version());
    var full = item;
    assertThatThrownBy(
            () -> service.localize(full.id(), "en-x-last", "Over quota", "", full.version()))
        .isInstanceOf(CatalogService.Failure.class)
        .extracting("code")
        .isEqualTo("LOCALIZATION_LIMIT_REACHED");
    assertThat(service.get(full.id(), "en", false).version()).isEqualTo(full.version());
    var published = publish(full);
    assertThat(service.snapshot(published.id(), published.version()).localizations()).hasSize(32);
  }

  @Test
  void taxonomy_exactPublishedSnapshotsRemainImmutableAcrossDraftRevisions() {
    var item = movie();
    var updated =
        service.taxonomy(
            item.id(),
            1,
            java.util.List.of("sci-fi"),
            java.util.List.of("Actor One"),
            java.time.LocalDate.of(2026, 1, 1),
            "en");
    var first = publish(updated);
    var snapshot = service.snapshot(first.id(), first.version());
    assertThat(snapshot.genres()).containsExactly("sci-fi");
    assertThat(snapshot.actors()).containsExactly("Actor One");
    var revised = service.revise(first.id(), first.version(), "en");
    var draft =
        service.taxonomy(
            first.id(),
            revised.version(),
            java.util.List.of("drama"),
            java.util.List.of("Actor Two"),
            null,
            "en");
    var next = publish(draft);
    assertThat(service.snapshot(first.id(), first.version())).isEqualTo(snapshot);
    assertThat(service.snapshot(next.id(), next.version()).genres()).containsExactly("drama");
    assertThatThrownBy(
            () ->
                service.taxonomy(
                    next.id(),
                    next.version(),
                    java.util.List.of("Drama Invalid"),
                    java.util.List.of(),
                    null,
                    "en"))
        .isInstanceOf(CatalogService.Failure.class);
  }

  @Test
  void batchRights_windowsRevocationMaturityAndDrafts_matchCurrentPolicy() {
    var item = publish(movie());
    var draft = movie();
    var grant = service.grant(item.id(), "IN", NOW, NOW.plusSeconds(60), true);
    var ids = java.util.List.of(item.id(), draft.id());
    assertThat(service.permitted(ids, "IN", 18)).containsExactly(item.id());
    assertThat(service.permitted(ids, "IN", 7)).isEmpty();
    assertThat(service.permitted(ids, "US", 18)).isEmpty();
    clock.now = NOW.plusSeconds(60);
    assertThat(service.permitted(ids, "IN", 18)).isEmpty();
    clock.now = NOW;
    service.revokeRights(grant.id());
    assertThat(service.permitted(ids, "IN", 18)).isEmpty();
    assertThatThrownBy(
            () -> service.permitted(java.util.Collections.nCopies(101, item.id()), "IN", 18))
        .isInstanceOf(CatalogService.Failure.class);
  }

  @Test
  void snapshotApis_viewerDeniedAndWorkloadAllowed() {
    var item = publish(movie());
    var headers = new HttpHeaders();
    headers.setBearerAuth(OIDC.token("viewer", "", false));
    String uri = "/internal/v1/catalog/" + item.id() + "/versions/" + item.version();
    assertThat(
            http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    headers.setBearerAuth(OIDC.token("indexer", "content.internal", false));
    assertThat(
            http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
  }

  @Test
  void hierarchy_invalidParent_rejectsAndValidSeriesSeasonEpisodePersists() {
    var series = service.create("SERIES", null, 7, "en", "Series", "");
    var season = service.create("SEASON", series.id(), 7, "en", "Season", "");
    assertThat(service.create("EPISODE", season.id(), 7, "en", "Episode", "").parentId())
        .isEqualTo(season.id());
    assertThatThrownBy(() -> service.create("EPISODE", series.id(), 7, "en", "Invalid", ""))
        .isInstanceOf(CatalogService.Failure.class)
        .extracting("code")
        .isEqualTo("INVALID_HIERARCHY");
    assertThatThrownBy(() -> service.create("MOVIE", series.id(), 7, "en", "Invalid", ""))
        .isInstanceOf(CatalogService.Failure.class);
  }

  @Test
  void localization_versionedUpdateAndFallback_areDeterministic() {
    var item = movie();
    var fr = service.localize(item.id(), "fr", "Titre", "Résumé", item.version());
    assertThat(fr.version()).isEqualTo(2);
    assertThat(fr.title()).isEqualTo("Titre");
    assertThat(service.get(item.id(), "ja", false).locale()).isEqualTo("en");
    assertThatThrownBy(() -> service.localize(item.id(), "de", "Alt", "", 1))
        .isInstanceOf(CatalogService.Failure.class)
        .extracting("code")
        .isEqualTo("VERSION_OR_STATE_CONFLICT");
  }

  @Test
  void publish_unvalidatedAsset_neverExposesDraftOrCreatesEvent() {
    var item = movie();
    var review = service.review(item.id(), 1, "en");
    assertThatThrownBy(() -> service.get(item.id(), "en", true))
        .isInstanceOf(CatalogService.Failure.class);
    assertThatThrownBy(() -> service.publish(item.id(), review.version(), 1, "en"))
        .isInstanceOf(CatalogService.Failure.class)
        .extracting("code")
        .isEqualTo("MEDIA_NOT_READY");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM published_version WHERE content_id=?",
                Integer.class,
                item.id()))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=?", Integer.class, item.id()))
        .isZero();
  }

  @Test
  void mediaReady_duplicateEvent_isIdempotentAndInvalidPrefixRejected() {
    var item = movie();
    var event = UUID.randomUUID();
    assertThat(service.mediaReady(event, item.id(), 1, "vod/" + item.id() + "/v1/", "a".repeat(64)))
        .isTrue();
    assertThat(service.mediaReady(event, item.id(), 1, "vod/" + item.id() + "/v1/", "a".repeat(64)))
        .isFalse();
    assertThatThrownBy(
            () ->
                service.mediaReady(UUID.randomUUID(), item.id(), 2, "../private/", "a".repeat(64)))
        .isInstanceOf(CatalogService.Failure.class);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM ready_asset WHERE content_id=?", Integer.class, item.id()))
        .isEqualTo(1);
  }

  @Test
  void version_newDraft_preservesPublishedSnapshotUntilNextPublication() {
    var current = publish(movie());
    var draft = service.revise(current.id(), current.version(), "en");
    var changed = service.localize(draft.id(), "en", "Changed", "New", draft.version());
    assertThat(service.get(current.id(), "en", true).title()).isEqualTo("Original");
    assertThatThrownBy(() -> service.localize(draft.id(), "fr", "Stale", "", 1))
        .isInstanceOf(CatalogService.Failure.class);
    var review = service.review(changed.id(), changed.version(), "en");
    var next = service.publish(review.id(), review.version(), 1, "en");
    assertThat(next.title()).isEqualTo("Changed");
    assertThat(
            db.queryForObject(
                "SELECT metadata->'localizations'->'en'->>'title' FROM published_version WHERE"
                    + " content_id=? AND version=?",
                String.class,
                current.id(),
                current.version()))
        .isEqualTo("Original");
    assertThat(service.get(current.id(), "en", true).version()).isEqualTo(next.version());
  }

  @Test
  void rights_startInclusiveEndExclusive_enforcesGeoMaturityAndOffline() {
    var item = publish(movie());
    var rights = service.grant(item.id(), "IN", NOW, NOW.plusSeconds(60), false);
    var allowed = service.rights(item.id(), "IN", 18, false);
    assertThat(allowed.allowed()).isTrue();
    assertThat(allowed.expiresAt()).isEqualTo(rights.endAt());
    assertThat(service.rights(item.id(), "US", 18, false).allowed()).isFalse();
    assertThat(service.rights(item.id(), "IN", 7, false).code())
        .isEqualTo("PARENTAL_CONTROL_RESTRICTED");
    assertThat(service.rights(item.id(), "IN", 18, true).allowed()).isFalse();
    clock.now = NOW.minusNanos(1);
    assertThat(service.rights(item.id(), "IN", 18, false).allowed()).isFalse();
    clock.now = NOW.plusSeconds(60);
    assertThat(service.rights(item.id(), "IN", 18, false).allowed()).isFalse();
  }

  @Test
  void rights_revocation_isIdempotentAndDoesNotMutatePublishedVersion() {
    var item = publish(movie());
    var grant = service.grant(item.id(), "IN", NOW, NOW.plusSeconds(60), true);
    assertThat(service.get(item.id(), "en", true).version()).isEqualTo(item.version());
    service.revokeRights(grant.id());
    service.revokeRights(grant.id());
    assertThat(service.rights(item.id(), "IN", 18, true).allowed()).isFalse();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND"
                    + " topic='rights.changed.v1'",
                Integer.class,
                item.id()))
        .isEqualTo(2);
  }

  @Test
  void publish_parallelSameVersion_commitsOneSnapshotAndOutbox() throws Exception {
    var item = movie();
    var review = service.review(item.id(), 1, "en");
    service.mediaReady(
        UUID.randomUUID(), item.id(), 1, "vod/" + item.id() + "/v1/", "a".repeat(64));
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 8; i++)
        tasks.add(
            () -> {
              try {
                service.publish(item.id(), review.version(), 1, "en");
                return true;
              } catch (CatalogService.Failure e) {
                assertThat(e.status()).isEqualTo(409);
                return false;
              }
            });
      int accepted = 0;
      for (var f : pool.invokeAll(tasks, 10, TimeUnit.SECONDS)) if (f.get()) accepted++;
      assertThat(accepted).isEqualTo(1);
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND"
                    + " topic='catalog.published.v1'",
                Integer.class,
                item.id()))
        .isEqualTo(1);
  }

  @Test
  void browse_publishedOnlyAndBoundedCursor_doesNotDuplicateResults() {
    publish(movie());
    publish(movie());
    movie();
    var first = service.browse("en", "MOVIE", null, 1);
    assertThat(first.items()).hasSize(1);
    assertThat(first.nextCursor()).isNotNull();
    var next = service.browse("en", "MOVIE", first.nextCursor(), 1);
    assertThat(next.items()).hasSize(1);
    assertThat(next.items().getFirst().id()).isNotEqualTo(first.items().getFirst().id());
    assertThatThrownBy(() -> service.browse("en", null, null, 101))
        .isInstanceOf(CatalogService.Failure.class);
  }

  @Test
  void api_writerViewerPublisherAndWorkloadScopes_enforceSegregation() {
    var input =
        Map.of("type", "MOVIE", "maturity", 13, "locale", "en", "title", "API", "synopsis", "");
    assertThat(call("POST", "/v1/admin/catalog", input, "").getStatusCode().value()).isEqualTo(403);
    assertThat(call("POST", "/v1/admin/catalog", input, "catalog.write").getStatusCode().value())
        .isEqualTo(201);
    var item = movie();
    assertThat(call("GET", "/v1/catalog/" + item.id(), null, "").getStatusCode().value())
        .isEqualTo(404);
    assertThat(
            call(
                    "POST",
                    "/v1/admin/catalog/" + item.id() + "/publish",
                    Map.of("version", 1, "assetVersion", 1, "locale", "en"),
                    "catalog.write")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    var query = Map.of("contentId", item.id(), "country", "IN", "maturity", 18, "offline", false);
    assertThat(call("POST", "/internal/v1/rights/check", query, "").getStatusCode().value())
        .isEqualTo(403);
    assertThat(call("POST", "/internal/v1/rights/check", query, "content.internal").getBody())
        .contains("\"allowed\":false");
  }

  org.springframework.http.ResponseEntity<String> call(
      String method, String path, Object body, String scope) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(OIDC.token("operator", scope, true));
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    return http.exchange(
        path, HttpMethod.valueOf(method), new HttpEntity<>(body, headers), String.class);
  }

  static class TestClock extends Clock {
    volatile Instant now = NOW;

    public Instant instant() {
      return now;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TimeConfiguration {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }
}
