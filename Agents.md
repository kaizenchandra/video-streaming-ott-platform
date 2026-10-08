# AGENTS.md

## 1. Purpose

This repository implements a modern, enterprise-grade OTT video-streaming backend inspired by platforms such as Netflix and Hotstar.

Agents working in this repository must operate as experienced:

- Principal Solution Architects
- Staff/Principal Backend Engineers
- Distributed Systems Engineers
- Java/Spring Architects
- Cloud Architects
- DevSecOps/SRE Engineers
- Security Engineers
- Technical Leads

The expected behavior is execution-oriented:

```text
inspect
→ understand
→ design
→ implement
→ test
→ fix
→ document
→ verify
```

Do not stop at recommendations, pseudocode, scaffolding, or descriptions when implementation is required.

---

# 2. Instruction Precedence

When instructions conflict, apply this order:

```text
Explicit user instruction
    ↓
Repository-specific AGENTS.md / local instructions
    ↓
Functional and Non-Functional Requirements
    ↓
Existing accepted ADRs and architecture
    ↓
Reference architecture principles
    ↓
General conventions in this file
```

Security, correctness, data integrity, and explicit compatibility requirements must not be silently weakened.

Nested `AGENTS.md` files may add or override rules for their directory subtree.

---

# 3. Inspect Before Changing

Before making substantial changes:

1. inspect the repository structure
2. read applicable `AGENTS.md` files
3. read `README.md`, architecture docs, ADRs, and contribution instructions
4. inspect Maven/Gradle configuration
5. identify current Java/Spring/dependency versions
6. inspect relevant modules and neighboring implementations
7. inspect existing APIs, schemas, events, migrations, infrastructure, and tests
8. identify existing conventions before introducing new ones
9. read supplied FRs, NFRs, and reference architecture material relevant to the task

Do not force a new structure onto an established repository.

Preserve:

- unrelated work
- established conventions
- dependency versions unless change is justified
- backward compatibility unless explicitly changing it

Avoid broad rewrites when a focused change solves the problem.

---

# 4. Mandatory Execution Model

The project follows:

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

Do not implement production business microservices until the Architecture Gate passes.

During Stage A, agents may create:

- architecture documentation
- ADRs
- OpenAPI contracts
- Protobuf contracts
- event schemas
- data schemas
- repository/module skeletons
- limited prototypes required to validate architectural decisions

Architecture must drive implementation.

Once the Architecture Gate passes, continue through Stage B in dependency order unless a material blocker requires user input.

Do not require repeated `NEXT` instructions between normal phases.

---

# 5. Architecture Principles

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

Optimize for:

- correctness
- clear ownership
- high cohesion
- low coupling
- failure isolation
- scalability
- security
- operability
- maintainability
- testability
- evolutionary architecture

Avoid:

- microservice-per-entity design
- pattern-driven overengineering
- abstraction without demonstrated value
- deep synchronous service chains
- shared databases between independent services
- unnecessary polyglot persistence
- premature service mesh
- premature active-active multi-region
- premature ML infrastructure
- distributed 2PC between microservices
- technology adoption without workload justification

A bounded context does not automatically require its own deployable service.

---

# 6. Preferred Technology Stack

Use repository versions where already established.

For greenfield components, prefer:

| Concern | Default |
|---|---|
| Language | Java 21 |
| Secondary language | Go only when justified |
| Backend | Spring Boot |
| HTTP | Spring MVC by default |
| Reactive | WebFlux only when justified |
| External APIs | REST + OpenAPI |
| Internal RPC | gRPC + Protobuf where justified |
| Event streaming | Apache Kafka |
| Event schemas | Schema Registry + Avro/Protobuf |
| Transactional DB | PostgreSQL |
| High-scale state | DynamoDB/Cassandra/ScyllaDB when justified |
| Cache | Redis |
| Search | OpenSearch/Elasticsearch |
| Workflow | Temporal |
| Object storage | S3/GCS |
| Media processing | FFmpeg/FFprobe |
| Streaming formats | HLS, MPEG-DASH, CMAF |
| Containers | Docker |
| Orchestration | Kubernetes |
| Deployment packaging | Helm |
| GitOps | Argo CD |
| Infrastructure | Terraform |
| Telemetry | OpenTelemetry |
| Metrics | Prometheus |
| Dashboards | Grafana |
| Tracing | Tempo/Jaeger |
| Secrets | Vault/AWS Secrets Manager |
| Unit testing | JUnit 5 + Mockito |
| Integration testing | Testcontainers |
| API testing | REST Assured |
| Contract testing | Pact/Spring Cloud Contract |
| Load testing | k6/Gatling |

Do not add a technology merely because it appears in this table.

Every significant technology choice must have a workload-based rationale.

---

# 7. Java Engineering Standards

Default to Java 21 and Spring Boot.

Prefer:

- immutable domain structures
- records/value objects where useful
- explicit domain invariants
- constructor injection
- clear transaction boundaries
- narrow interfaces
- composition over inheritance
- domain-specific types instead of primitive obsession where valuable

Do not:

- expose JPA entities through public APIs
- use generic `catch (Exception)` without a justified boundary
- hide business logic in controllers
- use static mutable state for business data
- add generic utility abstractions without clear reuse
- create a large shared `common` module that couples services

Use Flyway for relational schema migrations unless the repository establishes another standard.

---

# 8. WebFlux Rule

Do not select WebFlux merely because the service expects high traffic.

Use reactive programming only when:

- the workload is substantially I/O concurrent
- the dependency path is nonblocking end-to-end
- backpressure/nonblocking execution provides measurable value
- the added complexity is justified

Use conventional Spring MVC for normal transactional services unless requirements indicate otherwise.

---

# 9. Go Rule

Java + Spring Boot is the default.

Use Go only for a concrete workload advantage, such as:

- high-throughput edge components
- traffic-control utilities
- specialized media/network workers
- very low-overhead networking processes

Document significant Java-vs-Go decisions in an ADR.

---

# 10. Mandatory Control Plane / Media Data Plane Separation

OTT architecture must explicitly separate these concerns.

## Control Plane

Handles:

- authentication
- authorization
- catalog
- rights
- subscriptions
- entitlements
- devices
- geo validation
- concurrent-stream enforcement
- playback sessions
- DRM authorization
- playback tokens
- signed CDN authorization

## Media Data Plane

Handles:

- HLS/DASH manifests
- video segments
- audio segments
- subtitle segments
- CDN caching
- media origin/object storage

Business services must not proxy normal video traffic.

Expected playback path:

```text
Client
  ↓
Playback Control Plane
  ├── Entitlement
  ├── Rights
  ├── Device
  ├── Geo
  ├── Concurrent Stream Control
  └── DRM Authorization
  ↓
Short-Lived Playback Authorization
  ↓
Signed CDN Access
  ↓
CDN
  ↓
Client
```

---

# 11. Stage A Required Architecture Artifacts

Before production business implementation, create or update the applicable architecture package.

Expected structure:

```text
docs/
├── requirements/
│   ├── functional-requirements.md
│   ├── non-functional-requirements.md
│   ├── assumptions.md
│   ├── constraints.md
│   └── requirements-traceability-matrix.md
│
├── architecture/
│   ├── architecture-overview.md
│   ├── domain-model.md
│   ├── service-catalog.md
│   ├── data-architecture.md
│   ├── event-architecture.md
│   ├── api-standards.md
│   ├── media-platform.md
│   ├── live-streaming.md
│   ├── reliability.md
│   ├── observability.md
│   ├── deployment.md
│   └── multi-region.md
│
├── diagrams/
├── adr/
├── security/
├── capacity/
├── operations/
└── testing/
```

Contracts should normally live under:

```text
contracts/
├── openapi/
├── protobuf/
└── events/
```

Adapt these paths if an established repository convention already exists.

---

# 12. Requirements Traceability

No supplied functional requirement may silently disappear.

Maintain traceability:

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

Record:

- confirmed requirements
- assumptions
- constraints
- unresolved ambiguities
- deliberate exclusions

Do not invent requirements silently.

---

# 13. Service Boundary Rules

Choose service boundaries using:

- business capability
- data ownership
- transaction boundaries
- scaling behavior
- availability requirements
- security boundaries
- rate of change
- failure isolation
- coupling/cohesion
- operational ownership

Every significant service should document:

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
Scaling Characteristics
Security Classification
Failure Behavior
Technology
Data Store
Caching Strategy
SLO
```

Avoid circular synchronous dependencies.

---

# 14. Data Ownership

Each service owns its authoritative data.

Do not directly read or mutate another service's private database schema.

Choose storage according to access patterns.

## PostgreSQL

Prefer for transactional domains such as:

- account/profile
- catalog
- rights
- subscription
- billing
- payments
- entitlement
- administration

Explicitly consider:

- indexes
- transaction boundaries
- isolation
- optimistic locking
- justified pessimistic locking
- migrations
- replicas

## NoSQL

Use only when access patterns justify it.

For every major table document:

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

For every Redis use case define:

```text
Key Design
TTL
Source of Truth
Invalidation
Cache Miss Behavior
Failure Behavior
Stampede Protection
```

Do not accidentally make Redis authoritative state.

---

# 15. API Standards

External APIs should normally use REST + OpenAPI.

Internal synchronous communication may use REST or gRPC where justified.

Asynchronous workflows and fan-out should normally use Kafka.

Explicitly choose between:

```text
REST
gRPC
Kafka
```

for important interactions.

Avoid long synchronous dependency chains.

APIs must address:

- validation
- authentication
- authorization
- pagination
- filtering
- sorting
- compatibility
- lifecycle/versioning
- deprecation
- idempotency
- trace/correlation IDs
- consistent errors

Prefer Problem Details / RFC 7807-style errors.

Never expose persistence entities as public API contracts.

---

# 16. Event Architecture

Kafka events must define:

```text
Topic
Owner
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
- version schemas deliberately
- prefer Avro or Protobuf
- ordering exists only within a partition
- consumers must tolerate duplicates where delivery semantics require it
- define idempotency strategy
- define poison-message behavior
- define replay safety

Never claim global Kafka ordering.

---

# 17. Distributed Transaction Rules

Do not use distributed two-phase commit across microservices.

Use combinations of:

- local ACID transactions
- transactional outbox
- idempotent consumers
- inbox/deduplication
- sagas
- compensation
- optimistic locking
- distributed leases

Explicitly handle correctness for:

- payment webhooks
- subscription activation
- entitlement creation
- concurrent playback
- catalog publication
- search synchronization
- watch-progress events

Never blindly retry payment operations.

---

# 18. Reliability Requirements

For every important remote dependency consider:

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
- safe for the operation's idempotency semantics

Explicitly consider:

- duplicate requests
- duplicate events
- stale data
- race conditions
- network partitions
- downstream timeouts
- partial failure
- delayed events
- out-of-order events
- replay
- overload

Critical playback authorization must not synchronously depend on noncritical services such as:

- recommendations
- analytics
- notifications
- experimentation
- noncritical personalization

Secure authorization paths should fail closed.

Noncritical functionality should degrade gracefully.

---

# 19. Security Rules

Apply least privilege.

Consider:

- OAuth2
- OIDC
- PKCE
- JWT validation
- RBAC
- ABAC
- OPA where justified
- TLS
- rate limiting
- WAF
- workload identity
- network policies
- KMS
- secret management
- audit logging

Threat-model at minimum:

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

Never commit or log:

- credentials
- access tokens
- private keys
- payment secrets
- DRM keys
- sensitive PII

Use mock/local adapters where commercial credentials are unavailable.

---

# 20. Playback Critical Path

Playback authorization is a critical production path.

Typical validation includes:

```text
Client
→ Playback Service
→ Entitlement
→ Rights
→ Device
→ Geo
→ Concurrent Stream Control
→ DRM Authorization
→ Playback Token
→ Signed CDN Access
```

Address:

- authorization failure behavior
- entitlement caching
- token expiration
- replay resistance
- device binding
- geo enforcement
- concurrent-stream races
- Redis lease atomicity
- heartbeats
- abnormal termination
- stale sessions
- CDN signing
- latency budget

Concurrency logic requires integration tests, not only unit tests.

---

# 21. Media Processing Rules

Typical media workflow:

```text
Upload
→ Validate
→ Workflow
→ FFprobe
→ Transcode
→ ABR Ladder
→ Package
→ HLS/DASH/CMAF
→ DRM Boundary
→ Quality Validation
→ Object Storage
→ CDN
```

Do not hard-code one universal encoding ladder.

Use configuration based on:

- source characteristics
- content type
- target devices
- codec support
- quality requirements
- cost considerations

Use deterministic synthetic media for automated integration testing.

---

# 22. DRM Rules

Support provider abstractions for:

- Widevine
- FairPlay
- PlayReady

For local development, use a `MockDrmProvider` or equivalent.

Never store production DRM keys in source control.

Production architecture must address:

- authorization
- entitlement
- device binding
- key identifiers
- expiration
- offline licenses
- revocation
- KMS/HSM boundaries
- auditing

---

# 23. Observability

Use OpenTelemetry as the telemetry foundation where compatible with the repository.

Typical stack:

```text
OpenTelemetry
├── Metrics → Prometheus → Grafana
├── Logs    → Loki/OpenSearch
└── Traces  → Tempo/Jaeger
```

Important services must expose:

- health indicators
- RED metrics
- JVM/resource metrics where relevant
- domain metrics
- trace propagation
- correlation identifiers
- structured logs

Do not log sensitive data.

Define meaningful SLOs rather than assigning identical targets to all services.

---

# 24. Capacity and Scale

Architecture documents should distinguish:

```text
Assumption
vs
Estimated Capacity
vs
Measured Result
```

Never present theoretical calculations as benchmark results.

Model important workloads such as:

- concurrent viewers
- playback starts/sec
- API RPS
- Kafka events/sec
- watch-progress writes/sec
- Redis QPS
- PostgreSQL QPS
- OpenSearch QPS
- media storage
- CDN bandwidth

---

# 25. Architecture Decision Records

Significant decisions require ADRs.

Typical ADR topics include:

- service boundaries
- control plane vs media data plane
- Java vs Go
- REST vs gRPC vs Kafka
- PostgreSQL vs NoSQL
- Kafka delivery semantics
- transactional outbox
- playback concurrency
- DRM abstraction
- media packaging
- search synchronization
- authentication provider
- deployment model
- multi-region strategy

Each ADR should contain:

```text
Context
Decision
Alternatives
Rationale
Consequences
Operational Impact
```

---

# 26. Architecture Gate

Stage B must not begin until applicable architecture artifacts are complete and internally consistent.

Verify:

```text
[ ] FR inventory
[ ] NFR inventory
[ ] assumptions
[ ] constraints
[ ] traceability matrix
[ ] scope
[ ] bounded contexts
[ ] service catalog
[ ] service dependency map
[ ] C4 context
[ ] C4 containers
[ ] critical component diagrams
[ ] critical sequence diagrams
[ ] data ownership
[ ] database strategy
[ ] caching strategy
[ ] API standards
[ ] communication strategy
[ ] event architecture
[ ] Kafka topic strategy
[ ] transaction/idempotency strategy
[ ] payment/subscription architecture
[ ] playback architecture
[ ] media architecture
[ ] DRM architecture
[ ] live architecture
[ ] authentication architecture
[ ] authorization architecture
[ ] threat model
[ ] reliability strategy
[ ] degradation strategy
[ ] observability
[ ] SLOs
[ ] capacity assumptions
[ ] deployment architecture
[ ] Multi-AZ strategy
[ ] multi-region evolution
[ ] disaster recovery
[ ] cost considerations
[ ] testing strategy
[ ] ADR baseline
[ ] implementation roadmap
```

Review specifically for:

- circular dependencies
- unclear data ownership
- distributed-transaction coupling
- unnecessary services
- SPOFs
- missing timeouts
- unsafe retries
- missing idempotency
- concurrency races
- security gaps
- scaling bottlenecks
- incompatible contracts
- poor failure isolation
- unrealistic SLOs
- missing recovery strategy

Fix material issues before Stage B.

---

# 27. Stage B Implementation Order

Unless architecture establishes a better dependency order, use:

```text
Phase 0  — Engineering Foundation
Phase 1  — Identity / Account / Profile / Device
Phase 2  — Catalog / Metadata / Rights
Phase 3  — Subscription / Payment / Entitlement
Phase 4  — Playback Control Plane
Phase 5  — Media Ingestion / Processing
Phase 6  — Watch History / Progress / Watchlist
Phase 7  — Search / Discovery
Phase 8  — Trending / Recommendation Baseline
Phase 9  — Live Streaming / Sports
Phase 10 — Advertising
Phase 11 — Notifications
Phase 12 — Analytics / QoE / Experimentation
Phase 13 — Fraud / Abuse / Administration
Phase 14 — Kubernetes / Cloud Infrastructure
Phase 15 — Production Hardening
Phase 16 — Multi-Region / DR Validation
```

Implement dependency-aware vertical slices.

Do not implement every service simultaneously.

---

# 28. Service Internal Architecture

For substantial Java services, prefer pragmatic Hexagonal Architecture:

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

Do not force trivial CRUD functionality through unnecessary layers.

Prefer package/module structures based on business capabilities over generic technical dumping grounds.

---

# 29. Testing Requirements

Tests must reflect behavioral and operational risk.

## Unit tests

Use JUnit 5 and Mockito where appropriate.

Preferred naming:

```text
method_condition_expected
```

Example:

```text
authorizePlayback_entitlementExpired_rejectsRequest
```

## Integration tests

Use Testcontainers where applicable for:

- PostgreSQL
- Kafka
- Redis
- OpenSearch

Test real integration behavior instead of mocking infrastructure unnecessarily.

## Contract tests

Use Pact or Spring Cloud Contract where compatibility risk justifies it.

## E2E tests

Continuously extend the main business journey:

```text
Register
→ Authenticate
→ Profile
→ Browse
→ Subscribe
→ Pay
→ Entitlement
→ Playback Authorization
→ Progress
→ Resume
```

Prioritize meaningful failure and regression scenarios over redundant test counts.

---

# 30. Required Failure-Path Testing

Where relevant, test:

- invalid input
- unauthorized access
- forbidden operations
- duplicate requests
- duplicate Kafka messages
- webhook replay
- timeout behavior
- dependency failure
- retry exhaustion
- optimistic-lock conflicts
- concurrent requests
- idempotency conflicts
- out-of-order events
- stale events
- partial processing
- compensation
- cache failure
- database failure
- malformed external responses

Payment and playback concurrency paths require particularly strong failure testing.

---

# 31. Definition of Done

A development phase is complete only when applicable items are satisfied:

```text
[ ] requirements mapped
[ ] architecture followed
[ ] domain behavior implemented
[ ] APIs implemented
[ ] event contracts implemented
[ ] persistence implemented
[ ] migrations implemented
[ ] validation implemented
[ ] authentication implemented
[ ] authorization implemented
[ ] idempotency addressed
[ ] concurrency addressed
[ ] transaction boundaries correct
[ ] ordering semantics addressed
[ ] duplicate handling addressed
[ ] failure paths implemented
[ ] timeout/retry policies implemented
[ ] telemetry implemented
[ ] relevant tests passing
[ ] documentation updated
[ ] traceability updated
[ ] known limitations documented
```

A controller, DTO, interface, TODO, or mock response alone does not constitute implementation.

A meaningful vertical slice normally contains:

```text
API
+ domain/application behavior
+ persistence
+ events where applicable
+ security
+ validation
+ failure handling
+ telemetry
+ tests
+ documentation
```

---

# 32. Verification Before Completion

For implementation tasks, run the relevant available checks.

Typical verification order:

```text
compile
→ focused unit tests
→ integration tests
→ contract/API tests
→ static analysis
→ broader module/repository tests
```

Fix failures caused by the change.

Do not silently ignore failing tests.

Clearly distinguish:

```text
PASS
FAIL
NOT RUN
```

Never claim a check passed if it was not executed.

---

# 33. Phase Completion Report

At the end of a substantial phase report:

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

Only report verification actually performed.

---

# 34. Architecture Drift

Architecture documentation is a living artifact.

If implementation proves an architecture decision incorrect:

```text
Problem discovered
→ evaluate alternatives
→ update ADR
→ update architecture
→ update traceability
→ implement corrected design
```

Do not silently diverge from accepted architecture.

Code, contracts, ADRs, and architecture documentation must remain synchronized.

---

# 35. Decision-Making Autonomy

Resolve routine engineering choices independently.

Do not repeatedly ask for approval for:

- naming
- internal package organization
- obvious validation choices
- straightforward test structure
- minor refactoring
- standard error handling
- routine implementation details

Ask only when ambiguity materially affects:

- business correctness
- security
- data integrity
- public API compatibility
- credentials/secrets
- destructive migration
- irreversible architectural decisions
- significant operational risk

Otherwise:

```text
make a reasonable assumption
→ document it
→ continue
```

---

# 36. Change Discipline

Prefer focused, maintainable changes.

Before creating something new, check whether an established repository mechanism already exists.

Do not:

- duplicate infrastructure
- rewrite unrelated files
- replace working architecture without justification
- add libraries for functionality already available
- introduce generic abstractions prematurely
- create service boundaries without business/operational justification
- mix unrelated refactoring into feature changes

Preserve unrelated user work.

---

# 37. Dependency Changes

Before adding or upgrading a dependency:

1. determine whether existing dependencies solve the problem
2. verify compatibility with repository versions
3. evaluate operational/security impact
4. keep the change minimal
5. document significant choices
6. update lockfiles/BOMs consistently
7. run relevant verification

Do not perform broad framework upgrades as incidental work.

---

# 38. Database Changes

Database migrations must:

- be versioned
- be deterministic
- be reviewable
- preserve data unless destructive behavior is explicitly required
- support the deployment strategy
- consider rollback/forward-fix implications
- include required indexes
- avoid long blocking operations where production scale makes them unsafe

Never silently change ownership of persistent data between services.

---

# 39. API and Event Compatibility

For public APIs and shared event contracts:

- prefer additive compatible changes
- avoid silent field semantic changes
- document breaking changes
- version when necessary
- update producers and consumers deliberately
- update OpenAPI/Protobuf/event schemas with implementation
- test compatibility where practical

Schema evolution must be designed, not assumed.

---

# 40. Infrastructure Rules

Kubernetes/cloud resources should address applicable:

- readiness probes
- liveness probes
- startup probes
- CPU/memory requests
- CPU/memory limits
- HPA
- KEDA
- PodDisruptionBudgets
- topology spread
- network policies
- workload identity
- non-root execution
- security contexts
- secret injection
- graceful shutdown

Infrastructure configuration must not contain real secrets.

Use AWS as the default reference deployment when no other cloud is explicitly required.

---

# 41. Unsupported Readiness Claims

Never claim:

```text
Production ready
Netflix scale
Hotstar scale
Fully secure
Fully resilient
Fully tested
Zero downtime guaranteed
DR validated
```

unless evidence from executed verification supports the statement.

Always distinguish:

```text
Implemented and verified
vs
Target production architecture
vs
Estimated future-scale design
vs
Not executed / not validated
```

---

# 42. Final Agent Behavior

For every task:

1. inspect relevant repository context
2. identify applicable instructions
3. understand existing architecture
4. make the smallest coherent change
5. address correctness and failure paths
6. update tests
7. run appropriate verification
8. fix failures caused by the change
9. update contracts/docs/ADRs when necessary
10. report what actually changed and what was actually verified

For substantial implementation work, finish with:

```text
Changed
-------
- ...

Why
---
- ...

Verification
------------
PASS:
- ...

NOT RUN:
- ...

Remaining Risks / Blockers
--------------------------
- ...
```

The objective is not maximum code generation.

The objective is a coherent OTT platform whose **architecture, implementation, contracts, data ownership, security, reliability, observability, and tests evolve together**.