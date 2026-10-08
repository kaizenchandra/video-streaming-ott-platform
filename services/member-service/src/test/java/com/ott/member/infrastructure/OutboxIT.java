package com.ott.member.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ott.contracts.events.MemberDeviceRevokedV1;
import com.ott.member.adapter.outbound.kafka.OutboxRelay;
import com.ott.member.application.MemberService;
import com.ott.member.domain.model.MemberModels.Identity;
import java.nio.ByteBuffer;
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
class OutboxIT {
  static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:3.9.1")
          .withNetwork(NETWORK)
          .withNetworkAliases("broker")
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
  @Autowired MemberService service;
  @Autowired OutboxRelay relay;
  @Autowired JdbcTemplate jdbc;

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
    p.add("ott.outbox.delay-ms", () -> "3600000");
  }

  @Test
  void revoke_committedOutbox_realKafkaReceivesRegisteredAvroAndAckMarksPublished()
      throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic("member.device-revoked.v1", 1, (short) 1)))
          .all()
          .get(20, TimeUnit.SECONDS);
    }
    var registration =
        RestClient.builder()
            .baseUrl("http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081))
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
            .build()
            .post()
            .uri("/subjects/member.device-revoked.v1-value/versions")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "schemaType",
                    "AVRO",
                    "schema",
                    com.ott.member.adapter.outbound.kafka.DeviceEventEncoder.WRITER_SCHEMA
                        .toString()))
            .retrieve()
            .body(Map.class);
    int expectedSchema = ((Number) registration.get("id")).intValue();
    var identity = new Identity(OIDC.issuer(), UUID.randomUUID().toString(), Instant.now());
    var account = service.provision(identity);
    var device =
        service.registerDevice(
            identity, "install-test001", "TV", List.of("H264"), List.of("WIDEVINE"));
    service.revokeDevice(identity, device.id());
    relay.publishBatch();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND published_at IS NOT"
                    + " NULL",
                Integer.class,
                device.id()))
        .isEqualTo(1);
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
    try (var consumer =
        new KafkaConsumer<String, byte[]>(
            props, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of("member.device-revoked.v1"));
      var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      boolean found = false;
      while (System.nanoTime() < deadline && !found) {
        for (var record : consumer.poll(Duration.ofMillis(500))) {
          assertThat(record.key()).isEqualTo(account.id().toString());
          assertThat(record.value()[0]).isZero();
          assertThat(ByteBuffer.wrap(record.value(), 1, 4).getInt()).isEqualTo(expectedSchema);
          var reader =
              new SpecificDatumReader<MemberDeviceRevokedV1>(MemberDeviceRevokedV1.SCHEMA$);
          var event =
              reader.read(
                  null,
                  DecoderFactory.get()
                      .binaryDecoder(record.value(), 5, record.value().length - 5, null));
          assertThat(event.getDeviceId()).isEqualTo(device.id().toString());
          assertThat(event.getAccountId()).isEqualTo(account.id().toString());
          assertThat(event.getAggregateVersion()).isEqualTo(2);
          found = true;
        }
      }
      assertThat(found).isTrue();
    }
    relay.publishBatch();
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM outbox_event WHERE aggregate_id=?",
                Integer.class,
                device.id()))
        .isEqualTo(1);
  }
}
