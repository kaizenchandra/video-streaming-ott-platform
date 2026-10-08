# PHASE: 16 — Executed Local Recovery / Regional Boundaries

## Implemented
Opt-in recovery extension to the real nine-owner business journey; archives and independent restores for all owner PostgreSQL stores, media origin objects and Kafka/Registry state, disposable Redis loss with durable ledger checks, actual consumer duplicate replay and checkpointed OpenSearch rebuild. Archives come from the real integrated synthetic business state, not a placeholder/canary table. [Recovery runbook](recovery-runbook.md) explains procedure and limits.

## Architecture Decisions
[ADR028](../adr/ADR-028-local-recovery-evidence.md): quiesced local snapshots, exact event IDs/schema wire IDs/partition boundaries, authoritative owner restore before projection rebuild. No live cross-owner transaction or regional traffic/fencing correctness claim. Source/restored index definitions retained; compare structural metadata and predicate membership because PostgreSQL rewrites equivalent casts during dump/restore. Production target RPO/RTO remains distinct from local elapsed times.

## Requirements Covered
FR013 durable playback/session state, FR019 private origin, FR022 search synchronization and FR037 recovery/platform; NFR004/005 durability/idempotency/replay, NFR008 DR evolution, NFR009 cache/search, NFR012/013 deploy/test evidence. Full regional recovery objectives remain open.

## APIs Added/Changed
No business API change. Restore runs existing current Content snapshot API and Experience projection/indexer. The Java worker's private health surface now also has an image-level readiness healthcheck.

## Events Added/Changed
No schema/event change. Actual archived records are restored to fresh topics with the same partitions, keys and Avro bytes; fresh Registry imports preserve IDs/versions and BACKWARD_TRANSITIVE compatibility. Every restored broker record is read back and compared; the actual consumer safely tolerates duplicate archived catalog events.

## Database Changes
No new migration/business schema. All nine owned databases restore from actual `pg_dump` archive files into separate databases on an isolated new PostgreSQL host. Compare every table's content/count fingerprint including Flyway/inbox/outbox/financial/session/audit state, index metadata/predicate membership and immutable-audit trigger behavior. The test deliberately omits ACLs; production role/grant restore remains separate.

## Security
Only synthetic fixture data. Restricted ignored archive directory0700, SQL/media/event/schema files0600; no real credentials/keys or provider messages. Bounded HTTP/S3 calls, explicit local clear protection, fail-closed production packaging. Production WAF ARN/release/namespace guards exercise negative cases. Raw scan records remain visible; the expiring Spring applicability review and native-parser coverage limits remain release gates.

## Observability
Ignored recovery summary contains backup SHA256, actual local durations and object/event/document/table counts plus explicit NOT RUN fields. These measurements are not regional RPO/RTO. Actual OTLP authenticated-request privacy and parallel owner trace propagation are included in the final gate.

## Verification
PASS:
- Final complete `-Pintegration,recovery,sbom verify`:179 tests across32 classes, zero failures/errors/skips; all12 reactor projects succeeded, with Checkstyle, real owner/dependency failure integrations, contract/schema gates, native nine-owner journey, actual OTLP privacy/parallel propagation and independent recovery. Total9m55s on the shared local host.
- All nine owner images and native worker rebuilt from isolated contexts; final worker Docker readiness healthcheck became healthy before actual Temporal outage (readiness503/liveness200).
- Final source secret scan:zero findings excluding private ignored local data/build outputs. Sanitized machine-readable results are in [executed evidence](../testing/executed-evidence.json).
- Final aggregate SBOM and all ten runtime image vulnerability scans completed:one visible Spring MVC package finding, zero additional high/critical; generic images32 findings (critical1/medium15/low16), native worker157 (critical1/medium138/low18). Infrastructure49 findings (zero high/critical); worker Dockerfile healthcheck finding cleared. Native parser coverage remains unvalidated.
- Focused complete journey + recovery: all nine owned stores,92 tables including histories/receipts,64 synthetic media objects/995516 bytes restored with checksums/content metadata,40 archived records across17 business topics and20 Registry versions imported/read back; deleted index rebuilt to one current localized document, duplicate catalog replay unchanged. Focused local recovery27.62s; final combined run under concurrent build/scan load69.16s; fixture quiesced before backup, no cloud RPO/RTO inference.
- Native patched worker restricted headless startup, real PG/Temporal dependencies/FFmpeg, management-only surface and Temporal outage behavior.
- Ordinary preserved Compose/runtime:all nine localhost APIs READY, correctly labeled native worker Docker healthy, scoped Keycloak clients/topics/schemas provisioned, actual authenticated synthetic upload→Temporal/native FFmpeg→S3→Kafka→catalog publication PASS after clock repair. Launcher selected-owner graceful restart/occupied-port refusal preserves eight other owners. Current run instructions in [local runtime](local-runtime.md).
- Architecture37FR/15NFR/nine owners/21 topics/ten sequences, REST security boundary, Helm lint/ten-workload/nine-migration/HPA/84-route semantic guards, Python compilation and whitespace check.
FAIL: no unresolved failures in the focused recovery increment. Initial exact partial-index SQL comparison rejected PostgreSQL's equivalent cast normalization; replaced with structure/predicate membership plus SQL evidence. Initial default recovery HTTP/2 upgrade was rejected by Registry; use established bounded HTTP/1 client. Initial worker drill used an incorrect mock protection value; corrected to documented `mock-clear`, production guard preserved. Ordinary Compose startup exposed worker image platform metadata incorrectly labeledarm64 despite nativeamd64 binaries; corrected explicit build target, tested image architecture/Docker health and added amd64 node selection. Host/VM time drift caused legitimate timestamp denial in ordinary OIDC smoke; safe non-reset VM restart repaired clocks without relaxing validation.
NOT RUN: AWS apply/regional failover/failback, Multi-AZ failover, production PITR/role restore, MSK replication/offset migration, production KMS/IdP/Temporal persistent-history restore, CDN/client traffic cutover. No billed account/isolated regions/provider credentials/cost policy exist in this environment.

## Remaining Work / Risks
Production requires configured commercial providers, cloud telemetry/logging, effective network/IRSA/Kafka/Registry/secret enforcement, framework applicability resolution and native FFmpeg review, complete privacy lifecycle, sustained traffic/SLO testing and independently exercised regional fencing/reconciliation/failback. Restoring bytes or rendering IaC does not demonstrate those behaviors.

Phase Gate: PASS for the local recovery increment. Full regional/cloud DR validation remains NOT RUN; no DR-success or production-readiness claim.
