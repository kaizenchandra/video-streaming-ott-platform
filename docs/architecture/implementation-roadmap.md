# Implementation roadmap

Implement dependency-aware increments in this order; do not advance a failed correctness gate. Foundation operational prerequisites must be available before business integration. Each phase must update traceability, contracts and evidence reports. Target scope is complete only when all applicable gates pass.

| Phase | Increment | Prerequisites | Gate |
|---|---|---|---|
| 0 | Engineering foundation | Architecture Gate | Build/security/contracts + container infrastructure |
| 1 | Identity / account / profile / device | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 2 | Catalog / metadata / rights | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 3 | Subscription / payment / entitlement | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 4 | Playback control plane / DRM / offline | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 5 | Media ingestion / processing | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 6 | Watch state / collections / reactions | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 7 | Search / discovery | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 8 | Trending / recommendations | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 9 | Live / sports control plane | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 10 | Advertising | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 11 | Notifications | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 12 | Analytics / QoE / experiments | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 13 | Fraud / abuse / admin / support | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 14 | Kubernetes / AWS infrastructure | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 15 | Production hardening | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |
| 16 | DR / multi-region validation | Prior dependency phases; see test strategy | Domain/API/persistence/security/failures + real integration; documentation |

Phase gates and actual evidence live in `docs/operations/phase-*.md`. Commercial provider certification and regional cloud drills may remain external blockers; do not report those phases complete by merely creating adapters/IaC.


## Current executed state

Architecture Gate passed before business code. Phases0–13 have local vertical-slice gates and owner-specific reports. Phase14 packages all nine owners/native worker, tests Helm/Terraform semantics and runs an isolated restricted container. Phase15 adds scan-driven patches, actual HTTP/OTLP trace checks, a local authenticated load sample and real single-node kind migration/outage/rolling recovery. Phase16 adds isolated quiesced PostgreSQL/media/event/schema restore and search rebuild to the integrated journey. See each `docs/operations/phase-*.md` for actual verification and explicit limitations.

These are implemented local increments. Full production Phase14–16 completion is blocked by unconfigured commercial providers and a regional cloud validation environment, plus documented logging/network/secret/compatibility release gates. The repository does not equate an IaC plan or mock adapter with deployed commercial operation, regional recovery, regulatory compliance or production SLO validation.
