package com.ott.playback.adapter.outbound.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

public final class CloudFrontCookieSigner {
  private final PrivateKey key;
  private final String keyId;
  private final String origin;

  public CloudFrontCookieSigner(PrivateKey key, String keyId, String origin) {
    URI uri = URI.create(origin);
    if (!(key instanceof RSAPrivateKey rsa)
        || rsa.getModulus().bitLength() < 2048
        || !keyId.matches("[A-Za-z0-9_-]{1,100}")
        || !"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
      throw new IllegalArgumentException("Invalid CDN signer configuration");
    this.key = key;
    this.keyId = keyId;
    this.origin = origin.replaceAll("/$", "");
  }

  public Map<String, String> cookies(String prefix, Instant expiry) {
    if (!prefix.matches("(?:vod/[a-f0-9-]{36}/v[1-9][0-9]*|live/[a-f0-9-]{36}/e[1-9][0-9]*)/"))
      throw new IllegalArgumentException("Invalid immutable CDN prefix");
    String policy =
        "{\"Statement\":[{\"Resource\":\""
            + origin
            + "/"
            + prefix
            + "*\",\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":"
            + expiry.getEpochSecond()
            + "}}}]}";
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(key);
      signature.update(policy.getBytes(StandardCharsets.UTF_8));
      return Map.of(
          "CloudFront-Policy",
          encoded(policy.getBytes(StandardCharsets.UTF_8)),
          "CloudFront-Signature",
          encoded(signature.sign()),
          "CloudFront-Key-Pair-Id",
          keyId,
          "CloudFront-Hash-Algorithm",
          "SHA256");
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("CDN signing unavailable", e);
    }
  }

  public String manifest(String prefix) {
    if (!prefix.matches("(?:vod/[a-f0-9-]{36}/v[1-9][0-9]*|live/[a-f0-9-]{36}/e[1-9][0-9]*)/"))
      throw new IllegalArgumentException("Invalid immutable CDN prefix");
    return origin + "/" + prefix + "master.m3u8";
  }

  private static String encoded(byte[] bytes) {
    return Base64.getEncoder()
        .encodeToString(bytes)
        .replace('+', '-')
        .replace('=', '_')
        .replace('/', '~');
  }
}
