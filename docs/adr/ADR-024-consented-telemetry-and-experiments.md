# ADR-024: Consented telemetry admission and deterministic optional experiments

Status: Accepted Phase 12 design; implementation follows the Phase 11 gate.

## Context
FR-032–034 require representative activity events, QoE dimensions, analytics and feature/experiment foundations. The target event-stream/S3 platform is not evidence of measured volume. No player probe, commercial CDN metrics or experiment service credentials are supplied. Client telemetry cannot authorize playback or prove billing/viewing.

## Decision
Insights is an independent owner with its own PostgreSQL admission/dedup/configuration data and Kafka typed outbox. Initial traffic is unmeasured, so local ACID admission plus bounded retention and cohort rollups precede a bulk S3/archive pipeline. Archive evolution stays explicit rather than claiming it is deployed. Client event batches are bounded, authenticated and tied to owned profile/device/current Playback grants for play/QoE observations. Search carries no raw query text. Subscription/payment/ad signals come from existing typed owner topics; clients cannot fabricate those authoritative event types. All reports retain provenance (client claim versus owner business event).

Optional analytics and experiment consent default off. Enabling stores a server timestamp; opt-out removes owner-derived profile/account detail and prevents late pre-consent replay from reimporting it. Admission fingerprints and semantic session/sequence uniqueness protect duplicates; independently bounded samples accept out-of-order delivery without treating repeated cumulative counters as new deltas. QoE played/buffer milliseconds are interval deltas, startup is a start-only observation, and dimensions use finite/coarse allowlists. Client region/CDN/device hints are explicitly untrusted measurement dimensions and never substitute for geo/device authorization. No raw tokens, IPs, queries, provider payment references or names are archived.

QoE cohorts expose startup samples, buffer/played ratio with a zero-duration rule, quality switches, bitrate/resolution and failure counts from admitted observations. These are telemetry aggregates, not actual player/CDN SLO verification. High-cardinality account/session labels never enter metrics. A new typed analytics.received.v1 carries every admitted observation/business signal. Existing qoe.received.v1 carries session samples; additive defaulted kind/dimension fields preserve old writers. ad.impression.v1 adds nullable defaulted accountId so new owner signals can honor consent without an extra synchronous lookup; older records without it are ignored for consented analytics. Events persist with typed outbox; consumers must deduplicate event IDs because relay delivery is at least once.

Versioned experiment configuration applies only to optional experience features. Stable HMAC subject bucketing and persisted versioned assignments provide deterministic allocations. Experiment consent is independent of analytics; opt-out returns CONTROL and deletes assignment details. Disabling returns CONTROL immediately. Feature/experiment decisions cannot relax authentication, rights, payment, parental/device/geo/stream quotas, DRM or core availability policy, and Playback never calls Insights. No ML or general rule engine is introduced.

## Alternatives
Direct unbounded Kafka writes lose local admission/dedup evidence and backpressure control. Logging arbitrary client JSON collects unnecessary PII and enables metric-cardinality abuse. Trusting client payment/ad claims misrepresents authoritative business outcomes. Assigning experiments before consent introduces unsupported profiling. Synchronous analytics in Playback reduces availability.

## Rationale
Explicit provenance, consent boundaries and bounded owner-local transactions favor correctness for the available local workload. The existing Kafka/Registry and typed schemas support later archival without an additional storage technology before measurements justify it.

## Consequences
Analytical cohorts are consented/sparse and client reports may be incomplete or dishonest; neither full audience estimates nor recommendation lift/billable viewing follows. Sampling/admission limits can drop optional telemetry with explicit result/metrics. Production throughput, archives/residency, player accuracy and experiment impact require separate validation. Retention/erasure and source replay horizons must stay aligned.

## Operational Impact
Monitor admission/duplicate/conflict/consent/overload reasons, cohort freshness, outbox and source-consumer lag, retention backlog and assignment fallback. Separate Insights credentials and scoped owner calls. Test real PostgreSQL duplicate/sequence races, malformed batches/rollback, opt-out races, bounded dimensions/timings, source ownership/schema/poison, actual Kafka/Registry publication, deterministic assignment and disabled/CONTROL behavior. No cloud analytics or real player QoE is claimed exercised.
