# Observability and SLO model

OpenTelemetry W3C propagation/tracing, Micrometer RED/JVM/domain metrics→Prometheus/Grafana, OTLP collector→Tempo and structured logs→Loki. Infrastructure enters local compose by profile; no telemetry export defaults to external network. Sampling baseline10%, all error traces with collector tail sampling when available. No token/license/payment payload logging.

Log schema: UTC timestamp, level, service/version/environment, event/code, traceId/spanId/correlationId, safe operation, duration/status; pseudonymous bounded actor reference only where needed. Never raw query/body/auth header, profile name/email/device identifiers or DRM bytes. Metric labels use operation/result/provider class, never account/session/content IDs. Domain counters: webhook duplicates/reconciliation backlog, entitlement lag, playback denied by reason, lease conflicts/quarantine, media failures, outbox oldest age, search lag, telemetry dropped.

All SLOs proposed, rolling30d. Denominator valid authorized requests; policy denial and invalid input counted separately. Dependency errors count against owning endpoint SLI; exclusions documented. Availability budget = (1-target)×43,200min.

| Capability | SLI | Target | Latency | Error budget/30d |
|---|---|---|---|---|
| Authentication integration | valid JWT/JWKS requests completed without platform5xx | 99.95% | p95≤200ms | 21.6min |
| Catalog | permitted browse completed | 99.9% | p95≤150ms | 43.2min |
| Search | valid queries completed (freshness separately) | 99.9% | p95≤500ms; index lag p99≤60s | 43.2min |
| Subscription | valid commands durably accepted | 99.9% | p95≤500ms excluding provider settlement | 43.2min |
| Payment/webhook | verified events durably recorded; provider outcomes tracked separately | 99.9% | p95≤1s; entitlement lag≤5s | 43.2min |
| Playback authorization | eligible starts successfully authorized | 99.95% | p95≤300ms | 21.6min |
| Recommendations | valid requests return baseline/fallback | 99.5% | p95≤500ms | 216min |

Alerts: burn14.4× over1h AND5m page; burn6× over6h AND30m page; slow burn3× over1d/2h ticket. Min traffic threshold avoids ratio noise; synthetic probes cover low-traffic zones. Error budget exhausted→freeze risky changes except fixes. Dashboards separate user denial/provider availability/service failure, RED, pool pressure, JVM GC, Kafka lag/outbox age, origin/CDN4xx/5xx, startup/rebuffer/bitrate by coarse device/region. Playback/startup end-user metrics require a client probe and are not equated with backend response latency.

Advertising baseline counters: decision reason (filled, tier/kids excluded, capped/no fill, provider skip, not issued), accepted quartile, Engagement outbox success/failure. Provider calls bound connect/read to 100/300 ms, 16-call bulkhead and 64-KiB response. Owner calls use a 24-request bulkhead and per-call 100/300-ms connect/read limits; serial ownership resolution can exceed a single 300-ms deadline. The 99.5% ad-decision availability target remains proposed; p95 latency and real provider performance have not been measured. Optional ad skips are separate from core Playback SLI.

Phase12 Insights exposes consented client cohorts grouped only by bounded coarse hints. `startup_p95_ms` summarizes admitted start samples; `rebuffer_ratio` is summed interval buffer/summed interval played with0 for zero duration; `failure_observation_ratio` is failed/(started+failed) observations, not independently verified attempt failure rate. Other outputs include quality switches, resolution, bitrate and counts. These values are sparse/client-claimed and cannot establish playback/CDN SLOs. `ott.insights.admitted{source=CLIENT|OWNER}` and outbox metrics contain no account/session labels. SQL detail/receipt/source/assignment replay safeguards and asynchronous consumer lag require operational monitoring; Prometheus/collector dashboards are Phase14/15 validation.


## Executed local evidence

All25 manually built owner RestClients bind the owner ObservationRegistry; playback parallel eligibility checks capture that registry's current context. Integration fixtures verify the same W3C trace across four real owner HTTP requests and an authenticated request exports its known trace through actual OTLP without its bearer credential. HTTP server histograms are bounded5ms..3s and metrics remain scope-protected. Async outbox traceId remains optional/null; persisted request-to-event trace causality is not implemented. Production collector/storage/dashboard/alert deployment and sustained SLO/error-budget evidence are NOT RUN; see [Phase15](../operations/phase-15.md).
