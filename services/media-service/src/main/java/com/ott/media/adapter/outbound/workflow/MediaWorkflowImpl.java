package com.ott.media.adapter.outbound.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class MediaWorkflowImpl implements MediaWorkflow {
  private final MediaActivities activities =
      Workflow.newActivityStub(
          MediaActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofHours(3))
              .setScheduleToCloseTimeout(Duration.ofHours(6))
              .setHeartbeatTimeout(Duration.ofSeconds(30))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(2))
                      .setMaximumInterval(Duration.ofSeconds(60))
                      .setMaximumAttempts(3)
                      .build())
              .build());

  public void process(String id) {
    try {
      activities.packageAndPublish(id);
    } catch (io.temporal.failure.ActivityFailure e) {
      String code =
          e.getCause() instanceof io.temporal.failure.ApplicationFailure a
              ? a.getType()
              : "MEDIA_PROCESSING_FAILED";
      activities.failed(id, code);
      throw e;
    }
  }
}
