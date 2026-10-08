package com.ott.member.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.member.application.MemberFailure;
import com.ott.member.application.MemberService;
import com.ott.member.domain.model.MemberModels.Identity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemberIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

  static final OidcFixture OIDC = new OidcFixture();
  @Autowired MemberService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired TestRestTemplate http;

  @AfterAll
  static void closeOidc() {
    OIDC.close();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
    p.add("ott.security.issuer", OIDC::issuer);
    p.add("ott.security.jwks-uri", OIDC::jwks);
    p.add("ott.outbox.enabled", () -> "false");
  }

  Identity identity() {
    return new Identity(OIDC.issuer(), UUID.randomUUID().toString(), Instant.now());
  }

  org.springframework.http.ResponseEntity<String> call(
      String method, String path, Object body, String subject, String scope, boolean recent) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(OIDC.token(subject, scope, recent));
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    return http.exchange(
        path, HttpMethod.valueOf(method), new HttpEntity<>(body, headers), String.class);
  }

  @Test
  void provision_concurrentSameSubject_createsExactlyOneAccount() throws Exception {
    var identity = identity();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<UUID>>();
      for (int i = 0; i < 16; i++) tasks.add(() -> service.provision(identity).id());
      var results = pool.invokeAll(tasks, 10, TimeUnit.SECONDS);
      var ids = new ArrayList<UUID>();
      for (var f : results) ids.add(f.get());
      assertThat(ids.stream().distinct().count()).isEqualTo(1);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account WHERE issuer=? AND subject=?",
                Integer.class,
                identity.issuer(),
                identity.subject()))
        .isEqualTo(1);
  }

  @Test
  void provision_sameSubjectDifferentIssuer_doesNotShareAccount() {
    var one = identity();
    var two = new Identity("https://other-issuer.invalid", one.subject(), Instant.now());
    assertThat(service.provision(one).id()).isNotEqualTo(service.provision(two).id());
  }

  @Test
  void profile_kidsOverCeiling_rejectsWithoutPersisting() {
    var identity = identity();
    service.provision(identity);
    assertThatThrownBy(() -> service.createProfile(identity, "Kid", true, 18, "en-IN"))
        .isInstanceOf(MemberFailure.class);
    assertThat(service.profiles(identity)).isEmpty();
  }

  @Test
  void profile_weakeningWithoutRecentAuthentication_denies() {
    var identity = identity();
    service.provision(identity);
    var profile = service.createProfile(identity, "Kid", true, 7, "en-IN");
    var stale =
        new Identity(identity.issuer(), identity.subject(), Instant.now().minusSeconds(600));
    assertThatThrownBy(
            () -> service.updateProfile(stale, profile.id(), "Kid", false, 18, "en-IN", 1))
        .isInstanceOf(MemberFailure.class)
        .extracting("code")
        .isEqualTo("GUARDIAN_REAUTH_REQUIRED");
    assertThat(
            service.updateProfile(identity, profile.id(), "Adult", false, 18, "en-IN", 1).version())
        .isEqualTo(2);
  }

  @Test
  void profile_foreignAccountAndStaleVersion_rejects() {
    var owner = identity();
    var other = identity();
    service.provision(owner);
    service.provision(other);
    var profile = service.createProfile(owner, "Owned", false, 18, "en");
    assertThatThrownBy(
            () -> service.updateProfile(other, profile.id(), "Stolen", false, 18, "en", 1))
        .isInstanceOf(MemberFailure.class)
        .extracting("status")
        .isEqualTo(404);
    service.updateProfile(owner, profile.id(), "Changed", false, 18, "en", 1);
    assertThatThrownBy(() -> service.updateProfile(owner, profile.id(), "Old", false, 18, "en", 1))
        .isInstanceOf(MemberFailure.class)
        .extracting("code")
        .isEqualTo("VERSION_CONFLICT");
  }

  @Test
  void profile_adultCreationWithoutGuardianAuth_cannotBypassKidsRestrictions() {
    var identity = identity();
    service.provision(identity);
    var stale = new Identity(identity.issuer(), identity.subject(), null);
    assertThatThrownBy(() -> service.createProfile(stale, "Bypass", false, 18, "en"))
        .isInstanceOf(MemberFailure.class)
        .extracting("code")
        .isEqualTo("GUARDIAN_REAUTH_REQUIRED");
  }

  @Test
  void api_oversizeJson_rejectsBeforeParsingOrPersisting() {
    var subject = UUID.randomUUID().toString();
    var response =
        call("POST", "/v1/profiles", Map.of("name", "x".repeat(270000)), subject, "", true);
    assertThat(response.getStatusCode().value()).isEqualTo(413);
    assertThat(response.getBody()).contains("PAYLOAD_TOO_LARGE");
  }

  @Test
  void profile_parallelCreation_cannotExceedLimit() throws Exception {
    var identity = identity();
    service.provision(identity);
    try (var pool = Executors.newFixedThreadPool(12)) {
      var tasks = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 12; i++)
        tasks.add(
            () -> {
              try {
                service.createProfile(identity, "User", false, 18, "en");
                return true;
              } catch (MemberFailure e) {
                assertThat(e.code()).isEqualTo("PROFILE_LIMIT_REACHED");
                return false;
              }
            });
      int accepted = 0;
      for (var result : pool.invokeAll(tasks, 10, TimeUnit.SECONDS)) if (result.get()) accepted++;
      assertThat(accepted).isEqualTo(5);
    }
    assertThat(service.profiles(identity)).hasSize(5);
  }

  @Test
  void device_parallelRegistration_cannotExceedLimit() throws Exception {
    var identity = identity();
    service.provision(identity);
    try (var pool = Executors.newFixedThreadPool(12)) {
      var tasks = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 16; i++) {
        String installation = "install-" + i;
        tasks.add(
            () -> {
              try {
                service.registerDevice(
                    identity, installation, "TV", List.of("H264"), List.of("WIDEVINE"));
                return true;
              } catch (MemberFailure e) {
                assertThat(e.code()).isEqualTo("DEVICE_LIMIT_REACHED");
                return false;
              }
            });
      }
      int accepted = 0;
      for (var result : pool.invokeAll(tasks, 10, TimeUnit.SECONDS)) if (result.get()) accepted++;
      assertThat(accepted).isEqualTo(10);
    }
    assertThat(service.devices(identity)).hasSize(10);
  }

  @Test
  void device_duplicateConflictAndRevoke_arePersistentAndEmitOnce() {
    var identity = identity();
    var account = service.provision(identity);
    var profile = service.createProfile(identity, "Main", false, 18, "en");
    var device =
        service.registerDevice(identity, "install-001", "TV", List.of("H264"), List.of("WIDEVINE"));
    assertThat(
            service
                .registerDevice(identity, "install-001", "TV", List.of("H264"), List.of("WIDEVINE"))
                .id())
        .isEqualTo(device.id());
    assertThatThrownBy(
            () ->
                service.registerDevice(
                    identity, "install-001", "Other", List.of("H264"), List.of("WIDEVINE")))
        .isInstanceOf(MemberFailure.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(service.deviceEligibility(account.id(), profile.id(), device.id()).allowed())
        .isTrue();
    service.revokeDevice(identity, device.id());
    service.revokeDevice(identity, device.id());
    assertThat(service.deviceEligibility(account.id(), profile.id(), device.id()).allowed())
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=?",
                Integer.class,
                device.id()))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                service.registerDevice(
                    identity, "install-001", "TV", List.of("H264"), List.of("WIDEVINE")))
        .isInstanceOf(MemberFailure.class)
        .extracting("code")
        .isEqualTo("DEVICE_NOT_ALLOWED");
  }

  @Test
  void api_authenticatedJourney_validatesAndEnforcesInternalScope() {
    String subject = UUID.randomUUID().toString();
    var account = call("PUT", "/v1/accounts/me", null, subject, "", true);
    assertThat(account.getStatusCode().value()).isEqualTo(200);
    assertThat(call("PUT", "/v1/accounts/me", null, subject, "", true).getBody())
        .isEqualTo(account.getBody());
    var invalid =
        call(
            "POST",
            "/v1/profiles",
            Map.of("name", "", "kids", true, "maturity", 18, "locale", "en"),
            subject,
            "",
            true);
    assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    assertThat(invalid.getBody()).contains("INVALID_REQUEST");
    var profile =
        call(
            "POST",
            "/v1/profiles",
            Map.of("name", "Main", "kids", false, "maturity", 18, "locale", "en"),
            subject,
            "",
            true);
    assertThat(profile.getStatusCode().value()).isEqualTo(201);
    assertThat(
            call("GET", "/v1/profiles", null, UUID.randomUUID().toString(), "", true)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    var query =
        Map.of(
            "accountId",
            UUID.randomUUID(),
            "profileId",
            UUID.randomUUID(),
            "deviceId",
            UUID.randomUUID());
    assertThat(
            call("POST", "/internal/v1/member/eligibility", query, subject, "", true)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            call("POST", "/internal/v1/member/eligibility", query, subject, "member.internal", true)
                .getBody())
        .contains("\"allowed\":false");
  }
}
