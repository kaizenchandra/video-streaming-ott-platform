package com.ott.engagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.engagement.application.AdService;
import com.ott.engagement.application.EngagementFailure;
import com.ott.engagement.application.MessageDelivery;
import com.ott.engagement.application.NotificationAudience;
import com.ott.engagement.application.NotificationService;
import com.ott.engagement.application.NotificationWorker;
import java.time.Instant;
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
    classes = {EngagementApplication.class, NotificationsIT.Ports.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationsIT {
  @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6");
  static final OidcFixture OIDC = new OidcFixture();
  static final Instant NOW = AdsIT.NOW;
  static final String KEY = AdService.hash(UUID.randomUUID().toString());
  @Autowired NotificationService service;
  @Autowired NotificationWorker worker;
  @Autowired com.ott.engagement.application.EngagementRetention retention;
  @Autowired JdbcTemplate db;
  @Autowired Audience audience;
  @Autowired Provider provider;
  @Autowired AdsIT.Time clock;
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
    p.add("ott.notifications.enabled", () -> "true");
    p.add("ott.notifications.contact-key", () -> KEY);
    p.add("ott.notifications.delay-ms", () -> "3600000");
    p.add("spring.kafka.listener.auto-startup", () -> "false");
  }

  @BeforeEach
  void reset() {
    db.execute(
        "TRUNCATE notification_account,notification_source,live_notice_state,outbox_event CASCADE");
    clock.at = NOW;
    account = UUID.randomUUID();
    audience.account = account;
    audience.state = "SCHEDULED";
    audience.version = 1;
    audience.starts = NOW.plusSeconds(60);
    provider.calls = 0;
    provider.idempotent = true;
    provider.outcome = MessageDelivery.Outcome.ACCEPTED;
  }

  void queued() {
    for (var row :
        db.queryForList(
            "SELECT id,account_id,template FROM notification_intent WHERE state='QUEUED'")) {
      UUID id = (UUID) row.get("id");
      service.activate(
          UUID.randomUUID(),
          AdService.hash(id.toString()),
          (UUID) row.get("account_id"),
          id,
          row.get("template").toString(),
          1);
    }
  }

  UUID payment() {
    UUID order = UUID.randomUUID();
    service.payment(UUID.randomUUID(), AdService.hash(order.toString()), account, order, NOW);
    queued();
    return order;
  }

  void email() {
    service.verifiedContact(account, "EMAIL", "fixture@example.invalid");
    service.preference(account, "EMAIL", 1, true);
  }

  @Test
  void preferences_verifiedEncryptedContactVersionAndOptOut_enforced() {
    var prefs = service.preferences(account);
    assertThat(
            prefs.stream()
                .filter(NotificationService.Preference::enabled)
                .map(NotificationService.Preference::channel))
        .containsExactly("IN_APP");
    assertThatThrownBy(() -> service.preference(account, "EMAIL", 1, true))
        .isInstanceOf(EngagementFailure.class)
        .extracting("code")
        .isEqualTo("VERIFIED_CONTACT_REQUIRED");
    email();
    assertThat(db.queryForObject("SELECT ciphertext FROM notification_contact", String.class))
        .doesNotContain("fixture", "example.invalid");
    assertThat(service.recipient(account, "EMAIL")).isEqualTo("fixture@example.invalid");
    assertThatThrownBy(() -> service.preference(account, "EMAIL", 1, false))
        .isInstanceOf(EngagementFailure.class)
        .extracting("code")
        .isEqualTo("PREFERENCE_VERSION_CONFLICT");
    payment();
    service.preference(account, "EMAIL", 2, false);
    worker.batch();
    assertThat(provider.calls).isZero();
    assertThat(service.inbox(account, null, 20).items()).hasSize(1);
    service.removeContact(account, "EMAIL");
    assertThat(
            service.preferences(account).stream()
                .filter(p -> p.channel().equals("EMAIL"))
                .findFirst()
                .orElseThrow()
                .contactVerified())
        .isFalse();
  }

  @Test
  void payment_duplicateSemanticStaleAndUuidCollision_doNotResend() {
    UUID event = UUID.randomUUID(), order = UUID.randomUUID();
    String hash = AdService.hash(event.toString());
    service.payment(event, hash, account, order, NOW);
    service.payment(event, hash, account, order, NOW);
    service.payment(UUID.randomUUID(), AdService.hash("another"), account, order, NOW);
    service.payment(
        UUID.randomUUID(),
        AdService.hash("old"),
        account,
        UUID.randomUUID(),
        NOW.minusSeconds(31L * 86400));
    assertThatThrownBy(() -> service.payment(event, AdService.hash("changed"), account, order, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    queued();
    worker.batch();
    worker.batch();
    assertThat(service.inbox(account, null, 20).items()).hasSize(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE topic='notification.delivered.v1'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void contentReminder_idempotencyCancellationDueExpiryAndInboxOwnership() {
    UUID target = UUID.randomUUID();
    var reminder =
        service.remind(
            "viewer", target, "CONTENT", "IN_APP", NOW.plusSeconds(10), "content-request-01");
    assertThat(
            service
                .remind(
                    "viewer",
                    target,
                    "CONTENT",
                    "IN_APP",
                    NOW.plusSeconds(10),
                    "content-request-01")
                .id())
        .isEqualTo(reminder.id());
    assertThatThrownBy(
            () ->
                service.remind(
                    "viewer",
                    target,
                    "CONTENT",
                    "IN_APP",
                    NOW.plusSeconds(20),
                    "content-request-01"))
        .isInstanceOf(EngagementFailure.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    clock.at = NOW.plusSeconds(10);
    service.due();
    queued();
    worker.batch();
    var page = service.inbox(account, null, 1);
    assertThat(page.items()).hasSize(1);
    service.read(account, page.items().getFirst().id());
    assertThat(service.inbox(account, null, 20).items().getFirst().readAt()).isEqualTo(clock.at);
    assertThatThrownBy(() -> service.read(UUID.randomUUID(), page.items().getFirst().id()))
        .isInstanceOf(EngagementFailure.class)
        .extracting("code")
        .isEqualTo("NOTICE_NOT_FOUND");
    assertThat(service.inbox(UUID.randomUUID(), null, 20).items()).isEmpty();
    var cancelled =
        service.remind(
            "viewer",
            UUID.randomUUID(),
            "CONTENT",
            "IN_APP",
            clock.at.plusSeconds(1),
            "cancel-request-01");
    service.cancel(account, cancelled.id());
    clock.at = clock.at.plusSeconds(400);
    service.due();
    queued();
    worker.batch();
    assertThat(service.inbox(account, null, 20).items()).hasSize(1);
  }

  @Test
  void live_versionsRescheduleEndAndLateReplay_doNotReviveNotice() {
    UUID live = UUID.randomUUID();
    service.remind("viewer", live, "LIVE", "IN_APP", null, "live-reminder-01");
    service.live(
        UUID.randomUUID(),
        AdService.hash("moved"),
        live,
        2,
        "SCHEDULED",
        NOW.plusSeconds(120),
        NOW);
    assertThat(service.reminders(account, null, 20).getFirst().dueAt())
        .isEqualTo(NOW.plusSeconds(120));
    clock.at = NOW.plusSeconds(120);
    service.live(
        UUID.randomUUID(), AdService.hash("live"), live, 3, "LIVE", NOW.plusSeconds(120), clock.at);
    queued();
    service.live(
        UUID.randomUUID(),
        AdService.hash("ended"),
        live,
        4,
        "ENDED",
        NOW.plusSeconds(120),
        clock.at);
    service.live(
        UUID.randomUUID(),
        AdService.hash("replay"),
        live,
        3,
        "LIVE",
        NOW.plusSeconds(120),
        clock.at);
    worker.batch();
    assertThat(service.inbox(account, null, 20).items()).isEmpty();
    assertThat(db.queryForObject("SELECT state FROM live_notice_state", String.class))
        .isEqualTo("ENDED");
    assertThatThrownBy(
            () ->
                service.live(
                    UUID.randomUUID(),
                    AdService.hash("collision"),
                    live,
                    4,
                    "LIVE",
                    NOW.plusSeconds(120),
                    clock.at))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void worker_unknownOutcomeIdempotentRetryAndExhaustion_explicit() {
    email();
    payment();
    provider.outcome = MessageDelivery.Outcome.UNKNOWN;
    provider.idempotent = false;
    worker.batch();
    assertThat(
            db.queryForObject(
                "SELECT state FROM notification_intent WHERE channel='EMAIL'", String.class))
        .isEqualTo("RECONCILE");
    worker.batch();
    assertThat(provider.calls).isEqualTo(1);
    assertThat(
            service.deliveries(account, null, 20).stream().map(NotificationService.Delivery::state))
        .contains("RECONCILE");
    assertThat(service.deliveries(UUID.randomUUID(), null, 20)).isEmpty();
    reset();
    email();
    payment();
    provider.outcome = MessageDelivery.Outcome.UNKNOWN;
    for (int n = 0; n < 5; n++) {
      worker.batch();
      clock.at = clock.at.plusSeconds(400);
    }
    assertThat(provider.calls).isEqualTo(5);
    assertThat(
            db.queryForObject(
                "SELECT state FROM notification_intent WHERE channel='EMAIL'", String.class))
        .isEqualTo("FAILED");
  }

  @Test
  void worker_abandonedNonIdempotentDispatch_fencesNoBlindRetry() {
    email();
    payment();
    db.update(
        "UPDATE notification_intent SET"
            + " state='DISPATCHING',attempts=1,claim_token=?,claimed_until=? WHERE channel='EMAIL'",
        UUID.randomUUID(),
        java.sql.Timestamp.from(NOW.minusSeconds(1)));
    provider.idempotent = false;
    worker.batch();
    assertThat(provider.calls).isZero();
    assertThat(
            db.queryForObject(
                "SELECT state FROM notification_intent WHERE channel='EMAIL'", String.class))
        .isEqualTo("RECONCILE");
  }

  @Test
  void worker_inappOutboxFailure_rollsBackInboxAndCanRecover() {
    payment();
    db.execute(
        "ALTER TABLE outbox_event ADD CONSTRAINT fixture_notice_failure"
            + " CHECK(topic!='notification.delivered.v1') NOT VALID");
    try {
      assertThatThrownBy(() -> worker.batch())
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(service.inbox(account, null, 20).items()).isEmpty();
    } finally {
      db.execute("ALTER TABLE outbox_event DROP CONSTRAINT fixture_notice_failure");
    }
    clock.at = NOW.plusSeconds(11);
    worker.batch();
    assertThat(service.inbox(account, null, 20).items()).hasSize(1);
  }

  @Test
  void worker_badContactCipher_failsOnlyThatRoute() {
    email();
    payment();
    db.update(
        "UPDATE notification_contact SET ciphertext='invalid-route' WHERE account_id=?", account);
    worker.batch();
    assertThat(provider.calls).isZero();
    assertThat(service.inbox(account, null, 20).items()).hasSize(1);
    assertThat(
            db.queryForObject(
                "SELECT state FROM notification_intent WHERE channel='EMAIL'", String.class))
        .isEqualTo("FAILED");
  }

  @Test
  void activate_foreignIntentAccountOrTemplateAndDuplicate_doNotInventDelivery() {
    service.payment(UUID.randomUUID(), AdService.hash("request"), account, UUID.randomUUID(), NOW);
    UUID id = db.queryForObject("SELECT id FROM notification_intent", UUID.class);
    assertThatThrownBy(
            () ->
                service.activate(
                    UUID.randomUUID(),
                    AdService.hash("foreign"),
                    UUID.randomUUID(),
                    id,
                    "PAYMENT_FAILED",
                    1))
        .isInstanceOf(IllegalArgumentException.class);
    UUID event = UUID.randomUUID();
    service.activate(event, AdService.hash("activation"), account, id, "PAYMENT_FAILED", 1);
    service.activate(event, AdService.hash("activation"), account, id, "PAYMENT_FAILED", 1);
    worker.batch();
    assertThat(service.inbox(account, null, 20).items()).hasSize(1);
  }

  @Test
  void live_moreThanOneFanoutBatch_drainsFreshFollowers() {
    UUID target = UUID.randomUUID();
    UUID first = account;
    for (int n = 0; n < 105; n++) {
      audience.account = UUID.randomUUID();
      service.remind("viewer", target, "LIVE", "IN_APP", null, "follower-" + UUID.randomUUID());
    }
    clock.at = NOW.plusSeconds(60);
    service.live(
        UUID.randomUUID(), AdService.hash("crowd"), target, 2, "LIVE", audience.starts, clock.at);
    assertThat(db.queryForObject("SELECT count(*) FROM notification_intent", Integer.class))
        .isEqualTo(100);
    service.due();
    assertThat(db.queryForObject("SELECT count(*) FROM notification_intent", Integer.class))
        .isEqualTo(105);
  }

  @Test
  void api_contactVerificationUsesSignedClaimsNotClientBoolean() {
    var headers = new org.springframework.http.HttpHeaders();
    headers.setBearerAuth(OIDC.token("viewer", "", false));
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    assertThat(
            http.exchange(
                    "/v1/notification-contacts/EMAIL",
                    org.springframework.http.HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(
                        java.util.Map.of("verified", true, "email", "attacker@example.invalid"),
                        headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    headers.setBearerAuth(
        OIDC.token(
            "viewer",
            "",
            false,
            java.util.Map.of("email", "verified@example.invalid", "email_verified", true)));
    assertThat(
            http.exchange(
                    "/v1/notification-contacts/EMAIL",
                    org.springframework.http.HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(service.recipient(account, "EMAIL")).isEqualTo("verified@example.invalid");
    assertThat(db.queryForObject("SELECT ciphertext FROM notification_contact", String.class))
        .doesNotContain("verified@example.invalid");
  }

  @Test
  void retention_completedNoticePurged_unknownDispatchRetained() {
    email();
    payment();
    provider.outcome = MessageDelivery.Outcome.UNKNOWN;
    provider.idempotent = false;
    worker.batch();
    assertThat(service.inbox(account, null, 20).items()).hasSize(1);
    clock.at = NOW.plusSeconds(31L * 86400);
    retention.sweep();
    assertThat(service.inbox(account, null, 20).items()).isEmpty();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM notification_intent WHERE state='RECONCILE'", Integer.class))
        .isEqualTo(1);
    assertThat(db.queryForObject("SELECT count(*) FROM notification_contact", Integer.class))
        .isEqualTo(1);
    worker.batch();
    assertThat(provider.calls).isEqualTo(1);
  }

  static class Audience implements NotificationAudience {
    volatile UUID account;
    volatile String state = "SCHEDULED";
    volatile long version = 1;
    volatile Instant starts = NOW.plusSeconds(60);

    public UUID account(String token) {
      return account;
    }

    public Target target(String token, String kind, UUID id) {
      return new Target(state, version, starts);
    }
  }

  static class Provider implements MessageDelivery {
    volatile int calls;
    volatile boolean idempotent = true;
    volatile Outcome outcome = Outcome.ACCEPTED;

    public boolean idempotent() {
      return idempotent;
    }

    public Result send(UUID id, String c, String ref, String template, UUID target) {
      calls++;
      return new Result(outcome, "fixture-" + id);
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
    Provider provider() {
      return new Provider();
    }

    @Bean
    @Primary
    AdsIT.Time time() {
      return new AdsIT.Time();
    }
  }
}
