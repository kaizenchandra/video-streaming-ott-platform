package com.ott.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.insights.application.ExperimentService;
import com.ott.insights.application.InsightsFailure;
import com.ott.insights.application.TelemetryAudience;
import com.ott.insights.application.TelemetryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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
    classes = {InsightsApplication.class, InsightsIT.Ports.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsightsIT {
  @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6");
  static final OidcFixture OIDC = new OidcFixture();
  static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
  @Autowired TelemetryService telemetry;
  @Autowired ExperimentService experiments;
  @Autowired JdbcTemplate db;
  @Autowired Audience audience;
  @Autowired Time clock;
  @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;

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
    p.add("spring.kafka.listener.auto-startup", () -> "false");
    p.add("ott.insights.assignment-secret", () -> TelemetryService.hash("assignment-fixture"));
    p.add("ott.insights.telemetry-secret", () -> TelemetryService.hash("privacy-fixture"));
    p.add("ott.insights.retention-delay-ms", () -> "3600000");
  }

  @BeforeEach
  void reset() {
    db.execute("TRUNCATE insights_account,experiment,telemetry_receipt,outbox_event CASCADE");
    clock.at = NOW;
    audience.account = UUID.randomUUID();
    audience.profile = UUID.randomUUID();
    audience.device = UUID.randomUUID();
    audience.session = UUID.randomUUID();
    audience.unavailable = false;
  }

  TelemetryService.Sample sample(
      UUID id, String kind, long seq, long startup, long buffer, long played) {
    return new TelemetryService.Sample(
        id,
        kind,
        clock.at,
        seq,
        startup,
        buffer,
        played,
        4000,
        "IN",
        "TV",
        "CLOUDFRONT",
        "FHD",
        kind.equals("PLAY_FAILED") ? "NETWORK" : "NONE");
  }

  TelemetryService.Receipt send(TelemetryService.Sample... samples) {
    return telemetry.admit("viewer", audience.profile, audience.device, "grant", List.of(samples));
  }

  void consent() {
    telemetry.consent(audience.account, 1, true, true);
  }

  @Test
  void admit_defaultOffPreConsentAndOptOutPurge_enforced() {
    assertThat(telemetry.consent(audience.account).analytics()).isFalse();
    var old = sample(UUID.randomUUID(), "PLAY_STARTED", 1, 300, 0, 0);
    assertThat(send(old).ignored()).isEqualTo(1);
    assertThat(db.queryForObject("SELECT count(*) FROM telemetry_receipt", Integer.class)).isZero();
    clock.at = NOW.plusSeconds(1);
    consent();
    assertThat(send(old).ignored()).isEqualTo(1);
    assertThat(send(sample(UUID.randomUUID(), "PLAY_STARTED", 2, 300, 0, 0)).accepted())
        .isEqualTo(1);
    telemetry.consent(audience.account, 2, false, false);
    assertThat(db.queryForObject("SELECT count(*) FROM telemetry_event", Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
    assertThatThrownBy(() -> telemetry.consent(audience.account, 2, true, true))
        .isInstanceOf(InsightsFailure.class)
        .extracting("code")
        .isEqualTo("CONSENT_VERSION_CONFLICT");
  }

  @Test
  void admit_duplicateUuidSequenceCollisionAndOutOfOrder_areAtomic() {
    consent();
    UUID id = UUID.randomUUID();
    var r = sample(id, "PLAY_STARTED", 3, 1000, 0, 0);
    assertThat(send(r).accepted()).isEqualTo(1);
    assertThat(send(r).duplicate()).isEqualTo(1);
    assertThatThrownBy(() -> send(sample(id, "PLAY_STARTED", 3, 2000, 0, 0)))
        .isInstanceOf(InsightsFailure.class)
        .extracting("code")
        .isEqualTo("TELEMETRY_EVENT_CONFLICT");
    assertThat(send(sample(UUID.randomUUID(), "BUFFER_ENDED", 1, 0, 100, 1000)).accepted())
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                send(
                    sample(UUID.randomUUID(), "QUALITY_CHANGED", 2, 0, 0, 1000),
                    sample(UUID.randomUUID(), "PLAY_PAUSED", 3, 0, 0, 0)))
        .isInstanceOf(InsightsFailure.class)
        .extracting("code")
        .isEqualTo("TELEMETRY_SEQUENCE_CONFLICT");
    assertThat(db.queryForObject("SELECT count(*) FROM telemetry_event", Integer.class))
        .isEqualTo(2);
    assertThat(db.queryForObject("SELECT count(*) FROM telemetry_receipt", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void admit_invalidClaimsTimesAndServerKinds_rejected() {
    consent();
    assertThatThrownBy(() -> send(sample(UUID.randomUUID(), "PAYMENT_FAILED", 1, 0, 0, 0)))
        .isInstanceOf(InsightsFailure.class);
    assertThatThrownBy(() -> send(sample(UUID.randomUUID(), "BUFFER_ENDED", 1, 0, 1001, 1000)))
        .isInstanceOf(InsightsFailure.class);
    assertThatThrownBy(
            () ->
                telemetry.admit(
                    "viewer",
                    audience.profile,
                    audience.device,
                    null,
                    List.of(sample(UUID.randomUUID(), "PLAY_STARTED", 1, 10, 0, 0))))
        .isInstanceOf(InsightsFailure.class);
    var future = sample(UUID.randomUUID(), "PLAY_STARTED", 1, 20, 0, 0);
    clock.at = NOW.minusSeconds(6);
    assertThatThrownBy(() -> send(future)).isInstanceOf(InsightsFailure.class);
    assertThat(db.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
  }

  @Test
  void summary_intervalDeltasStartupAndFailures_computeWithoutDuplicateInflation() {
    consent();
    var started = sample(UUID.randomUUID(), "PLAY_STARTED", 1, 800, 0, 0);
    send(started);
    send(started);
    send(
        sample(UUID.randomUUID(), "BUFFER_ENDED", 3, 0, 1000, 10000),
        sample(UUID.randomUUID(), "QUALITY_CHANGED", 2, 0, 0, 10000),
        sample(UUID.randomUUID(), "PLAY_FAILED", 4, 0, 0, 0));
    var rows = telemetry.summary(NOW.minusSeconds(1), NOW.plusSeconds(1));
    assertThat(rows).hasSize(1);
    assertThat(((Number) rows.getFirst().get("observations")).longValue()).isEqualTo(4);
    assertThat(((Number) rows.getFirst().get("startup_p95_ms")).doubleValue()).isEqualTo(800);
    assertThat(((Number) rows.getFirst().get("rebuffer_ratio")).doubleValue()).isEqualTo(0.05);
    assertThat(((Number) rows.getFirst().get("failures")).intValue()).isEqualTo(1);
    assertThat(((Number) rows.getFirst().get("quality_switches")).intValue()).isEqualTo(1);
  }

  @Test
  void admit_quotaAndOutboxFailure_rollBackAdmission() {
    consent();
    db.update(
        "INSERT INTO telemetry_daily_quota VALUES(?,?::date,10000)",
        audience.account,
        "2026-10-08");
    assertThatThrownBy(() -> send(sample(UUID.randomUUID(), "PLAY_STARTED", 1, 100, 0, 0)))
        .isInstanceOf(InsightsFailure.class)
        .extracting("code")
        .isEqualTo("TELEMETRY_DAILY_LIMIT");
    assertThat(db.queryForObject("SELECT count(*) FROM telemetry_receipt", Integer.class)).isZero();
    db.update("DELETE FROM telemetry_daily_quota");
    db.execute(
        "ALTER TABLE outbox_event ADD CONSTRAINT fixture_outbox_failure"
            + " CHECK(topic!='qoe.received.v1') NOT VALID");
    try {
      assertThatThrownBy(() -> send(sample(UUID.randomUUID(), "PLAY_STARTED", 1, 100, 0, 0)))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(db.queryForObject("SELECT count(*) FROM telemetry_event", Integer.class)).isZero();
      assertThat(db.queryForObject("SELECT count(*) FROM telemetry_receipt", Integer.class))
          .isZero();
    } finally {
      db.execute("ALTER TABLE outbox_event DROP CONSTRAINT fixture_outbox_failure");
    }
  }

  @Test
  void owner_receiptAndSemanticDuplicatePreConsentStale_enforced() {
    consent();
    UUID id = UUID.randomUUID();
    String hash = TelemetryService.hash("owner");
    telemetry.owner(id, hash, audience.account, "PAYMENT_FAILED", "order-1", NOW);
    telemetry.owner(id, hash, audience.account, "PAYMENT_FAILED", "order-1", NOW);
    telemetry.owner(
        UUID.randomUUID(),
        TelemetryService.hash("another"),
        audience.account,
        "PAYMENT_FAILED",
        "order-1",
        NOW);
    telemetry.owner(
        UUID.randomUUID(),
        TelemetryService.hash("old"),
        audience.account,
        "PAYMENT_FAILED",
        "order-2",
        NOW.minusSeconds(1));
    assertThat(db.queryForObject("SELECT count(*) FROM telemetry_event", Integer.class))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                telemetry.owner(
                    id,
                    TelemetryService.hash("changed"),
                    audience.account,
                    "PAYMENT_FAILED",
                    "order-1",
                    NOW))
        .isInstanceOf(InsightsFailure.class);
    assertThat(telemetry.summary(NOW.minusSeconds(1), NOW.plusSeconds(1))).isEmpty();
  }

  @Test
  void experiments_defaultControlDeterminismVersionAndOptOut() {
    var e =
        experiments.create(
            "home-fixture",
            "HOME_RAILS",
            NOW.minusSeconds(1),
            NOW.plusSeconds(100),
            0,
            100,
            0,
            "admin");
    assertThat(experiments.assignment("viewer", e.id()).reason()).isEqualTo("CONSENT_REQUIRED");
    consent();
    assertThat(experiments.assignment("viewer", e.id()).variant()).isEqualTo("VARIANT_A");
    assertThat(experiments.assignment("viewer", e.id()).variant()).isEqualTo("VARIANT_A");
    var changed = experiments.configure(e.id(), 1, true, 0, 0, 100, "admin");
    assertThat(changed.version()).isEqualTo(2);
    assertThat(experiments.assignment("viewer", e.id()).variant()).isEqualTo("VARIANT_B");
    assertThatThrownBy(() -> experiments.configure(e.id(), 1, false, 100, 0, 0, "admin"))
        .isInstanceOf(InsightsFailure.class);
    telemetry.consent(audience.account, 2, true, false);
    assertThat(db.queryForObject("SELECT count(*) FROM experiment_assignment", Integer.class))
        .isZero();
    assertThat(experiments.assignment("viewer", e.id()).variant()).isEqualTo("CONTROL");
    audience.unavailable = true;
    assertThat(experiments.assignment("viewer", e.id()).reason()).isEqualTo("OWNER_UNAVAILABLE");
  }

  @Test
  void experiments_securityFeatureNeverAllowed_andApiScoped() {
    assertThatThrownBy(
            () ->
                experiments.create(
                    "bad", "ENTITLEMENT", NOW, NOW.plusSeconds(10), 100, 0, 0, "admin"))
        .isInstanceOf(InsightsFailure.class);
    var headers = new org.springframework.http.HttpHeaders();
    headers.setBearerAuth(OIDC.token("viewer", "", false));
    assertThat(
            http.exchange(
                    "/v1/admin/analytics/qoe?from="
                        + NOW.minusSeconds(1)
                        + "&to="
                        + NOW.plusSeconds(1),
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    headers.setBearerAuth(OIDC.token("viewer", "analytics.read", false));
    assertThat(
            http.exchange(
                    "/v1/admin/analytics/qoe?from="
                        + NOW.minusSeconds(1)
                        + "&to="
                        + NOW.plusSeconds(1),
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
  }

  @Test
  void retention_expiredDetailsPurged_withoutDroppingRecent() {
    consent();
    send(sample(UUID.randomUUID(), "PLAY_STARTED", 1, 100, 0, 0));
    clock.at = NOW.plusSeconds(31L * 86400);
    telemetry.retention();
    assertThat(db.queryForObject("SELECT count(*) FROM telemetry_event", Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT count(*) FROM telemetry_receipt", Integer.class)).isZero();
  }

  @Test
  void admission_duplicateRaceAndOptOutRace_areSerialized() throws Exception {
    consent();
    var r = sample(UUID.randomUUID(), "PLAY_STARTED", 1, 300, 0, 0);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var calls =
          new java.util.ArrayList<java.util.concurrent.Callable<TelemetryService.Receipt>>();
      for (int n = 0; n < 8; n++) calls.add(() -> send(r));
      int accepted = 0, duplicates = 0;
      for (var f : pool.invokeAll(calls)) {
        var receipt = f.get();
        accepted += receipt.accepted();
        duplicates += receipt.duplicate();
      }
      assertThat(accepted).isEqualTo(1);
      assertThat(duplicates).isEqualTo(7);
      var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
      for (int n = 0; n < 7; n++) {
        final int seq = n + 2;
        tasks.add(
            () -> {
              send(sample(UUID.randomUUID(), "PLAY_PAUSED", seq, 0, 0, 0));
              return null;
            });
      }
      tasks.add(
          () -> {
            telemetry.consent(audience.account, 2, false, false);
            return null;
          });
      for (var f : pool.invokeAll(tasks)) f.get();
    }
    assertThat(telemetry.consent(audience.account).analytics()).isFalse();
    assertThat(db.queryForObject("SELECT count(*) FROM telemetry_event", Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
  }

  static class Audience implements TelemetryAudience {
    volatile UUID account, profile, device, session;
    volatile boolean unavailable;

    public UUID account(String t) {
      if (unavailable) throw new InsightsFailure(503, "TELEMETRY_OWNER_UNAVAILABLE");
      return account;
    }

    public Context verify(String t, UUID p, UUID d, String token) {
      if (!profile.equals(p) || !device.equals(d))
        throw new InsightsFailure(403, "TELEMETRY_GRANT_DENIED");
      return new Context(account, p, d, token == null ? null : session);
    }
  }

  static class Time extends Clock {
    volatile Instant at = NOW;

    public ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
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
    Audience audience() {
      return new Audience();
    }

    @Bean
    @Primary
    Time time() {
      return new Time();
    }
  }
}
