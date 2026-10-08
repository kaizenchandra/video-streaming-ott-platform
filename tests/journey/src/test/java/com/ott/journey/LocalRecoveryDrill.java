package com.ott.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Quiesced synthetic-state recovery only. Never accesses real accounts/clouds or another test
 * broker.
 */
final class LocalRecoveryDrill {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  private LocalRecoveryDrill() {}

  private record Event(String topic, int partition, String key, String value) {
    byte[] bytes() {
      return Base64.getDecoder().decode(value);
    }
  }

  static void run() throws Exception {
    Path evidence = Path.of("target/recovery-evidence");
    Files.createDirectories(evidence);
    Files.setPosixFilePermissions(
        evidence, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    var report = new LinkedHashMap<String, Object>();
    long started = System.nanoTime();
    // Stop all writers/listeners first: these are explicitly quiesced snapshots, not a cross-owner
    // live ACID snapshot.
    JourneyIT.closeApps();
    var owners = new LinkedHashMap<String, PostgreSQLContainer<?>>();
    owners.put("member", JourneyIT.MEMBER_DB);
    owners.put("content", JourneyIT.CONTENT_DB);
    owners.put("commerce", JourneyIT.COMMERCE_DB);
    owners.put("playback", JourneyIT.PLAYBACK_DB);
    owners.put("media", JourneyIT.MEDIA_DB);
    owners.put("experience", JourneyIT.EXPERIENCE_DB);
    owners.put("engagement", JourneyIT.ENGAGEMENT_DB);
    owners.put("insights", JourneyIT.INSIGHTS_DB);
    owners.put("operations", JourneyIT.OPERATIONS_DB);
    try (var restored = new PostgreSQLContainer<>("postgres:17.6")) {
      restored.start();
      var databases = restoreOwners(owners, restored, evidence);
      report.put("postgres", databases);
      var playback = database(restored, "playback_recovered");
      var quotaBefore = fingerprints(playback);
      assertThat(JourneyIT.REDIS.execInContainer("redis-cli", "FLUSHDB").getExitCode()).isZero();
      assertThat(JourneyIT.REDIS.execInContainer("redis-cli", "DBSIZE").getStdout().trim())
          .isEqualTo("0");
      assertThat(fingerprints(playback)).isEqualTo(quotaBefore);
      report.put(
          "redis",
          "Disposable cache lost; complete durable playback/session/offline ledger unchanged."
              + " Actual no-overbooking regression also executes in PlaybackIT.");
      restoreMedia(evidence, report);
      restoreKafkaAndSearch(restored, evidence, report);
    }
    report.put("localDrillSeconds", (System.nanoTime() - started) / 1_000_000_000.0);
    report.put(
        "snapshotMode",
        "Quiesced synthetic state; no source writes after backup; not a measured cloud RPO/RTO");
    report.put(
        "notRun",
        List.of(
            "AWS regional failover/failback",
            "Multi-AZ failover",
            "production PITR",
            "MSK MirrorMaker recovery",
            "commercial DRM/KMS",
            "Temporal persistent history restore",
            "real client traffic cutover"));
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(evidence.resolve("summary.json").toFile(), report);
  }

  private static Map<String, Object> restoreOwners(
      Map<String, PostgreSQLContainer<?>> owners, PostgreSQLContainer<?> target, Path evidence)
      throws Exception {
    var report = new LinkedHashMap<String, Object>();
    for (var owner : owners.entrySet()) {
      long start = System.nanoTime();
      var source = owner.getValue();
      var before =
          fingerprints(
              new JdbcTemplate(
                  new DriverManagerDataSource(
                      source.getJdbcUrl(), source.getUsername(), source.getPassword())));
      var dump =
          source.execInContainer(
              "pg_dump",
              "-U",
              source.getUsername(),
              "-d",
              source.getDatabaseName(),
              "--no-owner",
              "--no-acl");
      assertThat(dump.getExitCode()).isZero();
      byte[] bytes = dump.getStdout().getBytes(StandardCharsets.UTF_8);
      Path file = evidence.resolve(owner.getKey() + ".sql");
      Files.write(file, bytes);
      Files.setPosixFilePermissions(
          file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      assertThat(
              target
                  .execInContainer(
                      "createdb", "-U", target.getUsername(), owner.getKey() + "_recovered")
                  .getExitCode())
          .isZero();
      target.copyFileToContainer(Transferable.of(Files.readAllBytes(file), 0600), "/tmp/owner.sql");
      var restored =
          target.execInContainer(
              "psql",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              target.getUsername(),
              "-d",
              owner.getKey() + "_recovered",
              "-f",
              "/tmp/owner.sql");
      assertThat(restored.getExitCode()).as("Owned restore for %s", owner.getKey()).isZero();
      var after = database(target, owner.getKey() + "_recovered");
      assertThat(fingerprints(after))
          .as("Every owned table/schema row for %s", owner.getKey())
          .isEqualTo(before);
      var sourceDb =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  source.getJdbcUrl(), source.getUsername(), source.getPassword()));
      assertThat(indexSignatures(after))
          .as("Index structure and predicate membership for %s", owner.getKey())
          .isEqualTo(indexSignatures(sourceDb));
      JSON.writerWithDefaultPrettyPrinter()
          .writeValue(
              evidence.resolve(owner.getKey() + "-indexes.json").toFile(),
              Map.of(
                  "source",
                  sourceDb.queryForList(
                      "SELECT indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY"
                          + " indexname"),
                  "restored",
                  after.queryForList(
                      "SELECT indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY"
                          + " indexname")));
      report.put(
          owner.getKey(),
          Map.of(
              "tables",
              before.size(),
              "backupSha256",
              sha(bytes),
              "restoreSeconds",
              (System.nanoTime() - start) / 1_000_000_000.0));
    }
    // A restored ordinary SQL mutation still hits the immutable audit trigger.
    var operations = database(target, "operations_recovered");
    assertThat(operations.queryForObject("SELECT count(*) FROM operations_audit", Long.class))
        .isGreaterThan(0);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> operations.update("UPDATE operations_audit SET action=action"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    return report;
  }

  private static Map<String, String> indexSignatures(JdbcTemplate db) {
    var result = new LinkedHashMap<String, String>();
    var rows =
        db.queryForList(
            "SELECT c.relname AS name,t.relname AS table_name, "
                + "concat_ws('|',a.amname,i.indisunique,i.indisprimary,i.indisexclusion,i.indisvalid,i.indisready,"
                + "i.indnkeyatts,i.indnatts,i.indkey::text,i.indcollation::text,i.indclass::text,i.indoption::text,i.indexprs"
                + " IS NOT NULL,i.indpred IS NOT NULL) AS"
                + " signature,pg_get_expr(i.indpred,i.indrelid) AS predicate FROM pg_index i JOIN"
                + " pg_class c ON c.oid=i.indexrelid JOIN pg_class t ON t.oid=i.indrelid JOIN"
                + " pg_namespace n ON n.oid=t.relnamespace JOIN pg_am a ON a.oid=c.relam WHERE"
                + " n.nspname='public' ORDER BY c.relname");
    for (var row : rows) {
      String predicate = (String) row.get("predicate");
      String membership =
          predicate == null
              ? "all"
              : db.queryForObject(
                  "SELECT md5(coalesce(string_agg(to_jsonb(t)::text, '' ORDER BY"
                      + " to_jsonb(t)::text),'')) FROM public.\""
                      + row.get("table_name")
                      + "\" t WHERE "
                      + predicate,
                  String.class);
      result.put((String) row.get("name"), row.get("signature") + "|" + membership);
    }
    return result;
  }

  private static JdbcTemplate database(PostgreSQLContainer<?> target, String name) {
    String url =
        "jdbc:postgresql://" + target.getHost() + ":" + target.getMappedPort(5432) + "/" + name;
    return new JdbcTemplate(
        new DriverManagerDataSource(url, target.getUsername(), target.getPassword()));
  }

  private static Map<String, String> fingerprints(JdbcTemplate db) {
    var result = new LinkedHashMap<String, String>();
    for (String table :
        db.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",
            String.class)) {
      if (!table.matches("[a-z_][a-z0-9_]*"))
        throw new IllegalStateException("Unexpected fixture table");
      result.put(
          table,
          db.queryForObject(
              "SELECT count(*)::text || ':' || coalesce(md5(string_agg(to_jsonb(t)::text,E'\\n"
                  + "' ORDER BY to_jsonb(t)::text)), 'empty') FROM \""
                  + table
                  + "\" t",
              String.class));
    }
    return result;
  }

  private static RestClient http(String url) {
    var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2000);
    factory.setReadTimeout(10000);
    return RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  private static S3Client s3(GenericContainer<?> container) {
    return S3Client.builder()
        .endpointOverride(
            java.net.URI.create(
                "http://" + container.getHost() + ":" + container.getMappedPort(4566)))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
        .forcePathStyle(true)
        .overrideConfiguration(
            b ->
                b.apiCallTimeout(Duration.ofSeconds(20))
                    .apiCallAttemptTimeout(Duration.ofSeconds(10)))
        .build();
  }

  private static void restoreMedia(Path evidence, Map<String, Object> report) throws Exception {
    long start = System.nanoTime();
    try (var target =
        new GenericContainer<>("localstack/localstack:4.3.0")
            .withEnv("SERVICES", "s3")
            .withExposedPorts(4566)
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566))) {
      target.start();
      try (var source = s3(JourneyIT.S3);
          var restore = s3(target)) {
        restore.createBucket(b -> b.bucket("ott-origin-recovered"));
        int count = 0;
        long totalBytes = 0;
        for (var page : source.listObjectsV2Paginator(b -> b.bucket("ott-origin"))) {
          for (var item : page.contents()) {
            byte[] bytes =
                source.getObjectAsBytes(b -> b.bucket("ott-origin").key(item.key())).asByteArray();
            var metadata = source.headObject(b -> b.bucket("ott-origin").key(item.key()));
            Path file =
                evidence.resolve(
                    "media-" + sha(item.key().getBytes(StandardCharsets.UTF_8)) + ".bin");
            Files.write(file, bytes);
            Files.setPosixFilePermissions(
                file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            // Restore from the actual archived bytes, not the live origin.
            restore.putObject(
                b ->
                    b.bucket("ott-origin-recovered")
                        .key(item.key())
                        .contentType(metadata.contentType())
                        .cacheControl(metadata.cacheControl())
                        .metadata(metadata.metadata()),
                RequestBody.fromFile(file));
            assertThat(
                    sha(
                        restore
                            .getObjectAsBytes(b -> b.bucket("ott-origin-recovered").key(item.key()))
                            .asByteArray()))
                .isEqualTo(sha(bytes));
            var restoredMetadata =
                restore.headObject(b -> b.bucket("ott-origin-recovered").key(item.key()));
            assertThat(restoredMetadata.metadata()).isEqualTo(metadata.metadata());
            assertThat(restoredMetadata.contentType()).isEqualTo(metadata.contentType());
            assertThat(restoredMetadata.cacheControl()).isEqualTo(metadata.cacheControl());
            count++;
            totalBytes += bytes.length;
          }
        }
        assertThat(count).isGreaterThan(10);
        report.put(
            "media",
            Map.of(
                "objects",
                count,
                "bytes",
                totalBytes,
                "restoreSeconds",
                (System.nanoTime() - start) / 1_000_000_000.0,
                "result",
                "Independent S3 emulator: every archived manifest/segment/thumbnail byte checksum"
                    + " and object metadata preserved"));
      }
    }
  }

  private static void restoreKafkaAndSearch(
      PostgreSQLContainer<?> db, Path evidence, Map<String, Object> report) throws Exception {
    long start = System.nanoTime();
    var sourceRegistry =
        http(
            "http://"
                + JourneyIT.REGISTRY.getHost()
                + ":"
                + JourneyIT.REGISTRY.getMappedPort(8081));
    List<Map<String, Object>> schemas = new ArrayList<>();
    for (String subject : sourceRegistry.get().uri("/subjects").retrieve().body(String[].class)) {
      for (int version :
          sourceRegistry
              .get()
              .uri("/subjects/{subject}/versions", subject)
              .retrieve()
              .body(int[].class)) {
        var schema =
            sourceRegistry
                .get()
                .uri("/subjects/{subject}/versions/{version}", subject, version)
                .retrieve()
                .body(JsonNode.class);
        schemas.add(
            Map.of(
                "subject",
                subject,
                "version",
                version,
                "id",
                schema.path("id").asInt(),
                "schema",
                schema.path("schema").asText()));
      }
    }
    List<Event> events = new ArrayList<>();
    var partitions = new LinkedHashMap<String, Integer>();
    try (var admin =
        AdminClient.create(Map.of("bootstrap.servers", JourneyIT.KAFKA.getBootstrapServers()))) {
      var topics =
          admin.listTopics().names().get().stream()
              .filter(t -> t.endsWith(".v1"))
              .sorted()
              .toList();
      var descriptions = admin.describeTopics(topics).allTopicNames().get();
      try (var consumer = consumer(JourneyIT.KAFKA.getBootstrapServers())) {
        for (String topic : topics) {
          int size = descriptions.get(topic).partitions().size();
          partitions.put(topic, size);
          var assigned =
              java.util.stream.IntStream.range(0, size)
                  .mapToObj(i -> new TopicPartition(topic, i))
                  .toList();
          consumer.assign(assigned);
          consumer.seekToBeginning(assigned);
          var ends = consumer.endOffsets(assigned);
          long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
          while (assigned.stream().anyMatch(p -> consumer.position(p) < ends.get(p))) {
            if (System.nanoTime() > deadline)
              throw new IllegalStateException("Archive deadline exhausted");
            for (var record : consumer.poll(Duration.ofMillis(200)))
              events.add(
                  new Event(
                      topic,
                      record.partition(),
                      record.key(),
                      Base64.getEncoder().encodeToString(record.value())));
          }
        }
      }
    }
    JSON.writeValue(evidence.resolve("schemas.json").toFile(), schemas);
    JSON.writeValue(evidence.resolve("events.json").toFile(), events);
    for (String archive : List.of("schemas.json", "events.json"))
      Files.setPosixFilePermissions(
          evidence.resolve(archive),
          java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    schemas =
        JSON.readValue(
            evidence.resolve("schemas.json").toFile(),
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
    events =
        JSON.readValue(
            evidence.resolve("events.json").toFile(),
            new com.fasterxml.jackson.core.type.TypeReference<List<Event>>() {});
    // New broker has no prior topic data/consumer offsets. Imported Registry IDs preserve archived
    // Avro wire references.
    try (var kafka =
        new KafkaContainer("apache/kafka:3.9.1")
            .withNetwork(JourneyIT.NETWORK)
            .withNetworkAliases("restored-broker")
            .withListener("restored-broker:19092")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")) {
      kafka.start();
      try (var registry =
          new GenericContainer<>("confluentinc/cp-schema-registry:7.8.3")
              .withNetwork(JourneyIT.NETWORK)
              .withExposedPorts(8081)
              .withEnv("SCHEMA_REGISTRY_HOST_NAME", "restored-registry")
              .withEnv(
                  "SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS",
                  "PLAINTEXT://restored-broker:19092")
              .withEnv("SCHEMA_REGISTRY_MODE_MUTABILITY", "true")
              .waitingFor(Wait.forHttp("/subjects").forPort(8081))) {
        registry.start();
        var restoredRegistry =
            http("http://" + registry.getHost() + ":" + registry.getMappedPort(8081));
        restoredRegistry
            .put()
            .uri("/mode")
            .body(Map.of("mode", "IMPORT"))
            .retrieve()
            .toBodilessEntity();
        for (var schema : schemas) {
          var value = new HashMap<>(schema);
          String subject = value.remove("subject").toString();
          value.put("schemaType", "AVRO");
          var response =
              restoredRegistry
                  .post()
                  .uri("/subjects/{subject}/versions", subject)
                  .body(value)
                  .retrieve()
                  .body(JsonNode.class);
          assertThat(response.path("id").asInt()).isEqualTo(schema.get("id"));
          restoredRegistry
              .put()
              .uri("/config/{subject}", subject)
              .body(Map.of("compatibility", "BACKWARD_TRANSITIVE"))
              .retrieve()
              .toBodilessEntity();
        }
        restoredRegistry
            .put()
            .uri("/mode")
            .body(Map.of("mode", "READWRITE"))
            .retrieve()
            .toBodilessEntity();
        try (var admin =
            AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
          admin
              .createTopics(
                  partitions.entrySet().stream()
                      .map(e -> new NewTopic(e.getKey(), e.getValue(), (short) 1))
                      .toList())
              .all()
              .get();
        }
        var producerProps =
            Map.<String, Object>of(
                "bootstrap.servers",
                kafka.getBootstrapServers(),
                "key.serializer",
                "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer",
                "org.apache.kafka.common.serialization.ByteArraySerializer",
                "acks",
                "all",
                "enable.idempotence",
                true);
        try (var producer = new KafkaProducer<String, byte[]>(producerProps)) {
          for (var event : events)
            producer
                .send(
                    new ProducerRecord<>(
                        event.topic(), event.partition(), event.key(), event.bytes()))
                .get();
        }
        try (var readback = consumer(kafka.getBootstrapServers())) {
          var assigned =
              partitions.entrySet().stream()
                  .flatMap(
                      e ->
                          java.util.stream.IntStream.range(0, e.getValue())
                              .mapToObj(i -> new TopicPartition(e.getKey(), i)))
                  .toList();
          readback.assign(assigned);
          readback.seekToBeginning(assigned);
          var ends = readback.endOffsets(assigned);
          List<Event> recoveredEvents = new ArrayList<>();
          long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
          while (assigned.stream().anyMatch(p -> readback.position(p) < ends.get(p))) {
            if (System.nanoTime() > deadline)
              throw new IllegalStateException("Restored Kafka readback deadline");
            for (var record : readback.poll(Duration.ofMillis(200)))
              recoveredEvents.add(
                  new Event(
                      record.topic(),
                      record.partition(),
                      record.key(),
                      Base64.getEncoder().encodeToString(record.value())));
          }
          assertThat(recoveredEvents).containsExactlyInAnyOrderElementsOf(events);
        }
        assertThat(events).isNotEmpty();
        for (var event : events) {
          byte[] bytes = event.bytes();
          int id = ByteBuffer.wrap(bytes, 1, 4).getInt();
          var raw =
              restoredRegistry.get().uri("/schemas/ids/{id}", id).retrieve().body(JsonNode.class);
          var schema = new Schema.Parser().parse(raw.path("schema").asText());
          var decoded =
              new GenericDatumReader<>(schema)
                  .read(null, DecoderFactory.get().binaryDecoder(bytes, 5, bytes.length - 5, null));
          assertThat(decoded.toString()).contains("eventId");
        }
        recoverIndexAndReplay(db, kafka, registry, events, report);
        report.put(
            "kafka",
            Map.of(
                "topics",
                partitions.size(),
                "events",
                events.size(),
                "schemaVersions",
                schemas.size(),
                "restoreSeconds",
                (System.nanoTime() - start) / 1_000_000_000.0,
                "result",
                "New broker/Registry, exact schema IDs/versions, partition/key/value archive and"
                    + " actual consumer replay"));
      }
    }
  }

  private static KafkaConsumer<String, byte[]> consumer(String bootstrap) {
    return new KafkaConsumer<>(
        Map.of(
            "bootstrap.servers",
            bootstrap,
            "key.deserializer",
            "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer",
            "org.apache.kafka.common.serialization.ByteArrayDeserializer",
            "enable.auto.commit",
            false,
            "group.id",
            "recovery-" + UUID.randomUUID()));
  }

  private static void recoverIndexAndReplay(
      PostgreSQLContainer<?> db,
      KafkaContainer kafka,
      GenericContainer<?> registry,
      List<Event> events,
      Map<String, Object> report)
      throws Exception {
    var contentProps = new HashMap<>(JourneyIT.contentProperties());
    contentProps.put("spring.datasource.url", jdbcUrl(db, "content_recovered"));
    contentProps.put("ott.outbox.enabled", "false");
    contentProps.put("ott.media-consumer.enabled", "false");
    var indexHttp =
        http("http://" + JourneyIT.SEARCH.getHost() + ":" + JourneyIT.SEARCH.getMappedPort(9200));
    indexHttp.delete().uri("/ott-journey-catalog").retrieve().toBodilessEntity();
    try (var content =
        JourneyIT.app(com.ott.content.ContentApplication.class, "content", db, contentProps)) {
      var props = new HashMap<>(JourneyIT.experienceProperties());
      props.put("spring.datasource.url", jdbcUrl(db, "experience_recovered"));
      props.put("ott.owners.content", JourneyIT.url(content));
      props.put("spring.kafka.bootstrap-servers", kafka.getBootstrapServers());
      props.put(
          "ott.schema-registry.url",
          "http://" + registry.getHost() + ":" + registry.getMappedPort(8081));
      props.put("ott.outbox.enabled", "false");
      props.put("ott.recommendations.enabled", "false");
      try (var experience =
          JourneyIT.app(com.ott.experience.ExperienceApplication.class, "experience", db, props)) {
        var projection = experience.getBean(com.ott.experience.application.SearchProjection.class);
        var listener =
            experience.getBean(
                com.ott.experience.adapter.inbound.kafka.CatalogPublishedListener.class);
        var owned = experience.getBean(JdbcTemplate.class);
        long before = owned.queryForObject("SELECT count(*) FROM search_inbox", Long.class);
        for (var event : events)
          if (event.topic().equals("catalog.published.v1")) {
            listener.published(event.key(), event.bytes());
            listener.published(event.key(), event.bytes());
          }
        assertThat(owned.queryForObject("SELECT count(*) FROM search_inbox", Long.class))
            .isEqualTo(before);
        UUID job = projection.rebuild();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!projection.rebuildStatus(job).state().equals("COMPLETE")) {
          if (System.nanoTime() > deadline)
            throw new IllegalStateException("Search rebuild deadline");
          Thread.sleep(100);
        }
        indexHttp.post().uri("/ott-journey-catalog/_refresh").retrieve().toBodilessEntity();
        int documents =
            indexHttp
                .get()
                .uri("/ott-journey-catalog/_count")
                .retrieve()
                .body(JsonNode.class)
                .path("count")
                .asInt();
        int expectedDocuments =
            owned.queryForObject(
                "SELECT count(*)::int FROM search_document d CROSS JOIN LATERAL"
                    + " jsonb_object_keys(d.snapshot->'localizations') AS locale",
                Integer.class);
        assertThat(documents).isEqualTo(expectedDocuments).isGreaterThan(0);
        report.put(
            "search",
            Map.of(
                "localizedDocuments",
                documents,
                "result",
                "Deleted index rebuilt by actual checkpoint/indexer/current restored Content owner;"
                    + " duplicate catalog replay leaves restored inbox unchanged"));
      }
    }
  }

  private static String jdbcUrl(PostgreSQLContainer<?> db, String name) {
    return "jdbc:postgresql://" + db.getHost() + ":" + db.getMappedPort(5432) + "/" + name;
  }

  private static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
