package com.ott.insights.application;

import java.util.UUID;

public interface TelemetryAudience {
  UUID account(String viewerToken);

  record Context(UUID accountId, UUID profileId, UUID deviceId, UUID sessionId) {}

  Context verify(String viewerToken, UUID profileId, UUID deviceId, String playbackToken);
}
