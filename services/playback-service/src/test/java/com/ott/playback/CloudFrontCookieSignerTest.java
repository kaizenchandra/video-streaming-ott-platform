package com.ott.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.playback.adapter.outbound.security.CloudFrontCookieSigner;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CloudFrontCookieSignerTest {
  @Test
  void cookies_policyCoversManifestAndSegments_signatureVerifiesAndExpiryIsBounded()
      throws Exception {
    var generator = java.security.KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var keys = generator.generateKeyPair();
    var signer = new CloudFrontCookieSigner(keys.getPrivate(), "KTEST", "https://cdn.example.test");
    String prefix = "vod/" + UUID.randomUUID() + "/v1/";
    Instant expiry = Instant.ofEpochSecond(1234567890);
    var cookies = signer.cookies(prefix, expiry);
    byte[] policy = decode(cookies.get("CloudFront-Policy"));
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(policy);
    assertThat(json.at("/Statement/0/Resource").asText())
        .isEqualTo("https://cdn.example.test/" + prefix + "*");
    assertThat(json.at("/Statement/0/Condition/DateLessThan/AWS:EpochTime").asLong())
        .isEqualTo(expiry.getEpochSecond());
    var verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(keys.getPublic());
    verifier.update(policy);
    assertThat(verifier.verify(decode(cookies.get("CloudFront-Signature")))).isTrue();
    assertThat(cookies.get("CloudFront-Hash-Algorithm")).isEqualTo("SHA256");
    assertThatThrownBy(() -> signer.cookies("../private/", expiry))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private byte[] decode(String value) {
    return Base64.getDecoder().decode(value.replace('-', '+').replace('_', '=').replace('~', '/'));
  }
}
