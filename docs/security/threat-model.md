# Threat model

Trust boundaries: untrusted clients→gateway→owner APIs; external provider webhook→commerce; upload→sandbox worker; control plane→signer/DRM; media origin→CDN; operators→privileged commands. Threat ownership follows service catalog.

| Threat | Mitigation | Evidence required | Residual risk / launch dependency |
|---|---|---|---|
| Account takeover/credential stuffing | IdP MFA/PKCE/rate limits, login risk and support recovery audit | IdP lockout/invalid login burst | IdP recovery policy |
| Token theft/replay | short expiry/audience/device-bound license, key rotation, no log tokens | tampered/expired/wrong audience tests | CDN bearer replay withinTTL remains |
| API abuse/DDoS | WAF, per-route principal rate, bounded bodies/pools and CDN | burst/oversize/overload tests | external scrubbing/provider capacity |
| Privilege escalation | default deny scopes+owner ABAC; no private DB reads | viewer→admin and cross-account tests | operator access review |
| SQL injection | parameterized JDBC; whitelist sorting | malicious filter/name tests | migration privilege isolation |
| SSRF | no arbitrary ingest URL; private-key prefixes; network allowlist | localhost/metadata URL rejected | media parser sandbox |
| Payment fraud/webhook replay | raw signature/time, inbox, amount/currency checks, reconciliation | invalid sig/replayed/mismatched/out-of-order tests | provider fraud engine selection |
| Content piracy/DRM abuse | scoped CDN cookies/OAC, license binding, provider KMS/HSM | unauthorized segment/license/expiry tests | mock DRM is unprotected |
| Geo circumvention | trusted edge country, geo provider, anomaly signals | spoofed header, disallowed region tests | VPN/geo false positives policy |
| Audit tampering/privacy | append-only role, encrypted archive, log minimization, purpose control | unauthorized update/log inspection | legal retention/residency confirmation |
