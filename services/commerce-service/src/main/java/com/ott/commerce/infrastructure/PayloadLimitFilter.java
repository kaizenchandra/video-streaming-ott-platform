package com.ott.commerce.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces actual JSON byte limits, including chunked bodies, before parsing. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PayloadLimitFilter extends OncePerRequestFilter {
  private static final int LIMIT = 256 * 1024;

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if (!req.getRequestURI().startsWith("/v1/") && !req.getRequestURI().startsWith("/internal/")) {
      chain.doFilter(req, res);
      return;
    }
    if (req.getContentLengthLong() > LIMIT) {
      reject(res);
      return;
    }
    byte[] body = req.getInputStream().readNBytes(LIMIT + 1);
    if (body.length > LIMIT) {
      reject(res);
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(req) {
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              public int read() {
                return input.read();
              }

              public int read(byte[] buffer, int off, int len) {
                return input.read(buffer, off, len);
              }

              public boolean isFinished() {
                return input.available() == 0;
              }

              public boolean isReady() {
                return true;
              }

              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Blocking MVC input only");
              }
            };
          }

          public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                new java.io.InputStreamReader(
                    getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        },
        res);
  }

  private static void reject(HttpServletResponse res) throws IOException {
    res.setStatus(413);
    res.setContentType("application/problem+json");
    res.getWriter()
        .write(
            "{\"type\":\"urn:ott:problem:payload_too_large\",\"title\":\"Payload too"
                + " large\",\"status\":413,\"code\":\"PAYLOAD_TOO_LARGE\"}");
  }
}
