# PHASE: 13 — Fraud / Abuse / Administration / Security Lifecycle

## Implemented
Ninth Operations owner: narrow trusted signal admission, bounded receipt/window/provenance/quota, deterministic indicator cases, versioned support requests/CAS/actions, safe owner summaries, durable command claims/fences/idempotency, bounded unknown retries/reconciliation and append-only audited outbox. Commerce typed payment failures feed risk cases. Heuristics never automatically ban users. Member optional adaptive PIN/lockout and bound opaque profile-selection credentials close adult-profile switching for configured locked accounts. Commerce cancellation/security revocation and current-owner offline checks make access lifecycle effective. Local watch/engagement retention is implemented.

## Architecture Decisions
[ADR025](../adr/ADR-025-owner-security-actions-and-profile-locks.md). Owner APIs preserve independent database ownership; dispatch never holds a local transaction across remote calls. Reversible owner commands use stable operation IDs and receipts; no provider refund/money movement is inferred. Source fraud classification belongs to Operations, independently of optional analytics consent. Unconfigured profile locking preserves compatibility and reports disabled; configured adult access requires a profile/device/revision/version grant. Failed PIN attempts commit before rejection. Disconnected commercial DRM remains a provider boundary.

## Requirements Covered
FR002 parental selection/guardian escalation; FR011/016 revocation/current license checks; FR035 local trusted integration/typed payment indicators; FR036 support/admin/audit. Separate local privacy minimization/retention strengthens NFR privacy without claiming complete regulatory erasure/compliance. Credential/geo/stream indicators are reviewable signals, not calibrated detection or commercial provider evidence.

## APIs Added/Changed
[Operations](../../contracts/openapi/operations.yaml), [Member](../../contracts/openapi/member.yaml), [Commerce](../../contracts/openapi/commerce.yaml), [Playback](../../contracts/openapi/playback.yaml). Member parental status/configuration/selection, optional guardian PIN for maturity escalation, selected-profile header on start/live/download/heartbeat; narrow owner security command/summary APIs. Explicit immediate subscription cancel and owner REVOKE/RESTORE. Current offline context validates current member/device/profile/epoch, rights/origin/offline permission and entitlement. Operations restricted cases/summary/audit, command enqueue/read/resolve and trusted signal API.

## Events Added/Changed
Operations first producer for abuse.signal.v1/action.v1/audit.recorded.v1, account key/aggregate and monotonic owner counter. Action only follows a saved APPLIED owner receipt; audit also records queued/retry/rejected/reconcile. Confidence0 means no calibrated statistical confidence. Source payment failures enforce type/producer/key/aggregate/version/UUID/Registry provenance. Subscription changeReason is additive/defaulted PURCHASED; cancellation/revocation/restoration are distinct reasons and restoration does not count as purchase analytics. Actual previous/current subscription schemas register under BACKWARD_TRANSITIVE.

## Database Changes
Member V4/V5: account status/security epoch, salted PIN/persistent lockout, hash-only selection, idempotent security receipt and append-only security audit. Commerce V2/V3: persistent access_revoked flag, receipt/audit; pending payments still invoice without enabling access. Playback V6 records offline owner epoch; old epoch0 credentials require fresh authorization. Operations V1/V2 owns cases/signals/provenance/quota/commands/audit/outbox. Experience V4 and Engagement V3 provide retention indexes/forward migrations. No applied migration rewrite/shared DB access.

## Security
support.read/write, operations.read/write, security.ingest and member.security/commerce.security workload authorities are separate. Five failed PIN attempts block900s; verified selection lasts30min and binds account/profile/device/PIN and profile versions. Current PIN plus recent OIDC authentication is needed for change/disable. Restricted owner actions bump security epoch and invalidate selections. No unsafe PIN recovery, fake refunds, auto-bans or sensitive logging. Audit triggers block ordinary application UPDATE/DELETE; privileged DB administrators/DDL and external immutable archival remain explicit trust boundaries.

## Observability
Bounded signal-kind/case-kind/command-outcome and outbox metrics, durable retry/reconciliation status and restricted audit. Source admission cap1000/account/UTC-day; trusted source age≤1h/future5s. Case/command pagination≤100, claims10/10s, owner REST100/300ms/bulkhead16; retries≤5 with exponential jitter. Watch activity retains180d, recommendation90d, completed engagement30d, security signals30d; cleanup batches≤1000. Unknown financial/delivery outcomes, financial/audit evidence, configured contacts/preferences and unpublished outbox are not blindly deleted.

## Verification
PASS:
- Full repository:175 tests; zero failures/errors/skips; compile/Checkstyle/contracts and nine-owner Journey. Evidence /private/tmp/ott-phase13-full.log,3:58.
- Real Member tests: PIN hashing/token hashing/binding/expiration, persistent rejection lockout/cooldown, maturity bypass rejection, PIN/config/version changes, account restriction replay/collision/epoch and audit.
- Commerce tests: revocation during pending settlement keeps access denied and invoice truthful; duplicate/conflicting command receipts; explicit restore reason; pending cancel rejection, immediate cancel and new-paid-order renewal.
- Playback tests: current owner denial invalidates offline token; existing online/Redis concurrency/failure tests remain passing.
- Seven Operations PostgreSQL tests: source UUID/replay/time/window/distinct geo/quota, review-case dedup, support request/action/CAS, owner calls outside local lock, explicit retry/reconcile/rejection, ack/outbox failure/recovery without repeated owner effect, append-only audit, strict read/write/ingest HTTP and atomic case/audit rollback.
- Retention tests preserve recent watch state and unresolved non-idempotent notification dispatch while pruning eligible old/completed detail.
- Nine-owner Journey: actual signed scoped owner command HTTP/receipt→restriction/restore/revoke/restore, safe case/audit, actual payment-risk consumer, profile credential enforced on live heartbeat, earlier offline license denied after current live association, typed Registry/Kafka signal/action/audit and subscription schema compatibility. Existing media/entitlement/ad/notification/analytics/search/progress journeys pass.
- Architecture graph/links37FR/15NFR/9owners/21topics/10sequences, scripts/Compose Operations config, actual local narrow workload scopes and git diff whitespace.
FAIL: none in final gate; earlier journey fixture requested ordinary offline after live association and correctly failed, corrected to exercise pre-association issuance/current denial.
NOT RUN: commercial IdP/fraud/geo providers; disconnected commercial license revocation; actual refunds; complete cross-owner/Kafka/backups/cloud privacy erasure/key rotation; privileged immutable cloud audit; production load/SLO/DR.

## Remaining Work / Risks
Cloud packaging/infrastructure, hardening and recovery validation follow. PIN locking must be explicitly enabled and clients must carry selection credentials; no claim of guardian age/recovery without trusted IdP procedure. Already signed online access expires within existing≤60s budget; already delivered mock-clear bytes cannot be recalled. Unknown commands remain RECONCILE until supported by owner evidence. Retention and consent are local controls, not a legal erasure guarantee; external backup/archive/financial policy needs authoritative input.

Phase Gate: PASS for implemented local increment; continue Phase14.
