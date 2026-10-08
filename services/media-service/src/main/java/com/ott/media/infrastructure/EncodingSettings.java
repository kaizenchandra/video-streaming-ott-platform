package com.ott.media.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class EncodingSettings {
  private final JsonNode document;
  private final String hash;

  public EncodingSettings(
      ObjectMapper json,
      @Value("${ott.media.profiles:classpath:encoding-profiles.json}") Resource resource)
      throws java.io.IOException {
    try (var input = resource.getInputStream()) {
      document = json.readTree(input);
    }
    hash = hash(json, document);
  }

  public JsonNode document() {
    return document.deepCopy();
  }

  public String hash() {
    return hash;
  }

  public static String hash(ObjectMapper json, JsonNode document) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(json.writeValueAsBytes(document)));
    } catch (java.security.NoSuchAlgorithmException
        | com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Invalid encoding settings", e);
    }
  }
}
