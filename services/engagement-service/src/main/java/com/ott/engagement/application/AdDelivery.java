package com.ott.engagement.application;

import java.time.Instant;
import java.util.UUID;

public interface AdDelivery {
  record Creative(String url, int durationMillis) {}

  Creative deliver(String adapter, UUID decision, AdService.Campaign campaign, Instant expiresAt);
}
