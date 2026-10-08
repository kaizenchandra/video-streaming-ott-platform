package com.ott.experience;

import static org.assertj.core.api.Assertions.assertThat;

import com.ott.experience.adapter.outbound.kafka.RegistryCodec;
import com.ott.experience.application.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
@SpringBootTest(classes = {ExperienceApplication.class, WatchIT.Ports.class, SearchIT.Ports.class})
class CatalogIndexEventsIT {
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

  @Container static final GenericContainer<?> SEARCH = SearchIT.SEARCH;
  static final OidcFixture OIDC = new OidcFixture();
  @Autowired WatchService watch;
  @Autowired SearchIT.Source source;
  @Autowired com.ott.experience.application.RecommendationSignals signals;
  @Autowired com.ott.experience.application.SearchProjection projection;
  @Autowired com.ott.experience.application.SearchIndexer indexer;
  @Autowired WatchIT.TestAccess access;
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
    p.add("ott.outbox.enabled", () -> "false");
    p.add("ott.search.enabled", () -> "true");
    p.add("ott.recommendations.enabled", () -> "true");
    p.add("ott.search.url", () -> "http://" + SEARCH.getHost() + ":" + SEARCH.getMappedPort(9200));
    p.add("ott.search.index", () -> "ott-kafka-catalog");
    p.add("ott.search.replicas", () -> "0");

    p.add("ott.outbox.delay-ms", () -> "3600000");
  }

  @Test
  void playbackKafka_optInAndDuplicates_createOneDailyContribution() throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic("playback.started.v1", 1, (short) 1),
                  new NewTopic("playback.started.v1.dlq", 1, (short) 1)))
          .all()
          .get(20, TimeUnit.SECONDS);
    }
    var registry =
        RestClient.builder()
            .baseUrl("http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081))
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
            .build();
    registry
        .post()
        .uri("/subjects/playback.started.v1-value/versions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            Map.of("schemaType", "AVRO", "schema", codec.writer("playback.started.v1").toString()))
        .retrieve()
        .toBodilessEntity();
    var profile = UUID.randomUUID();
    var account = UUID.randomUUID();
    var session = UUID.randomUUID();
    var content = UUID.randomUUID();
    var device = UUID.randomUUID();
    var identity = new com.ott.experience.application.ViewerAccess.Identity("signals-" + profile);
    access.profiles.put(
        profile,
        new WatchIT.Owned(
            identity.viewerToken(),
            new com.ott.experience.application.ViewerAccess.Profile(account, profile, 18)));
    signals.preference(identity, profile, true);
    var record =
        new org.apache.avro.generic.GenericData.Record(codec.writer("playback.started.v1"));
    var event = UUID.randomUUID();
    record.put("eventId", event.toString());
    record.put("type", "playback.started.v1");
    record.put("aggregateId", session.toString());
    record.put("aggregateVersion", 1L);
    record.put("occurredAt", Instant.now().toEpochMilli());
    record.put("producer", "playback");
    record.put("traceId", null);
    record.put("accountId", account.toString());
    record.put("profileId", profile.toString());
    record.put("sessionId", session.toString());
    record.put("contentId", content.toString());
    record.put("deviceId", device.toString());
    try (var producer =
        new org.apache.kafka.clients.producer.KafkaProducer<String, byte[]>(
            Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all"),
            new org.apache.kafka.common.serialization.StringSerializer(),
            new org.apache.kafka.common.serialization.ByteArraySerializer())) {
      byte[] bytes = codec.encode("playback.started.v1", record);
      for (int n = 0; n < 2; n++)
        producer
            .send(
                new org.apache.kafka.clients.producer.ProducerRecord<>(
                    "playback.started.v1", session.toString(), bytes))
            .get(10, TimeUnit.SECONDS);
    }
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      if (db.queryForObject(
              "SELECT count(*) FROM recommendation_inbox WHERE event_id=?", Integer.class, event)
          == 1) break;
      Thread.sleep(100);
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM daily_view WHERE profile_id=?", Integer.class, profile))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM recommendation_inbox WHERE event_id=?", Integer.class, event))
        .isEqualTo(1);
  }

  @Test
  void catalogKafka_duplicatesAndPoison_indexDurableSnapshotAndParkInvalidRecord()
      throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic("catalog.published.v1", 1, (short) 1),
                  new NewTopic("catalog.published.v1.dlq", 1, (short) 1)))
          .all()
          .get(20, TimeUnit.SECONDS);
    }
    var registry =
        RestClient.builder()
            .baseUrl("http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081))
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
            .build();
    registry
        .post()
        .uri("/subjects/catalog.published.v1-value/versions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            Map.of("schemaType", "AVRO", "schema", codec.writer("catalog.published.v1").toString()))
        .retrieve()
        .toBodilessEntity();
    var id = UUID.randomUUID();
    var snapshot =
        new com.ott.experience.application.CatalogProjectionSource.Snapshot(
            id,
            3,
            "MOVIE",
            7,
            Map.of(
                "en",
                new com.ott.experience.application.CatalogProjectionSource.Localized(
                    "Kafka Indexed Fixture", "Immutable metadata")),
            List.of("drama"),
            List.of(),
            null,
            Instant.now());
    source.rows.put(id + ":3", snapshot);
    source.latest.put(id, snapshot);
    var event =
        new org.apache.avro.generic.GenericData.Record(codec.writer("catalog.published.v1"));
    var eventId = UUID.randomUUID();
    event.put("eventId", eventId.toString());
    event.put("type", "catalog.published.v1");
    event.put("aggregateId", id.toString());
    event.put("aggregateVersion", 3L);
    event.put("occurredAt", Instant.now().toEpochMilli());
    event.put("producer", "content");
    event.put("traceId", null);
    event.put("contentId", id.toString());
    event.put("contentVersion", 3L);
    event.put("title", "Kafka Indexed Fixture");
    event.put("contentType", "MOVIE");
    event.put("maturity", 7);
    event.put("locales", List.of("en"));
    try (var producer =
        new org.apache.kafka.clients.producer.KafkaProducer<String, byte[]>(
            Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all"),
            new org.apache.kafka.common.serialization.StringSerializer(),
            new org.apache.kafka.common.serialization.ByteArraySerializer())) {
      byte[] bytes = codec.encode("catalog.published.v1", event);
      producer
          .send(
              new org.apache.kafka.clients.producer.ProducerRecord<>(
                  "catalog.published.v1", id.toString(), bytes))
          .get(10, TimeUnit.SECONDS);
      producer
          .send(
              new org.apache.kafka.clients.producer.ProducerRecord<>(
                  "catalog.published.v1", id.toString(), bytes))
          .get(10, TimeUnit.SECONDS);
      producer
          .send(
              new org.apache.kafka.clients.producer.ProducerRecord<>(
                  "catalog.published.v1", id.toString(), new byte[] {1, 2, 3}))
          .get(10, TimeUnit.SECONDS);
    }
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (db.queryForObject(
              "SELECT count(*) FROM search_document WHERE content_id=? AND indexed_version=3",
              Integer.class,
              id)
          == 1) break;
      Thread.sleep(100);
    }
    assertThat(projection.get(id).version()).isEqualTo(3);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM search_inbox WHERE event_id=?", Integer.class, eventId))
        .isEqualTo(1);
    var raw =
        RestClient.builder()
            .baseUrl("http://" + SEARCH.getHost() + ":" + SEARCH.getMappedPort(9200))
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
            .build();
    raw.post().uri("/ott-kafka-catalog/_refresh").retrieve().toBodilessEntity();
    var hits =
        raw.post()
            .uri("/ott-kafka-catalog/_search")
            .body(Map.of("query", Map.of("term", Map.of("contentId", id.toString()))))
            .retrieve()
            .body(com.fasterxml.jackson.databind.JsonNode.class);
    assertThat(hits.at("/hits/hits").size()).isEqualTo(1);
    boolean parked = false;
    try (var consumer =
        new KafkaConsumer<String, byte[]>(
            Map.of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "group.id",
                UUID.randomUUID().toString(),
                "auto.offset.reset",
                "earliest",
                "enable.auto.commit",
                false),
            new StringDeserializer(),
            new ByteArrayDeserializer())) {
      consumer.subscribe(List.of("catalog.published.v1.dlq"));
      deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (System.nanoTime() < deadline && !parked)
        for (var record : consumer.poll(Duration.ofMillis(500))) {
          assertThat(record.value()).containsExactly(1, 2, 3);
          parked = true;
        }
    }
    assertThat(parked).isTrue();
  }
}
