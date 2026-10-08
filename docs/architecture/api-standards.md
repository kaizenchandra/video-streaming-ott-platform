# API and communication standards

External: REST JSON under `/v1`, OpenAPI 3.1. Internal: REST under `/internal/v1`, workload JWT/mTLS; no public route exposure. Kafka for state integration/fan-out. gRPC/Protobuf contracts reserved for future measured need; no runtime RPC dependency now. Public DTOs are records and never persistence entities.

Authentication: OIDC issuer, audience, expiry/not-before, signature/algorithm allowlist. Subject mapped by issuer+sub; client-supplied account ID never confers ownership. Operators have explicit scopes; viewers must own profile/device. Kids maturity enforced on browse/playback. Registration/login at IdP with Authorization Code+PKCE. No passwords stored in backend.

Validation: bounded string/body sizes, UUID IDs, ISO country, BCP47 locale, UTC timestamps, explicit money integer range and currency. Do not accept arbitrary source URLs or sort expressions. Page size default 20/max100, opaque stable `(updated_at,id)` cursor; allowlisted sort/filter, deterministic ordering. Caption/audio-description fields support accessibility; clients handle presentation.

`Idempotency-Key` required on purchase and session-create; max128 ASCII. Scope `(subject,operation,key)`; canonical request hash; equal payload returns prior result, different payload 409 `IDEMPOTENCY_CONFLICT`; in-progress 409/Retry-After. Store response excluding secrets, token replay returns same bounded-expiry grant only while valid, otherwise require new key. PostgreSQL unique constraints settle races.

Problem Details: `type`, `title`, `status`, `detail` (safe), `instance`, stable `code`, `traceId`; never echo credentials, SQL, PII or internals. 400 input; 401 unauthenticated; 403 forbidden/ineligible; 404 missing or non-owned resource; 409 state/idempotency; 429 bounded rate; 503 unavailable dependency. Domain codes: SUBSCRIPTION_INACTIVE, CONTENT_NOT_AVAILABLE, CONTENT_RIGHTS_EXPIRED, REGION_NOT_ALLOWED, CONCURRENT_STREAM_LIMIT_REACHED, DEVICE_NOT_ALLOWED, DRM_LICENSE_DENIED, PAYMENT_DECLINED, IDEMPOTENCY_CONFLICT.

W3C traceparent propagated; gateway assigns correlation UUID if absent/invalid. No arbitrary unbounded header reflected. TLS externally and internally; service deadlines carried and bounded locally. POST create→201+Location; GET→200; idempotent DELETE→204; async media intake→202+job Location. PATCH uses explicit field whitelist/If-Match version.

Compatibility: additive optional fields within v1; do not silently repurpose values. New incompatible resource semantics require v2 plus deprecation/Sunset with at least one supported client release window (business-approved). Event enum consumers tolerate unknown values. No claim a draft future contract is already implemented.

Representative contracts live under `contracts/`. Per-phase implementation must update endpoint schemas and tests together. Contract gate validates OpenAPI, Avro and Protobuf tooling, then exercises actual request/response behavior.
