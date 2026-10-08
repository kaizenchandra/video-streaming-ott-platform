package com.ott.operations.adapter.inbound.kafka;

import com.ott.operations.adapter.outbound.kafka.RegistryCodec;
import com.ott.operations.application.OperationsFailure;
import com.ott.operations.application.OperationsService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "ott.operations.consumers-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PaymentRiskListener {
  private final RegistryCodec codec;
  private final OperationsService service;

  public PaymentRiskListener(RegistryCodec codec, OperationsService service) {
    this.codec = codec;
    this.service = service;
  }

  @KafkaListener(topics = "payment.failed.v1", groupId = "operations-payment-risk-v1")
  public void receive(byte[] bytes, @Header(KafkaHeaders.RECEIVED_KEY) String key) {
    var r = codec.decode("payment.failed.v1", bytes);
    UUID account = UUID.fromString(r.get("accountId").toString()),
        event = UUID.fromString(r.get("eventId").toString());
    UUID.fromString(r.get("orderId").toString());
    if (!"payment.failed.v1".equals(r.get("type").toString())
        || !"commerce".equals(r.get("producer").toString())
        || !account.toString().equals(key)
        || !key.equals(r.get("aggregateId").toString())
        || ((Number) r.get("aggregateVersion")).longValue() < 1)
      throw new IllegalArgumentException("Invalid payment risk source");
    try {
      service.signal(
          new OperationsService.Signal(
              event,
              account,
              "PAYMENT_FAILED",
              "UNKNOWN",
              Instant.ofEpochMilli(((Number) r.get("occurredAt")).longValue())),
          "typed-owner:commerce:"
              + OperationsService.hash(java.util.HexFormat.of().formatHex(bytes)));
    } catch (OperationsFailure e) {
      if (e.status() == 400 || e.status() == 409)
        throw new IllegalArgumentException("Invalid risk observation", e);
      throw e;
    }
  }
}
