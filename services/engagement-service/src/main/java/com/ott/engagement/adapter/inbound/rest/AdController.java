package com.ott.engagement.adapter.inbound.rest;

import com.ott.engagement.application.AdService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdController {
  private final AdService service;

  public AdController(AdService service) {
    this.service = service;
  }

  public record Campaign(
      @NotBlank @Size(max = 120) String name,
      @NotBlank String country,
      @Min(0) @Max(18) int maturity,
      @NotBlank String placement,
      @NotNull Instant startsAt,
      @NotNull Instant endsAt,
      @Min(1) @Max(20) int dailyCap,
      @NotBlank @Size(max = 2048) String creativeUrl,
      @Min(1000) @Max(120000) int durationMillis) {}

  public record Configuration(@Min(1) long version, @NotNull Boolean enabled) {}

  public record Decision(
      @NotNull UUID profileId,
      @NotNull UUID deviceId,
      @NotBlank @Size(max = 8192) String playbackToken,
      @NotBlank String placement,
      @NotBlank String adapter) {}

  public record Report(
      @NotNull UUID profileId,
      @NotNull UUID deviceId,
      @NotBlank @Size(max = 8192) String playbackToken,
      @NotNull UUID decisionId,
      @NotNull UUID eventId,
      @Min(0) @Max(4) int quartile,
      @NotNull Instant occurredAt) {}

  @PostMapping("/v1/admin/ads/campaigns")
  @ResponseStatus(HttpStatus.CREATED)
  public AdService.Campaign create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Campaign c) {
    return service.create(
        c.name(),
        c.country(),
        c.maturity(),
        c.placement(),
        c.startsAt(),
        c.endsAt(),
        c.dailyCap(),
        c.creativeUrl(),
        c.durationMillis(),
        actor(jwt));
  }

  @PutMapping("/v1/admin/ads/campaigns/{id}")
  public AdService.Campaign configure(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Configuration c) {
    return service.configure(id, c.version(), c.enabled(), actor(jwt));
  }

  @GetMapping("/v1/admin/ads/campaigns")
  public List<AdService.Campaign> campaigns(
      @RequestParam(required = false) UUID after, @RequestParam(defaultValue = "20") int limit) {
    return service.campaigns(after, limit);
  }

  @PostMapping("/v1/ads/decision")
  public AdService.Result decide(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Decision d) {
    return service.decide(
        jwt.getTokenValue(),
        d.profileId(),
        d.deviceId(),
        d.playbackToken(),
        d.placement(),
        d.adapter(),
        key);
  }

  @PostMapping("/v1/ads/events")
  public AdService.Receipt report(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Report r) {
    return service.report(
        jwt.getTokenValue(),
        r.profileId(),
        r.deviceId(),
        r.playbackToken(),
        r.decisionId(),
        r.eventId(),
        r.quartile(),
        r.occurredAt());
  }

  private String actor(Jwt jwt) {
    return jwt.getIssuer() + ":" + jwt.getSubject();
  }
}
