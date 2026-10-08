package com.ott.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.commerce.adapter.inbound.rest.WebhookVerifier;
import com.ott.commerce.application.CommerceService;
import com.ott.commerce.application.PaymentProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
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
    classes = {CommerceApplication.class, CommerceIT.ProviderConfiguration.class})
class CommerceIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

  static final OidcFixture OIDC = new OidcFixture();
  static final String SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();
  @Autowired CommerceService commerce;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired TestRestTemplate http;
  @Autowired WebhookVerifier verifier;
  @Autowired TestProvider provider;

  @AfterAll
  static void close() {
    OIDC.close();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
    p.add("ott.security.issuer", OIDC::issuer);
    p.add("ott.security.jwks-uri", OIDC::jwks);
    p.add("ott.payment.webhook-secret", () -> SECRET);
    p.add("ott.outbox.enabled", () -> "false");
  }

  CommerceService.Plan plan() {
    return commerce.createPlan("Premium", 999, "INR", 30, 2, false, true);
  }

  byte[] event(CommerceService.Order order, String id, String outcome, long amount, String currency)
      throws Exception {
    return json.writeValueAsBytes(
        new CommerceService.Webhook(
            id, order.id(), order.providerRef(), amount, currency, outcome));
  }

  void paid(CommerceService.Order order, String id) throws Exception {
    commerce.applyVerifiedWebhook(
        event(order, id, "SUCCEEDED", order.priceMinor(), order.currency()));
  }

  @Test
  void purchase_duplicateAndConflictingKey_doesNotChargeTwice() {
    var account = UUID.randomUUID();
    var plan = plan();
    String key = "purchase-" + UUID.randomUUID();
    var one = commerce.purchase(account, plan.id(), key);
    var two = commerce.purchase(account, plan.id(), key);
    assertThat(two.id()).isEqualTo(one.id());
    assertThat(provider.attempts.get(one.id()).get()).isEqualTo(1);
    assertThatThrownBy(() -> commerce.purchase(account, plan().id(), key))
        .isInstanceOf(CommerceService.Failure.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(commerce.entitlement(account).active()).isFalse();
  }

  @Test
  void purchase_parallelSameKey_persistsOneOrderAndProviderAttempt() throws Exception {
    var account = UUID.randomUUID();
    var plan = plan();
    var key = "purchase-" + UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<UUID>>();
      for (int i = 0; i < 8; i++) tasks.add(() -> commerce.purchase(account, plan.id(), key).id());
      var ids = new ArrayList<UUID>();
      for (var f : pool.invokeAll(tasks, 10, TimeUnit.SECONDS)) ids.add(f.get());
      assertThat(ids.stream().distinct().count()).isEqualTo(1);
      assertThat(provider.attempts.get(ids.getFirst()).get()).isEqualTo(1);
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM purchase_order WHERE account_id=?", Integer.class, account))
        .isEqualTo(1);
  }

  @Test
  void webhook_successAndReplay_activatesWithReceiptAndTwoEventsOnce() throws Exception {
    var account = UUID.randomUUID();
    var order = commerce.purchase(account, plan().id(), "purchase-" + UUID.randomUUID());
    var id = "event-" + UUID.randomUUID();
    paid(order, id);
    var entitlement = commerce.entitlement(account);
    assertThat(entitlement.active()).isTrue();
    assertThat(entitlement.maxStreams()).isEqualTo(2);
    assertThat(entitlement.offlineAllowed()).isTrue();
    paid(order, id);
    assertThat(commerce.entitlement(account)).isEqualTo(entitlement);
    assertThat(commerce.invoice(account, order.id()).amountMinor()).isEqualTo(999);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=?", Integer.class, account))
        .isEqualTo(2);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM provider_inbox WHERE event_id=?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void webhook_outboxDatabaseFailure_rollsBackPaymentSubscriptionReceiptInboxAndAudit()
      throws Exception {
    var account = UUID.randomUUID();
    var order = commerce.purchase(account, plan().id(), "purchase-" + UUID.randomUUID());
    var id = "event-" + UUID.randomUUID();
    db.execute(
        "CREATE FUNCTION reject_test_outbox() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN IF"
            + " NEW.aggregate_id='"
            + account
            + "'::uuid THEN RAISE EXCEPTION 'injected outbox fault'; END IF; RETURN NEW; END $$");
    db.execute(
        "CREATE TRIGGER reject_test_outbox BEFORE INSERT ON outbox_event FOR EACH ROW EXECUTE"
            + " FUNCTION reject_test_outbox()");
    try {
      assertThatThrownBy(() -> paid(order, id))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(commerce.get(account, order.id()).state()).isEqualTo("PENDING");
      assertThat(commerce.entitlement(account).active()).isFalse();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM provider_inbox WHERE event_id=?", Integer.class, id))
          .isZero();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM invoice WHERE order_id=?", Integer.class, order.id()))
          .isZero();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM payment_audit WHERE order_id=?", Integer.class, order.id()))
          .isZero();
    } finally {
      db.execute("DROP TRIGGER reject_test_outbox ON outbox_event");
      db.execute("DROP FUNCTION reject_test_outbox()");
    }
    paid(order, id);
    assertThat(commerce.entitlement(account).active()).isTrue();
  }

  @Test
  void webhook_amountCurrencyMismatch_rollsBackInboxAndEntitlement() throws Exception {
    var account = UUID.randomUUID();
    var order = commerce.purchase(account, plan().id(), "purchase-" + UUID.randomUUID());
    var id = "event-" + UUID.randomUUID();
    byte[] amount = event(order, id, "SUCCEEDED", 1, order.currency());
    assertThatThrownBy(() -> commerce.applyVerifiedWebhook(amount))
        .isInstanceOf(CommerceService.Failure.class)
        .extracting("code")
        .isEqualTo("PAYMENT_MISMATCH");
    byte[] currency = event(order, id, "SUCCEEDED", order.priceMinor(), "USD");
    assertThatThrownBy(() -> commerce.applyVerifiedWebhook(currency))
        .isInstanceOf(CommerceService.Failure.class);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM provider_inbox WHERE event_id=?", Integer.class, id))
        .isZero();
    assertThat(commerce.entitlement(account).active()).isFalse();
  }

  @Test
  void webhook_declineThenLateSuccess_cannotResurrectWithoutReconciliation() throws Exception {
    var account = UUID.randomUUID();
    var order = commerce.purchase(account, plan().id(), "purchase-" + UUID.randomUUID());
    commerce.applyVerifiedWebhook(
        event(order, "event-" + UUID.randomUUID(), "FAILED", order.priceMinor(), order.currency()));
    assertThat(commerce.get(account, order.id()).state()).isEqualTo("FAILED");
    assertThatThrownBy(() -> paid(order, "event-" + UUID.randomUUID()))
        .isInstanceOf(CommerceService.Failure.class)
        .extracting("code")
        .isEqualTo("PAYMENT_STATE_CONFLICT");
    assertThat(commerce.entitlement(account).active()).isFalse();
    assertThat(
            db.queryForObject(
                "SELECT topic FROM outbox_event WHERE aggregate_id=?", String.class, account))
        .isEqualTo("payment.failed.v1");
  }

  @Test
  void webhook_fractionalMinorUnits_rejectsInsteadOfSilentlyTruncatingMoney() {
    byte[] fractional =
        "{\"eventId\":\"event-fractional\",\"orderId\":\"00000000-0000-0000-0000-000000000001\",\"providerRef\":\"mock\",\"amountMinor\":999.5,\"currency\":\"INR\",\"outcome\":\"SUCCEEDED\"}"
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> commerce.applyVerifiedWebhook(fractional))
        .isInstanceOf(CommerceService.Failure.class)
        .extracting("code")
        .isEqualTo("INVALID_REQUEST");
  }

  @Test
  void entitlement_expiredPaidPeriod_deniesAccess() throws Exception {
    var account = UUID.randomUUID();
    var order = commerce.purchase(account, plan().id(), "purchase-" + UUID.randomUUID());
    paid(order, "event-" + UUID.randomUUID());
    db.update(
        "UPDATE subscription SET expires_at=clock_timestamp()-interval '1 second' WHERE"
            + " account_id=?",
        account);
    assertThat(commerce.entitlement(account).active()).isFalse();
  }

  @Test
  void webhook_sameEventDifferentBody_rejectsCollision() throws Exception {
    var account = UUID.randomUUID();
    var order = commerce.purchase(account, plan().id(), "purchase-" + UUID.randomUUID());
    var id = "event-" + UUID.randomUUID();
    paid(order, id);
    byte[] changed = event(order, id, "FAILED", order.priceMinor(), order.currency());
    assertThatThrownBy(() -> commerce.applyVerifiedWebhook(changed))
        .isInstanceOf(CommerceService.Failure.class)
        .extracting("code")
        .isEqualTo("PROVIDER_EVENT_COLLISION");
  }

  @Test
  void purchase_uncertainProvider_remainsPendingAndSameKeyNeverRetriesCharge() {
    var plan = commerce.createPlan("Timeout simulation", 1001, "INR", 30, 1, true, false);
    var account = UUID.randomUUID();
    var key = "purchase-" + UUID.randomUUID();
    var order = commerce.purchase(account, plan.id(), key);
    assertThat(order.state()).isEqualTo("PENDING_RECONCILIATION");
    assertThat(commerce.purchase(account, plan.id(), key).id()).isEqualTo(order.id());
    assertThat(provider.attempts.get(order.id()).get()).isEqualTo(1);
    assertThat(commerce.entitlement(account).active()).isFalse();
    assertThatThrownBy(() -> commerce.purchase(account, plan.id(), "purchase-" + UUID.randomUUID()))
        .isInstanceOf(CommerceService.Failure.class)
        .extracting("code")
        .isEqualTo("PURCHASE_PENDING");
  }

  @Test
  void reconcile_timeout_resolvesReferenceWithoutAnotherChargeAndAwaitingVerifiedOutcome()
      throws Exception {
    var plan = commerce.createPlan("Lost response", 1001, "INR", 30, 1, true, false);
    var account = UUID.randomUUID();
    var uncertain = commerce.purchase(account, plan.id(), "purchase-" + UUID.randomUUID());
    var resolved = commerce.reconcile(account, uncertain.id());
    assertThat(resolved.state()).isEqualTo("PENDING");
    assertThat(provider.attempts.get(resolved.id()).get()).isEqualTo(1);
    assertThat(commerce.entitlement(account).active()).isFalse();
    paid(resolved, "event-" + UUID.randomUUID());
    assertThat(commerce.entitlement(account).active()).isTrue();
  }

  @Test
  void subscription_samePlanRenewal_extendsPeriodAndRejectsUnspecifiedProration() throws Exception {
    var account = UUID.randomUUID();
    var plan = plan();
    var first = commerce.purchase(account, plan.id(), "purchase-" + UUID.randomUUID());
    paid(first, "event-" + UUID.randomUUID());
    var until = commerce.entitlement(account).expiresAt();
    assertThatThrownBy(
            () -> commerce.purchase(account, plan().id(), "purchase-" + UUID.randomUUID()))
        .isInstanceOf(CommerceService.Failure.class)
        .extracting("code")
        .isEqualTo("PLAN_CHANGE_REQUIRES_REVIEW");
    var second = commerce.purchase(account, plan.id(), "purchase-" + UUID.randomUUID());
    paid(second, "event-" + UUID.randomUUID());
    assertThat(commerce.entitlement(account).expiresAt()).isEqualTo(until.plusSeconds(30 * 86400));
    assertThatThrownBy(() -> commerce.invoice(UUID.randomUUID(), first.id()))
        .isInstanceOf(CommerceService.Failure.class)
        .extracting("status")
        .isEqualTo(404);
  }

  @Test
  void webhook_signatureTimestampAndRawBytes_enforceAuthentication() throws Exception {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String sig = signature(body, timestamp);
    verifier.verify(body, timestamp, sig);
    assertThatThrownBy(
            () -> verifier.verify(" { }".getBytes(StandardCharsets.UTF_8), timestamp, sig))
        .isInstanceOf(CommerceService.Failure.class);
    String stale = Long.toString(Instant.now().minusSeconds(600).getEpochSecond());
    assertThatThrownBy(() -> verifier.verify(body, stale, signature(body, stale)))
        .isInstanceOf(CommerceService.Failure.class);
    assertThatThrownBy(() -> verifier.verify(body, timestamp, "0".repeat(64)))
        .isInstanceOf(CommerceService.Failure.class);
  }

  @Test
  void api_webhookDoesNotNeedViewerJwt_butRequiresValidSignature() throws Exception {
    var account = UUID.randomUUID();
    var order = commerce.purchase(account, plan().id(), "purchase-" + UUID.randomUUID());
    byte[] body = event(order, "event-" + UUID.randomUUID(), "SUCCEEDED", 999, "INR");
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    var headers = new HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    headers.set("X-Webhook-Timestamp", timestamp);
    headers.set("X-Webhook-Signature", "0".repeat(64));
    assertThat(
            http.exchange(
                    "/v1/webhooks/payment",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(401);
    headers.set("X-Webhook-Signature", signature(body, timestamp));
    assertThat(
            http.exchange(
                    "/v1/webhooks/payment",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(204);
    assertThat(commerce.entitlement(account).active()).isTrue();
    headers = new HttpHeaders();
    headers.setBearerAuth(OIDC.token("viewer", "", true));
    assertThat(
            http.exchange(
                    "/internal/v1/entitlements/" + account,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    headers.setBearerAuth(OIDC.token("worker", "commerce.internal", true));
    assertThat(
            http.exchange(
                    "/internal/v1/entitlements/" + account,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
  }

  static String signature(byte[] body, String timestamp) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(mac.doFinal(body));
  }

  @Test
  void revoke_pendingSettlementDoesNotReactivate_andExplicitRestoreAudited() throws Exception {
    UUID account = UUID.randomUUID();
    var plan = plan();
    var order = commerce.purchase(account, plan.id(), "security-purchase-" + UUID.randomUUID());
    UUID id = UUID.randomUUID();
    var revoked = commerce.security(id, account, "REVOKE", "PAYMENT_RISK", "ops-client");
    paid(order, "security-paid-" + UUID.randomUUID());
    assertThat(commerce.entitlement(account).active()).isFalse();
    assertThat(commerce.invoice(account, order.id()).amountMinor()).isEqualTo(plan.priceMinor());
    assertThat(commerce.security(id, account, "REVOKE", "PAYMENT_RISK", "ops-client"))
        .isEqualTo(revoked);
    assertThatThrownBy(
            () -> commerce.security(id, account, "RESTORE", "PAYMENT_RISK", "ops-client"))
        .isInstanceOf(CommerceService.Failure.class)
        .extracting("code")
        .isEqualTo("OPERATION_CONFLICT");
    assertThatThrownBy(
            () -> commerce.purchase(account, plan.id(), "denied-purchase-" + UUID.randomUUID()))
        .isInstanceOf(CommerceService.Failure.class)
        .extracting("code")
        .isEqualTo("ENTITLEMENT_REVOKED");
    assertThat(
            commerce
                .security(UUID.randomUUID(), account, "RESTORE", "PAYMENT_RISK", "ops-client")
                .active())
        .isTrue();
    assertThat(
            db.queryForObject(
                "SELECT payload->>'changeReason' FROM outbox_event WHERE aggregate_id=? AND"
                    + " topic='subscription.changed.v1' ORDER BY aggregate_version DESC LIMIT 1",
                String.class,
                account))
        .isEqualTo("SECURITY_RESTORED");
  }

  @Test
  void cancel_noRefundAndFutureRenewalRequiresNewPaidOrder() throws Exception {
    UUID account = UUID.randomUUID();
    var plan = plan();
    var order = commerce.purchase(account, plan.id(), "cancel-purchase-" + UUID.randomUUID());
    assertThatThrownBy(() -> commerce.cancel(account, "viewer"))
        .isInstanceOf(CommerceService.Failure.class)
        .extracting("code")
        .isEqualTo("PURCHASE_PENDING");
    paid(order, "cancel-paid-" + UUID.randomUUID());
    var cancelled = commerce.cancel(account, "viewer");
    assertThat(cancelled.active()).isFalse();
    assertThat(commerce.cancel(account, "viewer").version()).isEqualTo(cancelled.version());
    var renewed = commerce.purchase(account, plan.id(), "new-purchase-" + UUID.randomUUID());
    assertThat(commerce.entitlement(account).active()).isFalse();
    paid(renewed, "new-paid-" + UUID.randomUUID());
    assertThat(commerce.entitlement(account).active()).isTrue();
    assertThat(commerce.invoice(account, order.id()).amountMinor()).isEqualTo(plan.priceMinor());
  }

  static class TestProvider implements PaymentProvider {
    final java.util.concurrent.ConcurrentMap<UUID, Attempt> known =
        new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ConcurrentMap<UUID, java.util.concurrent.atomic.AtomicInteger>
        attempts = new java.util.concurrent.ConcurrentHashMap<>();

    public Attempt create(UUID order, long amount, String currency) {
      attempts
          .computeIfAbsent(order, id -> new java.util.concurrent.atomic.AtomicInteger())
          .incrementAndGet();
      var pending = new Attempt("mock-" + order, "PENDING", amount, currency);
      known.put(order, pending);
      if (amount == 1001) throw new UncertainOutcome();
      return pending;
    }

    public Attempt lookup(UUID order) {
      return known.get(order);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProviderConfiguration {
    @Bean
    @Primary
    TestProvider provider() {
      return new TestProvider();
    }
  }
}
