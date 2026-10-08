package com.ott.experience;

import static org.assertj.core.api.Assertions.assertThat;

import com.ott.experience.adapter.outbound.kafka.ExperienceOutboxRelay;
import com.ott.experience.adapter.outbound.kafka.RegistryCodec;
import com.ott.experience.application.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.avro.io.DecoderFactory;
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
@SpringBootTest(classes = {ExperienceApplication.class, WatchIT.Ports.class})
class ExperienceEventsIT {
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
  @Autowired WatchService watch;
  @Autowired WatchIT.TestAccess access;
  @Autowired ExperienceOutboxRelay relay;
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
  void events_registryEvolutionAndOutage_publishTypedProfileKeyedVersionedRecords()
      throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic("watch.updated.v1", 1, (short) 1),
                  new NewTopic("reaction.changed.v1", 1, (short) 1)))
          .all()
          .get(20, TimeUnit.SECONDS);
    }
    var profile = UUID.randomUUID();
    var content = UUID.randomUUID();
    var account = UUID.randomUUID();
    var device = UUID.randomUUID();
    String token = "events-grant";
    var identity = new com.ott.experience.application.ViewerAccess.Identity("events-viewer");
    access.profiles.put(
        profile,
        new WatchIT.Owned(
            identity.viewerToken(),
            new com.ott.experience.application.ViewerAccess.Profile(account, profile, 18)));
    access.grants.put(
        token,
        new com.ott.experience.application.ViewerAccess.Grant(
            UUID.randomUUID(), account, profile, content, device, 100));
    watch.observe(
        identity,
        new WatchService.Observation(
            UUID.randomUUID(), profile, content, device, token, false, 1, 1000, 10000));
    watch.rate(identity, profile, content, 5);
    watch.react(identity, profile, content, "LIKE");
    watch.rate(identity, profile, content, null);
    watch.react(identity, profile, content, null);
    relay.publishBatch();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class))
        .isEqualTo(5);
    var registry =
        RestClient.builder()
            .baseUrl("http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081))
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
            .build();
    var current = codec.writer("reaction.changed.v1");
    var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(current.toString());
    var fields = (com.fasterxml.jackson.databind.node.ArrayNode) tree.path("fields");
    fields.remove(fields.size() - 1);
    fields.remove(fields.size() - 1);
    var original = new org.apache.avro.Schema.Parser().parse(tree.toString());
    registry
        .put()
        .uri("/config/reaction.changed.v1-value")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("compatibility", "BACKWARD_TRANSITIVE"))
        .retrieve()
        .toBodilessEntity();
    for (var schema : List.of(original, current))
      registry
          .post()
          .uri("/subjects/reaction.changed.v1-value/versions")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("schemaType", "AVRO", "schema", schema.toString()))
          .retrieve()
          .toBodilessEntity();
    assertThat(
            org.apache.avro.SchemaCompatibility.checkReaderWriterCompatibility(current, original)
                .getType())
        .isEqualTo(org.apache.avro.SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
    assertThat(
            org.apache.avro.SchemaCompatibility.checkReaderWriterCompatibility(original, current)
                .getType())
        .isEqualTo(org.apache.avro.SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
    registry
        .post()
        .uri("/subjects/watch.updated.v1-value/versions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("schemaType", "AVRO", "schema", codec.writer("watch.updated.v1").toString()))
        .retrieve()
        .toBodilessEntity();
    db.update("UPDATE outbox_event SET next_attempt_at=clock_timestamp()");
    relay.publishBatch();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL", Integer.class))
        .isEqualTo(5);
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
    int progress = 0, reactions = 0;
    boolean removed = false;
    try (var consumer =
        new KafkaConsumer<String, byte[]>(
            props, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of("watch.updated.v1", "reaction.changed.v1"));
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (System.nanoTime() < deadline && progress + reactions < 5)
        for (var record : consumer.poll(Duration.ofMillis(500))) {
          assertThat(record.key()).isEqualTo(profile.toString());
          var writer = codec.writer(record.topic());
          var decoded =
              new org.apache.avro.generic.GenericDatumReader<org.apache.avro.generic.GenericRecord>(
                      writer)
                  .read(
                      null,
                      DecoderFactory.get()
                          .binaryDecoder(record.value(), 5, record.value().length - 5, null));
          assertThat(decoded.get("producer").toString()).isEqualTo("experience");
          assertThat(decoded.get("profileId").toString()).isEqualTo(profile.toString());
          if (record.topic().equals("watch.updated.v1")) {
            progress++;
            assertThat(decoded.get("sessionEpoch")).isEqualTo(100L);
          } else {
            reactions++;
            if (Boolean.TRUE.equals(decoded.get("removed"))) {
              removed = true;
              assertThat(decoded.get("rating")).isEqualTo(0);
              assertThat(decoded.get("reaction")).isNull();
            }
            var oldReader =
                new org.apache.avro.generic.GenericDatumReader<
                        org.apache.avro.generic.GenericRecord>(writer, original)
                    .read(
                        null,
                        DecoderFactory.get()
                            .binaryDecoder(record.value(), 5, record.value().length - 5, null));
            assertThat(oldReader.get("rating")).isEqualTo(decoded.get("rating"));
          }
        }
    }
    assertThat(progress).isEqualTo(1);
    assertThat(reactions).isEqualTo(4);
    assertThat(removed).isTrue();
  }
}
