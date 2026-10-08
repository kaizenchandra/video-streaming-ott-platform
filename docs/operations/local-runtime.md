# Local nine-owner runtime

This is an explicit localhost development environment, with mock commercial payment/DRM protection and synthetic live proof. It is not a production configuration. Java21, Maven3.9+, Docker/Compose and FFmpeg/native worker prerequisites apply. Secrets are generated once into ignored0600 `.local/compose.env`; existing values and volumes are preserved.

## Start

```bash
python3 scripts/bootstrap-local.py
./scripts/mvn.sh --batch-mode -Pintegration,recovery,sbom verify
python3 scripts/build-images.py
python3 scripts/build-images.py worker
docker compose --env-file .local/compose.env -f platform/compose/compose.yaml \
  --profile media --profile search --profile experience --profile engagement \
  --profile insights --profile operations up -d --wait
python3 scripts/register-schemas.py
python3 scripts/bootstrap-topics.py
python3 scripts/bootstrap-workload-client.py
python3 scripts/bootstrap-experience-client.py
python3 scripts/bootstrap-engagement-client.py
python3 scripts/bootstrap-insights-client.py
python3 scripts/bootstrap-operations-client.py
python3 scripts/bootstrap-media-operator.py
python3 scripts/bootstrap-live-packager.py
```

Create task-owned development buckets `ott-quarantine` and `ott-origin` if absent: `docker exec ott-local-s3-1 awslocal s3api head-bucket --bucket <name>` then `create-bucket` only when missing. No real S3 account is used. Start the worker and API processes:

```bash
docker compose --env-file .local/compose.env -f platform/compose/compose.yaml \
  --profile media --profile media-worker up -d --no-build --wait media-worker
python3 scripts/local-platform.py start
python3 scripts/local-platform.py status
```

| Owner | API port | Owned PostgreSQL port |
|---|---|---|
| Member |8081|5432|
| Content |8082|5433|
| Commerce |8083|5434|
| Playback |8084|5435|
| Media |8086|5436|
| Experience |8087|5437|
| Engagement |8088|5438|
| Insights |8089|5439|
| Operations |8090|5440|

All API listeners bind127.0.0.1; the worker has no viewer/business listener. Its Docker healthcheck verifies the private8091 readiness surface. Coarse `/actuator/health/readiness` and liveness are public; business routes require OIDC audience/scope/ownership, and Prometheus requires metrics.read. Keycloak realmott is8080, OpenSearch9200, local S3 endpoint4566; exposed infrastructure ports also bindlocalhost. Local mocked media goes from the client to origin/CDN boundaries, never through business APIs.

Viewer authentication is through Keycloak's publicott-viewer PKCE client; bootstrap helpers create narrowly scoped local service/operator accounts. The launcher delegates to `run-local.py`, which passes only the selected owner's database and relevant workload secrets. Logs/PIDs stay in ignored `.local/apis` with restrictive permissions. Startup waits for recorded process plus actual readiness; untracked occupied ports are rejected. It is an operator convenience, not a production process supervisor.

`python3 scripts/smoke-local-media.py` produces and verifies real synthetic source→Temporal/FFmpeg→HLS/DASH/CMAF→S3→catalog publication without printing bearer tokens or signed URLs. The complete authenticated subscription/playback/watch/ads/notification/analytics/support journey is repeatable in `tests/journey` against isolated fixtures, with raw synthetic recovery outputs under ignoredtarget.

## Stop and recovery

`python3 scripts/local-platform.py stop` sends graceful SIGTERM only to recorded PIDs whose command still matches that exact owner jar; it never force-kills another listener. `stop <owner>` and `start <owner>` operate on a chosen owner. Stop Compose with `down` without `-v` when desired; do not destroy volumes to fix tests. LocalStack development persistence is not a production origin durability guarantee; republish deterministic synthetic media if the emulator is empty. Temporal's Compose PostgreSQL volume retains dev history, but its regional restore has not been validated.

On macOS OrbStack without CLI links, use its bundled docker/docker-compose binaries and set `DOCKER_HOST=unix:///Users/mwgbh/.orbstack/run/docker.sock` for this host's Testcontainers; other hosts should set their actual local endpoint. No credentials belong in shell arguments, logs, Git or exported documentation. Cloud/production prerequisites and known gaps are in [infrastructure guide](../../infrastructure/README.md) and [scan applicability](../security/scan-applicability.md).


## Clock diagnostic

Host APIs and Docker identity/payment/media services must have synchronized clocks. An executed local run found OrbStack's VM about15minutes behind the host after runtime recovery: fresh Keycloak credentials failed timestamp validation. Quiescing only task-owned APIs/containers, stopping/starting the VM without reset and restarting preserved Compose services restored alignment within1s. JWT expiry/issuer/audience validation was never weakened. Compare host UTC with `docker exec ott-local-postgres-1 date -u` before diagnosing valid-looking token failures. Verify no unrelated workloads are running before a VM restart; keep volumes, then refresh tokens and republish synthetic media when an ephemeral emulator is empty. This incident does not establish a production NTP-monitoring or cluster clock-repair mechanism.
