package com.ott.experience.application;

import com.ott.experience.adapter.outbound.search.OpenSearchCatalog;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "ott.search.enabled", havingValue = "true")
public class SearchIndexer {
  private final SearchProjection projection;
  private final OpenSearchCatalog search;
  private final CatalogProjectionSource source;

  public SearchIndexer(
      SearchProjection projection, OpenSearchCatalog search, CatalogProjectionSource source) {
    this.projection = projection;
    this.search = search;
    this.source = source;
  }

  @Scheduled(fixedDelayString = "${ott.search.index-delay-ms:1000}")
  public void index() {
    UUID token = UUID.randomUUID();
    for (var work : projection.claim(token)) {
      try {
        search.index(work.snapshot());
        projection.indexed(token, work);
      } catch (RestClientException | IllegalArgumentException | IllegalStateException e) {
        projection.failed(token, work);
      }
    }
  }

  @Scheduled(fixedDelayString = "${ott.search.rebuild-delay-ms:1000}")
  public void rebuild() {
    UUID token = UUID.randomUUID();
    for (var job : projection.claimRebuild(token)) {
      try {
        projection.rebuilt(token, job, source.snapshots(job.after(), 20));
      } catch (WatchFailure e) {
        /* Claim expires; durable checkpoint resumes without unbounded retries. */
      }
    }
  }
}
