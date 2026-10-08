# Security architecture

OAuth2/OIDC Authorization Code+PKCE at IdP; platform resource servers validate JWT signature/issuer/audience/time. TLS everywhere; ingress WAF/rate/body-size limits; every owner validates subject ownership and operation scopes. RBAC for editor/admin/support, ABAC for profile maturity, plan, territory, rights, device and purpose. OPA deferred until policy complexity warrants another availability dependency.

Least-privilege per-service DB/Kafka/S3 roles, no cross-owner DB grants. Workload identity obtains cloud permissions without static IAM keys. Restricted operators issue owner commands, never connect to business DB. Privileged actions require durable audit before acceptance; append-only audit DB role and S3 Object Lock/WORM where justified by legal policy.

Privacy: no passwords/PAN/DRM keys in platform stores; minimize OIDC claims and viewing telemetry; encrypted KMS storage/backups; consent and regional bucket boundaries; purpose-bound support access. Deletion orchestrates owner commands+projection tombstones; retention exceptions recorded. Compliance is a policy/legal launch dependency, not a technical guarantee.

Secure coding: parameterized SQL, validated bounded payloads, source URL allowlists/no arbitrary fetch, sandbox FFmpeg, constant-time webhook signature compare, approved JWT algorithms, no sensitive data in logs/errors/metric labels. API auth/ownership regression tests and dependency/container scanning required before release. Non-production mocks excluded from production profile/startup.
