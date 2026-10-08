# Authentication

Keycloak dev realm `ott` with viewer public PKCE client, configured issuer/audience `ott-api`; administrative bootstrap generated locally and not committed. Production OIDC issuer configured by environment and HTTPS required. Backend credentials/password registration endpoints absent by design: IdP manages login, MFA, password reset and credential abuse.

Resource servers validate aud, iss, exp, nbf, signature and approved algorithm. JWKS caching bounded and unknown kid refresh throttled to prevent amplification. No unsigned tokens or caller-controlled header identity. Account identity is composite issuer+subject (subject alone can collide across realms). Stateless bearer APIs CSRF disabled only for authorization-header mode, restrictive CORS/no wildcard credentials. Cookies need separate CSRF design.

Workload tokens use distinct audience/internal scopes and shortTTL; IdP unavailable means new login unavailable, valid cached-key tokens can continue until expiry. Compromised key incident rotates/revokes provider and bounds exposure by tokenTTL. Integration tests exercise invalid audience, wrong issuer, expired and tampered tokens; mocked JWT tests alone cannot prove cryptographic validation.
