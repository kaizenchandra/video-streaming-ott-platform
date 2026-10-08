# Reliability and degradation

| Dependency/path | Timeout | Retry | Circuit/bulkhead | Fallback / load shedding / backpressure |
|---|---|---|---|---|
| IdP JWKS | connect1s/read2s; bounded refresh | 1 jittered fetch; cached valid keys only | 4 concurrent refresh; open30s after failures | cached known unexpired key; unknown kid fails; authentication admission rate limit |
| Playback owner REST | connect30ms/read120ms; total250ms | none during user start (safe fresh retry by client with key) | per-owner max64 in-flight; open5s on50% of20 failures | deny uncertain eligibility; 503 Retry-After; bounded queue≤64 |
| Redis lease | 30ms | no acquire retry without session-ID semantics | max64 commands; failover fencing | deny on uncertain result; reconcile orphan reservation |
| PostgreSQL | statement2s; lock500ms; pool acquisition1s | bounded transaction retry only known serialization/conflict-safe op, max2 jittered | pool20/replica; DB connection budget | no fake persistence success; 503/409; reject at pool saturation |
| Payment create/status | connect1s/read3s | create only provider-guaranteed key; timeout→reconcile, status GET max2 | per-provider max16; open30s | pending reconciliation; webhook provider retries after failed commit |
| DRM/signing | authorization50ms budget; external license max2s | none for uncertain license issue | max32 provider calls; open10s | fail closed; no clear-content fallback |
| Kafka producer | delivery deadline10s | idempotent broker retries within deadline | bounded relay batch100; bounded buffer | outbox persists; alerts age>60s; shed noncritical events before DB growth exhaustion |
| Search | connect100ms/read500ms | max1 safe read jitter | max32; open10s | explicit unavailable search; home popular cached fallback |
| Notification/ad providers | connect500ms/read2s; ad decision100ms | notification max5 idempotent attempts; ads no retry on critical start | provider-isolated worker pools | notification DLQ; optional ad skipped; payment remains committed |
| Temporal/S3/media | intake3s; activity-specific duration deadline | transient activity max3 exponential+jitter; deterministic keys | max2 transcodes/worker, bounded task queue | resumable job; no partial publication |
| Telemetry | admission1s | bounded client retry with eventId | payload/batch cap; queue cap | 429; sample/drop explicitly measured; playback unaffected |

Client retries always carry idempotency keys and honor Retry-After. Retry budget ≤10% original request load; no layered retry multiplication. Chaos plan includes partitions/timeouts/DB loss/Redis restart/Kafka stop/poison payload/provider malformed response. Only executed fault injections count as evidence.

Graceful shutdown stops admission, drains requests within30s, finishes/rolls back transactions, stops outbox claims, releases only owned leases when grants permit. Readiness includes critical dependency connectivity; liveness is process health and must not restart entire fleet on downstream outage. Noncritical dependency health cannot make playback unready.
