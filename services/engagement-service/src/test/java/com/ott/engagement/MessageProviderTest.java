package com.ott.engagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.engagement.adapter.outbound.notifications.ContactCipher;
import com.ott.engagement.adapter.outbound.notifications.HttpMessageDelivery;
import com.ott.engagement.application.AdService;
import com.ott.engagement.application.EngagementFailure;
import com.ott.engagement.application.MessageDelivery;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MessageProviderTest {
  @Test
  void contacts_authenticatedEncryptionAndMissingKey_failClosed() {
    var cipher = new ContactCipher(AdService.hash(UUID.randomUUID().toString()));
    String encrypted = cipher.encrypt("fixture@example.invalid", "account:EMAIL");
    assertThat(cipher.decrypt(encrypted, "account:EMAIL")).isEqualTo("fixture@example.invalid");
    assertThatThrownBy(() -> cipher.decrypt(encrypted, "other:EMAIL"))
        .isInstanceOf(EngagementFailure.class);
    assertThatThrownBy(() -> new ContactCipher("").encrypt("fixture", "binding"))
        .isInstanceOf(EngagementFailure.class);
  }

  @Test
  void externalProvider_realHttpIdempotencyRejectionAndUnknown_areExplicit() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    UUID intent = UUID.randomUUID();
    var mode = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext(
        "/messages",
        exchange -> {
          assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
              .isEqualTo(intent.toString());
          assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
              .contains("fixture@example.invalid");
          if (mode.get() == 2)
            try {
              Thread.sleep(650);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          byte[] response =
              ("{\"intentId\":\""
                      + intent
                      + "\",\"accepted\":true,\"receipt\":\"fixture-receipt\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          try {
            exchange.sendResponseHeaders(mode.get() == 1 ? 400 : 200, response.length);
            exchange.getResponseBody().write(response);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var adapter =
          new HttpMessageDelivery(
              io.micrometer.observation.ObservationRegistry.NOOP,
              "http://127.0.0.1:" + server.getAddress().getPort() + "/messages",
              "s".repeat(32),
              true,
              new MockEnvironment());
      assertThat(adapter.idempotent()).isTrue();
      assertThat(
              adapter
                  .send(
                      intent,
                      "EMAIL",
                      "fixture@example.invalid",
                      "CONTENT_REMINDER",
                      UUID.randomUUID())
                  .outcome())
          .isEqualTo(MessageDelivery.Outcome.ACCEPTED);
      mode.set(1);
      assertThat(
              adapter
                  .send(
                      intent,
                      "EMAIL",
                      "fixture@example.invalid",
                      "CONTENT_REMINDER",
                      UUID.randomUUID())
                  .outcome())
          .isEqualTo(MessageDelivery.Outcome.REJECTED);
      mode.set(2);
      assertThat(
              adapter
                  .send(
                      intent,
                      "EMAIL",
                      "fixture@example.invalid",
                      "CONTENT_REMINDER",
                      UUID.randomUUID())
                  .outcome())
          .isEqualTo(MessageDelivery.Outcome.UNKNOWN);
      assertThat(
              new HttpMessageDelivery(
                      io.micrometer.observation.ObservationRegistry.NOOP,
                      "",
                      "",
                      false,
                      new MockEnvironment())
                  .send(intent, "PUSH", "fixture", "LIVE_STARTED", UUID.randomUUID())
                  .outcome())
          .isEqualTo(MessageDelivery.Outcome.REJECTED);
    } finally {
      server.stop(0);
    }
  }
}
