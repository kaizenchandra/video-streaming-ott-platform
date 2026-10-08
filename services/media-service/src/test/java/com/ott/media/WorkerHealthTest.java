package com.ott.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ott.media.infrastructure.WorkerHealth;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkerHealthTest {
  @Test
  void probes_stoppedWorkerOrFailedDatabase_rejectsReadinessWithoutBreakingLiveness()
      throws Exception {
    var factory = mock(WorkerFactory.class);
    var jdbc = mock(JdbcTemplate.class);
    var health = new WorkerHealth(factory, mock(WorkflowServiceStubs.class), jdbc, "default", 0);
    try {
      assertThat(get(health, "/health/live")).isEqualTo(503);
      when(factory.isStarted()).thenReturn(true);
      assertThat(get(health, "/health/live")).isEqualTo(200);
      when(jdbc.queryForObject("SELECT 1", Integer.class))
          .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
      assertThat(get(health, "/health/ready")).isEqualTo(503);
      assertThat(get(health, "/health/live")).isEqualTo(200);
      assertThat(get(health, "/v1/media")).isEqualTo(404);
      when(factory.isShutdown()).thenReturn(true);
      assertThat(get(health, "/health/live")).isEqualTo(503);
    } finally {
      health.destroy();
    }
  }

  private int get(WorkerHealth health, String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + health.port() + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }
}
