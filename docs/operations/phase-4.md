# PHASE: 4 — Playback Control Plane

Status: PASS local engineering increment. Clear-media mock DRM explicitly selected; commercial license systems and live CloudFront enforcement unexecuted.

## Implemented

Owner-bound playback start/idempotency, heartbeat revalidation, end and expiry worker. PostgreSQL per-account reservation before atomic Redis Lua acquire; renew durable expiry before lease renewal and record grant only after successful lease. Outstanding ended grants retain capacity until expiry. Trusted HMAC edge assertion binds issuer/subject/device/country/time. Scoped owner APIs, parallel rights/entitlement requests and bounded bulkhead/deadlines, cached OAuth client credentials. Audience/device/expiry/signature/session/token-hash validation. Offline policy with separate audience/ledger/quota/revoke and rights+plan bound expiration. CloudFront prefix cookies signed RSA/SHA256; local mock key is not trusted by a real distribution.

## Architecture Decisions

ADR-008 updated: the current durable PostgreSQL ledger makes Redis recovery conservative without an epoch. A stale DB restore still requires writer fencing/quarantine, unexercised. Initial lookup dependencies cannot promise the production300ms p95 target. Local HMAC grants require externally configured signing secrets; production asymmetric token rotation and cryptographic device proof remain launch work. GrantProvider is local and excludes prod; prod without a real adapter cannot start.

## Requirements Covered

FR-012/013/014 core local control, session/capacity/token/CDN adapter; FR-015 mock DRM provider boundary only, commercial adapters incomplete; FR-016 local offline authorization metadata, no commercial offline license. NFR consistency/security/idempotency/failure handling addressed; scale and production SLOs not measured.

## APIs Added/Changed

POST playback sessions with Idempotency-Key and trusted X-Geo-Assertion; POST session heartbeat; DELETE own session. POST downloads, DELETE own download. Scope playback.internal token check for playback/offline. Contracts in playback.yaml. Ordinary media bytes never pass through these APIs.

## Events Added/Changed

Actual typed playback.started.v1 and playback.ended.v1 via durable outbox; key=sessionId, versions1/2, ack/claim/backoff. Expiry/end duplicate-safe. Schema/Kafka failure retains the pending owner outbox.

## Database Changes

Owner V1 quota/session/grant-hash audit/outbox and V2 offline grant/hash audit. Quota row locks, idempotency uniqueness, state/expiry constraints, account+expiry indexes. PostgreSQL owns capacity; Redis only accelerates lease coordination. Task-owned local playback DB5435 added without replacing other owner databases.

## Security

Actual JWT issuer/audience/time/signature validation inherited. Viewer account resolved through member; internal owner requests use narrow client-credentials scopes. Real local Keycloak workload client verified for audience/scopes. Geo header alone is insufficient; fresh assertion required. Clear DRM mock explicitly identifies protection and untrusted CDN key. No private keys/tokens/secrets committed or logged. Signature cookie replay remains bearer risk until commercial device/DRM protection; max60s delivery grants. Offline metadata policy5 active/account and24h maximum is an assumption.

## Observability

Health/metrics/structured logs/OTel plus authorization/lease failure and outbox counters, durable hashed grants. Dashboards/latency histograms and fleet alerts remain phase15.

## Verification

PASS:
- Full `./scripts/mvn.sh -Pintegration verify`: 70 tests, zero failures/errors/skips, build/package/Checkstyle and all OpenAPI/Avro/Protobuf checks. Log `/private/tmp/ott-stage4-final.log`.
- Playback: 13 real PostgreSQL+Redis tests including parallel limit/same-key races, Redis loss, real command timeout and compensation, ended grant capacity, ownership, expired/nonrenewable sessions, duplicate expiry, device/region revocation, token signature/audience/hash and offline quota/revoke.
- Real PostgreSQL→Kafka→Schema Registry playback started/ended delivery with typed Avro decode, schema failure/retry.
- RSA CloudFront cookie signature independently verified and policy checked for exact prefix wildcard/expiry.
- Independent four-service HTTP journey including client-credentials issuance, actual owner checks, signed payment, entitlement, playback/heartbeat, device-revocation denial.
- Architecture checker; playback Compose DB healthy; executable playback jar readiness UP against local owner DB/Redis; actual local Keycloak workload audience/scopes verified.

FAIL:
- None remaining in executed checks. Initial timestamp binding and test aggregate-ID defects corrected and rerun. Keycloak hardcoded scope claim was overwritten by default scope construction; corrected to explicit client scopes and verified actual claims.

NOT RUN:
- Commercial DRM/CDN distribution/provider acceptance, browser cookie-domain delivery, cryptographic hardware device binding, asymmetric grant-key rotation, end-to-end media bytes (phase5), load/chaos/region or DB-restore fencing.

## Remaining Work

Media ingestion/processing next; commercial adapters and the remaining roadmap phases continue. MockClear license metadata cannot protect premium content. Production offline license enforcement/revocation is provider-specific and unimplemented.

## Risks / Blockers

No blocker to synthetic-media/local phase5. Production launch blocked on commercial adapters, measured SLOs, persistent-workflow/cloud integration and exercised recovery. Service HTTP/API/mocked clear mode is verified; not a production readiness claim.
