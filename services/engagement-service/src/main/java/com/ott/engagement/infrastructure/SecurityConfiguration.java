package com.ott.engagement.infrastructure;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .hasAuthority("SCOPE_metrics.read")
                    .requestMatchers("/internal/**")
                    .hasAuthority("SCOPE_engagement.internal")
                    .requestMatchers("/v1/admin/**")
                    .hasAuthority("SCOPE_ads.write")
                    .requestMatchers("/v1/**")
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
                          res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                          res.getWriter()
                              .write(
                                  "{\"type\":\"urn:ott:problem:unauthenticated\",\"title\":\"Authentication"
                                      + " required\",\"status\":401,\"code\":\"UNAUTHENTICATED\"}");
                        }))
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (req, res, ex) -> {
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
  NimbusJwtDecoder jwtDecoder(
      @Value("${ott.security.issuer}") String issuer,
      @Value("${ott.security.jwks-uri}") String jwksUri,
      @Value("${ott.security.audience}") String audience) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            audienceValidator(audience),
            subjectValidator()));
    return decoder;
  }

  public static OAuth2TokenValidator<Jwt> subjectValidator() {
    return jwt ->
        jwt.getSubject() != null && !jwt.getSubject().isBlank() && jwt.getSubject().length() <= 255
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Subject required", null));
  }

  public static OAuth2TokenValidator<Jwt> audienceValidator(String expected) {
    return jwt ->
        jwt.getAudience().contains(expected)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Required audience missing", null));
  }
}
