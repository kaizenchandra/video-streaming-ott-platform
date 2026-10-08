# PHASE: 1 — Identity / Account / Profile / Device

Status: PASS implemented member increment, local gate. IdP native signup/login/session UI and commercial security review remain external integration boundaries.

## Implemented

Issuer+subject account provision/get, bounded owner profile list/create/versioned update, kids ceiling and recent signed auth_time required to weaken restrictions or create unrestricted adult profile, device capability registration with natural-key idempotency/conflicts and active cap, owner revocation/list, scoped internal profile/device eligibility. Per-account locks serialize quotas. Revocation+typed outbox in one local transaction; multi-replica relay uses expiring SKIP LOCKED claims, broker ack before mark, same eventId retries and backoff.

## Architecture Decisions

ADR-001/004/005/006/007/012 followed. Stable typed contracts module is not a shared business-state common library. Member event partition key accountId; aggregate/version refers to device (corrected design). Schema Registry lookup uses exact registered writer schema, with Confluent Avro wire framing. HTTP1.1 configured for local registry Jetty compatibility. Guardian reauthentication uses IdP-signed auth_time≤120s; no plaintext PIN storage.

## Requirements Covered

FR-001..003 implemented for account/profile/device backend boundary; native IdP PKCE login/user UI not exercised end-to-end. Security negatives, concurrency/idempotency and transaction NFR evidence extended. Remaining FRs stay DESIGNED.

## APIs Added/Changed

`PUT/GET /v1/accounts/me`, `GET/POST /v1/profiles`, `PUT /v1/profiles/{id}`, `GET/POST /v1/devices`, `DELETE /v1/devices/{id}`, `POST /internal/v1/member/eligibility` (member.internal scope). OpenAPI member.yaml specifies records, validation, state errors and internal scope.

## Events Added/Changed

`member.device-revoked.v1` actual typed producer; schema registered before use. No claims of future service consumers being deployed.

## Database Changes

V2 account/profile/device with FK/check/unique/indexes; V3 outbox claim/retry columns+index. No shared data/DB reads. V1 preserved.

## Security

JWT ownership, internal scopes, safe consistent Problem Details+trace when available; bounded JSON bodies including chunked; server validation for age/locale/codec/DRM. Wrong-account IDs404; cannot re-register revoked installation. Signed auth_time required for weaker parental controls; missing/stale future auth time denies. Account passwords/MFA/logout remain IdP-owned.

## Observability

Profile creation, device deny/revoke and outbox success/failure counters with bounded labels; structured logs, traces, DB readiness and protected metrics inherited. No PII in application errors/events (pseudonymous UUID refs only).

## Verification

PASS:
- Full reactor `mvn -Pintegration verify`: 5 unit/contract +23 integration tests, zero failures/errors/skips; Java21 and Checkstyle.
- Concurrent provision creates1 account; issuer collision isolated; profile cap5 and active device cap10 under real PG parallel requests.
- Cross-account access, stale versions, invalid kids rating, reauth failure/success, adult-profile bypass prevention.
- Duplicate device request/fingerprint conflict, persistent revoke+exactly1 outbox insertion, revoked eligibility.
- Authenticated API journey, invalid body400/code, internal scope403, oversized JSON413.
- Real PostgreSQL→Kafka→SchemaRegistry Avro round trip, account partition key/device version/framing correct, ack marks published; already-published row not reattempted.
- Foundation cryptographic/probe/transaction/Kafka/Redis regression suite continues passing.

FAIL:
- None outstanding. API validation initially used Spring's default Problem Details without domain code; ordered owner advice fixed and tested. Registry HTTP2 upgrade incompatibility produced EOF; HTTP1.1 corrected and tested.

NOT RUN:
- Browser signup/PKCE redirect and IdP MFA/logout workflow; remote CI; commercial IdP policy certification; full downstream playback consumer (phase4); production rollout/load/chaos.

## Remaining Work

Proceed Phase2 catalog/rights. Member initial increment lacks account erasure/support policies pending later operations/privacy orchestration. No production-readiness claim.

## Risks / Blockers

Production guardian UX must request fresh IdP authentication (max_age) and signed auth_time. Local insecure Kafka/registry ports bindlocalhost; production workload identities/ACL/TLS later infrastructure. No blocker to catalog increment.
