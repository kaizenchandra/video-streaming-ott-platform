# Multi-region evolution and disaster recovery

Start single region/Multi-AZ. Standby secondary region uses asynchronous encrypted replication and independently configured IdP/DRM/CDN integrations. No active-active commerce/session writers. Failover uses explicit regional epoch fencing, blocks old issuance and waits max grant lifetime before new session grants; DNS failover alone cannot establish correctness.

Targets below proposed and NOT VALIDATED. Actual RPO/RTO measured by drills; backup existence is insufficient.

| Domain | RPO | RTO | Backup / replication / restore |
|---|---|---|---|
| Commerce/payment/entitlement | ≤5min regional, zero committed-AZ target | ≤60min | PITR/WAL+encrypted cross-region snapshots; reconcile provider ledger before reopening purchase; retain idempotency/inbox |
| Member/catalog/rights | ≤15min | ≤60min | PG PITR/cross-region snapshots; rights grants conservative until verified; IdP backup/issuer continuity |
| Playback leases/grants | ledger≤5min; ephemeral leases discard | ≤15min after authority ready | restore ledger; revoke old issuer epoch; quarantine60s, rebuild; no old token grants |
| Watch state | ≤1h | ≤4h | PG snapshot/WAL; replay events with epoch guard |
| Search/recommendations | ≤24h derived, catalog authoritative | ≤8h | rebuild from owner snapshot+watermarked Kafka; no dependency for playback |
| Media/S3 | new sources≤15min replication target | ≤2h origin switching | S3 versioning/replication; manifest checksums; origin/CDN route update; unfinished job resume |
| Kafka/outbox | DB outbox RPO; broker records≤15min target | ≤2h | owner outbox retained7d; mirror topics if justified; inbox/replay dedupe and side-effect suppression |
| Engagement/insights/audit | audit≤15min; telemetry≤24h | audit≤2h; analytics≤24h | encrypted owner snapshots+S3 archives; prioritize audit over recommendation signals |

Runbook: declare incident→fence writes/issuers→restore most recent verified backup→validate migrations/data/inbox→reconcile payments/rights→restore IdP/network/secrets→rebuild leases/projections→synthetic E2E→gradual routing→measure lag and actual RPO/RTO. Failback requires replication catch-up, same fence sequence and new epoch; never merge conflicting financial writers. Drill separately PG point-in-time restore, Kafka replay, Redis loss, OpenSearch rebuild, origin failover and whole regional switch. Record timestamps/checksums/business comparisons. No regional cloud resources available in current environment, so all regional claims remain design targets.


## Executed local recovery scope

[ADR028](../adr/ADR-028-local-recovery-evidence.md) and the [recovery runbook](../operations/recovery-runbook.md) document the opt-in `integration,recovery` journey extension. It quiesces synthetic writers, restores all owner databases, verifies durable playback state after cache loss, restores origin bytes/metadata into a new emulator, imports actual event/schema archives into a fresh broker/Registry and rebuilds deleted OpenSearch through restored Content authority. These are local process/data recovery checks. They do not validate the targets above, regional epochs/traffic cutover, production PITR/ACLs, managed broker replication, cloud KMS or Temporal persistent-history restore.
