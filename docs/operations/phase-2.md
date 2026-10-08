# PHASE: 2 — Catalog / Metadata / Rights

Status: PASS catalog/rights engineering increment, local gate. Extended audio/subtitle media metadata belongs to phase5; live fixture/timeline metadata to phase9.

## Implemented

Typed movie/series/season/episode/sport hierarchy, localization fallback, draft→review→published workflow, new draft revisions that preserve existing public immutable snapshots, bounded keyset browse/get, microsecond-precision territorial `[start,end)` rights/offline/age policy and idempotent revocation. Publication requires validated asset readiness. Actual registered media-ready event consumer uses inbox dedupe, retries with jitter and DLQ. Catalog publication/rights changes produce typed registered Avro through owner-local outbox/ack/expiring claims.

## Architecture Decisions

ADR-001/004/005/006/007/010/011 followed. No media bytes/API proxy, no cross-owner SQL. Published metadata is an immutable JSONB snapshot in an owner table, not a mutable catalog alias; rights policy version separate from published metadata version. Producers serialize raw packaged writer schemas; generated Java annotations cannot change schema identity. PostgreSQL time precision is explicitly enforced. Test topic auto-creation disabled to match production bootstrap assumptions.

## Requirements Covered

FR-004 hierarchy/basic metadata, FR-005 localization/immutable version subset (audio/subtitle track manifests remain phase5), FR-006 territorial/time-window/age/offline rights, FR-007 publication/review/outbox. Sports schedule/commentary/DVR are separately FR-025/026 phase9. No future feature silently treated as implemented.

## APIs Added/Changed

Authenticated `GET /v1/catalog`, `GET /v1/catalog/{id}`; catalog.write-scoped `POST /v1/admin/catalog`, localization update, revision/review, rights grant/revoke; publish additionally requires catalog.publish. Workload `POST /internal/v1/rights/check` requires content.internal. content.yaml documents DTOs, cursor limits, precision, state and scope semantics.

## Events Added/Changed

Actual producers `catalog.published.v1`, `rights.changed.v1`; actual consumer `media.ready.v1`. Poison messages route to `media.ready.v1.dlq`; duplicate ready events do not duplicate inbox/assets. The media producer used in integration is a synthetic test fixture; media service itself is phase5 work.

## Database Changes

Owned content DB V1 catalog/localization/rights/assets/immutable published snapshots/inbox/outbox+indexes/checks/FKs; V2 outbox claims/retry. No shared credentials/schema. Draft edits cannot mutate published snapshots.

## Security

Viewer cannot see drafts or write; editor cannot publish without publish scope; workload rights route unavailable to viewers. Trusted media origin prefix/checksum validated; malformed framing/schema/type/producer rejected. Internal maturity/geo values must be supplied by trusted playback owner, not public clients. Metadata visibility alone never authorizes media playback.

## Observability

Publication/outbox success/failure counters; inherited RED/JVM/structured logs, database readiness, protected Prometheus. More dashboards/alerts/latency measurements remain production-hardening work.

## Verification

PASS:
- Full reactor Java21 compile, Checkstyle, contracts and unit suites:5 tests; member integration regression23; content integration11; zero failures/errors/skips.
- Real PG hierarchy/localization/version conflicts, draft invisibility, unready publication rejection, immutable revisions, parallel publish commits1 snapshot/event.
- Territorial/age/offline allow/deny; inclusive start, exclusive end; sub-microsecond evaluation rounding regression fixed; rights revoke idempotence/version independence.
- Browse cursor/limit and writer/viewer/publisher/workload API authorization.
- Real Kafka+SchemaRegistry media-ready→inbox+asset→publish/rights outbox→registered Avro; duplicate ready record and malformed record→DLQ.

FAIL:
- None outstanding. Timestamp rounding, generated schema identity, test topic auto-create conflict discovered and corrected before gate passed.

NOT RUN:
- Full media transcode/publication E2E (phase5), search index consumer (phase7), end-user PKCE, deployed cloud/latency/load/DR, commercial rights feed integration.

## Remaining Work

Dependency-ready next: Phase3 commerce. Track/audio/subtitle metadata and live-specific sports metadata deferred to designated phases, not omitted. Packaging checks beyond prefix/checksum require actual media worker QC.

## Risks / Blockers

Production Kafka ACL/TLS and reviewer role assignments require infrastructure/IdP configuration. Country and profile maturity on internal route are trusted only under workload/gateway design. Raw-schema lookup fix also applied to member and verified in the full regression. No production-readiness claim.
