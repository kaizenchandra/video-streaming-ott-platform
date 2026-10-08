package com.ott.media.infrastructure;

import com.sun.net.httpserver.HttpServer;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Private management surface for a headless worker; dependency details never leave this process.
 */
@Component
@ConditionalOnProperty(name = "ott.media.worker-health.enabled", havingValue = "true")
public final class WorkerHealth implements DisposableBean {
  private final HttpServer server;
  private final ThreadPoolExecutor executor;

  public WorkerHealth(
      WorkerFactory factory,
      WorkflowServiceStubs temporal,
      JdbcTemplate jdbc,
      @Value("${ott.temporal.namespace:default}") String namespace,
      @Value("${ott.media.worker-health.port:8091}") int port)
      throws IOException {
    server = HttpServer.create(new InetSocketAddress(port), 16);
    executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    server.setExecutor(executor);
    server.createContext(
        "/health/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          int status = 404;
          if (path.equals("/health/live") || path.equals("/health/ready")) {
            status =
                !exchange.getRequestMethod().equals("GET")
                    ? 405
                    : factory.isStarted() && !factory.isShutdown() ? 200 : 503;
            if (status == 200 && path.equals("/health/ready")) {
              try {
                jdbc.queryForObject("SELECT 1", Integer.class);
                temporal
                    .blockingStub()
                    .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                    .describeNamespace(
                        DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build());
              } catch (org.springframework.dao.DataAccessException
                  | io.grpc.StatusRuntimeException e) {
                status = 503;
              }
            }
          }
          byte[] body = (status == 200 ? "UP" : "UNAVAILABLE").getBytes(StandardCharsets.US_ASCII);
          exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=US-ASCII");
          exchange.getResponseHeaders().set("Cache-Control", "no-store");
          exchange.sendResponseHeaders(status, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void destroy() {
    server.stop(1);
    executor.shutdownNow();
  }
}
