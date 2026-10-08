#!/usr/bin/env python3
"""Generate ignored local-only credentials, preserving all existing values."""
from pathlib import Path
import os
import secrets
import fcntl
root=Path(__file__).resolve().parents[1]
path=root/'.local/compose.env'
path.parent.mkdir(exist_ok=True, mode=0o700)
fd=os.open(path, os.O_RDWR|os.O_CREAT, 0o600)
with os.fdopen(fd,'r+') as stream:
    fcntl.flock(stream,fcntl.LOCK_EX)
    existing=stream.read()
    keys={line.partition('=')[0] for line in existing.splitlines() if '=' in line}
    additions=[f'{key}={secrets.token_hex(32 if key in {'CONTACT_ENCRYPTION_KEY','ASSIGNMENT_SECRET','TELEMETRY_PRIVACY_SECRET'} else 24)}' for key in ['OPERATIONS_DB_PASSWORD','OPERATIONS_WORKLOAD_SECRET','INSIGHTS_DB_PASSWORD','INSIGHTS_WORKLOAD_SECRET','ASSIGNMENT_SECRET','TELEMETRY_PRIVACY_SECRET','CONTACT_ENCRYPTION_KEY','ENGAGEMENT_DB_PASSWORD','ENGAGEMENT_WORKLOAD_SECRET','LIVE_FEED_SECRET','LIVE_PACKAGER_SECRET','EXPERIENCE_DB_PASSWORD','EXPERIENCE_WORKLOAD_SECRET','MEMBER_DB_PASSWORD','CONTENT_DB_PASSWORD','COMMERCE_DB_PASSWORD','PAYMENT_WEBHOOK_SECRET','PLAYBACK_DB_PASSWORD','MEDIA_DB_PASSWORD','MEDIA_OPERATOR_SECRET','PLAYBACK_TOKEN_SECRET','GEO_ASSERTION_SECRET','WORKLOAD_CLIENT_SECRET','KEYCLOAK_ADMIN_PASSWORD','GRAFANA_ADMIN_PASSWORD'] if key not in keys]
    if additions:
        stream.write(('\n' if existing and not existing.endswith('\n') else '')+'\n'.join(additions)+'\n')
    os.fchmod(stream.fileno(),0o600)
print('Local configuration available at .local/compose.env (secret values withheld).')
