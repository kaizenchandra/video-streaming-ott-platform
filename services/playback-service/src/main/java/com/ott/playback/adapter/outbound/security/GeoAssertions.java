package com.ott.playback.adapter.outbound.security;

import com.ott.playback.application.PlaybackFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeoAssertions {
  private final byte[] key;
  private final Clock clock;

  public GeoAssertions(@Value("${ott.geo.secret}") String secret, Clock clock) {
    if (secret.length() < 32) throw new IllegalStateException("Geo signing key too short");
    key = secret.getBytes(StandardCharsets.UTF_8);
    this.clock = clock;
  }

  public void verify(String issuer, String subject, UUID device, String country, String assertion) {
    try {
      if (country == null
          || !country.matches("[A-Z]{2}")
          || assertion == null
          || assertion.length() > 256) throw deny();
      String[] parts = assertion.split("\\.");
      long at = Long.parseLong(parts[0]);
      long now = clock.instant().getEpochSecond();
      if (parts.length != 2 || at > now + 5 || at < now - 30) throw deny();
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] expected =
          mac.doFinal(
              (at + "\n" + issuer + "\n" + subject + "\n" + device + "\n" + country)
                  .getBytes(StandardCharsets.UTF_8));
      if (!MessageDigest.isEqual(expected, java.util.Base64.getUrlDecoder().decode(parts[1])))
        throw deny();
    } catch (IllegalArgumentException
        | java.security.GeneralSecurityException
        | ArrayIndexOutOfBoundsException e) {
      throw deny();
    }
  }

  private static PlaybackFailure deny() {
    return new PlaybackFailure(403, "GEO_ASSERTION_DENIED", "Trusted edge geo assertion required");
  }
}
