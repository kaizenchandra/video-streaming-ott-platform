package com.ott.media.adapter.outbound.workflow;

import com.ott.media.application.MediaFailure;
import com.ott.media.application.MediaJobs;
import com.ott.media.application.MediaPipeline;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ott.media.worker", havingValue = "true")
public class MediaActivitiesImpl implements MediaActivities {
  private final MediaJobs jobs;
  private final MediaPipeline pipeline;

  public MediaActivitiesImpl(MediaJobs jobs, MediaPipeline pipeline) {
    this.jobs = jobs;
    this.pipeline = pipeline;
  }

  public void packageAndPublish(String id) {
    var job = jobs.get(UUID.fromString(id));
    if (job.state().equals("READY")) return;
    try {
      var context = io.temporal.activity.Activity.getExecutionContext();
      String checksum = pipeline.process(job, () -> context.heartbeat("processing"));
      jobs.ready(job.id(), checksum);
    } catch (MediaFailure e) {
      if (e.status() < 500)
        throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
            "Media validation failed", e.code());
      throw e;
    }
  }

  public void failed(String id, String code) {
    jobs.failed(UUID.fromString(id), code);
  }
}
