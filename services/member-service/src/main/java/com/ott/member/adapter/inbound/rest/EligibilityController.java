package com.ott.member.adapter.inbound.rest;

import com.ott.member.application.MemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EligibilityController {
  private final MemberService service;

  public EligibilityController(MemberService service) {
    this.service = service;
  }

  public record Query(@NotNull UUID accountId, @NotNull UUID profileId, @NotNull UUID deviceId) {}

  @PostMapping("/internal/v1/member/eligibility")
  public MemberService.Eligibility eligibility(
      @Valid @RequestBody Query query,
      @RequestHeader(value = "X-Profile-Authorization", required = false) String proof) {
    return service.deviceEligibility(
        query.accountId(), query.profileId(), query.deviceId(), proof, false);
  }

  @PostMapping("/internal/v1/member/issued-access")
  public MemberService.Eligibility issued(@Valid @RequestBody Query q) {
    return service.deviceEligibility(q.accountId(), q.profileId(), q.deviceId(), null, true);
  }
}
