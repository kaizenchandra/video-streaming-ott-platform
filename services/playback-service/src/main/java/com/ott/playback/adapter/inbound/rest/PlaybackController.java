package com.ott.playback.adapter.inbound.rest;

import com.ott.playback.adapter.outbound.security.GeoAssertions;
import com.ott.playback.application.Eligibility;
import com.ott.playback.application.PlaybackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaybackController {
  private final com.ott.playback.application.OfflineService offline;
  private final PlaybackService service;
  private final GeoAssertions geo;
  private final Eligibility eligibility;

  public PlaybackController(
      PlaybackService service,
      GeoAssertions geo,
      Eligibility eligibility,
      com.ott.playback.application.OfflineService offline) {
    this.offline = offline;
    this.service = service;
    this.geo = geo;
    this.eligibility = eligibility;
  }

  public record Start(
      @NotNull UUID profileId,
      @NotNull UUID deviceId,
      @NotNull UUID contentId,
      @NotBlank String country) {}

  public record Heartbeat(@NotNull UUID deviceId, @NotBlank String country) {}

  public record Token(@NotBlank @Size(max = 4096) String token, @NotNull UUID deviceId) {}

  public record LiveStart(
      @NotNull UUID profileId,
      @NotNull UUID deviceId,
      @NotBlank String country,
      @NotBlank String mode,
      java.time.Instant seekAt) {}

  @PostMapping("/v1/live/events/{event}/playback")
  public PlaybackService.Authorization live(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID event,
      @Valid @RequestBody LiveStart in,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader("X-Geo-Assertion") String assertion,
      @RequestHeader(value = "X-Profile-Authorization", required = false) String proof) {
    geo.verify(
        jwt.getIssuer().toString(), jwt.getSubject(), in.deviceId(), in.country(), assertion);
    return service.startLive(
        identity(jwt, proof),
        in.profileId(),
        in.deviceId(),
        event,
        in.country(),
        key,
        in.mode(),
        in.seekAt());
  }

  @PostMapping("/v1/playback/sessions")
  public PlaybackService.Authorization start(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody Start in,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader("X-Geo-Assertion") String assertion,
      @RequestHeader(value = "X-Profile-Authorization", required = false) String proof) {
    geo.verify(
        jwt.getIssuer().toString(), jwt.getSubject(), in.deviceId(), in.country(), assertion);
    return service.start(
        identity(jwt, proof), in.profileId(), in.deviceId(), in.contentId(), in.country(), key);
  }

  @PostMapping("/v1/playback/sessions/{id}/heartbeat")
  public PlaybackService.Authorization heartbeat(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Heartbeat in,
      @RequestHeader("X-Geo-Assertion") String assertion,
      @RequestHeader(value = "X-Profile-Authorization", required = false) String proof) {
    var identity = identity(jwt, proof);
    var session = service.owned(eligibility.account(identity), id);
    if (!in.deviceId().equals(session.deviceId()))
      throw new com.ott.playback.application.PlaybackFailure(
          403, "DEVICE_NOT_ALLOWED", "Device binding required");
    geo.verify(
        jwt.getIssuer().toString(), jwt.getSubject(), in.deviceId(), in.country(), assertion);
    return service.heartbeat(identity, id, in.country());
  }

  @DeleteMapping("/v1/playback/sessions/{id}")
  public void end(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.end(identity(jwt), id);
  }

  @PostMapping("/internal/v1/playback/ads/context")
  public PlaybackService.AdContext adContext(@Valid @RequestBody Token in) {
    return service.adContext(in.token(), in.deviceId());
  }

  @PostMapping("/internal/v1/playback/token/check")
  public PlaybackService.GrantContext validate(@Valid @RequestBody Token in) {
    return service.context(in.token(), in.deviceId());
  }

  @PostMapping("/v1/downloads")
  public com.ott.playback.application.OfflineService.Authorization download(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody Start in,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader("X-Geo-Assertion") String assertion,
      @RequestHeader(value = "X-Profile-Authorization", required = false) String proof) {
    geo.verify(
        jwt.getIssuer().toString(), jwt.getSubject(), in.deviceId(), in.country(), assertion);
    return offline.authorize(
        identity(jwt, proof), in.profileId(), in.deviceId(), in.contentId(), in.country(), key);
  }

  @DeleteMapping("/v1/downloads/{id}")
  public void revokeDownload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    offline.revoke(identity(jwt), id);
  }

  @PostMapping("/internal/v1/downloads/token/check")
  public Map<String, Object> validateDownload(@Valid @RequestBody Token in) {
    var context = offline.context(in.token(), in.deviceId());
    return Map.of(
        "downloadId",
        context.sessionId(),
        "sessionId",
        context.sessionId(),
        "accountId",
        context.accountId(),
        "profileId",
        context.profileId(),
        "contentId",
        context.contentId(),
        "deviceId",
        context.deviceId(),
        "epoch",
        context.epoch());
  }

  private Eligibility.Identity identity(Jwt jwt) {
    return new Eligibility.Identity(
        jwt.getIssuer().toString(), jwt.getSubject(), jwt.getTokenValue());
  }

  private Eligibility.Identity identity(Jwt jwt, String proof) {
    if (proof != null && !proof.matches("[A-Za-z0-9_-]{43}"))
      throw new com.ott.playback.application.PlaybackFailure(
          400, "INVALID_PROFILE_AUTHORIZATION", "Invalid profile selection");
    return new Eligibility.Identity(
        jwt.getIssuer().toString(), jwt.getSubject(), jwt.getTokenValue(), proof);
  }
}
