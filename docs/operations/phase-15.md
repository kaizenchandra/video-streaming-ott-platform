# PHASE: 15 — Local Hardening and Evidence

## Implemented
Targeted scan-driven patches, per-owner HTTP observations and captured parallel playback context, actual OTLP export/privacy fixture, bounded latency histograms, REST-only architectural security check/CI SBOM artifact correction, OS image patching, source/IaC/package/container scans, local authenticated k6 load, isolated Docker privileges/outage drill and real kind Helm rollout/outage/recovery. [ADR027](../adr/ADR-027-scan-driven-patches-and-http-observations.md) and [scan applicability](../security/scan-applicability.md) record residual findings. No blanket security/readiness claim.

## Requirements / Architecture / APIs / Events / Database
NFR security/observability/testing/reliability/deployment; business ownership, Java21/Boot3.5.16/MVC and existing APIs/events/migrations preserved. No business contract or schema changes. All25 manually constructed RestClients bind their owner ObservationRegistry; playback parallel tasks capture per-owner observation context. Async outbox traceId remains optional/null: persisted request→event trace causality is an explicit observability limitation, not claimed implemented.

## Security / Observability
Jackson2.21.7, Netty4.1.137, Tomcat10.1.60, pgjdbc42.7.12, gRPC1.75 BOM, OTel1.62, patched LZ4/Log4j/commons utilities verified against actual jar/SBOM paths. Template/view rendering forbidden by CI boundary; remaining Spring XSLT package finding is visible with expiry2026-11-07. Generic base OpenSSL and worker GPG/OpenSSL patches; base/native findings evaluated independently. Histograms bound5ms..3s and metrics.read remains required. OTLP test sees the known W3C trace ID without the bearer credential; parallel owner test sees one trace on four real HTTP requests and no credential baggage.

## Verification
PASS:
- Recovered full `-Pintegration verify`:178 tests, zero failures/errors/skips, all nine owners plus actual native Temporal/S3/Redis/Kafka/Registry/OpenSearch journeys, schema compatibility and failure paths.
- Additional focused Member exporter/security/metrics suite: actual trace export with authenticated request, known parent trace and no bearer token in OTLP; real PG health/outbox rollback and scope enforcement pass. Final aggregate gate includes this test in Phase16.
- Aggregate CycloneDX regenerated; dependency scan37→one visible Spring package finding, no remaining initial applicable high/critical dependency findings. Source secret scan zero findings; infrastructure52→49, zero high/critical. See applicability document; medium/low findings remain visible.
- Actual Member image and k6/container evidence from Phase14 plus OpenSSL rebuild/rescan; local image now has no OS high/critical finding, remaining package finding plus medium/low OS entries.
- Helm/semantic guards, including required regional API WAF attachment and production release/namespace role binding; two Terraform mocked security plans after CMK/CloudFront WAF additions.
- Patched native worker: actual headless restricted startup, FFmpeg execution, business path404, stopped Temporal readiness503 and liveness200; worker Docker readiness healthcheck added; final image healthcheck verification is recorded in Phase16.
- Real disposable kind1.37 single-node: Helm migration hook, Pod Security restricted owner startup, DB outage removes readiness without liveness restart, PVC-backed database restart/migration repetition and rolling owner recovery. Cluster and private kubeconfig cleaned; existing cluster context not changed.
FAIL: no final code/test failures in recovered gate. Interrupted first hardening run did not complete because Docker API stopped responding; OrbStack stop/start preserved containers/volumes and restored access. Initial kind Postgres image archive import failed due partial platform metadata; switched to normal kubelet pull and reran successfully. Focused exporter first attempt omitted explicit OrbStack endpoint; corrected and reran.
NOT RUN: cloud/regional chaos, actual Multi-AZ/ALB/Argo/IAM/WAF/production autoscaling, kindnet NetworkPolicy enforcement, sustained/full workload load/SLO validation, commercial DRM/payment/live/notification/ad integrations, independent native FFmpeg vulnerability review, deployed production telemetry backends/alerts.

## Remaining Work / Risks
Local owner snapshot/restore, event/schema archive replay, Redis loss and search/origin rebuild are Phase16. Cloud/service logging, fine-grained transport permissions, native media risks, maintained framework line and expiring applicability review remain release gates. Resource sizing/load result is one local account-read scenario. Production hardening remains unvalidated where credentials, cloud environment, traffic and operational policy are absent.

Phase Gate: PASS for the local hardening increment; no production-readiness claim.
