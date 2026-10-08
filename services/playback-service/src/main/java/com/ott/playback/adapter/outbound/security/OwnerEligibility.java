package com.ott.playback.adapter.outbound.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.ott.playback.application.Eligibility;
import com.ott.playback.application.PlaybackFailure;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OwnerEligibility implements Eligibility {
  private final RestClient http;
  private final String member;
  private final String content;
  private final String commerce;
  private final WorkloadTokens workload;
  private final ExecutorService executor;
  private final Semaphore bulkhead = new Semaphore(40);

  public OwnerEligibility(
      io.micrometer.observation.ObservationRegistry observations,
      @Value("${ott.owners.member}") String member,
      @Value("${ott.owners.content}") String content,
      @Value("${ott.owners.commerce}") String commerce,
      WorkloadTokens workload) {
    var contexts =
        new io.micrometer.context.ContextRegistry()
            .registerThreadLocalAccessor(
                new io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor(
                    observations));
    var snapshots =
        io.micrometer.context.ContextSnapshotFactory.builder().contextRegistry(contexts).build();
    executor =
        io.micrometer.context.ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), snapshots::captureAll);
    this.member = member;
    this.content = content;
    this.commerce = commerce;
    this.workload = workload;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(100));
    factory.setReadTimeout(Duration.ofMillis(300));
    http = RestClient.builder().observationRegistry(observations).requestFactory(factory).build();
  }

  @PreDestroy
  void close() {
    executor.close();
  }

  public UUID account(Identity identity) {
    try {
      var a =
          http.get()
              .uri(member + "/v1/accounts/me")
              .headers(h -> h.setBearerAuth(identity.viewerToken()))
              .retrieve()
              .body(JsonNode.class);
      return UUID.fromString(a.path("id").asText());
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 403)
        throw new PlaybackFailure(403, "ACCOUNT_RESTRICTED", "Account restricted");
      if (e.getStatusCode().value() == 404)
        throw new PlaybackFailure(403, "ACCOUNT_REQUIRED", "Account required");
      throw unavailable();
    } catch (RestClientException | IllegalArgumentException | NullPointerException e) {
      throw unavailable();
    }
  }

  public Check check(
      Identity identity,
      UUID profile,
      UUID device,
      UUID contentId,
      String country,
      boolean offline) {
    if (!bulkhead.tryAcquire())
      throw new PlaybackFailure(503, "PLAYBACK_OVERLOADED", "Authorization capacity exhausted");
    try {
      UUID account = account(identity);
      String token = workload.token();
      JsonNode m =
          post(
              member + "/internal/v1/member/eligibility",
              token,
              Map.of("accountId", account, "profileId", profile, "deviceId", device),
              identity.profileAuthorization());
      if (!m.path("allowed").asBoolean(false))
        throw new PlaybackFailure(403, "DEVICE_NOT_ALLOWED", "Device or profile denied");
      var rights =
          CompletableFuture.supplyAsync(
              () ->
                  post(
                      content + "/internal/v1/rights/check",
                      token,
                      Map.of(
                          "contentId",
                          contentId,
                          "country",
                          country,
                          "maturity",
                          m.path("maturity").asInt(),
                          "offline",
                          offline)),
              executor);
      var entitlement =
          CompletableFuture.supplyAsync(
              () -> get(commerce + "/internal/v1/entitlements/" + account, token), executor);
      JsonNode r = rights.join(), e = entitlement.join();
      if (!e.path("active").asBoolean(false))
        throw new PlaybackFailure(403, "SUBSCRIPTION_INACTIVE", "Subscription inactive");
      if (!r.path("allowed").asBoolean(false)) {
        String code = r.path("code").asText();
        if (!java.util.Set.of(
                "CONTENT_NOT_AVAILABLE",
                "CONTENT_RIGHTS_EXPIRED",
                "REGION_NOT_ALLOWED",
                "PARENTAL_CONTROL_DENIED",
                "OFFLINE_NOT_ALLOWED")
            .contains(code)) code = "CONTENT_NOT_AVAILABLE";
        throw new PlaybackFailure(403, code, "Content rights denied");
      }
      Instant rightsEnd = Instant.parse(r.path("expiresAt").asText()),
          entitlementEnd = Instant.parse(e.path("expiresAt").asText());
      return new Check(
          account,
          e.path("maxStreams").asInt(),
          rightsEnd.isBefore(entitlementEnd) ? rightsEnd : entitlementEnd,
          r.path("originPrefix").asText(),
          r.path("offlineAllowed").asBoolean(false) && e.path("offlineAllowed").asBoolean(false),
          m.path("securityEpoch").asLong());
    } catch (CompletionException e) {
      if (e.getCause() instanceof PlaybackFailure f) throw f;
      throw unavailable();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw unavailable();
    } finally {
      bulkhead.release();
    }
  }

  public LiveCheck checkLive(
      Identity identity,
      UUID profile,
      UUID device,
      UUID event,
      String country,
      String mode,
      Instant seek) {
    if (!bulkhead.tryAcquire())
      throw new PlaybackFailure(503, "PLAYBACK_OVERLOADED", "Authorization capacity exhausted");
    try {
      UUID account = account(identity);
      String token = workload.token();
      var memberResult =
          post(
              member + "/internal/v1/member/eligibility",
              token,
              Map.of("accountId", account, "profileId", profile, "deviceId", device),
              identity.profileAuthorization());
      if (!memberResult.path("allowed").asBoolean(false))
        throw new PlaybackFailure(403, "DEVICE_NOT_ALLOWED", "Device or profile denied");
      var input = new java.util.HashMap<String, Object>();
      input.put("mode", mode);
      input.put("country", country);
      input.put("maturity", memberResult.path("maturity").asInt());
      input.put("seekAt", seek == null ? null : seek.toString());
      var rights =
          CompletableFuture.supplyAsync(
              () ->
                  post(
                      content + "/internal/v1/live/events/" + event + "/playback/check",
                      token,
                      input),
              executor);
      var entitlement =
          CompletableFuture.supplyAsync(
              () -> get(commerce + "/internal/v1/entitlements/" + account, token), executor);
      var r = rights.join();
      var e = entitlement.join();
      if (!e.path("active").asBoolean(false))
        throw new PlaybackFailure(403, "SUBSCRIPTION_INACTIVE", "Subscription inactive");
      if (!r.path("allowed").asBoolean(false))
        throw new PlaybackFailure(403, "LIVE_NOT_AVAILABLE", "Live policy denied");
      Instant rightsEnd = Instant.parse(r.path("expiresAt").asText()),
          entitlementEnd = Instant.parse(e.path("expiresAt").asText());
      return new LiveCheck(
          new Check(
              account,
              e.path("maxStreams").asInt(),
              rightsEnd.isBefore(entitlementEnd) ? rightsEnd : entitlementEnd,
              r.path("originPrefix").asText(),
              false),
          UUID.fromString(r.path("contentId").asText()));
    } catch (CompletionException e) {
      if (e.getCause() instanceof PlaybackFailure f) throw f;
      throw unavailable();
    } catch (IllegalArgumentException | NullPointerException e) {
      throw unavailable();
    } finally {
      bulkhead.release();
    }
  }

  public boolean issuedAllowed(
      UUID account,
      UUID profile,
      UUID device,
      UUID contentId,
      String country,
      String prefix,
      long epoch) {
    if (!bulkhead.tryAcquire()) throw unavailable();
    try {
      String token = workload.token();
      var m =
          post(
              member + "/internal/v1/member/issued-access",
              token,
              Map.of("accountId", account, "profileId", profile, "deviceId", device));
      if (!m.path("allowed").asBoolean(false) || m.path("securityEpoch").asLong() != epoch)
        return false;
      var r =
          CompletableFuture.supplyAsync(
              () ->
                  post(
                      content + "/internal/v1/rights/check",
                      token,
                      Map.of(
                          "contentId",
                          contentId,
                          "country",
                          country,
                          "maturity",
                          m.path("maturity").asInt(),
                          "offline",
                          true)),
              executor);
      var e =
          CompletableFuture.supplyAsync(
              () -> get(commerce + "/internal/v1/entitlements/" + account, token), executor);
      var rights = r.join();
      var entitlement = e.join();
      return rights.path("allowed").asBoolean(false)
          && rights.path("offlineAllowed").asBoolean(false)
          && prefix.equals(rights.path("originPrefix").asText())
          && entitlement.path("active").asBoolean(false)
          && entitlement.path("offlineAllowed").asBoolean(false);
    } catch (CompletionException | IllegalArgumentException | NullPointerException e) {
      throw unavailable();
    } finally {
      bulkhead.release();
    }
  }

  private JsonNode post(String uri, String token, Object body) {
    return post(uri, token, body, null);
  }

  private JsonNode post(String uri, String token, Object body, String proof) {
    try {
      return http.post()
          .uri(uri)
          .headers(
              h -> {
                h.setBearerAuth(token);
                if (proof != null) h.set("X-Profile-Authorization", proof);
              })
          .body(body)
          .retrieve()
          .body(JsonNode.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 403)
        throw new PlaybackFailure(403, "PLAYBACK_DENIED", "Owner denied eligibility");
      throw unavailable();
    } catch (RestClientException e) {
      throw unavailable();
    }
  }

  private JsonNode get(String uri, String token) {
    try {
      return http.get()
          .uri(uri)
          .headers(h -> h.setBearerAuth(token))
          .retrieve()
          .body(JsonNode.class);
    } catch (RestClientException e) {
      throw unavailable();
    }
  }

  private static PlaybackFailure unavailable() {
    return new PlaybackFailure(503, "ELIGIBILITY_UNAVAILABLE", "Eligibility owner unavailable");
  }
}
