package com.ott.playback;

import static org.assertj.core.api.Assertions.assertThat;

import com.ott.playback.adapter.outbound.kafka.PlaybackOutboxRelay;
import com.ott.playback.adapter.outbound.kafka.RegistryCodec;
import com.ott.playback.application.PlaybackService;
import java.time.Duration;
import java.time.Instant;
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
@SpringBootTest(classes = {PlaybackApplication.class, PlaybackIT.TestPorts.class})
class PlaybackEventsIT {
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

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.5").withExposedPorts(6379);

  static final OidcFixture OIDC = new OidcFixture();
  @Autowired PlaybackService playback;
  @Autowired PlaybackOutboxRelay relay;
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
    p.add("ott.providers.mode", () -> "mock");
    p.add("ott.token.secret", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
    p.add("ott.geo.secret", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
    p.add("ott.workload.token-url", () -> "");
    p.add("ott.workload.client-id", () -> "");
    p.add("ott.workload.client-secret", () -> "");
    p.add("spring.data.redis.host", REDIS::getHost);
    p.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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
  void playback_commitsWithoutKafkaSchemaThenRetriesDurableOutboxWithRegisteredAvro()
      throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic("playback.started.v1", 1, (short) 1),
                  new NewTopic("playback.ended.v1", 1, (short) 1)))
          .all()
          .get(20, TimeUnit.SECONDS);
    }
    var account = UUID.randomUUID();
    var identity =
        new com.ott.playback.application.Eligibility.Identity(
            "test", account.toString(), "fixture");
    var grant =
        playback.start(
            identity,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "IN",
            "events-playback-01");
    playback.end(identity, grant.sessionId());
    relay.publishBatch();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND published_at IS NULL",
                Integer.class,
                grant.sessionId()))
        .isEqualTo(2);
    var registry =
        RestClient.builder()
            .baseUrl("http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081))
            .requestFactory(new SimpleClientHttpRequestFactory())
            .build();
    for (var topic : List.of("playback.started.v1", "playback.ended.v1"))
      registry
          .post()
          .uri("/subjects/" + topic + "-value/versions")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("schemaType", "AVRO", "schema", codec.writer(topic).toString()))
          .retrieve()
          .toBodilessEntity();
    db.update(
        "UPDATE outbox_event SET next_attempt_at=clock_timestamp() WHERE aggregate_id=?",
        grant.sessionId());
    relay.publishBatch();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND published_at IS NOT"
                    + " NULL",
                Integer.class,
                grant.sessionId()))
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
      consumer.subscribe(List.of("playback.started.v1", "playback.ended.v1"));
      var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (System.nanoTime() < deadline && !(entitlement && subscription))
        for (var record : consumer.poll(Duration.ofMillis(500))) {
          assertThat(record.key()).isEqualTo(grant.sessionId().toString());
          if (record.topic().equals("playback.started.v1")) {
            var decoded =
                new SpecificDatumReader<com.ott.contracts.events.PlaybackStartedV1>(
                        codec.writer(record.topic()),
                        com.ott.contracts.events.PlaybackStartedV1.SCHEMA$)
                    .read(
                        null,
                        DecoderFactory.get()
                            .binaryDecoder(record.value(), 5, record.value().length - 5, null));
            assertThat(decoded.getAccountId()).isEqualTo(account.toString());
            assertThat(decoded.getSessionId()).isEqualTo(grant.sessionId().toString());
            entitlement = true;
          } else {
            var decoded =
                new SpecificDatumReader<com.ott.contracts.events.PlaybackEndedV1>(
                        codec.writer(record.topic()),
                        com.ott.contracts.events.PlaybackEndedV1.SCHEMA$)
                    .read(
                        null,
                        DecoderFactory.get()
                            .binaryDecoder(record.value(), 5, record.value().length - 5, null));
            assertThat(decoded.getEndedAt()).isLessThanOrEqualTo(Instant.now().toEpochMilli());
            subscription = true;
          }
        }
    }
    assertThat(entitlement).isTrue();
    assertThat(subscription).isTrue();
  }
}
