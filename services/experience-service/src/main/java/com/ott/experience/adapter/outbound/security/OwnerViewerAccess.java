package com.ott.experience.adapter.outbound.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.ott.experience.application.ViewerAccess;
import com.ott.experience.application.WatchFailure;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OwnerViewerAccess implements ViewerAccess {
  private final RestClient http;
  private final String member, content, playback;
  private final WorkloadTokens workload;

  public OwnerViewerAccess(
      io.micrometer.observation.ObservationRegistry observations,
      @Value("${ott.owners.member}") String member,
      @Value("${ott.owners.content}") String content,
      @Value("${ott.owners.playback}") String playback,
      WorkloadTokens workload) {
    this.member = member;
    this.content = content;
    this.playback = playback;
    this.workload = workload;
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(Duration.ofMillis(100));
    f.setReadTimeout(Duration.ofMillis(300));
    http = RestClient.builder().observationRegistry(observations).requestFactory(f).build();
  }

  public Profile profile(Identity identity, UUID profileId) {
    try {
      var account = get(member + "/v1/accounts/me", identity.viewerToken());
      var profiles = get(member + "/v1/profiles", identity.viewerToken());
      for (var profile : profiles)
        if (profileId.toString().equals(profile.path("id").asText()))
          return new Profile(
              UUID.fromString(account.path("id").asText()),
              profileId,
              profile.path("maturity").asInt());
      throw new WatchFailure(404, "PROFILE_NOT_FOUND");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) throw new WatchFailure(404, "PROFILE_NOT_FOUND");
      throw unavailable();
    } catch (RestClientException | IllegalArgumentException | NullPointerException e) {
      throw unavailable();
    }
  }

  public Grant grant(String token, UUID device, boolean offline) {
    try {
      var result =
          http.post()
              .uri(
                  playback
                      + (offline
                          ? "/internal/v1/downloads/token/check"
                          : "/internal/v1/playback/token/check"))
              .headers(h -> h.setBearerAuth(workload.token()))
              .body(Map.of("token", token, "deviceId", device))
              .retrieve()
              .body(JsonNode.class);
      long epoch = result.path("epoch").asLong();
      if (epoch < 1) throw unavailable();
      return new Grant(
          UUID.fromString(result.path("sessionId").asText()),
          UUID.fromString(result.path("accountId").asText()),
          UUID.fromString(result.path("profileId").asText()),
          UUID.fromString(result.path("contentId").asText()),
          UUID.fromString(result.path("deviceId").asText()),
          epoch);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 403 || e.getStatusCode().value() == 404)
        throw new WatchFailure(403, "PROGRESS_GRANT_DENIED");
      throw unavailable();
    } catch (RestClientException | IllegalArgumentException | NullPointerException e) {
      throw unavailable();
    }
  }

  public void catalog(Identity identity, UUID contentId, int maturity) {
    try {
      var item = get(content + "/v1/catalog/" + contentId, identity.viewerToken());
      if (item.path("maturity").asInt(18) > maturity)
        throw new WatchFailure(403, "PARENTAL_CONTROL_DENIED");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) throw new WatchFailure(404, "CONTENT_NOT_FOUND");
      throw unavailable();
    } catch (RestClientException e) {
      throw unavailable();
    }
  }

  private JsonNode get(String uri, String token) {
    return http.get().uri(uri).headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class);
  }

  private static WatchFailure unavailable() {
    return new WatchFailure(503, "OWNER_UNAVAILABLE");
  }
}
