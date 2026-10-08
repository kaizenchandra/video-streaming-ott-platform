# Transactions, subscription and payment correctness

Subscription state: PENDING_PAYMENT→ACTIVE→EXPIRED/CANCELLED; failed payment may remain retryable only with a new provider attempt. Payment: CREATED→PENDING→SUCCEEDED/FAILED; REFUNDED is a later terminal financial reversal. Unknown provider outcome remains PENDING_RECONCILIATION. No optimistic success and no blind retry. Price/currency/provider reference are server-owned snapshots.

Create order: ownership/auth → idempotency reservation+order commit → provider create with stable provider idempotency key outside DB transaction → persist outcome. If response times out, query provider by reference/reconcile. Duplicate request payload returns order; different payload under same key conflicts.

Webhook: verify raw-body signature in constant time and provider timestamp within5m before parse. Match provider event/order/payment identity, exact amount/currency, allowed transition. Local ACID locks order, records UNIQUE(provider,eventId), changes payment/subscription/entitlement, appends audit/outbox. Duplicate same event is acknowledged without reactivating; same ID/different body is quarantined. Late success after refund cannot resurrect access. Fraud/manual cancellation checks precede activation. Acknowledge only after commit. Provider retry handles DB downtime; durable reconciliation catches missed webhooks.

Entitlement is owned by commerce and updated in the same transaction as paid activation; playback event-driven cache is bounded by actual expiry, revocation check fail-closed. Entitlement consumer applies increasing version. No transaction spans commerce and playback. Refund compensation revokes entitlement and requests provider refund with its own key; irreversible errors go to reconciliation case, not automatic loops.

Catalog publication transaction writes immutable version+rights snapshot+outbox. Search indexes confirmed published versions with external version guard; Kafka duplicate never regresses index. Media ready is a prerequisite event, not a cross-service DB transaction.

Watch progress: session epoch assigned server-side, client sequence increases per session, unique observation ID; CAS `(epoch,sequence)` prevents late device rollback but allows newer intentional rewinds. History dedupe independent of progress. Concurrency locks only per profile/content, never global.

Playback: eligibility outside lock with bounded freshness; short account-quota transaction reserves durable capacity, then account-scoped atomic Redis lease outside transaction, then conditional activation/signing; signing failure releases reservation. Grant expiration ≤lease expiration and ≤rights/entitlement. Quarantine/reconcile on Redis failover protects still-valid issued grants. Heartbeat rechecks revocation and rejects expired grant/session. No cross-service 2PC. See playback architecture for the coordination crash cases.

Initial implementation uses one configured provider/environment and prepaid same-plan renewals. Subscription row owns expiry and derived entitlement policy; invoice table is a paid receipt, not certified tax calculation. Provider lookup resolves only matched pending reference, never retries charge or invents success. Terminal conflicting outcomes require provider-led reconciliation. Recurring/refund/proration commercial policies remain launch integrations.
