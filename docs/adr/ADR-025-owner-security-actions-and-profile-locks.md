# ADR-025: Owner security actions, profile locks and auditable operations

Status: Accepted Phase13 design; implementation begins after the Phase12 gate.

## Context
FR-002/011/035/036 require effective parental boundaries, entitlement revocation, fraud signals and auditable administration/support. Current profile maturity is enforced, but a shared account JWT can choose another owned adult profile. Commercial IdP/fraud integrations and legal retention policies are not supplied. Operations must not read or mutate other private databases.

## Decision
Add an opt-in account parental PIN using adaptive salted hashing, recent trusted OIDC authentication for configuration and existing-PIN verification for changes. Bounded failed attempts/cooldown persist independently of rejected HTTP transactions. Member issues opaque short-lived profile-selection grants bound to account, adult profile/version, active device and PIN revision; only hashes persist. Configured locked accounts require the grant on playback start, live start, offline authorization and heartbeat. Kid profiles remain constrained by maturity. PIN changes invalidate existing grants. Unconfigured accounts preserve compatibility and explicitly report that selection locking is not enabled. Profile configuration/maturity bypasses must also require the existing PIN where configured. No PIN or selection token is logged or emitted.

Commerce owns explicit immediate subscription cancellation/revocation with account-row transactions, versioned typed events and audit. It does not infer or fake provider refunds; existing paid invoices remain historical financial records. New grants and heartbeats fail closed after revocation, while already signed CDN access expires within the existing grant budget. Offline license checks require current owner state and local revocation; disconnected commercial license enforcement remains a provider boundary and cannot recall delivered clear mock bytes.

Operations is the ninth independent owner for risk/support cases, signal receipts, audited decisions and idempotent command dispatch. Trusted integration signal APIs require narrow ingest authority; representative rules consume event IDs/typed owner signals with bounded time windows and deduplication. Risk scores open cases rather than silently confiscating subscriptions. Account restrictions and entitlement revocations execute through scoped owner APIs, never shared database access. Each command has an operation ID, durable local state, owner idempotency receipt and explicit unknown/reconciliation state; no distributed2PC and no blind retry of irreversible external financial actions. Admin/support read and write authorities are separate; reasons/templates are bounded to avoid accidental PII in cases/audit streams.

Privacy work minimizes local detail, implements bounded retention and local opt-out/deletion barriers where event replays could recreate data. Cross-owner erasure must be staged through owner APIs with durable progress/tombstones; published Kafka, backups, cloud archives and commercial systems require their own deletion/restriction procedures. No complete regulatory-compliance or full-erasure guarantee follows from a local deletion endpoint. Audit access is restricted and append-only for the application role; database administrators remain a privileged trust boundary.

## Alternatives
Shared account JWT plus profile selection alone permits parental bypass. Unsalted PIN hashing is inadequate. Throwing inside the same transaction as failed-attempt counters rolls back lockouts. Central Operations database writes violate ownership. Automatically banning accounts from heuristic client observations creates unjustified false-positive harm. Retrying provider money movement without idempotency/status evidence risks duplicate financial effects.

## Rationale
Owner-local invariants, bounded credential attempts and durable command receipts support meaningful security and operational behavior with the available environment. Explicit opt-in compatibility and commercial integration boundaries avoid unsupported production claims.

## Consequences
Clients need a profile-selection credential when an account enables locking. Account PIN recovery needs a trusted IdP recovery/guardian workflow; no unsafe reset is provided. Local signals are representative integration foundations, not an exercised credential-stuffing/fraud vendor. Immediate cancellation is an explicit access action and does not implement refund policy. Local administrative audit integrity does not survive malicious privileged database administration without external immutable archival.

## Operational Impact
Monitor lockouts, denied grants, case creation, source lag, command reconciliation and owner failures. Exercise PIN brute-force/races, cross-profile/device replay, changed PIN/profile versions, revoked subscriptions/licenses, authorization boundaries, idempotent support commands, audit rollback and real Kafka/Registry signal handling. Regional/cloud privacy and immutable-archive procedures stay separately unvalidated.
