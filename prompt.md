Act as a **Principal Solution Architect, Staff/Principal Backend Engineer, Distributed Systems Architect, Java/Spring Architect, Cloud Architect, DevSecOps/SRE Engineer, Security Architect, and Technical Lead**.

Your task is to **design first and then implement end-to-end a modern Netflix/Hotstar-style OTT video-streaming backend platform**.

You are operating as an autonomous coding agent inside the project repository. Do not merely describe what should be done: **inspect, design, create/update files, implement, test, fix, document, and verify the system within the available environment.**

---

# 1. Non-Negotiable Execution Model

Execute in this order:

```text
STAGE A — ARCHITECTURE & ENGINEERING DESIGN
                  ↓
          ARCHITECTURE GATE
                  ↓
STAGE B — PHASED IMPLEMENTATION
                  ↓
       PRODUCTION HARDENING
```

## Critical rule

**Do not implement production business microservices before the Architecture Gate passes.**

During Stage A you may create:

- architecture documentation
- ADRs
- API/event contracts
- schemas
- repository/module skeletons
- proof-of-concept code strictly required to validate an architectural decision

Architecture must drive implementation.

After the Architecture Gate passes, continue into Stage B automatically. **Do not wait for repeated `NEXT` instructions between normal phases.**

---

# 2. First Action: Inspect Before Changing Anything

Before designing or modifying code:

1. inspect the entire repository structure
2. read `AGENTS.md`, `CLAUDE.md`, `README.md`, contribution guides, architecture docs, and other repository instructions
3. inspect Maven/Gradle configuration and dependency versions
4. inspect existing services, modules, contracts, schemas, infrastructure, CI/CD, tests, and conventions
5. read all supplied:
    - Netflix/reference architecture documents
    - Functional Requirements
    - Non-Functional Requirements
    - related project artifacts

Treat repository-specific instructions and supplied project artifacts as authoritative.

If an existing repository exists:

- preserve its established architecture where reasonable
- preserve unrelated work
- do not rewrite working code unnecessarily
- preserve dependency versions unless a change is justified
- adapt this plan to the repository instead of forcing a new structure
- identify conflicts between requirements and existing architecture explicitly

Never silently ignore a requirement.

---

# 3. Source Priority

When inputs conflict, use this priority:

```text
Explicit user requirement
    ↓
Repository-specific instructions
    ↓
Functional / Non-Functional requirements
    ↓
Existing architecture decisions
    ↓
Reference architecture principles
    ↓
This generic execution prompt
```

Security, correctness, data integrity, and explicit compatibility requirements must not be weakened silently.

---

# 4. Architecture Objective

Design a modern OTT backend supporting, as applicable:

- accounts and authentication
- profiles and parental controls
- device management
- catalog and metadata
- localization
- content rights/licensing
- content ingestion
- transcoding
- packaging
- DRM
- subscription plans
- billing
- payments
- entitlements
- playback authorization
- playback sessions
- concurrent-stream enforcement
- geo restrictions
- adaptive bitrate streaming
- offline-download authorization
- watch history
- watch progress
- continue watching
- watchlists
- ratings/reactions
- discovery
- search
- recommendations
- trending
- live streaming
- sports
- DVR/catch-up/replay
- advertising
- notifications
- content publishing
- moderation
- administration
- customer support
- analytics
- QoE telemetry
- experimentation
- feature flags
- fraud/abuse detection
- rate limiting
- auditing
- integrations
- CDN delivery

No supplied FR may disappear during decomposition.

---

# 5. Architecture-Driving NFRs

Explicitly design for relevant:

- availability
- scalability
- flash crowds
- low latency
- playback-start latency
- buffering reduction
- high throughput
- horizontal scaling
- fault tolerance
- graceful degradation
- durability
- consistency
- idempotency
- event ordering
- duplicate delivery
- transaction integrity
- security
- privacy
- compliance
- auditability
- observability
- SLOs/error budgets
- disaster recovery
- multi-AZ
- evolutionary multi-region
- caching
- database scalability
- search scalability
- backpressure
- schema evolution
- API compatibility
- zero-downtime deployment
- rollback
- maintainability
- testability
- load testing
- chaos testing
- cost optimization
- capacity planning
- retention
- residency
- accessibility
- failure isolation

Every significant architecture decision must map to one or more FRs/NFRs.

---

# 6. Engineering Philosophy

Use pragmatically:

```text
Domain-Driven Design
+
Hexagonal / Ports-and-Adapters Architecture
+
Event-Driven Architecture
+
Pragmatic Microservices
+
Cloud-Native Architecture
```

Avoid:

- microservice-per-entity design
- pattern-driven overengineering
- unnecessary abstractions
- deep synchronous request chains
- shared databases between independent services
- unnecessary polyglot persistence
- premature active-active multi-region
- premature service mesh
- premature ML infrastructure
- distributed 2PC between microservices
- technology choices without workload justification

Use modularity proportional to actual complexity.

---

# 7. Preferred Technology Landscape

Default unless repository constraints or workload analysis justify otherwise:

| Concern | Preferred Technology |
|---|---|
| Primary language | Java 21 |
| Secondary language | Go only with documented justification |
| Backend | Spring Boot |
| Blocking HTTP | Spring MVC by default |
| Reactive | WebFlux only when justified |
| External APIs | REST + OpenAPI |
| Internal RPC | gRPC + Protobuf where justified |
| Async/eventing | Apache Kafka |
| Schema management | Schema Registry + Avro/Protobuf |
| Transactional DB | PostgreSQL |
| High-scale state | DynamoDB / Cassandra / ScyllaDB when justified |
| Cache | Redis Cluster |
| Search | OpenSearch / Elasticsearch |
| Workflow | Temporal |
| Object storage | S3 / GCS |
| Media | FFmpeg/FFprobe |
| Streaming | HLS + MPEG-DASH + CMAF |
| DRM | Widevine + FairPlay + PlayReady adapters |
| Containers | Docker |
| Orchestration | Kubernetes |
| Packaging | Helm |
| GitOps | Argo CD |
| IaC | Terraform |
| Telemetry | OpenTelemetry |
| Metrics | Prometheus |
| Dashboards | Grafana |
| Logs | Loki/OpenSearch |
| Tracing | Tempo/Jaeger |
| Secrets | Vault / AWS Secrets Manager |
| Tests | JUnit 5, Mockito, Testcontainers, REST Assured |
| Contract tests | Pact or Spring Cloud Contract |
| Load tests | k6 or Gatling |

Do **not** use every listed technology automatically.

Every significant technology choice needs a workload-based rationale.

---

# 8. Mandatory Control Plane / Media Data Plane Separation

This distinction is architectural, not optional.

## Control Plane

Responsible for:

- authentication
- authorization
- catalog
- rights
- subscriptions
- entitlements
- device validation
- geo validation
- concurrent-stream control
- playback sessions
- DRM authorization
- playback tokens
- signed CDN authorization

## Media Data Plane

Responsible for:

- HLS/DASH manifests
- video segments
- audio segments
- subtitles
- CDN caching
- origin/object-storage access

**Spring Boot business services must never proxy normal media traffic.**

Target path:

```text
Client
  ↓
Playback Control Plane
  ├─ Entitlement
  ├─ Rights
  ├─ Device
  ├─ Geo
  ├─ Concurrent-stream control
  └─ DRM authorization
  ↓
Short-lived playback authorization
  ↓
Signed CDN manifest/access
  ↓
CDN
  ↓
Client
```

---

# STAGE A — ARCHITECTURE & ENGINEERING DESIGN

# 9. Requirements Package

Create/update:

```text
docs/requirements/
├── functional-requirements.md
├── non-functional-requirements.md
├── assumptions.md
├── constraints.md
└── requirements-traceability-matrix.md
```

The traceability matrix must map:

```text
Requirement
→ Business Capability
→ Bounded Context
→ Service
→ API/Event
→ Persistence
→ Security
→ Test
→ SLI/Metric
```

Identify:

- confirmed requirements
- assumptions
- unresolved ambiguities
- constraints
- deliberate exclusions

Do not invent requirements silently.

---

# 10. System Scope

Explicitly document:

## In scope

At minimum evaluate:

- OTT backend/control plane
- VOD
- live/sports
- subscriptions
- billing/payment
- entitlement
- catalog
- rights
- playback
- DRM integration boundaries
- media processing
- CDN integration
- search/discovery
- recommendations
- analytics/QoE
- advertising
- administration
- infrastructure

## Out of scope

Explicitly record exclusions such as:

- production commercial DRM credentials
- actual commercial sports feeds
- proprietary recommendation ML models
- native TV/mobile clients unless required

Do not leave boundaries implicit.

---

# 11. Domain Decomposition

Evaluate bounded contexts for:

```text
Identity / Account
Profile / Device
Catalog / Metadata / Localization
Rights / Licensing
Subscription
Billing
Payment
Entitlement
Playback
Playback Session
DRM
Watch History
Watch Progress
Watchlist
Search
Discovery
Recommendation / Personalization / Trending
Content Ingestion
Transcoding / Packaging
Media Origin
Live Streaming / Sports
Advertising
Notifications
Analytics / QoE
Experimentation / Feature Management
Fraud / Abuse
Administration
Customer Support
Audit
```

A bounded context does **not** automatically equal one deployable microservice.

Choose service boundaries based on:

- business capability
- ownership
- transaction boundaries
- scaling characteristics
- availability requirements
- security boundaries
- rate of change
- data ownership
- failure isolation
- team operability
- coupling/cohesion

---

# 12. Service Catalog

Create:

```text
docs/architecture/service-catalog.md
```

For every proposed service document:

```text
Service Name
Business Responsibility
Bounded Context
Owned Data
Primary APIs
Consumed APIs
Published Events
Consumed Events
Dependencies
Consistency Model
Transaction Boundary
Availability Requirement
Scaling Characteristic
Security Classification
Failure Behavior
Technology
Data Store
Caching Strategy
SLO
```

Also create a service dependency map.

Flag and remove unjustified circular synchronous dependencies.

---

# 13. C4 + Runtime Architecture

Create/update:

```text
docs/architecture/architecture-overview.md
docs/architecture/domain-model.md
docs/diagrams/
```

Provide Mermaid diagrams where practical.

Required:

### C4 Level 1 — System Context

Include:

- viewers
- content administrators
- support users
- payment providers
- identity provider
- DRM providers
- CDN
- sports/live feeds
- OTT platform

### C4 Level 2 — Containers

Include:

- edge/WAF/CDN
- API Gateway
- backend services
- Kafka
- PostgreSQL
- Redis
- OpenSearch
- Temporal
- S3/object storage
- analytics platform

### C4 Level 3 — Components

At minimum for:

- subscription/payment
- playback
- catalog/rights
- media ingestion
- search
- watch history/progress

---

# 14. Mandatory Sequence Diagrams

Create diagrams for:

1. authentication
2. subscription + payment
3. entitlement activation
4. playback authorization
5. media ingestion/transcoding/packaging/publication
6. search indexing
7. watch-progress processing
8. live-event start
9. payment webhook handling
10. playback session/concurrent-stream race

Show:

- synchronous vs asynchronous boundaries
- timeout boundaries
- transaction boundaries
- outbox/event publication
- idempotency points
- failure handling
- degradation behavior

---

# 15. Data Architecture

Create:

```text
docs/architecture/data-architecture.md
```

For every service/domain define:

- source of truth
- ownership
- schema
- indexes
- transaction boundary
- consistency model
- replication
- partitioning/sharding
- backup/restore
- retention
- archival
- privacy classification

## PostgreSQL

Prefer for transactional business domains such as:

- account/profile
- catalog
- rights
- subscription
- billing
- payments
- entitlement
- administration
- audit metadata

Document:

- important indexes
- isolation levels
- optimistic locking
- justified pessimistic locking
- read replicas
- Flyway migrations
- transaction boundaries

## NoSQL

Use only when justified by access patterns.

Candidate workloads:

- playback session history
- watch progress/history
- high-volume activity state

For every table document:

```text
Partition Key
Sort/Clustering Key
Query Patterns
Expected Cardinality
Consistency
TTL
Hotspot Risk
Retention
```

## Redis

Document separately for each use case:

- entitlement cache
- playback lease
- concurrent-stream control
- rate limiting
- catalog cache
- trending cache

For each define:

```text
Key design
TTL
Invalidation
Failure behavior
Cache-miss behavior
Stampede protection
Source of truth
```

Redis must not silently become an authoritative store unless intentionally designed as one.

---

# 16. Event Architecture

Create:

```text
docs/architecture/event-architecture.md
```

For each Kafka topic/event document:

```text
Topic
Domain Owner
Producer
Consumers
Partition Key
Ordering Boundary
Schema
Compatibility Policy
Retention
Retry Strategy
DLQ Strategy
Replay Strategy
PII Classification
```

Rules:

- use Schema Registry
- prefer Avro or Protobuf
- explicitly version contracts
- ordering exists only within a Kafka partition
- define duplicate handling
- define consumer idempotency
- define poison-message handling
- define replay safety

---

# 17. Transaction Architecture

Never use cross-service distributed 2PC.

Use where appropriate:

- local ACID transaction
- transactional outbox
- inbox/deduplication
- idempotent consumers
- sagas
- compensation
- optimistic locking
- distributed leases

Explicitly design correctness for:

- payment webhooks
- subscription activation
- entitlement creation
- concurrent playback
- catalog publication
- search synchronization
- watch-progress events

Never retry payments blindly.

---

# 18. API & Communication Architecture

Create:

```text
docs/architecture/api-standards.md
```

Define:

- REST conventions
- OpenAPI
- resource naming
- validation
- pagination
- filtering
- sorting
- error model
- correlation/trace IDs
- idempotency keys
- authentication
- authorization
- API versioning/lifecycle
- backward compatibility
- deprecation

Prefer RFC 7807/Problem Details style errors.

Representative domain codes:

```text
SUBSCRIPTION_INACTIVE
CONTENT_NOT_AVAILABLE
CONTENT_RIGHTS_EXPIRED
REGION_NOT_ALLOWED
CONCURRENT_STREAM_LIMIT_REACHED
DEVICE_NOT_ALLOWED
DRM_LICENSE_DENIED
PAYMENT_DECLINED
IDEMPOTENCY_CONFLICT
```

For every important service interaction explicitly choose:

```text
REST
gRPC
or
Kafka
```

Guidance:

- REST/OpenAPI: external/public contracts
- gRPC/Protobuf: justified low-latency internal request/response
- Kafka: asynchronous workflow, integration events, fan-out

Avoid deep synchronous chains.

---

# 19. Playback Architecture

Treat playback authorization as a critical path.

Design:

```text
Client
→ Gateway
→ Playback
→ Entitlement
→ Rights
→ Device
→ Geo
→ Concurrent Stream Control
→ DRM Authorization
→ Short-lived Playback Token
→ Signed CDN Access
```

Cover:

- fail-closed authorization
- entitlement caching
- short-lived tokens
- replay resistance
- concurrent-stream race handling
- Redis lease atomicity
- session heartbeat
- expiration
- abnormal client termination
- CDN signing
- device binding
- geo enforcement
- degraded dependencies
- latency budget

Recommendations, analytics, notifications, experimentation, and other noncritical systems must not be synchronous playback dependencies.

---

# 20. Media Platform

Create:

```text
docs/architecture/media-platform.md
```

Design:

```text
Upload
→ Validation
→ Temporal Workflow
→ FFprobe
→ Transcoding
→ ABR Ladder
→ Packaging
→ HLS/DASH/CMAF
→ DRM Boundary
→ Quality Validation
→ Object Storage Origin
→ CDN
```

Cover:

- H.264
- HEVC where justified
- AV1 where supported
- audio tracks
- subtitles
- thumbnails
- content versioning
- retry/recovery
- workflow idempotency
- publication state machine

Do not hard-code a universal encoding ladder.

Use deterministic synthetic media for integration testing.

---

# 21. DRM

Design provider boundaries for:

- Widevine
- FairPlay
- PlayReady

For local/dev implementation use:

```text
MockDrmProvider
```

Production architecture must cover:

- license authorization
- entitlement checks
- device binding
- key IDs
- expiration
- offline licenses
- revocation
- KMS/HSM boundaries
- auditability

Never place real DRM keys in source control.

---

# 22. Live Streaming / Sports

Create:

```text
docs/architecture/live-streaming.md
```

Cover:

- source feeds
- encoder
- transcoder
- packager
- origin
- CDN
- entitlement
- authorization
- schedules/event state
- DVR
- catch-up
- replay
- subtitles
- alternate commentary
- highlights
- flash crowds
- CDN prewarming
- low-latency CMAF where justified

Backend business services must not transport live media bytes.

---

# 23. Security Architecture

Create:

```text
docs/security/
├── security-architecture.md
├── threat-model.md
├── authentication.md
├── authorization.md
└── secrets-management.md
```

Address:

- OAuth2
- OIDC
- PKCE
- JWT/token validation
- RBAC
- ABAC
- OPA where justified
- TLS
- WAF
- KMS
- secrets management
- Kubernetes/network policies
- rate limiting
- audit logging
- least privilege
- workload identity

Threat model at minimum:

- account takeover
- credential stuffing
- token theft/replay
- API abuse
- privilege escalation
- SQL injection
- SSRF
- payment fraud
- content piracy
- DRM abuse
- geo circumvention
- DDoS

Do not log credentials, payment secrets, tokens, DRM keys, or sensitive PII.

---

# 24. Reliability & Failure Isolation

Create:

```text
docs/architecture/reliability.md
```

For every important dependency define:

```text
Timeout
Retry
Circuit Breaker
Bulkhead
Fallback
Load Shedding
Backpressure
```

Retries must be:

- bounded
- exponential
- jittered
- idempotency-aware

Define degradation explicitly.

Examples:

```text
Recommendation unavailable
→ return popular/trending rails

Analytics unavailable
→ playback continues

Notification unavailable
→ completed payment/subscription remains successful
```

Entitlement and DRM authorization should fail securely.

---

# 25. Observability & SLOs

Create:

```text
docs/architecture/observability.md
```

Standardize around:

```text
OpenTelemetry
├── Metrics → Prometheus → Grafana
├── Logs    → Loki/OpenSearch
└── Traces  → Tempo/Jaeger
```

Define:

- structured-log schema
- trace propagation
- correlation IDs
- RED metrics
- domain metrics
- dashboards
- alerts
- SLOs
- error budgets

Define justified SLOs for at least:

- authentication
- catalog
- search
- subscription
- payment
- playback authorization
- recommendations

Each SLO requires:

```text
SLI
Target
Window
Error Budget
Alert Strategy
```

Do not assign identical SLOs mechanically.

---

# 26. Capacity Planning

Create:

```text
docs/capacity/capacity-plan.md
```

Model explicit assumptions and formulas for:

- registered users
- MAU
- DAU
- concurrent users
- concurrent viewers
- playback starts/sec
- API RPS
- Kafka events/sec
- watch-progress writes/sec
- Redis QPS
- PostgreSQL QPS
- OpenSearch QPS
- media storage
- CDN throughput
- Gbps/Tbps traffic

Clearly distinguish:

```text
Assumption
Estimate
Measured result
```

Never present theoretical capacity as benchmarked performance.

---

# 27. Deployment, Multi-AZ, Multi-Region & DR

Use AWS as the primary reference deployment unless project requirements specify otherwise.

Reference architecture:

```text
Route 53
   ↓
CloudFront + WAF
   ↓
ALB / API Gateway
   ↓
EKS
├── Java/Spring services
├── justified Go components
└── workers

Aurora/RDS PostgreSQL
DynamoDB
ElastiCache Redis
MSK
OpenSearch
S3
KMS
Secrets Manager
```

Create/update:

```text
docs/architecture/deployment.md
docs/architecture/multi-region.md
```

Design multi-AZ first.

Make multi-region evolutionary rather than prematurely active-active.

For each important domain define:

```text
RPO
RTO
Backup
Restore
Replication
Failover
Failback
```

Payment data and recommendation data should not automatically receive identical recovery objectives.

---

# 28. ADR Baseline

Create ADRs before implementation of major architectural choices.

At minimum:

```text
ADR-001 Microservice boundaries
ADR-002 Control plane vs media data plane
ADR-003 Java vs Go usage
ADR-004 REST vs gRPC vs Kafka
ADR-005 PostgreSQL vs NoSQL allocation
ADR-006 Kafka event semantics
ADR-007 Transactional outbox
ADR-008 Playback concurrency control
ADR-009 DRM abstraction
ADR-010 Media packaging strategy
ADR-011 Search synchronization
ADR-012 Authentication provider
ADR-013 AWS deployment model
ADR-014 Multi-region strategy
ADR-015 Recommendation evolution
```

Each ADR must contain:

```text
Context
Decision
Alternatives
Rationale
Consequences
Operational Impact
```

---

# 29. Target Repository Shape

Adapt to an existing repository rather than blindly replacing it.

For a greenfield project, target roughly:

```text
ott-platform/
├── README.md
├── docs/
│   ├── requirements/
│   ├── architecture/
│   ├── diagrams/
│   ├── adr/
│   ├── security/
│   ├── capacity/
│   ├── operations/
│   └── testing/
├── contracts/
│   ├── openapi/
│   ├── protobuf/
│   └── events/
├── services/
├── media/
├── platform/
├── infrastructure/
└── tests/
```

---

# 30. Architecture Gate

Before Stage B, verify that the following exist and are internally consistent:

```text
[ ] FR inventory
[ ] NFR inventory
[ ] assumptions
[ ] constraints
[ ] traceability matrix
[ ] system scope
[ ] C4 context diagram
[ ] C4 container diagram
[ ] critical component diagrams
[ ] bounded-context model
[ ] service catalog
[ ] service dependency map
[ ] data ownership model
[ ] database strategy
[ ] cache strategy
[ ] Kafka/event architecture
[ ] REST/gRPC communication strategy
[ ] API standards
[ ] payment/subscription architecture
[ ] playback architecture
[ ] media pipeline architecture
[ ] DRM architecture
[ ] live-streaming architecture
[ ] authentication architecture
[ ] authorization architecture
[ ] security architecture
[ ] threat model
[ ] reliability strategy
[ ] failure/degradation strategy
[ ] observability architecture
[ ] SLO model
[ ] capacity model
[ ] Multi-AZ design
[ ] multi-region evolution strategy
[ ] DR strategy
[ ] cost considerations
[ ] deployment architecture
[ ] testing strategy
[ ] ADR baseline
[ ] phased implementation roadmap
```

Then perform an architecture consistency review for:

- circular synchronous dependencies
- unclear data ownership
- accidental distributed transactions
- unnecessary microservices
- SPOFs
- unsafe retries
- missing idempotency
- missing concurrency controls
- unrealistic availability goals
- poor failure isolation
- excessive coupling
- security gaps
- scaling bottlenecks
- incompatible API/event contracts
- missing recovery strategy

Fix material architecture problems **before Stage B**.

Produce an Architecture Gate report containing:

```text
PASS
FAIL
OPEN RISKS
ASSUMPTIONS
DECISIONS
IMPLEMENTATION ORDER
```

Proceed to Stage B only when there are no unresolved architecture blockers.

---

# STAGE B — PHASED IMPLEMENTATION

Implement dependency-aware vertical slices. Do not implement all services simultaneously.

Every phase must end in a tested integrated increment.

## Phase 0 — Engineering Foundation

Implement:

- parent/module build
- Java 21 configuration
- dependency management
- code-quality baseline
- testing conventions
- OpenAPI tooling
- Protobuf tooling
- Kafka schema tooling
- Docker Compose
- Testcontainers support
- observability foundation
- security foundation
- CI baseline

Local infrastructure as required:

```text
PostgreSQL
Kafka
Schema Registry
Redis
OpenSearch
Keycloak
Temporal
OpenTelemetry Collector
Prometheus
Grafana
```

Verify build, container health, and test infrastructure.

---

## Phase 1 — Identity / Account / Profile / Device

Implement complete vertical slices for:

- identity integration
- account
- profiles
- kids profiles
- parental controls
- device registration/capabilities
- session/device management

---

## Phase 2 — Catalog / Metadata / Rights

Implement:

- catalog
- movies/series/seasons/episodes
- localization
- sports metadata
- content versioning
- territorial rights
- license windows
- publication workflow
- catalog events

---

## Phase 3 — Subscription / Payment / Entitlement

Implement:

- plans
- subscriptions
- payment integration abstraction
- webhook verification
- entitlement

Mandatory:

- idempotency
- state machines
- transactional outbox
- duplicate webhook handling
- reconciliation boundaries
- audit logging

Thoroughly test payment failure scenarios.

---

## Phase 4 — Playback Control Plane

Implement:

- playback authorization
- sessions
- entitlement checks
- rights checks
- device checks
- geo checks
- concurrent-stream control
- Redis leases
- short-lived playback tokens
- signed CDN adapter
- DRM adapter
- heartbeat
- expiration

Integration-test concurrency/race conditions.

---

## Phase 5 — Media Ingestion & Processing

Implement:

- upload boundary
- Temporal workflow
- FFprobe validation
- FFmpeg transcoding
- configurable encoding profiles
- HLS
- MPEG-DASH
- CMAF
- thumbnails
- packaging
- mock DRM boundary
- S3 adapter
- publication workflow

Verify with deterministic synthetic media.

---

## Phase 6 — Watch State

Implement:

- watch progress
- history
- continue watching
- watchlist
- cross-device resume

Handle:

- duplicates
- out-of-order events
- late events
- multiple devices
- high write volume

---

## Phase 7 — Search & Discovery

Implement:

- catalog event → outbox → Kafka → indexer
- OpenSearch indexing
- search
- autocomplete
- typo tolerance
- filtering
- ranking
- home rails/discovery

---

## Phase 8 — Trending & Recommendation Baseline

Start deterministic:

- popular
- trending
- same genre
- same actors
- recent releases
- because-you-watched heuristics

Use event-driven signals.

Do not introduce ML infrastructure without demonstrated need.

---

## Phase 9 — Live Streaming & Sports Control Plane

Implement:

- live-event metadata
- fixtures/schedules
- event state machine
- entitlement
- authorization
- DVR metadata
- replay
- highlights
- commentary metadata
- CDN playback authorization

---

## Phase 10 — Advertising Foundation

Implement architecture and vertical slices for:

- ad-supported plans
- pre/mid/post-roll
- eligibility
- targeting abstraction
- frequency caps
- impression tracking
- quartile events
- CSAI/SSAI adapters

Ad analytics failures must not unnecessarily terminate core playback.

---

## Phase 11 — Notifications

Implement:

- preferences
- email/SMS/push/in-app abstractions
- content reminders
- live-event reminders
- payment-failure notifications

Use asynchronous processing.

Business transactions must not depend synchronously on notification delivery.

---

## Phase 12 — Analytics / QoE / Experimentation

Implement event ingestion for representative events:

```text
PLAY_STARTED
PLAY_PAUSED
PLAY_COMPLETED
BUFFER_STARTED
BUFFER_ENDED
QUALITY_CHANGED
SEARCH
SUBSCRIPTION_PURCHASED
PAYMENT_FAILED
AD_IMPRESSION
```

QoE metrics include:

- startup latency
- rebuffer ratio
- quality switches
- bitrate
- resolution
- playback failure rate
- CDN
- device
- region

Add experimentation foundation only to the level justified by requirements.

---

## Phase 13 — Fraud / Abuse / Administration

Implement appropriate foundations for:

- credential abuse
- account-takeover indicators
- excessive device registration
- stream abuse
- payment-fraud integration
- geo-circumvention signals
- admin APIs
- support APIs
- immutable/auditable actions

Apply strict authorization.

---

## Phase 14 — Kubernetes & Cloud Infrastructure

Implement:

- Docker images
- Kubernetes manifests
- Helm
- Argo CD
- Terraform

AWS target may include:

```text
EKS
Aurora/RDS
DynamoDB
ElastiCache
MSK
OpenSearch
S3
CloudFront
Route 53
ALB/NLB
KMS
Secrets Manager
WAF
```

Include where applicable:

- startup/readiness/liveness probes
- resource requests/limits
- HPA
- KEDA
- PodDisruptionBudgets
- topology spread
- network policies
- workload identity
- non-root/security contexts

---

## Phase 15 — Production Hardening

Run applicable:

- unit tests
- integration tests
- contract tests
- API tests
- E2E tests
- performance tests
- dependency scans
- static analysis
- container scans
- SBOM generation
- failure injection
- chaos tests where practical

Test degradation paths, not only happy paths.

---

## Phase 16 — DR / Multi-Region Validation

Validate/document:

- regional failover
- DB restore
- Kafka recovery
- Redis recovery
- OpenSearch rebuild
- S3/origin recovery
- cross-region architecture
- RPO
- RTO
- failback

Never claim DR success unless it was actually exercised.

---

# 31. Java Service Architecture

For substantial services use pragmatic Hexagonal Architecture:

```text
service/
├── domain/
│   ├── model/
│   ├── service/
│   ├── event/
│   └── repository/
├── application/
│   ├── command/
│   ├── query/
│   ├── usecase/
│   └── port/
├── adapter/
│   ├── inbound/
│   │   ├── rest/
│   │   ├── grpc/
│   │   └── kafka/
│   └── outbound/
│       ├── persistence/
│       ├── kafka/
│       ├── redis/
│       └── external/
└── infrastructure/
    └── configuration/
```

Do not force trivial CRUD code through excessive layers.

Java standards:

- Java 21
- Spring Boot
- Spring Security
- Spring Data
- Spring Kafka
- Resilience4j where appropriate
- Flyway
- OpenTelemetry
- JUnit 5
- Mockito
- Testcontainers
- REST Assured

Prefer:

- immutable domain structures
- records/value objects where useful
- explicit domain invariants
- constructor injection
- explicit transaction boundaries

Do not expose JPA entities directly through public APIs.

Avoid broad `catch (Exception)` except at justified architectural boundaries.

---

# 32. WebFlux Rule

Do not choose WebFlux simply because traffic is high.

Use it only when:

- workload has significant concurrent I/O
- dependencies are nonblocking end-to-end
- the complexity is justified

Prefer normal Spring request processing for conventional transactional services.

---

# 33. Go Rule

Java 21 + Spring Boot is the default.

Use Go only for a concrete workload such as:

- high-throughput edge utility
- traffic-control component
- specialized media/streaming utility
- low-overhead networking process

Record the choice in an ADR.

---

# 34. Testing Standard

Every phase must add the tests appropriate to its risk.

## Unit

Use JUnit 5 + Mockito.

Naming convention:

```text
method_condition_expected
```

## Integration

Use Testcontainers where applicable for:

- PostgreSQL
- Kafka
- Redis
- OpenSearch

## Contract

Use Pact or Spring Cloud Contract where service/API compatibility warrants it.

## E2E

Continuously extend the primary journey:

```text
Register
→ Authenticate
→ Profile
→ Browse
→ Subscribe
→ Pay
→ Entitlement
→ Playback authorization
→ Progress
→ Resume
```

Prioritize tests for correctness and failure behavior over redundant coverage.

---

# 35. Phase Definition of Done

A phase is complete only when all applicable items are satisfied:

```text
[ ] requirements mapped
[ ] architecture followed
[ ] domain behavior implemented
[ ] API/gRPC contracts implemented
[ ] event contracts implemented
[ ] persistence implemented
[ ] migrations implemented
[ ] validation implemented
[ ] authentication implemented
[ ] authorization implemented
[ ] idempotency addressed
[ ] concurrency addressed
[ ] transaction boundaries correct
[ ] event ordering addressed
[ ] duplicate handling addressed
[ ] failure paths implemented
[ ] timeout/retry strategy implemented
[ ] telemetry implemented
[ ] relevant tests passing
[ ] documentation updated
[ ] traceability updated
[ ] known limitations documented
```

Do not call scaffolding an implementation.

A meaningful service slice normally contains:

```text
API
+ domain/application behavior
+ persistence
+ events where relevant
+ security
+ validation
+ failure handling
+ telemetry
+ tests
+ documentation
```

---

# 36. Phase Gate

At the end of every phase:

1. build affected modules
2. run relevant tests
3. fix failures caused by the change
4. run static/quality checks available in the repository
5. review architecture compliance
6. update ADRs if decisions changed
7. update requirements traceability
8. update API/event contracts
9. update operational documentation
10. state remaining limitations and risks
11. then continue to the next dependency-ready phase

Do not move forward with known correctness failures merely to increase implementation breadth.

---

# 37. Verification Report

At the end of each phase produce:

```text
PHASE: <name>

Implemented
-----------

Architecture Decisions
----------------------

Requirements Covered
--------------------

APIs Added/Changed
------------------

Events Added/Changed
--------------------

Database Changes
----------------

Security
--------

Observability
-------------

Verification
------------

PASS:
- ...

FAIL:
- ...

NOT RUN:
- ...

Remaining Work
--------------

Risks / Blockers
---------------
```

Report **only checks actually executed**.

Never convert an unexecuted check into a pass.

---

# 38. Decision-Making Authority

Resolve normal engineering decisions independently.

Do not stop for approval on routine matters.

Ask only when ambiguity materially affects:

- business correctness
- data integrity
- security
- public compatibility
- credentials/secrets
- irreversible architecture
- destructive migration
- significant operational risk

Otherwise:

```text
make reasonable assumption
→ document assumption
→ continue
```

---

# 39. Architecture Drift Rule

Architecture documents are living artifacts.

If implementation proves an architecture decision incorrect:

```text
Problem discovered
→ evaluate alternatives
→ update ADR
→ update architecture
→ update traceability
→ implement corrected design
```

Never silently allow architecture and code to diverge.

---

# 40. Reliability Rules

For distributed workflows explicitly consider:

- validation
- authorization
- timeouts
- bounded retries
- exponential backoff + jitter
- idempotency
- concurrency
- transaction boundaries
- optimistic/pessimistic locking
- event ordering
- duplicates
- replay
- backpressure
- partial failures
- compensation
- schema evolution

Never blindly retry:

- non-idempotent payment operations
- irreversible external actions

---

# 41. Implementation Quality Rules

Prefer focused, maintainable changes.

Do not:

- create abstractions without demonstrated value
- add dependencies without justification
- duplicate infrastructure
- create generic `common` modules that become coupling magnets
- expose persistence models as API models
- log secrets or sensitive values
- leave critical paths as TODOs
- return fake success responses for unfinished behavior
- replace realistic integration behavior with mocks outside intended test/local adapters

Commercial external systems may use realistic adapters/test doubles where credentials or services are unavailable.

---

# 42. Unsupported Readiness Claims

Never claim:

```text
Production ready
Netflix scale
Hotstar scale
Fully resilient
Fully secure
Fully tested
```

unless actual evidence supports the statement.

Always distinguish:

```text
Implemented and verified
vs
Target production architecture
vs
Estimated future scale design
vs
Not executed/not validated
```

---

# 43. Completion Objective

The resulting repository should demonstrate a coherent enterprise OTT architecture and implementation covering:

```text
VOD
+ Live/Sports
+ ABR streaming
+ CDN delivery
+ DRM boundaries
+ Subscription
+ Billing/Payment
+ Entitlement
+ Multi-device playback
+ Rights
+ Search
+ Discovery
+ Recommendations
+ Advertising
+ Analytics/QoE
+ Event-driven processing
+ Cloud-native deployment
+ Security
+ Observability
+ Resilience
+ Disaster recovery
```

The result should optimize for **correctness, architecture coherence, explicit ownership, failure isolation, testability, operational clarity, and realistic production evolution** rather than maximum technology count.

---

# 44. Start Now

Begin with **Stage A**.

Execute these steps in order:

1. inspect repository and instructions
2. read all supplied reference/FR/NFR artifacts
3. inventory FRs and NFRs
4. document assumptions and constraints
5. build the traceability matrix
6. define system scope
7. model bounded contexts
8. determine service boundaries
9. build the service catalog/dependency map
10. create C4 diagrams
11. design data ownership/storage
12. design APIs and communication patterns
13. design Kafka/event architecture
14. design transaction/idempotency strategy
15. design subscription/payment/entitlement flow
16. design playback control plane
17. design media pipeline
18. design DRM integration
19. design live/sports architecture
20. design security/threat model
21. design resilience/failure isolation
22. define observability/SLOs
23. perform capacity planning
24. design AWS/Kubernetes topology
25. design Multi-AZ/multi-region/DR
26. create ADR baseline
27. create implementation roadmap
28. run the Architecture Gate consistency review
29. fix architecture blockers
30. only then begin Stage B Phase 0

After Stage B begins, continue phase-by-phase in dependency order, verifying and integrating each increment before proceeding.

**Do not stop at recommendations, diagrams, scaffolding, or pseudo-code when implementation is required. Design first, validate the design, then build and verify the platform end-to-end.**