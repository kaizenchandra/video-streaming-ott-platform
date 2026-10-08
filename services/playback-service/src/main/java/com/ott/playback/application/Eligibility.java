package com.ott.playback.application;

import java.time.Instant;
import java.util.UUID;

public interface Eligibility {
  record Identity(String issuer, String subject, String viewerToken, String profileAuthorization) {
    public Identity(String issuer, String subject, String viewerToken) {
      this(issuer, subject, viewerToken, null);
    }

    @Override
    public String toString() {
      return "PlaybackIdentity[credentials redacted]";
    }
  }

  record Check(
      UUID accountId,
      int maxStreams,
      Instant expiresAt,
      String originPrefix,
      boolean offlineAllowed,
      long memberEpoch) {
    public Check(
        UUID accountId,
        int maxStreams,
        Instant expiresAt,
        String originPrefix,
        boolean offlineAllowed) {
      this(accountId, maxStreams, expiresAt, originPrefix, offlineAllowed, 0);
    }
  }

  default boolean issuedAllowed(
      UUID account,
      UUID profile,
      UUID device,
      UUID content,
      String country,
      String prefix,
      long epoch) {
    throw new PlaybackFailure(503, "ELIGIBILITY_UNAVAILABLE", "Issued access policy unavailable");
  }

  record LiveCheck(Check authorization, UUID contentId) {}

  default LiveCheck checkLive(
      Identity identity,
      UUID profile,
      UUID device,
      UUID event,
      String country,
      String mode,
      Instant seek) {
    throw new PlaybackFailure(503, "LIVE_UNAVAILABLE", "Live eligibility unavailable");
  }

  UUID account(Identity identity);

  Check check(
      Identity identity, UUID profile, UUID device, UUID content, String country, boolean offline);
}
