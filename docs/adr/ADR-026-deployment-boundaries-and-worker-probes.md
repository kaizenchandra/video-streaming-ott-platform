# ADR-026 — Deployment boundaries and worker probes

## Context
FR037 and NFR availability/security/deployment require repeatable owner packaging, private data services, meaningful readiness and an explicit distinction between local adapters and a commercial deployment. Media workers are headless and cannot expose the servlet Actuator endpoints.

## Decision
Use one small Java 21 runtime image per owner and a separate FFmpeg worker image. A Helm chart packages nine independent deployments and a headless worker, with owner-specific existing Secrets, restricted service accounts, probes, rolling updates, disruption budgets, topology spread and deny-default network policy. Production chart rendering requires digest-pinned images, TLS ingress and workload roles; unavailable payment/DRM/media protection providers are explicitly blocked. AWS Terraform defines a reviewable three-AZ foundation with private owner RDS instances, EKS, MSK, Redis Cluster, OpenSearch, encrypted media storage and CloudFront OAC. Schema Registry, owner topic ACLs, Temporal, IdP, ingress controller, Secret injection and observability remain separately configured dependencies; they cannot be inferred from a successful infrastructure plan.

Headless worker probes use a small dedicated management HTTP listener enabled only for worker deployments. Liveness checks worker lifecycle; readiness also checks PostgreSQL and Temporal with deadlines. It never serves business/media routes or discloses dependency details. Kubernetes network policy restricts management access.

## Alternatives
Fake file/process checks miss stopped polling; adding a business servlet server expands the worker attack surface. One shared database/login violates ownership; deploying every managed product without workload/cost input is premature. Automatic cloud apply without account/budget parameters cannot establish a safe or reviewable environment.

## Rationale
Existing owner schemas and tested local adapters remain authoritative. Deployment packaging must retain fail-closed commercial boundaries and distinguish manifest/schema validation from a live cluster rollout or cloud verification.

## Consequences
Local demonstration is runnable with explicit mock settings and injected development Secrets. Commercial production rollout remains blocked until adapters, ingress trust, external dependencies and credentials pass their gates. Initial resource sizes are assumptions. Worker management requires one extra private TCP port.

## Operational Impact
Build/package before image construction; store credentials outside Git; use short-lived workload identity, separately provisioned migration credentials and reviewed forward-only migrations. No billed cloud apply is executed by this change. Test local container/probe behavior, chart rejection paths and Terraform schema/security checks; record live EKS and AWS checks as NOT RUN until exercised.
