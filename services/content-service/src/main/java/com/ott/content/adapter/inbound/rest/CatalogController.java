package com.ott.content.adapter.inbound.rest;

import com.ott.content.application.CatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
public class CatalogController {
  private final CatalogService service;

  public CatalogController(CatalogService service) {
    this.service = service;
  }

  public record Create(
      @NotBlank String type,
      UUID parentId,
      @Min(0) @Max(18) int maturity,
      @NotBlank @Size(max = 35) String locale,
      @NotBlank @Size(max = 200) String title,
      @NotNull @Size(max = 4000) String synopsis) {}

  public record Localization(
      @NotBlank @Size(max = 35) String locale,
      @NotBlank @Size(max = 200) String title,
      @NotNull @Size(max = 4000) String synopsis,
      @Min(1) long version) {}

  public record Version(@Min(1) long version, @Min(1) long assetVersion, @NotBlank String locale) {}

  public record Revision(@Min(1) long version, @NotBlank String locale) {}

  public record Grant(
      @NotBlank String country,
      @NotNull Instant startAt,
      @NotNull Instant endAt,
      boolean offlineAllowed) {}

  public record Check(
      @NotNull UUID contentId,
      @NotBlank String country,
      @Min(0) @Max(18) int maturity,
      boolean offline) {}

  public record Taxonomy(
      @Min(1) long version,
      @NotNull @Size(max = 10) java.util.List<String> genres,
      @NotNull @Size(max = 20) java.util.List<String> actors,
      java.time.LocalDate releaseDate,
      @NotBlank String locale) {}

  public record BatchCheck(
      @NotNull @Size(max = 100) java.util.List<@NotNull UUID> contentIds,
      @NotBlank String country,
      @Min(0) @Max(18) int maturity) {}

  @PutMapping("/v1/admin/catalog/{id}/taxonomy")
  public CatalogService.Item taxonomy(@PathVariable UUID id, @Valid @RequestBody Taxonomy in) {
    return service.taxonomy(
        id, in.version(), in.genres(), in.actors(), in.releaseDate(), in.locale());
  }

  @GetMapping("/internal/v1/catalog/{id}/versions/{version}")
  public CatalogService.Snapshot snapshot(@PathVariable UUID id, @PathVariable long version) {
    return service.snapshot(id, version);
  }

  @GetMapping("/internal/v1/catalog/snapshots")
  public CatalogService.Snapshots snapshots(
      @RequestParam(required = false) UUID after, @RequestParam(defaultValue = "100") int limit) {
    return service.snapshots(after, limit);
  }

  @PostMapping("/internal/v1/rights/batch")
  public java.util.List<UUID> permitted(@Valid @RequestBody BatchCheck in) {
    return service.permitted(in.contentIds(), in.country(), in.maturity());
  }

  @GetMapping("/v1/catalog")
  public CatalogService.Page browse(
      @RequestParam(defaultValue = "en") String locale,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "20") int limit) {
    return service.browse(locale, type, after, limit);
  }

  @GetMapping("/v1/catalog/{id}")
  public CatalogService.Item get(
      @PathVariable UUID id, @RequestParam(defaultValue = "en") String locale) {
    return service.get(id, locale, true);
  }

  @PostMapping("/v1/admin/catalog")
  @ResponseStatus(HttpStatus.CREATED)
  public CatalogService.Item create(@Valid @RequestBody Create in) {
    return service.create(
        in.type(), in.parentId(), in.maturity(), in.locale(), in.title(), in.synopsis());
  }

  @PutMapping("/v1/admin/catalog/{id}/localizations")
  public CatalogService.Item localize(@PathVariable UUID id, @Valid @RequestBody Localization in) {
    return service.localize(id, in.locale(), in.title(), in.synopsis(), in.version());
  }

  @PostMapping("/v1/admin/catalog/{id}/versions")
  public CatalogService.Item revise(@PathVariable UUID id, @Valid @RequestBody Revision in) {
    return service.revise(id, in.version(), in.locale());
  }

  @PostMapping("/v1/admin/catalog/{id}/review")
  public CatalogService.Item review(@PathVariable UUID id, @Valid @RequestBody Revision in) {
    return service.review(id, in.version(), in.locale());
  }

  @PostMapping("/v1/admin/catalog/{id}/publish")
  @PreAuthorize("hasAuthority('SCOPE_catalog.publish')")
  public CatalogService.Item publish(@PathVariable UUID id, @Valid @RequestBody Version in) {
    return service.publish(id, in.version(), in.assetVersion(), in.locale());
  }

  @PostMapping("/v1/admin/catalog/{id}/rights")
  @ResponseStatus(HttpStatus.CREATED)
  public CatalogService.Rights grant(@PathVariable UUID id, @Valid @RequestBody Grant in) {
    return service.grant(id, in.country(), in.startAt(), in.endAt(), in.offlineAllowed());
  }

  @DeleteMapping("/v1/admin/rights/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable UUID id) {
    service.revokeRights(id);
  }

  @PostMapping("/internal/v1/rights/check")
  public CatalogService.Decision check(@Valid @RequestBody Check in) {
    return service.rights(in.contentId(), in.country(), in.maturity(), in.offline());
  }
}
