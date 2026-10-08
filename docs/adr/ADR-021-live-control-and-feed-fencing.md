# ADR-021: Fenced live control and separate replay authorization

Status: Accepted Phase 9 implementation design; commercial feeds/DRM/CDN remain external integration boundaries.

## Context
FR-025–027 need schedules, fixtures, event lifecycle, commentary/subtitles, DVR/catch-up/replay/highlights and secure live grants. Content owns live metadata and rights; Playback owns all stream quota/grants. Business services cannot transport media. No commercial feed credentials are supplied.

## Decision
Content owns versioned SCHEDULED → READY → LIVE → ENDED → ARCHIVED events and cancellation from pre-live states. Commands use expected versions, durable idempotency receipts, audit and typed outbox in one transaction. Sports content must be published before READY; a reference-territory license must cover scheduled start. Starting is permitted only inside the scheduled interval with fresh healthy feed proof. Feed failover increments a source epoch and invalidates old health/prefixes; existing sessions must restart on prefix change.

A packager workload is bound to the event's configured JWT subject, requires live.feed, and signs the raw heartbeat body/timestamp with a local/provider-specific secret. Proof includes monotonic source sequence, epoch, manifest checksum, first/latest segment time, healthy/sealed status and available commentary/subtitle metadata. Duplicates do not refresh health; a reused sequence with changed body conflicts. Live proof expires after 30 seconds, future media timestamps are bounded, and old epochs cannot refresh a new source. The packager is the trusted origin/segment authority; accepting a proof does not mean Content fetched or proxied media. Local tests use deterministic synthetic media and signed packager fixtures; real contribution acquisition, redundant encoders and commercial origin health integration are explicitly unvalidated.

Playback adds an event/mode target to its existing durable capacity/session ledger. LIVE and DVR grants use fresh event policy and the same member/device/geo/subscription/DRM checks as VOD. Heartbeats revalidate the saved event/mode. Idempotency binds event, mode and initial seek; current prefix changes are conflicts. DVR start must lie within the proof's rolling window. Origin/packager eviction enforces unavailable older media; advertised seek windows alone are not a segment-level security mechanism.

ENDED DVR requires sealed final proof and expires at the configured catch-up deadline. ARCHIVED replay/highlight references are separate published VOD content with separate current rights; live grants cannot authorize replay indefinitely. A live-linked sports content ID cannot use ordinary VOD authorization to bypass the event policy. No live/offline license is inferred from these grants.

## Alternatives
Separate quota logic for live risks combined VOD/live overbooking. Trusting client live-state or unsigned health enables stale/forged grants. Reusing live rights for archive bypasses licensing. Reusing old source prefixes after failover fails fencing. Proxying segments in Spring violates the data-plane boundary.

## Rationale
The existing Content/Playback boundaries cover the control-plane workload without a new deployable or distributed transaction. Short grants bound stale authorization, and source epochs plus current owner checks provide explicit failure behavior.

## Consequences
Clients renew live authorization before its ≤30-second proof deadline and restart on source-prefix change. Source outages deny new grants/renewal; already-issued CDN access lasts only to expiry. Archive processing/readiness is separate from ending an event. Actual low-latency glass-to-glass behavior, commercial feed redundancy, origin eviction and CDN prewarming require provider/player/load validation; none are implied by metadata state.

## Operational Impact
Monitor feed freshness/sequence/epoch rejection, event transition failures, grant denials and existing stream quota/latency metrics. Provision a dedicated packager identity/secret and least-privilege live-prefix object-store access. Plan scheduled capacity/prewarming and exercise signed synthetic feed loss, duplicate starts, failover, stale proof, DVR bounds and independent replay rights in real database/session integration tests.
