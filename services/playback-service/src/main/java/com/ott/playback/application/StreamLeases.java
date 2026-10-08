package com.ott.playback.application;

import java.util.UUID;

public interface StreamLeases {
  boolean acquire(UUID account, UUID session, int limit, long durationMillis);

  boolean renew(UUID account, UUID session, long durationMillis);

  void release(UUID account, UUID session);
}
