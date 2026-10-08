package com.ott.member.adapter.inbound.rest;

import com.ott.member.application.MemberSecurityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemberSecurityController {
  private final MemberSecurityService service;

  public MemberSecurityController(MemberSecurityService service) {
    this.service = service;
  }

  public record Command(
      @NotNull UUID operationId,
      @NotNull UUID accountId,
      @NotBlank String action,
      @NotBlank String reason) {}

  @GetMapping("/internal/v1/member/security/accounts/{account}")
  public Map<String, Object> summary(@PathVariable UUID account) {
    return service.summary(account);
  }

  @PostMapping("/internal/v1/member/security/commands")
  public MemberSecurityService.Result command(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Command c) {
    return service.command(
        c.operationId(),
        c.accountId(),
        c.action(),
        c.reason(),
        jwt.getIssuer() + ":" + jwt.getSubject());
  }
}
