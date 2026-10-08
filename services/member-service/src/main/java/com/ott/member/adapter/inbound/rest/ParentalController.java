package com.ott.member.adapter.inbound.rest;

import com.ott.member.application.ParentalService;
import com.ott.member.domain.model.MemberModels.Identity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ParentalController {
  private final ParentalService service;

  public ParentalController(ParentalService service) {
    this.service = service;
  }

  public record Configuration(
      @Min(0) long version, @Size(max = 8) String currentPin, @Size(max = 8) String newPin) {}

  public record Unlock(
      @NotNull UUID profileId, @NotNull UUID deviceId, @Size(max = 8) String pin) {}

  @GetMapping("/v1/parental-control")
  public ParentalService.Status status(@AuthenticationPrincipal Jwt jwt) {
    return service.status(identity(jwt));
  }

  @PutMapping("/v1/parental-control")
  public ParentalService.Status configure(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Configuration c) {
    return service.configure(identity(jwt), c.version(), c.currentPin(), c.newPin());
  }

  @PostMapping("/v1/profile-selections")
  public ParentalService.Grant unlock(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Unlock u) {
    return service.unlock(identity(jwt), u.profileId(), u.deviceId(), u.pin());
  }

  static Identity identity(Jwt jwt) {
    Object at = jwt.getClaims().get("auth_time");
    return new Identity(
        jwt.getIssuer().toString(),
        jwt.getSubject(),
        at instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null);
  }
}
