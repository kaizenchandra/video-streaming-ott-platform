package com.ott.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.operations.application.OperationsFailure;
import com.ott.operations.application.OperationsService;
import com.ott.operations.application.OwnerControl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
    classes = {OperationsApplication.class, OperationsIT.Ports.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperationsIT {
  @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6");
  static final OidcFixture OIDC = new OidcFixture();
  static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
  @Autowired OperationsService service;
  @Autowired JdbcTemplate db;
  @Autowired Owner owner;
  @Autowired Time clock;
  @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
  UUID account;

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
    p.add("ott.operations.command-delay-ms", () -> "3600000");
    p.add("ott.operations.retention-delay-ms", () -> "3600000");
  }

  @BeforeEach
  void reset() {
    db.execute("TRUNCATE operations_account,support_request,outbox_event CASCADE");
    account = UUID.randomUUID();
    clock.at = NOW;
    owner.error = 0;
    owner.calls = 0;
    owner.versions.clear();
  }

  OperationsService.Case support() {
    return service.support(UUID.randomUUID(), account, "support-actor");
  }

  @Test
  void signals_sourceDedupStaleTimeAndWindow_rulesOpenOnlyOneCase() {
    UUID event = UUID.randomUUID();
    var sample = new OperationsService.Signal(event, account, "LOGIN_FAILURE", "UNKNOWN", NOW);
    assertThat(service.signal(sample, "idp").accepted()).isTrue();
    assertThat(service.signal(sample, "idp").duplicate()).isTrue();
    assertThatThrownBy(
            () ->
                service.signal(
                    new OperationsService.Signal(event, account, "LOGIN_FAILURE", "IN", NOW),
                    "idp"))
        .isInstanceOf(OperationsFailure.class)
        .extracting("code")
        .isEqualTo("SIGNAL_EVENT_CONFLICT");
    for (int n = 0; n < 9; n++)
      service.signal(
          new OperationsService.Signal(UUID.randomUUID(), account, "LOGIN_FAILURE", "UNKNOWN", NOW),
          "idp");
    assertThat(service.cases(null, 20)).hasSize(1);
    assertThat(service.cases(null, 20).getFirst().kind()).isEqualTo("CREDENTIAL_ABUSE");
    service.signal(
        new OperationsService.Signal(UUID.randomUUID(), account, "LOGIN_FAILURE", "UNKNOWN", NOW),
        "idp");
    assertThat(service.cases(null, 20)).hasSize(1);
    assertThat(
            service
                .signal(
                    new OperationsService.Signal(
                        UUID.randomUUID(),
                        account,
                        "STREAM_LIMIT",
                        "UNKNOWN",
                        NOW.minusSeconds(3601)),
                    "edge")
                .ignored())
        .isTrue();
    assertThatThrownBy(
            () ->
                service.signal(
                    new OperationsService.Signal(
                        UUID.randomUUID(), account, "STREAM_LIMIT", "UNKNOWN", NOW.plusSeconds(6)),
                    "edge"))
        .isInstanceOf(OperationsFailure.class);
  }

  @Test
  void signal_distinctGeoAndQuota_areBounded() {
    for (String region : new String[] {"IN", "IN", "US"})
      service.signal(
          new OperationsService.Signal(UUID.randomUUID(), account, "GEO_CHANGE", region, NOW),
          "edge");
    assertThat(service.cases(null, 20)).isEmpty();
    service.signal(
        new OperationsService.Signal(UUID.randomUUID(), account, "GEO_CHANGE", "GB", NOW), "edge");
    assertThat(service.cases(null, 20).getFirst().kind()).isEqualTo("GEO_ABUSE");
    db.update("UPDATE signal_quota SET count=1000 WHERE account_id=?", account);
    assertThatThrownBy(
            () ->
                service.signal(
                    new OperationsService.Signal(
                        UUID.randomUUID(), account, "GEO_CHANGE", "DE", NOW),
                    "edge"))
        .isInstanceOf(OperationsFailure.class)
        .extracting("code")
        .isEqualTo("SECURITY_SIGNAL_LIMIT");
    assertThat(db.queryForObject("SELECT count(*) FROM security_signal", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void support_durableCommandReceiptOutsideTxAndAppendOnlyAudit() {
    UUID request = UUID.randomUUID();
    var c = service.support(request, account, "support-actor");
    assertThat(service.support(request, account, "support-actor").id()).isEqualTo(c.id());
    UUID id = UUID.randomUUID();
    var queued = service.enqueue(id, c.id(), 1, "SUSPEND_ACCOUNT", "admin");
    assertThat(service.enqueue(id, c.id(), 1, "SUSPEND_ACCOUNT", "admin").id()).isEqualTo(id);
    assertThatThrownBy(() -> service.enqueue(id, c.id(), 1, "RESUME_ACCOUNT", "admin"))
        .isInstanceOf(OperationsFailure.class);
    assertThatThrownBy(() -> service.resolve(c.id(), 2, "admin"))
        .isInstanceOf(OperationsFailure.class)
        .extracting("code")
        .isEqualTo("COMMAND_UNRESOLVED");
    service.dispatch();
    assertThat(service.command(id).state()).isEqualTo("APPLIED");
    assertThat(service.command(id).ownerVersion()).isEqualTo(1);
    assertThat(owner.calls).isEqualTo(1);
    assertThat(service.resolve(c.id(), service.get(c.id()).version(), "admin").status())
        .isEqualTo("RESOLVED");
    assertThat(service.audit(c.id(), null, 20)).hasSize(4);
    assertThatThrownBy(() -> db.update("DELETE FROM operations_audit WHERE case_id=?", c.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void command_unknownOutcomeRecoveryAndExhaustion_areExplicit() {
    var c = support();
    UUID id = UUID.randomUUID();
    service.enqueue(id, c.id(), 1, "REVOKE_ENTITLEMENT", "admin");
    owner.error = 503;
    service.dispatch();
    assertThat(service.command(id).state()).isEqualTo("RETRY");
    owner.error = 0;
    clock.at = NOW.plusSeconds(20);
    service.dispatch();
    assertThat(service.command(id).state()).isEqualTo("APPLIED");
    assertThat(owner.versions).hasSize(1);
    var next =
        service.enqueue(
            UUID.randomUUID(),
            c.id(),
            service.get(c.id()).version(),
            "RESTORE_ENTITLEMENT",
            "admin");
    owner.error = 503;
    for (int n = 0; n < 5; n++) {
      service.dispatch();
      clock.at = clock.at.plusSeconds(300);
    }
    assertThat(service.command(next.id()).state()).isEqualTo("RECONCILE");
    assertThatThrownBy(() -> service.resolve(c.id(), service.get(c.id()).version(), "admin"))
        .isInstanceOf(OperationsFailure.class);
  }

  @Test
  void command_ownerRejectionAndAckOutboxFailure_recoverWithoutDuplicateOwnerEffect() {
    var c = support();
    var cmd = service.enqueue(UUID.randomUUID(), c.id(), 1, "SUSPEND_ACCOUNT", "admin");
    owner.error = 409;
    service.dispatch();
    assertThat(service.command(cmd.id()).state()).isEqualTo("REJECTED");
    owner.error = 0;
    var next =
        service.enqueue(
            UUID.randomUUID(), c.id(), service.get(c.id()).version(), "RESUME_ACCOUNT", "admin");
    db.execute(
        "ALTER TABLE outbox_event ADD CONSTRAINT fixture_action_failure"
            + " CHECK(topic!='abuse.action.v1') NOT VALID");
    try {
      assertThatThrownBy(() -> service.dispatch())
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(service.command(next.id()).state()).isEqualTo("RUNNING");
    } finally {
      db.execute("ALTER TABLE outbox_event DROP CONSTRAINT fixture_action_failure");
    }
    clock.at = NOW.plusSeconds(11);
    service.dispatch();
    assertThat(service.command(next.id()).state()).isEqualTo("APPLIED");
    assertThat(owner.versions).hasSize(1);
  }

  @Test
  void api_narrowSupportReadWriteAdminAndIngestAuthorities() {
    var headers = new org.springframework.http.HttpHeaders();
    headers.setBearerAuth(OIDC.token("viewer", "", false));
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    assertThat(
            http.exchange(
                    "/v1/support/cases",
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    headers.setBearerAuth(OIDC.token("reader", "support.read", false));
    assertThat(
            http.exchange(
                    "/v1/support/cases",
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            http.exchange(
                    "/v1/support/cases",
                    org.springframework.http.HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(
                        Map.of("requestId", UUID.randomUUID(), "accountId", account), headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    headers.setBearerAuth(OIDC.token("writer", "support.write", false));
    assertThat(
            http.exchange(
                    "/v1/support/cases",
                    org.springframework.http.HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(
                        Map.of("requestId", UUID.randomUUID(), "accountId", account), headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    headers.setBearerAuth(OIDC.token("edge", "security.ingest", false));
    assertThat(
            http.exchange(
                    "/internal/v1/operations/signals",
                    org.springframework.http.HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(
                        Map.of(
                            "eventId",
                            UUID.randomUUID(),
                            "accountId",
                            account,
                            "kind",
                            "PAYMENT_FAILED",
                            "region",
                            "UNKNOWN",
                            "occurredAt",
                            NOW.toString()),
                        headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(400);
  }

  @Test
  void support_outboxFailure_rollsBackCaseAndReceipt() {
    db.execute(
        "ALTER TABLE outbox_event ADD CONSTRAINT fixture_audit_failure"
            + " CHECK(topic!='audit.recorded.v1') NOT VALID");
    try {
      assertThatThrownBy(() -> support())
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(db.queryForObject("SELECT count(*) FROM support_case", Integer.class)).isZero();
      assertThat(db.queryForObject("SELECT count(*) FROM support_request", Integer.class)).isZero();
      assertThat(db.queryForObject("SELECT count(*) FROM operations_audit", Integer.class))
          .isZero();
    } finally {
      db.execute("ALTER TABLE outbox_event DROP CONSTRAINT fixture_audit_failure");
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

  static class Owner implements OwnerControl {
    volatile int error, calls;
    final Map<UUID, Long> versions = new java.util.concurrent.ConcurrentHashMap<>();
    @Autowired JdbcTemplate db;

    public Map<String, Object> summary(UUID a) {
      return Map.of("id", a, "status", "ACTIVE");
    }

    public Result apply(UUID id, UUID a, String action, String reason) {
      calls++;
      try (var connection = db.getDataSource().getConnection();
          var query =
              connection.prepareStatement(
                  "SELECT account_id FROM operations_account WHERE account_id=? FOR UPDATE"
                      + " NOWAIT")) {
        query.setObject(1, a);
        query.executeQuery();
      } catch (java.sql.SQLException e) {
        throw new AssertionError("Owner called under transaction lock", e);
      }
      if (error == 409) throw new OperationsFailure(409, "OWNER_REJECTED");
      long version = versions.computeIfAbsent(id, k -> (long) versions.size() + 1);
      if (error != 0) throw new OperationsFailure(error, "OWNER_UNAVAILABLE");
      return new Result(version);
    }
  }

  @TestConfiguration
  static class Ports {
    @Bean
    @Primary
    Time time() {
      return new Time();
    }

    @Bean
    @Primary
    Owner owner() {
      return new Owner();
    }
  }
}
