package com.ott.member.adapter.inbound.rest;

import com.ott.member.application.MemberService;
import com.ott.member.domain.model.MemberModels.Account;
import com.ott.member.domain.model.MemberModels.Device;
import com.ott.member.domain.model.MemberModels.Identity;
import com.ott.member.domain.model.MemberModels.Profile;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemberController {
  private final MemberService service;

  public MemberController(MemberService service) {
    this.service = service;
  }

  public record ProfileInput(
      @NotBlank @Size(max = 80) String name,
      @NotNull Boolean kids,
      @Min(0) @Max(18) int maturity,
      @NotBlank @Size(max = 35) String locale,
      @Min(0) long version) {}

  public record DeviceInput(
      @NotBlank @Size(min = 8, max = 128) String installationId,
      @NotBlank @Size(max = 80) String name,
      @NotNull @Size(min = 1, max = 3) List<String> codecs,
      @NotNull @Size(max = 3) List<String> drm) {}

  @PutMapping("/v1/accounts/me")
  public Account provision(@AuthenticationPrincipal Jwt jwt) {
    return service.provision(identity(jwt));
  }

  @GetMapping("/v1/accounts/me")
  public Account account(@AuthenticationPrincipal Jwt jwt) {
    return service.account(identity(jwt));
  }

  @GetMapping("/v1/profiles")
  public List<Profile> profiles(@AuthenticationPrincipal Jwt jwt) {
    return service.profiles(identity(jwt));
  }

  @PostMapping("/v1/profiles")
  @ResponseStatus(HttpStatus.CREATED)
  public Profile createProfile(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ProfileInput in,
      @RequestHeader(value = "X-Parental-PIN", required = false) String pin) {
    return service.createProfile(
        identity(jwt), in.name(), in.kids(), in.maturity(), in.locale(), pin);
  }

  @PutMapping("/v1/profiles/{id}")
  public Profile update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ProfileInput in,
      @RequestHeader(value = "X-Parental-PIN", required = false) String pin) {
    return service.updateProfile(
        identity(jwt), id, in.name(), in.kids(), in.maturity(), in.locale(), in.version(), pin);
  }

  @GetMapping("/v1/devices")
  public List<Device> devices(@AuthenticationPrincipal Jwt jwt) {
    return service.devices(identity(jwt));
  }

  @PostMapping("/v1/devices")
  @ResponseStatus(HttpStatus.CREATED)
  public Device register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceInput in) {
    return service.registerDevice(
        identity(jwt), in.installationId(), in.name(), in.codecs(), in.drm());
  }

  @DeleteMapping("/v1/devices/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.revokeDevice(identity(jwt), id);
  }

  private static Identity identity(Jwt jwt) {
    Object authTime = jwt.getClaims().get("auth_time");
    Instant at = authTime instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null;
    return new Identity(jwt.getIssuer().toString(), jwt.getSubject(), at);
  }
}
