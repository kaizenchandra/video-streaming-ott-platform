package com.ott.playback.adapter.outbound.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.ott.playback.application.PlaybackFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ClientCredentialsTokens implements WorkloadTokens {
  private final RestClient http;
  private final String endpoint;
  private final String client;
  private final String secret;
  private final Clock clock;
  private String cached;
  private Instant expires = Instant.EPOCH;

  public ClientCredentialsTokens(
      io.micrometer.observation.ObservationRegistry observations,
      @Value("${ott.workload.token-url:}") String endpoint,
      @Value("${ott.workload.client-id:}") String client,
      @Value("${ott.workload.client-secret:}") String secret,
      Clock clock) {
    this.endpoint = endpoint;
    this.client = client;
    this.secret = secret;
    this.clock = clock;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(300));
    factory.setReadTimeout(Duration.ofMillis(700));
    http = RestClient.builder().observationRegistry(observations).requestFactory(factory).build();
  }

  public synchronized String token() {
    if (cached != null && expires.isAfter(clock.instant())) return cached;
    if (endpoint.isBlank() || client.isBlank() || secret.isBlank()) throw unavailable();
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", client);
    form.add("client_secret", secret);
    try {
      JsonNode result =
          http.post()
              .uri(endpoint)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(JsonNode.class);
      if (result == null
          || !result.path("token_type").asText().equalsIgnoreCase("Bearer")
          || !result.path("access_token").isTextual()
          || result.path("access_token").asText().isBlank()
          || result.path("expires_in").asInt() < 30) throw unavailable();
      cached = result.path("access_token").asText();
      expires = clock.instant().plusSeconds(Math.min(240, result.path("expires_in").asLong() - 10));
      return cached;
    } catch (RestClientException e) {
      throw unavailable();
    }
  }

  private static PlaybackFailure unavailable() {
    return new PlaybackFailure(503, "IDENTITY_UNAVAILABLE", "Workload identity unavailable");
  }
}
