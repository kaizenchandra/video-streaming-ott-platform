package com.ott.experience.application;

import java.util.UUID;

public interface ViewerAccess {
  record Identity(String viewerToken) {}

  record Profile(UUID accountId, UUID profileId, int maturity) {}

  record Grant(
      UUID sessionId, UUID accountId, UUID profileId, UUID contentId, UUID deviceId, long epoch) {}

  Profile profile(Identity identity, UUID profileId);

  Grant grant(String token, UUID device, boolean offline);

  void catalog(Identity identity, UUID contentId, int maturity);
}
