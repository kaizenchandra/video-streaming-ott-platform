# ADR-020: Deterministic, consented recommendation signals

Status: Accepted Phase 8 design extension to ADR-015; execution evidence is separate.

## Context
FR-024 needs popular/trending, same genre/actors, recent releases and because-you-watched heuristics. No training corpus or demonstrated ML need exists. NFR privacy, replay safety and failure isolation apply. Activity counts must not be misrepresented as measured audience, billable viewing or fraud-proof engagement.

## Decision
Experience retains its boundary and builds deterministic rails from its durable published catalog projection. Popular uses deduplicated daily profile/content starts over 90 days; trending uses the last day, ordered by counts then stable content UUID. These are opt-in audience signals, not total viewer counts. A typed playback.started.v1 consumer checks owner/type/key/version, deduplicates event UUIDs, bounds future event time, and limits contribution to one profile/content/day. Source timestamps preserve replay semantics. Derived activity has a 90-day lifecycle.

Profile personalization defaults off. A viewer-owned preference opts in; opt-out deletes that profile's derived contributions transactionally. Core watch progress remains a separate requested resume function. Popular/trending can be returned to any viewer, but only opted-in profiles contribute signals. Contextual same-genre/actor rails can use an explicitly selected permitted seed. Because-you-watched uses the profile's own latest progress only while opted in. All candidates and seeds pass the same current rights/maturity filtering as search; no personalization service is on the playback path.

Use bounded PostgreSQL queries, taxonomy GIN indexes and indexed date/content signal keys. Stable tie-breaks and explicit rail names make behavior explainable. Recent releases use known release date, falling back to publication time only for missing release metadata. Empty cohorts fall back to recent eligible catalog; dependency failures cannot fabricate signal success or bypass rights.

## Alternatives
ML infrastructure adds complexity without evidence. Largest-progress popularity trusts client duration and position. Counting every playback heartbeat permits refresh amplification. Retaining derived identifiers after opt-out contradicts the preference. A synchronous recommendation dependency would harm playback isolation.

## Rationale
Deterministic, replay-safe and consented data supports a useful baseline with controllable privacy and operational cost.

## Consequences
Sparse opt-in cohorts produce sparse rankings. Daily caps limit simple amplification but do not constitute fraud prevention. No quality, personalized lift, scale or statistical validity is claimed before evaluation. Future ML, regional signal federation or weighted engagement requires a new measured decision and consent review.

## Operational Impact
Observe accepted/duplicate/ignored signals, consumer lag/DLQ, consent changes and rail latency. Test duplicates, old/future events, opt-out erasure, stable ordering, rights changes and actual Kafka delivery. Retention deletes bounded batches; global privacy erasure and regional recovery remain later-phase work.
