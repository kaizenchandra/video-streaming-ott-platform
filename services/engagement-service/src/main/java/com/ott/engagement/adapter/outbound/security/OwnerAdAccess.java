package com.ott.engagement.adapter.outbound.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.ott.engagement.application.AdAccess;
import com.ott.engagement.application.EngagementFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OwnerAdAccess implements AdAccess {
  private final RestClient http;
  private final String member, commerce, playback;
  private final WorkloadTokens tokens;
  private final Semaphore calls = new Semaphore(24);

  public OwnerAdAccess(
      io.micrometer.observation.ObservationRegistry observations,
      @Value("${ott.owners.member}") String member,
      @Value("${ott.owners.commerce}") String commerce,
      @Value("${ott.owners.playback}") String playback,
      WorkloadTokens tokens) {
    this.member = member;
    this.commerce = commerce;
    this.playback = playback;
    this.tokens = tokens;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(100));
    factory.setReadTimeout(Duration.ofMillis(300));
    http = RestClient.builder().observationRegistry(observations).requestFactory(factory).build();
  }

  public Context verify(String viewerToken, UUID profile, UUID device, String playbackToken) {
    if (profile == null || device == null || playbackToken == null || playbackToken.length() > 8192)
      throw new EngagementFailure(400, "INVALID_AD_REQUEST");
    if (!calls.tryAcquire()) throw new EngagementFailure(503, "AD_OWNER_OVERLOADED");
    try {
      var account = get(member + "/v1/accounts/me", viewerToken);
      UUID accountId = UUID.fromString(account.path("id").asText());
      var profiles = get(member + "/v1/profiles", viewerToken);
      JsonNode owned = null;
      for (var row : profiles) if (row.path("id").asText().equals(profile.toString())) owned = row;
      if (owned == null) throw new EngagementFailure(404, "PROFILE_NOT_FOUND");
      var grant =
          http.post()
              .uri(playback + "/internal/v1/playback/ads/context")
              .headers(h -> h.setBearerAuth(tokens.token()))
              .body(Map.of("token", playbackToken, "deviceId", device))
              .retrieve()
              .body(JsonNode.class);
      if (grant == null
          || !accountId.toString().equals(grant.path("accountId").asText())
          || !profile.toString().equals(grant.path("profileId").asText())
          || !device.toString().equals(grant.path("deviceId").asText()))
        throw new EngagementFailure(403, "AD_GRANT_DENIED");
      String country = grant.path("country").asText();
      if (!country.matches("[A-Z]{2}")) throw new IllegalArgumentException();
      var entitlement = get(commerce + "/internal/v1/entitlements/" + accountId, tokens.token());
      Instant expires = Instant.parse(grant.path("expiresAt").asText());
      boolean supported =
          entitlement.path("active").asBoolean(false)
              && entitlement.path("adSupported").asBoolean(false);
      if (supported)
        expires =
            expires.isBefore(Instant.parse(entitlement.path("expiresAt").asText()))
                ? expires
                : Instant.parse(entitlement.path("expiresAt").asText());
      return new Context(
          accountId,
          profile,
          device,
          UUID.fromString(grant.path("sessionId").asText()),
          country,
          owned.path("maturity").asInt(0),
          owned.path("kids").asBoolean(true),
          supported,
          expires);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 403 || e.getStatusCode().value() == 404)
        throw new EngagementFailure(403, "AD_GRANT_DENIED");
      throw new EngagementFailure(503, "AD_OWNER_UNAVAILABLE");
    } catch (RestClientException | IllegalArgumentException | NullPointerException e) {
      throw new EngagementFailure(503, "AD_OWNER_UNAVAILABLE");
    } finally {
      calls.release();
    }
  }

  private JsonNode get(String uri, String token) {
    return http.get().uri(uri).headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class);
  }
}
