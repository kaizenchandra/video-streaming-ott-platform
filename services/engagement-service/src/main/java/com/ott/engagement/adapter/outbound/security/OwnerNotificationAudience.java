package com.ott.engagement.adapter.outbound.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.ott.engagement.application.EngagementFailure;
import com.ott.engagement.application.NotificationAudience;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OwnerNotificationAudience implements NotificationAudience {
  private final RestClient http;
  private final String member, content;
  private final Semaphore calls = new Semaphore(24);

  public OwnerNotificationAudience(
      io.micrometer.observation.ObservationRegistry observations,
      @Value("${ott.owners.member}") String member,
      @Value("${ott.owners.content:http://localhost:8082}") String content) {
    this.member = member;
    this.content = content;
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(Duration.ofMillis(100));
    f.setReadTimeout(Duration.ofMillis(300));
    http = RestClient.builder().observationRegistry(observations).requestFactory(f).build();
  }

  public UUID account(String token) {
    var result = get(member + "/v1/accounts/me", token);
    try {
      return UUID.fromString(result.path("id").asText());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new EngagementFailure(503, "NOTIFICATION_OWNER_UNAVAILABLE");
    }
  }

  public Target target(String token, String kind, UUID id) {
    try {
      var value =
          get(content + (kind.equals("LIVE") ? "/v1/live/events/" : "/v1/catalog/") + id, token);
      if (value == null) throw new IllegalArgumentException();
      if (kind.equals("LIVE")) {
        String state = value.path("state").asText();
        long version = value.path("version").asLong();
        if (version < 1) throw new IllegalArgumentException();
        if (!java.util.List.of("SCHEDULED", "READY", "LIVE").contains(state))
          throw new EngagementFailure(409, "REMINDER_TARGET_CLOSED");
        return new Target(state, version, java.time.Instant.parse(value.path("startsAt").asText()));
      }
      return new Target("PUBLISHED", 0, null);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new EngagementFailure(503, "NOTIFICATION_OWNER_UNAVAILABLE");
    }
  }

  private JsonNode get(String uri, String token) {
    if (!calls.tryAcquire()) throw new EngagementFailure(503, "NOTIFICATION_OWNER_OVERLOADED");
    try {
      return http.get()
          .uri(uri)
          .headers(h -> h.setBearerAuth(token))
          .retrieve()
          .body(JsonNode.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404)
        throw new EngagementFailure(404, "NOTIFICATION_TARGET_NOT_FOUND");
      throw new EngagementFailure(503, "NOTIFICATION_OWNER_UNAVAILABLE");
    } catch (RestClientException e) {
      throw new EngagementFailure(503, "NOTIFICATION_OWNER_UNAVAILABLE");
    } finally {
      calls.release();
    }
  }
}
