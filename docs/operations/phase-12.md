# PHASE: 12 — Analytics / QoE / Experiments

## Implemented
Independent Insights service with authenticated bounded batches, owned profile/device/current Playback grant validation, default-off consent epochs/CAS, UUID/hash and semantic session/sequence deduplication, source provenance, daily quota, interval QoE cohorts, owner-event consumers, typed outbox and deterministic versioned optional experiments. Server consent boundaries suppress stale/pre-consent reports; opt-out deletes local detail/pending outbox/assignments. Bounded thirty-day cleanup is implemented.

## Architecture Decisions
[ADR024](../adr/ADR-024-consented-telemetry-and-experiments.md). PostgreSQL admission precedes unimplemented S3 archival because volume is unmeasured. Current Playback context is checked through narrowly scoped workload credentials. Playback never depends on Insights. Client business event types are rejected; purchases, payment failures and ad impressions come from actual Commerce/Engagement Kafka events. Client region/CDN/device hints are untrusted observations. Experiments cannot change authorization policy.

## Requirements Covered
FR032–034 local baseline. Play/pause/complete/buffer/quality/search/failure reports, owner purchase/failure/ad signals, startup p95, rebuffer ratio, bitrate/resolution/coarse dimensions and failure observations. Experiments and versioned optional feature enablement default to CONTROL when inactive, opted out, owner unavailable or key unavailable. Results are sparse/client-claimed and do not establish real-player/CDN SLOs or impact.

## APIs Added/Changed
See [Insights OpenAPI](../../contracts/openapi/insights.yaml): consent GET/PUT, telemetry batch POST, scoped QoE summary, scoped experiment create/config/list and owned deterministic assignment. Batch≤20; report time24h/future5s, interval played≤60s, buffer≤played, startup≤120s/start only. UTC-day account admission cap10,000. Summary window≤7d within retained30d.

## Events Added/Changed
New analytics.received.v1 account-keyed monotonic owner version; qoe.received.v1 session-keyed sample sequence. QoE dimensions/kind have additive defaults; ad.impression.v1 adds nullable/defaulted accountId. Old ad records without account attribution are excluded from consented analytics. Actual Registry BACKWARD_TRANSITIVE registration accepts previous/current schemas. Consumers validate framing/schema/producer/key/aggregate/version; replay receipts and owner semantic causes prevent inflation. Raw DLQs preserve poison/exhausted sources.

## Database Changes
New Insights FlywayV1: consent/account counter, detail/receipt/time indexes, quotas, semantic source cause, versioned experiments/assignments/audit and outbox. Account row locking serializes opt-out/admission and quota. No shared database reads/writes. Local secrets are independent32-byte telemetry/assignment keys and scoped workload credential; generated configuration remains ignored0600.

## Security
OIDC issuer/audience/subject validation, account/profile/device predicates, current grant binding, finite dimensions and safe errors/body bounds. analytics.read and insights.write are separate authorities. HMAC references minimize linkability but envelope/session UUIDs remain restricted pseudonymous metadata. No raw query, IP, token, recipient or payment-provider reference is recorded. Retained receipt fingerprints prevent replay. Published/in-flight stream data cannot be erased by SQL deletion; complete cloud/backups/Kafka erasure and key rotation require separately controlled procedures.

## Observability
Bounded source/outbox metrics, SQL cohort dimensions and request telemetry. Failure observation ratio is failed/(start+failure) client observations, not an independently measured attempt failure rate. REST owners have100ms connect/300ms read, bulkhead24, no retries. Retention deletes bounded1000-row batches; unpublished outbox is retained for recovery. No account/session metric labels.

## Verification
PASS:
- Full repository:159 tests, zero failures/errors/skips; compilation, Checkstyle, OpenAPI/Avro/Protobuf and eight-owner Journey. Evidence: /private/tmp/ott-phase12-full.log,3:44.
- Ten Insights real-PostgreSQL tests: default-off/pre-consent/opt-out/CAS, UUID/hash/sequence collisions, out-of-order intervals, invalid/server claims/time bounds, cohort math, quota and outbox rollback, owner semantic dedup, experiment safety/default/version/determinism, scoped HTTP, retention and concurrent duplicate/opt-out races.
- Eight-owner Journey: real Member ownership/active-device and Playback token context; consented QoE/analytics outbox→Registry/Kafka→typed decoding; actual owner subscription purchase/payment failure/ad impression ingestion; optional experiment assignment. Previous/current QoE/ad schemas register under actual BACKWARD_TRANSITIVE policy.
- Architecture checker:37FR/15NFR/9owners/21business topics/10mandatory sequences, no reported graph/link issues.
- Compose Insights profile config and Python scripts compile; actual local IdP workload audience/scopes verified with tokens withheld.
FAIL: none in final gate.
NOT RUN: cloud S3 analytics/archive pipeline; commercial player/CDN measurement; production throughput/SLO/experiment-impact tests; complete privacy erasure/key rotation; remote CI/cloud deployment.

## Remaining Work / Risks
Privacy replay barriers and controlled operations follow in Phase13. Infrastructure/monitoring/hardening/DR phases remain. Cohorts are consented/sparse, client claims may be dishonest or missing, and initial PostgreSQL performance is not production scale evidence. Commercial/downstream archival and backups require independent retention/erasure controls.

Phase Gate: PASS for the implemented local increment; continue Phase13.
