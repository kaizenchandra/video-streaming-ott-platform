package com.ott.experience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ott.experience.application.ViewerAccess;
import com.ott.experience.application.WatchFailure;
import com.ott.experience.application.WatchService;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = {ExperienceApplication.class, WatchIT.Ports.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WatchIT {
  @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6");
  static final OidcFixture OIDC = new OidcFixture();
  @Autowired WatchService service;
  @Autowired com.ott.experience.application.WatchRetention retention;
  @Autowired TestAccess access;
  @Autowired JdbcTemplate db;
  @LocalServerPort int port;

  @AfterAll
  static void close() {
    OIDC.close();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", DB::getJdbcUrl);
    p.add("spring.datasource.username", DB::getUsername);
    p.add("spring.datasource.password", DB::getPassword);
    p.add("ott.security.issuer", OIDC::issuer);
    p.add("ott.security.jwks-uri", OIDC::jwks);
    p.add("ott.outbox.enabled", () -> "false");
    p.add("ott.watchlist.max-items", () -> "2");
  }

  record Fixture(
      ViewerAccess.Identity identity, UUID profile, UUID content, UUID device, String token) {}

  Fixture fixture(long epoch) {
    var i = new ViewerAccess.Identity(UUID.randomUUID().toString());
    var profile = UUID.randomUUID();
    var account = UUID.randomUUID();
    var content = UUID.randomUUID();
    var device = UUID.randomUUID();
    String token = UUID.randomUUID().toString();
    access.profiles.put(
        profile, new Owned(i.viewerToken(), new ViewerAccess.Profile(account, profile, 18)));
    access.grants.put(
        token, new ViewerAccess.Grant(UUID.randomUUID(), account, profile, content, device, epoch));
    return new Fixture(i, profile, content, device, token);
  }

  WatchService.Observation observation(Fixture f, long seq, long position) {
    return new WatchService.Observation(
        UUID.randomUUID(),
        f.profile(),
        f.content(),
        f.device(),
        f.token(),
        false,
        seq,
        position,
        100000);
  }

  Fixture session(Fixture f, long epoch) {
    String token = UUID.randomUUID().toString();
    var old = access.grants.get(f.token());
    access.grants.put(
        token,
        new ViewerAccess.Grant(
            UUID.randomUUID(), old.accountId(), f.profile(), f.content(), f.device(), epoch));
    return new Fixture(f.identity(), f.profile(), f.content(), f.device(), token);
  }

  @Test
  void observe_duplicateAndConflictingEventUuid_deduplicatesOrRejects() {
    var f = fixture(100);
    var in = observation(f, 1, 1000);
    var first = service.observe(f.identity(), in);
    var dup = service.observe(f.identity(), in);
    assertThat(first.accepted()).isTrue();
    assertThat(dup.duplicate()).isTrue();
    assertThat(dup.progress().version()).isEqualTo(1);
    var conflict =
        new WatchService.Observation(
            in.eventId(),
            in.profileId(),
            in.contentId(),
            in.deviceId(),
            in.grantToken(),
            false,
            2,
            2000,
            100000);
    assertThatThrownBy(() -> service.observe(f.identity(), conflict))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id=(SELECT id FROM"
                    + " watch_progress WHERE profile_id=?)",
                Integer.class,
                f.profile()))
        .isEqualTo(1);
  }

  @Test
  void observe_sequenceOrdersAllowRewind_ignoreLateAndRejectSequenceCollision() {
    var f = fixture(200);
    service.observe(f.identity(), observation(f, 3, 70000));
    var rewind = service.observe(f.identity(), observation(f, 4, 20000));
    assertThat(rewind.progress().positionMillis()).isEqualTo(20000);
    assertThat(service.observe(f.identity(), observation(f, 2, 80000)).accepted()).isFalse();
    assertThat(service.observe(f.identity(), observation(f, 4, 20000)).duplicate()).isTrue();
    assertThatThrownBy(() -> service.observe(f.identity(), observation(f, 4, 21000)))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("SEQUENCE_CONFLICT");
    assertThat(service.resume(f.identity(), f.profile(), f.content()).version()).isEqualTo(2);
  }

  @Test
  void observe_newDeviceEpochWins_olderOfflineCannotOverwrite() {
    var f = fixture(300);
    service.observe(f.identity(), observation(f, 50, 60000));
    var newer = session(f, 301);
    service.observe(newer.identity(), observation(newer, 1, 10000));
    var late = observation(f, 51, 90000);
    var offline =
        new WatchService.Observation(
            late.eventId(),
            late.profileId(),
            late.contentId(),
            late.deviceId(),
            late.grantToken(),
            true,
            late.sequence(),
            late.positionMillis(),
            late.durationMillis());
    assertThat(service.observe(f.identity(), offline).accepted()).isFalse();
    assertThat(service.resume(f.identity(), f.profile(), f.content()).epoch()).isEqualTo(301);
    assertThat(service.history(f.identity(), f.profile(), null, 20).items()).hasSize(2);
  }

  @Test
  void observe_parallelSequences_finalStateIsLargestSequence() throws Exception {
    var f = fixture(400);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<WatchService.Receipt>>();
      for (int n = 1; n <= 20; n++) {
        int seq = n;
        tasks.add(() -> service.observe(f.identity(), observation(f, seq, seq * 1000)));
      }
      for (var result : pool.invokeAll(tasks, 10, TimeUnit.SECONDS)) result.get();
    }
    assertThat(service.resume(f.identity(), f.profile(), f.content()).sequence()).isEqualTo(20);
  }

  @Test
  void observe_wrongProfileContentOrDevice_failsWithoutMutation() {
    var f = fixture(500);
    var other = fixture(501);
    var grant = access.grants.get(f.token());
    access.grants.put(
        f.token(),
        new ViewerAccess.Grant(
            grant.sessionId(),
            access.grants.get(other.token()).accountId(),
            grant.profileId(),
            grant.contentId(),
            grant.deviceId(),
            grant.epoch()));
    assertThatThrownBy(() -> service.observe(f.identity(), observation(f, 1, 100)))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("PROGRESS_GRANT_DENIED");
    assertThatThrownBy(() -> service.resume(other.identity(), f.profile(), f.content()))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("PROFILE_NOT_FOUND");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM progress_inbox WHERE account_id=?",
                Integer.class,
                grant.accountId()))
        .isZero();
  }

  @Test
  void observe_outboxFailure_rollsBackInboxProgressAndHistory() {
    var f = fixture(600);
    db.execute(
        "ALTER TABLE outbox_event ADD CONSTRAINT fixture_watch_block"
            + " CHECK(topic!='watch.updated.v1') NOT VALID");
    try {
      assertThatThrownBy(() -> service.observe(f.identity(), observation(f, 1, 100)))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM watch_progress WHERE profile_id=?",
                  Integer.class,
                  f.profile()))
          .isZero();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM watch_history WHERE profile_id=?",
                  Integer.class,
                  f.profile()))
          .isZero();
    } finally {
      db.execute("ALTER TABLE outbox_event DROP CONSTRAINT fixture_watch_block");
    }
    assertThat(service.observe(f.identity(), observation(f, 1, 100)).accepted()).isTrue();
  }

  @Test
  void watchlist_parallelAdds_enforcesQuotaAndIdempotentDelete() throws Exception {
    var f = fixture(700);
    service.add(f.identity(), f.profile(), f.content());
    service.add(f.identity(), f.profile(), f.content());
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<String>>();
      for (int n = 0; n < 4; n++)
        tasks.add(
            () -> {
              try {
                service.add(f.identity(), f.profile(), UUID.randomUUID());
                return "ADDED";
              } catch (WatchFailure e) {
                return e.code();
              }
            });
      var results = pool.invokeAll(tasks);
      int added = 0;
      for (var result : results) if (result.get().equals("ADDED")) added++;
      assertThat(added).isEqualTo(1);
    }
    assertThat(service.list(f.identity(), f.profile(), null, 20).items()).hasSize(2);
    service.remove(f.identity(), f.profile(), f.content());
    service.remove(f.identity(), f.profile(), f.content());
    assertThat(service.list(f.identity(), f.profile(), null, 20).items()).hasSize(1);
  }

  @Test
  void reactions_starsAndThumbIndependent_removalLeavesVersionedTombstone() {
    var f = fixture(800);
    var rated = service.rate(f.identity(), f.profile(), f.content(), 4);
    assertThat(service.rate(f.identity(), f.profile(), f.content(), 4).version())
        .isEqualTo(rated.version());
    var both = service.react(f.identity(), f.profile(), f.content(), "LIKE");
    assertThat(both.rating()).isEqualTo(4);
    var thumb = service.rate(f.identity(), f.profile(), f.content(), null);
    assertThat(thumb.reaction()).isEqualTo("LIKE");
    var deleted = service.react(f.identity(), f.profile(), f.content(), null);
    assertThat(deleted.rating()).isNull();
    assertThat(deleted.reaction()).isNull();
    assertThat(deleted.version()).isGreaterThan(both.version());
    assertThat(
            db.queryForObject(
                "SELECT payload->>'removed' FROM outbox_event WHERE aggregate_id=(SELECT id FROM"
                    + " reaction WHERE profile_id=?) ORDER BY aggregate_version DESC LIMIT 1",
                String.class,
                f.profile()))
        .isEqualTo("true");
  }

  @Test
  void historyAndContinue_keysetNewestFirst_excludesFinishedAndValidatesCursor() {
    var f = fixture(900);
    service.observe(f.identity(), observation(f, 1, 1000));
    var other = fixture(901);
    var owned = access.profiles.get(f.profile());
    access.profiles.put(
        other.profile(),
        new Owned(
            other.identity().viewerToken(),
            new ViewerAccess.Profile(
                access.grants.get(other.token()).accountId(), other.profile(), 18)));
    var content = UUID.randomUUID();
    String token = UUID.randomUUID().toString();
    access.grants.put(
        token,
        new ViewerAccess.Grant(
            UUID.randomUUID(), owned.profile().accountId(), f.profile(), content, f.device(), 902));
    var newer = new Fixture(f.identity(), f.profile(), content, f.device(), token);
    service.observe(newer.identity(), observation(newer, 1, 2000));
    var page = service.recent(f.identity(), f.profile(), null, 1, true);
    assertThat(page.items().getFirst().contentId()).isEqualTo(content);
    assertThat(page.nextCursor()).isNotNull();
    assertThat(
            service
                .recent(f.identity(), f.profile(), page.nextCursor(), 1, true)
                .items()
                .getFirst()
                .contentId())
        .isEqualTo(f.content());
    service.observe(newer.identity(), observation(newer, 2, 100000));
    assertThat(service.recent(f.identity(), f.profile(), null, 20, true).items()).hasSize(1);
    assertThatThrownBy(() -> service.history(f.identity(), f.profile(), "invalid", 20))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("INVALID_CURSOR");
  }

  @Test
  void watchlist_kidsMaturityAndUnavailableGrant_denyWithoutWrites() {
    var f = fixture(1000);
    var owner = access.profiles.get(f.profile());
    access.profiles.put(
        f.profile(),
        new Owned(
            f.identity().viewerToken(),
            new ViewerAccess.Profile(owner.profile().accountId(), f.profile(), 7)));
    assertThatThrownBy(() -> service.add(f.identity(), f.profile(), f.content()))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("PARENTAL_CONTROL_DENIED");
    access.grants.remove(f.token());
    assertThatThrownBy(() -> service.observe(f.identity(), observation(f, 1, 100)))
        .isInstanceOf(WatchFailure.class)
        .extracting("code")
        .isEqualTo("OWNER_UNAVAILABLE");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM profile_owner WHERE profile_id=?",
                Integer.class,
                f.profile()))
        .isZero();
  }

  @Test
  void api_unsignedWrongOwnerAndFractionalSequence_areRejected() {
    var http = RestClient.create("http://localhost:" + port);
    assertThatThrownBy(
            () ->
                http.get()
                    .uri("/v1/profiles/" + UUID.randomUUID() + "/history")
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOf(RestClientResponseException.class)
        .extracting(e -> ((RestClientResponseException) e).getStatusCode().value())
        .isEqualTo(401);
    String jwt = OIDC.token("viewer", "", false);
    var f = fixture(1100);
    access.profiles.put(
        f.profile(),
        new Owned(
            jwt,
            new ViewerAccess.Profile(access.grants.get(f.token()).accountId(), f.profile(), 18)));
    var body =
        Map.of(
            "eventId",
            UUID.randomUUID(),
            "contentId",
            f.content(),
            "deviceId",
            f.device(),
            "grantToken",
            f.token(),
            "sequence",
            1.5,
            "positionMillis",
            100,
            "durationMillis",
            100000);
    assertThatThrownBy(
            () ->
                http.post()
                    .uri("/v1/profiles/" + f.profile() + "/progress")
                    .headers(h -> h.setBearerAuth(jwt))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOf(RestClientResponseException.class)
        .extracting(e -> ((RestClientResponseException) e).getStatusCode().value())
        .isEqualTo(400);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM watch_progress WHERE profile_id=?",
                Integer.class,
                f.profile()))
        .isZero();
  }

  record Owned(String token, ViewerAccess.Profile profile) {}

  @Test
  void retention_oldProgressHistoryReceiptsAndSequencesPurged_recentPreserved() {
    var old = fixture(1);
    var recent = fixture(1);
    service.observe(old.identity(), observation(old, 1, 1000));
    service.observe(recent.identity(), observation(recent, 1, 2000));
    var grant = access.grants.get(old.token());
    db.update(
        "UPDATE watch_progress SET updated_at=clock_timestamp()-interval '181 days' WHERE"
            + " profile_id=?",
        old.profile());
    db.update(
        "UPDATE watch_history SET last_at=clock_timestamp()-interval '181 days' WHERE profile_id=?",
        old.profile());
    db.update(
        "UPDATE progress_inbox SET received_at=clock_timestamp()-interval '181 days' WHERE"
            + " account_id=?",
        grant.accountId());
    db.update(
        "UPDATE session_sequence SET created_at=clock_timestamp()-interval '181 days' WHERE"
            + " session_id=?",
        grant.sessionId());
    retention.sweep();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM watch_progress WHERE profile_id=?",
                Integer.class,
                old.profile()))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM watch_history WHERE profile_id=?",
                Integer.class,
                old.profile()))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM progress_inbox WHERE account_id=?",
                Integer.class,
                grant.accountId()))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM session_sequence WHERE session_id=?",
                Integer.class,
                grant.sessionId()))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM watch_progress WHERE profile_id=?",
                Integer.class,
                recent.profile()))
        .isEqualTo(1);
  }

  static class TestAccess implements ViewerAccess {
    final Map<UUID, Owned> profiles = new ConcurrentHashMap<>();
    final Map<String, Grant> grants = new ConcurrentHashMap<>();

    public Profile profile(Identity identity, UUID id) {
      var stored = profiles.get(id);
      if (stored == null || !stored.token().equals(identity.viewerToken()))
        throw new WatchFailure(404, "PROFILE_NOT_FOUND");
      return stored.profile();
    }

    public Grant grant(String token, UUID device, boolean offline) {
      var grant = grants.get(token);
      if (grant == null) throw new WatchFailure(503, "OWNER_UNAVAILABLE");
      return grant;
    }

    public void catalog(Identity identity, UUID content, int maturity) {
      if (maturity < 12) throw new WatchFailure(403, "PARENTAL_CONTROL_DENIED");
    }
  }

  @TestConfiguration
  static class Ports {
    @Bean
    @Primary
    TestAccess testAccess() {
      return new TestAccess();
    }
  }
}
