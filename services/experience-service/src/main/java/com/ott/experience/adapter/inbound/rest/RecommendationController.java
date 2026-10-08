package com.ott.experience.adapter.inbound.rest;

import com.ott.experience.application.RecommendationService;
import com.ott.experience.application.ViewerAccess;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "ott.recommendations.enabled", havingValue = "true")
public class RecommendationController {
  private final RecommendationService recommendations;

  public RecommendationController(RecommendationService recommendations) {
    this.recommendations = recommendations;
  }

  @GetMapping("/v1/recommendations")
  public RecommendationService.Recommendations get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID profileId,
      @RequestParam String country,
      @RequestParam(defaultValue = "en") String locale,
      @RequestParam(required = false) UUID seedContentId,
      @RequestParam(defaultValue = "10") int limit) {
    return recommendations.recommend(
        new ViewerAccess.Identity(jwt.getTokenValue()),
        profileId,
        country,
        locale,
        seedContentId,
        limit);
  }
}
