package com.ott.member.adapter.outbound.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "ott.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
  private static final String TOPIC = "member.device-revoked.v1";
  private final JdbcTemplate jdbc;
  private final KafkaTemplate<String, byte[]> kafka;
  private final TransactionTemplate transactions;
  private final RestClient registry;
  private final MeterRegistry metrics;
  private volatile Integer schemaId;

  public OutboxRelay(
      io.micrometer.observation.ObservationRegistry observations,
      JdbcTemplate jdbc,
      KafkaTemplate<String, byte[]> kafka,
      PlatformTransactionManager tx,
      @Value("${ott.schema-registry.url}") String registryUrl,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.kafka = kafka;
    this.transactions = new TransactionTemplate(tx);
    this.metrics = metrics;
    var requestFactory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(1))
                .build());
    requestFactory.setReadTimeout(java.time.Duration.ofSeconds(2));
    this.registry =
        RestClient.builder()
            .observationRegistry(observations)
            .baseUrl(registryUrl)
            .requestFactory(requestFactory)
            .build();
  }

  private record Pending(
      UUID id, UUID aggregate, long version, Instant at, UUID account, int attempts) {}

  @Scheduled(fixedDelayString = "${ott.outbox.delay-ms:1000}")
  public void publishBatch() {
    var claim = UUID.randomUUID();
    List<Pending> events =
        transactions.execute(
            status ->
                jdbc.query(
                    """
                    UPDATE outbox_event SET claim_token=?,claimed_until=clock_timestamp()+interval '120 seconds',attempts=attempts+1
                    WHERE event_id IN (SELECT event_id FROM outbox_event
                     WHERE published_at IS NULL AND topic=? AND next_attempt_at<=clock_timestamp()
                     AND (claimed_until IS NULL OR claimed_until<clock_timestamp())
                     ORDER BY created_at,event_id FOR UPDATE SKIP LOCKED LIMIT 10)
                    RETURNING event_id,aggregate_id,aggregate_version,created_at,payload->>'accountId' AS account_id,attempts
                    """,
                    (r, n) ->
                        new Pending(
                            r.getObject("event_id", UUID.class),
                            r.getObject("aggregate_id", UUID.class),
                            r.getLong("aggregate_version"),
                            r.getTimestamp("created_at").toInstant(),
                            UUID.fromString(r.getString("account_id")),
                            r.getInt("attempts")),
                    claim,
                    TOPIC));
    for (var event : events) {
      try {
        byte[] body =
            DeviceEventEncoder.encode(
                schemaId(),
                event.id(),
                event.aggregate(),
                event.version(),
                event.at().toEpochMilli(),
                event.account());
        kafka.send(TOPIC, event.account().toString(), body).get(5, TimeUnit.SECONDS);
        jdbc.update(
            "UPDATE outbox_event SET"
                + " published_at=clock_timestamp(),claim_token=NULL,claimed_until=NULL WHERE"
                + " event_id=? AND claim_token=?",
            event.id(),
            claim);
        metrics.counter("ott.member.outbox.published").increment();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (ExecutionException
          | TimeoutException
          | RestClientException
          | IllegalStateException e) {
        // DB committed before Kafka; unknown broker outcome is retried with same eventId.
        var delay =
            Math.min(300, 1L << Math.min(8, event.attempts()))
                + ThreadLocalRandom.current().nextInt(3);
        jdbc.update(
            "UPDATE outbox_event SET"
                + " claim_token=NULL,claimed_until=NULL,next_attempt_at=clock_timestamp()+(? *"
                + " interval '1 second') WHERE event_id=? AND claim_token=?",
            delay,
            event.id(),
            claim);
        metrics.counter("ott.member.outbox.failed").increment();
      }
    }
  }

  private int schemaId() {
    if (schemaId == null) {
      Map<?, ?> response =
          registry
              .post()
              .uri("/subjects/" + TOPIC + "-value")
              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
              .body(
                  Map.of(
                      "schemaType", "AVRO", "schema", DeviceEventEncoder.WRITER_SCHEMA.toString()))
              .retrieve()
              .body(Map.class);
      if (response == null || !(response.get("id") instanceof Number id))
        throw new IllegalStateException("Registered event schema unavailable");
      schemaId = id.intValue();
    }
    return schemaId;
  }
}
