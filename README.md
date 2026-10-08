# OTT backend platform

Java21 / Spring Boot3.5.16 backend, designed before business implementation. The [Architecture Gate](docs/architecture/architecture-gate.md) records the accepted technical baseline. [Traceability](docs/requirements/requirements-traceability-matrix.md) separates requested scope from actual progress. This repository does not claim production or Netflix-scale readiness.

## Local prerequisites

Java21, Maven3.9+, Docker engine/Compose. On macOS `scripts/mvn.sh` selects installed Java21; elsewhere configure JAVA_HOME. Commercial payment/DRM/feed integrations use explicit local adapters in their phases. FFmpeg/FFprobe7.1 are required for native media tests; the pinned worker image supplies them. Tests need real Docker; no H2 substitute for PostgreSQL correctness.

```bash
python3 scripts/architecture_check.py
python3 scripts/bootstrap-local.py
docker compose --env-file .local/compose.env -f platform/compose/compose.yaml up -d --wait
python3 scripts/register-schemas.py
python3 scripts/bootstrap-topics.py
./scripts/mvn.sh --batch-mode -Pintegration verify
```

Credentials are generated in ignored `.local/compose.env` with permissions0600. Never commit or paste them. For OrbStack installed without CLI links use its bundled `docker-compose` executable and export the engine socket as DOCKER_HOST if Testcontainers cannot discover it. Do not destroy volumes to work around tests.

Run the service after infrastructure starts:

```bash
set -a
source .local/compose.env
set +a
export MEMBER_DB_URL=jdbc:postgresql://localhost:5432/member
export MEMBER_DB_USER=member
export OIDC_ISSUER=http://localhost:8080/realms/ott
export OIDC_JWKS_URL=http://localhost:8080/realms/ott/protocol/openid-connect/certs
./scripts/mvn.sh -pl services/member-service spring-boot:run
```

Viewer signup/login uses local Keycloak `ott` realm, public `ott-viewer` client with PKCE; backend accepts JWTs with audience`ott-api`. Client redirect example`http://localhost:3001/callback` is local development only. Tokens/credentials must not appear in command logs. Probes `/actuator/health/liveness` and `/actuator/health/readiness` are public coarse health; Prometheus requires`metrics.read` scope. Other paths deny by default or require JWT/scopes.

Optional Compose profiles: `telemetry` (collector/Prometheus/Grafana), `search` (OpenSearch at phase7), `media` (Temporal dev at phase5). Dev profiles disable selected security only on localhost; they are not production deployment manifests. Stop with Compose`down` without`-v` to preserve local owner data.

## Repository

- `docs/requirements`, `docs/architecture`, `docs/security`, `docs/adr`: inventories and accepted design, data ownership, SLO/DR assumptions.
- `contracts`: OpenAPI validation, generated typed Avro and planned Protobuf DTO tooling; generation is not evidence a future producer/endpoint exists.
- `services/member-service`: implemented account/profile/device increment.
- `services/content-service`: implemented catalog/version/territorial-rights increment.
- `platform`: local infrastructure/IdP/telemetry configuration.
- `scripts`: repeatable checks and local setup; `.local/m2` is ignored dependency cache.
- `.github/workflows`: CI executes the real integration suite and optional SBOM profile.

[Roadmap](docs/architecture/implementation-roadmap.md) defines phases0–16. [Test strategy](docs/testing/test-strategy.md) and phase reports under`docs/operations` record PASS/FAIL/NOT RUN. Cloud provisioning, commercial certification, load and regional DR require their own executed evidence.

Content service uses its own PostgreSQL on5433 (`CONTENT_DB_URL=jdbc:postgresql://localhost:5433/content`, `CONTENT_DB_USER=content`, generated CONTENT_DB_PASSWORD), port8082, and the same configured OIDC issuer/JWKS. Run with `-pl services/content-service`. Current evidence: [Phase0](docs/operations/phase-0.md), [Phase1](docs/operations/phase-1.md), [Phase2](docs/operations/phase-2.md); Phase3 additionally implements prepaid orders, verified payment webhooks and entitlement; see [Phase3](docs/operations/phase-3.md). Later increments and their executed evidence are linked below.

Commerce uses its own PostgreSQL on5434 (`COMMERCE_DB_URL=jdbc:postgresql://localhost:5434/commerce`, `COMMERCE_DB_USER=commerce`, generated COMMERCE_DB_PASSWORD), port8083 and `PAYMENT_MODE=mock` for explicit local simulation. Generated PAYMENT_WEBHOOK_SECRET authenticates the local adapter's webhook protocol. Commercial adapters are unconfigured and mocks excluded from prod. `tests/journey` boots nine independent owners to verify the backend journey through publication, entitlement, playback, watch state, discovery, ads, notifications, analytics and support; the test IdP and commercial adapters are explicit local fixtures. Executable service jars now use `-exec.jar`; thin jars support test-only integration dependencies.

Playback control plane is verified in [phase4](docs/operations/phase-4.md), including durable concurrency, Redis loss/timeout, scoped owner checks, signed grants, offline metadata and the four-service journey. Clear DRM and an untrusted local CDN key require explicit mock mode; commercial protection remains unexecuted.

Local commands: `python3 scripts/bootstrap-local.py`, Compose with `.local/compose.env`, then `python3 scripts/bootstrap-workload-client.py`. After building, `python3 scripts/run-local.py playback` runs the owner on8084/DB5435; the same launcher supports member/content/commerce. Credentials stay in ignored0600 local configuration.

Actual media processing and its recovery evidence are in [phase5](docs/operations/phase-5.md). The integrated journey uses real Temporal/FFmpeg/S3/Kafka media publication. Local limits: MP4 single-PUT256MiB, expanded validated package1GiB, clear H264/AAC mock protection; commercial DRM and full-size production ingest remain extensions.

To run the local worker after building: Compose `--profile media up -d --wait media-postgres s3 temporal`, create task-owned buckets `ott-quarantine`/`ott-origin` with `docker exec ott-local-s3-1 awslocal s3api create-bucket --bucket <name>`, build `media/worker/Dockerfile`, then Compose `--profile media-worker up -d media-worker`. Run catalog/media APIs with `scripts/run-local.py content` and `scripts/run-local.py media`; `bootstrap-media-operator.py` creates the narrow local operator. `smoke-local-media.py` uses a synthetic source in `.local/media-smoke/source.mp4`, withholds tokens/URLs, and verifies actual publication. No real feeds or DRM keys needed.

Phase 6: profile-owned watch state, server-epoch ordering, rewind/resume, watchlists and reactions are verified locally. See [phase evidence](docs/operations/phase-6.md). Run `python3 scripts/bootstrap-local.py`, `python3 scripts/bootstrap-experience-client.py`, enable the Compose `experience` profile, and launch `python3 scripts/run-local.py experience` after packaging. Commercial integrations, remaining phases and production claims are explicitly tracked separately.

Search/discovery and deterministic consented recommendations are verified locally: [Phase 7](docs/operations/phase-7.md), [Phase 8](docs/operations/phase-8.md). Enable the Compose `search` profile and update the local Experience client to content.internal + playback.internal. Personalization defaults off; preference opt-in is explicit. Sparse popularity falls back to recent releases with response provenance.

Live/sports control is verified with signed finite synthetic source proof: [Phase 9](docs/operations/phase-9.md). Provision the dedicated packager using `python3 scripts/bootstrap-live-packager.py`; use its printed subject when creating an event. The provider signs raw proof bodies with LIVE_FEED_SECRET. Production contribution/encoder/CDN services remain external, and no business API transports segments.

Advertising uses the optional Engagement owner on port 8088: [Phase 10](docs/operations/phase-10.md). Enable Compose `engagement`, provision `bootstrap-engagement-client.py`, and run `run-local.py engagement`. Contextual CSAI metadata, durable caps, impression/quartile ingestion and an external SSAI HTTP boundary are verified locally. Kids profiles receive no ads; commercial stitch/media/deliverability and billable-viewing claims remain unvalidated.

Notifications are asynchronous in Engagement: [Phase 11](docs/operations/phase-11.md). In-app reminders and payment-failure notices use typed owner events and an owned inbox. External channels require verified signed identity claims, encrypted routing and a configured provider; no commercial message was sent in local verification. Run bootstrap-local for the ignored contact key and bootstrap-topics/register-schemas for notification topics/DLQs.

Insights (Phase12) runs on8089 with its own optional `insights` Compose database profile5439. Generate ignored settings with `scripts/bootstrap-local.py`, provision the scoped `scripts/bootstrap-insights-client.py`, and launch `scripts/run-local.py insights`. Analytics and experiment consent default off; see [Insights contracts](contracts/openapi/insights.yaml) and [ADR024](docs/adr/ADR-024-consented-telemetry-and-experiments.md). The integrated journey tests actual grant-bound QoE, compatible Registry schemas and owner business events. Cloud analytics archival, measured player accuracy/throughput and experiment impact remain unvalidated.

Phase13 adds Operations on8090 (optional Compose `operations` database5440), auditable support/risk cases and scoped idempotent owner commands. Profile selection locking is explicitly enabled through Member parental-control APIs; locked adult playback/heartbeat/download requests carry `X-Profile-Authorization`. Immediate cancellation revokes access without claiming a provider refund. Current offline license checks revalidate owner state. Local minimization/retention does not establish complete privacy erasure or regulatory compliance.


Infrastructure and hardening evidence: [Phase14](docs/operations/phase-14.md), [Phase15](docs/operations/phase-15.md), [deployment guide](infrastructure/README.md). All nine owner images plus the native worker are buildable. Helm includes terminating migrations, restricted pods, probes, PDB/HPA and default-deny policies; a disposable single-node kind drill exercises migration, DB outage and rolling recovery. AWS Terraform has schema/security-plan checks; no billed cloud infrastructure was provisioned. Production mode rejects commercial-dependent owners until real providers are implemented.

For the complete repeatable local verification, run:

```bash
python3 scripts/architecture_check.py
python3 scripts/security_check.py
./scripts/mvn.sh --batch-mode -Pintegration,recovery,sbom verify
python3 scripts/build-images.py
python3 scripts/build-images.py worker
```

The final combined local gate passed179 tests across32 classes with zero failures/errors/skips. The additional recovery profile snapshots the synthetic journey into ignored `tests/journey/target/recovery-evidence`; it restores all owned databases, archived media and Kafka/Registry data, verifies duplicate replay and rebuilds OpenSearch. See [Phase16 evidence](docs/operations/phase-16.md) and the [recovery runbook](docs/operations/recovery-runbook.md). This is quiesced local recovery, not regional failover or measured cloud RPO/RTO.

Release limitations are tracked in [scan applicability](docs/security/scan-applicability.md) and the phase reports. Commercial DRM/payment/live delivery, cloud effective permissions/secret rotation, sustained production load/SLOs, complete privacy erasure, managed-service logging and regional DR remain open. One visible Spring MVC package vulnerability requires the documented applicability review or a supported framework upgrade; the scanner result is not suppressed.


For a full localhost runtime, enable Compose profiles `media`, `search`, `experience`, `engagement`, `insights`, `operations`; provision local workload clients with the matching `bootstrap-*-client.py` helpers (Playback uses `bootstrap-workload-client.py`), and create the private development buckets before starting the worker. `python3 scripts/local-platform.py start` launches all nine APIs using `run-local.py`'s per-owner credential allowlist and waits for readiness. `status` reports tracked API health; `stop` gracefully stops only its recorded processes after verifying their exact jar command. It rejects untracked occupied ports and keeps private logs/PIDs in ignored `.local/apis`. It does not replace Compose or provision real providers. See [local runtime guide](docs/operations/local-runtime.md) for the complete sequence.
