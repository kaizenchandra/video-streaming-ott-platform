# PHASE: 9 — Live/sports control plane

## Implemented
Published sports-linked events with league/teams, stable schedule pagination, versioned SCHEDULED/READY/LIVE/ENDED/ARCHIVED transitions and pre-live cancellation/rescheduling. Durable command receipts, source epoch fencing, subject-bound signed raw feed proof, monotonic source sequences, freshness, sealed final timeline, commentary/subtitle metadata and bounded highlight references. LIVE/DVR/REPLAY grants reuse existing Member/device/geo/Commerce checks and the PostgreSQL/Redis stream quota. Ordinary VOD cannot bypass a linked live sports event.

## Architecture Decisions
[ADR-021](../adr/ADR-021-live-control-and-feed-fencing.md). Content owns live metadata/policy; Playback owns quotas/grants. No new deployable or media proxy. Packager proves private-origin media; commercial contribution acquisition, discontinuity alignment, redundancy, eviction and CDN integration remain provider boundaries. A source-prefix change requires restarting existing sessions. Live proof/grants last at most 30 seconds; catch-up expires relative to actual final media time.

## Requirements Covered
FR-025/026 control-plane baseline; FR-027 shared quota and authorization. Flash-crowd/cloud capacity and CDN prewarming remain target design, not exercised evidence. Scores are not a supplied FR and are only future provider metadata.

## APIs Added/Changed
Content live admin creation/read/transition/reschedule/failover/highlight endpoints, authenticated schedule/fixture/highlight reads, scoped live policy check and signed packager heartbeat. Playback event/mode authorization route. See [Content](../../contracts/openapi/content.yaml) and [Playback](../../contracts/openapi/playback.yaml) contracts.

## Events Added/Changed
Existing live.changed.v1 first producer now emits Content lifecycle/reschedule/failover/highlight changes in the same transaction as state/audit. Event aggregate/key is live event ID, version is owner state version. Feed heartbeat sequences are independent and do not emit lifecycle changes. Existing playback events cover VOD/live sessions alike. No breaking Avro schema change.

## Database Changes
Content V4 adds event/schedule index, command receipts, audit and unique highlight references. Playback V5 adds target kind/event ID and a compatibility trigger for older VOD inserts. Existing VOD idempotency fingerprints preserved. No previously applied migration rewritten.

## Security
live.write admin scope; live.feed workload scope plus exact configured packager JWT subject and raw-body HMAC/timestamp verification. No tokens/provider secrets in logs. Viewer fixture omits provider identity/checksum/epoch. Live policy remains fail closed on stale source, rights/maturity/region denial or owner failure. Separate replay/highlight rights. Signed CDN prefix validation now permits only VOD versions or event source epochs; local DRM/CDN remain explicitly mock clear/untrusted. Full segment-window enforcement requires actual origin eviction/provider verification.

## Observability
Existing HTTP/JVM/trace/outbox/playback metrics, live state counters and accepted feed-proof counter. Operators monitor proof age, source epoch changes, state/command conflict, bounded grant renewal and quota denials. No continuous-feed or startup-latency benchmark claimed.

## Verification
PASS:
- LiveIT (8), real PostgreSQL: competing starts, command rollback/collision, duplicate proof without refresh, source fencing, wrong packager, altered sequence/future media, stale proof, region/maturity/DVR bounds, no VOD bypass, sealed end/catch-up, separate replay rights, highlights, reschedule/cancellation and signed HTTP scope/tamper checks.
- PlaybackIT (16), real PostgreSQL/Redis: prior suite plus mixed live/VOD competing grants under one cap and source-prefix/revocation fencing.
- Six-owner JourneyIT: actual FFmpeg/Temporal packaging, private S3, account/payment/entitlement/search/progress; finite synthetic CMAF live-prefix publication directly to S3, signed source proof, owner-based live grant/heartbeat. Typed live.changed.v1 registry/Kafka decode checked in full regression below.
- Complete repository integration regression: 124 tests, zero failures/errors/skips; compilation, Checkstyle, contracts, all owner suites and expanded journey. Evidence: /private/tmp/ott-phase9-full.log (3:08).
- Architecture inventory/link/dependency check and Python bootstrap/launcher compilation.
- Dedicated local Keycloak packager bootstrap: aud=ott-api and live.feed scope; token/secret withheld.

FAIL:
- None in focused run. Initial new tests exposed the VOD-only signer/prefix validation, which was corrected with a strict live allowlist.

NOT RUN:
- Commercial feeds, sustained live continuity, actual contribution failover, low-latency player behavior, segment eviction, commercial DRM/CDN acceptance, cloud flash crowds/prewarming and regional DR.

## Remaining Work
Advertising and later phases. Configure independent packager workload/secret and least-privilege live-prefix S3 policy per environment. Local client bootstrap prints only its subject. Run bootstrap-local to generate ignored secrets, bootstrap-live-packager to provision live.feed, and bootstrap-media-operator to add live.write for local administration. The local proof fixture is finite test media, not a continuously running broadcaster.

## Risks / Blockers
Full phase gate PASS; no remaining phase correctness failure. ≤30-second grants bound stale authorization but cannot revoke already issued CDN bytes sooner. Catch-up uses the same source prefix and depends on packager retention/eviction. Separate archive assets are externally produced through the existing media workflow.
