package com.ott.playback.adapter.inbound.rest;

import com.ott.playback.application.PlaybackFailure;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProblemHandler {
  @ExceptionHandler(PlaybackFailure.class)
  ProblemDetail domain(PlaybackFailure e) {
    return problem(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  ProblemDetail invalid(Exception e) {
    return problem(400, "INVALID_REQUEST", "Invalid request fields");
  }

  @ExceptionHandler(org.springframework.web.client.RestClientException.class)
  ProblemDetail remote(org.springframework.web.client.RestClientException e) {
    return problem(503, "DEPENDENCY_UNAVAILABLE", "Member identity unavailable");
  }

  @ExceptionHandler(DataAccessException.class)
  ProblemDetail db(DataAccessException e) {
    return problem(503, "DEPENDENCY_UNAVAILABLE", "Persistence unavailable");
  }

  private static ProblemDetail problem(int status, String code, String detail) {
    var p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
    p.setType(URI.create("urn:ott:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
    p.setProperty("code", code);
    return p;
  }
}
