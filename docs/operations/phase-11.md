# PHASE: 11 — Notifications

## Implemented
Account-owned channel preferences, verified contact registration from signed identity claims, AES-GCM encrypted routing references, idempotent/cancellable content/live reminders, requested/delivered typed outbox, replay-safe payment/live consumers, versioned live-notice projection, bounded follower/schedule batches, fenced asynchronous delivery and an owned in-app inbox/read state. Source requested events activate saved intents; foreign events cannot invent them. Safe delivery-status API exposes failures/reconciliation without recipient/provider receipt disclosure.

## Architecture Decisions
[ADR-023](../adr/ADR-023-notification-intents-and-delivery.md). Engagement consumes existing account-keyed/account-versioned Commerce failures (order ID is a payload reference), event-keyed Content live lifecycle, and account-keyed owner notification requests. All source/intents commit locally; Kafka outage delays notifications and does not roll back payments or playback. Claims/provider calls are separate transactions. Unknown outcomes from non-idempotent providers become RECONCILE; idempotent providers get at most five attempts with bounded exponential jitter. Already dispatched external operations may finish after opt-out; queued/unclaimed work is cancelled. In-app insertion/delivery/outbox are one transaction.

## Requirements Covered
FR-030/031 local baseline. Default in-app on, email/SMS/push off. Generic account-level templates/target references do not grant content rights. Live start follows actual versioned lifecycle, rescheduling updates the due window, ended/cancelled state suppresses pending delivery, and late/stale start events cannot revive notices. At most 100 waiting/requested reminders/account; source fan-out handles 100 then fresh scheduler batches of 20. Actual flash-crowd delivery capacity is unmeasured.

## APIs Added/Changed
Notification preference GET/PUT, verified-contact POST/DELETE, reminder POST/GET/DELETE, inbox GET/read PUT and delivery-state GET. See [Engagement contract](../../contracts/openapi/engagement.yaml). Live due time comes from Content; content due time is future ≤30 days. External contact registration accepts no self-asserted destination/verified flag: email/phone use verified signed OIDC claims; push requires configured trusted contact-provider reference/verified claims.

## Events Added/Changed
Existing notification.requested.v1/delivered.v1 first producer, intent aggregate/version 1/2 with account partition key. Typed source Registry decoding validates owner/type/key/aggregate/version and UUID/hash receipts. Malformed/exhausted payment/live/request records park in per-topic DLQs. No breaking Avro change. Delivered means durable in-app insertion or provider adapter acceptance, not recipient reading or commercial deliverability.

## Database Changes
Engagement V2 adds preference/contact, reminder/due index, live-notice version/time projection, source receipt, unique semantic intent/claim index and stable inbox indexes. Route ciphertext binds account/channel, never appears in Kafka. No applied migration rewrite. The local key is stable in ignored 0600 configuration; production key rotation requires a versioned re-encryption/key-management procedure rather than replacing it in place.

## Security
Independent OIDC resource validation and Member account ownership; Content published/open target checks with viewer token. Encrypted recipient references and verified identity claims; no raw recipient/token/body/secret logging. External provider production HTTPS, injected secret, stable intent idempotency key and ≤64-KiB response. In-app/read/delivery APIs enforce account predicates. Commercial provider/contact-verification configuration is not exercised; tests send only to local HTTP fixtures and never send actual email/SMS/push.

## Observability
Request template, outcome state, retry, contact failure and outbox metrics; no account/recipient labels. Provider connect/read 100/300 ms, 16-call bulkhead, bounded worker batches/10-second claims. Preference/expiry and live state are checked before dispatch. Metrics represent attempts/adapter observations; durable delivery ledger and emitted events are the delivery truth. Reconciliation states remain explicit and require provider evidence before resolving unknown external outcomes.

## Verification
PASS:
- Complete repository regression: 148 tests, zero failures/errors/skips, compilation/Checkstyle/contracts and seven-owner journey. Evidence /private/tmp/ott-phase11-full.log (3:24).
- NotificationsIT (10 in full run), real PostgreSQL: verified/encrypted routing, preference versions/opt-out, source UUID/semantic/stale replay, reminder keys/due/cancellation/expiry, owned stable inbox/read, live reschedule/end/version collision and late replay, idempotent retry exhaustion/non-idempotent unknown/abandoned dispatch, atomic in-app/outbox rollback, foreign intent rejection, 105 followers across batches and signed HTTP contact claims instead of client Boolean.
- MessageProviderTest (2): authenticated encryption/account-channel binding/missing key; actual local HTTP idempotency, accepted/rejected/timeout-unknown outcomes and unconfigured-provider rejection.
- Seven-owner JourneyIT: actual Content live.changed → Engagement requested outbox/Kafka → activation/worker → owned in-app LIVE_STARTED notice. Actual verified failed renewal webhook → Commerce outbox/Kafka → PAYMENT_FAILED notice; paid subscription remains active. Foreign account inbox is empty. Existing advertising/media/search/VOD/live/progress path still passes.
- Final focused NotificationsIT (11), AdsIT (10) and provider/contract checks pass after route isolation fix; corrupt ciphertext fails EMAIL while IN_APP completes, with no provider call. Evidence /private/tmp/ott-notify-contact-isolation.log. Full regression was before this final isolated change; no claim of a 149-test full run.
- Advertising (10 integration +2 provider tests), all earlier owner suites and local synthetic media pipeline regressions.

FAIL:
- None in the full regression. An initial scheduler placeholder mismatch was corrected. Notification consumer originally assumed an order aggregate; actual Commerce uses account key/aggregate and account version. Consumer and documentation now preserve that contract.

NOT RUN:
- Real commercial messages, provider deliverability/reconciliation, verified push registration flow, key rotation, high-volume fan-out, cloud ACL/TLS/retention/residency and regional DR.

## Remaining Work
Phase 12 analytics/experiments and later operations/infrastructure/hardening/DR. Retention/privacy erasure and supported operational reconciliation are tracked for later phases. In-flight external sends cannot be recalled by preference change. No self-asserted recipient is accepted, no fake commercial delivery succeeds, and no source transaction depends on this owner.

## Risks / Blockers
Phase gate PASS after full regression and final focused route-isolation checks. External provider credentials are absent; IN_APP operates without them. Unknown non-idempotent sends remain RECONCILE, never blind retry or fake success. A damaged ciphertext/key must fail only that route, allowing unrelated in-app jobs to continue. Local one-key contact encryption is not a validated production rotation strategy.
