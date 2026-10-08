# ADR-017: Bounded initial media workflow and immutable publication

Status: Accepted engineering increment, 2026-10-08.

## Context

FR-005,017..019; NFR-004..006,008,013. Synthetic-media processing requires real storage/workflow/codec behavior without commercial DRM credentials. Native parser execution must stay outside viewer control-plane requests. Initial local uploads are limited to256MiB; full long-form production ingest needs multipart and measured worker capacity.

## Decision

Media API owns PostgreSQL job state and generates scoped single-PUT S3 uploads fixed to size/checksum/content type/key, conditional create and10min expiry. Callback verifies HEAD before UPLOADED; dispatcher starts Temporal with stable job ID outside DB transaction. Start response loss reconciles already-started workflow. Activities download bounded bytes, verify SHA256, probe MP4 with file-only protocols, choose non-upscaled config renditions and content-specific VOD/SPORT profile, package H264/AAC CMAF HLS/DASH, extract text captions and thumbnails, validate referenced files/durations and sample decode, then publish create-only origin objects and completion manifest last. Each existing object must match its SHA256 metadata on retry. READY+typed outbox is one local transaction. Exhausted/invalid processing records a safe failed event; no fake publication.

## Alternatives

Synchronous HTTP transcoding; ephemeral async executor; DB/S3 distributed transaction; Redis-only queue; a universal1080p ladder; arbitrary URL ingestion.

## Rationale

Stable Temporal execution survives worker restarts; bounded native work and immutable objects make retries safe. Object writes cannot join a PostgreSQL transaction, so completion marker and idempotent local finalize bridge that boundary. Source metadata and explicit profiles avoid upscaling and make quality/cost choices reviewable.

## Consequences

Initial single-PUT limitation is explicit, not full-size production ingest. Clear mock packaging excluded from prod; real provider packager is a launch dependency. Audio tracks/text subtitles supported, image captions/audio-description authoring and HEVC/AV1 require additional profiles/QC. Native API and worker can deploy separately using worker flag/web application mode. Exhausted jobs require investigated forward asset version; no automatic repeated irreversible publication policy.

## Operational Impact

Java Temporal SDK1.31.0 and AWS SDK2.31.78 verified with existing Java21/Spring3.5.16. Temporal planned1.3.0 image tag was unavailable; pin verified official CLI1.9.1/server1.32.0 image by digest. Both official MinIO registry locations refused retrieval; local/tests use official LocalStack4.3.0 S3 emulator. AWS remains target S3 production store. Local emulator/server are not production DR/durability validation. Worker container security/resources, cloud workload IAM, multipart, workflow-history rollout compatibility and production commercial DRM remain launch/hardening tasks.


Recovery correction: native encoders are not assumed byte deterministic across retries. After QC, an atomic create-only validated ZIP is stored under a private staging job key before any origin publication. Retries load those original bytes, safely extract bounded filenames/≤1GiB total, validate every manifest checksum/source/config/profile, and conditionally publish the same objects. `_complete.json` is written last; READY/outbox remains one PostgreSQL transaction. An actual lost-publication-acknowledgement test failed under regeneration and passed after this correction. Jobs pin the canonical encoding-config SHA256; changed configuration requires a matching worker or a forward asset version.

V1 remains unchanged; V2 adds VOD/SPORT profile, V3 pins encoding configuration hash with a legacy baseline default. Native subprocesses execute fixed binaries/argument arrays and receive an allowlist environment excluding application credentials. Tools run directly; timeout terminates/waits for the direct native process. No shell or external process wrapper is supported in production. Standalone worker uses headless Spring, non-root10001, read-only root, bounded scratch/resources and no extra capabilities. Local image Linux amd64 requires emulation on this ARM host; no performance claim. Native parser exploit isolation/least-privilege IAM and full container scan remain launch work.
