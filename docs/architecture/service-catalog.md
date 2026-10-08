# Service catalog

All are proposed target services, not claims of deployed code. Java 21 + Spring Boot MVC; JDBC/Flyway; stateless replicas with bounded pools. Every PostgreSQL owner has its own database credentials and migrations. Kafka projections are eventually consistent; owner commands/reads use local ACID. No cross-service database joins.

## member

- Business responsibility / bounded contexts: Account/profile/device.
- Owned data: account, profile, device.
- Primary APIs: /v1/accounts/me; /v1/profiles; /v1/devices.
- Consumed APIs: OIDC JWKS.
- Published events: member.device-revoked.v1.
- Consumed events: none.
- Dependencies: IdP; PostgreSQL.
- Consistency: local ACID; asynchronous projections eventual and version guarded.
- Transaction boundary: account provisioning and device/profile changes.
- Availability requirement / SLO: 99.95%; p95 200ms; see observability for window/budget.
- Scaling: independent horizontal replicas; DB pool budget per replica; bounded request concurrency; partition-keyed consumers.
- Security classification: private PII.
- Failure behavior: deny unknown/revoked subjects/devices.
- Technology: Java 21, Spring Boot MVC; Spring Security OIDC resource server.
- Data store: owner PostgreSQL; additional projections as dependencies above.
- Caching strategy: none initially; cache is not business source of truth.

## content

- Business responsibility / bounded contexts: Catalog/rights/publishing/live.
- Owned data: content_item, version, rights, publication, live_event.
- Primary APIs: /v1/catalog; /internal/v1/rights/check; /v1/live/events.
- Consumed APIs: none; media readiness arrives by Kafka.
- Published events: catalog.published.v1; rights.changed.v1; live.changed.v1.
- Consumed events: media.ready.v1.
- Dependencies: PostgreSQL; Kafka.
- Consistency: local ACID; asynchronous projections eventual and version guarded.
- Transaction boundary: publish version+outbox; rights grant changes.
- Availability requirement / SLO: 99.9% browse; rights 99.95%; p95 150ms; see observability for window/budget.
- Scaling: independent horizontal replicas; DB pool budget per replica; bounded request concurrency; partition-keyed consumers.
- Security classification: licensed metadata.
- Failure behavior: deny uncertain rights; browse cached metadata.
- Technology: Java 21, Spring Boot MVC; Spring Security OIDC resource server.
- Data store: owner PostgreSQL; additional projections as dependencies above.
- Caching strategy: catalog:{id}:{locale}:{version}, 5m; cache is not business source of truth.

## commerce

- Business responsibility / bounded contexts: Subscription/payment/billing/entitlement.
- Owned data: plan, subscription, order, payment, entitlement, inbox.
- Primary APIs: /v1/subscriptions; /v1/payments; /v1/webhooks/payment; /internal/v1/entitlements.
- Consumed APIs: member GET accounts/me on viewer commands (ADR-016); payment provider REST.
- Published events: subscription.changed.v1; entitlement.changed.v1; payment.failed.v1.
- Consumed events: none.
- Dependencies: PostgreSQL; Kafka; payment provider.
- Consistency: local ACID; asynchronous projections eventual and version guarded.
- Transaction boundary: order state+entitlement+inbox+outbox.
- Availability requirement / SLO: 99.9% transactions; entitlement 99.95%; see observability for window/budget.
- Scaling: independent horizontal replicas; DB pool budget per replica; bounded request concurrency; partition-keyed consumers.
- Security classification: financial; no PAN.
- Failure behavior: ambiguous payment pending reconciliation, never blind retry.
- Technology: Java 21, Spring Boot MVC; Spring Security OIDC resource server.
- Data store: owner PostgreSQL; additional projections as dependencies above.
- Caching strategy: entitlement:{account}, 15s positive, 3s negative; cache is not business source of truth.

## playback

- Business responsibility / bounded contexts: Authorization/session/DRM/offline.
- Owned data: session ledger, authorization audit, offline grant.
- Primary APIs: /v1/playback/sessions; heartbeat; /v1/drm/licenses; /v1/downloads.
- Consumed APIs: parallel member/content/commerce REST; DRM REST.
- Published events: playback.started.v1; playback.ended.v1.
- Consumed events: entitlement.changed.v1; rights.changed.v1; member.device-revoked.v1.
- Dependencies: PostgreSQL; Redis; eligibility owners; signer; DRM.
- Consistency: local ACID; asynchronous projections eventual and version guarded.
- Transaction boundary: durable session/grant; Redis lease atomic at account boundary.
- Availability requirement / SLO: 99.95%; p95 300ms; see observability for window/budget.
- Scaling: independent horizontal replicas; DB pool budget per replica; bounded request concurrency; partition-keyed consumers.
- Security classification: sensitive bearer grants.
- Failure behavior: fail closed; quarantine after lease-store failover.
- Technology: Java 21, Spring Boot MVC; Spring Security OIDC resource server.
- Data store: owner PostgreSQL; additional projections as dependencies above.
- Caching strategy: lease:{account}, 90s plus grant-safe tombstone; cache is not business source of truth.

## media

- Business responsibility / bounded contexts: Ingestion/transcode/package/origin.
- Owned data: job, asset_version, manifest.
- Primary APIs: /v1/media/uploads; /v1/media/jobs.
- Consumed APIs: S3; Temporal; DRM packager.
- Published events: media.ready.v1; media.failed.v1.
- Consumed events: none.
- Dependencies: PostgreSQL; Temporal; S3; workers; Kafka.
- Consistency: local ACID; asynchronous projections eventual and version guarded.
- Transaction boundary: job transition+outbox; idempotent workflow steps.
- Availability requirement / SLO: 99.9% intake; 99% job success excluding invalid source; see observability for window/budget.
- Scaling: independent horizontal replicas; DB pool budget per replica; CPU/IO-isolated media job workers and task queues.
- Security classification: private source media.
- Failure behavior: retain resumable job; never publish partial package.
- Technology: Java 21, Spring Boot MVC; FFmpeg/FFprobe, Temporal SDK.
- Data store: owner PostgreSQL; additional projections as dependencies above.
- Caching strategy: none; cache is not business source of truth.

## experience

- Business responsibility / bounded contexts: Watch state/search/discovery/recommendation.
- Owned data: progress, history, watchlist, reactions, indexed projection, signals.
- Primary APIs: /v1/progress; /v1/watchlist; /v1/search; /v1/home; /v1/recommendations.
- Consumed APIs: member profile ownership REST; content details REST.
- Published events: watch.updated.v1; reaction.changed.v1.
- Consumed events: catalog.published.v1; rights.changed.v1; playback.ended.v1.
- Dependencies: PostgreSQL; Kafka; OpenSearch phase7; Redis later.
- Consistency: local ACID; asynchronous projections eventual and version guarded.
- Transaction boundary: profile/content progress CAS+outbox; per-event projection write.
- Availability requirement / SLO: search 99.9%; recommendations 99.5%; see observability for window/budget.
- Scaling: independent horizontal replicas; DB pool budget per replica; bounded request concurrency; partition-keyed consumers.
- Security classification: viewing behavior.
- Failure behavior: popular fallback; search unavailable returns explicit 503.
- Technology: Java 21, Spring Boot MVC; Spring Security OIDC resource server.
- Data store: owner PostgreSQL; additional projections as dependencies above.
- Caching strategy: trending:{region}:{age}, 60s; cache is not business source of truth.

## engagement

- Business responsibility / bounded contexts: Advertising and notifications.
- Owned data: ad decision/cap/event, preferences, reminder, delivery.
- Primary APIs: /v1/ads/decision; /v1/ads/events; /v1/notification-preferences.
- Consumed APIs: Member profile/account ownership and Content reminder metadata with viewer JWT; Playback ad context and Commerce entitlement with scoped workload JWT; ad and messaging providers REST.
- Published events: ad.impression.v1; notification.delivered.v1.
- Consumed events: payment.failed.v1; live.changed.v1; notification.requested.v1.
- Dependencies: PostgreSQL; Kafka; provider adapters.
- Consistency: local ACID; asynchronous projections eventual and version guarded.
- Transaction boundary: cap+decision local transaction; delivery inbox.
- Availability requirement / SLO: ad decision 99.5%; notification intent 99.9%; see observability for window/budget.
- Scaling: independent horizontal replicas; DB pool budget per replica; bounded request concurrency; partition-keyed consumers.
- Security classification: consent/destination PII.
- Failure behavior: ad timeout skips optional ad; delivery retries asynchronously.
- Technology: Java 21, Spring Boot MVC; Spring Security OIDC resource server.
- Data store: owner PostgreSQL; additional projections as dependencies above.
- Caching strategy: optional cap projection; DB authoritative; cache is not business source of truth.

## insights

- Business responsibility / bounded contexts: consented analytics/QoE and optional experiments.
- Owned data: insights_account consent epochs, telemetry receipts/detail/quota/source causes, experiments, assignments, audit and outbox.
- Primary APIs: `/v1/insights/consent`, `/v1/telemetry/batches`, `/v1/experiments/{id}/assignment`, scoped `/v1/admin/analytics/qoe` and `/v1/admin/insights/experiments`.
- Consumed APIs: Member account/profile/device with viewer JWT; scoped Playback token context for session samples.
- Published events: analytics.received.v1 (account UUID key/aggregate and monotonic owner counter); qoe.received.v1 (session UUID key/aggregate, sample sequence).
- Consumed events: ad.impression.v1, subscription.changed.v1, payment.failed.v1. Other original analytical fan-out is target evolution, not deployed consumers.
- Dependencies: Member and Playback bounded REST; own PostgreSQL; Kafka and Registry. Playback has no reverse dependency.
- Consistency: local ACID per batch/source; owner source ingestion/outbox are asynchronous at least once.
- Transaction boundary: account lock serializes consent, quota and admission; UUID/hash receipts, session/sequence uniqueness and owner semantic causes protect replays. Experiments use config CAS plus audited versions.
- Availability / SLO target: 99.5% telemetry admission; p95 1s; measured scale/SLO is NOT validated.
- Scaling: bounded batch20, 10,000 admitted rows/account/UTC-day; DB pool20; REST bulkhead24, 100ms connect/300ms read/no retries. Thirty-day detail retention deletes bounded batches. Production archive/partition evolution depends on measurements.
- Security classification: restricted pseudonymous activity; UUID event envelopes/session fields remain linkable; HMAC references do not make the stream anonymous. No raw search text, tokens, IPs, names or provider payment references.
- Failure behavior: optional telemetry returns explicit rejection/unavailability; experiments return CONTROL for opt-out, inactive config, missing key or owner unavailability. No effect on playback/payment transactions.
- Technology/store: Java21 Spring MVC, own Flyway/PostgreSQL and typed Avro Kafka outbox. S3 analytical archival is target design and not implemented in this increment.
- Caching: no cache needed for initial configuration workload; authoritative versioned SQL assignments.

## operations

- Business responsibility / bounded contexts: fraud/abuse indicators, support cases and audited administrative owner commands.
- Owned data: operations_account monotonic counter, signal receipts/provenance/quota, support cases/requests, command ledger/claims, append-only audit and outbox.
- Primary APIs: trusted `/internal/v1/operations/signals`; scoped `/v1/support/cases`/account summary/audit; scoped `/v1/admin/cases/{id}/actions`/resolve and command state.
- Consumed APIs: narrowly scoped Member safe summary/security commands and Commerce security commands. Other original support-owner integrations remain target evolution, not implemented synchronous calls.
- Published events: abuse.signal.v1, abuse.action.v1 and audit.recorded.v1. First signal writer is Operations rather than the originally planned Insights classifier; this keeps restricted fraud processing independent of optional analytical consent (ADR025).
- Consumed events: payment.failed.v1, verified typed Commerce account-keyed/versioned source.
- Dependencies: Member and Commerce REST100ms connect/300ms read, bulkhead16; own PostgreSQL, Kafka, Registry.
- Consistency / transaction boundary: account row serializes dedup/quota/case/CAS/command queue/audit/outbox; dispatch calls the owner outside transactions and fences local acknowledgements. Stable operation IDs protect unknown retries at each owner; exhausted unknowns become RECONCILE.
- Availability/SLO target: support99.5%, command completion99.9% within5min where dependencies recover; unmeasured. Never a synchronous Playback dependency.
- Scaling: trusted signal admission1000/account/day, bounded source windows, cases keyset pagination≤100, command claims10/10s, bounded retries5/backoff+jitter, source retention30d batches1000.
- Security classification: restricted account/risk/audit data; separate support.read/support.write, operations.read/operations.write, security.ingest and owner-specific workload authorities. No raw credentials/IP/email/payment references; actor/provenance hashes.
- Failure behavior: heuristics open review cases, never auto-ban; owner4xx→REJECTED, unavailable/uncertain→bounded retry thenRECONCILE. Case resolution blocks unresolved commands. Existing financial invoices survive revocation.
- Technology / store / cache: Java21 Spring MVC, owner PostgreSQL/Flyway, typed Avro outbox. No additional cache required initially. Audit triggers reject application UPDATE/DELETE; privileged DB administration and cloud immutable archival are separate trust/validation boundaries.
