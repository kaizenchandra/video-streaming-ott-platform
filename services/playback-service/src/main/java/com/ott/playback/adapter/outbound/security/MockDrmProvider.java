package com.ott.playback.adapter.outbound.security;

import com.ott.playback.application.GrantProvider;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
@ConditionalOnProperty(name = "ott.providers.mode", havingValue = "mock")
public class MockDrmProvider implements GrantProvider {
  private final CloudFrontCookieSigner signer;

  public MockDrmProvider() {
    try {
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      signer =
          new CloudFrontCookieSigner(
              generator.generateKeyPair().getPrivate(),
              "mock-untrusted",
              "https://local-cdn.invalid");
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Cannot create mock CDN signer", e);
    }
  }

  public Grant authorize(UUID session, UUID device, String prefix, Instant expiry, String token) {
    return new Grant(
        signer.manifest(prefix),
        "MOCK_CLEAR_ONLY",
        "mock-license-authorization:" + session + ":" + device,
        signer.cookies(prefix, expiry));
  }
}
