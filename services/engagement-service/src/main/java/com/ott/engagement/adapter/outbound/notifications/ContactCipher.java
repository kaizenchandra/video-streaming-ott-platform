package com.ott.engagement.adapter.outbound.notifications;

import com.ott.engagement.application.EngagementFailure;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ContactCipher {
  private final byte[] key;
  private final java.security.SecureRandom random = new java.security.SecureRandom();

  public ContactCipher(@Value("${ott.notifications.contact-key:}") String value) {
    if (value.isBlank()) key = null;
    else {
      try {
        key = java.util.HexFormat.of().parseHex(value);
        if (key.length != 32) throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Contact encryption requires a 256-bit hex key");
      }
    }
  }

  public String encrypt(String reference, String binding) {
    if (key == null) throw unavailable();
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
      byte[] body = cipher.doFinal(reference.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder()
          .encodeToString(
              java.nio.ByteBuffer.allocate(nonce.length + body.length)
                  .put(nonce)
                  .put(body)
                  .array());
    } catch (java.security.GeneralSecurityException e) {
      throw unavailable();
    }
  }

  public String decrypt(String value, String binding) {
    if (key == null) throw unavailable();
    try {
      byte[] body = Base64.getDecoder().decode(value);
      if (body.length < 29 || body.length > 4096) throw unavailable();
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, body, 0, 12));
      cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(body, 12, body.length - 12), StandardCharsets.UTF_8);
    } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
      throw unavailable();
    }
  }

  private static EngagementFailure unavailable() {
    return new EngagementFailure(503, "CONTACT_VAULT_UNAVAILABLE");
  }
}
