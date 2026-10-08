package com.ott.commerce.adapter.outbound.payment;

import com.ott.commerce.application.PaymentProvider;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@org.springframework.context.annotation.Profile("!prod")
@Component
@ConditionalOnProperty(name = "ott.payment.mode", havingValue = "mock")
public class MockPaymentProvider implements PaymentProvider {
  private final java.util.concurrent.ConcurrentMap<UUID, Attempt> pending =
      new java.util.concurrent.ConcurrentHashMap<>();

  public Attempt create(UUID orderId, long amountMinor, String currency) {
    return pending.computeIfAbsent(
        orderId, id -> new Attempt("mock-" + id, "PENDING", amountMinor, currency));
  }

  public Attempt lookup(UUID orderId) {
    var found = pending.get(orderId);
    if (found == null) throw new UncertainOutcome();
    return found;
  }
}
