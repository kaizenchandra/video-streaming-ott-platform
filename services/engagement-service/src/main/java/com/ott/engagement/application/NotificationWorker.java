package com.ott.engagement.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
    name = "ott.notifications.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class NotificationWorker {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final NotificationService notices;
  private final MessageDelivery provider;
  private final Clock clock;
  private final MeterRegistry metrics;

  public NotificationWorker(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      NotificationService notices,
      MessageDelivery provider,
      Clock clock,
      MeterRegistry metrics) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.notices = notices;
    this.provider = provider;
    this.clock = clock;
    this.metrics = metrics;
  }

  private record Job(
      UUID id,
      UUID account,
      String template,
      UUID target,
      String channel,
      String state,
      Instant expires,
      int attempts) {}

  private record Dispatch(Job job, String recipient) {}

  @Scheduled(fixedDelayString = "${ott.notifications.delay-ms:1000}")
  public void batch() {
    notices.due();
    UUID claim = UUID.randomUUID();
    var jobs =
        tx.execute(
            t ->
                db.query(
                    "UPDATE notification_intent SET claim_token=?,claimed_until=? WHERE id"
                        + " IN(SELECT id FROM notification_intent WHERE state"
                        + " IN('PENDING','DISPATCHING') AND next_attempt_at<=? AND (claimed_until"
                        + " IS NULL OR claimed_until<=?) ORDER BY next_attempt_at,id FOR UPDATE"
                        + " SKIP LOCKED LIMIT 10) RETURNING *",
                    this::job,
                    claim,
                    stamp(clock.instant().plusSeconds(10)),
                    stamp(clock.instant()),
                    stamp(clock.instant())));
    for (var job : jobs) {
      Dispatch dispatch;
      try {
        dispatch =
            tx.execute(
                t -> {
                  notices.lock(job.account());
                  var current =
                      db.queryForObject(
                          "SELECT * FROM notification_intent WHERE id=? FOR UPDATE",
                          this::job,
                          job.id());
                  if (!owned(current.id(), claim)) return null;
                  if (current.state().equals("DISPATCHING")
                      && !current.channel().equals("IN_APP")
                      && !provider.idempotent()) {
                    state(current, claim, "RECONCILE", null);
                    return null;
                  }
                  if (!current.expires().isAfter(clock.instant())
                      || !notices.permitted(current.account(), current.channel())
                      || !liveAllowed(current)) {
                    state(current, claim, "SKIPPED", null);
                    return null;
                  }
                  if (current.channel().equals("IN_APP")) {
                    db.update(
                        "INSERT INTO"
                            + " notification_inbox(intent_id,account_id,template,target_id,created_at)"
                            + " VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                        current.id(),
                        current.account(),
                        current.template(),
                        current.target(),
                        stamp(clock.instant()));
                    state(current, claim, "DELIVERED", null);
                    delivered(current);
                    return null;
                  }
                  String recipient = notices.recipient(current.account(), current.channel());
                  db.update(
                      "UPDATE notification_intent SET state='DISPATCHING',attempts=attempts+1 WHERE"
                          + " id=? AND claim_token=?",
                      current.id(),
                      claim);
                  return new Dispatch(current, recipient);
                });
      } catch (EngagementFailure failure) {
        if (!failure.code().equals("CONTACT_VAULT_UNAVAILABLE")) throw failure;
        tx.executeWithoutResult(
            t -> {
              db.update(
                  "UPDATE notification_intent SET"
                      + " state='FAILED',claim_token=NULL,claimed_until=NULL WHERE id=? AND"
                      + " claim_token=? AND state='PENDING'",
                  job.id(),
                  claim);
            });
        metrics.counter("ott.notification.contact.failure").increment();
        continue;
      }
      if (dispatch == null) continue;
      var result =
          provider.send(
              dispatch.job().id(),
              dispatch.job().channel(),
              dispatch.recipient(),
              dispatch.job().template(),
              dispatch.job().target());
      tx.executeWithoutResult(
          t -> {
            var current =
                db.queryForObject(
                    "SELECT * FROM notification_intent WHERE id=? FOR UPDATE", this::job, job.id());
            if (!owned(current.id(), claim)) return;
            if (result.outcome() == MessageDelivery.Outcome.ACCEPTED) {
              state(current, claim, "DELIVERED", result.receipt());
              delivered(current);
            } else if (result.outcome() == MessageDelivery.Outcome.REJECTED)
              state(current, claim, "FAILED", null);
            else if (!provider.idempotent()) state(current, claim, "RECONCILE", null);
            else if (current.attempts() >= 5) state(current, claim, "FAILED", null);
            else {
              long delay =
                  Math.min(300, 1L << current.attempts())
                      + java.util.concurrent.ThreadLocalRandom.current().nextLong(3);
              db.update(
                  "UPDATE notification_intent SET"
                      + " state='PENDING',claim_token=NULL,claimed_until=NULL,next_attempt_at=?"
                      + " WHERE id=? AND claim_token=?",
                  stamp(clock.instant().plusSeconds(delay)),
                  current.id(),
                  claim);
              metrics.counter("ott.notification.retry").increment();
            }
          });
    }
  }

  private boolean liveAllowed(Job job) {
    if (!job.template().equals("LIVE_STARTED")) return true;
    var states =
        db.queryForList(
            "SELECT state FROM live_notice_state WHERE event_id=?", String.class, job.target());
    return !states.isEmpty() && states.getFirst().equals("LIVE");
  }

  private boolean owned(UUID id, UUID claim) {
    return db.queryForObject(
            "SELECT count(*) FROM notification_intent WHERE id=? AND claim_token=? AND state"
                + " IN('PENDING','DISPATCHING')",
            Integer.class,
            id,
            claim)
        == 1;
  }

  private void state(Job job, UUID claim, String state, String receipt) {
    db.update(
        "UPDATE notification_intent SET"
            + " state=?,provider_receipt=?,claim_token=NULL,claimed_until=NULL WHERE id=? AND"
            + " claim_token=?",
        state,
        receipt,
        job.id(),
        claim);
    metrics.counter("ott.notification.outcome", "state", state).increment();
  }

  private void delivered(Job job) {
    notices.outbox(
        job.id(),
        2,
        "notification.delivered.v1",
        Map.of(
            "accountId",
            job.account().toString(),
            "intentId",
            job.id().toString(),
            "channel",
            job.channel()));
  }

  private Job job(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Job(
        r.getObject("id", UUID.class),
        r.getObject("account_id", UUID.class),
        r.getString("template"),
        r.getObject("target_id", UUID.class),
        r.getString("channel"),
        r.getString("state"),
        r.getTimestamp("expires_at").toInstant(),
        r.getInt("attempts"));
  }

  private static java.sql.Timestamp stamp(Instant at) {
    return java.sql.Timestamp.from(at);
  }
}
