package com.ott.content.infrastructure;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/liveness", "/actuator/health/readiness")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .hasAuthority("SCOPE_metrics.read")
                    .requestMatchers("/internal/v1/live/feeds/**")
                    .hasAuthority("SCOPE_live.feed")
                    .requestMatchers("/internal/**")
                    .hasAuthority("SCOPE_content.internal")
                    .requestMatchers("/v1/admin/live/**")
                    .hasAuthority("SCOPE_live.write")
                    .requestMatchers("/v1/admin/**")
                    .hasAuthority("SCOPE_catalog.write")
                    .requestMatchers("/v1/catalog/**", "/v1/catalog", "/v1/live/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (req, res, e) -> {
                          res.setStatus(401);
                          res.setContentType("application/problem+json");
                          res.getWriter()
                              .write(
                                  "{\"type\":\"urn:ott:problem:unauthenticated\",\"title\":\"Authentication"
                                      + " required\",\"status\":401,\"code\":\"UNAUTHENTICATED\"}");
                        }))
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (req, res, x) -> {
                      res.setStatus(403);
                      res.setContentType("application/problem+json");
                      res.getWriter()
                          .write(
                              "{\"type\":\"urn:ott:problem:forbidden\",\"title\":\"Access"
                                  + " denied\",\"status\":403,\"code\":\"FORBIDDEN\"}");
                    }))
        .build();
  }

  @Bean
  NimbusJwtDecoder jwt(
      @Value("${ott.security.issuer}") String issuer,
      @Value("${ott.security.jwks-uri}") String jwks,
      @Value("${ott.security.audience}") String audience) {
    var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            jwt ->
                jwt.getAudience().contains(audience)
                        && jwt.getSubject() != null
                        && !jwt.getSubject().isBlank()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Invalid subject or audience", null))));
    return decoder;
  }
}
