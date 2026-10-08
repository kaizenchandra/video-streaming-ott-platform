# PHASE: 6 — Watch state

## Implemented
Experience owns durable progress, history, continue watching, watchlists, independent star/thumb reactions and versioned removal tombstones. Server-issued online/offline epochs order devices; session sequences allow intentional rewinds while rejecting collisions. Event UUID hashes deduplicate requests. Current profile ownership and device-bound grant validation precede mutation. Inbox, progress/history and typed outbox share a PostgreSQL transaction. Pages use bounded descending timestamp/UUID cursors; watchlist quota is serialized per profile.

## Architecture Decisions
[ADR-018](../adr/ADR-018-watch-state-ordering.md): PostgreSQL state, grant-authorized telemetry, account-monotonic epochs, newest-session ordering and per-content aggregate versions. Experience never becomes a Playback dependency. Local launchers pass only owner credentials; Experience's local client has playback.internal alone at this phase.

## Requirements Covered
FR-020 and FR-021 core behavior; NFR correctness, ownership, ordering, duplicates and failure isolation. Production retention/privacy jobs, high-volume measurement and DR fencing remain pending.

## APIs Added/Changed
See [Experience OpenAPI](../../contracts/openapi/experience.yaml): profile progress, resume, continue watching, history, watchlist, ratings and reactions. Existing scoped Playback token checks add validated account/profile/content/device and epoch; offline retains downloadId alias. Media/Playback OpenAPI descriptions and local ports corrected.

## Events Added/Changed
watch.updated.v1 first producer; reaction.changed.v1 first producer with backward-compatible nullable reaction and removed defaults. Rating 1–5 means explicit stars; 0 means absent stars. Kafka keys are profile IDs; aggregate version is per profile/content row. Actual registry transitive compatibility and old-reader decoding verified.

## Database Changes
Experience V1 creates owner-local state, inbox, session ordering and outbox. Playback V3/V4 add server epoch allocation triggers and legacy backfill without changing applied migrations. Counter is shared across online/offline grants.

## Security
JWT issuer/audience/signature/time checks; profile ownership on all operations; grant context account/profile/content/device match before progress. Kids maturity policy enforced on collection additions/reactions. Credentials, grant tokens and payloads are not logged. Client duration is advisory, bounded to 24 hours, and cannot prove billable viewing.

## Observability
Accepted/stale watch counters, outbox published/failed counters, standard JVM/HTTP/health/trace foundation. Dedicated dashboards/load measurements pending.

## Verification
PASS:
- PostgreSQL/Redis phase checks: PlaybackIT (14), PlaybackEventsIT (1), WatchIT (11); includes online/offline epochs, counter beyond wall time, parallel stream acquisition, progress ordering, late offline updates, event/sequence conflicts, quota races and transaction rollback.
- ExperienceEventsIT (1): real Kafka/Registry/PostgreSQL, outage retention/retry, typed records, profile keys, tombstones, schema evolution and old reader.
- Six-owner JourneyIT (1): real owner HTTP, independent PostgreSQL databases, Redis, Kafka/Registry, Temporal, S3 emulator, FFmpeg; signed payment → published synthetic media → playback → progress/resume/watchlist/rating.
- Reactor compilation/Checkstyle/contracts and unit tests; broader regression run passed Member/Content/Commerce/Playback/Media before a missing test import stopped Experience compilation. Corrected import and focused failed-module/journey run passed. Logs: /private/tmp/watch-playback-it.log, /private/tmp/ott-stage6-full.log, /private/tmp/ott-stage6-focused.log.
- Architecture inventory/dependency checks; Compose configuration validation; local scoped client actual token audience/scope verification (values withheld).

FAIL:
- None remaining. Initial schema fixture HTTP/2 upgrade failure and missing HTTP factory import corrected.

NOT RUN:
- One uninterrupted full-reactor integration run after the final test import fix; earlier unaffected modules plus corrected focused checks supply phase evidence.
- Production retention, privacy erasure, cloud/DR restore fencing, commercial DRM/CDN, performance/load or security scanning.

## Remaining Work
Search/discovery/recommendations and later phases proceed in dependency order. Synchronization after expired grants requires renewal; arbitrary late expired telemetry is rejected. Timestamp-based pages are not snapshot-isolated across concurrent updates.

## Risks / Blockers
No phase correctness blocker remains. Legacy epoch timestamps may collide; conflicting legacy session epochs are rejected securely. A restored database must not lower the epoch counter while old grants remain valid; actual regional restore fencing is unexercised. History/inbox/tombstone growth requires lifecycle controls before production operation.
