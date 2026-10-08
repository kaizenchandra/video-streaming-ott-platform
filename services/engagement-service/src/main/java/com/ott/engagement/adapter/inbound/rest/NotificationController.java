package com.ott.engagement.adapter.inbound.rest;

import com.ott.engagement.application.EngagementFailure;
import com.ott.engagement.application.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {
  private final NotificationService service;

  public NotificationController(NotificationService service) {
    this.service = service;
  }

  public record Preference(@Min(1) long version, @NotNull Boolean enabled) {}

  public record Reminder(
      @NotNull UUID targetId, @NotBlank String kind, @NotBlank String channel, Instant dueAt) {}

  @GetMapping("/v1/notification-preferences")
  public List<NotificationService.Preference> preferences(@AuthenticationPrincipal Jwt jwt) {
    return service.preferences(service.account(jwt.getTokenValue()));
  }

  @PutMapping("/v1/notification-preferences/{channel}")
  public NotificationService.Preference preference(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String channel,
      @Valid @RequestBody Preference p) {
    return service.preference(
        service.account(jwt.getTokenValue()), channel, p.version(), p.enabled());
  }

  @PostMapping("/v1/notification-contacts/{channel}")
  public void contact(@AuthenticationPrincipal Jwt jwt, @PathVariable String channel) {
    String reference =
        switch (channel) {
          case "EMAIL" -> verified(jwt, "email", "email_verified");
          case "SMS" -> verified(jwt, "phone_number", "phone_number_verified");
          case "PUSH" -> verified(jwt, "push_contact_ref", "push_contact_verified");
          default -> throw new EngagementFailure(400, "INVALID_CHANNEL");
        };
    if (channel.equals("EMAIL")
            && !reference.matches("[^\\s@]{1,200}@[^\\s@]{1,250}\\.[^\\s@]{1,60}")
        || channel.equals("SMS") && !reference.matches("\\+[1-9][0-9]{7,14}"))
      throw new EngagementFailure(400, "INVALID_CONTACT");
    service.verifiedContact(service.account(jwt.getTokenValue()), channel, reference);
  }

  @DeleteMapping("/v1/notification-contacts/{channel}")
  public void removeContact(@AuthenticationPrincipal Jwt jwt, @PathVariable String channel) {
    service.removeContact(service.account(jwt.getTokenValue()), channel);
  }

  @PostMapping("/v1/reminders")
  public NotificationService.Reminder remind(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Reminder r) {
    return service.remind(jwt.getTokenValue(), r.targetId(), r.kind(), r.channel(), r.dueAt(), key);
  }

  @GetMapping("/v1/reminders")
  public List<NotificationService.Reminder> reminders(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "20") int limit) {
    return service.reminders(service.account(jwt.getTokenValue()), after, limit);
  }

  @DeleteMapping("/v1/reminders/{id}")
  public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.cancel(service.account(jwt.getTokenValue()), id);
  }

  @GetMapping("/v1/notification-deliveries")
  public List<NotificationService.Delivery> deliveries(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "20") int limit) {
    return service.deliveries(service.account(jwt.getTokenValue()), after, limit);
  }

  @GetMapping("/v1/notifications")
  public NotificationService.Page inbox(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return service.inbox(service.account(jwt.getTokenValue()), cursor, limit);
  }

  @PutMapping("/v1/notifications/{id}/read")
  public void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.read(service.account(jwt.getTokenValue()), id);
  }

  private static String verified(Jwt jwt, String value, String proof) {
    Object reference = jwt.getClaims().get(value);
    if (!Boolean.TRUE.equals(jwt.getClaims().get(proof))
        || !(reference instanceof String text)
        || text.isBlank()) throw new EngagementFailure(403, "VERIFIED_CONTACT_REQUIRED");
    return text;
  }
}
