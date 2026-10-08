# OTT architecture overview

## Scope

All FR-001..037 are in technical scope. The target covers VOD/live/sports, member and commerce lifecycle, playback rights/DRM/offline policy, media processing, watch state/search/recommendations, advertising/notifications, analytics/QoE/experiments, fraud/support/admin, infrastructure and recovery.

Out of scope: native mobile/TV/web clients, proprietary ML, acquisition of sports feeds, commercial DRM keys, card storage, actual cloud provisioning without account/budget and jurisdiction-specific tax/compliance certification. Provider boundaries are in scope; credentials and vendor approval are launch dependencies.

## Deployment boundaries

Nine service owners: member, content, commerce, playback, media, experience, engagement, insights and operations. Platform configuration is not a business service. Grouping preserves local ACID where rules naturally belong together; eventual event projections handle cross-owner integration. No private database reads across services. Extraction needs measured scaling or ownership evidence, not one bounded context per process.

Public clients use REST via an authenticated rate-limited gateway. Playback makes bounded parallel REST reads to member/content/commerce; no gRPC justification exists yet. Kafka carries integration events and fan-out. Media workers fetch private objects and emit ready metadata; business services return metadata/authorizations only. S3 + CDN serve HLS/DASH/CMAF bytes directly.

Critical path uses authoritative eligibility where revocation matters; cache policy never extends rights/subscription expiration. Optional recommendations, ads, telemetry and notifications cannot gate playback. Async failure does not roll back an already committed subscription.

See [domain model](domain-model.md), [service catalog](service-catalog.md), [C4](../diagrams/c4.md), [runtime sequences](../diagrams/sequences.md), [transactions](transactions.md), and [roadmap](implementation-roadmap.md).

## Reference verification

[Spring Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) supports Java 21. [Confluent schema evolution](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html) informs Avro compatibility. [CloudFront private content](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-choosing-signed-urls-cookies.html) supports signed cookies for multiple HLS resources. These primary references are implementation guidance; no supplied proprietary reference document was found.

Inspection update: `prompt.md` appeared during execution, was read, and contains the supplied user execution/requirements prompt; preserved unchanged. No separate proprietary reference or additional FR/NFR attachment was found.
