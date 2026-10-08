# ADR-028 — Local recovery evidence and regional boundaries

## Context
NFR durability/recovery and Phase16 require executed recovery evidence rather than untested RPO/RTO claims. This environment provides Docker/Testcontainers and synthetic business journeys, but no billed AWS account/regions, commercial DRM/KMS or client traffic cutover.

## Decision
Add an opt-in recovery Maven profile to the complete nine-owner journey. Quiesce every application writer, snapshot each independent owner database, restore to separate owner databases on a new isolated PostgreSQL host and compare every table's row-count/content fingerprint, migrations, structural index metadata and predicate membership against every restored table. Retain source/restored index SQL for review: PostgreSQL may rewrite equivalent cast expressions during dump/restore. Preserve audit triggers. Archive actual Kafka partition/key/value records plus all Registry schema versions/IDs, import into a fresh broker/Registry, verify typed decoding and replay actual catalog consumers against restored Content ownership. Delete/rebuild the search index using the real checkpointed projection/indexer, restore every origin object into a separate S3 emulator with byte checksums/metadata, and lose the Redis cache while preserving PostgreSQL playback ledgers. Existing Redis-loss/no-overbooking tests remain part of the gate.

## Alternatives
Fake JSON success or restoring only a canary table gives little evidence. Applying unconfigured AWS Terraform cannot establish regional behavior safely. A quiesced cross-owner fixture is reproducible but is not a live globally consistent backup/2PC protocol; independent cloud PITR plus outbox/inbox reconciliation must be separately exercised.

## Rationale
Synthetic invoices, entitlements, sessions, private media, indexes, receipts and immutable audit are available from the actual tested journey. Restoring all owner tables and real event wire IDs validates practical backup procedures without risking real data or pretending local timing proves regional objectives.

## Consequences
Recovery is an explicit additional profile because it duplicates infrastructure and increases test time. Raw synthetic archives are ignored test outputs with restricted permissions. Local topic replication is1; it cannot validate target MSK replication3, MirrorMaker offsets or broker AZ loss. Regional failover/failback, production PITR and persistent Temporal workflow-history restore remain NOT RUN.

## Operational Impact
Run `./scripts/mvn.sh -Pintegration,recovery verify` with working Docker. Preserve sanctioned encrypted production backups and the same event UUIDs/schema IDs/partition boundaries; duplicate handling must remain active. Never replay real payment charges/notifications or issue old grants during recovery. Quarantine unresolved external outcomes, rotate/revalidate region keys, fence old writers and rebuild disposable projections before admitting traffic. Report local durations as measured drill timings only; use the existing domain RPO/RTO table as unvalidated targets.
