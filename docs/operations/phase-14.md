# PHASE: 14 — Containers / Kubernetes / AWS Packaging

## Implemented
Nine owner Java runtime images/build helper; separate native media worker, private real worker probes, terminating owner-only Flyway entry points; Helm deployments/services/Secrets references/single migration hooks/probes/resources/rolling updates/topology/PDB/optional HPA/default-deny network policy and84 source-derived edge routes. Argo application/restricted project templates and AWS Terraform foundation with lockfile/security mock plans. Existing application secrets remain outside Git. Initial retention sweep is delayed; bounded cleanup skips locked rows after an executed gate exposed startup/test reset contention.

## Architecture Decisions
[ADR026](../adr/ADR-026-deployment-boundaries-and-worker-probes.md). Independent owner deployment/data credentials; migration jobs never start business schedulers. Production rendering blocks missing commercial Commerce/Playback/Media providers. Private dependencies and workload claims preserve ownership. Headless worker liveness is lifecycle-only, readiness checks actual DB and Temporal with deadlines. [Deployment instructions](../../infrastructure/README.md) state prerequisites and incomplete production integrations.

## Requirements Covered
FR037 infrastructure plus NFR security, deployment, availability, ownership and operability. Costs/resources are reference assumptions, not measured cloud capacity.

## APIs / Events / Database Changes
No business contract/event/schema change. Worker private `/health/live` and `/health/ready` management surface only; per-owner Flyway entry points use existing owned migrations. Retention scheduling/locking changes preserve existing retention windows.

## Security
UID10001, read-only filesystem, capped tmpfs, dropped capabilities, RuntimeDefault seccomp, no implicit service-account token, owner Secret references, immutable-image/TLS/dependency/role production guards. Separate migration/application credentials. AWS private API/data services, encryption/backups/deletion protection, SCRAM/TLS Kafka, TLS Redis/search, private versioned S3 and signed CloudFront OAC. Audit and credential integration require cloud setup; AWS policy validation cannot prove effective permissions.

## Observability
Readiness/liveness/startup plus migration job status; JVM/container health. Worker endpoints disclose only UP/UNAVAILABLE and exclude business/media routes. Actual stopped-database container drill shows readiness503 with liveness200.

## Verification
PASS:
- Full repository `-Pintegration verify`,177 tests, zero failures/errors/skips, including actual PostgreSQL+Temporal worker readiness and all nine-owner journeys.
- Helm lint and deployment semantic tests: ten workloads, nine isolated migration jobs/HPA,84 controller routes, production rejection paths.
- Terraform schema validation and two mocked security plans, including rejected single-AZ input; no AWS calls.
- Actual Member image build and isolated container drill: terminating migration, DML-only runtime user rejects CREATE TABLE, signed local OIDC provision/read, UID10001/read-only health, stopped DB readiness/liveness behavior. Isolated containers/network/temporary IdP client cleaned.
- k6 authenticated account-read drill: five VUs15s,5727 requests, zero failed reads, p95≈80.02ms,≈381.73requests/s on this shared local host. It does not benchmark playback/cloud/full OTT traffic.
FAIL: no final business/build check failures. Initial full run failed due immediate retention sweep colliding with test TRUNCATE; corrected scheduler/skip-locked behavior and reran full gate. Initial Terraform/Helm syntax mistakes corrected before schema/semantic gates.
NOT RUN: live Kubernetes/ALB/Argo rollout, AWS provisioning, Multi-AZ failover, production autoscaling/resource sizing, commercial provider deployment, cloud effective permissions/geo/WAF/Registry authentication.

## Remaining Work / Risks
Hardening scans/SBOM/trace checks and local recovery drills follow. Terraform state handling, billed account/budget, durable Temporal/Registry/IdP/Secret injection, Kafka ACLs, tighter owner transport policy, worker role/storage separation and commercial adapters remain explicit production prerequisites. Cloud readiness is not claimed.

Phase Gate: PASS for the tested local packaging increment; production/cloud deployment checks remain NOT RUN.
