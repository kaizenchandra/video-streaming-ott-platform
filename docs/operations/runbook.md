# Operations baseline

Start with README local setup; secrets generated locally. `/actuator/health/liveness` process, `/actuator/health/readiness` admission, `/actuator/prometheus` restricted scrape endpoint. Correlate safe traceId with RED/outbox/lease metrics. Never paste tokens/keys into incident docs.

Payment uncertain: freeze automatic repeated charge; query provider reference, compare expected amount/currency/ledger, reconcile with audited owner command. Outbox stalled: inspect broker availability/schema registration/oldest age; retain data and bounded retries, no destructive reset. DLQ: identify eventId/type/version and redacted reason, patch consumer/contract, replay audited with side-effect suppression. Redis loss: fence grant issuer/quarantine, restore ledger and await old grantTTL before admitting capacity. Rights incident: revoke owner grants, evict cache, wait tokenTTL; emergency CDN denylist for legal takedown.

Deployment: schema expand before traffic, health/readiness, rolling/canary, watch burn. Roll back image only when schema compatible; forward-fix DB. Restore/failover follows multi-region runbook; record actual elapsed time and lost record comparison. Quarterly access/secret reviews and restore drills; incident review ties failures to FR/NFR and regression test.

## Search projection and repair

Enable the Compose `search` and `experience` profiles. Run `bootstrap-local.py`, `bootstrap-experience-client.py`, `bootstrap-topics.py`, and `register-schemas.py` against the task-owned local runtime, then package/launch Experience. Its workload client uses content.internal and playback.internal only. Search endpoints require an owned profile and discovery country. Production profile requires OpenSearch TLS, configured credentials and at least one replica; security-disabled local search is not a production deployment.

If the index is lost, a search.write operator POSTs `/v1/admin/search/rebuild` and polls `/v1/admin/search/rebuild/{id}`. SCANNING means owner snapshots are still being hydrated; INDEXING means the scan checkpoint is complete with pending current documents; COMPLETE means both current stages are caught up. New publications can increase current lag. Replay parked catalog.published.v1.dlq records only after repairing schema/owner failures. The typed consumer and immutable-version guard make duplicates safe. Catalog hydration failure remains durable via the bounded DLQ, not silently discarded. This local index-loss drill is distinct from regional PostgreSQL/Kafka disaster recovery.
