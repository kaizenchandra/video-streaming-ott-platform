# ADR-018: Playback-authorized watch state and cross-device ordering

Status: Accepted technical decision for Phase 6. No measured high-scale claim.

## Context
FR-020–021 require progress, resume, history, watchlists and reactions. Client timestamps cannot establish ordering across devices. Replayed or fabricated telemetry must not grant viewing rights. PostgreSQL is the current state store (ADR-005); a dedicated NoSQL store requires measured saturation.

## Decision
Experience owns its PostgreSQL state and validates profile ownership through Member. Each progress submission must carry a current device-bound online or offline grant; Playback returns the validated account/profile/content/device and a server-issued epoch over a scoped internal REST endpoint. Experience is never a synchronous Playback dependency.

Playback allocates strictly increasing epochs per account in PostgreSQL, under the existing account quota lock. A database trigger also covers older binaries that omit the additive column. Legacy rows are backfilled before Experience has any state; timestamps can collide for legacy rows, in which case Experience rejects conflicting session epochs rather than guessing. New grants share one counter across online and offline grants.

Experience accepts the newest epoch, then the largest sequence within that session. Position may decrease intentionally for rewind. An older epoch cannot overwrite newer progress. A duplicate event UUID must retain the same owner and payload hash; a reused session sequence with different values is a conflict. Per-profile/content transaction advisory locks serialize competing updates. Inbox, progress, history and outbox commit together. Accepted progress versions are monotonic per profile/content aggregate; Kafka is keyed by profile. Consumers must still tolerate duplicates and out-of-order relay publication using aggregate versions.

Recent history, continue watching and watchlists use bounded descending timestamp/UUID keyset pagination. Progress duration is bounded client telemetry, not authoritative media duration, ad proof or billing evidence. Watchlists default to 500 items per profile, enforced with a profile row lock. Ratings and LIKE/DISLIKE reactions are independent; removing both leaves a versioned tombstone.

The previously unproduced `reaction.changed.v1` schema gains nullable `reaction` and `removed` fields with defaults. Its first producer specifies rating 1–5 as explicit stars and 0 as no star rating. Existing integer wire type is retained; compatibility must be tested against the original schema. No deployed consumer is known to depend on a narrower rating meaning. A future incompatible change requires a new event version.

## Alternatives
Client wall clocks permit clock skew and forgery. Largest position prevents rewind. Unauthenticated telemetry permits cross-account poisoning. A shared Playback database violates ownership. Cassandra/DynamoDB would add operational complexity before the present workload is measured.

## Rationale
Server-issued ordering and real grant verification provide deterministic cross-device behavior while preserving control-plane separation, local ACID transactions and explicit ownership.

## Consequences
Expired grants cannot submit late telemetry; clients renew an online grant or synchronize offline progress before its license expires. Failed owner lookups return unavailable without mutation. Starting a newer device session intentionally wins over an older offline session. Dynamic collections may move between pages; no snapshot-pagination guarantee is made. Persistent history, inbox and tombstones require retention/privacy jobs before production use.

## Operational Impact
Monitor accepted/stale/conflicting submissions, owner failures, database lock timeouts, and outbox lag. Regional restore must restore the epoch counter and watch state consistently or fence old grants; DR validation remains a later phase. Commercial DRM/CDN validation and high-volume benchmarking remain unexecuted.
