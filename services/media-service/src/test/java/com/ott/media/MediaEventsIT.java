package com.ott.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.ott.media.adapter.outbound.kafka.MediaOutboxRelay;
import com.ott.media.adapter.outbound.kafka.RegistryCodec;
import com.ott.media.application.MediaJobs;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
class MediaEventsIT {
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
  @Autowired MediaJobs jobs;
  @Autowired MediaOutboxRelay relay;
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
    p.add("ott.media.dispatch-delay-ms", () -> "3600000");
    p.add("ott.media.worker", () -> "false");
    p.add("ott.temporal.target", () -> "localhost:1");
    p.add("ott.storage.endpoint", () -> "http://localhost:1");
    p.add("ott.storage.quarantine", () -> "fixture-quarantine");
    p.add("ott.storage.origin", () -> "fixture-origin");
    System.setProperty("aws.accessKeyId", "test");
    System.setProperty("aws.secretAccessKey", "test");
  }

  @Test
  void media_readyAndFailed_retryRegisteredTypedOutbox() throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic("media.ready.v1", 1, (short) 1),
                  new NewTopic("media.failed.v1", 1, (short) 1)))
          .all()
          .get(20, TimeUnit.SECONDS);
    }
    var ready = jobs.prepare(UUID.randomUUID(), 1, 100, "c".repeat(64)).job();
    db.update("UPDATE media_job SET state='UPLOADED' WHERE id=?", ready.id());
    jobs.ready(ready.id(), "d".repeat(64));
    var failed = jobs.prepare(UUID.randomUUID(), 1, 100, "c".repeat(64)).job();
    jobs.failed(failed.id(), "INVALID_MEDIA");
    relay.publishBatch();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class))
        .isEqualTo(2);
    var registry =
        RestClient.builder()
            .baseUrl("http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081))
            .requestFactory(new SimpleClientHttpRequestFactory())
            .build();
    for (var topic : List.of("media.ready.v1", "media.failed.v1"))
      registry
          .post()
          .uri("/subjects/" + topic + "-value/versions")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("schemaType", "AVRO", "schema", codec.writer(topic).toString()))
          .retrieve()
          .toBodilessEntity();
    db.update("UPDATE outbox_event SET next_attempt_at=clock_timestamp()");
    relay.publishBatch();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL", Integer.class))
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
    boolean entitlement = false, subscription = false;
    try (var consumer =
        new KafkaConsumer<String, byte[]>(
            props, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of("media.ready.v1", "media.failed.v1"));
      var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (System.nanoTime() < deadline && !(entitlement && subscription))
        for (var record : consumer.poll(Duration.ofMillis(500))) {
          assertThat(record.key())
              .isIn(ready.contentId().toString(), failed.contentId().toString());
          if (record.topic().equals("media.ready.v1")) {
            var decoded =
                new SpecificDatumReader<com.ott.contracts.events.MediaReadyV1>(
                        codec.writer(record.topic()), com.ott.contracts.events.MediaReadyV1.SCHEMA$)
                    .read(
                        null,
                        DecoderFactory.get()
                            .binaryDecoder(record.value(), 5, record.value().length - 5, null));
            assertThat(decoded.getContentId()).isEqualTo(ready.contentId().toString());
            assertThat(decoded.getAssetVersion()).isEqualTo(1);
            assertThat(decoded.getManifestChecksum()).isEqualTo("d".repeat(64));
            entitlement = true;
          } else {
            var decoded =
                new SpecificDatumReader<com.ott.contracts.events.MediaFailedV1>(
                        codec.writer(record.topic()),
                        com.ott.contracts.events.MediaFailedV1.SCHEMA$)
                    .read(
                        null,
                        DecoderFactory.get()
                            .binaryDecoder(record.value(), 5, record.value().length - 5, null));
            assertThat(decoded.getJobId()).isEqualTo(failed.id().toString());
            assertThat(decoded.getReasonCode()).isEqualTo("INVALID_MEDIA");
            subscription = true;
          }
        }
    }
    assertThat(entitlement).isTrue();
    assertThat(subscription).isTrue();
  }
}
