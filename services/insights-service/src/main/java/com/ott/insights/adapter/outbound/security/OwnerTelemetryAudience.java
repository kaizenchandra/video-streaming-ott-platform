package com.ott.insights.adapter.outbound.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.ott.insights.application.InsightsFailure;
import com.ott.insights.application.TelemetryAudience;
import java.time.Duration;
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
public class OwnerTelemetryAudience implements TelemetryAudience {
  private final RestClient http;
  private final String member, playback;
  private final WorkloadTokens tokens;
  private final Semaphore calls = new Semaphore(24);

  public OwnerTelemetryAudience(
      io.micrometer.observation.ObservationRegistry observations,
      @Value("${ott.owners.member}") String member,
      @Value("${ott.owners.playback}") String playback,
      WorkloadTokens tokens) {
    this.member = member;
    this.playback = playback;
    this.tokens = tokens;
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(Duration.ofMillis(100));
    f.setReadTimeout(Duration.ofMillis(300));
    http = RestClient.builder().observationRegistry(observations).requestFactory(f).build();
  }

  public UUID account(String token) {
    return call(() -> UUID.fromString(get(member + "/v1/accounts/me", token).path("id").asText()));
  }

  public Context verify(String token, UUID profile, UUID device, String grantToken) {
    if (profile == null || device == null) throw new InsightsFailure(400, "INVALID_TELEMETRY");
    return call(
        () -> {
          UUID account =
              UUID.fromString(get(member + "/v1/accounts/me", token).path("id").asText());
          boolean owned = false;
          for (var row : get(member + "/v1/profiles", token))
            if (row.path("id").asText().equals(profile.toString())) owned = true;
          if (!owned) throw new InsightsFailure(404, "PROFILE_NOT_FOUND");
          boolean registered = false;
          for (var row : get(member + "/v1/devices", token))
            if (row.path("id").asText().equals(device.toString())
                && "ACTIVE".equals(row.path("status").asText())) registered = true;
          if (!registered) throw new InsightsFailure(403, "DEVICE_NOT_ALLOWED");
          UUID session = null;
          if (grantToken != null) {
            var grant =
                http.post()
                    .uri(playback + "/internal/v1/playback/token/check")
                    .headers(h -> h.setBearerAuth(tokens.token()))
                    .body(Map.of("token", grantToken, "deviceId", device))
                    .retrieve()
                    .body(JsonNode.class);
            if (grant == null
                || !account.toString().equals(grant.path("accountId").asText())
                || !profile.toString().equals(grant.path("profileId").asText())
                || !device.toString().equals(grant.path("deviceId").asText()))
              throw new InsightsFailure(403, "TELEMETRY_GRANT_DENIED");
            session = UUID.fromString(grant.path("sessionId").asText());
          }
          return new Context(account, profile, device, session);
        });
  }

  private JsonNode get(String uri, String token) {
    return http.get().uri(uri).headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class);
  }

  private <T> T call(java.util.function.Supplier<T> body) {
    if (!calls.tryAcquire()) throw new InsightsFailure(503, "TELEMETRY_OWNER_OVERLOADED");
    try {
      return body.get();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 403 || e.getStatusCode().value() == 404)
        throw new InsightsFailure(403, "TELEMETRY_GRANT_DENIED");
      throw new InsightsFailure(503, "TELEMETRY_OWNER_UNAVAILABLE");
    } catch (RestClientException | IllegalArgumentException | NullPointerException e) {
      throw new InsightsFailure(503, "TELEMETRY_OWNER_UNAVAILABLE");
    } finally {
      calls.release();
    }
  }
}
