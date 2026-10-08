package com.ott.member.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@AutoConfigureObservability
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {com.ott.member.MemberApplication.class, FoundationIT.FixtureConfiguration.class})
class FoundationIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

  static final RSAKey KEY = key();
  static final HttpServer JWKS = jwks();
  static final java.util.concurrent.BlockingQueue<byte[]> EXPORTED =
      new java.util.concurrent.ArrayBlockingQueue<>(32);
  static final String ISSUER = "http://localhost:" + JWKS.getAddress().getPort() + "/issuer";
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired io.opentelemetry.sdk.trace.SdkTracerProvider tracing;

  @AfterAll
  static void stopJwks() {
    JWKS.stop(0);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("ott.security.issuer", () -> ISSUER);
    r.add(
        "ott.security.jwks-uri", () -> "http://localhost:" + JWKS.getAddress().getPort() + "/jwks");
    r.add("ott.security.audience", () -> "ott-api");
    r.add("ott.outbox.enabled", () -> "false");
    r.add("management.otlp.tracing.export.enabled", () -> "true");
    r.add(
        "management.otlp.tracing.endpoint",
        () -> "http://localhost:" + JWKS.getAddress().getPort() + "/v1/traces");
    r.add("management.tracing.sampling.probability", () -> "1.0");
  }

  static RSAKey key() {
    try {
      return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException("Cannot generate ephemeral test key", e);
    }
  }

  static HttpServer jwks() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/jwks",
          exchange -> {
            byte[] bytes =
                new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) {
              stream.write(bytes);
            }
          });
      server.start();
      server.createContext(
          "/v1/traces",
          exchange -> {
            var input = exchange.getRequestBody();
            if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding")))
              input = new java.util.zip.GZIPInputStream(input);
            try (var body = input) {
              EXPORTED.offer(body.readNBytes(1024 * 1024));
            }
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      return server;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot start test JWKS server", e);
    }
  }

  static String token(String audience, String issuer, Instant expiry, RSAKey key, String scope) {
    var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("viewer-test")
            .audience(java.util.List.of(audience))
            .issuedAt(Instant.now().minusSeconds(300))
            .expiresAt(expiry)
            .claim("scope", scope)
            .build();
    return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
  }

  org.springframework.http.ResponseEntity<String> get(String path, String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  @Test
  void trace_authenticatedRequest_exportsKnownTraceWithoutCredentials() throws Exception {
    EXPORTED.clear();
    String trace = "0123456789abcdef0123456789abcdef";
    String credential = token("ott-api", ISSUER, Instant.now().plusSeconds(60), KEY, "");
    var headers = new HttpHeaders();
    headers.setBearerAuth(credential);
    headers.set("traceparent", "00-" + trace + "-0123456789abcdef-01");
    assertThat(
            http.exchange(
                    "/v1/test-identity", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    tracing.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    byte[] traceBytes = java.util.HexFormat.of().parseHex(trace);
    boolean found = false;
    for (var span = EXPORTED.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        span != null;
        span = EXPORTED.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
      assertThat(new String(span, StandardCharsets.ISO_8859_1)).doesNotContain(credential);
      for (int i = 0; i <= span.length - traceBytes.length; i++) {
        if (java.util.Arrays.equals(
            java.util.Arrays.copyOfRange(span, i, i + traceBytes.length), traceBytes)) found = true;
      }
    }
    assertThat(found).isTrue();
  }

  @Test
  void health_realDatabase_readyAndMigrationApplied() {
    assertThat(
            http.getForEntity("/actuator/health/readiness", String.class).getStatusCode().value())
        .isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void authenticate_missingToken_returnsSafeProblem() {
    var response = http.getForEntity("/v1/test-identity", String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(401);
    assertThat(response.getBody()).contains("UNAUTHENTICATED").doesNotContain("Bearer", "SQL");
  }

  @Test
  void authenticate_validSignedToken_acceptsIdentity() {
    var response =
        get("/v1/test-identity", token("ott-api", ISSUER, Instant.now().plusSeconds(60), KEY, ""));
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).contains("viewer-test");
  }

  @Test
  void authenticate_wrongAudience_rejectsCryptographicToken() {
    assertThat(
            get("/v1/test-identity", token("other", ISSUER, Instant.now().plusSeconds(60), KEY, ""))
                .getStatusCode()
                .value())
        .isEqualTo(401);
  }

  @Test
  void authenticate_wrongIssuer_rejectsCryptographicToken() {
    assertThat(
            get(
                    "/v1/test-identity",
                    token(
                        "ott-api",
                        "https://untrusted.invalid",
                        Instant.now().plusSeconds(60),
                        KEY,
                        ""))
                .getStatusCode()
                .value())
        .isEqualTo(401);
  }

  @Test
  void authenticate_expiredToken_rejects() {
    assertThat(
            get(
                    "/v1/test-identity",
                    token("ott-api", ISSUER, Instant.now().minusSeconds(120), KEY, ""))
                .getStatusCode()
                .value())
        .isEqualTo(401);
  }

  @Test
  void authenticate_foreignSignature_rejects() {
    assertThat(
            get(
                    "/v1/test-identity",
                    token("ott-api", ISSUER, Instant.now().plusSeconds(60), key(), ""))
                .getStatusCode()
                .value())
        .isEqualTo(401);
  }

  @Test
  void scrape_viewerWithoutScope_forbidden() {
    assertThat(
            get(
                    "/actuator/prometheus",
                    token("ott-api", ISSUER, Instant.now().plusSeconds(60), KEY, ""))
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            get(
                    "/actuator/prometheus",
                    token("ott-api", ISSUER, Instant.now().plusSeconds(60), KEY, "metrics.read"))
                .getStatusCode()
                .value())
        .isEqualTo(200);
  }

  @Test
  void transaction_failure_rollsBackOutbox() {
    var id = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        s -> {
                          jdbc.update(
                              "INSERT INTO"
                                  + " outbox_event(event_id,aggregate_id,aggregate_version,topic,payload)"
                                  + " VALUES (?,?,1,'test.foundation','{}'::jsonb)",
                              id,
                              UUID.randomUUID());
                          throw new IllegalStateException("test transaction abort");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_id=?", Integer.class, id))
        .isZero();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixtureConfiguration {
    @Bean
    TestIdentity testIdentity() {
      return new TestIdentity();
    }
  }

  @RestController
  static class TestIdentity {
    @GetMapping("/v1/test-identity")
    Map<String, String> identity(org.springframework.security.core.Authentication auth) {
      return Map.of("subject", auth.getName());
    }
  }
}
