package com.ott.media.adapter.outbound.workflow;

@io.temporal.activity.ActivityInterface
public interface MediaActivities {
  void packageAndPublish(String jobId);

  void failed(String jobId, String code);
}
