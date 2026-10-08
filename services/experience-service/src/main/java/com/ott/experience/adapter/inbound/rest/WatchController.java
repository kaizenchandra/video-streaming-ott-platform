package com.ott.experience.adapter.inbound.rest;

import com.ott.experience.application.ViewerAccess;
import com.ott.experience.application.WatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WatchController {
  private final WatchService service;

  public WatchController(WatchService service) {
    this.service = service;
  }

  public record Observation(
      @NotNull UUID eventId,
      @NotNull UUID contentId,
      @NotNull UUID deviceId,
      @NotBlank @Size(max = 4096) String grantToken,
      boolean offline,
      @Min(1) long sequence,
      @Min(0) long positionMillis,
      @Min(1) @Max(86400000) long durationMillis) {}

  public record Stars(@Min(1) @Max(5) @NotNull Integer rating) {}

  public record Thumb(@NotBlank @Pattern(regexp = "LIKE|DISLIKE") String reaction) {}

  @PostMapping("/v1/profiles/{profile}/progress")
  public WatchService.Receipt observe(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID profile,
      @Valid @RequestBody Observation in) {
    return service.observe(
        identity(jwt),
        new WatchService.Observation(
            in.eventId(),
            profile,
            in.contentId(),
            in.deviceId(),
            in.grantToken(),
            in.offline(),
            in.sequence(),
            in.positionMillis(),
            in.durationMillis()));
  }

  @GetMapping("/v1/profiles/{profile}/progress/{content}")
  public WatchService.Progress resume(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID profile, @PathVariable UUID content) {
    return service.resume(identity(jwt), profile, content);
  }

  @GetMapping("/v1/profiles/{profile}/continue-watching")
  public WatchService.Page<WatchService.Progress> continuing(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID profile,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return service.recent(identity(jwt), profile, cursor, limit, true);
  }

  @GetMapping("/v1/profiles/{profile}/history")
  public WatchService.Page<WatchService.History> history(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID profile,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return service.history(identity(jwt), profile, cursor, limit);
  }

  @GetMapping("/v1/profiles/{profile}/watchlist")
  public WatchService.Page<WatchService.Collection> list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID profile,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return service.list(identity(jwt), profile, cursor, limit);
  }

  @PutMapping("/v1/profiles/{profile}/watchlist/{content}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void add(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID profile, @PathVariable UUID content) {
    service.add(identity(jwt), profile, content);
  }

  @DeleteMapping("/v1/profiles/{profile}/watchlist/{content}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID profile, @PathVariable UUID content) {
    service.remove(identity(jwt), profile, content);
  }

  @PutMapping("/v1/profiles/{profile}/ratings/{content}")
  public WatchService.Reaction rate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID profile,
      @PathVariable UUID content,
      @Valid @RequestBody Stars in) {
    return service.rate(identity(jwt), profile, content, in.rating());
  }

  @DeleteMapping("/v1/profiles/{profile}/ratings/{content}")
  public WatchService.Reaction unrate(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID profile, @PathVariable UUID content) {
    return service.rate(identity(jwt), profile, content, null);
  }

  @PutMapping("/v1/profiles/{profile}/reactions/{content}")
  public WatchService.Reaction react(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID profile,
      @PathVariable UUID content,
      @Valid @RequestBody Thumb in) {
    return service.react(identity(jwt), profile, content, in.reaction());
  }

  @DeleteMapping("/v1/profiles/{profile}/reactions/{content}")
  public WatchService.Reaction unreact(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID profile, @PathVariable UUID content) {
    return service.react(identity(jwt), profile, content, null);
  }

  private ViewerAccess.Identity identity(Jwt jwt) {
    return new ViewerAccess.Identity(jwt.getTokenValue());
  }
}
