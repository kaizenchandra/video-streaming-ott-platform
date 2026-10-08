package com.ott.member.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
class MessagingIT {
  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.5").withExposedPorts(6379);

  @Test
  void kafka_producedRecord_realBrokerDeliversWithKey() throws Exception {
    String topic = "foundation-" + UUID.randomUUID();
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
          .all()
          .get(20, TimeUnit.SECONDS);
    }
    var producerProps =
        Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all");
    try (var producer =
        new KafkaProducer<String, String>(
            producerProps, new StringSerializer(), new StringSerializer())) {
      producer
          .send(new ProducerRecord<>(topic, "aggregate-key", "infrastructure-test"))
          .get(10, TimeUnit.SECONDS);
    }
    var consumerProps =
        Map.<String, Object>of(
            "bootstrap.servers",
            KAFKA.getBootstrapServers(),
            "group.id",
            UUID.randomUUID().toString(),
            "auto.offset.reset",
            "earliest",
            "enable.auto.commit",
            false);
    try (var consumer =
        new KafkaConsumer<String, String>(
            consumerProps, new StringDeserializer(), new StringDeserializer())) {
      consumer.subscribe(List.of(topic));
      var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      boolean found = false;
      while (System.nanoTime() < deadline && !found) {
        for (var record : consumer.poll(Duration.ofMillis(500))) {
          assertThat(record.key()).isEqualTo("aggregate-key");
          assertThat(record.value()).isEqualTo("infrastructure-test");
          found = true;
        }
      }
      assertThat(found).isTrue();
    }
  }

  @Test
  void redis_ephemeralCounter_realAtomicIncrementAndTtl() {
    var client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    try (var connection = client.connect()) {
      var redis = connection.sync();
      String key = "foundation:" + UUID.randomUUID();
      assertThat(redis.incr(key)).isEqualTo(1);
      redis.expire(key, 30);
      assertThat(redis.incr(key)).isEqualTo(2);
      assertThat(redis.ttl(key)).isBetween(1L, 30L);
    } finally {
      client.shutdown();
    }
  }
}
