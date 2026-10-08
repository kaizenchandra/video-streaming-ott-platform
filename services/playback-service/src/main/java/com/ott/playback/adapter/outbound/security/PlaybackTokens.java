package com.ott.playback.adapter.outbound.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.playback.application.PlaybackFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PlaybackTokens {
  private final byte[] key;
  private final ObjectMapper json;
  private final Clock clock;

  public PlaybackTokens(
      @Value("${ott.token.secret}") String secret, ObjectMapper json, Clock clock) {
    if (secret.length() < 32) throw new IllegalStateException("Token signing key too short");
    key = secret.getBytes(StandardCharsets.UTF_8);
    this.json = json;
    this.clock = clock;
  }

  public String issue(
      UUID account, UUID profile, UUID device, UUID session, UUID content, Instant expiry) {
    return issueFor("ott-playback", account, profile, device, session, content, expiry);
  }

  public String issueOffline(
      UUID account, UUID profile, UUID device, UUID session, UUID content, Instant expiry) {
    return issueFor("ott-offline", account, profile, device, session, content, expiry);
  }

  private String issueFor(
      String audience,
      UUID account,
      UUID profile,
      UUID device,
      UUID session,
      UUID content,
      Instant expiry) {
    try {
      String payload =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  json.writeValueAsBytes(
                      Map.of(
                          "aud",
                          audience,
                          "account",
                          account.toString(),
                          "profile",
                          profile.toString(),
                          "device",
                          device.toString(),
                          "session",
                          session.toString(),
                          "content",
                          content.toString(),
                          "exp",
                          expiry.getEpochSecond(),
                          "jti",
                          UUID.randomUUID().toString())));
      return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot issue grant", e);
    }
  }

  public UUID verify(String token, UUID device) {
    return verifyFor("ott-playback", token, device);
  }

  public UUID verifyOffline(String token, UUID device) {
    return verifyFor("ott-offline", token, device);
  }

  private UUID verifyFor(String audience, String token, UUID device) {
    try {
      if (token == null || token.length() > 4096) throw deny();
      String[] parts = token.split("\\.");
      if (parts.length != 2
          || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1])))
        throw deny();
      var claims = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
      if (!audience.equals(claims.path("aud").asText())
          || !device.toString().equals(claims.path("device").asText())
          || clock.instant().getEpochSecond() >= claims.path("exp").asLong()) throw deny();
      return UUID.fromString(claims.path("session").asText());
    } catch (java.io.IOException | IllegalArgumentException e) {
      throw deny();
    }
  }

  private byte[] sign(String payload) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Grant signer unavailable", e);
    }
  }

  private static PlaybackFailure deny() {
    return new PlaybackFailure(403, "PLAYBACK_TOKEN_DENIED", "Playback grant denied");
  }
}
