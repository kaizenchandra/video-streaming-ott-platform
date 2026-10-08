package com.ott.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.content.application.CatalogService;
import com.ott.content.application.LiveService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
    classes = {ContentApplication.class, ContentIT.TimeConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveIT {
  @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6");
  static final OidcFixture OIDC = new OidcFixture();
  static final String SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();
  static final Instant NOW = ContentIT.NOW;
  @Autowired CatalogService catalog;
  @Autowired LiveService live;
  @Autowired ContentIT.TestClock clock;
  @Autowired JdbcTemplate db;
  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper json;

  @AfterAll
  static void close() {
    OIDC.close();
  }

  @BeforeEach
  void reset() {
    clock.now = NOW;
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", DB::getJdbcUrl);
    p.add("spring.datasource.username", DB::getUsername);
    p.add("spring.datasource.password", DB::getPassword);
    p.add("ott.security.issuer", OIDC::issuer);
    p.add("ott.security.jwks-uri", OIDC::jwks);
    p.add("ott.media-consumer.enabled", () -> "false");
    p.add("ott.outbox.enabled", () -> "false");
    p.add("ott.live.feed-secret", () -> SECRET);
  }

  CatalogService.Item publish(String type) {
    var c = catalog.create(type, null, 7, "en", "Synthetic sports fixture", "");
    catalog.mediaReady(UUID.randomUUID(), c.id(), 1, "vod/" + c.id() + "/v1/", "a".repeat(64));
    var review = catalog.review(c.id(), 1, "en");
    return catalog.publish(c.id(), review.version(), 1, "en");
  }

  LiveService.Event event() {
    var c = publish("SPORT");
    catalog.grant(c.id(), "IN", NOW.minusSeconds(60), NOW.plusSeconds(10000), false);
    return live.create(
        c.id(),
        "Test League",
        "Home",
        "Away",
        "IN",
        NOW.minusSeconds(1),
        NOW.plusSeconds(3600),
        "packager-fixture",
        120,
        "operator");
  }

  LiveService.Feed proof(LiveService.Event e, long seq, boolean sealed) {
    return new LiveService.Feed(
        e.feedEpoch(),
        seq,
        true,
        sealed,
        NOW.minusSeconds(90),
        clock.now,
        "b".repeat(64),
        List.of(new LiveService.Track("en", "English"), new LiveService.Track("hi", "Hindi")),
        List.of("en"));
  }

  LiveService.Event feed(LiveService.Event e, long seq, boolean sealed) {
    var body = proof(e, seq, sealed);
    return live.heartbeat(e.id(), "packager-fixture", body, LiveService.hash(body.toString()));
  }

  LiveService.Event start(LiveService.Event e) {
    feed(e, 1, false);
    var ready =
        live.transition(e.id(), e.version(), "READY", "ready-request-01", null, "operator").event();
    return live.transition(e.id(), ready.version(), "LIVE", "start-request-01", null, "operator")
        .event();
  }

  @Test
  void transition_parallelStartsAndDuplicateKeys_commitOneStateEventAndAudit() throws Exception {
    var e = event();
    feed(e, 1, false);
    var ready = live.transition(e.id(), 1, "READY", "ready-key-01", null, "operator").event();
    try (var pool = Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<Callable<Boolean>>();
      for (int n = 0; n < 6; n++)
        tasks.add(
            () -> {
              try {
                return !live.transition(
                        e.id(),
                        ready.version(),
                        "LIVE",
                        "start-" + UUID.randomUUID(),
                        null,
                        "operator")
                    .duplicate();
              } catch (CatalogService.Failure failure) {
                assertThat(failure.code()).isEqualTo("LIVE_VERSION_OR_STATE_CONFLICT");
                return false;
              }
            });
      int accepted = 0;
      for (var result : pool.invokeAll(tasks, 10, TimeUnit.SECONDS)) if (result.get()) accepted++;
      assertThat(accepted).isEqualTo(1);
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND"
                    + " payload->>'state'='LIVE'",
                Integer.class,
                e.id()))
        .isEqualTo(1);
    var ended = live.get(e.id());
    var failedKey = "bad-reuse-key-01";
    assertThatThrownBy(
            () ->
                live.transition(e.id(), ended.version(), "CANCELLED", failedKey, null, "operator"))
        .isInstanceOf(CatalogService.Failure.class);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM live_command WHERE event_id=? AND idempotency_key=?",
                Integer.class,
                e.id(),
                failedKey))
        .isZero();
  }

  @Test
  void heartbeat_duplicateDoesNotRefreshAndEpochFencesOldPublisher() {
    var e = event();
    var originalProof = proof(e, 1, false);
    live.heartbeat(
        e.id(), "packager-fixture", originalProof, LiveService.hash(originalProof.toString()));
    var ready = live.transition(e.id(), 1, "READY", "ready-request-01", null, "operator").event();
    var active =
        live.transition(e.id(), ready.version(), "LIVE", "start-request-01", null, "operator")
            .event();
    clock.now = NOW.plusSeconds(31);
    var duplicate =
        live.heartbeat(
            e.id(), "packager-fixture", originalProof, LiveService.hash(originalProof.toString()));
    assertThat(duplicate.healthReceivedAt()).isEqualTo(NOW);
    assertThat(live.playback(e.id(), "LIVE", "IN", 18, null).allowed()).isFalse();
    var switched =
        live.failover(e.id(), active.version(), "failover-request-01", "operator").event();
    assertThat(switched.feedEpoch()).isEqualTo(2);
    assertThat(live.playback(e.id(), "LIVE", "IN", 18, null).allowed()).isFalse();
    assertThatThrownBy(() -> feed(e, 2, false))
        .isInstanceOf(CatalogService.Failure.class)
        .extracting("code")
        .isEqualTo("STALE_SOURCE_EPOCH");
    feed(switched, 1, false);
    assertThat(live.playback(e.id(), "LIVE", "IN", 18, null).originPrefix()).endsWith("/e2/");
    assertThat(
            live.failover(e.id(), active.version(), "failover-request-01", "operator").duplicate())
        .isTrue();
  }

  @Test
  void heartbeat_sequenceCollisionForeignSubjectAndFutureMedia_rejected() {
    var e = event();
    var body = proof(e, 1, false);
    live.heartbeat(e.id(), "packager-fixture", body, LiveService.hash(body.toString()));
    assertThatThrownBy(() -> live.heartbeat(e.id(), "foreign", proof(e, 2, false), "c".repeat(64)))
        .isInstanceOf(CatalogService.Failure.class)
        .extracting("code")
        .isEqualTo("PACKAGER_NOT_ALLOWED");
    assertThatThrownBy(() -> live.heartbeat(e.id(), "packager-fixture", body, "c".repeat(64)))
        .isInstanceOf(CatalogService.Failure.class)
        .extracting("code")
        .isEqualTo("FEED_SEQUENCE_CONFLICT");
    var future =
        new LiveService.Feed(
            1, 2, true, false, NOW, NOW.plusSeconds(6), "b".repeat(64), List.of(), List.of());
    assertThatThrownBy(() -> live.heartbeat(e.id(), "packager-fixture", future, "c".repeat(64)))
        .isInstanceOf(CatalogService.Failure.class)
        .extracting("code")
        .isEqualTo("INVALID_FEED_PROOF");
  }

  @Test
  void playback_freshnessDvrBoundsAndGeoMaturity_failClosed() {
    var e = start(event());
    assertThat(live.playback(e.id(), "LIVE", "IN", 18, null).allowed()).isTrue();
    assertThat(live.playback(e.id(), "DVR", "IN", 18, NOW.minusSeconds(60)).allowed()).isTrue();
    assertThat(live.playback(e.id(), "DVR", "IN", 18, NOW.minusSeconds(121)).code())
        .isEqualTo("DVR_SEEK_OUTSIDE_WINDOW");
    assertThat(live.playback(e.id(), "LIVE", "US", 18, null).allowed()).isFalse();
    assertThat(live.playback(e.id(), "LIVE", "IN", 0, null).allowed()).isFalse();
    assertThat(catalog.rights(e.contentId(), "IN", 18, false).code())
        .isEqualTo("LIVE_EVENT_REQUIRED");
    clock.now = NOW.plusSeconds(30);
    assertThat(live.playback(e.id(), "LIVE", "IN", 18, null).code())
        .isEqualTo("LIVE_SOURCE_UNAVAILABLE");
  }

  @Test
  void endArchiveAndHighlight_requireSealedPublishedSeparateRights() {
    var e = start(event());
    assertThatThrownBy(
            () -> live.transition(e.id(), e.version(), "ENDED", "end-request-01", null, "operator"))
        .isInstanceOf(CatalogService.Failure.class)
        .extracting("code")
        .isEqualTo("SEALED_SOURCE_REQUIRED");
    feed(e, 2, true);
    clock.now = NOW.plusSeconds(40);
    var ended =
        live.transition(e.id(), e.version(), "ENDED", "end-request-01", null, "operator").event();
    assertThat(ended.endedAt()).isEqualTo(NOW);
    assertThat(live.playback(e.id(), "DVR", "IN", 18, NOW.minusSeconds(30)).allowed()).isTrue();
    clock.now = NOW;
    var replay = publish("MOVIE");
    var archive =
        live.transition(
                e.id(), ended.version(), "ARCHIVED", "archive-request-01", replay.id(), "operator")
            .event();
    assertThat(live.playback(e.id(), "REPLAY", "IN", 18, null).allowed()).isFalse();
    catalog.grant(replay.id(), "IN", NOW, NOW.plusSeconds(3600), false);
    assertThat(live.playback(e.id(), "REPLAY", "IN", 18, null).originPrefix())
        .isEqualTo("vod/" + replay.id() + "/v1/");
    var highlight = live.highlight(e.id(), replay.id(), "Winning goal", 1000, 2000, "operator");
    assertThat(live.highlight(e.id(), replay.id(), "Winning goal", 1000, 2000, "operator"))
        .isEqualTo(highlight);
    assertThat(live.highlights(e.id())).hasSize(1);
    assertThat(live.get(e.id()).version()).isGreaterThan(archive.version());
  }

  @Test
  void reschedule_invalidatesProofAndCommandsCannotRestartCancelledEvent() {
    var event = event();
    feed(event, 1, false);
    var ready =
        live.transition(event.id(), 1, "READY", "schedule-ready-01", null, "operator").event();
    var moved =
        live.reschedule(
                event.id(),
                ready.version(),
                "move-request-01",
                NOW.plusSeconds(300),
                NOW.plusSeconds(3600),
                "operator")
            .event();
    assertThat(moved.state()).isEqualTo("SCHEDULED");
    assertThat(moved.feedEpoch()).isEqualTo(2);
    assertThat(moved.healthy()).isFalse();
    assertThat(
            live.reschedule(
                    event.id(),
                    ready.version(),
                    "move-request-01",
                    NOW.plusSeconds(300),
                    NOW.plusSeconds(3600),
                    "operator")
                .duplicate())
        .isTrue();
    assertThatThrownBy(
            () ->
                live.reschedule(
                    event.id(),
                    ready.version(),
                    "move-request-01",
                    NOW.plusSeconds(400),
                    NOW.plusSeconds(3600),
                    "operator"))
        .isInstanceOf(CatalogService.Failure.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    var cancelled =
        live.transition(
                event.id(), moved.version(), "CANCELLED", "cancel-request-01", null, "operator")
            .event();
    assertThatThrownBy(
            () ->
                live.transition(
                    event.id(),
                    cancelled.version(),
                    "READY",
                    "ready-after-cancel",
                    null,
                    "operator"))
        .isInstanceOf(CatalogService.Failure.class);
    assertThat(live.playback(event.id(), "LIVE", "IN", 18, null).allowed()).isFalse();
  }

  @Test
  void transition_outboxFailure_rollsBackCommandStateAndAudit() {
    var e = event();
    feed(e, 1, false);
    db.execute(
        "ALTER TABLE outbox_event ADD CONSTRAINT fixture_live_failure"
            + " CHECK(topic!='live.changed.v1') NOT VALID");
    try {
      assertThatThrownBy(
              () -> live.transition(e.id(), 1, "READY", "transaction-key-01", null, "operator"))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(live.get(e.id()).version()).isEqualTo(1);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM live_command WHERE event_id=?", Integer.class, e.id()))
          .isZero();
    } finally {
      db.execute("ALTER TABLE outbox_event DROP CONSTRAINT fixture_live_failure");
    }
    assertThat(
            live.transition(e.id(), 1, "READY", "transaction-key-01", null, "operator")
                .event()
                .state())
        .isEqualTo("READY");
  }

  @Test
  void api_signedRawFeedScopeSubjectAndTampering_enforced() throws Exception {
    var e = event();
    byte[] raw = json.writeValueAsBytes(proof(e, 1, false));
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(OIDC.token("packager-fixture", "live.feed", false));
    String timestamp = Long.toString(NOW.getEpochSecond());
    headers.set("X-Feed-Timestamp", timestamp);
    headers.set("X-Feed-Signature", sign(timestamp, raw));
    String uri = "/internal/v1/live/feeds/" + e.id() + "/heartbeat";
    assertThat(
            http.exchange(uri, HttpMethod.POST, new HttpEntity<>(raw, headers), String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    byte[] changed = json.writeValueAsBytes(proof(e, 2, false));
    assertThat(
            http.exchange(uri, HttpMethod.POST, new HttpEntity<>(changed, headers), String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    headers.setBearerAuth(OIDC.token("packager-fixture", "content.internal", false));
    assertThat(
            http.exchange(uri, HttpMethod.POST, new HttpEntity<>(raw, headers), String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    var viewer = new HttpHeaders();
    viewer.setBearerAuth(OIDC.token("viewer", "", false));
    var response =
        http.exchange(
            "/v1/live/events/" + e.id(), HttpMethod.GET, new HttpEntity<>(viewer), String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).doesNotContain("packagerSubject", "manifestSha", "feedEpoch");
  }

  private String sign(String at, byte[] bytes) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((at + ".").getBytes(StandardCharsets.UTF_8));
    return java.util.HexFormat.of().formatHex(mac.doFinal(bytes));
  }
}
