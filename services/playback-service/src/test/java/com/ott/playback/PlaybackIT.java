package com.ott.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.playback.adapter.outbound.security.GeoAssertions;
import com.ott.playback.application.Eligibility;
import com.ott.playback.application.PlaybackFailure;
import com.ott.playback.application.PlaybackService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = {PlaybackApplication.class, PlaybackIT.TestPorts.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlaybackIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4.5").withExposedPorts(6379);

  static final OidcFixture OIDC = new OidcFixture();
  static final String SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();
  @Autowired com.ott.playback.application.OfflineService offline;
  @Autowired PlaybackService service;
  @Autowired JdbcTemplate db;
  @Autowired StringRedisTemplate redis;
  @Autowired MutableClock clock;
  @Autowired TestEligibility eligibility;
  @Autowired GeoAssertions geo;

  @AfterAll
  static void close() {
    OIDC.close();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    p.add("spring.datasource.username", POSTGRES::getUsername);
    p.add("spring.datasource.password", POSTGRES::getPassword);
    p.add("spring.data.redis.host", REDIS::getHost);
    p.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    p.add("ott.security.issuer", OIDC::issuer);
    p.add("ott.security.jwks-uri", OIDC::jwks);
    p.add("ott.outbox.enabled", () -> "false");
    p.add("ott.providers.mode", () -> "mock");
    p.add("ott.token.secret", () -> SECRET);
    p.add("ott.geo.secret", () -> SECRET);
    p.add("ott.workload.token-url", () -> "");
    p.add("ott.workload.client-id", () -> "");
    p.add("ott.workload.client-secret", () -> "");
  }

  @BeforeEach
  void reset() {
    clock.at = Instant.now();
    eligibility.deny = false;
    eligibility.duration = 300;
    eligibility.liveEpoch = 1;
  }

  Eligibility.Identity identity() {
    return new Eligibility.Identity("test", UUID.randomUUID().toString(), "fixture");
  }

  PlaybackService.Authorization start(Eligibility.Identity i, String key) {
    return service.start(i, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "IN", key);
  }

  @Test
  void epochs_onlineOfflineAndClockRollback_strictlyIncreaseWithValidatedContext() {
    var i = identity();
    var profile = UUID.randomUUID();
    var device = UUID.randomUUID();
    var content = UUID.randomUUID();
    var online = service.start(i, profile, device, content, "IN", "epochs-online-01");
    var first = service.context(online.playbackToken(), device);
    var download = offline.authorize(i, profile, device, content, "IN", "epochs-offline-01");
    var second = offline.context(download.offlineToken(), device);
    var next = service.start(i, profile, device, content, "IN", "epochs-online-02");
    var third = service.context(next.playbackToken(), device);
    assertThat(first.accountId()).isEqualTo(second.accountId());
    assertThat(first.profileId()).isEqualTo(profile);
    assertThat(first.contentId()).isEqualTo(content);
    assertThat(second.epoch()).isGreaterThan(first.epoch());
    assertThat(third.epoch()).isGreaterThan(second.epoch());
    // Advance the persisted counter beyond wall time: a backwards clock must not lower it.
    db.update(
        "UPDATE account_quota SET next_epoch=next_epoch+1000000 WHERE account_id=?",
        first.accountId());
    var later = offline.authorize(i, profile, device, content, "IN", "epochs-offline-02");
    assertThat(offline.context(later.offlineToken(), device).epoch())
        .isGreaterThan(third.epoch() + 999999);
  }

  @Test
  void start_parallelAccountsLimit_neverIssuesMoreThanTwoGrants() throws Exception {
    var i = identity();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<String>>();
      for (int n = 0; n < 8; n++)
        tasks.add(
            () -> {
              try {
                return start(i, "request-" + UUID.randomUUID()).sessionId().toString();
              } catch (PlaybackFailure e) {
                return e.code();
              }
            });
      var results = new ArrayList<String>();
      for (var f : pool.invokeAll(tasks, 10, TimeUnit.SECONDS)) results.add(f.get());
      assertThat(results.stream().filter(x -> x.equals("CONCURRENT_STREAM_LIMIT_REACHED")).count())
          .isEqualTo(6);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM playback_session WHERE account_id=? AND state='ACTIVE'",
                  Integer.class,
                  eligibility.account(i)))
          .isEqualTo(2);
    }
  }

  @Test
  void liveAndVod_parallelStarts_shareDurableQuota() throws Exception {
    var identity = identity();
    var event = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<String>>();
      for (int n = 0; n < 8; n++) {
        boolean live = n % 2 == 0;
        tasks.add(
            () -> {
              try {
                return (live
                        ? service.startLive(
                            identity,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            event,
                            "IN",
                            "live-" + UUID.randomUUID(),
                            "LIVE",
                            null)
                        : start(identity, "vod-" + UUID.randomUUID()))
                    .sessionId()
                    .toString();
              } catch (PlaybackFailure e) {
                return e.code();
              }
            });
      }
      int rejected = 0;
      for (var f : pool.invokeAll(tasks, 10, TimeUnit.SECONDS))
        if (f.get().equals("CONCURRENT_STREAM_LIMIT_REACHED")) rejected++;
      assertThat(rejected).isEqualTo(6);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM playback_session WHERE account_id=? AND state='ACTIVE'",
                  Integer.class,
                  eligibility.account(identity)))
          .isEqualTo(2);
    }
  }

  @Test
  void live_failoverAndRevocation_fenceHeartbeatAndIdempotentGrant() {
    var identity = identity();
    var event = UUID.randomUUID();
    var profile = UUID.randomUUID();
    var device = UUID.randomUUID();
    var key = "live-start-01";
    var grant = service.startLive(identity, profile, device, event, "IN", key, "LIVE", null);
    assertThat(grant.expiresAt()).isBeforeOrEqualTo(clock.instant().plusSeconds(30));
    assertThat(
            db.queryForObject(
                "SELECT target_kind FROM playback_session WHERE id=?",
                String.class,
                grant.sessionId()))
        .isEqualTo("LIVE");
    eligibility.liveEpoch = 2;
    assertThatThrownBy(() -> service.heartbeat(identity, grant.sessionId(), "IN"))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("CONTENT_VERSION_CHANGED");
    assertThatThrownBy(
            () -> service.startLive(identity, profile, device, event, "IN", key, "LIVE", null))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("CONTENT_VERSION_CHANGED");
    eligibility.liveEpoch = 1;
    eligibility.deny = true;
    assertThatThrownBy(() -> service.heartbeat(identity, grant.sessionId(), "IN"))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("DEVICE_NOT_ALLOWED");
  }

  @Test
  void start_parallelSameKey_oneSessionAndOneStartedEvent() throws Exception {
    var i = identity();
    var profile = UUID.randomUUID();
    var device = UUID.randomUUID();
    var content = UUID.randomUUID();
    var key = "request-" + UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<Callable<UUID>>();
      for (int n = 0; n < 6; n++)
        tasks.add(() -> service.start(i, profile, device, content, "IN", key).sessionId());
      var results = new ArrayList<UUID>();
      for (var f : pool.invokeAll(tasks, 10, TimeUnit.SECONDS)) results.add(f.get());
      assertThat(results.stream().distinct().count()).isEqualTo(1);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM outbox_event WHERE aggregate_id=?",
                  Integer.class,
                  results.getFirst()))
          .isEqualTo(1);
    }
  }

  @Test
  void start_sameKeyDifferentRequest_rejectsConflict() {
    var i = identity();
    var key = "request-" + UUID.randomUUID();
    start(i, key);
    assertThatThrownBy(() -> start(i, key))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void redis_loss_keepsDurableSlotsAndDoesNotOverbook() {
    var i = identity();
    var a = start(i, "request-" + UUID.randomUUID());
    start(i, "request-" + UUID.randomUUID());
    redis.delete("playback:lease:{" + eligibility.account(i) + "}");
    assertThatThrownBy(() -> start(i, "request-" + UUID.randomUUID()))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("CONCURRENT_STREAM_LIMIT_REACHED");
    assertThatThrownBy(() -> service.heartbeat(i, a.sessionId(), "IN"))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("LEASE_LOST");
  }

  @Test
  void end_beforeGrantExpires_keepsCapacityUntilExpiry() {
    var i = identity();
    var a = start(i, "request-" + UUID.randomUUID());
    start(i, "request-" + UUID.randomUUID());
    service.end(i, a.sessionId());
    service.end(i, a.sessionId());
    assertThatThrownBy(() -> start(i, "request-" + UUID.randomUUID()))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("CONCURRENT_STREAM_LIMIT_REACHED");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND"
                    + " topic='playback.ended.v1'",
                Integer.class,
                a.sessionId()))
        .isEqualTo(1);
    clock.at = clock.at.plusSeconds(61);
    var next = start(i, "request-" + UUID.randomUUID());
    assertThat(next.sessionId()).isNotEqualTo(a.sessionId());
  }

  @Test
  void grant_bindsDeviceSignatureAndLedger_andCannotRenewExpiredSession() {
    var i = identity();
    var a = start(i, "request-" + UUID.randomUUID());
    var s = service.owned(eligibility.account(i), a.sessionId());
    assertThat(service.validateToken(a.playbackToken(), s.deviceId())).isEqualTo(s.id());
    assertThatThrownBy(() -> service.validateToken(a.playbackToken(), UUID.randomUUID()))
        .isInstanceOf(PlaybackFailure.class);
    assertThatThrownBy(() -> service.validateToken(a.playbackToken() + "x", s.deviceId()))
        .isInstanceOf(PlaybackFailure.class);
    clock.at = clock.at.plusSeconds(91);
    assertThatThrownBy(() -> service.validateToken(a.playbackToken(), s.deviceId()))
        .isInstanceOf(PlaybackFailure.class);
    assertThatThrownBy(() -> service.heartbeat(i, s.id(), "IN"))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("SESSION_EXPIRED");
  }

  @Test
  void heartbeat_revocationOrChangedRegion_deniesNewGrant() {
    var i = identity();
    var a = start(i, "request-" + UUID.randomUUID());
    assertThatThrownBy(() -> service.heartbeat(i, a.sessionId(), "US"))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("REGION_CHANGED");
    eligibility.deny = true;
    assertThatThrownBy(() -> service.heartbeat(i, a.sessionId(), "IN"))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("DEVICE_NOT_ALLOWED");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM grant_audit WHERE session_id=?",
                Integer.class,
                a.sessionId()))
        .isEqualTo(1);
  }

  @Test
  void heartbeat_valid_renewsWithinAuthorizationExpiry() {
    var i = identity();
    eligibility.duration = 70;
    var a = start(i, "request-" + UUID.randomUUID());
    clock.at = clock.at.plusSeconds(10);
    var renewed = service.heartbeat(i, a.sessionId(), "IN");
    assertThat(renewed.expiresAt())
        .isAfter(a.expiresAt())
        .isBeforeOrEqualTo(clock.at.plusSeconds(60));
  }

  @Test
  void session_otherAccount_cannotEndOrRead() {
    var i = identity();
    var a = start(i, "request-" + UUID.randomUUID());
    assertThatThrownBy(() -> service.end(identity(), a.sessionId()))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("SESSION_NOT_FOUND");
  }

  @Test
  void expiry_worker_isIdempotentAndExpiredTokenCannotConsumeCapacity() {
    var i = identity();
    var a = start(i, "request-" + UUID.randomUUID());
    clock.at = clock.at.plusSeconds(91);
    service.expire();
    service.expire();
    assertThat(service.owned(eligibility.account(i), a.sessionId()).state()).isEqualTo("ENDED");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND"
                    + " topic='playback.ended.v1'",
                Integer.class,
                a.sessionId()))
        .isEqualTo(1);
  }

  @Test
  void redis_timeout_failsClosedAndCancelsOnlyUnissuedReservation() throws Exception {
    var i = identity();
    REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "1000", "ALL");
    try {
      assertThatThrownBy(() -> start(i, "request-" + UUID.randomUUID()))
          .isInstanceOf(PlaybackFailure.class)
          .extracting("code")
          .isEqualTo("PLAYBACK_UNAVAILABLE");
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM grant_audit g JOIN playback_session s ON s.id=g.session_id"
                      + " WHERE s.account_id=?",
                  Integer.class,
                  eligibility.account(i)))
          .isZero();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM playback_session WHERE account_id=? AND state='PENDING'",
                  Integer.class,
                  eligibility.account(i)))
          .isZero();
    } finally {
      REDIS.execInContainer("redis-cli", "PING");
    }
  }

  @Test
  void offline_policyQuotaDeviceAudienceAndRevocation_areEnforced() {
    var i = identity();
    var p = UUID.randomUUID();
    var d = UUID.randomUUID();
    var c = UUID.randomUUID();
    var a = offline.authorize(i, p, d, c, "IN", "download-key-01");
    assertThat(offline.authorize(i, p, d, c, "IN", "download-key-01").downloadId())
        .isEqualTo(a.downloadId());
    assertThat(a.deliveryExpiresAt()).isBefore(a.licenseExpiresAt());
    assertThat(offline.validate(a.offlineToken(), d)).isEqualTo(a.downloadId());
    assertThatThrownBy(() -> service.validateToken(a.offlineToken(), d))
        .isInstanceOf(PlaybackFailure.class);
    assertThatThrownBy(() -> offline.validate(a.offlineToken(), UUID.randomUUID()))
        .isInstanceOf(PlaybackFailure.class);
    assertThatThrownBy(() -> offline.revoke(identity(), a.downloadId()))
        .isInstanceOf(PlaybackFailure.class);
    for (int n = 0; n < 4; n++)
      offline.authorize(i, p, d, UUID.randomUUID(), "IN", "download-more-" + n);
    assertThatThrownBy(() -> offline.authorize(i, p, d, UUID.randomUUID(), "IN", "download-more-5"))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("OFFLINE_LIMIT_REACHED");
    offline.revoke(i, a.downloadId());
    assertThatThrownBy(() -> offline.validate(a.offlineToken(), d))
        .isInstanceOf(PlaybackFailure.class);
  }

  @Test
  void offlineValidation_currentOwnerRevocation_failsClosed() {
    var i = new Eligibility.Identity("fixture", UUID.randomUUID().toString(), "viewer");
    UUID profile = UUID.randomUUID(), device = UUID.randomUUID();
    var grant = offline.authorize(i, profile, device, UUID.randomUUID(), "IN", "issued-owner-01");
    assertThat(offline.validate(grant.offlineToken(), device)).isEqualTo(grant.downloadId());
    eligibility.deny = true;
    assertThatThrownBy(() -> offline.validate(grant.offlineToken(), device))
        .isInstanceOf(PlaybackFailure.class)
        .extracting("code")
        .isEqualTo("OFFLINE_TOKEN_DENIED");
  }

  @Test
  void geo_signedAssertion_bindsIdentityDeviceCountryAndFreshness() throws Exception {
    var device = UUID.randomUUID();
    long at = clock.instant().getEpochSecond();
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(
        new javax.crypto.spec.SecretKeySpec(
            SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
    String signature =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mac.doFinal(
                    (at + "\ntest\nviewer\n" + device + "\nIN")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    String assertion = at + "." + signature;
    geo.verify("test", "viewer", device, "IN", assertion);
    assertThatThrownBy(() -> geo.verify("test", "viewer", device, "US", assertion))
        .isInstanceOf(PlaybackFailure.class);
    assertThatThrownBy(() -> geo.verify("test", "other", device, "IN", assertion))
        .isInstanceOf(PlaybackFailure.class);
    clock.at = clock.at.plusSeconds(31);
    assertThatThrownBy(() -> geo.verify("test", "viewer", device, "IN", assertion))
        .isInstanceOf(PlaybackFailure.class);
  }

  static class MutableClock extends Clock {
    volatile Instant at = Instant.now();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return at;
    }
  }

  static class TestEligibility implements Eligibility {
    final MutableClock clock;
    volatile boolean deny;
    volatile int duration = 300;
    volatile long liveEpoch = 1;

    public LiveCheck checkLive(
        Identity i, UUID p, UUID d, UUID event, String country, String mode, Instant seek) {
      if (deny) throw new PlaybackFailure(403, "DEVICE_NOT_ALLOWED", "Revoked device");
      return new LiveCheck(
          new Check(
              account(i),
              2,
              clock.instant().plusSeconds(30),
              "live/" + event + "/e" + liveEpoch + "/",
              false),
          event);
    }

    public boolean issuedAllowed(
        UUID a, UUID p, UUID d, UUID c, String country, String prefix, long epoch) {
      return !deny;
    }

    TestEligibility(MutableClock clock) {
      this.clock = clock;
    }

    public UUID account(Identity i) {
      return UUID.fromString(i.subject());
    }

    public Check check(Identity i, UUID p, UUID d, UUID c, String country, boolean offline) {
      if (deny) throw new PlaybackFailure(403, "DEVICE_NOT_ALLOWED", "Revoked device");
      return new Check(
          account(i), 2, clock.instant().plusSeconds(duration), "vod/" + c + "/v1/", true);
    }
  }

  @TestConfiguration
  static class TestPorts {
    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }

    @Bean
    @Primary
    TestEligibility testEligibility(MutableClock c) {
      return new TestEligibility(c);
    }
  }
}
