package com.ott.engagement.application;

import java.time.Instant;
import java.util.UUID;

public interface AdAccess {
  record Context(
      UUID accountId,
      UUID profileId,
      UUID deviceId,
      UUID sessionId,
      String country,
      int maturity,
      boolean kids,
      boolean adSupported,
      Instant expiresAt) {}

  Context verify(String viewerToken, UUID profile, UUID device, String playbackToken);
}
