package com.ott.experience.adapter.inbound.rest;

import com.ott.experience.application.DiscoveryService;
import com.ott.experience.application.SearchProjection;
import com.ott.experience.application.ViewerAccess;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "ott.search.enabled", havingValue = "true")
public class DiscoveryController {
  private final DiscoveryService discovery;
  private final SearchProjection projection;

  public DiscoveryController(DiscoveryService discovery, SearchProjection projection) {
    this.discovery = discovery;
    this.projection = projection;
  }

  @GetMapping("/v1/search")
  public DiscoveryService.Page search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID profileId,
      @RequestParam String country,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "en") String locale,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String genre,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "20") int limit) {
    return discovery.search(
        new ViewerAccess.Identity(jwt.getTokenValue()),
        profileId,
        country,
        q,
        locale,
        type,
        genre,
        offset,
        limit,
        false);
  }

  @GetMapping("/v1/search/autocomplete")
  public DiscoveryService.Page autocomplete(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID profileId,
      @RequestParam String country,
      @RequestParam String q,
      @RequestParam(defaultValue = "en") String locale,
      @RequestParam(defaultValue = "10") int limit) {
    return discovery.search(
        new ViewerAccess.Identity(jwt.getTokenValue()),
        profileId,
        country,
        q,
        locale,
        null,
        null,
        0,
        limit,
        true);
  }

  @GetMapping("/v1/home")
  public DiscoveryService.Home home(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID profileId,
      @RequestParam String country,
      @RequestParam(defaultValue = "en") String locale,
      @RequestParam(defaultValue = "20") int limit) {
    return discovery.home(
        new ViewerAccess.Identity(jwt.getTokenValue()), profileId, country, locale, limit);
  }

  @GetMapping("/v1/admin/search/rebuild/{id}")
  @PreAuthorize("hasAuthority('SCOPE_search.write')")
  public SearchProjection.RebuildStatus status(
      @org.springframework.web.bind.annotation.PathVariable UUID id) {
    return projection.rebuildStatus(id);
  }

  @PostMapping("/v1/admin/search/rebuild")
  @PreAuthorize("hasAuthority('SCOPE_search.write')")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, UUID> rebuild() {
    return Map.of("jobId", projection.rebuild());
  }
}
