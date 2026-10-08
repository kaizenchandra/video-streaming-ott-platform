package com.ott.engagement.adapter.outbound.notifications;

import com.ott.engagement.application.MessageDelivery;
import java.net.URI;
import java.time.Duration;
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
public class HttpMessageDelivery implements MessageDelivery {
  private final RestClient http;
  private final String endpoint, secret;
  private final boolean idempotent;
  private final Semaphore calls = new Semaphore(16);
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper();

  public HttpMessageDelivery(
      io.micrometer.observation.ObservationRegistry observations,
      @Value("${ott.notifications.provider-url:}") String url,
      @Value("${ott.notifications.provider-secret:}") String secret,
      @Value("${ott.notifications.provider-idempotent:false}") boolean idempotent,
      Environment env) {
    this.endpoint = url;
    this.secret = secret;
    this.idempotent = idempotent;
    if (!url.isBlank()) {
      URI uri = URI.create(url);
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || !(uri.getScheme().equals("https")
              || !env.acceptsProfiles(Profiles.of("prod"))
                  && uri.getScheme().equals("http")
                  && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))
        throw new IllegalArgumentException("Invalid message-provider endpoint");
    }
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(Duration.ofMillis(100));
    f.setReadTimeout(Duration.ofMillis(300));
    http = RestClient.builder().observationRegistry(observations).requestFactory(f).build();
  }

  public boolean idempotent() {
    return idempotent;
  }

  public Result send(UUID intent, String channel, String recipient, String template, UUID target) {
    if (endpoint.isBlank() || secret.length() < 32 || !calls.tryAcquire())
      return new Result(Outcome.REJECTED, null);
    try {
      return http.post()
          .uri(endpoint)
          .header("Authorization", "Bearer " + secret)
          .header("Idempotency-Key", intent.toString())
          .body(
              Map.of(
                  "intentId",
                  intent,
                  "channel",
                  channel,
                  "recipientReference",
                  recipient,
                  "templateId",
                  template,
                  "targetId",
                  target))
          .exchange(
              (request, response) -> {
                byte[] raw = response.getBody().readNBytes(65537);
                if (response.getStatusCode().is4xxClientError())
                  return new Result(Outcome.REJECTED, null);
                if (!response.getStatusCode().is2xxSuccessful() || raw.length > 65536)
                  return new Result(Outcome.UNKNOWN, null);
                var body = json.readTree(raw);
                if (body == null
                    || !intent.toString().equals(body.path("intentId").asText())
                    || !body.path("accepted").isBoolean()) return new Result(Outcome.UNKNOWN, null);
                String receipt = body.path("receipt").asText();
                if (!body.path("accepted").asBoolean()) return new Result(Outcome.REJECTED, null);
                if (receipt.isBlank()
                    || receipt.length() > 255
                    || receipt.chars().anyMatch(Character::isISOControl))
                  return new Result(Outcome.UNKNOWN, null);
                return new Result(Outcome.ACCEPTED, receipt);
              });
    } catch (RestClientException | IllegalArgumentException e) {
      return new Result(Outcome.UNKNOWN, null);
    } finally {
      calls.release();
    }
  }
}
