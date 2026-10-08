package com.ott.media.infrastructure;

import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ott.media.worker", havingValue = "true")
public class WorkerShutdown implements org.springframework.beans.factory.DisposableBean {
  private final WorkerFactory factory;

  public WorkerShutdown(WorkerFactory factory) {
    this.factory = factory;
  }

  public void destroy() {
    factory.shutdown();
    factory.awaitTermination(30, TimeUnit.SECONDS);
    if (!factory.isTerminated()) {
      factory.shutdownNow();
      factory.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
