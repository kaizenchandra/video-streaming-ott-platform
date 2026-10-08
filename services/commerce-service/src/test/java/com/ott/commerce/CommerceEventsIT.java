package com.ott.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.ott.commerce.adapter.outbound.kafka.CommerceOutboxRelay;
import com.ott.commerce.adapter.outbound.kafka.RegistryCodec;
import com.ott.commerce.application.CommerceService;
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
@SpringBootTest
class CommerceEventsIT {
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
  @Autowired CommerceService commerce;
  @Autowired CommerceOutboxRelay relay;
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
    p.add("ott.payment.mode", () -> "mock");
    p.add("ott.payment.webhook-secret", () -> UUID.randomUUID() + "-" + UUID.randomUUID());
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
  void settlement_commitsWithoutKafkaSchemaThenRetriesDurableOutboxWithRegisteredAvro()
      throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic("entitlement.changed.v1", 1, (short) 1),
                  new NewTopic("subscription.changed.v1", 1, (short) 1)))
          .all()
          .get(20, TimeUnit.SECONDS);
    }
    var plan = commerce.createPlan("Premium", 999, "INR", 30, 2, false, true);
    var account = UUID.randomUUID();
    var order = commerce.purchase(account, plan.id(), "events-purchase-01");
    byte[] payment =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsBytes(
                new CommerceService.Webhook(
                    "payment-event-01", order.id(), order.providerRef(), 999, "INR", "SUCCEEDED"));
    commerce.applyVerifiedWebhook(payment);
    relay.publishBatch();
    assertThat(commerce.entitlement(account).active()).isTrue();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND published_at IS NULL",
                Integer.class,
                account))
        .isEqualTo(2);
    var registry =
        RestClient.builder()
            .baseUrl("http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081))
            .requestFactory(new SimpleClientHttpRequestFactory())
            .build();
    for (var topic : List.of("entitlement.changed.v1", "subscription.changed.v1"))
      registry
          .post()
          .uri("/subjects/" + topic + "-value/versions")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("schemaType", "AVRO", "schema", codec.writer(topic).toString()))
          .retrieve()
          .toBodilessEntity();
    db.update(
        "UPDATE outbox_event SET next_attempt_at=clock_timestamp() WHERE aggregate_id=?", account);
    relay.publishBatch();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND published_at IS NOT"
                    + " NULL",
                Integer.class,
                account))
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
      consumer.subscribe(List.of("entitlement.changed.v1", "subscription.changed.v1"));
      var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (System.nanoTime() < deadline && !(entitlement && subscription))
        for (var record : consumer.poll(Duration.ofMillis(500))) {
          assertThat(record.key()).isEqualTo(account.toString());
          if (record.topic().startsWith("entitlement")) {
            var decoded =
                new SpecificDatumReader<com.ott.contracts.events.EntitlementChangedV1>(
                        codec.writer(record.topic()),
                        com.ott.contracts.events.EntitlementChangedV1.SCHEMA$)
                    .read(
                        null,
                        DecoderFactory.get()
                            .binaryDecoder(record.value(), 5, record.value().length - 5, null));
            assertThat(decoded.getAccountId()).isEqualTo(account.toString());
            assertThat(decoded.getActive()).isTrue();
            assertThat(decoded.getMaxStreams()).isEqualTo(2);
            entitlement = true;
          } else {
            var decoded =
                new SpecificDatumReader<com.ott.contracts.events.SubscriptionChangedV1>(
                        codec.writer(record.topic()),
                        com.ott.contracts.events.SubscriptionChangedV1.SCHEMA$)
                    .read(
                        null,
                        DecoderFactory.get()
                            .binaryDecoder(record.value(), 5, record.value().length - 5, null));
            assertThat(decoded.getState()).isEqualTo("ACTIVE");
            assertThat(decoded.getExpiresAt()).isGreaterThan(Instant.now().toEpochMilli());
            subscription = true;
          }
        }
    }
    assertThat(entitlement).isTrue();
    assertThat(subscription).isTrue();
  }
}
