package com.ott.journey;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

final class OidcFixture implements AutoCloseable {
  private final RSAKey key;
  private final HttpServer server;

  OidcFixture() {
    try {
      key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/jwks",
          exchange -> {
            byte[] bytes =
                new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) {
              stream.write(bytes);
            }
          });
      server.createContext(
          "/token",
          exchange -> {
            String form =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!form.contains("grant_type=client_credentials")
                || (!form.contains("client_id=playback-test")
                    && !form.contains("client_id=operations-test"))
                || !form.contains("client_secret=fixture-secret")) {
              exchange.sendResponseHeaders(401, -1);
              exchange.close();
              return;
            }
            String jwt =
                token(
                    form.contains("client_id=operations-test")
                        ? "operations-service"
                        : "playback-service",
                    form.contains("client_id=operations-test")
                        ? "member.security commerce.security"
                        : "member.internal content.internal commerce.internal playback.internal",
                    false);
            byte[] bytes =
                ("{\"access_token\":\"" + jwt + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) {
              stream.write(bytes);
            }
          });
      server.start();
    } catch (java.io.IOException | com.nimbusds.jose.JOSEException e) {
      throw new IllegalStateException("Cannot create isolated OIDC fixture", e);
    }
  }

  String issuer() {
    return "http://localhost:" + server.getAddress().getPort() + "/issuer";
  }

  String tokenEndpoint() {
    return "http://localhost:" + server.getAddress().getPort() + "/token";
  }

  String jwks() {
    return "http://localhost:" + server.getAddress().getPort() + "/jwks";
  }

  String token(String subject, String scope, boolean recentAuth) {
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer())
            .subject(subject)
            .audience(List.of("ott-api"))
            .issuedAt(Instant.now().minusSeconds(2))
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("scope", scope);
    if (recentAuth) claims.claim("auth_time", Instant.now().getEpochSecond());
    return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)))
        .encode(JwtEncoderParameters.from(claims.build()))
        .getTokenValue();
  }

  public void close() {
    server.stop(0);
  }
}
