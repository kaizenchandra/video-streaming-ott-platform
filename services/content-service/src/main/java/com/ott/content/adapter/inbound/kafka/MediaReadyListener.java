package com.ott.content.adapter.inbound.kafka;

import com.ott.content.adapter.outbound.kafka.RegistryCodec;
import com.ott.content.application.CatalogService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "ott.media-consumer.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class MediaReadyListener {
  private final RegistryCodec codec;
  private final CatalogService catalog;

  public MediaReadyListener(RegistryCodec codec, CatalogService catalog) {
    this.codec = codec;
    this.catalog = catalog;
  }

  @KafkaListener(topics = "media.ready.v1", groupId = "content-media-v1")
  public void ready(byte[] bytes) {
    var event = codec.mediaReady(bytes);
    if (!"media.ready.v1".equals(event.getType()) || !"media".equals(event.getProducer()))
      throw new IllegalArgumentException("Invalid event owner/type");
    catalog.mediaReady(
        UUID.fromString(event.getEventId()),
        UUID.fromString(event.getContentId()),
        event.getAssetVersion(),
        event.getOriginPrefix(),
        event.getManifestChecksum());
  }
}
