package com.ott.member.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTest {
  @Test
  void validateAudience_wrongAudience_deniesToken() {
    var jwt =
        Jwt.withTokenValue("test-only")
            .header("alg", "RS256")
            .subject("viewer")
            .claim("aud", "another-api")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    assertThat(SecurityConfiguration.audienceValidator("ott-api").validate(jwt).hasErrors())
        .isTrue();
  }

  @Test
  void validateAudience_correctAudience_allowsToken() {
    var jwt =
        Jwt.withTokenValue("test-only")
            .header("alg", "RS256")
            .subject("viewer")
            .claim("aud", "ott-api")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    assertThat(SecurityConfiguration.audienceValidator("ott-api").validate(jwt).hasErrors())
        .isFalse();
  }
}
