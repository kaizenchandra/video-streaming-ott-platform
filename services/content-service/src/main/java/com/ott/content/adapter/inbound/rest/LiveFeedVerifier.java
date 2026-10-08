package com.ott.content.adapter.inbound.rest;

import com.ott.content.application.CatalogService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LiveFeedVerifier {
  private final byte[] secret;
  private final Clock clock;

  public LiveFeedVerifier(@Value("${ott.live.feed-secret:}") String secret, Clock clock) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.clock = clock;
  }

  public void verify(byte[] raw, String timestamp, String signature) {
    if (secret.length < 32) throw fail(503, "LIVE_PROVIDER_NOT_CONFIGURED");
    try {
      long at = Long.parseLong(timestamp);
      long now = clock.instant().getEpochSecond();
      if (at < now - 30
          || at > now + 30
          || signature == null
          || !signature.matches("[a-f0-9]{64}")
          || raw == null
          || raw.length > 65536) throw fail(403, "FEED_SIGNATURE_DENIED");
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
      if (!MessageDigest.isEqual(mac.doFinal(raw), HexFormat.of().parseHex(signature)))
        throw fail(403, "FEED_SIGNATURE_DENIED");
    } catch (NumberFormatException e) {
      throw fail(403, "FEED_SIGNATURE_DENIED");
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Feed verifier unavailable", e);
    }
  }

  private static CatalogService.Failure fail(int status, String code) {
    return new CatalogService.Failure(status, code, code.replace('_', ' '));
  }
}
