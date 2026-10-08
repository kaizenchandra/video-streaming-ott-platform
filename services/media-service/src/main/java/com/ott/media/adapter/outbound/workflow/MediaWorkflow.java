package com.ott.media.adapter.outbound.workflow;

@io.temporal.workflow.WorkflowInterface
public interface MediaWorkflow {
  @io.temporal.workflow.WorkflowMethod
  void process(String jobId);
}
