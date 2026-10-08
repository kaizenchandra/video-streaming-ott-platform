package com.ott.experience.adapter.inbound.kafka;

import com.ott.experience.adapter.outbound.kafka.RegistryCodec;
import com.ott.experience.application.CatalogProjectionSource;
import com.ott.experience.application.SearchProjection;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ott.search.enabled", havingValue = "true")
public class CatalogPublishedListener {
  private final RegistryCodec codec;
  private final CatalogProjectionSource source;
  private final SearchProjection projection;

  public CatalogPublishedListener(
      RegistryCodec codec, CatalogProjectionSource source, SearchProjection projection) {
    this.codec = codec;
    this.source = source;
    this.projection = projection;
  }

  @KafkaListener(topics = "catalog.published.v1", groupId = "experience-catalog-v1")
  public void published(
      @org.springframework.messaging.handler.annotation.Header(
              org.springframework.kafka.support.KafkaHeaders.RECEIVED_KEY)
          String key,
      byte[] bytes) {
    var record = codec.decode("catalog.published.v1", bytes);
    if (!"catalog.published.v1".equals(record.get("type").toString())
        || !"content".equals(record.get("producer").toString()))
      throw new IllegalArgumentException("Invalid catalog owner/type");
    UUID content = UUID.fromString(record.get("contentId").toString());
    long version = ((Number) record.get("contentVersion")).longValue();
    if (!content.toString().equals(key)
        || !content.toString().equals(record.get("aggregateId").toString())
        || version != ((Number) record.get("aggregateVersion")).longValue())
      throw new IllegalArgumentException("Invalid publication identity");
    var snapshot = source.snapshot(content, version);
    if (!content.equals(snapshot.contentId()) || version != snapshot.version())
      throw new IllegalArgumentException("Wrong published snapshot");
    projection.accept(UUID.fromString(record.get("eventId").toString()), snapshot);
  }
}
