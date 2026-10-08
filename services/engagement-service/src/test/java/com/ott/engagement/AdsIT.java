package com.ott.engagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.engagement.application.AdAccess;
import com.ott.engagement.application.AdDelivery;
import com.ott.engagement.application.AdService;
import com.ott.engagement.application.EngagementFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
    classes = {EngagementApplication.class, AdsIT.Ports.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdsIT {
  @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6");
  static final OidcFixture OIDC = new OidcFixture();
  static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
  @Autowired AdService service;
  @Autowired TestAccess access;
  @Autowired TestDelivery delivery;
  @Autowired Time clock;
  @Autowired JdbcTemplate db;
  @Autowired TestRestTemplate http;
  UUID account, profile, device, session;

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
    p.add("ott.notifications.enabled", () -> "false");
    p.add("ott.ads.creative-hosts", () -> "local-cdn.invalid");
  }

  @BeforeEach
  void reset() {
    db.execute("TRUNCATE ad_account,ad_campaign,ad_receipt,ad_audit,outbox_event CASCADE");
    clock.at = NOW;
    account = UUID.randomUUID();
    profile = UUID.randomUUID();
    device = UUID.randomUUID();
    session = UUID.randomUUID();
    access.context =
        new AdAccess.Context(
            account, profile, device, session, "IN", 18, false, true, NOW.plusSeconds(60));
    delivery.fail = false;
    delivery.latch = null;
    delivery.calls.set(0);
    delivery.entered.drainPermits();
  }

  AdService.Campaign campaign(int cap) {
    return service.create(
        "Synthetic campaign",
        "IN",
        7,
        "PRE_ROLL",
        NOW.minusSeconds(1),
        NOW.plusSeconds(3600),
        cap,
        "https://local-cdn.invalid/ad.mp4",
        4000,
        "operator");
  }

  AdService.Result decide(String key) {
    return service.decide("viewer", profile, device, "grant", "PRE_ROLL", "CSAI", key);
  }

  @Test
  void decide_parallelCapAndIdempotency_neverIssuesMoreThanTwo() throws Exception {
    campaign(2);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<String>>();
      for (int n = 0; n < 8; n++) tasks.add(() -> decide("request-" + UUID.randomUUID()).reason());
      int filled = 0;
      for (var f : pool.invokeAll(tasks, 10, TimeUnit.SECONDS))
        if (f.get().equals("FILLED")) filled++;
      assertThat(filled).isEqualTo(2);
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM ad_decision WHERE state='ACTIVE'", Integer.class))
        .isEqualTo(2);
    assertThat(delivery.calls.get()).isEqualTo(2);
  }

  @Test
  void decide_sameKeyAndOmittedImpression_bindRequestAndKeepCap() {
    campaign(1);
    var first = decide("decision-key-01");
    assertThat(decide("decision-key-01").decision().id()).isEqualTo(first.decision().id());
    assertThat(delivery.calls.get()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                service.decide(
                    "viewer", profile, device, "grant", "PRE_ROLL", "SSAI", "decision-key-01"))
        .isInstanceOf(EngagementFailure.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    clock.at = NOW.plusSeconds(61);
    access.context =
        new AdAccess.Context(
            account, profile, device, session, "IN", 18, false, true, clock.at.plusSeconds(60));
    assertThat(decide("another-key-01").reason()).isEqualTo("NO_FILL_OR_CAPPED");
    assertThat(db.queryForObject("SELECT count(*) FROM ad_event", Integer.class)).isZero();
  }

  @Test
  void decide_kidsAdFreeRegionAndMaturity_skipWithoutProvider() {
    campaign(1);
    access.context =
        new AdAccess.Context(
            account, profile, device, session, "IN", 18, true, true, NOW.plusSeconds(60));
    assertThat(decide("kids-request-01").reason()).isEqualTo("KIDS_EXCLUDED");
    access.context =
        new AdAccess.Context(
            account, profile, device, session, "IN", 18, false, false, NOW.plusSeconds(60));
    assertThat(decide("adfree-request-01").reason()).isEqualTo("AD_FREE_OR_INACTIVE");
    access.context =
        new AdAccess.Context(
            account, profile, device, session, "US", 18, false, true, NOW.plusSeconds(60));
    assertThat(decide("region-request-01").reason()).isEqualTo("NO_FILL_OR_CAPPED");
    access.context =
        new AdAccess.Context(
            account, profile, device, session, "IN", 0, false, true, NOW.plusSeconds(60));
    assertThat(decide("maturity-request-01").reason()).isEqualTo("NO_FILL_OR_CAPPED");
    assertThat(delivery.calls.get()).isZero();
  }

  @Test
  void decide_providerFailureAndExpiredReservation_releaseOnlyUnissuedSlots() {
    campaign(1);
    delivery.fail = true;
    assertThat(decide("provider-request-01").reason()).isEqualTo("PROVIDER_SKIPPED");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM ad_decision WHERE state='SKIPPED'", Integer.class))
        .isEqualTo(1);
    delivery.fail = false;
    var granted = decide("provider-request-02");
    assertThat(granted.reason()).isEqualTo("FILLED");
    assertThat(granted.decision().expiresAt()).isEqualTo(NOW.plusSeconds(60));
  }

  @Test
  void decide_providerOutsideTransaction_pendingReservationFencesParallelRequest()
      throws Exception {
    campaign(1);
    delivery.latch = new java.util.concurrent.CountDownLatch(1);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var first = pool.submit(() -> decide("pending-request-01"));
      assertThat(delivery.entered.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> decide("pending-request-01"))
          .isInstanceOf(EngagementFailure.class)
          .extracting("code")
          .isEqualTo("AD_DECISION_PENDING");
      assertThat(decide("pending-request-02").reason()).isEqualTo("NO_FILL_OR_CAPPED");
      delivery.latch.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).reason()).isEqualTo("FILLED");
    }
  }

  @Test
  void decide_campaignDisabledDuringProviderCall_neverIssuesStaleDecision() throws Exception {
    var c = campaign(1);
    delivery.latch = new java.util.concurrent.CountDownLatch(1);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var first = pool.submit(() -> decide("disable-pending-01"));
      assertThat(delivery.entered.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
      service.configure(c.id(), 1, false, "operator");
      delivery.latch.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).decision()).isNull();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM ad_decision WHERE state='ACTIVE'", Integer.class))
          .isZero();
    }
  }

  @Test
  void report_sameReceiptAcrossDecisions_concurrentConflictIsExplicit() throws Exception {
    campaign(2);
    var first = decide("receipt-first-01").decision();
    var second = decide("receipt-second-01").decision();
    UUID event = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var tasks =
          java.util.List.<Callable<String>>of(
              () -> report(first.id(), event), () -> report(second.id(), event));
      var results = new ArrayList<String>();
      for (var future : pool.invokeAll(tasks, 5, TimeUnit.SECONDS)) results.add(future.get());
      assertThat(results).containsExactlyInAnyOrder("ACCEPTED", "IDEMPOTENCY_CONFLICT");
    }
    assertThat(db.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isEqualTo(1);
  }

  private String report(UUID decision, UUID event) {
    try {
      service.report("viewer", profile, device, "grant", decision, event, 0, NOW);
      return "ACCEPTED";
    } catch (EngagementFailure e) {
      return e.code();
    }
  }

  @Test
  void report_duplicatesOrderTimingAndOwnership_commitOneSemanticOutbox() {
    campaign(2);
    var decision = decide("measurement-key-01").decision();
    UUID event = UUID.randomUUID();
    assertThat(
            service
                .report("viewer", profile, device, "grant", decision.id(), event, 0, NOW)
                .duplicate())
        .isFalse();
    assertThat(
            service
                .report("viewer", profile, device, "grant", decision.id(), event, 0, NOW)
                .duplicate())
        .isTrue();
    assertThatThrownBy(
            () -> service.report("viewer", profile, device, "grant", decision.id(), event, 1, NOW))
        .isInstanceOf(EngagementFailure.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(
            () ->
                service.report(
                    "viewer", profile, device, "grant", decision.id(), UUID.randomUUID(), 4, NOW))
        .isInstanceOf(EngagementFailure.class)
        .extracting("code")
        .isEqualTo("IMPOSSIBLE_AD_EVENT_TIME");
    clock.at = NOW.plusSeconds(5);
    assertThat(
            service
                .report(
                    "viewer",
                    profile,
                    device,
                    "grant",
                    decision.id(),
                    UUID.randomUUID(),
                    4,
                    NOW.plusSeconds(4))
                .version())
        .isEqualTo(2);
    assertThat(
            service
                .report(
                    "viewer",
                    profile,
                    device,
                    "grant",
                    decision.id(),
                    UUID.randomUUID(),
                    1,
                    NOW.plusSeconds(1))
                .version())
        .isEqualTo(3);
    assertThat(
            service
                .report(
                    "viewer",
                    profile,
                    device,
                    "grant",
                    decision.id(),
                    UUID.randomUUID(),
                    1,
                    NOW.plusSeconds(2))
                .duplicate())
        .isTrue();
    access.context =
        new AdAccess.Context(
            UUID.randomUUID(),
            profile,
            device,
            session,
            "IN",
            18,
            false,
            true,
            NOW.plusSeconds(60));
    assertThatThrownBy(
            () ->
                service.report(
                    "foreign", profile, device, "grant", decision.id(), UUID.randomUUID(), 0, NOW))
        .isInstanceOf(EngagementFailure.class)
        .extracting("code")
        .isEqualTo("AD_GRANT_DENIED");
    assertThat(db.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isEqualTo(3);
  }

  @Test
  void report_outboxFailure_rollsBackReceiptEventAndVersion() {
    campaign(1);
    var d = decide("rollback-key-01").decision();
    UUID event = UUID.randomUUID();
    db.execute(
        "ALTER TABLE outbox_event ADD CONSTRAINT fixture_ad_failure"
            + " CHECK(topic!='ad.impression.v1') NOT VALID");
    try {
      assertThatThrownBy(
              () -> service.report("viewer", profile, device, "grant", d.id(), event, 0, NOW))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(db.queryForObject("SELECT count(*) FROM ad_receipt", Integer.class)).isZero();
      assertThat(db.queryForObject("SELECT count(*) FROM ad_event", Integer.class)).isZero();
    } finally {
      db.execute("ALTER TABLE outbox_event DROP CONSTRAINT fixture_ad_failure");
    }
    assertThat(service.report("viewer", profile, device, "grant", d.id(), event, 0, NOW).version())
        .isEqualTo(1);
  }

  @Test
  void campaign_apiScopeVersionAndCreativeHost_enforced() {
    var c = campaign(1);
    assertThatThrownBy(
            () ->
                service.create(
                    "x",
                    "IN",
                    0,
                    "PRE_ROLL",
                    NOW,
                    NOW.plusSeconds(1),
                    1,
                    "https://evil.invalid/ad",
                    1000,
                    "operator"))
        .isInstanceOf(EngagementFailure.class);
    assertThat(service.configure(c.id(), 1, false, "operator").enabled()).isFalse();
    assertThatThrownBy(() -> service.configure(c.id(), 1, true, "operator"))
        .isInstanceOf(EngagementFailure.class)
        .extracting("code")
        .isEqualTo("CAMPAIGN_VERSION_CONFLICT");
    var headers = new HttpHeaders();
    headers.setBearerAuth(OIDC.token("viewer", "", false));
    assertThat(
            http.exchange(
                    "/v1/admin/ads/campaigns",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    headers.setBearerAuth(OIDC.token("operator", "ads.write", false));
    assertThat(
            http.exchange(
                    "/v1/admin/ads/campaigns",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
  }

  static class TestAccess implements AdAccess {
    volatile Context context;

    public Context verify(String v, UUID p, UUID d, String token) {
      return context;
    }
  }

  static class TestDelivery implements AdDelivery {
    volatile boolean fail;
    volatile java.util.concurrent.CountDownLatch latch;
    final java.util.concurrent.Semaphore entered = new java.util.concurrent.Semaphore(0);
    final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();

    public Creative deliver(String adapter, UUID decision, AdService.Campaign c, Instant expiry) {
      calls.incrementAndGet();
      entered.release();
      if (latch != null)
        try {
          if (!latch.await(5, TimeUnit.SECONDS))
            throw new EngagementFailure(503, "AD_PROVIDER_UNAVAILABLE");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new EngagementFailure(503, "AD_PROVIDER_UNAVAILABLE");
        }
      if (fail) throw new EngagementFailure(503, "AD_PROVIDER_UNAVAILABLE");
      return new Creative(c.creativeUrl(), c.durationMillis());
    }
  }

  static class Time extends Clock {
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
  static class Ports {
    @Bean
    @Primary
    TestAccess access() {
      return new TestAccess();
    }

    @Bean
    @Primary
    TestDelivery delivery() {
      return new TestDelivery();
    }

    @Bean
    @Primary
    Time time() {
      return new Time();
    }
  }
}
