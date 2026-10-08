package com.ott.commerce.adapter.inbound.rest;

import com.ott.commerce.application.CommerceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
public class CommerceController {
  private final CommerceService service;
  private final WebhookVerifier verifier;
  private final RestClient member;

  public CommerceController(
      io.micrometer.observation.ObservationRegistry observations,
      CommerceService service,
      WebhookVerifier verifier,
      @Value("${ott.member.url}") String url) {
    this.service = service;
    this.verifier = verifier;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(300);
    factory.setReadTimeout(1000);
    member =
        RestClient.builder()
            .observationRegistry(observations)
            .baseUrl(url)
            .requestFactory(factory)
            .build();
  }

  public record PlanInput(
      @NotBlank @Size(max = 80) String name,
      @Min(1) @Max(1000000000) long priceMinor,
      @NotBlank String currency,
      @Min(1) @Max(3650) int durationDays,
      @Min(1) @Max(10) int maxStreams,
      boolean adSupported,
      boolean offlineAllowed) {}

  public record Purchase(@NotNull UUID planId) {}

  private record Account(UUID id) {}

  @GetMapping("/v1/plans")
  public List<CommerceService.Plan> plans() {
    return service.plans();
  }

  @PostMapping("/v1/admin/plans")
  @ResponseStatus(HttpStatus.CREATED)
  public CommerceService.Plan create(@Valid @RequestBody PlanInput in) {
    return service.createPlan(
        in.name(),
        in.priceMinor(),
        in.currency(),
        in.durationDays(),
        in.maxStreams(),
        in.adSupported(),
        in.offlineAllowed());
  }

  @PostMapping("/v1/orders")
  @ResponseStatus(HttpStatus.CREATED)
  public CommerceService.Order purchase(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody Purchase in,
      @RequestHeader("Idempotency-Key") String key) {
    return service.purchase(account(jwt), in.planId(), key);
  }

  @PostMapping("/v1/subscription/cancel")
  public CommerceService.Entitlement cancel(@AuthenticationPrincipal Jwt jwt) {
    return service.cancel(account(jwt), jwt.getIssuer() + ":" + jwt.getSubject());
  }

  public record SecurityCommand(
      @NotNull UUID operationId,
      @NotNull UUID accountId,
      @NotBlank String action,
      @NotBlank String reason) {}

  @PostMapping("/internal/v1/commerce/security/commands")
  public CommerceService.SecurityResult security(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SecurityCommand c) {
    return service.security(
        c.operationId(),
        c.accountId(),
        c.action(),
        c.reason(),
        jwt.getIssuer() + ":" + jwt.getSubject());
  }

  @GetMapping("/v1/orders/{id}")
  public CommerceService.Order order(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.get(account(jwt), id);
  }

  @PostMapping("/v1/orders/{id}/reconcile")
  public CommerceService.Order reconcile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.reconcile(account(jwt), id);
  }

  @GetMapping("/v1/orders/{id}/invoice")
  public CommerceService.Invoice invoice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.invoice(account(jwt), id);
  }

  @GetMapping("/v1/subscription")
  public CommerceService.Entitlement subscription(@AuthenticationPrincipal Jwt jwt) {
    return service.entitlement(account(jwt));
  }

  @GetMapping("/internal/v1/entitlements/{accountId}")
  public CommerceService.Entitlement entitlement(@PathVariable UUID accountId) {
    return service.entitlement(accountId);
  }

  @PostMapping("/v1/webhooks/payment")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void webhook(
      @RequestBody byte[] raw,
      @RequestHeader("X-Webhook-Timestamp") String timestamp,
      @RequestHeader("X-Webhook-Signature") String signature) {
    verifier.verify(raw, timestamp, signature);
    service.applyVerifiedWebhook(raw);
  }

  private UUID account(Jwt jwt) {
    var account =
        member
            .get()
            .uri("/v1/accounts/me")
            .headers(h -> h.setBearerAuth(jwt.getTokenValue()))
            .retrieve()
            .body(Account.class);
    if (account == null || account.id() == null)
      throw new CommerceService.Failure(503, "MEMBER_UNAVAILABLE", "Member identity unavailable");
    return account.id();
  }
}
