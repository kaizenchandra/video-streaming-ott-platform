# Secrets management

No committed real credentials, tokens, keys, payment webhook secrets or DRM keys. Local bootstrap generates secrets under ignored `.local/` with restrictive filesystem permissions. Examples use `${VAR:?required}` references. Test-only ephemeral fixture keys do not access any service and are clearly scoped.

AWS workload identity→Secrets Manager/KMS; per-service resource policies; rotation with overlapping key IDs, revoke old only after token/provider window. Commercial DRM keys stay provider KMS/HSM; business services hold opaque key IDs. CloudFront signer private key injected secret, not mounted writable and never logged. Terraform state can contain sensitive values: encrypted restricted remote state, no generated plaintext outputs. Secret scan CI and incident rotation runbook required.
