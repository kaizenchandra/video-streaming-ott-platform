# ADR-027 — Scan-driven patches and HTTP observations

## Context
Phase15 executed CycloneDX/Trivy scanning found patched vulnerabilities in the existing Boot-managed dependency lines and the Temporal SDK's gRPC1.58.1. Existing manually created RestClient builders also bypassed Boot's HTTP observation registry, leaving remote-owner trace continuity incomplete. NFR security/observability demand evidence and targeted corrections.

## Decision
Keep Java21/Boot3.5.16, MVC/hexagonal owner boundaries and all business contracts. Override only scan-driven compatible dependency versions: Jackson BOM2.21.7, Netty4.1.137.Final, Tomcat10.1.60, PostgreSQL42.7.12, and an aligned gRPC1.75.0 BOM for Temporal. Independently patched utility/telemetry artifacts are selected only after inspecting actual runtime/SBOM paths. Execute all integration journeys after the overrides, including native Temporal workflows and Kafka/Registry compatibility.

Bind manually constructed HTTP clients to the injected per-application Micrometer ObservationRegistry. Use W3C propagation through Boot tracing handlers; avoid recording bodies, bearer credentials, query PII or user/device IDs as metric labels. Verify actual received trace headers, not just constructor configuration. Archive bounded scan summaries and local load/recovery evidence with explicit applicability and remaining findings.

## Alternatives
Suppressing scanner findings hides evidence; a broad Boot4/Spring7 migration changes too much at once. Spring6.2's XsltView fix is enterprise-only: do not imply an OSS patch exists. The platform's REST-only controllers have no XSLT/view rendering; enforce that architectural exclusion and record the flagged package with an expiring applicability review. Static shared tracing state would couple owners and is unnecessary.

## Rationale
The upstream [Spring advisory](https://spring.io/security/cve-2026-47884/) requires XsltView and an implicit view name; [Jackson2.21 patches](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21), [gRPC1.75](https://github.com/grpc/grpc-java/releases/tag/v1.75.0), [Tomcat security](https://tomcat.apache.org/security-10), [Netty patch](https://github.com/netty/netty/releases/tag/netty-4.1.137.Final), and [pgjdbc patch](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.12) justify targeted changes. Scanner package presence is evidence to review, not proof that every exploit path is reachable.

## Consequences
Versions intentionally diverge from the unchanged Boot parent BOM and require regression verification. The XSLT finding remains visible until a supported migration/backport or future package removal; REST-only exclusion does not guarantee all future Spring flaws are inapplicable. Baseline scans are time-specific, never proof of full security.

## Operational Impact
Regenerate SBOM/scans on every release; compare actual image contents to aggregate/test components. Gate new applicable high/critical findings; record bounded exemptions with reason/expiry and rerun when dependencies/controllers change. Production TLS/identity/export backends and cloud chaos/SLO validation remain separate checks.

The executed Member image scan also found the base image's libssl3 patch behind the Ubuntu security fix (CVE-2026-84782). Upgrade that existing package during runtime image construction, without installing extra runtime tooling. Rebuild/rescan images and use release digests; package repository availability remains part of the reproducible release process.
