package com.ott.experience.application;

import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WatchRetention {
  private final JdbcTemplate db;
  private final Clock clock;
  private final TransactionTemplate tx;

  public WatchRetention(JdbcTemplate db, Clock clock, PlatformTransactionManager manager) {
    this.db = db;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
  }

  @Scheduled(
      fixedDelayString = "${ott.watch.retention-delay-ms:3600000}",
      initialDelayString = "${ott.watch.retention-initial-delay-ms:180000}")
  public void sweep() {
    var cutoff = java.sql.Timestamp.from(clock.instant().minusSeconds(180L * 86400));
    tx.executeWithoutResult(
        s -> {
          db.update(
              "DELETE FROM watch_history WHERE session_id IN(SELECT session_id FROM watch_history"
                  + " WHERE last_at<? ORDER BY last_at,session_id LIMIT 1000 FOR UPDATE SKIP"
                  + " LOCKED)",
              cutoff);
          db.update(
              "DELETE FROM watch_progress WHERE id IN(SELECT id FROM watch_progress WHERE"
                  + " updated_at<? ORDER BY updated_at,id LIMIT 1000 FOR UPDATE SKIP LOCKED)",
              cutoff);
          db.update(
              "DELETE FROM progress_inbox WHERE event_id IN(SELECT event_id FROM progress_inbox"
                  + " WHERE received_at<? ORDER BY received_at,event_id LIMIT 1000 FOR UPDATE SKIP"
                  + " LOCKED)",
              cutoff);
          db.update(
              "DELETE FROM session_sequence WHERE session_id IN(SELECT session_id FROM"
                  + " session_sequence WHERE created_at<? ORDER BY created_at,session_id LIMIT"
                  + " 1000 FOR UPDATE SKIP LOCKED)",
              cutoff);
          db.update(
              "DELETE FROM outbox_event WHERE event_id IN(SELECT event_id FROM outbox_event WHERE"
                  + " published_at<? ORDER BY published_at,event_id LIMIT 1000 FOR UPDATE SKIP"
                  + " LOCKED)",
              cutoff);
        });
  }
}
