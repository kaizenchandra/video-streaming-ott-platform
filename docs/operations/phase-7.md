# PHASE: 7 — Search and discovery

## Implemented
Catalog outbox → typed Kafka → exact immutable snapshot hydration → transactional Experience projection/inbox → claimed asynchronous OpenSearch indexer. Per-content/locale external versions reject stale writers and tolerate lost acknowledgement. Plain-text fuzzy search, prefix autocomplete, type/genre/locale filters, bounded relevance pagination and a recent-publications home rail. Current owner rights and profile maturity filter results; uncertain policy returns unavailable. Home degrades to durable recent metadata. A scoped, checkpointed rebuild repairs projection/index from owner snapshots and reports actual scan/index state.

## Architecture Decisions
[ADR-019](../adr/ADR-019-search-projection.md) extends ADR-011. No synchronous search dependency in publication or playback, no cross-owner SQL, no cross-store atomicity claim. Country is discovery selection, not playback geo proof. Index dates are explicit ISO strings. Worker claims at most two snapshots, bounded to 32 locales each; snapshot repair pages contain 20 items. Production search requires TLS, configured credentials and replica count ≥1.

## Requirements Covered
FR-022 and FR-023 core search/home behavior. FR-004/005 metadata gains draft genres/actors/release date captured in immutable versions. Relevant ordering, replay, privacy, compatibility and degradation NFRs exercised locally. Generic analyzer is not claimed to provide language-specific relevance quality.

## APIs Added/Changed
Experience search, autocomplete, home, privileged rebuild intent/status. Content taxonomy, scoped exact-version snapshots, snapshot inventory and current rights batch. Contracts: [Experience](../../contracts/openapi/experience.yaml), [Content](../../contracts/openapi/content.yaml). search.write guards rebuild operations; content.internal guards snapshot/rights APIs.

## Events Added/Changed
Existing catalog.published.v1 first real Experience consumer, with producer/type/schema/key/aggregate checks. Duplicate UUID/version protection and bounded poison/transient handling; raw parked records go to catalog.published.v1.dlq. No new business topic or incompatible schema change.

## Database Changes
Content V3 adds JSON taxonomy and release date without rewriting applied migrations. Experience V2 adds search inbox, versioned metadata/index checkpoint, bounded claims and rebuild checkpoint. OpenSearch strict mapping: per-content/locale documents, search-as-you-type title, text synopsis, keyword facets and explicit dates.

## Security
Viewer JWT and owned profiles; authoritative current rights/maturity batch filtering before response. Bounded server-built DSL, query length ≤100, pages ≤100, offset ≤1000, no arbitrary DSL execution. Local Experience credentials carry content.internal and playback.internal only. Security-disabled local OpenSearch binds loopback/task-owned networks; production authentication/TLS/replication checks reject unsafe configuration.

## Observability
Projection accepted/stale, index published/failed, search requests and home fallback metrics. Five consecutive transport/search failures open the read circuit for ten seconds; read bulkhead 32. HTTP/JVM/health/trace baseline retained. Measured scale/relevance dashboards remain future work.

## Verification
PASS:
- Full reactor `-Pintegration verify`: compilation, Checkstyle, contract parsing/generation, unit/integration tests and journey; zero failures/skips. Evidence: /private/tmp/ott-stage7-full.log.
- ContentIT (14) + CatalogEventsIT (1): immutable taxonomy/localizations, 32-locale quota rollback, current batch rights/time/maturity/revocation and scoped snapshots, existing publication/event regressions.
- SearchIT (8): real PostgreSQL/OpenSearch, fuzzy/prefix/localized/facet search, current rights revocation without reindex, owner failure/foreign profile denial, external versions/lost acknowledgement, actual index deletion → durable home fallback → rebuild, input bounds and privileged intent/status APIs.
- CatalogIndexEventsIT (1): real Kafka/Registry/PostgreSQL/OpenSearch; duplicate typed publication, durable hydration/indexing and raw poison record parked to DLQ.
- JourneyIT (1): actual six-owner HTTP, independent databases, private synthetic media processing, raw media event publication, catalog outbox/Kafka/search indexing, rights filtering, signed payment/entitlement, playback and watch state.
- Updated Compose config and actual local workload scope/audience verification; architecture inventory/dependency check.

FAIL:
- None remaining. Initial numeric timestamp indexing failure corrected; unused disabled fixture injection and test imports corrected.

NOT RUN:
- Cloud OpenSearch authentication/authorization deployment, language relevance evaluation, high-volume indexing/search load, large online blue/green reindex or regional disaster recovery.

## Remaining Work
Phase 8 deterministic rails/signals, then later phases. Rebuild COMPLETE means owner scan checkpoint is complete and current projection has zero pending documents; new publications can increase lag. Dynamic pages are not a point-in-time snapshot and current rights can shorten a page. Large-production reindex watermark/alias orchestration requires further operational validation.

## Risks / Blockers
No phase correctness blocker remains. Event hydration failures park after bounded retries; operations must repair and replay DLQs. Snapshot source retains published versions for replay. Local index-loss recovery is verified and must not be confused with regional PostgreSQL/Kafka recovery. Index freshness and generic multilingual relevance remain bounded by their tested local behavior.
