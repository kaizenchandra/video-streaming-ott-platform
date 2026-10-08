# PHASE: 8 — Deterministic recommendation baseline

## Implemented
Recent releases, contextual genre/actor rails, opt-in popular/trending starts, and consented because-you-watched rails. Published catalog metadata is durable and current rights/maturity filter every seed/result. Personalization defaults off; enabling records a server timestamp, opt-out atomically deletes derived contributions. Typed playback starts feed a deduplicated inbox/daily projection. Contributions cap one profile/content/UTC day and 200 distinct contents/profile/day by default, serialized on the preference row. Source time rejects pre-consent imports and bounds future/90-day events. Bounded retention deletes old contributions/event hashes. No ML infrastructure introduced.

## Architecture Decisions
[ADR-020](../adr/ADR-020-recommendation-signals.md) extends ADR-015. Rankings and related metadata use bounded owner-local PostgreSQL queries and taxonomy GIN indexes. OpenSearch outage does not block these rails; current Content policy failure returns unavailable. Responses identify opt-in-start ranking versus recent fallback. personalizationEnabled is consent state; personalized is true only for a nonempty history-based rail actually applied.

## Requirements Covered
FR-024 baseline; privacy, duplicate/replay, local transaction, stable ordering and failure-isolation NFRs. Sparse opted-in cohorts are explicit; no total-audience, fraud-proof viewing, recommendation quality/lift or scale claim.

## APIs Added/Changed
Profile personalization GET/PUT and recommendations GET with optional permitted seed. See [Experience contract](../../contracts/openapi/experience.yaml). Core resume remains independent of optional recommendation consent.

## Events Added/Changed
Existing playback.started.v1 consumer validates producer/type/schema/session key/aggregate/version and profile/account binding. Duplicate UUID/hash and per-day caps protect replay/contribution correctness. playback.started.v1.dlq parks invalid/exhausted transient records. No new business schema/topic or breaking producer change.

## Database Changes
Experience V3 adds personalization, recommendation inbox, daily counters/date indexes and catalog taxonomy GIN indexes. No applied migration rewritten. Signal inbox/counter commit together; consent row locking serializes opt-out with ingestion.

## Security
Owned profile preference and recommendation requests; default opt-out and enabled-since boundary; cross-account signals rejected. Current rights/maturity on contextual/history seeds and all candidates. No payment/DRM credentials, access tokens or raw signal payloads logged. Pseudonymous derived identifiers are still privacy-sensitive. Production broker ACL/TLS deployment and full privacy erasure are later-phase work.

## Observability
Consent, accepted/duplicate/ignored signal counters plus existing HTTP/JVM/tracing foundation. Daily counts are heuristic contributions, not billable-viewing evidence. Retention runs hourly in batches of at most 1000 rows; monitor backlog for capacity planning.

## Verification
PASS:
- RecommendationIT (7), real PostgreSQL: default/enable/idempotent consent, opt-out deletion/ownership, duplicate/collision/daily cap, parallel quota race, pre-consent/future/expired events, retention, wrong-account/DB rollback, stable taxonomy/history/current-policy rails and opt-in popular/trending ranking with no index available.
- CatalogIndexEventsIT (2), real Kafka/Registry/PostgreSQL/OpenSearch: existing publication/poison handling plus actual duplicate playback signal delivery yielding one daily contribution.
- Six-owner JourneyIT (1): real owner HTTP/media/search/payment/playback/progress; actual consent before playback, Playback outbox/Kafka → Experience contribution, recommendations returned under current rights.
- Affected reactor compilation, Checkstyle, all contract parsing/generation and unit tests. Evidence: /private/tmp/recommendation-final-focused.log. Prior complete Phase 7 regression remains recorded in /private/tmp/ott-stage7-full.log (106 tests).

FAIL:
- None remaining. First extended journey lacked its new playback topics; test bootstrap and bounded producer configuration corrected.

NOT RUN:
- One full repository integration run after Phase 8; focused affected checks and integrated journey passed.
- Recommendation relevance/quality experiments, fraud effectiveness, high-volume ranking, cloud broker ACL validation, full privacy erasure or regional recovery.

## Remaining Work
Live/sports and later phases proceed. Candidate cohorts are at most 100 and responses at most 20 per rail; current policy can shorten them. Future-date metadata and clock skew need operational curation. Retention interval/backlog must be sized from measured workload.

## Risks / Blockers
No phase correctness blocker remains. Opt-in counts deliberately represent a restricted cohort; daily caps limit simple amplification and are not comprehensive abuse prevention. Pre-consent playback starts are ignored rather than retroactively imported. Event inbox hashes retain deduplication for 90 days; older replay cannot recreate expired contributions. Recent fallback is labeled explicitly.
