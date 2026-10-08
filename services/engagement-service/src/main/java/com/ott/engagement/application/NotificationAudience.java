package com.ott.engagement.application;

import java.util.UUID;

public interface NotificationAudience {
  UUID account(String viewerToken);

  record Target(String state, long version, java.time.Instant startsAt) {}

  Target target(String viewerToken, String kind, UUID targetId);
}
