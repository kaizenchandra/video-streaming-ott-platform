# PHASE: 10 — Advertising foundation

## Implemented
Engagement owner with PostgreSQL/Flyway, OIDC resource security, observability, typed outbox and scoped owner HTTP. Versioned/audited contextual campaigns support pre/mid/post-roll, territory, maturity, validity and daily account caps. Kids excluded, ad-free/inactive tiers skip, and region comes from saved geo-validated Playback context. CSAI metadata and bounded optional external SSAI HTTP adapter. Durable pending reservations and stable provider idempotency/fencing; issued opportunities count despite absent impression reports. UUID/hash and decision/quartile deduplication, plausible elapsed time, out-of-order observations and local measurement/outbox atomicity.

## Architecture Decisions
[ADR-022](../adr/ADR-022-ad-decision-and-measurement.md). Engagement → Member/Playback/Commerce; no inverse Playback dependency. Provider call outside transactions. Separate Playback ad-context endpoint preserves generic/offline contract. Current configuration is checked again at activation; withdrawn campaigns cannot issue a pending decision. Creative/provider host validation, bounded responses, concurrency and deadlines. At most 100 enabled campaigns per territory/placement, 1000 reserved requests/account/UTC day; configurable commercial scale requires measurement.

## Requirements Covered
FR-028/029 local baseline. Contextual targeting only; no behavioral profiling. CSAI does not enforce ad watching. External stitcher media production, ad exchanges, provider/player delivery, anti-fraud and commercial billing are not claimed verified.

## APIs Added/Changed
Campaign admin POST/GET/PUT under ads.write, POST /v1/ads/decision and /v1/ads/events. New scoped /internal/v1/playback/ads/context with exact supplied token expiry and saved country. See [Engagement](../../contracts/openapi/engagement.yaml) and [Playback](../../contracts/openapi/playback.yaml). Provider failures explicitly return optional-ad skip; owner/DB failure returns 503 for client skip handling.

## Events Added/Changed
First Engagement producer of existing ad.impression.v1. Key/aggregate decision ID; version follows newly accepted semantic observations. quartile 0 impression, 1–4 quartiles. No breaking Avro change. Events are client claims, not proof of billing/viewing.

## Database Changes
Engagement V1 adds account lock, campaign/config audit, reservation/decision, receipt hash, semantic event and outbox/indexes. One owner DB; no shared schema. Provider results activate only under current claim and configuration. Existing applied migrations unchanged.

## Security
Independent issuer/audience/time/subject JWT validation. Owned Member profile, scoped Playback token context and Commerce entitlement. Kids exclusion, current maturity/territory, creative HTTPS host allowlist, production provider HTTPS and injected secrets. Operator audit hashes actor; no token/body/destination/secret logging. External URLs are direct media references; business services never fetch/proxy ads. Provider fixture is local HTTP only; no real messages or commercial ad calls were sent.

## Observability
HTTP/JVM/traces and ad decision reason/quartile/outbox metrics. Provider bulkhead 16, owner bulkhead 24; 100/300-ms connect/read limits, 64-KiB SSAI response. No measured latency or throughput claim.

## Verification
PASS:
- AdsIT (10), real PostgreSQL: competing caps; omitted-impression cap; idempotent request/body conflict; kids/tier/geo/maturity exclusion; provider failure releases only unissued slot; provider calls outside transactions and pending fencing; campaign disable during call; concurrent receipt collision; event duplicate/order/timing/ownership; atomic outbox rollback; admin scope/config version/creative host.
- AdProviderTest (2): real local HTTP idempotency/secret/binding, validated creative host/duration, bounded timeout; CSAI/unconfigured SSAI/production HTTPS/URI restrictions.
- PlaybackIT (16), real PostgreSQL/Redis: existing quota/token/live/offline suite with unchanged generic context.
- Seven-owner JourneyIT (1): actual member/profile/catalog/media/payment/entitlement/search/VOD/live/progress plus ad-supported plan, current owner HTTP ad decision, duplicate impression receipt and Registry/Kafka typed ad event with decision key.
- Affected reactor compile, Checkstyle, unit checks and all OpenAPI/Avro/Protobuf contracts. Evidence /private/tmp/ott-ad-compatible-final.log (1:07).
- Architecture link/ownership/acyclic dependency check, Python bootstrap/launcher compilation, Compose engagement profile validation and actual local Keycloak scoped client provisioning (playback.internal/commerce.internal).

FAIL:
- None remaining. Initial test synchronization retained old semaphore permits; fixture reset corrected. First integrated Kafka check exposed a copied profile-key mapping; relay now uses decision aggregate key and Engagement metrics.

NOT RUN:
- Full repository integration run after Phase 10 (Phase 9 full regression passed 124 tests); affected tests/integrated journey passed.
- Commercial SSAI stitching/media delivery, viewability/fraud/billing assurance, cloud broker ACL/TLS, load, segment/client enforcement or DR.

## Remaining Work
Phase 11 notifications, then analytics/operations/infrastructure/hardening/DR. Retention and privacy erasure are tracked for later phases. Configure a real idempotent SSAI provider before enabling that adapter; no blind provider retries. Local CSAI URL is explicitly a fixture host, not a working commercial creative.

## Risks / Blockers
No remaining local phase correctness blocker. Daily cap measures granted opportunities by UTC reservation day. Duplicate no-fill requests may become eligible later; only stored decisions have durable key receipts. Provider accepted metadata is not proof of stitched bytes. Existing CDN/DRM remain explicit local mock boundaries. Saved short token validity bounds revocation; ongoing core playback/heartbeat policy remains separate from optional ads.
