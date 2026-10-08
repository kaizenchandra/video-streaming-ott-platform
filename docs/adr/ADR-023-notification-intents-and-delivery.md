# ADR-023: Owned notification preferences and asynchronous delivery intents

Status: Accepted Phase 11 design; implementation follows the Phase 10 gate.

## Context
FR-030/031 require notification preferences, channel boundaries, content/live reminders and payment-failure notices. Payment, playback and publication cannot wait for message delivery. No commercial messaging credentials are supplied. Destination ownership and privacy cannot be inferred from arbitrary user input.

## Decision
Engagement owns account/channel preferences, reminder subscriptions, durable notification intents and delivery state. Member resolves authenticated account ownership; Content validates published content/live references through scoped APIs. In-app is a durable owned inbox; external email/SMS/push adapters require verified recipient references and explicit opt-in. External destination registration must be proven by the identity/contact provider, never a self-asserted verified Boolean. Email and phone derive only from verified signed OIDC claims; push routing requires a configured signed contact-provider reference/verified claim. Route values are encrypted with an environment-injected AES-GCM key and never appear in public events. Account-level reminders return generic template/target references and do not grant content access. Local external adapters simulate delivery only to explicitly local fixtures; they never send real messages or report commercial delivery as exercised.

Viewer reminder subscriptions use an idempotency key and unique account/content-or-live/channel target. A caller can schedule a content reminder within a bounded future interval. Live start notices follow source event version/state; ended/cancelled/archive events cannot recreate a late start reminder. Reminders expire, are cancellable, and respect current preferences at dispatch. Payment failure consumes the existing account-keyed/account-versioned typed Commerce event (order ID is a payload reference) asynchronously and creates one intent per order/template/account/channel. Event identity/hash collision and semantic intent uniqueness prevent replayed fan-out. Stale events are rejected/ignored under a documented retention window; no notification is part of a payment transaction.

Intent, inbox/dedup and requested event commit locally. The typed owner-requested Kafka event activates the saved intent after validating its account/template/identity; no foreign event can invent an intent. Broker outage delays notifications. Workers claim bounded batches with expiring fencing tokens, call providers outside transactions, and persist delivery/outbox only for the current claim. A stable intent/channel ID is the provider idempotency key. Providers without idempotency support are not blindly retried after an unknown outcome; such deliveries enter reconciliation. Retries are bounded/exponential/jittered, preferences are rechecked before dispatch (already dispatched provider operations may finish), poison input is parked, and operator-visible failure never rolls back the source business action. The delivered event means this adapter accepted delivery/in-app insertion, not proof an external recipient read a message.

## Alternatives
Synchronous notifications in payment/playback reduce core availability. Raw destination strings in Kafka leak PII and lack ownership proof. Retrying an unknown non-idempotent provider send risks duplicate messages. A Redis-only reminder queue loses durable intent after restart.

## Rationale
Owner-local PostgreSQL intents and existing Kafka/outbox are sufficient for this initial workload. A new Temporal workflow for each short notification is not justified. Destination references keep public events pseudonymous while a provider/contact vault owns sensitive routing.

## Consequences
Commercial provider acceptance, deliverability, rate limits, opt-out propagation and contact verification require environment-specific integrations. Transactional in-app notices can operate without external credentials. Optional reminder delivery may be skipped when current policy or preference denies it; the skip is explicit, not fake delivery.

## Operational Impact
Monitor intent age, due reminder backlog, retry/unknown/final-failure counts, opt-out skips and DLQ. Restrict workload scopes and provider secrets per owner; broker topics use owner producer ACLs. Test duplicate/stale/versioned live events, concurrent reminder commands, consent changes before dispatch, timeout/unknown provider outcomes, fencing after worker restart, and delivery-outbox rollback with actual PostgreSQL/Kafka and local providers.
