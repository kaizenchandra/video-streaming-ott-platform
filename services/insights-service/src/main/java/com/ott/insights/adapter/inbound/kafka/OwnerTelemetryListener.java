package com.ott.insights.adapter.inbound.kafka;

import com.ott.insights.adapter.outbound.kafka.RegistryCodec;
import com.ott.insights.application.TelemetryService;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "ott.insights.consumers-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OwnerTelemetryListener {
  private final RegistryCodec codec;
  private final TelemetryService service;

  public OwnerTelemetryListener(RegistryCodec codec, TelemetryService service) {
    this.codec = codec;
    this.service = service;
  }

  @KafkaListener(
      topics = {"subscription.changed.v1", "payment.failed.v1", "ad.impression.v1"},
      groupId = "insights-owner-observations-v1")
  public void receive(
      byte[] bytes,
      @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
      @Header(KafkaHeaders.RECEIVED_KEY) String key) {
    var r = codec.decode(topic, bytes);
    String producer = topic.equals("ad.impression.v1") ? "engagement" : "commerce";
    if (!topic.equals(r.get("type").toString())
        || !producer.equals(r.get("producer").toString())
        || !r.getSchema().getFullName().equals(codec.writer(topic).getFullName())
        || ((Number) r.get("aggregateVersion")).longValue() < 1)
      throw new IllegalArgumentException("Invalid observation source");
    UUID event = UUID.fromString(r.get("eventId").toString());
    Instant at = Instant.ofEpochMilli(((Number) r.get("occurredAt")).longValue());
    String fingerprint = TelemetryService.hash(HexFormat.of().formatHex(bytes));
    if (topic.equals("ad.impression.v1")) {
      UUID decision = UUID.fromString(r.get("decisionId").toString());
      if (!decision.toString().equals(key) || !key.equals(r.get("aggregateId").toString()))
        throw new IllegalArgumentException("Invalid ad observation identity");
      int quartile = ((Number) r.get("quartile")).intValue();
      if (quartile < 0 || quartile > 4) throw new IllegalArgumentException("Invalid quartile");
      if (quartile != 0
          || r.getSchema().getField("accountId") == null
          || r.get("accountId") == null) return;
      service.owner(
          event,
          fingerprint,
          UUID.fromString(r.get("accountId").toString()),
          "AD_IMPRESSION",
          decision.toString(),
          at);
    } else {
      UUID account = UUID.fromString(r.get("accountId").toString());
      if (!account.toString().equals(key) || !key.equals(r.get("aggregateId").toString()))
        throw new IllegalArgumentException("Invalid commerce observation identity");
      if (topic.equals("subscription.changed.v1")) {
        if (!"ACTIVE".equals(r.get("state").toString())
            || !"PURCHASED".equals(r.get("changeReason").toString())) return;
        service.owner(
            event,
            fingerprint,
            account,
            "SUBSCRIPTION_PURCHASED",
            account + ":" + r.get("aggregateVersion"),
            at);
      } else
        service.owner(
            event,
            fingerprint,
            account,
            "PAYMENT_FAILED",
            UUID.fromString(r.get("orderId").toString()).toString(),
            at);
    }
  }
}
