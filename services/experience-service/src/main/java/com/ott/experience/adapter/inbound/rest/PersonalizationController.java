package com.ott.experience.adapter.inbound.rest;

import com.ott.experience.application.RecommendationSignals;
import com.ott.experience.application.ViewerAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PersonalizationController {
  private final RecommendationSignals signals;

  public PersonalizationController(RecommendationSignals signals) {
    this.signals = signals;
  }

  public record Preference(@NotNull Boolean enabled) {}

  @GetMapping("/v1/profiles/{profile}/personalization")
  public RecommendationSignals.Preference preference(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID profile) {
    return signals.preference(new ViewerAccess.Identity(jwt.getTokenValue()), profile);
  }

  @PutMapping("/v1/profiles/{profile}/personalization")
  public RecommendationSignals.Preference preference(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID profile,
      @Valid @RequestBody Preference in) {
    return signals.preference(
        new ViewerAccess.Identity(jwt.getTokenValue()), profile, in.enabled());
  }
}
