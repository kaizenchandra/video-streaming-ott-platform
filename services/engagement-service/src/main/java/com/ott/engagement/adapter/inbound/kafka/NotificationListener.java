package com.ott.engagement.adapter.inbound.kafka;

import com.ott.engagement.adapter.outbound.kafka.RegistryCodec;
import com.ott.engagement.application.AdService;
import com.ott.engagement.application.NotificationService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "ott.notifications.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class NotificationListener {
  private final RegistryCodec codec;
  private final NotificationService service;

  public NotificationListener(RegistryCodec codec, NotificationService service) {
    this.codec = codec;
    this.service = service;
  }

  @KafkaListener(
      topics = {"payment.failed.v1", "live.changed.v1", "notification.requested.v1"},
      groupId = "engagement-notifications-v1")
  public void receive(
      @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
      @Header(KafkaHeaders.RECEIVED_KEY) String key,
      byte[] bytes) {
    var r = codec.decode(topic, bytes);
    String expected =
        topic.equals("payment.failed.v1")
            ? "commerce"
            : topic.equals("live.changed.v1") ? "content" : "engagement";
    if (!expected.equals(r.get("producer").toString()) || !topic.equals(r.get("type").toString()))
      throw new IllegalArgumentException("Foreign notification producer");
    UUID event = UUID.fromString(r.get("eventId").toString());
    long version = ((Number) r.get("aggregateVersion")).longValue();
    if (version < 1) throw new IllegalArgumentException("Invalid source version");
    String fingerprint = AdService.hash(java.util.HexFormat.of().formatHex(bytes));
    if (topic.equals("payment.failed.v1")) {
      UUID account = UUID.fromString(r.get("accountId").toString()),
          order = UUID.fromString(r.get("orderId").toString());
      if (!key.equals(account.toString())
          || !r.get("aggregateId").toString().equals(account.toString()))
        throw new IllegalArgumentException("Invalid payment notice identity");
      service.payment(
          event,
          fingerprint,
          account,
          order,
          Instant.ofEpochMilli(((Number) r.get("occurredAt")).longValue()));
    } else if (topic.equals("live.changed.v1")) {
      UUID target = UUID.fromString(r.get("liveEventId").toString());
      if (!key.equals(target.toString()) || !r.get("aggregateId").toString().equals(key))
        throw new IllegalArgumentException("Invalid live notice identity");
      service.live(
          event,
          fingerprint,
          target,
          version,
          r.get("state").toString(),
          Instant.ofEpochMilli(((Number) r.get("startsAt")).longValue()),
          Instant.ofEpochMilli(((Number) r.get("occurredAt")).longValue()));
    } else {
      UUID account = UUID.fromString(r.get("accountId").toString()),
          intent = UUID.fromString(r.get("intentId").toString());
      if (!key.equals(account.toString())
          || !r.get("aggregateId").toString().equals(intent.toString()))
        throw new IllegalArgumentException("Invalid intent identity");
      service.activate(
          event, fingerprint, account, intent, r.get("templateId").toString(), version);
    }
  }
}
