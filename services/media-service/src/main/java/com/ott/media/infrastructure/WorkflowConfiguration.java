package com.ott.media.infrastructure;

import com.ott.media.adapter.outbound.workflow.MediaActivities;
import com.ott.media.adapter.outbound.workflow.MediaWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkflowConfiguration {
  @Bean(destroyMethod = "shutdown")
  WorkflowServiceStubs temporal(@Value("${ott.temporal.target}") String target) {
    return WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget(target)
            .setRpcTimeout(java.time.Duration.ofSeconds(2))
            .build());
  }

  @Bean
  WorkflowClient workflows(
      WorkflowServiceStubs stubs, @Value("${ott.temporal.namespace:default}") String namespace) {
    return WorkflowClient.newInstance(
        stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
  }

  @Bean(initMethod = "start", destroyMethod = "shutdown")
  @ConditionalOnProperty(name = "ott.media.worker", havingValue = "true")
  WorkerFactory workers(WorkflowClient client, MediaActivities activities) {
    var factory = WorkerFactory.newInstance(client);
    var worker =
        factory.newWorker(
            "ott-media-v1",
            WorkerOptions.newBuilder()
                .setMaxConcurrentActivityExecutionSize(2)
                .setMaxConcurrentWorkflowTaskExecutionSize(4)
                .build());
    worker.registerWorkflowImplementationTypes(MediaWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    return factory;
  }
}
