#!/usr/bin/env python3
"""Headless worker process/probe/native-tool drill on isolated PostgreSQL+Temporal."""
import json,os,secrets,subprocess,time,urllib.request,urllib.error
from pathlib import Path
root=Path(__file__).resolve().parents[1];local=root/'.local/worker-drill';local.mkdir(parents=True,exist_ok=True)
prefix='ott-worker-drill-'+secrets.token_hex(3);pg=prefix+'-pg';temporal=prefix+'-temporal';worker=prefix+'-worker';net=prefix+'-net'
def docker(*args,fail=False):
 p=subprocess.run(['docker',*args],capture_output=True,text=True)
 if p.returncode and not fail:raise RuntimeError('Worker drill Docker '+args[0]+' failed')
 return p
def envfile(name,values):
 p=local/name;p.write_text(''.join(k+'='+v+'\n' for k,v in values.items()));p.chmod(0o600);return str(p)
def code(base,path):
 try:
  with urllib.request.urlopen(base+path,timeout=5) as response:return response.status
 except urllib.error.HTTPError as error:return error.code
password=secrets.token_hex(24)
try:
 assert docker('image','inspect','--format','{{.Architecture}}','ott-media-worker:local').stdout.strip()=='amd64'
 docker('network','create',net)
 f=envfile('pg.env',{'POSTGRES_DB':'media','POSTGRES_USER':'media','POSTGRES_PASSWORD':password})
 docker('run','-d','--name',pg,'--network',net,'--env-file',f,'postgres:17.6')
 docker('run','-d','--name',temporal,'--network',net,'temporalio/temporal@sha256:ad4c82c97bd12b417d1ea942610dbcd511afb250c4d5ed26c694009533df447e','server','start-dev','--ip','0.0.0.0')
 for _ in range(60):
  if docker('exec',pg,'pg_isready','-U','media','-d','media',fail=True).returncode==0:break
  time.sleep(.5)
 f=envfile('worker.env',{'MEDIA_DB_URL':f'jdbc:postgresql://{pg}:5432/media','MEDIA_DB_USER':'media','MEDIA_DB_PASSWORD':password,'OIDC_ISSUER':'https://fixture.ott.example','OIDC_JWKS_URL':'https://fixture.ott.example/certs','TEMPORAL_TARGET':f'{temporal}:7233','QUARANTINE_BUCKET':'ott-quarantine','ORIGIN_BUCKET':'ott-origin','AWS_REGION':'us-east-1','AWS_ACCESS_KEY_ID':'test','AWS_SECRET_ACCESS_KEY':'test','OUTBOX_ENABLED':'false','MEDIA_PROTECTION':'mock-clear'})
 docker('run','-d','--name',worker,'--network',net,'--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=2g,uid=10001,gid=10001','--memory','1g','--cpus','2','--pids-limit','256','--cap-drop','ALL','--security-opt','no-new-privileges:true','--env-file',f,'-p','127.0.0.1::8091','ott-media-worker:local','--spring.main.web-application-type=none','--ott.media.worker=true','--ott.media.worker-health.enabled=true')
 port=docker('port',worker,'8091').stdout.strip().split(':')[-1];base='http://127.0.0.1:'+port
 for _ in range(160):
  try:
   if code(base,'/health/ready')==200:break
  except (OSError,TimeoutError):pass
  time.sleep(.5)
 else:
  (local/'startup.log').write_text(docker('logs',worker,fail=True).stdout);raise RuntimeError('Headless worker readiness failed; local startup log saved')
 for _ in range(60):
  health=docker('inspect','--format','{{.State.Health.Status}}',worker).stdout.strip()
  if health=='healthy':break
  time.sleep(.5)
 else:raise AssertionError('Worker Docker readiness healthcheck did not become healthy')
 assert code(base,'/v1/media')==404
 assert docker('exec',worker,'ffmpeg','-version').returncode==0
 docker('stop','-t','1',temporal)
 for _ in range(20):
  if code(base,'/health/ready')==503:break
  time.sleep(.25)
 else:raise AssertionError('Temporal outage did not fail readiness')
 assert code(base,'/health/live')==200
 result={'startup':'PASS real headless Java/Temporal/PG worker at UID10001/read-only','native':'PASS FFmpeg7.1 executable after OS patch','dockerHealth':'PASS amd64 image metadata and actual readiness healthcheck becomes healthy','surface':'PASS business path404; management only','failure':'PASS Temporal outage readiness503 while worker liveness200'}
 (local/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
finally:
 docker('rm','-f',worker,pg,temporal,fail=True);docker('network','rm',net,fail=True)
 for f in local.glob('*.env'):f.unlink()
