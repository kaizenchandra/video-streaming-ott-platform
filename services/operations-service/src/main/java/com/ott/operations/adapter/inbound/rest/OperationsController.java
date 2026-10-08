package com.ott.operations.adapter.inbound.rest;

import com.ott.operations.application.OperationsFailure;
import com.ott.operations.application.OperationsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
public class OperationsController {
  private final OperationsService service;

  public OperationsController(OperationsService service) {
    this.service = service;
  }

  public record Support(@NotNull UUID requestId, @NotNull UUID accountId) {}

  public record Version(@Min(1) long version) {}

  public record Action(@NotNull UUID operationId, @Min(1) long version, String action) {}

  @PostMapping("/v1/support/cases")
  public OperationsService.Case support(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Support s) {
    return service.support(s.requestId(), s.accountId(), actor(jwt));
  }

  @GetMapping("/v1/support/cases")
  public List<OperationsService.Case> cases(
      @RequestParam(required = false) UUID after, @RequestParam(defaultValue = "20") int limit) {
    return service.cases(after, limit);
  }

  @GetMapping("/v1/support/cases/{id}")
  public OperationsService.Case get(@PathVariable UUID id) {
    return service.get(id);
  }

  @GetMapping("/v1/support/cases/{id}/account")
  public Map<String, Object> summary(@PathVariable UUID id) {
    return service.summary(id);
  }

  @GetMapping("/v1/support/cases/{id}/audit")
  public List<Map<String, Object>> audit(
      @PathVariable UUID id,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "20") int limit) {
    return service.audit(id, after, limit);
  }

  @PutMapping("/v1/admin/cases/{id}/resolve")
  public OperationsService.Case resolve(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Version v) {
    return service.resolve(id, v.version(), actor(jwt));
  }

  @PostMapping("/v1/admin/cases/{id}/actions")
  public OperationsService.Command action(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Action a) {
    return service.enqueue(a.operationId(), id, a.version(), a.action(), actor(jwt));
  }

  @GetMapping("/v1/admin/commands/{id}")
  public OperationsService.Command command(@PathVariable UUID id) {
    return service.command(id);
  }

  @PostMapping("/internal/v1/operations/signals")
  public OperationsService.SignalReceipt signal(
      @AuthenticationPrincipal Jwt jwt, @RequestBody OperationsService.Signal s) {
    if (s != null && "PAYMENT_FAILED".equals(s.kind()))
      throw new OperationsFailure(400, "OWNER_SIGNAL_REQUIRED");
    return service.signal(s, actor(jwt));
  }

  private String actor(Jwt jwt) {
    return jwt.getIssuer() + ":" + jwt.getSubject();
  }
}
