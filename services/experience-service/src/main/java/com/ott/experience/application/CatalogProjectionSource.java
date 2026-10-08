package com.ott.experience.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface CatalogProjectionSource {
  record Localized(String title, String synopsis) {}

  record Snapshot(
      UUID contentId,
      long version,
      String contentType,
      int maturity,
      Map<String, Localized> localizations,
      List<String> genres,
      List<String> actors,
      LocalDate releaseDate,
      Instant publishedAt) {}

  record Page(List<Snapshot> items, UUID nextCursor) {}

  Snapshot snapshot(UUID content, long version);

  Page snapshots(UUID after, int limit);

  List<UUID> permitted(List<UUID> content, String country, int maturity);
}
