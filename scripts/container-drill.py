#!/usr/bin/env python3
"""Isolated Member image/migration/least-privilege/failure/load drill. Never changes existing owner DBs."""
import json,os,secrets,subprocess,time,urllib.request,urllib.parse,urllib.error
from pathlib import Path
root=Path(__file__).resolve().parents[1];local=root/'.local/container-drill';local.mkdir(parents=True,exist_ok=True)
prefix='ott-image-drill-'+secrets.token_hex(4);pg=prefix+'-pg';app=prefix+'-member';net=prefix+'-net'
password=secrets.token_hex(24);app_password=secrets.token_hex(24)
settings=dict(line.split('=',1) for line in (root/'.local/compose.env').read_text().splitlines() if '=' in line)
def docker(*args,fail=False,stdin=None):
 p=subprocess.run(['docker',*args],input=stdin,text=True,capture_output=True)
 if not fail and p.returncode:raise RuntimeError('Docker drill command failed: '+args[0])
 return p
def envfile(name,values):
 p=local/name;p.write_text(''.join(k+'='+v+'\n' for k,v in values.items()));p.chmod(0o600);return str(p)
def http(url,body=None,token=None,form=False,method=None):
 headers={}
 if token:headers['Authorization']='Bearer '+token
 if body is not None:
  headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
  body=(urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
 try:
  with urllib.request.urlopen(urllib.request.Request(url,body,headers,method=method),timeout=5) as r:
   b=r.read();return r.status,json.loads(b) if b else None
 except urllib.error.HTTPError as e:return e.code,None
issuer='http://localhost:8080/realms/ott';client_id=prefix;client_uuid=None;result={}
try:
 docker('network','create',net)
 f=envfile('postgres.env',{'POSTGRES_DB':'member','POSTGRES_USER':'migration_admin','POSTGRES_PASSWORD':password})
 docker('run','-d','--name',pg,'--network',net,'--env-file',f,'postgres:17.6')
 for _ in range(60):
  if docker('exec',pg,'pg_isready','-U','migration_admin','-d','member',fail=True).returncode==0:break
  time.sleep(.5)
 else:raise RuntimeError('PostgreSQL not ready')
 f=envfile('migration.env',{'MIGRATION_DB_URL':f'jdbc:postgresql://{pg}:5432/member','MIGRATION_DB_USER':'migration_admin','MIGRATION_DB_PASSWORD':password})
 docker('run','--rm','--network',net,'--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=64m','--cap-drop','ALL','--security-opt','no-new-privileges:true','--env-file',f,'--entrypoint','java','ott-member:local','-Dloader.main=com.ott.member.infrastructure.DatabaseMigration','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher')
 result['migration']='PASS: terminating owned Flyway entry point'
 sql=f"CREATE ROLE member_app LOGIN PASSWORD '{app_password}'; GRANT USAGE ON SCHEMA public TO member_app; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO member_app; GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO member_app; REVOKE CREATE ON SCHEMA public FROM PUBLIC;"
 docker('exec','-i',pg,'psql','-v','ON_ERROR_STOP=1','-U','migration_admin','-d','member',stdin=sql)
 denied=docker('exec','-i',pg,'psql','-v','ON_ERROR_STOP=1','-U','member_app','-d','member',stdin='CREATE TABLE forbidden(id int);',fail=True)
 assert denied.returncode!=0 and 'permission denied' in denied.stderr
 result['ddl_separation']='PASS: runtime role cannot create tables'
 f=envfile('member.env',{'MEMBER_DB_URL':f'jdbc:postgresql://{pg}:5432/member','MEMBER_DB_USER':'member_app','MEMBER_DB_PASSWORD':app_password,'OIDC_ISSUER':issuer,'OIDC_JWKS_URL':'http://host.docker.internal:8080/realms/ott/protocol/openid-connect/certs','SERVER_PORT':'8080','SPRING_FLYWAY_ENABLED':'false','OUTBOX_ENABLED':'false'})
 docker('run','-d','--name',app,'--network',net,'--read-only','--tmpfs','/tmp:rw,noexec,nosuid,size=256m','--memory','1g','--cpus','1','--pids-limit','256','--cap-drop','ALL','--security-opt','no-new-privileges:true','--env-file',f,'-p','127.0.0.1::8080','ott-member:local')
 port=docker('port',app,'8080').stdout.strip().split(':')[-1];base='http://127.0.0.1:'+port
 for _ in range(120):
  try:
   if http(base+'/actuator/health/readiness')[0]==200:break
  except (OSError,TimeoutError):pass
  time.sleep(.5)
 else:raise RuntimeError('Member not ready')
 info=json.loads(docker('inspect',app).stdout)[0];assert info['Config']['User']=='10001:10001' and info['HostConfig']['ReadonlyRootfs']
 result['runtime']='PASS: UID10001/read-only/tmpfs/capabilities/readiness'
 # Real local OIDC client is isolated and removed in finally; credentials never printed.
 _,auth=http('http://localhost:8080/realms/master/protocol/openid-connect/token',{'client_id':'admin-cli','username':'local-admin','password':settings['KEYCLOAK_ADMIN_PASSWORD'],'grant_type':'password'},form=True)
 admin=auth['access_token'];secret=secrets.token_hex(24)
 client={'clientId':client_id,'enabled':True,'publicClient':False,'secret':secret,'serviceAccountsEnabled':True,'protocolMappers':[{'name':'ott-audience','protocol':'openid-connect','protocolMapper':'oidc-audience-mapper','config':{'included.client.audience':'ott-api','access.token.claim':'true'}}]}
 assert http('http://localhost:8080/admin/realms/ott/clients',client,admin)[0]==201
 _,clients=http('http://localhost:8080/admin/realms/ott/clients?clientId='+client_id,token=admin);client_uuid=clients[0]['id']
 _,auth=http(issuer+'/protocol/openid-connect/token',{'client_id':client_id,'client_secret':secret,'grant_type':'client_credentials'},form=True);token=auth['access_token']
 code,account=http(base+'/v1/accounts/me',{'displayName':'Container drill'},token,method='PUT');assert code in (200,201)
 result['oidc_dml']='PASS: signed audience-bound OIDC plus DML-only provision'
 env=dict(os.environ,BASE_URL=base,ACCESS_TOKEN=token,ACCOUNT_ID=account['id'])
 load=subprocess.run(['k6','run','--quiet','--summary-export',str(local/'load.json'),str(root/'tests/load/member.js')],env=env,capture_output=True,text=True)
 (local/'load.log').write_text(load.stdout+load.stderr)
 assert load.returncode==0,'Local authenticated load thresholds failed'
 result['load']='PASS: five users,15s authenticated read; see ignored load.json'
 docker('stop','-t','1',pg)
 for _ in range(15):
  if http(base+'/actuator/health/readiness')[0]==503:break
  time.sleep(.5)
 else:raise RuntimeError('Readiness did not detect database outage')
 assert http(base+'/actuator/health/liveness')[0]==200
 result['failure']='PASS: stopped DB makes readiness503 while liveness200'
 (local/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
finally:
 if client_uuid:
  http('http://localhost:8080/admin/realms/ott/clients/'+client_uuid,token=admin,method='DELETE')
 docker('rm','-f',app,pg,fail=True);docker('network','rm',net,fail=True)
 for p in local.glob('*.env'):p.unlink()
