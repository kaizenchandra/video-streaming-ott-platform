# Domain model and invariants

| Owner | Bounded contexts | Aggregates and invariants |
|---|---|---|
| member | Identity integration, account, profile, device | Account unique by issuer+subject; profile belongs to account; kids ceiling cannot exceed configured maturity; active devices bounded under account row lock; revoked device cannot play |
| content | Catalog, metadata/localization, rights, publishing, live/sports | Published version immutable; hierarchy typed; license is `[start,end)` plus territory/device/format; publication requires ready media; live scheduled→ready→live→ended→archived, cancelled terminal |
| commerce | Subscription, billing, payment, entitlement | Order fixes amount/currency; only verified matched provider success activates; provider event unique; entitlement derived from subscription; refund/revoke never reordered into activation |
| playback | Authorization, session, DRM/offline | Eligibility and lease precede grant; grant bound to account/profile/device/content; max streams enforced atomically; heartbeat cannot resurrect expired/revoked session; offline limited by rights/plan/license |
| media | Ingestion, validation, transcoding, packaging, origin metadata | Upload scoped to owned key; job/version immutable; validated package before ready; workflow retry does not publish duplicate asset; content-addressed objects |
| experience | Watch progress/history, watchlist/reaction, search/discovery, recommendation/trending | Profile-owned state; sequence/version checks preserve rewind and suppress stale updates; search is projection, never eligibility authority |
| engagement | Ad decisions/measurement, notifications/reminders | Ad-free tier cannot receive commercial ads; kids targeting restricted; caps atomic; delivery respects preferences at send time; duplicate event doesn't cause duplicate intent |
| insights | Analytics/QoE, experiments/flags | Event ID dedupe; bounded telemetry sizes; no PII in metric labels; safety-critical access never decided by experiment |
| operations | Fraud/abuse, moderation review, admin/support/audit | Scoped command APIs to owners; no cross-DB mutations; audit append-only; sensitive support reads purpose-bound |

Ubiquitous types: AccountId/ProfileId/DeviceId/ContentId/SessionId UUIDs, immutable money(amountMinor,currency), UTC Instants, BCP47 locale and ISO country. Entities remain private to owner adapters; APIs expose immutable records. No shared mutable domain common library.
