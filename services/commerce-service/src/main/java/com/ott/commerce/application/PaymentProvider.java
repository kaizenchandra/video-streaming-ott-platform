package com.ott.commerce.application;

import java.util.UUID;

public interface PaymentProvider {
  record Attempt(String reference, String state, long amountMinor, String currency) {}

  Attempt create(UUID orderId, long amountMinor, String currency);

  Attempt lookup(UUID orderId);

  // An unknown outcome is not a decline and must never cause automatic charge retry.
  class UncertainOutcome extends RuntimeException {
    public UncertainOutcome() {
      super("Payment outcome requires reconciliation");
    }
  }
}
