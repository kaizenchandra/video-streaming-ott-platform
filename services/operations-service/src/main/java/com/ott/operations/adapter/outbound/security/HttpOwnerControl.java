package com.ott.operations.adapter.outbound.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ott.operations.application.OperationsFailure;
import com.ott.operations.application.OwnerControl;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpOwnerControl implements OwnerControl {
  private final RestClient http;
  private final String member, commerce;
  private final WorkloadTokens tokens;
  private final ObjectMapper json;
  private final Semaphore calls = new Semaphore(16);

  public HttpOwnerControl(
      io.micrometer.observation.ObservationRegistry observations,
      @Value("${ott.owners.member}") String member,
      @Value("${ott.owners.commerce}") String commerce,
      WorkloadTokens tokens,
      ObjectMapper json) {
    this.member = member;
    this.commerce = commerce;
    this.tokens = tokens;
    this.json = json;
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(Duration.ofMillis(100));
    f.setReadTimeout(Duration.ofMillis(300));
    http = RestClient.builder().observationRegistry(observations).requestFactory(f).build();
  }

  public Map<String, Object> summary(UUID account) {
    var row = request(member + "/internal/v1/member/security/accounts/" + account, null);
    if (!account.toString().equals(row.path("id").asText()))
      throw fail(503, "OWNER_RESPONSE_INVALID");
    return Map.of(
        "id",
        account,
        "status",
        row.path("status").asText(),
        "securityEpoch",
        row.path("security_epoch").asLong(),
        "profiles",
        row.path("profiles").asInt(),
        "activeDevices",
        row.path("activeDevices").asInt());
  }

  public Result apply(UUID op, UUID account, String action, String reason) {
    boolean memberAction = action.equals("SUSPEND_ACCOUNT") || action.equals("RESUME_ACCOUNT");
    String ownerAction =
        switch (action) {
          case "SUSPEND_ACCOUNT" -> "SUSPEND";
          case "RESUME_ACCOUNT" -> "RESUME";
          case "REVOKE_ENTITLEMENT" -> "REVOKE";
          case "RESTORE_ENTITLEMENT" -> "RESTORE";
          default -> throw fail(400, "INVALID_OWNER_ACTION");
        };
    var row =
        request(
            (memberAction
                ? member + "/internal/v1/member/security/commands"
                : commerce + "/internal/v1/commerce/security/commands"),
            Map.of(
                "operationId", op, "accountId", account, "action", ownerAction, "reason", reason));
    long version = row.path(memberAction ? "securityEpoch" : "version").asLong();
    if (!op.toString().equals(row.path("operationId").asText())
        || !account.toString().equals(row.path("accountId").asText())
        || version < 1) throw fail(503, "OWNER_RESPONSE_INVALID");
    if (memberAction
        && !row.path("status")
            .asText()
            .equals(ownerAction.equals("SUSPEND") ? "SUSPENDED" : "ACTIVE"))
      throw fail(503, "OWNER_RESPONSE_INVALID");
    return new Result(version);
  }

  private com.fasterxml.jackson.databind.JsonNode request(String uri, Map<String, Object> input) {
    if (!calls.tryAcquire()) throw fail(503, "OWNER_OVERLOADED");
    try {
      var request =
          input == null
              ? http.get().uri(uri).headers(h -> h.setBearerAuth(tokens.token()))
              : http.post().uri(uri).headers(h -> h.setBearerAuth(tokens.token())).body(input);
      return request.exchange(
          (req, res) -> {
            int status = res.getStatusCode().value();
            if (status >= 400 && status < 500)
              throw fail(status == 404 ? 404 : 409, "OWNER_REJECTED");
            if (status != 200) throw fail(503, "OWNER_UNAVAILABLE");
            byte[] bytes = res.getBody().readNBytes(65537);
            if (bytes.length > 65536) throw fail(503, "OWNER_RESPONSE_INVALID");
            try {
              return json.readTree(bytes);
            } catch (java.io.IOException e) {
              throw fail(503, "OWNER_RESPONSE_INVALID");
            }
          });
    } catch (RestClientException e) {
      throw fail(503, "OWNER_UNAVAILABLE");
    } finally {
      calls.release();
    }
  }

  private static OperationsFailure fail(int s, String c) {
    return new OperationsFailure(s, c);
  }
}
