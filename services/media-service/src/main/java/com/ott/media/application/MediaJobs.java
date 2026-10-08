package com.ott.media.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.media.adapter.outbound.workflow.MediaWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MediaJobs {
  private final com.ott.media.infrastructure.EncodingSettings settings;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectStorage storage;
  private final WorkflowClient workflows;
  private final ObjectMapper json;

  public MediaJobs(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      ObjectStorage storage,
      WorkflowClient workflows,
      ObjectMapper json,
      com.ott.media.infrastructure.EncodingSettings settings) {
    this.settings = settings;
    this.db = db;
    tx = new TransactionTemplate(manager);
    this.storage = storage;
    this.workflows = workflows;
    this.json = json;
  }

  public record Job(
      UUID id,
      UUID contentId,
      long assetVersion,
      String sourceKey,
      long expectedBytes,
      String sourceChecksum,
      String state,
      String originPrefix,
      String encodingProfile,
      String encodingConfigHash,
      String manifestChecksum,
      String failureCode) {}

  public record Intake(Job job, ObjectStorage.Upload upload) {}

  public Intake prepare(UUID content, long version, long bytes, String checksum) {
    return prepare(content, version, bytes, checksum, "VOD");
  }

  public Intake prepare(UUID content, long version, long bytes, String checksum, String profile) {
    if (!java.util.Set.of("VOD", "SPORT").contains(profile))
      throw new MediaFailure(400, "INVALID_ENCODING_PROFILE");
    if (content == null
        || version < 1
        || bytes < 1
        || bytes > 268435456
        || checksum == null
        || !checksum.matches("[a-f0-9]{64}")) throw new MediaFailure(400, "INVALID_INPUT");
    var job =
        tx.execute(
            t -> {
              UUID id = UUID.randomUUID();
              db.update(
                  "INSERT INTO"
                      + " media_job(id,content_id,asset_version,source_key,expected_bytes,source_checksum,state,origin_prefix,encoding_profile,encoding_config_hash)"
                      + " VALUES(?,?,?,?,?,?,'WAITING',?,?,?) ON CONFLICT(content_id,asset_version)"
                      + " DO NOTHING",
                  id,
                  content,
                  version,
                  "quarantine/" + id + "/source.mp4",
                  bytes,
                  checksum,
                  "vod/" + content + "/v" + version + "/",
                  profile,
                  settings.hash());
              var j =
                  db.query(
                          "SELECT * FROM media_job WHERE content_id=? AND asset_version=? FOR"
                              + " UPDATE",
                          this::row,
                          content,
                          version)
                      .getFirst();
              if (j.expectedBytes() != bytes
                  || !j.sourceChecksum().equals(checksum)
                  || !j.encodingProfile().equals(profile)
                  || !j.encodingConfigHash().equals(settings.hash()))
                throw new MediaFailure(409, "IDEMPOTENCY_CONFLICT");
              if (!j.state().equals("WAITING"))
                throw new MediaFailure(409, "UPLOAD_ALREADY_COMPLETED");
              return j;
            });
    return new Intake(
        job, storage.presign(job.sourceKey(), job.expectedBytes(), job.sourceChecksum()));
  }

  public Job completed(UUID id) {
    var job = get(id);
    if (!job.state().equals("WAITING")) return job;
    var head = storage.head(job.sourceKey());
    if (head.bytes() != job.expectedBytes() || !head.checksum().equals(job.sourceChecksum()))
      throw new MediaFailure(409, "UPLOAD_MISMATCH");
    db.update(
        "UPDATE media_job SET state='UPLOADED',updated_at=clock_timestamp() WHERE id=? AND"
            + " state='WAITING'",
        id);
    return get(id);
  }

  @Scheduled(fixedDelayString = "${ott.media.dispatch-delay-ms:1000}")
  public void dispatch() {
    for (var id :
        db.queryForList(
            "SELECT id FROM media_job WHERE state='UPLOADED' ORDER BY created_at LIMIT 20",
            UUID.class)) {
      var options =
          WorkflowOptions.newBuilder()
              .setTaskQueue("ott-media-v1")
              .setWorkflowId("media-" + id)
              .setWorkflowIdReusePolicy(
                  WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
              .build();
      try {
        var workflow = workflows.newWorkflowStub(MediaWorkflow.class, options);
        WorkflowClient.start(workflow::process, id.toString());
        db.update(
            "UPDATE media_job SET state='PROCESSING',updated_at=clock_timestamp() WHERE id=? AND"
                + " state='UPLOADED'",
            id);
      } catch (WorkflowExecutionAlreadyStarted e) {
        db.update(
            "UPDATE media_job SET state='PROCESSING',updated_at=clock_timestamp() WHERE id=? AND"
                + " state='UPLOADED'",
            id);
      } catch (io.temporal.client.WorkflowServiceException | io.grpc.StatusRuntimeException e) {
        return;
      }
    }
  }

  public Job get(UUID id) {
    var found = db.query("SELECT * FROM media_job WHERE id=?", this::row, id);
    if (found.isEmpty()) throw new MediaFailure(404, "MEDIA_JOB_NOT_FOUND");
    return found.getFirst();
  }

  public void ready(UUID id, String checksum) {
    if (!checksum.matches("[a-f0-9]{64}")) throw new MediaFailure(422, "INVALID_MANIFEST");
    tx.executeWithoutResult(
        t -> {
          var job =
              db.query("SELECT * FROM media_job WHERE id=? FOR UPDATE", this::row, id).getFirst();
          if (job.state().equals("READY")) {
            if (!checksum.equals(job.manifestChecksum()))
              throw new MediaFailure(409, "IMMUTABLE_ASSET_CONFLICT");
            return;
          }
          if (!java.util.Set.of("PROCESSING", "UPLOADED").contains(job.state()))
            throw new MediaFailure(409, "INVALID_JOB_STATE");
          db.update(
              "UPDATE media_job SET state='READY',manifest_checksum=?,updated_at=clock_timestamp()"
                  + " WHERE id=?",
              checksum,
              id);
          event(
              job,
              "media.ready.v1",
              Map.of(
                  "contentId",
                  job.contentId().toString(),
                  "assetVersion",
                  job.assetVersion(),
                  "originPrefix",
                  job.originPrefix(),
                  "manifestChecksum",
                  checksum));
        });
  }

  public void failed(UUID id, String code) {
    if (!java.util.Set.of(
            "INVALID_MEDIA",
            "SOURCE_CHECKSUM_MISMATCH",
            "SOURCE_TOO_LARGE",
            "INVALID_ASSET_PATH",
            "INVALID_MANIFEST",
            "IMMUTABLE_ASSET_CONFLICT",
            "MEDIA_PROCESSING_FAILED")
        .contains(code)) code = "MEDIA_PROCESSING_FAILED";
    String safe = code;
    tx.executeWithoutResult(
        t -> {
          var job =
              db.query("SELECT * FROM media_job WHERE id=? FOR UPDATE", this::row, id).getFirst();
          if (java.util.Set.of("READY", "FAILED").contains(job.state())) return;
          db.update(
              "UPDATE media_job SET state='FAILED',failure_code=?,updated_at=clock_timestamp()"
                  + " WHERE id=?",
              safe,
              id);
          event(
              job,
              "media.failed.v1",
              Map.of(
                  "contentId",
                  job.contentId().toString(),
                  "jobId",
                  job.id().toString(),
                  "reasonCode",
                  safe));
        });
  }

  private void event(Job job, String topic, Object payload) {
    try {
      db.update(
          "INSERT INTO outbox_event(event_id,aggregate_id,aggregate_version,topic,payload)"
              + " VALUES(?,?,?,?,?::jsonb)",
          UUID.randomUUID(),
          job.contentId(),
          job.assetVersion(),
          topic,
          json.writeValueAsString(payload));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot encode media event", e);
    }
  }

  private Job row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Job(
        r.getObject("id", UUID.class),
        r.getObject("content_id", UUID.class),
        r.getLong("asset_version"),
        r.getString("source_key"),
        r.getLong("expected_bytes"),
        r.getString("source_checksum"),
        r.getString("state"),
        r.getString("origin_prefix"),
        r.getString("encoding_profile"),
        r.getString("encoding_config_hash"),
        r.getString("manifest_checksum"),
        r.getString("failure_code"));
  }
}
