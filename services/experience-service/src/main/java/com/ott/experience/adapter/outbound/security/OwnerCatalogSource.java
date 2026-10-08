package com.ott.experience.adapter.outbound.security;

import com.ott.experience.application.CatalogProjectionSource;
import com.ott.experience.application.WatchFailure;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OwnerCatalogSource implements CatalogProjectionSource {
  private final RestClient http;
  private final WorkloadTokens workload;

  public OwnerCatalogSource(
      io.micrometer.observation.ObservationRegistry observations,
      @Value("${ott.owners.content}") String content,
      WorkloadTokens workload) {
    this.workload = workload;
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(100);
    f.setReadTimeout(500);
    http =
        RestClient.builder()
            .observationRegistry(observations)
            .baseUrl(content)
            .requestFactory(f)
            .build();
  }

  public Snapshot snapshot(UUID id, long version) {
    try {
      var result =
          http.get()
              .uri("/internal/v1/catalog/{id}/versions/{version}", id, version)
              .headers(h -> h.setBearerAuth(workload.token()))
              .retrieve()
              .body(Snapshot.class);
      if (result == null) throw new WatchFailure(503, "CATALOG_UNAVAILABLE");
      return result;
    } catch (RestClientException e) {
      throw new WatchFailure(503, "CATALOG_UNAVAILABLE");
    }
  }

  public Page snapshots(UUID after, int limit) {
    try {
      var result =
          http.get()
              .uri(
                  b -> {
                    b.path("/internal/v1/catalog/snapshots").queryParam("limit", limit);
                    if (after != null) b.queryParam("after", after);
                    return b.build();
                  })
              .headers(h -> h.setBearerAuth(workload.token()))
              .retrieve()
              .body(Page.class);
      if (result == null) throw new WatchFailure(503, "CATALOG_UNAVAILABLE");
      return result;
    } catch (RestClientException e) {
      throw new WatchFailure(503, "CATALOG_UNAVAILABLE");
    }
  }

  public List<UUID> permitted(List<UUID> ids, String country, int maturity) {
    if (ids.isEmpty()) return List.of();
    try {
      var result =
          http.post()
              .uri("/internal/v1/rights/batch")
              .headers(h -> h.setBearerAuth(workload.token()))
              .body(Map.of("contentIds", ids, "country", country, "maturity", maturity))
              .retrieve()
              .body(new ParameterizedTypeReference<List<UUID>>() {});
      if (result == null) throw new WatchFailure(503, "CATALOG_UNAVAILABLE");
      return result;
    } catch (RestClientException e) {
      throw new WatchFailure(503, "CATALOG_UNAVAILABLE");
    }
  }
}
