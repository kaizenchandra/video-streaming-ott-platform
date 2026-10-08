# Functional requirements

Source: user request dated 2026-10-08 and root `Agents.md`. Inspection found no separate FR, NFR or Netflix reference artifacts. Each row is confirmed requested scope; implementation status is separately tracked. No feature is implicitly implemented by this inventory.

| ID | Requirement | Owner | Phase |
|---|---|---|---|
| FR-001 | Accounts and OIDC authentication | member | 1 |
| FR-002 | Profiles, kids profiles and parental controls | member | 1 |
| FR-003 | Device registration, capabilities and revocation | member | 1 |
| FR-004 | Movie, series, season, episode and sports metadata | content | 2 |
| FR-005 | Localization, subtitles, audio and content versions | content | 2 |
| FR-006 | Territorial licensing and time-window rights | content | 2 |
| FR-007 | Publishing workflow and moderation | content | 2 |
| FR-008 | Plans, ad-supported tiers and subscriptions | commerce | 3 |
| FR-009 | Billing, invoices and payment adapters | commerce | 3 |
| FR-010 | Verified webhooks and reconciliation | commerce | 3 |
| FR-011 | Entitlement activation, expiration and revocation | commerce | 3 |
| FR-012 | Playback authorization and geo/device checks | playback | 4 |
| FR-013 | Concurrent streams, heartbeat and session expiration | playback | 4 |
| FR-014 | Short-lived tokens and signed CDN access | playback | 4 |
| FR-015 | Widevine, FairPlay and PlayReady provider boundaries | playback | 4 |
| FR-016 | Offline download/license authorization | playback | 4 |
| FR-017 | Ingestion, validation and workflow recovery | media | 5 |
| FR-018 | FFprobe, configurable ABR and HLS/DASH/CMAF packaging | media | 5 |
| FR-019 | S3 origin, CDN, thumbnails and DRM packaging boundary | media | 5 |
| FR-020 | Progress, history, resume and continue watching | experience | 6 |
| FR-021 | Watchlists and ratings/reactions | experience | 6 |
| FR-022 | Search, autocomplete, typo tolerance, filters and ranking | experience | 7 |
| FR-023 | Discovery rails and home aggregation | experience | 7 |
| FR-024 | Popular, trending, genre/actor and recent recommendations | experience | 8 |
| FR-025 | Live/sports schedules, fixtures and event state | content | 9 |
| FR-026 | DVR, catch-up, replay, highlights and commentary | content | 9 |
| FR-027 | Flash crowd planning and live CDN authorization | playback | 9 |
| FR-028 | Ad eligibility, slots, targeting and CSAI/SSAI adapters | engagement | 10 |
| FR-029 | Frequency caps, impressions and quartile tracking | engagement | 10 |
| FR-030 | Notification preferences, email/SMS/push/in-app | engagement | 11 |
| FR-031 | Content/live reminders and payment-failure notifications | engagement | 11 |
| FR-032 | Play/pause/complete/buffer/quality/search/purchase/ad events | insights | 12 |
| FR-033 | QoE startup, rebuffering, bitrate, resolution, device/CDN/region | insights | 12 |
| FR-034 | Experimentation and feature flags foundation | insights | 12 |
| FR-035 | Fraud/abuse, device, credential, stream and geo signals | operations | 13 |
| FR-036 | Administration, support, auditing and integrations | operations | 13 |
| FR-037 | Cloud deployment, resilience, hardening and recovery | platform | 14 |

Inspection update: `prompt.md` appeared during execution, was read, and contains the supplied user execution/requirements prompt; preserved unchanged. No separate proprietary reference or additional FR/NFR attachment was found.
