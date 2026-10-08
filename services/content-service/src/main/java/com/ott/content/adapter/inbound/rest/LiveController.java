package com.ott.content.adapter.inbound.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.content.application.LiveService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LiveController {
  private final LiveService live;
  private final LiveFeedVerifier verifier;
  private final ObjectMapper json;

  public LiveController(LiveService live, LiveFeedVerifier verifier, ObjectMapper json) {
    this.live = live;
    this.verifier = verifier;
    this.json = json;
  }

  public record Create(
      @NotNull UUID contentId,
      @NotBlank @Size(max = 120) String league,
      @NotBlank @Size(max = 120) String homeTeam,
      @NotBlank @Size(max = 120) String awayTeam,
      @NotBlank String referenceCountry,
      @NotNull Instant startsAt,
      @NotNull Instant endsAt,
      @NotBlank @Size(max = 255) String packagerSubject,
      @Min(0) @Max(7200) int dvrSeconds) {}

  public record Transition(@Min(1) long version, @NotBlank String state, UUID replayContentId) {}

  public record Reschedule(
      @Min(1) long version, @NotNull Instant startsAt, @NotNull Instant endsAt) {}

  public record Version(@Min(1) long version) {}

  public record Highlight(
      @NotNull UUID contentId,
      @NotBlank @Size(max = 160) String label,
      @Min(0) long startMillis,
      @Min(1) long endMillis) {}

  public record Check(
      @NotBlank String mode,
      @NotBlank String country,
      @Min(0) @Max(18) int maturity,
      Instant seekAt) {}

  public record Fixture(
      UUID id,
      UUID contentId,
      String league,
      String homeTeam,
      String awayTeam,
      Instant startsAt,
      Instant endsAt,
      String state,
      long version,
      int dvrSeconds,
      boolean sourceAvailable,
      List<LiveService.Track> commentaries,
      List<String> subtitles,
      UUID replayContentId) {}

  public record Schedule(List<Fixture> items, String nextCursor) {}

  public record FeedReceipt(long epoch, long sequence, boolean sourceAvailable) {}

  @PostMapping("/v1/admin/live/events")
  @ResponseStatus(HttpStatus.CREATED)
  public LiveService.Event create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Create in) {
    return live.create(
        in.contentId(),
        in.league(),
        in.homeTeam(),
        in.awayTeam(),
        in.referenceCountry(),
        in.startsAt(),
        in.endsAt(),
        in.packagerSubject(),
        in.dvrSeconds(),
        actor(jwt));
  }

  @GetMapping("/v1/admin/live/events/{id}")
  public LiveService.Event admin(@PathVariable UUID id) {
    return live.get(id);
  }

  @PostMapping("/v1/admin/live/events/{id}/transitions")
  public LiveService.Command transition(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Transition in) {
    return live.transition(id, in.version(), in.state(), key, in.replayContentId(), actor(jwt));
  }

  @PostMapping("/v1/admin/live/events/{id}/schedule")
  public LiveService.Command reschedule(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Reschedule in) {
    return live.reschedule(id, in.version(), key, in.startsAt(), in.endsAt(), actor(jwt));
  }

  @PostMapping("/v1/admin/live/events/{id}/failover")
  public LiveService.Command failover(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Version in) {
    return live.failover(id, in.version(), key, actor(jwt));
  }

  @PostMapping("/v1/admin/live/events/{id}/highlights")
  public LiveService.Highlight highlight(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Highlight in) {
    return live.highlight(
        id, in.contentId(), in.label(), in.startMillis(), in.endMillis(), actor(jwt));
  }

  @GetMapping("/v1/live/events")
  public Schedule schedule(
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    var result = live.schedule(from, to, cursor, limit);
    return new Schedule(result.items().stream().map(this::fixture).toList(), result.nextCursor());
  }

  @GetMapping("/v1/live/events/{id}")
  public Fixture event(@PathVariable UUID id) {
    return fixture(live.get(id));
  }

  @GetMapping("/v1/live/events/{id}/highlights")
  public List<LiveService.Highlight> highlights(@PathVariable UUID id) {
    return live.highlights(id);
  }

  @PostMapping("/internal/v1/live/events/{id}/playback/check")
  public LiveService.Decision check(@PathVariable UUID id, @Valid @RequestBody Check in) {
    return live.playback(id, in.mode(), in.country(), in.maturity(), in.seekAt());
  }

  @PostMapping("/internal/v1/live/feeds/{id}/heartbeat")
  public FeedReceipt feed(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestBody byte[] raw,
      @RequestHeader("X-Feed-Timestamp") String at,
      @RequestHeader("X-Feed-Signature") String signature) {
    verifier.verify(raw, at, signature);
    try {
      var value = json.readValue(raw, LiveService.Feed.class);
      var event =
          live.heartbeat(
              id,
              jwt.getSubject(),
              value,
              java.util.HexFormat.of()
                  .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
      return new FeedReceipt(event.feedEpoch(), event.feedSequence(), live.sourceHealthy(event));
    } catch (java.io.IOException e) {
      throw new com.ott.content.application.CatalogService.Failure(
          400, "INVALID_FEED_PROOF", "Invalid feed proof");
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Fixture fixture(LiveService.Event e) {
    return new Fixture(
        e.id(),
        e.contentId(),
        e.league(),
        e.homeTeam(),
        e.awayTeam(),
        e.startsAt(),
        e.endsAt(),
        e.state(),
        e.version(),
        e.dvrSeconds(),
        live.sourceHealthy(e),
        e.commentaries(),
        e.subtitles(),
        e.replayContentId());
  }

  private String actor(Jwt jwt) {
    return jwt.getIssuer() + ":" + jwt.getSubject();
  }
}
