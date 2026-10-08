package com.ott.operations.adapter.outbound.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.avro.generic.GenericData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "ott.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OperationsOutboxRelay {
  private final JdbcTemplate db;
  private final KafkaTemplate<String, byte[]> kafka;
  private final RegistryCodec codec;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;

  public OperationsOutboxRelay(
      JdbcTemplate db,
      KafkaTemplate<String, byte[]> kafka,
      RegistryCodec codec,
      ObjectMapper json,
      PlatformTransactionManager manager,
      MeterRegistry metrics) {
    this.db = db;
    this.kafka = kafka;
    this.codec = codec;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
    this.metrics = metrics;
  }

  private record Event(
      UUID id,
      UUID aggregate,
      long version,
      String topic,
      String payload,
      Instant at,
      int attempts) {}

  @Scheduled(fixedDelayString = "${ott.outbox.delay-ms:1000}")
  public void publishBatch() {
    var claim = UUID.randomUUID();
    List<Event> events =
        tx.execute(
            s ->
                db.query(
                    """
                    UPDATE outbox_event SET claim_token=?,claimed_until=clock_timestamp()+interval '120 seconds',attempts=attempts+1
                    WHERE event_id IN (SELECT event_id FROM outbox_event WHERE published_at IS NULL
                     AND next_attempt_at<=clock_timestamp() AND (claimed_until IS NULL OR claimed_until<clock_timestamp())
                     ORDER BY created_at,event_id FOR UPDATE SKIP LOCKED LIMIT 10)
                    RETURNING event_id,aggregate_id,aggregate_version,topic,payload,created_at,attempts
                    """,
                    (r, n) ->
                        new Event(
                            r.getObject(1, UUID.class),
                            r.getObject(2, UUID.class),
                            r.getLong(3),
                            r.getString(4),
                            r.getString(5),
                            r.getTimestamp(6).toInstant(),
                            r.getInt(7)),
                    claim));
    for (var event : events) {
      try {
        var schema = codec.writer(event.topic());
        var payload = json.readTree(event.payload());
        var record = new GenericData.Record(schema);
        record.put("eventId", event.id().toString());
        record.put("type", event.topic());
        record.put("aggregateId", event.aggregate().toString());
        record.put("aggregateVersion", event.version());
        record.put("occurredAt", event.at().toEpochMilli());
        record.put("producer", "operations");
        record.put("traceId", null);
        for (var field : schema.getFields().subList(7, schema.getFields().size())) {
          var value = payload.get(field.name());
          if (value == null) {
            if (field.hasDefaultValue()) {
              record.put(
                  field.name(), org.apache.avro.generic.GenericData.get().getDefaultValue(field));
              continue;
            }
            throw new IllegalStateException("Invalid outbox payload");
          }
          Object typed =
              switch (field.schema().getType()) {
                case STRING -> value.asText();
                case LONG -> value.longValue();
                case INT -> value.intValue();
                case BOOLEAN -> value.booleanValue();
                case DOUBLE -> value.doubleValue();
                case UNION -> {
                  if (field.schema().getTypes().size() != 2
                      || field.schema().getTypes().getFirst().getType()
                          != org.apache.avro.Schema.Type.NULL
                      || field.schema().getTypes().getLast().getType()
                          != org.apache.avro.Schema.Type.STRING)
                    throw new IllegalStateException("Unsupported union");
                  yield value.isNull() ? null : value.asText();
                }
                case ARRAY -> {
                  var list = new java.util.ArrayList<String>();
                  value.forEach(v -> list.add(v.asText()));
                  yield list;
                }
                default -> throw new IllegalStateException("Unsupported event field");
              };
          record.put(field.name(), typed);
        }
        kafka
            .send(event.topic(), event.aggregate().toString(), codec.encode(event.topic(), record))
            .get(5, TimeUnit.SECONDS);
        db.update(
            "UPDATE outbox_event SET"
                + " published_at=clock_timestamp(),claimed_until=NULL,claim_token=NULL WHERE"
                + " event_id=? AND claim_token=?",
            event.id(),
            claim);
        metrics.counter("ott.operations.outbox.published", "topic", event.topic()).increment();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (ExecutionException
          | TimeoutException
          | RestClientException
          | JsonProcessingException
          | IllegalStateException e) {
        long delay =
            Math.min(300, 1L << Math.min(8, event.attempts()))
                + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
        db.update(
            "UPDATE outbox_event SET"
                + " claim_token=NULL,claimed_until=NULL,next_attempt_at=clock_timestamp()+(? *"
                + " interval '1 second') WHERE event_id=? AND claim_token=?",
            delay,
            event.id(),
            claim);
        metrics.counter("ott.operations.outbox.failed").increment();
      }
    }
  }
}
