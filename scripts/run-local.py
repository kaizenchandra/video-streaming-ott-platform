#!/usr/bin/env python3
"""Run one executable owner using only that owner's local credentials."""
from pathlib import Path
import os,sys
root=Path(__file__).resolve().parents[1]
owner=sys.argv[1] if len(sys.argv)==2 else ''
ports={'member':5432,'content':5433,'commerce':5434,'playback':5435,'media':5436,'experience':5437,'engagement':5438,'insights':5439,'operations':5440}
if owner not in ports:raise SystemExit('Usage: python3 scripts/run-local.py '+ '|'.join(ports))
settings=dict(line.split('=',1) for line in (root/'.local/compose.env').read_text().splitlines() if '=' in line)
# Explicit environment allowlist prevents handing other owners' database/payment secrets to this process.
env={key:value for key,value in os.environ.items() if key in {'PATH','HOME','LANG','LC_ALL','TMPDIR','JAVA_HOME','SSL_CERT_FILE','SSL_CERT_DIR'}}
prefix=owner.upper();env.update({prefix+'_DB_URL':f'jdbc:postgresql://localhost:{ports[owner]}/{owner}',prefix+'_DB_USER':owner,prefix+'_DB_PASSWORD':settings[prefix+'_DB_PASSWORD'],'OIDC_ISSUER':'http://localhost:8080/realms/ott','OIDC_JWKS_URL':'http://localhost:8080/realms/ott/protocol/openid-connect/certs'})
if owner=='content':env.update({'LIVE_FEED_SECRET':settings['LIVE_FEED_SECRET']})
if owner=='commerce':env.update({'PAYMENT_MODE':'mock','PAYMENT_WEBHOOK_SECRET':settings['PAYMENT_WEBHOOK_SECRET']})
if owner=='playback':env.update({'PLAYBACK_PROVIDERS':'mock','PLAYBACK_TOKEN_SECRET':settings['PLAYBACK_TOKEN_SECRET'],'GEO_ASSERTION_SECRET':settings['GEO_ASSERTION_SECRET']})
if owner in {'playback','experience','engagement','insights','operations'}:
    env.update({'WORKLOAD_TOKEN_URL':'http://localhost:8080/realms/ott/protocol/openid-connect/token','WORKLOAD_CLIENT_ID':'ott-'+owner,'WORKLOAD_CLIENT_SECRET':settings[owner.upper()+'_WORKLOAD_SECRET' if owner in {'experience','engagement','insights','operations'} else 'WORKLOAD_CLIENT_SECRET']})
if owner=='insights':env.update({'ASSIGNMENT_SECRET':settings['ASSIGNMENT_SECRET'],'TELEMETRY_PRIVACY_SECRET':settings['TELEMETRY_PRIVACY_SECRET']})
if owner=='engagement':env.update({'CONTACT_ENCRYPTION_KEY':settings['CONTACT_ENCRYPTION_KEY'],'NOTIFICATIONS_ENABLED':'true'})
if owner=='experience':env.update({'SEARCH_ENABLED':'true','RECOMMENDATIONS_ENABLED':'true'})
if owner=='media':env.update({'S3_ENDPOINT':'http://localhost:4566','QUARANTINE_BUCKET':'ott-quarantine','ORIGIN_BUCKET':'ott-origin','AWS_REGION':'us-east-1','AWS_ACCESS_KEY_ID':'test','AWS_SECRET_ACCESS_KEY':'test'})
java=Path('/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java')
executable=str(java) if java.exists() else 'java'
jar=root/f'services/{owner}-service/target/{owner}-service-0.1.0-SNAPSHOT-exec.jar'
if not jar.exists():raise SystemExit('Build executable jars first: ./scripts/mvn.sh package')
os.execvpe(executable,[executable,'-jar',str(jar),'--server.address=127.0.0.1'],env)
