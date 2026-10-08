package com.ott.experience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.experience.application.CatalogProjectionSource;
import com.ott.experience.application.RecommendationService;
import com.ott.experience.application.RecommendationSignals;
import com.ott.experience.application.SearchProjection;
import com.ott.experience.application.ViewerAccess;
import com.ott.experience.application.WatchFailure;
import com.ott.experience.application.WatchService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
    classes = {
      ExperienceApplication.class,
      WatchIT.Ports.class,
      SearchIT.Ports.class,
      RecommendationIT.Time.class
    })
class RecommendationIT {
  @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6");
  static final OidcFixture OIDC = new OidcFixture();
  static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
  @Autowired RecommendationSignals signals;
  @Autowired RecommendationService recommendations;
  @Autowired WatchIT.TestAccess viewers;
  @Autowired SearchIT.Source source;
  @Autowired SearchProjection projection;
  @Autowired WatchService watch;
  @Autowired JdbcTemplate db;
  @Autowired MutableClock clock;
  ViewerAccess.Identity identity;
  UUID profile, account, device;

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
    p.add("ott.search.url", () -> "http://localhost:1");
    p.add("ott.search.index", () -> "ott-recommendation-test");
    p.add("ott.search.replicas", () -> "0");
    p.add("ott.recommendations.enabled", () -> "true");
    p.add("ott.recommendations.daily-profile-cap", () -> "2");
    p.add("spring.kafka.listener.auto-startup", () -> "false");
    p.add("ott.search.index-delay-ms", () -> "3600000");
    p.add("ott.search.rebuild-delay-ms", () -> "3600000");
  }

  @BeforeEach
  void reset() {
    clock.at = NOW;
    source.unavailable = false;
    identity = new ViewerAccess.Identity(UUID.randomUUID().toString());
    profile = UUID.randomUUID();
    account = UUID.randomUUID();
    device = UUID.randomUUID();
    viewers.profiles.put(
        profile,
        new WatchIT.Owned(identity.viewerToken(), new ViewerAccess.Profile(account, profile, 18)));
  }

  CatalogProjectionSource.Snapshot add(String title, List<String> genres, List<String> actors) {
    var id = UUID.randomUUID();
    var s =
        new CatalogProjectionSource.Snapshot(
            id,
            3,
            "MOVIE",
            7,
            Map.of("en", new CatalogProjectionSource.Localized(title, "")),
            genres,
            actors,
            java.time.LocalDate.of(2026, 1, 1),
            NOW);
    projection.accept(UUID.randomUUID(), s);
    source.latest.put(id, s);
    return s;
  }

  boolean start(UUID event, UUID content, Instant at) {
    return signals.start(event, account, profile, UUID.randomUUID(), content, device, at);
  }

  @Test
  void consent_defaultOffEnableAndOptOut_preserveOwnershipAndEraseDerivedRows() {
    var content = UUID.randomUUID();
    assertThat(signals.preference(identity, profile).enabled()).isFalse();
    assertThat(start(UUID.randomUUID(), content, NOW)).isFalse();
    var opted = signals.preference(identity, profile, true);
    assertThat(signals.preference(identity, profile, true)).isEqualTo(opted);
    assertThat(start(UUID.randomUUID(), content, NOW)).isTrue();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM daily_view WHERE profile_id=?", Integer.class, profile))
        .isEqualTo(1);
    assertThat(signals.preference(identity, profile, false).enabled()).isFalse();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM daily_view WHERE profile_id=?", Integer.class, profile))
        .isZero();
    assertThatThrownBy(
            () -> signals.preference(new ViewerAccess.Identity("foreign"), profile, true))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("PROFILE_NOT_FOUND");
  }

  @Test
  void signal_duplicateCollisionAndDailyCap_areReplaySafeAndBounded() {
    signals.preference(identity, profile, true);
    var event = UUID.randomUUID();
    var session = UUID.randomUUID();
    var content = UUID.randomUUID();
    assertThat(signals.start(event, account, profile, session, content, device, NOW)).isTrue();
    assertThat(signals.start(event, account, profile, session, content, device, NOW)).isFalse();
    assertThatThrownBy(
            () -> signals.start(event, account, profile, session, UUID.randomUUID(), device, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(start(UUID.randomUUID(), content, NOW.plusSeconds(1))).isTrue();
    assertThat(start(UUID.randomUUID(), UUID.randomUUID(), NOW)).isTrue();
    assertThat(start(UUID.randomUUID(), UUID.randomUUID(), NOW)).isFalse();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM daily_view WHERE profile_id=?", Integer.class, profile))
        .isEqualTo(2);
  }

  @Test
  void signal_parallelContentStarts_enforcesProfileDailyCap() throws Exception {
    signals.preference(identity, profile, true);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
      for (int n = 0; n < 8; n++) tasks.add(() -> start(UUID.randomUUID(), UUID.randomUUID(), NOW));
      int accepted = 0;
      for (var result : pool.invokeAll(tasks, 10, java.util.concurrent.TimeUnit.SECONDS))
        if (result.get()) accepted++;
      assertThat(accepted).isEqualTo(2);
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM daily_view WHERE profile_id=?", Integer.class, profile))
        .isEqualTo(2);
  }

  @Test
  void signal_beforeConsentFutureAndExpired_ignoredOrRejectedAndRetentionDeletesOldRows() {
    signals.preference(identity, profile, true);
    assertThat(start(UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(1))).isFalse();
    assertThatThrownBy(() -> start(UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(301)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(start(UUID.randomUUID(), UUID.randomUUID(), NOW)).isTrue();
    clock.at = NOW.plus(java.time.Duration.ofDays(91));
    signals.retain();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM daily_view WHERE profile_id=?", Integer.class, profile))
        .isZero();
    assertThat(start(UUID.randomUUID(), UUID.randomUUID(), NOW)).isFalse();
  }

  @Test
  void signal_wrongAccountOrDatabaseFailure_neverCommitsCounter() {
    signals.preference(identity, profile, true);
    var content = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                signals.start(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    profile,
                    UUID.randomUUID(),
                    content,
                    device,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
    db.execute(
        "ALTER TABLE daily_view ADD CONSTRAINT fixture_signal_failure CHECK(content_id!='"
            + content
            + "'::uuid) NOT VALID");
    var event = UUID.randomUUID();
    try {
      assertThatThrownBy(() -> start(event, content, NOW))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM recommendation_inbox WHERE event_id=?",
                  Integer.class,
                  event))
          .isZero();
    } finally {
      db.execute("ALTER TABLE daily_view DROP CONSTRAINT fixture_signal_failure");
    }
    assertThat(start(event, content, NOW)).isTrue();
  }

  @Test
  void recommendation_stableContextualAndBecauseWatchedRails_useTaxonomyAndCurrentPolicy() {
    var seed = add("Seed film", List.of("sci-fi"), List.of("Actor One"));
    var related = add("Related film", List.of("sci-fi"), List.of("Actor Two"));
    var actor = add("Actor film", List.of("drama"), List.of("Actor One"));
    signals.preference(identity, profile, true);
    String token = UUID.randomUUID().toString();
    viewers.grants.put(
        token,
        new ViewerAccess.Grant(UUID.randomUUID(), account, profile, seed.contentId(), device, 100));
    watch.observe(
        identity,
        new WatchService.Observation(
            UUID.randomUUID(), profile, seed.contentId(), device, token, false, 1, 1000, 10000));
    var result = recommendations.recommend(identity, profile, "IN", "en", seed.contentId(), 10);
    assertThat(result.personalized()).isTrue();
    assertThat(
            result.rails().stream()
                .filter(r -> r.name().equals("same-genre"))
                .findFirst()
                .orElseThrow()
                .items())
        .extracting(com.ott.experience.adapter.outbound.search.OpenSearchCatalog.Hit::contentId)
        .contains(related.contentId())
        .doesNotContain(seed.contentId());
    assertThat(
            result.rails().stream()
                .filter(r -> r.name().equals("same-actors"))
                .findFirst()
                .orElseThrow()
                .items())
        .extracting(com.ott.experience.adapter.outbound.search.OpenSearchCatalog.Hit::contentId)
        .contains(actor.contentId());
    assertThat(result.rails())
        .extracting(RecommendationService.Rail::name)
        .contains("because-you-watched");
    assertThat(recommendations.recommend(identity, profile, "IN", "en", seed.contentId(), 10))
        .isEqualTo(result);
    source.denied.add(seed.contentId());
    assertThatThrownBy(
            () -> recommendations.recommend(identity, profile, "IN", "en", seed.contentId(), 10))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("CONTENT_NOT_AVAILABLE");
  }

  @Test
  void recommendation_popularAndTrending_rankOptInUniqueDailySignals_notIndexAvailability() {
    var first = add("Popular first", List.of("drama"), List.of());
    var second = add("Popular second", List.of("drama"), List.of());
    signals.preference(identity, profile, true);
    start(UUID.randomUUID(), first.contentId(), NOW);
    start(UUID.randomUUID(), first.contentId(), NOW);
    start(UUID.randomUUID(), second.contentId(), NOW);
    var other = UUID.randomUUID();
    var otherAccount = UUID.randomUUID();
    var i = new ViewerAccess.Identity("other-" + other);
    viewers.profiles.put(
        other,
        new WatchIT.Owned(i.viewerToken(), new ViewerAccess.Profile(otherAccount, other, 18)));
    signals.preference(i, other, true);
    signals.start(
        UUID.randomUUID(), otherAccount, other, UUID.randomUUID(), first.contentId(), device, NOW);
    var rails = recommendations.recommend(identity, profile, "IN", "en", null, 10).rails();
    assertThat(
            rails.stream()
                .filter(r -> r.name().equals("popular-opt-in"))
                .findFirst()
                .orElseThrow()
                .items()
                .getFirst()
                .contentId())
        .isEqualTo(first.contentId());
    assertThat(
            rails.stream()
                .filter(r -> r.name().equals("trending-opt-in"))
                .findFirst()
                .orElseThrow()
                .items()
                .getFirst()
                .contentId())
        .isEqualTo(first.contentId());
    source.unavailable = true;
    assertThatThrownBy(() -> recommendations.recommend(identity, profile, "IN", "en", null, 10))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("CATALOG_UNAVAILABLE");
  }

  static class MutableClock extends Clock {
    volatile Instant at = NOW;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return at;
    }
  }

  @TestConfiguration
  static class Time {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }
}
