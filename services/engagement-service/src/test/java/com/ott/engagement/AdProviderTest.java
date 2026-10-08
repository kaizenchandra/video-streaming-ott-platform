package com.ott.engagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.engagement.adapter.outbound.ads.CreativePolicy;
import com.ott.engagement.adapter.outbound.ads.ProviderAdDelivery;
import com.ott.engagement.application.AdService;
import com.ott.engagement.application.EngagementFailure;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AdProviderTest {
  @Test
  void provider_csaiAndUnconfiguredSsai_haveExplicitBoundary() {
    var policy = new CreativePolicy("cdn.example");
    var adapter =
        new ProviderAdDelivery(
            io.micrometer.observation.ObservationRegistry.NOOP,
            policy,
            "",
            "",
            new MockEnvironment());
    var c = campaign();
    assertThat(adapter.deliver("CSAI", UUID.randomUUID(), c, Instant.now().plusSeconds(30)).url())
        .isEqualTo(c.creativeUrl());
    assertThatThrownBy(() -> adapter.deliver("SSAI", UUID.randomUUID(), c, Instant.now()))
        .isInstanceOf(EngagementFailure.class);
    for (var url :
        java.util.List.of(
            "http://cdn.example/ad",
            "https://cdn.example@evil.invalid/ad",
            "https://cdn.example:444/ad",
            "https://cdn.example/ad#token",
            "https://other.example/ad")) assertThat(policy.permits(url)).isFalse();
    assertThatThrownBy(
            () ->
                new ProviderAdDelivery(
                    io.micrometer.observation.ObservationRegistry.NOOP,
                    policy,
                    "http://127.0.0.1/stitch",
                    "s".repeat(32),
                    new MockEnvironment().withProperty("spring.profiles.active", "prod")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void provider_realHttp_validatesBindingHostAndDeadline() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var mode = new java.util.concurrent.atomic.AtomicInteger();
    var id = UUID.randomUUID();
    server.createContext(
        "/stitch",
        exchange -> {
          assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
              .isEqualTo(id.toString());
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer " + "s".repeat(32));
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          assertThat(body).contains(id.toString());
          if (mode.get() == 2)
            try {
              Thread.sleep(650);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          String response =
              "{\"decisionId\":\""
                  + id
                  + "\",\"url\":\"https://"
                  + (mode.get() == 1 ? "evil.invalid" : "cdn.example")
                  + "/stitched.m3u8\",\"durationMillis\":4000}";
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          try {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var adapter =
          new ProviderAdDelivery(
              io.micrometer.observation.ObservationRegistry.NOOP,
              new CreativePolicy("cdn.example"),
              "http://127.0.0.1:" + server.getAddress().getPort() + "/stitch",
              "s".repeat(32),
              new MockEnvironment());
      assertThat(adapter.deliver("SSAI", id, campaign(), Instant.now().plusSeconds(30)).url())
          .endsWith("stitched.m3u8");
      mode.set(1);
      assertThatThrownBy(() -> adapter.deliver("SSAI", id, campaign(), Instant.now()))
          .isInstanceOf(EngagementFailure.class);
      mode.set(2);
      long start = System.nanoTime();
      assertThatThrownBy(() -> adapter.deliver("SSAI", id, campaign(), Instant.now()))
          .isInstanceOf(EngagementFailure.class);
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
          .isLessThan(java.time.Duration.ofSeconds(1));
    } finally {
      server.stop(0);
    }
  }

  private AdService.Campaign campaign() {
    return new AdService.Campaign(
        UUID.randomUUID(),
        "Fixture",
        "IN",
        0,
        "PRE_ROLL",
        Instant.now().minusSeconds(1),
        Instant.now().plusSeconds(3600),
        1,
        "https://cdn.example/ad.mp4",
        4000,
        true,
        1);
  }
}
