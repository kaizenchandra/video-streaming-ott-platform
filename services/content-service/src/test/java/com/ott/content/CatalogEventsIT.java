package com.ott.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.ott.content.adapter.outbound.kafka.ContentOutboxRelay;
import com.ott.content.adapter.outbound.kafka.RegistryCodec;
import com.ott.content.application.CatalogService;
import com.ott.contracts.events.CatalogPublishedV1;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.avro.generic.GenericData;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
class CatalogEventsIT {
  static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:3.9.1")
          .withNetwork(NETWORK)
          .withNetworkAliases("broker")
          .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
          .withListener("broker:19092");

  @Container
  static final GenericContainer<?> REGISTRY =
      new GenericContainer<>("confluentinc/cp-schema-registry:7.8.3")
          .withNetwork(NETWORK)
          .withExposedPorts(8081)
          .dependsOn(KAFKA)
          .withEnv("SCHEMA_REGISTRY_HOST_NAME", "registry")
          .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://broker:19092")
          .waitingFor(
              Wait.forHttp("/subjects").forPort(8081).withStartupTimeout(Duration.ofMinutes(2)));

  static final OidcFixture OIDC = new OidcFixture();
  @Autowired CatalogService catalog;
  @Autowired ContentOutboxRelay relay;
  @Autowired RegistryCodec codec;
  @Autowired JdbcTemplate db;

  @AfterAll
  static void close() {
    OIDC.close();
    NETWORK.close();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
    p.add("ott.security.issuer", OIDC::issuer);
    p.add("ott.security.jwks-uri", OIDC::jwks);
    p.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    p.add(
        "ott.schema-registry.url",
        () -> "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081));
    p.add("ott.outbox.enabled", () -> "true");
    p.add("ott.outbox.delay-ms", () -> "3600000");
  }

  @Test
  void pipeline_realMediaEvent_consumesIdempotentlyPublishesAvroAndRoutesPoisonToDlq()
      throws Exception {
    List<String> topics =
        List.of(
            "media.ready.v1", "media.ready.v1.dlq", "catalog.published.v1", "rights.changed.v1");
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(topics.stream().map(t -> new NewTopic(t, 1, (short) 1)).toList())
          .all()
          .get(20, TimeUnit.SECONDS);
    }
    var registry =
        RestClient.builder()
            .baseUrl("http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081))
            .requestFactory(new SimpleClientHttpRequestFactory())
            .build();
    for (var topic : List.of("media.ready.v1", "catalog.published.v1", "rights.changed.v1"))
      registry
          .post()
          .uri("/subjects/" + topic + "-value/versions")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("schemaType", "AVRO", "schema", codec.writer(topic).toString()))
          .retrieve()
          .toBodilessEntity();
    var item = catalog.create("MOVIE", null, 7, "en", "Fixture", "Synthetic metadata");
    var event = new GenericData.Record(codec.writer("media.ready.v1"));
    var eventId = UUID.randomUUID();
    event.put("eventId", eventId.toString());
    event.put("type", "media.ready.v1");
    event.put("aggregateId", item.id().toString());
    event.put("aggregateVersion", 1L);
    event.put("occurredAt", Instant.now().toEpochMilli());
    event.put("producer", "media");
    event.put("traceId", null);
    event.put("contentId", item.id().toString());
    event.put("assetVersion", 1L);
    event.put("originPrefix", "vod/" + item.id() + "/v1/");
    event.put("manifestChecksum", "b".repeat(64));
    byte[] body = codec.encode("media.ready.v1", event);
    try (var producer =
        new KafkaProducer<String, byte[]>(
            Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all"),
            new StringSerializer(),
            new ByteArraySerializer())) {
      producer
          .send(new ProducerRecord<>("media.ready.v1", item.id().toString(), body))
          .get(10, TimeUnit.SECONDS);
      producer
          .send(new ProducerRecord<>("media.ready.v1", item.id().toString(), body))
          .get(10, TimeUnit.SECONDS);
      producer
          .send(new ProducerRecord<>("media.ready.v1", item.id().toString(), new byte[] {99, 1, 2}))
          .get(10, TimeUnit.SECONDS);
    }
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(
                        db.queryForObject(
                            "SELECT count(*) FROM ready_asset WHERE content_id=?",
                            Integer.class,
                            item.id()))
                    .isEqualTo(1));
    var review = catalog.review(item.id(), item.version(), "en");
    catalog.publish(item.id(), review.version(), 1, "en");
    catalog.grant(
        item.id(),
        "IN",
        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        true);
    relay.publishBatch();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND published_at IS NOT"
                    + " NULL",
                Integer.class,
                item.id()))
        .isEqualTo(2);
    var props =
        Map.<String, Object>of(
            "bootstrap.servers",
            KAFKA.getBootstrapServers(),
            "group.id",
            UUID.randomUUID().toString(),
            "auto.offset.reset",
            "earliest",
            "enable.auto.commit",
            false);
    boolean published = false, dlq = false;
    try (var consumer =
        new KafkaConsumer<String, byte[]>(
            props, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of("catalog.published.v1", "media.ready.v1.dlq"));
      var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline && !(published && dlq))
        for (var record : consumer.poll(Duration.ofMillis(500))) {
          if (record.topic().endsWith(".dlq")) {
            assertThat(record.value()).containsExactly((byte) 99, (byte) 1, (byte) 2);
            dlq = true;
          } else {
            var decoded =
                new SpecificDatumReader<CatalogPublishedV1>(
                        codec.writer("catalog.published.v1"), CatalogPublishedV1.SCHEMA$)
                    .read(
                        null,
                        DecoderFactory.get()
                            .binaryDecoder(record.value(), 5, record.value().length - 5, null));
            assertThat(decoded.getContentId()).isEqualTo(item.id().toString());
            assertThat(decoded.getTitle()).isEqualTo("Fixture");
            published = true;
          }
        }
    }
    assertThat(published).isTrue();
    assertThat(dlq).isTrue();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM inbox WHERE event_id=?", Integer.class, eventId))
        .isEqualTo(1);
  }
}
