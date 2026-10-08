package com.ott.commerce.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CommerceService {
  private final JdbcTemplate db;
  private final Clock clock;
  private final PaymentProvider provider;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  public CommerceService(
      JdbcTemplate db,
      Clock clock,
      PaymentProvider provider,
      PlatformTransactionManager manager,
      ObjectMapper json) {
    this.db = db;
    this.clock = clock;
    this.provider = provider;
    this.tx = new TransactionTemplate(manager);
    this.json = json;
  }

  public record Plan(
      UUID id,
      String name,
      long priceMinor,
      String currency,
      int durationDays,
      int maxStreams,
      boolean adSupported,
      boolean offlineAllowed) {}

  public record Order(
      UUID id,
      UUID accountId,
      UUID planId,
      long priceMinor,
      String currency,
      String state,
      String providerRef) {}

  public record Entitlement(
      boolean active,
      Instant expiresAt,
      int maxStreams,
      boolean adSupported,
      boolean offlineAllowed,
      long version) {}

  public record Webhook(
      String eventId,
      UUID orderId,
      String providerRef,
      long amountMinor,
      String currency,
      String outcome) {}

  @Transactional
  public Plan createPlan(
      String name,
      long price,
      String currency,
      int days,
      int streams,
      boolean ads,
      boolean offline) {
    if (name == null
        || name.isBlank()
        || name.length() > 80
        || price < 1
        || price > 1000000000
        || days < 1
        || days > 3650
        || streams < 1
        || streams > 10) throw invalid();
    try {
      if (!Currency.getInstance(currency).getCurrencyCode().equals(currency)) throw invalid();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw invalid();
    }
    var id = UUID.randomUUID();
    db.update(
        "INSERT INTO plan VALUES (?,?,?,?,?,?,?,?)",
        id,
        name.strip(),
        price,
        currency,
        days,
        streams,
        ads,
        offline);
    return new Plan(id, name.strip(), price, currency, days, streams, ads, offline);
  }

  @Transactional(readOnly = true)
  public List<Plan> plans() {
    return db.query(
        "SELECT * FROM plan ORDER BY id LIMIT 100",
        (r, n) ->
            new Plan(
                r.getObject("id", UUID.class),
                r.getString("name"),
                r.getLong("price_minor"),
                r.getString("currency"),
                r.getInt("duration_days"),
                r.getInt("max_streams"),
                r.getBoolean("ad_supported"),
                r.getBoolean("offline_allowed")));
  }

  public Order purchase(UUID account, UUID plan, String key) {
    if (account == null || plan == null || key == null || !key.matches("[A-Za-z0-9_-]{8,128}"))
      throw invalid();
    String fingerprint = hash(plan.toString().getBytes(StandardCharsets.UTF_8));
    var reserved =
        tx.execute(
            status -> {
              db.update(
                  "INSERT INTO commerce_account(account_id) VALUES (?) ON CONFLICT DO NOTHING",
                  account);
              db.queryForObject(
                  "SELECT account_id FROM commerce_account WHERE account_id=? FOR UPDATE",
                  UUID.class,
                  account);
              if (db.queryForObject(
                  "SELECT access_revoked FROM commerce_account WHERE account_id=?",
                  Boolean.class,
                  account)) throw new Failure(403, "ENTITLEMENT_REVOKED", "Access revoked");
              var existing =
                  db.query(
                      "SELECT id,fingerprint FROM purchase_order WHERE account_id=? AND"
                          + " idempotency_key=?",
                      (r, n) -> List.of(r.getObject("id", UUID.class), r.getString("fingerprint")),
                      account,
                      key);
              if (!existing.isEmpty()) {
                if (!fingerprint.equals(existing.getFirst().get(1)))
                  throw new Failure(
                      409, "IDEMPOTENCY_CONFLICT", "Key already used for another purchase");
                return new Reserved((UUID) existing.getFirst().get(0), false);
              }
              if (db.queryForObject(
                      "SELECT count(*) FROM purchase_order WHERE account_id=? AND state IN"
                          + " ('CREATED','PENDING','PENDING_RECONCILIATION')",
                      Integer.class,
                      account)
                  > 0)
                throw new Failure(
                    409, "PURCHASE_PENDING", "An earlier purchase requires resolution");
              var current =
                  db.queryForList(
                      "SELECT plan_id FROM subscription WHERE account_id=? AND state='ACTIVE' AND"
                          + " expires_at>?",
                      UUID.class,
                      account,
                      java.sql.Timestamp.from(clock.instant()));
              if (!current.isEmpty() && !current.getFirst().equals(plan))
                throw new Failure(
                    409,
                    "PLAN_CHANGE_REQUIRES_REVIEW",
                    "Mid-period plan changes require billing policy review");
              var id = UUID.randomUUID();
              int inserted =
                  db.update(
                      """
                      INSERT INTO purchase_order(id,account_id,plan_id,price_minor,currency,duration_days,max_streams,ad_supported,offline_allowed,idempotency_key,fingerprint,state)
                      SELECT ?,?,id,price_minor,currency,duration_days,max_streams,ad_supported,offline_allowed,?,?,'CREATED' FROM plan WHERE id=?
                      """,
                      id,
                      account,
                      key,
                      fingerprint,
                      plan);
              if (inserted != 1) throw new Failure(404, "PLAN_NOT_FOUND", "Plan not found");
              return new Reserved(id, true);
            });
    var order = get(account, reserved.id());
    if (!reserved.created()) return order;
    try {
      var attempt = provider.create(order.id(), order.priceMinor(), order.currency());
      if (attempt == null
          || attempt.reference() == null
          || attempt.reference().isBlank()
          || attempt.reference().length() > 200
          || !"PENDING".equals(attempt.state())
          || attempt.amountMinor() != order.priceMinor()
          || !order.currency().equals(attempt.currency()))
        throw new PaymentProvider.UncertainOutcome();
      tx.executeWithoutResult(
          status ->
              db.update(
                  "UPDATE purchase_order SET provider_ref=?,state='PENDING' WHERE id=? AND"
                      + " state='CREATED'",
                  attempt.reference(),
                  order.id()));
    } catch (PaymentProvider.UncertainOutcome e) {
      tx.executeWithoutResult(
          status ->
              db.update(
                  "UPDATE purchase_order SET state='PENDING_RECONCILIATION' WHERE id=? AND"
                      + " state='CREATED'",
                  order.id()));
    }
    return get(account, order.id());
  }

  public Order reconcile(UUID account, UUID orderId) {
    var order = get(account, orderId);
    if (!List.of("CREATED", "PENDING_RECONCILIATION").contains(order.state())) return order;
    PaymentProvider.Attempt found;
    try {
      found = provider.lookup(orderId);
    } catch (PaymentProvider.UncertainOutcome e) {
      return order;
    }
    if (found == null
        || found.reference() == null
        || found.reference().isBlank()
        || found.reference().length() > 200
        || !"PENDING".equals(found.state())
        || found.amountMinor() != order.priceMinor()
        || !order.currency().equals(found.currency()))
      throw new Failure(409, "RECONCILIATION_MISMATCH", "Provider lookup does not match order");
    tx.executeWithoutResult(
        status -> {
          db.queryForObject(
              "SELECT account_id FROM commerce_account WHERE account_id=? FOR UPDATE",
              UUID.class,
              account);
          int updated =
              db.update(
                  "UPDATE purchase_order SET provider_ref=?,state='PENDING' WHERE id=? AND state IN"
                      + " ('CREATED','PENDING_RECONCILIATION')",
                  found.reference(),
                  orderId);
          if (updated == 1)
            db.update(
                "INSERT INTO payment_audit(id,order_id,action) VALUES (?,?,'RECONCILED_REFERENCE')",
                UUID.randomUUID(),
                orderId);
        });
    return get(account, orderId);
  }

  private record Reserved(UUID id, boolean created) {}

  @Transactional(readOnly = true)
  public Order get(UUID account, UUID id) {
    return db
        .query(
            "SELECT * FROM purchase_order WHERE id=? AND account_id=?",
            (r, n) ->
                new Order(
                    r.getObject("id", UUID.class),
                    r.getObject("account_id", UUID.class),
                    r.getObject("plan_id", UUID.class),
                    r.getLong("price_minor"),
                    r.getString("currency"),
                    r.getString("state"),
                    r.getString("provider_ref")),
            id,
            account)
        .stream()
        .findFirst()
        .orElseThrow(() -> new Failure(404, "ORDER_NOT_FOUND", "Order not found"));
  }

  public record Invoice(UUID orderId, long amountMinor, String currency, Instant paidAt) {}

  @Transactional(readOnly = true)
  public Invoice invoice(UUID account, UUID orderId) {
    get(account, orderId);
    return db
        .query(
            "SELECT * FROM invoice WHERE order_id=?",
            (r, n) ->
                new Invoice(
                    orderId,
                    r.getLong("amount_minor"),
                    r.getString("currency"),
                    r.getTimestamp("paid_at").toInstant()),
            orderId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new Failure(404, "INVOICE_NOT_FOUND", "Receipt not available"));
  }

  @Transactional(readOnly = true)
  public Entitlement entitlement(UUID account) {
    return db
        .query(
            "SELECT s.*,c.access_revoked FROM subscription s JOIN commerce_account c"
                + " USING(account_id) WHERE account_id=?",
            (r, n) -> {
              var expiry = r.getTimestamp("expires_at").toInstant();
              return new Entitlement(
                  "ACTIVE".equals(r.getString("state"))
                      && !r.getBoolean("access_revoked")
                      && clock.instant().isBefore(expiry),
                  expiry,
                  r.getInt("max_streams"),
                  r.getBoolean("ad_supported"),
                  r.getBoolean("offline_allowed"),
                  r.getLong("version"));
            },
            account)
        .stream()
        .findFirst()
        .orElse(new Entitlement(false, null, 0, false, false, 0));
  }

  @Transactional
  public void applyVerifiedWebhook(byte[] body) {
    Webhook event;
    try {
      event = json.readValue(body, Webhook.class);
    } catch (JsonProcessingException e) {
      throw invalid();
    } catch (java.io.IOException e) {
      throw invalid();
    }
    if (event.eventId() == null
        || !event.eventId().matches("[A-Za-z0-9_-]{8,128}")
        || event.orderId() == null
        || event.providerRef() == null
        || event.currency() == null
        || !List.of("SUCCEEDED", "FAILED").contains(event.outcome())) throw invalid();
    String hash = hash(body);
    var orderAccounts =
        db.queryForList(
            "SELECT account_id FROM purchase_order WHERE id=?", UUID.class, event.orderId());
    if (orderAccounts.isEmpty()) throw new Failure(404, "ORDER_NOT_FOUND", "Order not found");
    var account = orderAccounts.getFirst();
    db.queryForObject(
        "SELECT account_id FROM commerce_account WHERE account_id=? FOR UPDATE",
        UUID.class,
        account);
    var old =
        db.queryForList(
            "SELECT body_hash FROM provider_inbox WHERE event_id=?", String.class, event.eventId());
    if (!old.isEmpty()) {
      if (!old.getFirst().equals(hash))
        throw new Failure(409, "PROVIDER_EVENT_COLLISION", "Provider event identity conflict");
      return;
    }
    var order = get(account, event.orderId());
    if (order.providerRef() == null)
      throw new Failure(
          503, "PAYMENT_RECONCILIATION_REQUIRED", "Provider reference not yet committed");
    if (!order.providerRef().equals(event.providerRef())
        || order.priceMinor() != event.amountMinor()
        || !order.currency().equals(event.currency()))
      throw new Failure(400, "PAYMENT_MISMATCH", "Payment details do not match order");
    if (!order.state().equals("PENDING"))
      throw new Failure(
          409, "PAYMENT_STATE_CONFLICT", "Payment transition requires reconciliation");
    db.update("INSERT INTO provider_inbox(event_id,body_hash) VALUES (?,?)", event.eventId(), hash);
    db.update(
        "UPDATE purchase_order SET state=? WHERE id=?",
        event.outcome().equals("SUCCEEDED") ? "PAID" : "FAILED",
        order.id());
    long version =
        db.queryForObject(
            "UPDATE commerce_account SET version=version+1 WHERE account_id=? RETURNING version",
            Long.class,
            account);
    db.update(
        "INSERT INTO payment_audit(id,order_id,action) VALUES (?,?,?)",
        UUID.randomUUID(),
        order.id(),
        event.outcome());
    if (event.outcome().equals("FAILED")) {
      emit(
          account,
          version,
          "payment.failed.v1",
          java.util.Map.of(
              "accountId",
              account.toString(),
              "orderId",
              order.id().toString(),
              "reasonCode",
              "PROVIDER_DECLINED"));
      return;
    }
    var current = entitlement(account);
    var start = current.active() ? current.expiresAt() : clock.instant();
    int days =
        db.queryForObject(
            "SELECT duration_days FROM purchase_order WHERE id=?", Integer.class, order.id());
    var expiry =
        start.plus(Duration.ofDays(days)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    db.update(
        """
        INSERT INTO subscription(account_id,plan_id,expires_at,max_streams,ad_supported,offline_allowed,version)
        SELECT account_id,plan_id,?,max_streams,ad_supported,offline_allowed,? FROM purchase_order WHERE id=?
        ON CONFLICT(account_id) DO UPDATE SET plan_id=excluded.plan_id,expires_at=excluded.expires_at,max_streams=excluded.max_streams,ad_supported=excluded.ad_supported,offline_allowed=excluded.offline_allowed,version=excluded.version,state='ACTIVE'
        """,
        java.sql.Timestamp.from(expiry),
        version,
        order.id());
    db.update(
        "INSERT INTO invoice VALUES (?,?,?,?)",
        order.id(),
        order.priceMinor(),
        order.currency(),
        java.sql.Timestamp.from(clock.instant()));
    var granted = entitlement(account);
    emit(
        account,
        version,
        "entitlement.changed.v1",
        java.util.Map.of(
            "accountId",
            account.toString(),
            "active",
            granted.active(),
            "expiresAt",
            expiry.toEpochMilli(),
            "maxStreams",
            granted.maxStreams(),
            "offlineAllowed",
            granted.offlineAllowed()));
    emit(
        account,
        version,
        "subscription.changed.v1",
        java.util.Map.of(
            "accountId",
            account.toString(),
            "subscriptionId",
            account.toString(),
            "state",
            granted.active() ? "ACTIVE" : "CANCELLED",
            "expiresAt",
            expiry.toEpochMilli(),
            "changeReason",
            "PURCHASED"));
  }

  public record SecurityResult(UUID operationId, UUID accountId, boolean active, long version) {}

  @Transactional
  public Entitlement cancel(UUID account, String actor) {
    lockAccount(account);
    if (db.queryForObject(
            "SELECT count(*) FROM purchase_order WHERE account_id=? AND state"
                + " IN('CREATED','PENDING','PENDING_RECONCILIATION')",
            Integer.class,
            account)
        > 0)
      throw new Failure(409, "PURCHASE_PENDING", "Resolve in-flight purchase before cancellation");
    var current = entitlement(account);
    if (!current.active()) return current;
    long version = nextVersion(account);
    db.update(
        "UPDATE subscription SET state='CANCELLED',version=? WHERE account_id=?", version, account);
    changed(account, version, actor, "OWNER_CANCELLED");
    return entitlement(account);
  }

  @Transactional
  public SecurityResult security(
      UUID id, UUID account, String action, String reason, String actor) {
    if (id == null
        || account == null
        || !java.util.Set.of("REVOKE", "RESTORE").contains(action)
        || !java.util.Set.of("CREDENTIAL_ABUSE", "STREAM_ABUSE", "PAYMENT_RISK", "SUPPORT_REVIEW")
            .contains(reason)) throw invalid();
    lockAccount(account);
    String fingerprint =
        hash(
            (account + ":" + action + ":" + reason + ":" + actor).getBytes(StandardCharsets.UTF_8));
    var prior =
        db.queryForList(
            "SELECT fingerprint FROM commerce_security_command WHERE id=?", String.class, id);
    if (!prior.isEmpty()) {
      if (!prior.getFirst().equals(fingerprint))
        throw new Failure(409, "OPERATION_CONFLICT", "Operation changed");
      return securityResult(id);
    }
    db.update(
        "UPDATE commerce_account SET access_revoked=? WHERE account_id=?",
        action.equals("REVOKE"),
        account);
    long version = nextVersion(account);
    db.update("UPDATE subscription SET version=? WHERE account_id=?", version, account);
    changed(account, version, actor, action + ":" + reason);
    db.update(
        "INSERT INTO commerce_security_command VALUES(?,?,?,?,?,?)",
        id,
        account,
        fingerprint,
        entitlement(account).active(),
        version,
        java.sql.Timestamp.from(clock.instant()));
    return securityResult(id);
  }

  private void lockAccount(UUID account) {
    db.update("INSERT INTO commerce_account(account_id) VALUES(?) ON CONFLICT DO NOTHING", account);
    db.queryForObject(
        "SELECT account_id FROM commerce_account WHERE account_id=? FOR UPDATE",
        UUID.class,
        account);
  }

  private long nextVersion(UUID account) {
    return db.queryForObject(
        "UPDATE commerce_account SET version=version+1 WHERE account_id=? RETURNING version",
        Long.class,
        account);
  }

  private SecurityResult securityResult(UUID id) {
    return db.queryForObject(
        "SELECT * FROM commerce_security_command WHERE id=?",
        (r, n) ->
            new SecurityResult(
                id,
                r.getObject("account_id", UUID.class),
                r.getBoolean("active"),
                r.getLong("version")),
        id);
  }

  private void changed(UUID account, long version, String actor, String action) {
    var current = entitlement(account);
    db.update(
        "INSERT INTO subscription_audit VALUES(?,?,?,?,?,?)",
        UUID.randomUUID(),
        account,
        hash(actor.getBytes(StandardCharsets.UTF_8)),
        action,
        version,
        java.sql.Timestamp.from(clock.instant()));
    long expires =
        current.expiresAt() == null
            ? clock.instant().toEpochMilli()
            : current.expiresAt().toEpochMilli();
    emit(
        account,
        version,
        "entitlement.changed.v1",
        java.util.Map.of(
            "accountId",
            account.toString(),
            "active",
            current.active(),
            "expiresAt",
            expires,
            "maxStreams",
            current.maxStreams(),
            "offlineAllowed",
            current.offlineAllowed()));
    emit(
        account,
        version,
        "subscription.changed.v1",
        java.util.Map.of(
            "accountId",
            account.toString(),
            "subscriptionId",
            account.toString(),
            "state",
            current.active() ? "ACTIVE" : "CANCELLED",
            "expiresAt",
            expires,
            "changeReason",
            action.startsWith("RESTORE") ? "SECURITY_RESTORED" : "ACCESS_REVOKED"));
  }

  private void emit(UUID account, long version, String topic, Object payload) {
    try {
      db.update(
          "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload) VALUES"
              + " (?,?,?,?,?::jsonb)",
          UUID.randomUUID(),
          account,
          version,
          topic,
          json.writeValueAsString(payload));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize owner event", e);
    }
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA256 unavailable", e);
    }
  }

  private static Failure invalid() {
    return new Failure(400, "INVALID_REQUEST", "Invalid commerce fields");
  }

  public static class Failure extends RuntimeException {
    private final int status;
    private final String code;

    public Failure(int status, String code, String detail) {
      super(detail);
      this.status = status;
      this.code = code;
    }

    public int status() {
      return status;
    }

    public String code() {
      return code;
    }
  }
}
