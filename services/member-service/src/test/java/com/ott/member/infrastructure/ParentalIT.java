package com.ott.member.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.member.MemberApplication;
import com.ott.member.application.MemberFailure;
import com.ott.member.application.MemberSecurityService;
import com.ott.member.application.MemberService;
import com.ott.member.application.ParentalService;
import com.ott.member.domain.model.MemberModels.Identity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = {MemberApplication.class, ParentalIT.Ports.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParentalIT {
  @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6");
  static final OidcFixture OIDC = new OidcFixture();
  static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
  @Autowired MemberService members;
  @Autowired ParentalService parents;
  @Autowired MemberSecurityService security;
  @Autowired JdbcTemplate db;
  @Autowired Time clock;
  Identity identity;
  UUID account, adult, kid, device;

  @AfterAll
  static void close() {
    OIDC.close();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", DB::getJdbcUrl);
    p.add("spring.datasource.username", DB::getUsername);
    p.add("spring.datasource.password", DB::getPassword);
    p.add("ott.security.issuer", OIDC::issuer);
    p.add("ott.security.jwks-uri", OIDC::jwks);
    p.add("ott.outbox.enabled", () -> "false");
  }

  @BeforeEach
  void reset() {
    db.execute("TRUNCATE account,outbox_event CASCADE");
    clock.at = NOW;
    identity = new Identity("fixture", "viewer", NOW);
    account = members.provision(identity).id();
    adult = members.createProfile(identity, "Adult", false, 18, "en").id();
    kid = members.createProfile(identity, "Kid", true, 7, "en").id();
    device =
        members.registerDevice(identity, "parental-fixture", "TV", List.of("H264"), List.of()).id();
  }

  @Test
  void lockedProfile_sharedJwtRequiresBoundProofAndKidsStayLimited() {
    parents.configure(identity, 0, null, "2468");
    assertThat(members.deviceEligibility(account, adult, device).allowed()).isFalse();
    assertThat(members.deviceEligibility(account, kid, device).allowed()).isTrue();
    var grant = parents.unlock(identity, adult, device, "2468");
    assertThat(members.deviceEligibility(account, adult, device, grant.token(), false).allowed())
        .isTrue();
    assertThat(
            members
                .deviceEligibility(account, adult, UUID.randomUUID(), grant.token(), false)
                .allowed())
        .isFalse();
    assertThat(db.queryForObject("SELECT token_hash FROM profile_selection", String.class))
        .doesNotContain(grant.token());
    assertThat(
            db.queryForObject(
                "SELECT pin_hash FROM parental_control WHERE account_id=?", String.class, account))
        .startsWith("$2a$")
        .doesNotContain("2468");
    clock.at = NOW.plusSeconds(1800);
    assertThat(members.deviceEligibility(account, adult, device, grant.token(), false).allowed())
        .isFalse();
  }

  @Test
  void pinFailureCounters_surviveRejectedTransactionsAndCooldown() {
    parents.configure(identity, 0, null, "2468");
    for (int n = 0; n < 5; n++)
      assertThatThrownBy(() -> parents.unlock(identity, adult, device, "9999"))
          .isInstanceOf(MemberFailure.class);
    assertThat(
            db.queryForObject(
                "SELECT failures FROM parental_control WHERE account_id=?", Integer.class, account))
        .isEqualTo(5);
    assertThatThrownBy(() -> parents.unlock(identity, adult, device, "2468"))
        .isInstanceOf(MemberFailure.class)
        .extracting("code")
        .isEqualTo("PARENTAL_PIN_LOCKED");
    clock.at = NOW.plusSeconds(901);
    assertThat(parents.unlock(identity, adult, device, "2468").token()).hasSize(43);
  }

  @Test
  void pinChange_profileChangeAndGuardianBypass_fenced() {
    parents.configure(identity, 0, null, "2468");
    var old = parents.unlock(identity, adult, device, "2468");
    assertThatThrownBy(() -> members.updateProfile(identity, kid, "Kid", false, 18, "en", 1))
        .isInstanceOf(MemberFailure.class);
    members.updateProfile(identity, kid, "Adult two", false, 18, "en", 1, "2468");
    assertThatThrownBy(() -> parents.configure(identity, 1, "9999", "8642"))
        .isInstanceOf(MemberFailure.class);
    parents.configure(identity, 1, "2468", "8642");
    assertThat(members.deviceEligibility(account, adult, device, old.token(), false).allowed())
        .isFalse();
    assertThatThrownBy(
            () ->
                parents.configure(
                    new Identity("fixture", "viewer", NOW.minusSeconds(121)), 2, "8642", null))
        .isInstanceOf(MemberFailure.class);
    assertThatThrownBy(() -> parents.configure(identity, 1, "8642", null))
        .isInstanceOf(MemberFailure.class)
        .extracting("code")
        .isEqualTo("PARENTAL_VERSION_CONFLICT");
  }

  @Test
  void securityCommands_replayCollisionRestrictionAndEpochAudited() {
    UUID op = UUID.randomUUID();
    var result = security.command(op, account, "SUSPEND", "SUPPORT_REVIEW", "ops-client");
    assertThat(security.command(op, account, "SUSPEND", "SUPPORT_REVIEW", "ops-client"))
        .isEqualTo(result);
    assertThatThrownBy(
            () -> security.command(op, account, "RESUME", "SUPPORT_REVIEW", "ops-client"))
        .isInstanceOf(MemberFailure.class)
        .extracting("code")
        .isEqualTo("OPERATION_CONFLICT");
    assertThatThrownBy(() -> members.account(identity))
        .isInstanceOf(MemberFailure.class)
        .extracting("code")
        .isEqualTo("ACCOUNT_RESTRICTED");
    assertThat(members.deviceEligibility(account, adult, device).allowed()).isFalse();
    var resumed =
        security.command(UUID.randomUUID(), account, "RESUME", "SUPPORT_REVIEW", "ops-client");
    assertThat(resumed.securityEpoch()).isGreaterThan(result.securityEpoch());
    assertThat(members.account(identity).id()).isEqualTo(account);
    assertThat(db.queryForObject("SELECT count(*) FROM member_security_audit", Integer.class))
        .isEqualTo(2);
  }

  static class Time extends Clock {
    Instant at = NOW;

    public ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return at;
    }
  }

  @TestConfiguration
  static class Ports {
    @Bean
    @Primary
    Time time() {
      return new Time();
    }
  }
}
