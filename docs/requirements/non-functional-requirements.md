# Non-functional requirements

Targets are proposed engineering assumptions, not measured performance or contractual guarantees.

| ID | Requirement | Design response | Evidence required | Document |
|---|---|---|---|---|
| NFR-001 | Availability and fault isolation | Multi-AZ; capability-specific SLOs; bounded dependency fan-out | SLI success ratio and burn alerts | [design](../architecture/reliability.md) |
| NFR-002 | Scale, flash crowds, throughput and low latency | Separate CDN media load; load shed before pool exhaustion; prewarm live metadata | k6 ramps; authorization latency | [design](../capacity/capacity-plan.md) |
| NFR-003 | Playback start and buffering reduction | Regional CDN; ABR ladder; startup/QoE instrumentation | startup p95; rebuffer ratio | [design](../architecture/media-platform.md) |
| NFR-004 | Durability, consistency and transaction integrity | ACID owner stores; outbox; versioned projections; no 2PC | rollback/duplicate/concurrent integration tests | [design](../architecture/transactions.md) |
| NFR-005 | Idempotency, ordering, duplicates and replay | Request fingerprint; provider inbox; partition ordering; sequence-guarded consumers | replay and out-of-order tests | [design](../architecture/event-architecture.md) |
| NFR-006 | Security, privacy, compliance, residency and auditing | OIDC; ownership/RBAC; encryption; minimization; retention; geography constraints | security negative tests and audit completeness | [design](../security/security-architecture.md) |
| NFR-007 | Observability, SLOs and error budgets | OTel propagation; RED/domain metrics; actionable burn-rate alerts | synthetic probes and dashboard queries | [design](../architecture/observability.md) |
| NFR-008 | Disaster recovery and multi-region evolution | Multi-AZ first; fenced active/passive regional recovery | timed restore and failback evidence | [design](../architecture/multi-region.md) |
| NFR-009 | Caching and database/search scalability | Bounded TTL; owner API miss; stampede protection; index projections | cache outage, stale version and query load tests | [design](../architecture/data-architecture.md) |
| NFR-010 | Backpressure, graceful degradation and overload | Bounded workers; controlled retry/DLQ; no analytics on playback path | dependency failure and saturation tests | [design](../architecture/reliability.md) |
| NFR-011 | Schema evolution and API compatibility | OpenAPI; Avro BACKWARD_TRANSITIVE; additive v1 changes | contract parsing and compatibility CI | [design](../architecture/api-standards.md) |
| NFR-012 | Deployment rollback and maintainability | Expand/contract migrations; immutable images; probes; small modules | Helm lint; rollout and migration tests | [design](../architecture/deployment.md) |
| NFR-013 | Testability, load/chaos validation | Risk-based real infrastructure integration; deterministic synthetic media | unit/integration/E2E/load/chaos suite | [design](../testing/test-strategy.md) |
| NFR-014 | Cost, retention and lifecycle management | CDN bytes dominate; lifecycle old variants; right-size indexes and MSK | monthly unit-cost and retention reports | [design](../capacity/capacity-plan.md) |
| NFR-015 | Accessibility and localization contracts | BCP47 locales; captions/audio-description metadata; no native UI scope | locale and accessibility metadata tests | [design](../architecture/api-standards.md) |
