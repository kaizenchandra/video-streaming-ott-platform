package com.ott.engagement.application;

import java.util.UUID;

public interface MessageDelivery {
  enum Outcome {
    ACCEPTED,
    REJECTED,
    UNKNOWN
  }

  record Result(Outcome outcome, String receipt) {}

  boolean idempotent();

  Result send(UUID intent, String channel, String recipientReference, String template, UUID target);
}
