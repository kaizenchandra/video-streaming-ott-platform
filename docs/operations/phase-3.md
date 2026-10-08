# PHASE: 3 — Subscription / Payment / Entitlement

Status: PASS initial prepaid-commerce engineering increment, local gate. Commercial recurring mandates/tax/proration/refunds remain business/provider launch integrations; no commercial settlement/certification claimed.

## Implemented

Configured priced plans, server-owned order snapshots, owner-bound purchase/get/paid receipt, same-plan prepaid renewal and authoritative subscription-derived entitlement. Account ID resolved by bounded authenticated member API, never client body or cross-DB read. Unique idempotency request scope+fingerprint and one unresolved purchase/account. Provider create outside DB transaction. Unknown response remains pending reconciliation; read-only provider lookup resolves matching reference without repeating charge. Signed raw-body webhook activation/decline/replay/collision and receipt/audit/inbox/outbox atomic local transaction. Expired subscription denies access.

## Architecture Decisions

ADR-016 adds commerce→member for viewer commands; internal entitlement reads do not call member, retaining shallow playback dependencies. One configured payment provider/environment initially; local MockPaymentProvider explicit and excluded from prod profile. Local mock provider stores its own pending simulation in memory (restart loses unresolved mock lookup state); authoritative business data persists in PostgreSQL. No charge retries. Subscription row contains initial derived entitlement policy, avoiding duplicate authoritative stores inside the same owner. Paid receipt is not a certified tax invoice.

## Requirements Covered

FR-008 prepaid plans/subscription including ad-supported/offline policy; FR-009 provider/billing/receipt boundary; FR-010 signature/inbox/replay/collision/reconciliation; FR-011 local transactional activation and expiration. Recurring mandates, proration and commercial refund reconciliation stay constrained by A-04/provider integration rather than fabricated behavior.

## APIs Added/Changed

Authenticated GET plans/subscription, POST orders (Idempotency-Key), owner order/receipt/reconcile; commerce.write POST admin plans. `GET /internal/v1/entitlements/{accountId}` commerce.internal scope. `POST /v1/webhooks/payment` does not use viewer JWT and requires timestamp+HMAC over exact raw bytes. commerce.yaml documents all schemas/limits/state/scopes and unconfigured provider failure behavior.

## Events Added/Changed

Actual typed `subscription.changed.v1`, `entitlement.changed.v1`, `payment.failed.v1` producers via durable owner outbox. Kafka/schema outage cannot undo already committed payment/subscription. Old events cannot reactivate a terminal declined order; provider event-ID/different body quarantined.

## Database Changes

Owner V1 plans/commerce_account/order/subscription/provider_inbox/paid receipt/payment_audit/outbox with FK/check/unique/partial indexes; unique unresolved order/account. Each service migration location now `db/{owner}` to avoid classpath collisions in independent-service E2E. Existing SQL checksums preserved by move. Thin module jars + executable `*-exec.jar` allow test-only journey dependencies without a shared business library.

## Security

Member owner resolves account, receipt/order ownership enforced, internal/read/admin scopes, constant-time HMACSHA256, drift≤5min, exact immutable reference/money/currency, strict integer deserialization (fractional minor units rejected). No passwords/cards/provider secrets/license keys committed. Mock provider never reports paid; verified outcome required. Production without a real provider cannot start successfully.

## Observability

Inherited health/RED/structured logs/OTel bridge, outbox success/failure and durable payment audit. More reconciliation backlog/latency dashboards and cloud alerts remain hardening work.

## Verification

PASS:
- Full reactor `mvn -Pintegration verify`:5 unit/contract +50 integration tests =55; zero failures/errors/skips; Java21/Checkstyle.
- Commerce real PG14 tests: parallel idempotency/single attempt, replay/collision, money/currency mismatch, decline→late success deny, unknown outcome, read-only reconciliation, same-plan extension, owner receipt denial, signature/time/raw-byte/route auth, expired access and fractional minor-unit rejection.
- Injected outbox database failure rolls back payment/subscription/receipt/inbox/audit; retry after recovery activates safely.
- Real Kafka/SchemaRegistry event test: settlement commits with unavailable schema; outbox remains pending; register raw schemas then safe retry delivers decoded entitlement/subscription and marks only broker-acknowledged records.
- Independent-service E2E: cryptographic test IdP→member account/profile/device→content draft/readiness/review/publication/browse→commerce plan/order→signed webhook→entitlement; three actual app contexts and separate PG owners. Other viewer cannot read order.
- All previous member/content/foundation regressions pass. Latest commerce OpenAPI parser check executed separately after contract update and passed.

FAIL:
- None outstanding. Journey test generic-response type inference fixed before execution. No known correctness failure carried forward.

NOT RUN:
- Native Keycloak browser PKCE/MFA, commercial provider settlement/refund/tax certification, recurring mandate/proration policies, remote CI, load/chaos/cloud/DR.

## Remaining Work

Proceed Phase4 playback; extend independent journey into actual authorization/progress/resume in dependency order. Production credential/provider and pricing/legal policies needed before commercial launch.

## Risks / Blockers

Mock provider pending state is intentionally local and volatile; production provider must support authoritative idempotency-key lookup/reconciliation and its own webhook protocol. Signed secret scheme here is the local adapter protocol, not a claim of Stripe/Razorpay compatibility. No blocker to local playback increment using validated owner entitlement API.
