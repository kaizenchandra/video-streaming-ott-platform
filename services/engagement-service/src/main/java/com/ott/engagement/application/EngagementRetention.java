package com.ott.engagement.application;

import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EngagementRetention {
  private final JdbcTemplate db;
  private final Clock clock;
  private final TransactionTemplate tx;

  public EngagementRetention(JdbcTemplate db, Clock clock, PlatformTransactionManager manager) {
    this.db = db;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
  }

  @Scheduled(
      fixedDelayString = "${ott.engagement.retention-delay-ms:3600000}",
      initialDelayString = "${ott.engagement.retention-initial-delay-ms:180000}")
  public void sweep() {
    var cutoff = java.sql.Timestamp.from(clock.instant().minusSeconds(30L * 86400));
    tx.executeWithoutResult(
        s -> {
          var notices =
              db.queryForList(
                  "SELECT id FROM notification_intent WHERE created_at<? AND state"
                      + " IN('DELIVERED','FAILED','SKIPPED') ORDER BY created_at,id FOR UPDATE SKIP"
                      + " LOCKED LIMIT 1000",
                  java.util.UUID.class,
                  cutoff);
          for (var id : notices) {
            db.update("DELETE FROM notification_inbox WHERE intent_id=?", id);
            db.update("DELETE FROM notification_intent WHERE id=?", id);
          }
          db.update(
              "DELETE FROM reminder WHERE id IN(SELECT id FROM reminder WHERE expires_at<? AND"
                  + " state IN('REQUESTED','EXPIRED','CANCELLED') AND NOT EXISTS(SELECT 1 FROM"
                  + " notification_intent n WHERE n.cause_id=reminder.id AND n.state"
                  + " IN('DISPATCHING','RECONCILE')) ORDER BY expires_at,id LIMIT 1000 FOR UPDATE"
                  + " SKIP LOCKED)",
              cutoff);
          db.update(
              "DELETE FROM notification_source WHERE event_id IN(SELECT event_id FROM"
                  + " notification_source WHERE received_at<? ORDER BY received_at,event_id LIMIT"
                  + " 1000 FOR UPDATE SKIP LOCKED)",
              cutoff);
          var decisions =
              db.queryForList(
                  "SELECT id FROM ad_decision WHERE created_at<? AND expires_at<? AND state"
                      + " IN('ACTIVE','SKIPPED') ORDER BY created_at,id FOR UPDATE SKIP LOCKED"
                      + " LIMIT 1000",
                  java.util.UUID.class,
                  cutoff,
                  cutoff);
          for (var id : decisions) {
            db.update("DELETE FROM ad_event WHERE decision_id=?", id);
            db.update("DELETE FROM ad_decision WHERE id=?", id);
          }
          db.update(
              "DELETE FROM ad_receipt WHERE event_id IN(SELECT event_id FROM ad_receipt WHERE"
                  + " received_at<? ORDER BY received_at,event_id LIMIT 1000 FOR UPDATE SKIP"
                  + " LOCKED)",
              cutoff);
          db.update(
              "DELETE FROM outbox_event WHERE event_id IN(SELECT event_id FROM outbox_event WHERE"
                  + " published_at<? ORDER BY published_at,event_id LIMIT 1000 FOR UPDATE SKIP"
                  + " LOCKED)",
              cutoff);
        });
  }
}
