package com.ott.commerce.adapter.inbound.rest;

import com.ott.commerce.application.CommerceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookVerifier {
  private final byte[] key;
  private final Clock clock;

  public WebhookVerifier(@Value("${ott.payment.webhook-secret}") String secret, Clock clock) {
    if (secret.length() < 32)
      throw new IllegalStateException("Webhook signing key must have at least32 characters");
    this.key = secret.getBytes(StandardCharsets.UTF_8);
    this.clock = clock;
  }

  public void verify(byte[] raw, String timestamp, String signature) {
    if (raw == null
        || raw.length > 256 * 1024
        || timestamp == null
        || signature == null
        || !signature.matches("[a-fA-F0-9]{64}")) throw unauthorized();
    try {
      long at = Long.parseLong(timestamp);
      if (Duration.between(Instant.ofEpochSecond(at), clock.instant())
              .abs()
              .compareTo(Duration.ofMinutes(5))
          > 0) throw unauthorized();
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
      if (!MessageDigest.isEqual(mac.doFinal(raw), HexFormat.of().parseHex(signature)))
        throw unauthorized();
    } catch (NumberFormatException | java.time.DateTimeException e) {
      throw unauthorized();
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Webhook verifier unavailable", e);
    }
  }

  private static CommerceService.Failure unauthorized() {
    return new CommerceService.Failure(
        401, "INVALID_WEBHOOK_SIGNATURE", "Webhook authentication failed");
  }
}
