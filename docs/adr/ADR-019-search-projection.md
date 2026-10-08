# ADR-019: Durable search projection and authoritative result filtering

Status: Accepted technical design for Phase 7; execution evidence belongs in the phase report.

## Context
FR-022–024 require localized search, autocomplete, filters, discovery and deterministic recommendations. ADR-011 chooses a Kafka-fed OpenSearch projection. Search cannot block catalog publication or playback, and stale index rights cannot authorize viewing.

## Decision
Experience consumes typed catalog publication events, retrieves the exact immutable published metadata snapshot through scoped Content REST, and commits a version-guarded PostgreSQL projection/inbox. An asynchronous index worker writes per-content/locale OpenSearch documents with external versions. Database commit and search indexing are separate recoverable steps; acknowledgement loss and out-of-order events must not lower a document version. Durable metadata also supports bounded home fallback and rebuilding a lost index.

Content adds draft genre/actor/release-date metadata and includes it in immutable published snapshots. Existing snapshots default to empty taxonomy; public existing catalog representations remain compatible. Snapshot APIs are workload-scoped. Metadata hydration never reads another owner's private database.

Search accepts a bounded plain-text query and server-built JSON DSL, with exact type/genre/locale/maturity filters, bounded pagination, BM25 relevance and fuzzy matching. Autocomplete uses search-as-you-type prefix matching; prefix completion does not promise fuzzy correction of its final token. Each response validates profile ownership and batch-filters candidate IDs through Content's current rights and maturity policy. A country parameter selects discovery results and is not playback authorization; actual playback still requires the trusted edge geo assertion. Uncertain policy lookups return unavailable without revealing candidate results. Search outage returns explicit unavailable; home may return recent metadata from the durable projection, filtered through the same owner policy.

Index I/O has bounded deadlines and concurrency. Transient event handling retries with jitter and then parks the raw record in a topic-specific DLQ; malformed/foreign events are poison messages. Replays and rebuilds are safe only through the version guard. Rebuild tooling must distinguish reconstructing OpenSearch from Experience state from recovering lost Experience state via catalog snapshots/replay.

## Alternatives
Writing OpenSearch inside Content transactions couples availability and has no atomic cross-store guarantee. Querying Content databases violates ownership. Client-provided DSL risks expensive queries and injection. Trusting indexed rights allows stale authorization. A new search service is unjustified given the accepted Experience boundary.

## Rationale
This design isolates search failures, preserves owner authority, and provides a repairable projection without 2PC or synchronous index dependence in publication/playback.

## Consequences
Search freshness is eventual and rights filtering may yield a shorter page. Local security-disabled OpenSearch is restricted to loopback/task-owned networks; production requires TLS and scoped credentials/workload identity. Dynamic result pagination is bounded and does not promise a point-in-time snapshot. Multilingual language analyzers, synonym dictionaries and large-catalog relevance evaluation are future measured tuning tasks, not implied by a generic analyzer.

## Operational Impact
Measure projection/index lag, stale events, DLQ volume, search failures and fallback use. Use [OpenSearch external versions](https://docs.opensearch.org/latest/api-reference/document-apis/index-document/), [autocomplete fields](https://docs.opensearch.org/latest/search-plugins/searching-data/autocomplete/) and [multi-match prefix/fuzziness semantics](https://docs.opensearch.org/latest/query-dsl/full-text/multi-match/). Run real OpenSearch/Kafka/Registry/PostgreSQL integration tests and an index-loss rebuild drill. Regional recovery remains unvalidated until explicitly exercised.
