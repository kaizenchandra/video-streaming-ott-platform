package com.ott.experience.adapter.inbound.kafka;

import com.ott.experience.adapter.outbound.kafka.RegistryCodec;
import com.ott.experience.application.RecommendationSignals;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ott.recommendations.enabled", havingValue = "true")
public class PlaybackSignalListener {
  private final RegistryCodec codec;
  private final RecommendationSignals signals;

  public PlaybackSignalListener(RegistryCodec codec, RecommendationSignals signals) {
    this.codec = codec;
    this.signals = signals;
  }

  @KafkaListener(topics = "playback.started.v1", groupId = "experience-recommendation-v1")
  public void start(@Header(KafkaHeaders.RECEIVED_KEY) String key, byte[] bytes) {
    var r = codec.decode("playback.started.v1", bytes);
    if (!"playback.started.v1".equals(r.get("type").toString())
        || !"playback".equals(r.get("producer").toString())
        || !key.equals(r.get("sessionId").toString())
        || !key.equals(r.get("aggregateId").toString())
        || ((Number) r.get("aggregateVersion")).longValue() != 1)
      throw new IllegalArgumentException("Invalid playback signal identity");
    signals.start(
        UUID.fromString(r.get("eventId").toString()),
        UUID.fromString(r.get("accountId").toString()),
        UUID.fromString(r.get("profileId").toString()),
        UUID.fromString(key),
        UUID.fromString(r.get("contentId").toString()),
        UUID.fromString(r.get("deviceId").toString()),
        Instant.ofEpochMilli(((Number) r.get("occurredAt")).longValue()));
  }
}
