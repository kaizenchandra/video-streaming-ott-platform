package com.ott.experience.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecommendationSignals {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ViewerAccess viewers;
  private final Clock clock;
  private final MeterRegistry metrics;
  private final int limit;

  public RecommendationSignals(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      ViewerAccess viewers,
      Clock clock,
      MeterRegistry metrics,
      @Value("${ott.recommendations.daily-profile-cap:200}") int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid daily signal cap");
    this.db = db;
    tx = new TransactionTemplate(manager);
    this.viewers = viewers;
    this.clock = clock;
    this.metrics = metrics;
    this.limit = limit;
  }

  public record Preference(boolean enabled, long version, Instant enabledSince) {}

  public Preference preference(ViewerAccess.Identity identity, UUID profile) {
    viewers.profile(identity, profile);
    return stored(profile);
  }

  public Preference preference(ViewerAccess.Identity identity, UUID profile, boolean enabled) {
    var owner = viewers.profile(identity, profile);
    return tx.execute(
        t -> {
          db.update(
              "INSERT INTO profile_owner VALUES(?,?) ON CONFLICT DO NOTHING",
              profile,
              owner.accountId());
          UUID known =
              db.queryForObject(
                  "SELECT account_id FROM profile_owner WHERE profile_id=?", UUID.class, profile);
          if (!known.equals(owner.accountId())) throw new WatchFailure(403, "PROFILE_NOT_ALLOWED");
          db.update(
              "INSERT INTO personalization(profile_id) VALUES(?) ON CONFLICT DO NOTHING", profile);
          db.queryForObject(
              "SELECT profile_id FROM personalization WHERE profile_id=? FOR UPDATE",
              UUID.class,
              profile);
          var previous = stored(profile);
          if (previous.enabled() != enabled) {
            db.update(
                "UPDATE personalization SET enabled=?,enabled_since=?,version=version+1 WHERE"
                    + " profile_id=?",
                enabled,
                enabled ? stamp(clock.instant().truncatedTo(ChronoUnit.MILLIS)) : null,
                profile);
            if (!enabled) db.update("DELETE FROM daily_view WHERE profile_id=?", profile);
            metrics
                .counter("ott.recommendation.consent", "enabled", Boolean.toString(enabled))
                .increment();
          }
          return stored(profile);
        });
  }

  public boolean enabled(UUID profile) {
    return stored(profile).enabled();
  }

  public boolean start(
      UUID event, UUID account, UUID profile, UUID session, UUID content, UUID device, Instant at) {
    if (event == null
        || account == null
        || profile == null
        || session == null
        || content == null
        || device == null
        || at == null
        || at.isAfter(clock.instant().plusSeconds(300)))
      throw new IllegalArgumentException("Invalid playback signal");
    String fingerprint =
        hash(
            account
                + ":"
                + profile
                + ":"
                + session
                + ":"
                + content
                + ":"
                + device
                + ":"
                + at.toEpochMilli());
    return tx.execute(
        t -> {
          int inserted =
              db.update(
                  "INSERT INTO recommendation_inbox(event_id,fingerprint) VALUES(?,?) ON CONFLICT"
                      + " DO NOTHING",
                  event,
                  fingerprint);
          if (inserted == 0) {
            String previous =
                db.queryForObject(
                    "SELECT fingerprint FROM recommendation_inbox WHERE event_id=?",
                    String.class,
                    event);
            if (!previous.equals(fingerprint))
              throw new IllegalArgumentException("Conflicting playback event");
            metrics.counter("ott.recommendation.signal.duplicate").increment();
            return false;
          }
          var known =
              db.queryForList(
                  "SELECT p.profile_id,o.account_id FROM personalization p JOIN profile_owner o"
                      + " USING(profile_id) WHERE p.profile_id=? FOR UPDATE OF p",
                  profile);
          if (known.isEmpty()) return ignored();
          if (!account.equals(known.getFirst().get("account_id")))
            throw new IllegalArgumentException("Foreign profile signal");
          var preference = stored(profile);
          if (!preference.enabled()
              || at.isBefore(preference.enabledSince())
              || at.isBefore(clock.instant().minus(90, ChronoUnit.DAYS))) return ignored();
          var day = java.sql.Date.valueOf(at.atZone(ZoneOffset.UTC).toLocalDate());
          int count =
              db.queryForObject(
                  "SELECT count(*) FROM daily_view WHERE profile_id=? AND day=?",
                  Integer.class,
                  profile,
                  day);
          int same =
              db.queryForObject(
                  "SELECT count(*) FROM daily_view WHERE profile_id=? AND content_id=? AND day=?",
                  Integer.class,
                  profile,
                  content,
                  day);
          if (count >= limit && same == 0) return ignored();
          db.update(
              "INSERT INTO daily_view(profile_id,content_id,day,last_at) VALUES(?,?,?,?) ON"
                  + " CONFLICT(profile_id,content_id,day) DO UPDATE SET"
                  + " last_at=GREATEST(daily_view.last_at,EXCLUDED.last_at)",
              profile,
              content,
              day,
              stamp(at));
          metrics.counter("ott.recommendation.signal.accepted").increment();
          return true;
        });
  }

  private boolean ignored() {
    metrics.counter("ott.recommendation.signal.ignored").increment();
    return false;
  }

  private Preference stored(UUID profile) {
    var rows =
        db.query(
            "SELECT enabled,version,enabled_since FROM personalization WHERE profile_id=?",
            (r, n) ->
                new Preference(
                    r.getBoolean(1),
                    r.getLong(2),
                    r.getTimestamp(3) == null ? null : r.getTimestamp(3).toInstant()),
            profile);
    return rows.isEmpty() ? new Preference(false, 0, null) : rows.getFirst();
  }

  @Scheduled(fixedDelayString = "${ott.recommendations.retention-delay-ms:3600000}")
  public void retain() {
    var cutoff = stamp(clock.instant().minus(90, ChronoUnit.DAYS));
    db.update(
        "DELETE FROM daily_view WHERE (profile_id,content_id,day) IN(SELECT"
            + " profile_id,content_id,day FROM daily_view WHERE last_at<? ORDER BY last_at LIMIT"
            + " 1000)",
        cutoff);
    db.update(
        "DELETE FROM recommendation_inbox WHERE event_id IN(SELECT event_id FROM"
            + " recommendation_inbox WHERE received_at<? ORDER BY received_at LIMIT 1000)",
        cutoff);
  }

  private static java.sql.Timestamp stamp(Instant at) {
    return java.sql.Timestamp.from(at);
  }

  private static String hash(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
