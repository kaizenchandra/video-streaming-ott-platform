package com.ott.member.adapter.inbound.rest;

import com.ott.member.application.MemberFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ProblemHandler {
  private final org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer>
      tracers;

  public ProblemHandler(
      org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracers) {
    this.tracers = tracers;
  }

  @ExceptionHandler(MemberFailure.class)
  ProblemDetail domain(MemberFailure failure, HttpServletRequest request) {
    return problem(failure.status(), failure.code(), failure.getMessage(), request);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ProblemDetail invalid(Exception failure, HttpServletRequest request) {
    return problem(400, "INVALID_REQUEST", "Invalid request fields", request);
  }

  @ExceptionHandler(DataAccessException.class)
  ProblemDetail database(DataAccessException failure, HttpServletRequest request) {
    return problem(503, "DEPENDENCY_UNAVAILABLE", "Persistence unavailable", request);
  }

  private ProblemDetail problem(
      int status, String code, String detail, HttpServletRequest request) {
    var p =
        ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatusCode.valueOf(status), detail);
    p.setType(URI.create("urn:ott:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
    p.setProperty("code", code);
    var tracer = tracers.getIfAvailable();
    if (tracer != null && tracer.currentSpan() != null)
      p.setProperty("traceId", tracer.currentSpan().context().traceId());
    p.setInstance(URI.create(request.getRequestURI()));
    return p;
  }
}
