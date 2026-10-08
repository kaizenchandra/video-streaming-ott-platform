# PHASE: 0 — Engineering Foundation

Status: PASS local engineering gate, 2026-10-08. Runtime integration evidence is local OrbStack/macOS ARM64, not cloud deployment evidence.

## Implemented

Java21 Maven parent + contracts/member-service modules, versioned dependencies, release/toolchain enforcement, Checkstyle baseline, JUnit5 and real Testcontainers, OpenAPI parser validation, typed Avro/proto generation, Compose core dependencies with health checks, Keycloak PKCE realm, JWT resource-server foundation, structured ECS logs, OTel bridge/export configuration, protected Prometheus endpoint and dependency-aware readiness, CI+optional CycloneDX profile. Generated local secrets are ignored and permissions0600.

## Architecture Decisions

ADR-001..015 followed. Boot3.5.16 chosen for mature Java21/JUnit5 integration; no cache-driven upgrade to Boot4. Lazy explicit JWKS URI permits cached-key auth without discovery at startup. Docker29 test client uses API1.44. No business microservice behavior before Architecture Gate.

## Requirements Covered

Foundation portions FR-001/037 and NFR-001/006/007/011/012/013; full FR implementation remains phased.

## APIs Added/Changed

Coarse liveness/readiness probes; metrics requires metrics.read. Test identity route exists in integration fixture only, not production.

## Events Added/Changed

20 designed typed Avro schemas and compatibility/topic ownership inventory; no business event producer yet. All20 local subjects registered with BACKWARD_TRANSITIVE and checked against latest baseline.

## Database Changes

V1 creates owner-local outbox+unpublished index. Real PostgreSQL migration and failed transaction rollback exercised.

## Security

Issuer/audience/signature/expiry validation; default deny; internal/metrics scopes; 401/403 safe Problem Details. Actual signed JWT negative tests, not merely mocked auth. Local IdP realm PKCE/registration; no backend password custody.

## Observability

Structured logs, health, protected Prometheus scrape tested; tracing configuration present. Telemetry export/collector dashboards not exercised.

## Verification

PASS:
- Architecture inventory/reference/acyclic dependency checker.
- `mvn test`: 3 contract +2 security unit tests, zero failures/skips.
- `mvn -Pintegration verify`: same5 +11 integration tests, zero failures/skips; Checkstyle and Java21 enforcement.
- Real PG migration/rollback, Kafka keyed produce/consume, Redis atomic increment+TTL.
- Cryptographic JWT acceptance and wrong audience/issuer/expiry/signature rejection; missing token and metrics scope enforcement.
- Docker Compose health: PostgreSQL17.6, Redis7.4.5, Kafka3.9.1, SchemaRegistry7.8.3, Keycloak26.2.5.
- Local OIDC discovery issuer verified;20 schema registrations/initial baseline compatibility checks.

FAIL:
- None outstanding. Initial Prometheus integration check failed because Spring test disabled metrics export; corrected with AutoConfigureObservability and full verification rerun passed.

NOT RUN:
- Remote GitHub CI, optional telemetry/search/media profiles, SBOM/scans, performance/chaos and cloud/DR (later phases).

## Remaining Work

Proceed Phase1 member domain, APIs, migrations, outbox production/consumption security and races. Later business/cloud/media phases not implemented by this foundation.

## Risks / Blockers

Commercial provider credentials, jurisdiction policies and cloud drills remain launch dependencies. No blocker to member increment. All pinned images/dependencies require release-time security review; no scan/readiness claim implied.
