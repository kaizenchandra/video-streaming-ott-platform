package com.ott.insights.adapter.inbound.rest;

import com.ott.insights.application.ExperimentService;
import com.ott.insights.application.TelemetryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InsightsController {
  private final TelemetryService telemetry;
  private final ExperimentService experiments;

  public InsightsController(TelemetryService telemetry, ExperimentService experiments) {
    this.telemetry = telemetry;
    this.experiments = experiments;
  }

  public record Consent(@Min(1) long version, boolean analytics, boolean experiments) {}

  public record Batch(
      @NotNull UUID profileId,
      @NotNull UUID deviceId,
      String playbackToken,
      @NotNull List<TelemetryService.Sample> samples) {}

  public record Experiment(
      String name,
      String feature,
      Instant startsAt,
      Instant endsAt,
      int control,
      int variantA,
      int variantB) {}

  public record Configuration(
      @Min(1) long version, boolean enabled, int control, int variantA, int variantB) {}

  @GetMapping("/v1/insights/consent")
  public TelemetryService.Consent consent(@AuthenticationPrincipal Jwt jwt) {
    return telemetry.consent(telemetry.account(jwt.getTokenValue()));
  }

  @PutMapping("/v1/insights/consent")
  public TelemetryService.Consent consent(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Consent c) {
    return telemetry.consent(
        telemetry.account(jwt.getTokenValue()), c.version(), c.analytics(), c.experiments());
  }

  @PostMapping("/v1/telemetry/batches")
  public TelemetryService.Receipt admit(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Batch b) {
    return telemetry.admit(
        jwt.getTokenValue(), b.profileId(), b.deviceId(), b.playbackToken(), b.samples());
  }

  @GetMapping("/v1/admin/analytics/qoe")
  public List<Map<String, Object>> summary(@RequestParam Instant from, @RequestParam Instant to) {
    return telemetry.summary(from, to);
  }

  @PostMapping("/v1/admin/insights/experiments")
  public ExperimentService.Experiment create(
      @AuthenticationPrincipal Jwt jwt, @RequestBody Experiment e) {
    return experiments.create(
        e.name(),
        e.feature(),
        e.startsAt(),
        e.endsAt(),
        e.control(),
        e.variantA(),
        e.variantB(),
        jwt.getIssuer() + ":" + jwt.getSubject());
  }

  @PutMapping("/v1/admin/insights/experiments/{id}")
  public ExperimentService.Experiment configure(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Configuration c) {
    return experiments.configure(
        id,
        c.version(),
        c.enabled(),
        c.control(),
        c.variantA(),
        c.variantB(),
        jwt.getIssuer() + ":" + jwt.getSubject());
  }

  @GetMapping("/v1/admin/insights/experiments")
  public List<ExperimentService.Experiment> list(
      @RequestParam(required = false) UUID after, @RequestParam(defaultValue = "20") int limit) {
    return experiments.list(after, limit);
  }

  @GetMapping("/v1/experiments/{id}/assignment")
  public ExperimentService.Assignment assignment(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return experiments.assignment(jwt.getTokenValue(), id);
  }
}
