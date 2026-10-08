package com.ott.playback.application;

import java.time.Instant;
import java.util.UUID;

public interface GrantProvider {
  record Grant(
      String manifestUrl,
      String protection,
      String drmAuthorization,
      java.util.Map<String, String> cdnCookies) {}

  Grant authorize(UUID session, UUID device, String originPrefix, Instant expiresAt, String token);
}
