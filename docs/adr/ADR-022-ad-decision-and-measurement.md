# ADR-022: Optional ad decisions with durable caps and bounded measurement

Status: Accepted Phase 10 design.

## Context
FR-028/029 require ad-supported tiers, pre/mid/post-roll, targeting adapters, caps, impressions and quartiles. Engagement owns advertising; Playback cannot depend on ad delivery or analytics. Commercial ad exchange and SSAI credentials are unavailable. Client-side reports are not proof of billable viewing.

## Decision
An authenticated viewer requests a decision tied to a current device-bound Playback token. Engagement verifies token context through scoped Playback API, owned Member profile and current Commerce entitlement with bounded HTTP deadlines. Ad-free/inactive tiers and kids profiles receive an explicit no-ad result. Targeting starts contextual (region, placement and maturity), with no behavioral targeting or cross-profile activity inference. Current plan eligibility is reevaluated even on an idempotent decision retry.

Campaigns are versioned operator configuration with validity windows, territory, creative maturity, placement, daily account cap, HTTPS creative location and bounded duration. Only administrator-configured allowlisted creative/provider hosts are permitted; business services never fetch/proxy creative media. CSAI returns metadata for direct media access. An optional HTTP SSAI adapter uses a stable decision identifier as provider idempotency key, short deadline and validated response metadata; an unconfigured/failing provider explicitly skips optional ads. This adapter authorizes an external stitcher; it does not claim this Java service stitches media.

A request first creates a durable PENDING decision and reserves one account/campaign/UTC-day slot under an account lock. Provider calls run outside transactions. Only validated adapter success activates the decision; failure or expiry releases an unissued reservation. Issued decisions continue to count against the cap even without a reported impression, preventing client omission from bypassing it. Duplicate keys return the same live decision; changed request conflicts. Issued lifetime is bounded by playback grant, campaign window and five minutes. Daily caps therefore bound granted opportunities, not impressions.

Impression/quartile ingestion requires current viewer/device token ownership of the same saved session/decision. Exactly one semantic impression or quartile per decision is stored; a receipt UUID with changed body conflicts. Out-of-order valid quartiles are tolerated as independent observations, while reports claiming impossible elapsed quartile times are rejected. Receipt, semantic event and typed outbox commit together. A generated monotonic aggregate version follows accepted events; aggregate is decision ID. Reports are pseudonymous client claims, not billing evidence. Retention and fraud review remain later hardening work.

## Alternatives
Redis-only caps lose durable correctness after cache failure. Incrementing only client impressions lets clients bypass caps. Calling the ad provider inside a DB transaction prolongs locks. Requiring ads synchronously in Playback couples core availability to optional delivery. Behavioral targeting by default creates an unsupported consent assumption.

## Rationale
A local ACID reservation/decision ledger and bounded provider boundary reuse the existing architecture without cross-owner transactions. Small caps and decision windows permit PostgreSQL account locks for the initial measured workload; cache optimization must preserve owner authority.

## Consequences
CSAI is advisory client metadata and does not prevent ad skipping; commercial billing/anti-fraud and SSAI media verification require provider/player integration. Kids ads are excluded in this baseline. Ads are optional when providers fail; no fake impression/fill is recorded. Provider idempotency support is a configuration prerequisite for retries; no blind stitch retries occur.

## Operational Impact
Monitor decision latency, eligible/no-fill/provider-skipped outcomes, pending reservation age, cap denials, duplicate events and outbox backlog. Provision Engagement-only PostgreSQL credentials and workload scopes playback.internal/commerce.internal; Member viewer calls use the viewer token. TLS and host allowlists are required for external providers, with secrets injected outside source. Test cap races, missing provider, malformed/timeout responses, key collisions, quartile replay/order/time and local rollback using real PostgreSQL and HTTP fixtures.
