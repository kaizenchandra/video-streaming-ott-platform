package com.ott.playback.adapter.outbound.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ott.playback.application.Eligibility;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class OwnerTracingTest {
  @Test
  void check_parallelOwners_propagatesOneTraceWithoutCredentialBaggage() throws Exception {
    var provider = SdkTracerProvider.builder().build();
    var otel = provider.get("owner-test");
    var tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> {});
    var propagator =
        new OtelPropagator(
            ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel);
    var observations = ObservationRegistry.create();
    observations
        .observationConfig()
        .observationHandler(
            new io.micrometer.observation.ObservationHandler
                .FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));
    var traces = new CopyOnWriteArrayList<String>();
    var baggage = new CopyOnWriteArrayList<String>();
    UUID account = UUID.randomUUID();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
    var threads = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(threads);
    server.createContext(
        "/",
        exchange -> {
          traces.add(exchange.getRequestHeaders().getFirst("traceparent"));
          if (exchange.getRequestHeaders().getFirst("baggage") != null)
            baggage.add(exchange.getRequestHeaders().getFirst("baggage"));
          String path = exchange.getRequestURI().getPath();
          String body =
              path.equals("/v1/accounts/me")
                  ? "{\"id\":\"" + account + "\"}"
                  : path.contains("member")
                      ? "{\"allowed\":true,\"maturity\":18,\"securityEpoch\":1}"
                      : path.contains("rights")
                          ? "{\"allowed\":true,\"expiresAt\":\"2099-01-01T00:00:00Z\",\"originPrefix\":\"assets/example/v1/\",\"offlineAllowed\":true}"
                          : "{\"active\":true,\"maxStreams\":2,\"offlineAllowed\":true,\"expiresAt\":\"2099-01-01T00:00:00Z\"}";
          var bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    var url = "http://127.0.0.1:" + server.getAddress().getPort();
    var owners = new OwnerEligibility(observations, url, url, url, () -> "workload-secret-test");
    var observation = Observation.start("playback-test", observations);
    try (var scope = observation.openScope()) {
      String traceId = tracer.currentSpan().context().traceId();
      var result =
          owners.check(
              new Eligibility.Identity("issuer", "subject", "viewer-secret-test"),
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              "IN",
              false);
      assertThat(result.accountId()).isEqualTo(account);
      assertThat(traces)
          .hasSize(4)
          .allSatisfy(value -> assertThat(value).contains("-" + traceId + "-"));
      assertThat(baggage).isEmpty();
    } finally {
      observation.stop();
      owners.close();
      server.stop(0);
      threads.close();
      provider.close();
    }
  }
}
