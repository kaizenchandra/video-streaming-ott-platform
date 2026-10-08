# PHASE: 5 — Media Ingestion and Processing

Status: PASS local bounded-MP4/clear-packaging engineering increment. Production multipart/long-form capacity, commercial DRM and production workflow/cloud recovery remain explicit extensions.

## Implemented

Owner PostgreSQL jobs, scoped conditional-create S3 upload (≤256MiB, SHA256/size fixed,10min), verified HEAD callback, durable dispatch and stable Temporal workflow IDs. Native source download bounded and checksum checked; MP4/file-only FFprobe; non-upscaled configuration-based VOD/SPORT renditions, H264/AAC CMAF, HLS/DASH, alternate source audio languages, text WebVTT captions, thumbnail. QC checks paths, MPD, durations and first4s decode of each rendition. Validated package frozen atomically in private staging before create-only immutable origin objects; completion manifest last, then READY+outbox ACID. Retry reuses original encoded bytes. Corrupt input/permanent failures produce safe failed state/event; transient retries capped3. Exhausted jobs require inspected forward asset version.

Separate API and headless worker modes; actual Docker Java21/FFmpeg7.1 worker UID10001, read-only root, capabilities dropped, bounded CPU/memory/PIDs/tmpfs. Native process excludes inherited credentials, executes fixed tool/arguments without shell, cleans diagnostics and terminates on timeout. Graceful worker shutdown waits before dependent DB resources close.

## Architecture Decisions

ADR-017 and media-platform updated after failure injection disproved byte-deterministic re-encoding. Original validated bytes are durable staging authority. Job pins config SHA256/profile; wrong worker configuration cannot silently re-encode. Official planned Temporal image tag unavailable; verified CLI1.9.1/server1.32.0 pinned by digest. Both official MinIO registry locations refused retrieval; official LocalStack4.3.0 used locally/tests, AWS S3 remains target. Actual CLI SQLite volume requires UID1000 ownership; task-owned initializer gives only CHOWN. Worker image Linux amd64 consistently for both Java and native codecs, emulated on ARM local host; no performance claim.

## Requirements Covered

FR-005 localization/version plus alternate audio/text captions; FR-017 bounded private ingestion/workflow; FR-018 actual probe/ABR/HLS/DASH/CMAF; FR-019 storage/thumbnail/clear DRM boundary/publication. Commercial packaging, multipart/large long-form sources, broader codecs/accessibility authoring and production CDN enforcement tracked rather than fabricated.

## APIs Added/Changed

Scope media.write: POST admin media uploads, POST job upload-completed, GET job. media.yaml specifies checksum/size/profile limits. Upload and playback media bytes go directly to object storage/CDN; business APIs do not proxy them.

## Events Added/Changed

Actual typed media.ready.v1 and media.failed.v1 via owner outbox. Ready includes version/prefix/manifest SHA256; failed includes jobId/reasonCode. Kafka outage leaves pending outbox; catalog consumes the actual ready event before allowing publication.

## Database Changes

V1 media_job/outbox; forward V2 VOD/SPORT profile; forward V3 pinned encoding-config hash/legacy default. V1 left unchanged after review rejected a baseline rewrite. Unique content+asset version, source key, expected size/checksum and state constraints. Completion state/outbox transaction tested with injected DB trigger failure.

## Security

Real JWT issuer/audience/scope validation; admin media authorization. No arbitrary source URLs/path arguments. Presigned key/checksum/size/conditional-create; private quarantine/staging/origin. Archive extraction rejects directories/path traversal/duplicate names, verifies all file hashes/source/config/profile and≤1GiB expanded total. Clear packaging blocked by prod profile. Local operator Keycloak client has only media.write/catalog.write/catalog.publish. Tokens/signed upload URLs withheld from logs/artifacts. Native exploit isolation, IAM/KMS and commercial packager require production hardening.

## Observability

Health/metrics/OTel/structured logs inherited, durable job/failure states, Temporal workflow/activity history/heartbeats, outbox counters. Detailed duration/quality/cost dashboards remain hardening work.

## Verification

PASS:
- Full repository `-Pintegration verify`:78 tests, zero failures/errors/skips, compile/package/Checkstyle/contracts, log `/private/tmp/ott-stage5-final.log`. Added native-process tests subsequently passed in focused media verification/package:2 tests;80 total verified tests across current suites.
- Seven real PostgreSQL+Temporal+S3 emulator+FFmpeg media tests: synthetic two-audio/caption source, two non-upscaled renditions, actual referenced files/sample decoding, corrupt input, missing/mismatched upload, quota/validation/scope, lost origin acknowledgement and atomic READY/outbox rollback.
- Real Kafka+Schema Registry typed success/failure decode and retained/retried outbox; no schema identity mismatch.
- Five-service HTTP journey: private upload→real Temporal/FFmpeg→S3→Kafka→catalog publication→signed payment/entitlement→playback/heartbeat/revocation denial. No direct mediaReady fixture remains in this journey.
- Native unit tests: inherited credential exclusion, diagnostics absent after execution, timeout termination.
- Standalone worker built and booted headless against local DB/Temporal; actual UID10001 and FFmpeg7.1 verified. DB/S3/Temporal local runtime healthy.
- Separate actual Keycloak→catalog/media executable APIs→private upload→headless worker using production NativeMediaTools→S3/Temporal/Kafka→catalog publication smoke passed. `.local/media-smoke` retains synthetic source and safe job/publication metadata. No token or signed upload URL retained. Native diagnostics contamination found here, fixed, regression-tested and rerun.
- Architecture checker PASS.

FAIL:
- None remaining in executed checks. Regenerated immutable-byte conflict fixed with durable package. Native temporary diagnostics leaking into archive fixed at adapter boundary. Flyway baseline preserved with forward migrations. Local Temporal permissions and cross-architecture Java/native image selection corrected.

NOT RUN:
- Commercial DRM, CloudFront distribution acceptance, AWS production S3/IAM/KMS, multi-hour/multipart ingest, codec licensing/production image scans, production Temporal durability/failover, full perceptual/DRM QC, server/worker crash chaos or regional restore. Clear synthetic output is not premium-content protection.

## Remaining Work

Watch state next; then search/discovery/recommendations/live/ads/notifications/analytics/operations/infrastructure/hardening/DR roadmap. Production media extensions above remain tracked requirements.

## Risks / Blockers

No blocker to local watch-state implementation. Production launch still blocked on commercial adapters, measured quality/capacity, isolation/scanning and exercised recovery. Local S3 emulator is not production durability proof. Linux amd64 emulation on this ARM host is not a benchmark.
