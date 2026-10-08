# Scan applicability and release gates — 2026-10-08

Executed Trivy0.75.0 from the upstream immutable release, published SHA256 verified; vulnerability DB downloaded2026-10-08. No source/secrets uploaded. Initial aggregate CycloneDX:37 findings (5critical,12high,20medium); patched aggregate:one critical package finding. Infrastructure scan initially52 findings (3high,22medium,27low); after media CMK/CloudFront WAF changes49 remain (zero high/critical,22medium,27low). Source secret scan excluding ignored local data/build outputs:zero findings. These are time-specific results; tool completion is not a statement that the application or infrastructure is fully secure.

## Remaining dependency finding

CVE-2026-47884, Spring MVC6.2.19, scanner severityCRITICAL; upstream Spring rates MEDIUM. The [upstream advisory](https://spring.io/security/cve-2026-47884/) requires XsltView and implicit view rendering from a wildcard mapping. This platform has26 REST controllers, no XsltView/ModelAndView/templates or implicit view mapping. `scripts/security_check.py` enforces that source boundary in CI and does not suppress the scanner record. Applicability review expires2026-11-07, immediately on introducing view rendering, changing framework components or receiving contradictory evidence. The Spring6.2.20 fix is enterprise-only; the current OSS line is not falsely described as patched. Boot4/Spring7 migration or licensed6.2 maintenance is a separate, justified follow-up. Production release review must accept this explicit residual package exposure; no blanket risk acceptance is implied.

## Remaining infrastructure findings

AWS-0010/0042/0074/0089/0178: CloudFront/S3/OpenSearch/MSK/VPC access/flow logging not provisioned in this reference Terraform. EKS control-plane audit logs and application structured logs are configured, but managed-service logging retention/privacy/permissions need a separate cloud integration before production release.

AWS-0133: RDS Performance Insights not provisioned; add when cloud telemetry/cost/retention policy is defined. AWS-0176: RDS IAM DB authentication is disabled deliberately; managed master Secrets bootstrap separate owner DML users, TLS/app Secret rotation and least privilege are required. IAM authentication is an alternative, not a proof of correctness or an implicit implemented adapter.

AWS-0099/0124: security group/rule descriptions need refinement before cloud review; group rules themselves are restricted to the EKS cluster SG/private broker-self path. KSV-0110: scanner's default Helm render uses the default namespace; deployment instructions require namespaceott. KSV-01010: ConfigMap contains explicit mock mode values and workload token endpoint names, not credentials; actual credentials are owner existing-Secret references. Review rendered production overlay; never put passwords/crypto keys/bearer tokens in ConfigMaps. The chart's per-owner transport precision, worker IAM split, live ALB routing/rule quota, Kafka ACLs and Registry authentication are explicit deployment gates.

## Applied patches / observations

Keep Boot3.5.16/Java21; Jackson2.21.7, Netty4.1.137, Tomcat10.1.60, pgjdbc42.7.12 and aligned gRPC1.75.0; telemetry1.62.0 closes unbounded baggage handling, commons-lang3/Log4j/LZ4/compress fixes close remaining initial medium findings. `commons-compress1.24.0` was actually in the Media executable despite an aggregate BOM warning: runtime jar inspection justified aligning1.26.2 rather than dismissing it as test-only. All25 manual RestClients bind owner ObservationRegistry; playback parallel checks use a per-owner context snapshot executor. An actual HTTP fixture verifies one W3C trace across four synchronous/parallel owner calls and no credential baggage. Bodies, PINs, access tokens, contact details and signing keys are not deliberately traced/logged.

## Release policy

Generate package SBOM, scan each immutable image and source/IaC, enforce REST boundary and run integration/contract/journey gates on every release. New applicable high/critical findings block release. Keep raw reports under ignored `.local/security`; publish sanitized aggregate/evidence through controlled CI artifacts. Base image/native FFmpeg findings require their own applicability analysis and compatible image patch, not a blanket scanner suppression. Private commercial provider, cloud effective IAM/network/secret rotation/DR and browser/client protections remain outside the local scan evidence.
