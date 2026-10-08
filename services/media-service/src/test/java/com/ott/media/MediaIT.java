package com.ott.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.media.adapter.outbound.workflow.MediaActivitiesImpl;
import com.ott.media.adapter.outbound.workflow.MediaWorkflowImpl;
import com.ott.media.application.MediaFailure;
import com.ott.media.application.MediaJobs;
import com.ott.media.application.MediaPipeline;
import com.ott.media.application.MediaTools;
import com.ott.media.application.ObjectStorage;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.WorkerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.services.s3.S3Client;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = {MediaApplication.class, MediaIT.TestPorts.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final GenericContainer<?> S3 =
      new GenericContainer<>("localstack/localstack:4.3.0")
          .withEnv("SERVICES", "s3")
          .withExposedPorts(4566)
          .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566));

  @Container
  static final GenericContainer<?> TEMPORAL =
      new GenericContainer<>(
              "temporalio/temporal@sha256:ad4c82c97bd12b417d1ea942610dbcd511afb250c4d5ed26c694009533df447e")
          .withCommand("server", "start-dev", "--ip", "0.0.0.0")
          .withExposedPorts(7233);

  @Container
  static final GenericContainer<?> NATIVE =
      new GenericContainer<>("jrottenberg/ffmpeg:7.1-ubuntu")
          .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
          .withCommand("-c", "sleep infinity");

  static final OidcFixture OIDC = new OidcFixture();
  @Autowired FailAfterPublishStorage faultStorage;
  @Autowired MediaJobs jobs;
  @Autowired JdbcTemplate db;
  @Autowired S3Client s3;
  @Autowired ObjectMapper json;
  @Autowired ObjectStorage storage;
  @Autowired MediaTools tools;
  @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
  @Autowired WorkerFactory workerFactory;
  @Autowired io.temporal.serviceclient.WorkflowServiceStubs temporalStubs;

  @Test
  void workerProbe_realDatabaseAndTemporal_reportsReady() throws Exception {
    var probe =
        new com.ott.media.infrastructure.WorkerHealth(
            workerFactory, temporalStubs, db, "default", 0);
    try {
      var result =
          java.net.http.HttpClient.newHttpClient()
              .send(
                  java.net.http.HttpRequest.newBuilder(
                          java.net.URI.create("http://127.0.0.1:" + probe.port() + "/health/ready"))
                      .GET()
                      .build(),
                  java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(result.statusCode()).isEqualTo(200);
      assertThat(result.body()).isEqualTo("UP");
    } finally {
      probe.destroy();
    }
  }

  @AfterAll
  static void close() {
    OIDC.close();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
    p.add("ott.security.issuer", OIDC::issuer);
    p.add("ott.security.jwks-uri", OIDC::jwks);
    p.add("ott.outbox.enabled", () -> "false");
    p.add("ott.media.worker", () -> "false");
    p.add("ott.media.dispatch-delay-ms", () -> "3600000");
    p.add("ott.temporal.target", () -> TEMPORAL.getHost() + ":" + TEMPORAL.getMappedPort(7233));
    p.add("ott.storage.endpoint", () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(4566));
    p.add("ott.storage.quarantine", () -> "ott-quarantine");
    p.add("ott.storage.origin", () -> "ott-origin");
    System.setProperty("aws.accessKeyId", "test");
    System.setProperty("aws.secretAccessKey", "test");
  }

  void buckets() {
    for (var name : List.of("ott-quarantine", "ott-origin"))
      try {
        s3.createBucket(b -> b.bucket(name));
      } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException e) {
      }
  }

  void upload(MediaJobs.Intake intake, byte[] source) throws Exception {
    var request =
        java.net.http.HttpRequest.newBuilder(java.net.URI.create(intake.upload().url()))
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(source));
    intake
        .upload()
        .headers()
        .forEach(
            (name, values) -> {
              if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length"))
                values.forEach(v -> request.header(name, v));
            });
    try (var client = java.net.http.HttpClient.newHttpClient()) {
      var response =
          client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
    }
  }

  MediaJobs.Job await(UUID id) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    MediaJobs.Job job;
    do {
      job = jobs.get(id);
      if (java.util.Set.of("READY", "FAILED").contains(job.state())) return job;
      Thread.sleep(50);
    } while (System.nanoTime() < end);
    throw new AssertionError("Workflow did not reach terminal state");
  }

  byte[] synthetic() throws Exception {
    Path dir = Files.createTempDirectory("ott-fixture-");
    try {
      Files.writeString(
          dir.resolve("captions.vtt"),
          "WEBVTT\n\n00:00:00.000 --> 00:00:04.000\nDeterministic caption\n");
      tools.run(
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

  @Test
  void workflow_syntheticSource_uploadProbeAbrCmafTracksQcOriginAndAtomicReady() throws Exception {
    buckets();
    byte[] source = synthetic();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
    var content = UUID.randomUUID();
    var intake = jobs.prepare(content, 1, source.length, hash);
    assertThat(jobs.prepare(content, 1, source.length, hash).job().id())
        .isEqualTo(intake.job().id());
    assertThatThrownBy(() -> jobs.prepare(content, 1, source.length, "a".repeat(64)))
        .isInstanceOf(MediaFailure.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    upload(intake, source);
    assertThat(jobs.completed(intake.job().id()).state()).isEqualTo("UPLOADED");
    jobs.dispatch();
    var job = await(intake.job().id());
    assertThat(job.state()).isEqualTo("READY");
    assertThat(job.manifestChecksum()).matches("[a-f0-9]{64}");
    byte[] complete =
        s3.getObjectAsBytes(b -> b.bucket("ott-origin").key(job.originPrefix() + "_complete.json"))
            .asByteArray();
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(complete)))
        .isEqualTo(job.manifestChecksum());
    var manifest = json.readTree(complete);
    assertThat(manifest.path("protection").asText()).isEqualTo("MOCK_CLEAR_ONLY");
    assertThat(manifest.path("tracks").size()).isEqualTo(3);
    assertThat(manifest.path("files").has("master.m3u8")).isTrue();
    assertThat(manifest.path("files").has("manifest.mpd")).isTrue();
    assertThat(manifest.path("files").has("thumbnail.jpg")).isTrue();
    assertThat(manifest.path("files").has("subs-0.vtt")).isTrue();
    String mpd =
        s3.getObjectAsBytes(b -> b.bucket("ott-origin").key(job.originPrefix() + "manifest.mpd"))
            .asUtf8String();
    assertThat(mpd)
        .contains("height=\"90\"", "height=\"180\"", "text/vtt")
        .doesNotContain("height=\"360\"");
    String hls =
        s3.getObjectAsBytes(b -> b.bucket("ott-origin").key(job.originPrefix() + "master.m3u8"))
            .asUtf8String();
    assertThat(hls).contains("TYPE=SUBTITLES", "SUBTITLES=\"subs\"");
    jobs.ready(job.id(), job.manifestChecksum());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND topic='media.ready.v1'",
                Integer.class,
                content))
        .isEqualTo(1);
  }

  @Test
  void workflow_corruptUploadedBytes_failsWithoutReadyPublication() throws Exception {
    buckets();
    byte[] bytes = "invalid video fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var intake = jobs.prepare(UUID.randomUUID(), 1, bytes.length, hash);
    upload(intake, bytes);
    jobs.completed(intake.job().id());
    jobs.dispatch();
    var job = await(intake.job().id());
    assertThat(job.state()).isEqualTo("FAILED");
    assertThat(job.failureCode()).isEqualTo("INVALID_MEDIA");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND topic='media.ready.v1'",
                Integer.class,
                job.contentId()))
        .isZero();
  }

  @Test
  void workflow_responseLostAfterOriginPublish_retriesImmutableObjectsAndCommitsOneReady()
      throws Exception {
    buckets();
    byte[] source = synthetic();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
    var intake = jobs.prepare(UUID.randomUUID(), 1, source.length, hash);
    faultStorage.failures.put(
        intake.job().originPrefix(), new java.util.concurrent.atomic.AtomicInteger());
    upload(intake, source);
    jobs.completed(intake.job().id());
    jobs.dispatch();
    var job = await(intake.job().id());
    assertThat(job.state()).isEqualTo("READY");
    assertThat(faultStorage.failures.get(job.originPrefix()).get()).isEqualTo(2);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND topic='media.ready.v1'",
                Integer.class,
                job.contentId()))
        .isEqualTo(1);
  }

  @Test
  void completed_sizeOrChecksumMismatch_keepsUploadUnqueued() {
    buckets();
    var intake = jobs.prepare(UUID.randomUUID(), 1, 100, "a".repeat(64));
    byte[] changed = "different bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String checksum;
    try {
      checksum =
          java.util.Base64.getEncoder()
              .encodeToString(MessageDigest.getInstance("SHA-256").digest(changed));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    s3.putObject(
        b -> b.bucket("ott-quarantine").key(intake.job().sourceKey()).checksumSHA256(checksum),
        software.amazon.awssdk.core.sync.RequestBody.fromBytes(changed));
    assertThatThrownBy(() -> jobs.completed(intake.job().id()))
        .isInstanceOf(MediaFailure.class)
        .extracting("code")
        .isEqualTo("UPLOAD_MISMATCH");
    assertThat(jobs.get(intake.job().id()).state()).isEqualTo("WAITING");
  }

  @Test
  void ready_outboxFault_rollsBackStatusAndCanResumeCompletion() {
    buckets();
    var job = jobs.prepare(UUID.randomUUID(), 1, 100, "a".repeat(64)).job();
    db.update("UPDATE media_job SET state='UPLOADED' WHERE id=?", job.id());
    db.execute(
        "CREATE FUNCTION reject_media_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN IF"
            + " NEW.aggregate_id='"
            + job.contentId()
            + "'::uuid THEN RAISE EXCEPTION 'injected media outbox fault'; END IF; RETURN NEW; END"
            + " $$");
    db.execute(
        "CREATE TRIGGER reject_media_event BEFORE INSERT ON outbox_event FOR EACH ROW EXECUTE"
            + " FUNCTION reject_media_event()");
    try {
      assertThatThrownBy(() -> jobs.ready(job.id(), "b".repeat(64)))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(jobs.get(job.id()).state()).isEqualTo("UPLOADED");
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM outbox_event WHERE aggregate_id=?",
                  Integer.class,
                  job.contentId()))
          .isZero();
    } finally {
      db.execute("DROP TRIGGER reject_media_event ON outbox_event");
      db.execute("DROP FUNCTION reject_media_event()");
    }
    jobs.ready(job.id(), "b".repeat(64));
    assertThat(jobs.get(job.id()).state()).isEqualTo("READY");
  }

  @Test
  void intake_invalidAndMissingObject_cannotQueueWorkflow() {
    buckets();
    assertThatThrownBy(() -> jobs.prepare(UUID.randomUUID(), 1, 268435457, "a".repeat(64)))
        .isInstanceOf(MediaFailure.class);
    var intake = jobs.prepare(UUID.randomUUID(), 1, 100, "a".repeat(64));
    assertThatThrownBy(() -> jobs.completed(intake.job().id()))
        .isInstanceOf(MediaFailure.class)
        .extracting("code")
        .isEqualTo("UPLOAD_NOT_READY");
    assertThat(jobs.get(intake.job().id()).state()).isEqualTo("WAITING");
  }

  @Test
  void api_adminScopeRequired_viewerCannotAllocateUpload() {
    var headers = new org.springframework.http.HttpHeaders();
    headers.setBearerAuth(OIDC.token("viewer", "", true));
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    var result =
        http.exchange(
            "/v1/admin/media/uploads",
            org.springframework.http.HttpMethod.POST,
            new org.springframework.http.HttpEntity<>(
                Map.of(
                    "contentId",
                    UUID.randomUUID(),
                    "assetVersion",
                    1,
                    "bytes",
                    100,
                    "sha256",
                    "a".repeat(64)),
                headers),
            String.class);
    assertThat(result.getStatusCode().value()).isEqualTo(403);
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

  static class FailAfterPublishStorage implements ObjectStorage {
    final com.ott.media.adapter.outbound.storage.S3Storage actual;
    final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
        failures = new java.util.concurrent.ConcurrentHashMap<>();

    FailAfterPublishStorage(com.ott.media.adapter.outbound.storage.S3Storage actual) {
      this.actual = actual;
    }

    public Upload presign(String key, long bytes, String checksum) {
      return actual.presign(key, bytes, checksum);
    }

    public Head head(String key) {
      return actual.head(key);
    }

    public void download(String key, Path path, long bytes) {
      actual.download(key, path, bytes);
    }

    public void stage(String key, Path archive) {
      actual.stage(key, archive);
    }

    public boolean resume(String key, Path archive) {
      return actual.resume(key, archive);
    }

    public void publish(String prefix, Path directory) {
      actual.publish(prefix, directory);
      var attempt = failures.get(prefix);
      if (attempt != null && attempt.incrementAndGet() == 1)
        throw new IllegalStateException("Injected lost publication acknowledgement");
    }
  }

  @TestConfiguration
  static class TestPorts {
    @Bean
    @org.springframework.context.annotation.Primary
    FailAfterPublishStorage faultStorage(com.ott.media.adapter.outbound.storage.S3Storage storage) {
      return new FailAfterPublishStorage(storage);
    }

    @Bean
    MediaTools containerTools() {
      return new ContainerTools();
    }

    @Bean
    MediaPipeline pipeline(ObjectStorage storage, MediaTools tools, ObjectMapper json)
        throws Exception {
      try (var input = MediaIT.class.getResourceAsStream("/encoding-profiles.json")) {
        return new MediaPipeline(storage, tools, json, json.readTree(input));
      }
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    WorkerFactory testWorker(WorkflowClient client, MediaJobs jobs, MediaPipeline pipeline) {
      var factory = WorkerFactory.newInstance(client);
      var worker = factory.newWorker("ott-media-v1");
      worker.registerWorkflowImplementationTypes(MediaWorkflowImpl.class);
      worker.registerActivitiesImplementations(new MediaActivitiesImpl(jobs, pipeline));
      return factory;
    }
  }
}
