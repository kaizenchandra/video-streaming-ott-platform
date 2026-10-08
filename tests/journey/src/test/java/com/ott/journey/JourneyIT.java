package com.ott.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.commerce.CommerceApplication;
import com.ott.content.ContentApplication;
import com.ott.media.application.MediaFailure;
import com.ott.media.application.MediaTools;
import com.ott.member.MemberApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class JourneyIT {
  @Container
  static final PostgreSQLContainer<?> MEMBER_DB = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final PostgreSQLContainer<?> CONTENT_DB = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final PostgreSQLContainer<?> COMMERCE_DB = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final PostgreSQLContainer<?> ENGAGEMENT_DB = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final PostgreSQLContainer<?> EXPERIENCE_DB = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final PostgreSQLContainer<?> INSIGHTS_DB = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final PostgreSQLContainer<?> OPERATIONS_DB = new PostgreSQLContainer<>("postgres:17.6");

  static final OidcFixture OIDC = new OidcFixture();
  static final String SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();

  @Container
  static final PostgreSQLContainer<?> PLAYBACK_DB = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final org.testcontainers.containers.GenericContainer<?> REDIS =
      new org.testcontainers.containers.GenericContainer<>("redis:7.4.5").withExposedPorts(6379);

  static final org.testcontainers.containers.Network NETWORK =
      org.testcontainers.containers.Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> MEDIA_DB = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final org.testcontainers.containers.GenericContainer<?> S3 =
      new org.testcontainers.containers.GenericContainer<>("localstack/localstack:4.3.0")
          .withEnv("SERVICES", "s3")
          .withExposedPorts(4566)
          .waitingFor(
              org.testcontainers.containers.wait.strategy.Wait.forHttp("/_localstack/health")
                  .forPort(4566));

  @Container
  static final org.testcontainers.containers.GenericContainer<?> TEMPORAL =
      new org.testcontainers.containers.GenericContainer<>(
              "temporalio/temporal@sha256:ad4c82c97bd12b417d1ea942610dbcd511afb250c4d5ed26c694009533df447e")
          .withCommand("server", "start-dev", "--ip", "0.0.0.0")
          .withExposedPorts(7233);

  @Container
  static final org.testcontainers.containers.GenericContainer<?> NATIVE =
      new org.testcontainers.containers.GenericContainer<>("jrottenberg/ffmpeg:7.1-ubuntu")
          .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
          .withCommand("-c", "sleep infinity");

  @Container
  static final org.testcontainers.kafka.KafkaContainer KAFKA =
      new org.testcontainers.kafka.KafkaContainer("apache/kafka:3.9.1")
          .withNetwork(NETWORK)
          .withNetworkAliases("broker")
          .withListener("broker:19092")
          .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

  @Container
  static final org.testcontainers.containers.GenericContainer<?> REGISTRY =
      new org.testcontainers.containers.GenericContainer<>("confluentinc/cp-schema-registry:7.8.3")
          .withNetwork(NETWORK)
          .withExposedPorts(8081)
          .dependsOn(KAFKA)
          .withEnv("SCHEMA_REGISTRY_HOST_NAME", "registry")
          .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://broker:19092")
          .waitingFor(
              org.testcontainers.containers.wait.strategy.Wait.forHttp("/subjects").forPort(8081));

  @Container
  static final org.testcontainers.containers.GenericContainer<?> SEARCH =
      new org.testcontainers.containers.GenericContainer<>("opensearchproject/opensearch:2.19.3")
          .withExposedPorts(9200)
          .withEnv("discovery.type", "single-node")
          .withEnv("DISABLE_SECURITY_PLUGIN", "true")
          .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
          .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
          .waitingFor(
              org.testcontainers.containers.wait.strategy.Wait.forHttp(
                      "/_cluster/health?wait_for_status=yellow&timeout=30s")
                  .forPort(9200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  static ConfigurableApplicationContext member,
      content,
      commerce,
      playback,
      media,
      experience,
      engagement,
      insights,
      operations;
  static RestClient memberHttp,
      contentHttp,
      commerceHttp,
      playbackHttp,
      mediaHttp,
      experienceHttp,
      engagementHttp,
      insightsHttp,
      operationsHttp;
  static final ObjectMapper JSON = new ObjectMapper();

  @BeforeAll
  static void start() throws Exception {
    try (var admin =
        org.apache.kafka.clients.admin.AdminClient.create(
            Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new org.apache.kafka.clients.admin.NewTopic("audit.recorded.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("abuse.signal.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("abuse.action.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic(
                      "analytics.received.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("qoe.received.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic(
                      "subscription.changed.v1.dlq", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("ad.impression.v1.dlq", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("media.ready.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("live.changed.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("ad.impression.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("payment.failed.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic(
                      "entitlement.changed.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic(
                      "subscription.changed.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic(
                      "payment.failed.v1.dlq", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("live.changed.v1.dlq", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic(
                      "notification.requested.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic(
                      "notification.requested.v1.dlq", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic(
                      "notification.delivered.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("media.failed.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("media.ready.v1.dlq", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("catalog.published.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic(
                      "catalog.published.v1.dlq", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("playback.started.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic("playback.ended.v1", 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic(
                      "playback.started.v1.dlq", 1, (short) 1)))
          .all()
          .get(20, java.util.concurrent.TimeUnit.SECONDS);
    }
    var registry =
        RestClient.builder()
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
            .baseUrl("http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081))
            .build();
    for (var topic :
        List.of(
            "audit.recorded.v1",
            "abuse.signal.v1",
            "abuse.action.v1",
            "analytics.received.v1",
            "qoe.received.v1",
            "media.ready.v1",
            "media.failed.v1",
            "catalog.published.v1",
            "live.changed.v1",
            "ad.impression.v1",
            "notification.requested.v1",
            "notification.delivered.v1",
            "payment.failed.v1",
            "entitlement.changed.v1",
            "subscription.changed.v1",
            "playback.started.v1",
            "playback.ended.v1")) {
      try (var input = JourneyIT.class.getResourceAsStream("/events/" + topic + ".avsc")) {
        byte[] schemaBytes = input.readAllBytes();
        if (topic.equals("qoe.received.v1")
            || topic.equals("ad.impression.v1")
            || topic.equals("subscription.changed.v1")) {
          var old = JSON.readTree(schemaBytes);
          var fields = (com.fasterxml.jackson.databind.node.ArrayNode) old.get("fields");
          if (topic.equals("qoe.received.v1"))
            while (fields.size() > 13) fields.remove(fields.size() - 1);
          else fields.remove(fields.size() - 1);
          registry
              .put()
              .uri("/config/" + topic + "-value")
              .contentType(MediaType.APPLICATION_JSON)
              .body(Map.of("compatibility", "BACKWARD_TRANSITIVE"))
              .retrieve()
              .toBodilessEntity();
          registry
              .post()
              .uri("/subjects/" + topic + "-value/versions")
              .contentType(MediaType.APPLICATION_JSON)
              .body(Map.of("schema", old.toString(), "schemaType", "AVRO"))
              .retrieve()
              .toBodilessEntity();
        }
        registry
            .post()
            .uri("/subjects/" + topic + "-value/versions")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "schema",
                    new String(schemaBytes, StandardCharsets.UTF_8),
                    "schemaType",
                    "AVRO"))
            .retrieve()
            .toBodilessEntity();
      }
    }

    member = app(MemberApplication.class, "member", MEMBER_DB, Map.of());
    memberHttp = client(member);
    content = app(ContentApplication.class, "content", CONTENT_DB, contentProperties());
    contentHttp = client(content);

    var commerceProps = new java.util.HashMap<String, String>();
    commerceProps.put("ott.payment.mode", "mock");
    commerceProps.put("ott.payment.webhook-secret", SECRET);
    commerceProps.put("ott.member.url", url(member));
    commerceProps.put("ott.outbox.enabled", "true");
    commerceProps.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
    commerceProps.put(
        "spring.kafka.producer.key-serializer",
        "org.apache.kafka.common.serialization.StringSerializer");
    commerceProps.put(
        "spring.kafka.producer.value-serializer",
        "org.apache.kafka.common.serialization.ByteArraySerializer");
    commerceProps.put(
        "ott.schema-registry.url",
        "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081));
    commerce = app(CommerceApplication.class, "commerce", COMMERCE_DB, commerceProps);
    commerceHttp = client(commerce);
    var playbackProps = new java.util.HashMap<String, String>();
    playbackProps.put("ott.outbox.enabled", "true");
    playbackProps.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
    playbackProps.put(
        "spring.kafka.producer.key-serializer",
        "org.apache.kafka.common.serialization.StringSerializer");
    playbackProps.put(
        "spring.kafka.producer.value-serializer",
        "org.apache.kafka.common.serialization.ByteArraySerializer");
    playbackProps.put(
        "ott.schema-registry.url",
        "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081));
    playbackProps.put("ott.providers.mode", "mock");
    playbackProps.put("ott.token.secret", SECRET);
    playbackProps.put("ott.geo.secret", SECRET);
    playbackProps.put("ott.owners.member", url(member));
    playbackProps.put("ott.owners.content", url(content));
    playbackProps.put("ott.owners.commerce", url(commerce));
    playbackProps.put("ott.workload.token-url", OIDC.tokenEndpoint());
    playbackProps.put("ott.workload.client-id", "playback-test");
    playbackProps.put("ott.workload.client-secret", "fixture-secret");
    playbackProps.put("spring.data.redis.host", REDIS.getHost());
    playbackProps.put("spring.data.redis.port", REDIS.getMappedPort(6379).toString());
    playback =
        app(com.ott.playback.PlaybackApplication.class, "playback", PLAYBACK_DB, playbackProps);
    playbackHttp = client(playback);
    experience =
        app(
            com.ott.experience.ExperienceApplication.class,
            "experience",
            EXPERIENCE_DB,
            experienceProperties());
    experienceHttp = client(experience);
    var adProps = new java.util.HashMap<String, String>();
    adProps.put("ott.owners.member", url(member));
    adProps.put("ott.owners.content", url(content));
    adProps.put("ott.notifications.enabled", "true");
    adProps.put(
        "spring.kafka.consumer.key-deserializer",
        "org.apache.kafka.common.serialization.StringDeserializer");
    adProps.put(
        "spring.kafka.consumer.value-deserializer",
        "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    adProps.put("spring.kafka.consumer.enable-auto-commit", "false");
    adProps.put("spring.kafka.consumer.auto-offset-reset", "earliest");
    adProps.put("spring.kafka.listener.ack-mode", "record");
    adProps.put("ott.owners.commerce", url(commerce));
    adProps.put("ott.owners.playback", url(playback));
    adProps.put("ott.workload.token-url", OIDC.tokenEndpoint());
    adProps.put("ott.workload.client-id", "playback-test");
    adProps.put("ott.workload.client-secret", "fixture-secret");
    adProps.put("ott.ads.creative-hosts", "local-cdn.invalid");
    adProps.put("ott.outbox.enabled", "true");
    adProps.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
    adProps.put(
        "spring.kafka.producer.key-serializer",
        "org.apache.kafka.common.serialization.StringSerializer");
    adProps.put(
        "spring.kafka.producer.value-serializer",
        "org.apache.kafka.common.serialization.ByteArraySerializer");
    adProps.put(
        "ott.schema-registry.url",
        "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081));
    engagement =
        app(com.ott.engagement.EngagementApplication.class, "engagement", ENGAGEMENT_DB, adProps);
    engagementHttp = client(engagement);
    var insightProps = new java.util.HashMap<String, String>(adProps);
    insightProps.put("ott.insights.consumers-enabled", "true");
    insightProps.put(
        "ott.insights.assignment-secret",
        com.ott.insights.application.TelemetryService.hash("assignment-fixture"));
    insightProps.put(
        "ott.insights.telemetry-secret",
        com.ott.insights.application.TelemetryService.hash("privacy-fixture"));
    insights =
        app(com.ott.insights.InsightsApplication.class, "insights", INSIGHTS_DB, insightProps);
    insightsHttp = client(insights);
    var opsProps = new java.util.HashMap<String, String>(insightProps);
    opsProps.put("ott.owners.commerce", url(commerce));
    opsProps.put("ott.workload.client-id", "operations-test");
    opsProps.put("ott.operations.consumers-enabled", "true");
    operations =
        app(com.ott.operations.OperationsApplication.class, "operations", OPERATIONS_DB, opsProps);
    operationsHttp = client(operations);

    System.setProperty("aws.accessKeyId", "test");
    System.setProperty("aws.secretAccessKey", "test");
    var props = new java.util.HashMap<String, String>();
    props.put("ott.storage.endpoint", "http://" + S3.getHost() + ":" + S3.getMappedPort(4566));
    props.put("ott.storage.region", "us-east-1");
    props.put("ott.storage.quarantine", "ott-quarantine");
    props.put("ott.storage.origin", "ott-origin");
    props.put("ott.temporal.target", TEMPORAL.getHost() + ":" + TEMPORAL.getMappedPort(7233));
    props.put("ott.media.worker", "true");
    props.put("ott.media.protection", "mock-clear");
    props.put("ott.media.ffmpeg", "ffmpeg");
    props.put("ott.media.ffprobe", "ffprobe");
    props.put("ott.media.profiles", "classpath:encoding-profiles.json");
    props.put("ott.outbox.enabled", "true");
    props.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
    props.put(
        "spring.kafka.producer.key-serializer",
        "org.apache.kafka.common.serialization.StringSerializer");
    props.put(
        "spring.kafka.producer.value-serializer",
        "org.apache.kafka.common.serialization.ByteArraySerializer");
    props.put("spring.kafka.producer.acks", "all");
    props.put(
        "ott.schema-registry.url",
        "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081));
    media = app(com.ott.media.MediaApplication.class, "media", MEDIA_DB, props);
    mediaHttp = client(media);
    var s3 = media.getBean(software.amazon.awssdk.services.s3.S3Client.class);
    s3.createBucket(b -> b.bucket("ott-quarantine"));
    s3.createBucket(b -> b.bucket("ott-origin"));
  }

  static Map<String, String> contentProperties() {
    var props = new java.util.HashMap<String, String>();
    props.put("ott.live.feed-secret", SECRET);
    props.put("ott.media-consumer.enabled", "true");
    props.put("ott.outbox.enabled", "true");
    props.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
    props.put(
        "spring.kafka.consumer.key-deserializer",
        "org.apache.kafka.common.serialization.StringDeserializer");
    props.put(
        "spring.kafka.consumer.value-deserializer",
        "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    props.put("spring.kafka.consumer.auto-offset-reset", "earliest");
    props.put("spring.kafka.consumer.enable-auto-commit", "false");
    props.put("spring.kafka.listener.ack-mode", "record");
    props.put(
        "spring.kafka.producer.key-serializer",
        "org.apache.kafka.common.serialization.StringSerializer");
    props.put(
        "spring.kafka.producer.value-serializer",
        "org.apache.kafka.common.serialization.ByteArraySerializer");
    props.put(
        "ott.schema-registry.url",
        "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081));
    return props;
  }

  static Map<String, String> experienceProperties() {
    var props = new java.util.HashMap<String, String>();
    props.put("ott.owners.member", url(member));
    props.put("ott.owners.content", url(content));
    props.put("ott.owners.playback", url(playback));
    props.put("ott.workload.token-url", OIDC.tokenEndpoint());
    props.put("ott.workload.client-id", "playback-test");
    props.put("ott.workload.client-secret", "fixture-secret");
    props.put("ott.search.enabled", "true");
    props.put("ott.recommendations.enabled", "true");
    props.put("ott.search.url", "http://" + SEARCH.getHost() + ":" + SEARCH.getMappedPort(9200));
    props.put("ott.search.index", "ott-journey-catalog");
    props.put("ott.search.replicas", "0");
    props.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
    props.put(
        "spring.kafka.consumer.key-deserializer",
        "org.apache.kafka.common.serialization.StringDeserializer");
    props.put(
        "spring.kafka.consumer.value-deserializer",
        "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    props.put("spring.kafka.consumer.auto-offset-reset", "earliest");
    props.put("spring.kafka.consumer.enable-auto-commit", "false");
    props.put("spring.kafka.listener.ack-mode", "record");
    props.put(
        "spring.kafka.producer.key-serializer",
        "org.apache.kafka.common.serialization.StringSerializer");
    props.put(
        "spring.kafka.producer.value-serializer",
        "org.apache.kafka.common.serialization.ByteArraySerializer");
    props.put(
        "ott.schema-registry.url",
        "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(8081));
    return props;
  }

  static ConfigurableApplicationContext app(
      Class<?> application, String owner, PostgreSQLContainer<?> pg, Map<String, String> extra) {
    var args = new java.util.ArrayList<String>();
    var props =
        new java.util.HashMap<String, String>(
            Map.of(
                "spring.config.location",
                "optional:classpath:/journey-empty.yaml",
                "spring.application.name",
                owner + "-service",
                "server.port",
                "0",
                "spring.datasource.url",
                pg.getJdbcUrl(),
                "spring.datasource.username",
                pg.getUsername(),
                "spring.datasource.password",
                pg.getPassword(),
                "spring.flyway.locations",
                "classpath:db/" + owner,
                "ott.security.issuer",
                OIDC.issuer(),
                "ott.security.jwks-uri",
                OIDC.jwks(),
                "ott.security.audience",
                "ott-api"));
    props.put("spring.jackson.deserialization.accept-float-as-int", "false");
    props.put("spring.kafka.producer.properties.delivery.timeout.ms", "5000");
    props.put("spring.kafka.producer.properties.request.timeout.ms", "2000");
    props.put("spring.kafka.producer.properties.max.block.ms", "2000");
    props.put("spring.kafka.producer.acks", "all");
    props.put("ott.outbox.enabled", "false");
    props.put("ott.schema-registry.url", "http://localhost:1");
    props.put("management.otlp.tracing.export.enabled", "false");
    props.putAll(extra);
    props.forEach((k, v) -> args.add("--" + k + "=" + v));
    var builder = new SpringApplicationBuilder(application);
    if (owner.equals("media")) builder.sources(MediaPorts.class);
    return builder.run(args.toArray(String[]::new));
  }

  static String url(ConfigurableApplicationContext c) {
    return "http://localhost:" + ((ServletWebServerApplicationContext) c).getWebServer().getPort();
  }

  static RestClient client(ConfigurableApplicationContext c) {
    return RestClient.create(url(c));
  }

  @AfterAll
  static void close() {
    closeApps();
    OIDC.close();
    NETWORK.close();
  }

  static void closeApps() {
    if (operations != null) operations.close();
    if (insights != null) insights.close();
    if (engagement != null) engagement.close();
    if (experience != null) experience.close();
    if (media != null) media.close();
    if (playback != null) playback.close();
    if (commerce != null) commerce.close();
    if (content != null) content.close();
    if (member != null) member.close();
  }

  @Test
  void
      authenticatedAccountProfileCatalogOrderVerifiedPaymentEntitlement_independentOwnersIntegrate()
          throws Exception {
    String viewer = OIDC.token("viewer-journey", "", true);
    String editor = OIDC.token("editor", "catalog.write", true);
    String commerceAdmin = OIDC.token("billing-operator", "commerce.write", true);
    var account = put(memberHttp, "/v1/accounts/me", viewer, null);
    assertThat(account.get("id").asText()).isNotBlank();
    var analyticsConsent =
        insightsHttp
            .get()
            .uri("/v1/insights/consent")
            .headers(h -> h.setBearerAuth(viewer))
            .retrieve()
            .body(JsonNode.class);
    assertThat(analyticsConsent.path("analytics").asBoolean()).isFalse();
    put(
        insightsHttp,
        "/v1/insights/consent",
        viewer,
        Map.of(
            "version",
            analyticsConsent.path("version").asLong(),
            "analytics",
            true,
            "experiments",
            true));

    var profile =
        post(
            memberHttp,
            "/v1/profiles",
            viewer,
            Map.of("name", "Main", "kids", false, "maturity", 18, "locale", "en"));
    assertThat(profile.get("version").asInt()).isEqualTo(1);
    var device =
        post(
            memberHttp,
            "/v1/devices",
            viewer,
            Map.of(
                "installationId",
                "journey-tv-01",
                "name",
                "TV",
                "codecs",
                List.of("H264"),
                "drm",
                List.of("WIDEVINE")));
    assertThat(device.get("status").asText()).isEqualTo("ACTIVE");
    var item =
        post(
            contentHttp,
            "/v1/admin/catalog",
            editor,
            Map.of(
                "type",
                "SPORT",
                "maturity",
                7,
                "locale",
                "en",
                "title",
                "Journey movie",
                "synopsis",
                "Synthetic fixture"));
    var contentId = UUID.fromString(item.get("id").asText());
    byte[] source = synthetic();
    String checksum =
        HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source));
    String mediaOperator = OIDC.token("media-operator", "media.write", true);
    var intake =
        post(
            mediaHttp,
            "/v1/admin/media/uploads",
            mediaOperator,
            Map.of(
                "contentId",
                contentId.toString(),
                "assetVersion",
                1,
                "bytes",
                source.length,
                "sha256",
                checksum));
    var upload = intake.get("upload");
    var request =
        java.net.http.HttpRequest.newBuilder(java.net.URI.create(upload.get("url").asText()))
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(source));
    upload
        .get("headers")
        .fields()
        .forEachRemaining(
            entry -> {
              if (!entry.getKey().equalsIgnoreCase("host")
                  && !entry.getKey().equalsIgnoreCase("content-length"))
                entry.getValue().forEach(v -> request.header(entry.getKey(), v.asText()));
            });
    try (var client = java.net.http.HttpClient.newHttpClient()) {
      assertThat(
              client
                  .send(request.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
                  .statusCode())
          .isEqualTo(200);
    }
    String jobId = intake.at("/job/id").asText();
    post(mediaHttp, "/v1/admin/media/jobs/" + jobId + "/upload-completed", mediaOperator, Map.of());
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    JsonNode job;
    do {
      job =
          JSON.readTree(
              mediaHttp
                  .get()
                  .uri("/v1/admin/media/jobs/" + jobId)
                  .headers(h -> h.setBearerAuth(mediaOperator))
                  .retrieve()
                  .body(String.class));
      if (java.util.Set.of("READY", "FAILED").contains(job.get("state").asText())) break;
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    assertThat(job.get("state").asText()).isEqualTo("READY");
    var s3 = media.getBean(software.amazon.awssdk.services.s3.S3Client.class);
    String prefix = "vod/" + contentId + "/v1/";
    assertThat(
            s3.getObjectAsBytes(b -> b.bucket("ott-origin").key(prefix + "master.m3u8"))
                .asUtf8String())
        .startsWith("#EXTM3U");
    post(
        contentHttp,
        "/v1/admin/catalog/" + contentId + "/review",
        editor,
        Map.of("version", 1, "locale", "en"));
    String publisher = OIDC.token("publisher", "catalog.write catalog.publish", true);
    boolean published = false;
    deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    do {
      try {
        post(
            contentHttp,
            "/v1/admin/catalog/" + contentId + "/publish",
            publisher,
            Map.of("version", 2, "assetVersion", 1, "locale", "en"));
        published = true;
      } catch (org.springframework.web.client.HttpClientErrorException e) {
        if (e.getStatusCode().value() != 409
            || !e.getResponseBodyAsString().contains("MEDIA_NOT_READY")) throw e;
        Thread.sleep(100);
      }
    } while (!published && System.nanoTime() < deadline);
    assertThat(published).isTrue();
    var browsed =
        contentHttp
            .get()
            .uri("/v1/catalog")
            .headers(h -> h.setBearerAuth(viewer))
            .retrieve()
            .body(String.class);
    assertThat(browsed).contains("Journey movie");
    var plan =
        post(
            commerceHttp,
            "/v1/admin/plans",
            commerceAdmin,
            Map.of(
                "name",
                "Premium",
                "priceMinor",
                999,
                "currency",
                "INR",
                "durationDays",
                30,
                "maxStreams",
                2,
                "adSupported",
                true,
                "offlineAllowed",
                true));
    String key = "journey-purchase-01";
    String orderBody =
        commerceHttp
            .post()
            .uri("/v1/orders")
            .headers(
                h -> {
                  h.setBearerAuth(viewer);
                  h.set("Idempotency-Key", key);
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("planId", plan.get("id").asText()))
            .retrieve()
            .body(String.class);
    var order = JSON.readTree(orderBody);
    assertThat(order.get("accountId").asText()).isEqualTo(account.get("id").asText());
    assertThat(order.get("state").asText()).isEqualTo("PENDING");
    byte[] event =
        JSON.writeValueAsBytes(
            Map.of(
                "eventId",
                "journey-event-01",
                "orderId",
                order.get("id").asText(),
                "providerRef",
                order.get("providerRef").asText(),
                "amountMinor",
                999,
                "currency",
                "INR",
                "outcome",
                "SUCCEEDED"));
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
    String signature = HexFormat.of().formatHex(mac.doFinal(event));
    commerceHttp
        .post()
        .uri("/v1/webhooks/payment")
        .header("X-Webhook-Timestamp", timestamp)
        .header("X-Webhook-Signature", signature)
        .contentType(MediaType.APPLICATION_JSON)
        .body(event)
        .retrieve()
        .toBodilessEntity();
    var entitlement =
        JSON.readTree(
            commerceHttp
                .get()
                .uri("/v1/subscription")
                .headers(h -> h.setBearerAuth(viewer))
                .retrieve()
                .body(String.class));
    assertThat(entitlement.get("active").asBoolean()).isTrue();
    assertThat(entitlement.get("maxStreams").asInt()).isEqualTo(2);
    post(
        contentHttp,
        "/v1/admin/catalog/" + contentId + "/rights",
        editor,
        Map.of(
            "country",
            "IN",
            "startAt",
            Instant.now().minusSeconds(60).toString(),
            "endAt",
            Instant.now().plusSeconds(3600).toString(),
            "offlineAllowed",
            true));
    var rawSearch =
        RestClient.builder()
            .baseUrl("http://" + SEARCH.getHost() + ":" + SEARCH.getMappedPort(9200))
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
            .build();
    deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    boolean searchable = false;
    do {
      try {
        rawSearch.post().uri("/ott-journey-catalog/_refresh").retrieve().toBodilessEntity();
        var found =
            experienceHttp
                .get()
                .uri("/v1/search?profileId=" + profile.get("id").asText() + "&country=IN&q=Journey")
                .headers(h -> h.setBearerAuth(viewer))
                .retrieve()
                .body(JsonNode.class);
        searchable = found.path("items").size() > 0;
      } catch (org.springframework.web.client.RestClientResponseException e) {
        if (e.getStatusCode().value() != 503 && e.getStatusCode().value() != 404) throw e;
      }
      if (!searchable) Thread.sleep(100);
    } while (!searchable && System.nanoTime() < deadline);
    assertThat(searchable).isTrue();
    put(
        experienceHttp,
        "/v1/profiles/" + profile.get("id").asText() + "/personalization",
        viewer,
        Map.of("enabled", true));
    String geo = geo("viewer-journey", device.get("id").asText(), "IN");
    String playbackBody =
        playbackHttp
            .post()
            .uri("/v1/playback/sessions")
            .headers(
                h -> {
                  h.setBearerAuth(viewer);
                  h.set("Idempotency-Key", "journey-playback-01");
                  h.set("X-Geo-Assertion", freshGeo(device.get("id").asText()));
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "profileId",
                    profile.get("id").asText(),
                    "deviceId",
                    device.get("id").asText(),
                    "contentId",
                    contentId.toString(),
                    "country",
                    "IN"))
            .retrieve()
            .body(String.class);
    var grant = JSON.readTree(playbackBody);
    assertThat(grant.get("delivery").get("protection").asText()).isEqualTo("MOCK_CLEAR_ONLY");
    assertThat(grant.get("playbackToken").asText()).isNotBlank();

    String profileId = profile.get("id").asText();
    String progressPath = "/v1/profiles/" + profileId + "/progress";
    var progressInput = new java.util.HashMap<String, Object>();
    progressInput.put("eventId", UUID.randomUUID().toString());
    progressInput.put("contentId", contentId.toString());
    progressInput.put("deviceId", device.get("id").asText());
    progressInput.put("grantToken", grant.get("playbackToken").asText());
    progressInput.put("sequence", 1);
    progressInput.put("positionMillis", 1000);
    progressInput.put("durationMillis", 4000);
    assertThat(
            post(experienceHttp, progressPath, viewer, progressInput).get("accepted").asBoolean())
        .isTrue();
    assertThat(
            post(experienceHttp, progressPath, viewer, progressInput).get("duplicate").asBoolean())
        .isTrue();
    var resumed =
        experienceHttp
            .get()
            .uri(progressPath + "/" + contentId)
            .headers(h -> h.setBearerAuth(viewer))
            .retrieve()
            .body(JsonNode.class);
    assertThat(resumed.get("positionMillis").asLong()).isEqualTo(1000);
    progressInput.put("eventId", UUID.randomUUID().toString());
    progressInput.put("sequence", 2);
    progressInput.put("positionMillis", 500);
    assertThat(
            post(experienceHttp, progressPath, viewer, progressInput)
                .at("/progress/positionMillis")
                .asLong())
        .isEqualTo(500);
    experienceHttp
        .put()
        .uri("/v1/profiles/" + profileId + "/watchlist/" + contentId)
        .headers(h -> h.setBearerAuth(viewer))
        .retrieve()
        .toBodilessEntity();
    var watchlist =
        experienceHttp
            .get()
            .uri("/v1/profiles/" + profileId + "/watchlist")
            .headers(h -> h.setBearerAuth(viewer))
            .retrieve()
            .body(JsonNode.class);
    assertThat(watchlist.path("items").size()).isEqualTo(1);
    assertThat(
            put(
                    experienceHttp,
                    "/v1/profiles/" + profileId + "/ratings/" + contentId,
                    viewer,
                    Map.of("rating", 5))
                .get("rating")
                .asInt())
        .isEqualTo(5);
    var experienceDb = experience.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
    deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    do {
      if (experienceDb.queryForObject(
              "SELECT count(*) FROM daily_view WHERE profile_id=?",
              Integer.class,
              UUID.fromString(profileId))
          == 1) break;
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
    assertThat(
            experienceDb.queryForObject(
                "SELECT count(*) FROM daily_view WHERE profile_id=?",
                Integer.class,
                UUID.fromString(profileId)))
        .isEqualTo(1);
    var recommended =
        experienceHttp
            .get()
            .uri("/v1/recommendations?profileId=" + profileId + "&country=IN")
            .headers(h -> h.setBearerAuth(viewer))
            .retrieve()
            .body(JsonNode.class);
    assertThat(recommended.path("personalizationEnabled").asBoolean()).isTrue();
    assertThat(recommended.path("rails").size()).isGreaterThanOrEqualTo(3);
    UUID telemetryId = UUID.randomUUID();
    var sample =
        Map.ofEntries(
            Map.entry("eventId", telemetryId),
            Map.entry("kind", "PLAY_STARTED"),
            Map.entry("occurredAt", Instant.now().toString()),
            Map.entry("sequence", 1),
            Map.entry("startupMillis", 700),
            Map.entry("bufferMillis", 0),
            Map.entry("playedMillis", 0),
            Map.entry("bitrateKbps", 4000),
            Map.entry("regionHint", "IN"),
            Map.entry("deviceFamily", "TV"),
            Map.entry("cdn", "CLOUDFRONT"),
            Map.entry("resolution", "FHD"),
            Map.entry("failureCode", "NONE"));
    var batch =
        Map.of(
            "profileId",
            profileId,
            "deviceId",
            device.get("id").asText(),
            "playbackToken",
            grant.get("playbackToken").asText(),
            "samples",
            List.of(sample));
    assertThat(post(insightsHttp, "/v1/telemetry/batches", viewer, batch).path("accepted").asInt())
        .isEqualTo(1);
    assertThat(post(insightsHttp, "/v1/telemetry/batches", viewer, batch).path("duplicate").asInt())
        .isEqualTo(1);
    var experiment =
        post(
            insightsHttp,
            "/v1/admin/insights/experiments",
            OIDC.token("insights-admin", "insights.write", true),
            Map.of(
                "name",
                "journey-home",
                "feature",
                "HOME_RAILS",
                "startsAt",
                Instant.now().minusSeconds(1).toString(),
                "endsAt",
                Instant.now().plusSeconds(300).toString(),
                "control",
                0,
                "variantA",
                100,
                "variantB",
                0));
    assertThat(
            insightsHttp
                .get()
                .uri("/v1/experiments/" + experiment.path("id").asText() + "/assignment")
                .headers(h -> h.setBearerAuth(viewer))
                .retrieve()
                .body(JsonNode.class)
                .path("variant")
                .asText())
        .isEqualTo("VARIANT_A");
    var adCampaign =
        post(
            engagementHttp,
            "/v1/admin/ads/campaigns",
            OIDC.token("ad-operator", "ads.write", true),
            Map.of(
                "name",
                "Synthetic contextual pre-roll",
                "country",
                "IN",
                "maturity",
                7,
                "placement",
                "PRE_ROLL",
                "startsAt",
                Instant.now().minusSeconds(1).toString(),
                "endsAt",
                Instant.now().plusSeconds(3600).toString(),
                "dailyCap",
                1,
                "creativeUrl",
                "https://local-cdn.invalid/ad.mp4",
                "durationMillis",
                4000));
    var ad =
        engagementHttp
            .post()
            .uri("/v1/ads/decision")
            .headers(h -> h.setBearerAuth(viewer))
            .header("Idempotency-Key", "journey-ad-01")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "profileId",
                    profileId,
                    "deviceId",
                    device.get("id").asText(),
                    "playbackToken",
                    grant.get("playbackToken").asText(),
                    "placement",
                    "PRE_ROLL",
                    "adapter",
                    "CSAI"))
            .retrieve()
            .body(JsonNode.class);
    assertThat(ad.get("reason").asText()).isEqualTo("FILLED");
    assertThat(ad.at("/decision/campaignId").asText()).isEqualTo(adCampaign.get("id").asText());
    var measurement =
        Map.of(
            "profileId",
            profileId,
            "deviceId",
            device.get("id").asText(),
            "playbackToken",
            grant.get("playbackToken").asText(),
            "decisionId",
            ad.at("/decision/id").asText(),
            "eventId",
            UUID.randomUUID().toString(),
            "quartile",
            0,
            "occurredAt",
            Instant.now().toString());
    assertThat(
            post(engagementHttp, "/v1/ads/events", viewer, measurement)
                .get("duplicate")
                .asBoolean())
        .isFalse();
    assertThat(
            post(engagementHttp, "/v1/ads/events", viewer, measurement)
                .get("duplicate")
                .asBoolean())
        .isTrue();
    try (var consumer =
        new org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]>(
            Map.of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "group.id",
                "ad-journey-" + UUID.randomUUID(),
                "auto.offset.reset",
                "earliest",
                "enable.auto.commit",
                "false",
                "key.deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer",
                "org.apache.kafka.common.serialization.ByteArrayDeserializer"))) {
      consumer.subscribe(List.of("ad.impression.v1"));
      boolean seen = false;
      long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      while (!seen && System.nanoTime() < until)
        for (var record : consumer.poll(Duration.ofMillis(200))) {
          if (!record.key().equals(ad.at("/decision/id").asText())) continue;
          byte[] raw = record.value();
          var decoded =
              new org.apache.avro.specific.SpecificDatumReader<
                      com.ott.contracts.events.AdImpressionV1>(
                      com.ott.contracts.events.AdImpressionV1.SCHEMA$)
                  .read(
                      null,
                      org.apache.avro.io.DecoderFactory.get()
                          .binaryDecoder(raw, 5, raw.length - 5, null));
          assertThat(decoded.getProducer().toString()).isEqualTo("engagement");
          assertThat(decoded.getQuartile()).isZero();
          seen = true;
        }
      assertThat(seen).isTrue();
    }
    var insightsDb = insights.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
    long insightDeadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    do {
      if (insightsDb.queryForObject(
              "SELECT count(*) FROM telemetry_event WHERE kind='AD_IMPRESSION'", Integer.class)
          == 1) break;
      Thread.sleep(100);
    } while (System.nanoTime() < insightDeadline);
    assertThat(
            insightsDb.queryForObject(
                "SELECT count(*) FROM telemetry_event WHERE kind='AD_IMPRESSION'", Integer.class))
        .isEqualTo(1);
    try (var telemetryConsumer =
        new org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]>(
            Map.of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "group.id",
                "insights-journey-" + UUID.randomUUID(),
                "auto.offset.reset",
                "earliest",
                "enable.auto.commit",
                "false",
                "key.deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer",
                "org.apache.kafka.common.serialization.ByteArrayDeserializer"))) {
      telemetryConsumer.subscribe(List.of("qoe.received.v1", "analytics.received.v1"));
      boolean qoe = false, analytics = false;
      long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      while ((!qoe || !analytics) && System.nanoTime() < until)
        for (var record : telemetryConsumer.poll(Duration.ofMillis(200))) {
          var decoded =
              insights
                  .getBean(com.ott.insights.adapter.outbound.kafka.RegistryCodec.class)
                  .decode(record.topic(), record.value());
          assertThat(decoded.get("producer").toString()).isEqualTo("insights");
          if (record.topic().equals("qoe.received.v1")) {
            assertThat(decoded.get("sessionId").toString())
                .isEqualTo(grant.path("sessionId").asText());
            assertThat(((Number) decoded.get("startupMillis")).intValue()).isEqualTo(700);
            qoe = true;
          } else if (decoded.get("eventKind").toString().equals("PLAY_STARTED")) {
            assertThat(decoded.get("accountRef").toString()).hasSize(64);
            analytics = true;
          }
        }
      assertThat(qoe && analytics).isTrue();
    }
    var validated =
        post(
            playbackHttp,
            "/internal/v1/playback/token/check",
            OIDC.token("drm-boundary", "playback.internal", false),
            Map.of(
                "token",
                grant.get("playbackToken").asText(),
                "deviceId",
                device.get("id").asText()));
    assertThat(validated.get("sessionId").asText()).isEqualTo(grant.get("sessionId").asText());
    playbackHttp
        .post()
        .uri("/v1/playback/sessions/" + grant.get("sessionId").asText() + "/heartbeat")
        .headers(
            h -> {
              h.setBearerAuth(viewer);
              h.set("X-Geo-Assertion", freshGeo(device.get("id").asText()));
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("deviceId", device.get("id").asText(), "country", "IN"))
        .retrieve()
        .toBodilessEntity();
    // A finite synthetic packager fixture copies verified CMAF objects directly to the private
    // origin. It is not a commercial feed or a latency/continuity benchmark.
    var downloadInput =
        Map.of(
            "profileId",
            profileId,
            "deviceId",
            device.get("id").asText(),
            "contentId",
            contentId.toString(),
            "country",
            "IN");
    var download =
        playbackHttp
            .post()
            .uri("/v1/downloads")
            .headers(
                h -> {
                  h.setBearerAuth(viewer);
                  h.set("X-Geo-Assertion", freshGeo(device.get("id").asText()));
                })
            .header("Idempotency-Key", "journey-offline-current-01")
            .contentType(MediaType.APPLICATION_JSON)
            .body(downloadInput)
            .retrieve()
            .body(JsonNode.class);
    var licenseInput =
        Map.of(
            "token", download.path("offlineToken").asText(), "deviceId", device.get("id").asText());
    post(
        playbackHttp,
        "/internal/v1/downloads/token/check",
        OIDC.token("licensed-context", "playback.internal", false),
        licenseInput);
    var liveEvent =
        post(
            contentHttp,
            "/v1/admin/live/events",
            OIDC.token("live-operator", "live.write", true),
            Map.of(
                "contentId",
                contentId.toString(),
                "league",
                "Synthetic League",
                "homeTeam",
                "Home",
                "awayTeam",
                "Away",
                "referenceCountry",
                "IN",
                "startsAt",
                Instant.now().minusSeconds(1).toString(),
                "endsAt",
                Instant.now().plusSeconds(3600).toString(),
                "packagerSubject",
                "packager-fixture",
                "dvrSeconds",
                120));
    String liveId = liveEvent.get("id").asText();
    String livePrefix = "live/" + liveId + "/e1/";
    for (var object : s3.listObjectsV2(b -> b.bucket("ott-origin").prefix(prefix)).contents()) {
      byte[] bytes =
          s3.getObjectAsBytes(b -> b.bucket("ott-origin").key(object.key())).asByteArray();
      if (object.key().endsWith(".m3u8"))
        bytes =
            new String(bytes, StandardCharsets.UTF_8)
                .replace("#EXT-X-ENDLIST", "")
                .getBytes(StandardCharsets.UTF_8);
      s3.putObject(
          b -> b.bucket("ott-origin").key(livePrefix + object.key().substring(prefix.length())),
          software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
    }
    byte[] liveManifest =
        s3.getObjectAsBytes(b -> b.bucket("ott-origin").key(livePrefix + "master.m3u8"))
            .asByteArray();
    assertThat(new String(liveManifest, StandardCharsets.UTF_8)).startsWith("#EXTM3U");
    var proofAt = Instant.now();
    byte[] proof =
        JSON.writeValueAsBytes(
            Map.of(
                "epoch",
                1,
                "sequence",
                1,
                "healthy",
                true,
                "sealed",
                false,
                "firstSegmentAt",
                proofAt.minusSeconds(8).toString(),
                "lastSegmentAt",
                proofAt.toString(),
                "manifestSha",
                HexFormat.of()
                    .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(liveManifest)),
                "commentaries",
                List.of(
                    Map.of("language", "en", "label", "English"),
                    Map.of("language", "es", "label", "Spanish")),
                "subtitles",
                List.of("en")));
    String feedAt = Long.toString(proofAt.getEpochSecond());
    var feedMac = Mac.getInstance("HmacSHA256");
    feedMac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    feedMac.update((feedAt + ".").getBytes(StandardCharsets.UTF_8));
    contentHttp
        .post()
        .uri("/internal/v1/live/feeds/" + liveId + "/heartbeat")
        .headers(h -> h.setBearerAuth(OIDC.token("packager-fixture", "live.feed", false)))
        .header("X-Feed-Timestamp", feedAt)
        .header("X-Feed-Signature", HexFormat.of().formatHex(feedMac.doFinal(proof)))
        .contentType(MediaType.APPLICATION_JSON)
        .body(proof)
        .retrieve()
        .toBodilessEntity();
    var liveReminder =
        engagementHttp
            .post()
            .uri("/v1/reminders")
            .headers(h -> h.setBearerAuth(viewer))
            .header("Idempotency-Key", "journey-live-reminder-01")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("targetId", liveId, "kind", "LIVE", "channel", "IN_APP"))
            .retrieve()
            .body(JsonNode.class);
    assertThat(liveReminder.get("state").asText()).isEqualTo("WAITING");
    for (var transition :
        List.of(Map.of("version", 1, "state", "READY"), Map.of("version", 2, "state", "LIVE")))
      contentHttp
          .post()
          .uri("/v1/admin/live/events/" + liveId + "/transitions")
          .headers(h -> h.setBearerAuth(OIDC.token("live-operator", "live.write", true)))
          .header("Idempotency-Key", "journey-state-" + transition.get("state"))
          .contentType(MediaType.APPLICATION_JSON)
          .body(transition)
          .retrieve()
          .toBodilessEntity();
    var liveGrant =
        playbackHttp
            .post()
            .uri("/v1/live/events/" + liveId + "/playback")
            .headers(
                h -> {
                  h.setBearerAuth(viewer);
                  h.set("X-Geo-Assertion", freshGeo(device.get("id").asText()));
                })
            .header("Idempotency-Key", "journey-live-01")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "profileId",
                    profileId,
                    "deviceId",
                    device.get("id").asText(),
                    "country",
                    "IN",
                    "mode",
                    "LIVE"))
            .retrieve()
            .body(JsonNode.class);
    assertThat(liveGrant.get("delivery").toString()).contains(livePrefix);
    assertThat(Instant.parse(liveGrant.get("expiresAt").asText()))
        .isBeforeOrEqualTo(proofAt.plusSeconds(30));
    playbackHttp
        .post()
        .uri("/v1/playback/sessions/" + liveGrant.get("sessionId").asText() + "/heartbeat")
        .headers(
            h -> {
              h.setBearerAuth(viewer);
              h.set("X-Geo-Assertion", freshGeo(device.get("id").asText()));
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("deviceId", device.get("id").asText(), "country", "IN"))
        .retrieve()
        .toBodilessEntity();
    try (var consumer =
        new org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]>(
            Map.of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "group.id",
                "live-journey-" + UUID.randomUUID(),
                "auto.offset.reset",
                "earliest",
                "enable.auto.commit",
                "false",
                "key.deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer",
                "org.apache.kafka.common.serialization.ByteArrayDeserializer"))) {
      consumer.subscribe(List.of("live.changed.v1"));
      var versions = new java.util.TreeSet<Long>();
      long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      while (versions.size() < 3 && System.nanoTime() < until)
        for (var record : consumer.poll(Duration.ofMillis(200))) {
          if (!record.key().equals(liveId)) continue;
          byte[] raw = record.value();
          assertThat(raw[0]).isZero();
          var decoded =
              new org.apache.avro.specific.SpecificDatumReader<
                      com.ott.contracts.events.LiveChangedV1>(
                      com.ott.contracts.events.LiveChangedV1.SCHEMA$)
                  .read(
                      null,
                      org.apache.avro.io.DecoderFactory.get()
                          .binaryDecoder(raw, 5, raw.length - 5, null));
          assertThat(decoded.getProducer().toString()).isEqualTo("content");
          assertThat(decoded.getLiveEventId().toString()).isEqualTo(liveId);
          versions.add(decoded.getAggregateVersion());
        }
      assertThat(versions).containsExactly(1L, 2L, 3L);
    }
    long notificationDeadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    JsonNode inbox;
    do {
      inbox =
          engagementHttp
              .get()
              .uri("/v1/notifications")
              .headers(h -> h.setBearerAuth(viewer))
              .retrieve()
              .body(JsonNode.class);
      if (inbox.path("items").size() == 1) break;
      Thread.sleep(100);
    } while (System.nanoTime() < notificationDeadline);
    assertThat(inbox.path("items")).hasSize(1);
    assertThat(inbox.at("/items/0/template").asText()).isEqualTo("LIVE_STARTED");
    assertThat(inbox.at("/items/0/targetId").asText()).isEqualTo(liveId);
    assertThat(
            engagement
                .getBean(org.springframework.jdbc.core.JdbcTemplate.class)
                .queryForObject(
                    "SELECT count(*) FROM notification_intent WHERE state='DELIVERED'",
                    Integer.class))
        .isEqualTo(1);
    var failedOrder =
        commerceHttp
            .post()
            .uri("/v1/orders")
            .headers(h -> h.setBearerAuth(viewer))
            .header("Idempotency-Key", "journey-failure-order-01")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("planId", plan.get("id").asText()))
            .retrieve()
            .body(JsonNode.class);
    byte[] failedBody =
        JSON.writeValueAsBytes(
            Map.of(
                "eventId",
                "journey-failed-payment-01",
                "orderId",
                failedOrder.get("id").asText(),
                "providerRef",
                failedOrder.get("providerRef").asText(),
                "amountMinor",
                999,
                "currency",
                "INR",
                "outcome",
                "FAILED"));
    String failedAt = Long.toString(Instant.now().getEpochSecond());
    var failedMac = Mac.getInstance("HmacSHA256");
    failedMac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    failedMac.update((failedAt + ".").getBytes(StandardCharsets.UTF_8));
    commerceHttp
        .post()
        .uri("/v1/webhooks/payment")
        .header("X-Webhook-Timestamp", failedAt)
        .header("X-Webhook-Signature", HexFormat.of().formatHex(failedMac.doFinal(failedBody)))
        .contentType(MediaType.APPLICATION_JSON)
        .body(failedBody)
        .retrieve()
        .toBodilessEntity();
    notificationDeadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    do {
      inbox =
          engagementHttp
              .get()
              .uri("/v1/notifications")
              .headers(h -> h.setBearerAuth(viewer))
              .retrieve()
              .body(JsonNode.class);
      if (inbox.path("items").size() == 2) break;
      Thread.sleep(100);
    } while (System.nanoTime() < notificationDeadline);
    assertThat(inbox.path("items")).hasSize(2);
    assertThat(inbox.toString()).contains("PAYMENT_FAILED");
    assertThat(
            commerceHttp
                .get()
                .uri("/v1/subscription")
                .headers(h -> h.setBearerAuth(viewer))
                .retrieve()
                .body(JsonNode.class)
                .get("active")
                .asBoolean())
        .isTrue();

    String guardianViewer = OIDC.token("viewer-journey", "", true);
    assertThat(
            put(
                    memberHttp,
                    "/v1/parental-control",
                    guardianViewer,
                    Map.of("version", 0, "newPin", "2468"))
                .path("enabled")
                .asBoolean())
        .isTrue();
    assertThat(
            playbackHttp
                .post()
                .uri("/internal/v1/downloads/token/check")
                .headers(
                    h ->
                        h.setBearerAuth(OIDC.token("licensed-context", "playback.internal", false)))
                .body(licenseInput)
                .<Integer>exchange((req, res) -> res.getStatusCode().value()))
        .isEqualTo(403);
    var heartbeatInput = Map.of("deviceId", device.get("id").asText(), "country", "IN");
    String heartbeatPath =
        "/v1/playback/sessions/" + liveGrant.path("sessionId").asText() + "/heartbeat";
    assertThat(
            playbackHttp
                .post()
                .uri(heartbeatPath)
                .headers(
                    h -> {
                      h.setBearerAuth(viewer);
                      h.set("X-Geo-Assertion", freshGeo(device.get("id").asText()));
                    })
                .body(heartbeatInput)
                .<Integer>exchange((req, res) -> res.getStatusCode().value()))
        .isEqualTo(403);
    var selection =
        post(
            memberHttp,
            "/v1/profile-selections",
            viewer,
            Map.of("profileId", profileId, "deviceId", device.get("id").asText(), "pin", "2468"));
    assertThat(
            playbackHttp
                .post()
                .uri(heartbeatPath)
                .headers(
                    h -> {
                      h.setBearerAuth(viewer);
                      h.set("X-Geo-Assertion", freshGeo(device.get("id").asText()));
                      h.set("X-Profile-Authorization", selection.path("token").asText());
                    })
                .body(heartbeatInput)
                .retrieve()
                .body(JsonNode.class)
                .path("sessionId")
                .asText())
        .isEqualTo(liveGrant.path("sessionId").asText());
    String supportToken = OIDC.token("support-fixture", "support.write support.read", true);
    var supportCase =
        post(
            operationsHttp,
            "/v1/support/cases",
            supportToken,
            Map.of("requestId", UUID.randomUUID(), "accountId", account.path("id").asText()));
    String operationsToken =
        OIDC.token("operations-fixture", "operations.write operations.read", true);
    String casePath = "/v1/support/cases/" + supportCase.path("id").asText();
    for (String action :
        List.of("SUSPEND_ACCOUNT", "RESUME_ACCOUNT", "REVOKE_ENTITLEMENT", "RESTORE_ENTITLEMENT")) {
      var currentCase =
          operationsHttp
              .get()
              .uri(casePath)
              .headers(h -> h.setBearerAuth(supportToken))
              .retrieve()
              .body(JsonNode.class);
      var cmd =
          post(
              operationsHttp,
              "/v1/admin/cases/" + supportCase.path("id").asText() + "/actions",
              operationsToken,
              Map.of(
                  "operationId",
                  UUID.randomUUID(),
                  "version",
                  currentCase.path("version").asLong(),
                  "action",
                  action));
      long commandDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      JsonNode applied;
      do {
        applied =
            operationsHttp
                .get()
                .uri("/v1/admin/commands/" + cmd.path("id").asText())
                .headers(h -> h.setBearerAuth(operationsToken))
                .retrieve()
                .body(JsonNode.class);
        if (applied.path("state").asText().equals("APPLIED")) break;
        Thread.sleep(100);
      } while (System.nanoTime() < commandDeadline);
      assertThat(applied.path("state").asText()).isEqualTo("APPLIED");
      if (action.equals("SUSPEND_ACCOUNT"))
        assertThat(
                memberHttp
                    .get()
                    .uri("/v1/accounts/me")
                    .headers(h -> h.setBearerAuth(viewer))
                    .<Integer>exchange((req, res) -> res.getStatusCode().value()))
            .isEqualTo(403);
      if (action.equals("RESUME_ACCOUNT"))
        assertThat(
                memberHttp
                    .get()
                    .uri("/v1/accounts/me")
                    .headers(h -> h.setBearerAuth(viewer))
                    .retrieve()
                    .body(JsonNode.class)
                    .path("id")
                    .asText())
            .isEqualTo(account.path("id").asText());
      if (action.equals("REVOKE_ENTITLEMENT") || action.equals("RESTORE_ENTITLEMENT"))
        assertThat(
                commerceHttp
                    .get()
                    .uri("/v1/subscription")
                    .headers(h -> h.setBearerAuth(viewer))
                    .retrieve()
                    .body(JsonNode.class)
                    .path("active")
                    .asBoolean())
            .isEqualTo(action.equals("RESTORE_ENTITLEMENT"));
    }
    var currentCase =
        operationsHttp
            .get()
            .uri(casePath)
            .headers(h -> h.setBearerAuth(supportToken))
            .retrieve()
            .body(JsonNode.class);
    put(
        operationsHttp,
        "/v1/admin/cases/" + supportCase.path("id").asText() + "/resolve",
        operationsToken,
        Map.of("version", currentCase.path("version").asLong()));
    String ingestToken = OIDC.token("trusted-idp-fixture", "security.ingest", false);
    for (int n = 0; n < 10; n++)
      post(
          operationsHttp,
          "/internal/v1/operations/signals",
          ingestToken,
          Map.of(
              "eventId",
              UUID.randomUUID(),
              "accountId",
              account.path("id").asText(),
              "kind",
              "LOGIN_FAILURE",
              "region",
              "UNKNOWN",
              "occurredAt",
              Instant.now().toString()));
    try (var operationsConsumer =
        new org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]>(
            Map.of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "group.id",
                "operations-journey-" + UUID.randomUUID(),
                "auto.offset.reset",
                "earliest",
                "enable.auto.commit",
                "false",
                "key.deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer",
                "org.apache.kafka.common.serialization.ByteArrayDeserializer"))) {
      operationsConsumer.subscribe(
          List.of("audit.recorded.v1", "abuse.action.v1", "abuse.signal.v1"));
      var seen = new java.util.HashSet<String>();
      long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      while (seen.size() < 3 && System.nanoTime() < until)
        for (var record : operationsConsumer.poll(Duration.ofMillis(200))) {
          var row =
              operations
                  .getBean(com.ott.operations.adapter.outbound.kafka.RegistryCodec.class)
                  .decode(record.topic(), record.value());
          assertThat(record.key()).isEqualTo(account.path("id").asText());
          assertThat(row.get("producer").toString()).isEqualTo("operations");
          seen.add(record.topic());
        }
      assertThat(seen).hasSize(3);
    }
    var operationsDb = operations.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
    assertThat(
            operationsDb.queryForObject(
                "SELECT count(*) FROM security_signal WHERE kind='PAYMENT_FAILED'", Integer.class))
        .isEqualTo(1);
    memberHttp
        .delete()
        .uri("/v1/devices/" + device.get("id").asText())
        .headers(h -> h.setBearerAuth(viewer))
        .retrieve()
        .toBodilessEntity();
    Integer revoked =
        playbackHttp
            .post()
            .uri("/v1/playback/sessions/" + grant.get("sessionId").asText() + "/heartbeat")
            .headers(
                h -> {
                  h.setBearerAuth(viewer);
                  h.set("X-Geo-Assertion", freshGeo(device.get("id").asText()));
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("deviceId", device.get("id").asText(), "country", "IN"))
            .<Integer>exchange((req, res) -> res.getStatusCode().value());
    assertThat(revoked).isEqualTo(403);
    long businessDeadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    do {
      if (insightsDb.queryForObject(
                  "SELECT count(*) FROM telemetry_event WHERE kind='SUBSCRIPTION_PURCHASED'",
                  Integer.class)
              == 1
          && insightsDb.queryForObject(
                  "SELECT count(*) FROM telemetry_event WHERE kind='PAYMENT_FAILED'", Integer.class)
              == 1) break;
      Thread.sleep(100);
    } while (System.nanoTime() < businessDeadline);
    assertThat(
            insightsDb.queryForObject(
                "SELECT count(*) FROM telemetry_event WHERE kind='SUBSCRIPTION_PURCHASED'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            insightsDb.queryForObject(
                "SELECT count(*) FROM telemetry_event WHERE kind='PAYMENT_FAILED'", Integer.class))
        .isEqualTo(1);
    String other = OIDC.token("other-viewer", "", true);
    put(memberHttp, "/v1/accounts/me", other, null);
    assertThat(
            engagementHttp
                .get()
                .uri("/v1/notifications")
                .headers(h -> h.setBearerAuth(other))
                .retrieve()
                .body(JsonNode.class)
                .path("items"))
        .isEmpty();
    Integer status =
        commerceHttp
            .get()
            .uri("/v1/orders/" + order.get("id").asText())
            .headers(h -> h.setBearerAuth(other))
            .<Integer>exchange((req, res) -> res.getStatusCode().value());
    assertThat(status).isEqualTo(404);
    if (Boolean.getBoolean("ott.recovery.drill")) LocalRecoveryDrill.run();
  }

  static String freshGeo(String device) {
    try {
      return geo("viewer-journey", device, "IN");
    } catch (Exception e) {
      throw new IllegalStateException("Cannot sign fixture geo proof", e);
    }
  }

  static String geo(String subject, String device, String country) throws Exception {
    long at = Instant.now().getEpochSecond();
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return at
        + "."
        + java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mac.doFinal(
                    (at + "\n" + OIDC.issuer() + "\n" + subject + "\n" + device + "\n" + country)
                        .getBytes(StandardCharsets.UTF_8)));
  }

  static JsonNode post(RestClient http, String path, String token, Object body) throws Exception {
    return JSON.readTree(
        http.post()
            .uri(path)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String.class));
  }

  static JsonNode put(RestClient http, String path, String token, Object body) throws Exception {
    var request = http.put().uri(path).headers(h -> h.setBearerAuth(token));
    return JSON.readTree(
        (body == null ? request : request.body(body)).retrieve().body(String.class));
  }

  static byte[] synthetic() throws Exception {
    Path dir = Files.createTempDirectory("ott-fixture-");
    try {
      Files.writeString(
          dir.resolve("captions.vtt"),
          "WEBVTT\n\n00:00:00.000 --> 00:00:04.000\nDeterministic caption\n");
      new ContainerTools()
          .run(
              dir,
              "ffmpeg",
              List.of(
                  "-nostdin",
                  "-hide_banner",
                  "-loglevel",
                  "error",
                  "-y",
                  "-f",
                  "lavfi",
                  "-i",
                  "testsrc2=size=320x180:rate=24",
                  "-f",
                  "lavfi",
                  "-i",
                  "sine=frequency=1000:sample_rate=48000",
                  "-f",
                  "lavfi",
                  "-i",
                  "sine=frequency=500:sample_rate=48000",
                  "-i",
                  "captions.vtt",
                  "-map",
                  "0:v",
                  "-map",
                  "1:a",
                  "-map",
                  "2:a",
                  "-map",
                  "3:s",
                  "-c:v",
                  "libx264",
                  "-preset",
                  "ultrafast",
                  "-threads",
                  "2",
                  "-c:a",
                  "aac",
                  "-c:s",
                  "mov_text",
                  "-metadata:s:a:0",
                  "language=eng",
                  "-metadata:s:a:1",
                  "language=spa",
                  "-metadata:s:s:0",
                  "language=eng",
                  "-t",
                  "8",
                  "source.mp4"),
              Duration.ofSeconds(30),
              () -> {});
      return Files.readAllBytes(dir.resolve("source.mp4"));
    } finally {
      try (var paths = Files.walk(dir)) {
        for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList())
          Files.deleteIfExists(p);
      }
    }
  }

  static class ContainerTools implements MediaTools {
    public String run(
        Path directory, String tool, List<String> args, Duration timeout, Runnable heartbeat) {
      String remote = "/work/" + UUID.randomUUID();
      try {
        NATIVE.execInContainer("mkdir", "-p", remote);
        try (var paths = Files.list(directory)) {
          for (var p : paths.filter(Files::isRegularFile).toList())
            NATIVE.copyFileToContainer(
                MountableFile.forHostPath(p), remote + "/" + p.getFileName());
        }
        var command =
            new java.util.ArrayList<String>(
                List.of("sh", "-c", "cd \"$1\" && shift && exec \"$@\"", "runner", remote, tool));
        command.addAll(args);
        heartbeat.run();
        var result = NATIVE.execInContainer(command.toArray(String[]::new));
        if (result.getExitCode() != 0) throw new MediaFailure(422, "INVALID_MEDIA");
        var outputs = NATIVE.execInContainer("ls", "-1", remote);
        for (var name : outputs.getStdout().split("\n"))
          if (name.matches("[A-Za-z0-9_.-]{1,180}"))
            NATIVE.copyFileFromContainer(remote + "/" + name, directory.resolve(name).toString());
        heartbeat.run();
        return result.getStdout();
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      } finally {
        try {
          NATIVE.execInContainer("rm", "-rf", remote);
        } catch (java.io.IOException e) {
          throw new IllegalStateException(e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class MediaPorts {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    MediaTools tools() {
      return new ContainerTools();
    }
  }
}
