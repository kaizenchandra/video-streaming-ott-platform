package com.ott.member.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MemberModels {
  private MemberModels() {}

  public record Identity(String issuer, String subject, Instant authenticatedAt) {
    public Identity {
      if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
        throw new IllegalArgumentException("Validated issuer and subject required");
      }
    }
  }

  public record Account(UUID id, Instant createdAt) {}

  public record Profile(
      UUID id, String name, boolean kids, int maturity, String locale, long version) {}

  public record Device(
      UUID id,
      String installationId,
      String name,
      List<String> codecs,
      List<String> drm,
      String status,
      long version) {
    public Device {
      codecs = List.copyOf(codecs);
      drm = List.copyOf(drm);
    }
  }
}
