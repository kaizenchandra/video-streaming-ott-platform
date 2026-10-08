package com.ott.engagement.adapter.outbound.ads;

import com.ott.engagement.application.AdDelivery;
import com.ott.engagement.application.AdService;
import com.ott.engagement.application.EngagementFailure;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ProviderAdDelivery implements AdDelivery {
  private final CreativePolicy policy;
  private final String endpoint, secret;
  private final RestClient http;
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper();
  private final Semaphore calls = new Semaphore(16);

  public ProviderAdDelivery(
      io.micrometer.observation.ObservationRegistry observations,
      CreativePolicy policy,
      @Value("${ott.ads.ssai-url:}") String endpoint,
      @Value("${ott.ads.ssai-secret:}") String secret,
      Environment env) {
    this.policy = policy;
    this.endpoint = endpoint;
    this.secret = secret;
    if (!endpoint.isBlank()) {
      URI uri = URI.create(endpoint);
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || !(uri.getScheme().equals("https")
              || !env.acceptsProfiles(Profiles.of("prod"))
                  && uri.getScheme().equals("http")
                  && SetOfLoopback.contains(uri.getHost())))
        throw new IllegalArgumentException("Invalid SSAI provider endpoint");
    }
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(100));
    factory.setReadTimeout(Duration.ofMillis(300));
    http = RestClient.builder().observationRegistry(observations).requestFactory(factory).build();
  }

  private static final java.util.Set<String> SetOfLoopback =
      java.util.Set.of("localhost", "127.0.0.1", "[::1]");

  public Creative deliver(
      String adapter, UUID decision, AdService.Campaign campaign, Instant expires) {
    if (adapter.equals("CSAI"))
      return new Creative(campaign.creativeUrl(), campaign.durationMillis());
    if (!adapter.equals("SSAI") || endpoint.isBlank() || secret.length() < 32) throw unavailable();
    if (!calls.tryAcquire()) throw unavailable();
    try {
      var result =
          http.post()
              .uri(endpoint)
              .header("Authorization", "Bearer " + secret)
              .header("Idempotency-Key", decision.toString())
              .body(
                  Map.of(
                      "decisionId",
                      decision,
                      "campaignId",
                      campaign.id(),
                      "creativeUrl",
                      campaign.creativeUrl(),
                      "durationMillis",
                      campaign.durationMillis(),
                      "expiresAt",
                      expires.toString()))
              .exchange(
                  (request, response) -> {
                    byte[] raw = response.getBody().readNBytes(65537);
                    if (!response.getStatusCode().is2xxSuccessful() || raw.length > 65536)
                      throw unavailable();
                    return json.readTree(raw);
                  });
      if (result == null
          || !decision.toString().equals(result.path("decisionId").asText())
          || !policy.permits(result.path("url").asText())
          || !result.path("durationMillis").isIntegralNumber()
          || result.path("durationMillis").asInt() != campaign.durationMillis())
        throw unavailable();
      return new Creative(result.path("url").asText(), result.path("durationMillis").asInt());
    } catch (RestClientException | IllegalArgumentException e) {
      throw unavailable();
    } finally {
      calls.release();
    }
  }

  private static EngagementFailure unavailable() {
    return new EngagementFailure(503, "AD_PROVIDER_UNAVAILABLE");
  }
}
