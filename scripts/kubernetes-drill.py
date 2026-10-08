#!/usr/bin/env python3
"""Disposable, explicitly scoped kind Helm/migration/probe drill. Never uses the user's kubeconfig."""
import json,os,secrets,subprocess,time
from pathlib import Path
root=Path(__file__).resolve().parents[1];local=root/'.local/kubernetes-drill';local.mkdir(parents=True,exist_ok=True)
cluster='ott-drill-'+secrets.token_hex(3);kube=local/'kubeconfig';kind=root/'.local/tools/kind'
node='kindest/node:v1.37.0@sha256:a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5'
def run(*cmd,stdin=None,fail=False):
 p=subprocess.run(cmd,input=stdin,text=True,capture_output=True)
 if p.returncode and not fail:raise RuntimeError(cmd[0]+': '+p.stderr[-1200:])
 return p.stdout
def kubectl(*args,stdin=None):return run('kubectl','--kubeconfig',str(kube),*args,stdin=stdin)
def apply(resources):kubectl('apply','-f','-',stdin=json.dumps({'apiVersion':'v1','kind':'List','items':resources}))
try:
 run(str(kind),'create','cluster','--name',cluster,'--image',node,'--kubeconfig',str(kube),'--wait','120s')
 kube.chmod(0o600)
 run(str(kind),'load','docker-image','ott-member:local','--name',cluster)
 # Let kubelet pull the public postgres image; Docker29's partial platform archive cannot be imported with --all-platforms.
 apply([{'apiVersion':'v1','kind':'Namespace','metadata':{'name':'ott','labels':{'pod-security.kubernetes.io/enforce':'restricted'}}},{'apiVersion':'v1','kind':'Namespace','metadata':{'name':'ott-deps','labels':{'ott-dependencies':'true'}}}])
 password=secrets.token_hex(24)
 apply([{'apiVersion':'v1','kind':'Secret','metadata':{'name':'pg','namespace':'ott-deps'},'stringData':{'POSTGRES_PASSWORD':password}}, {'apiVersion':'v1','kind':'PersistentVolumeClaim','metadata':{'name':'pg-data','namespace':'ott-deps'},'spec':{'accessModes':['ReadWriteOnce'],'resources':{'requests':{'storage':'1Gi'}}}}, {'apiVersion':'apps/v1','kind':'Deployment','metadata':{'name':'postgres','namespace':'ott-deps'},'spec':{'replicas':1,'selector':{'matchLabels':{'app':'postgres'}},'template':{'metadata':{'labels':{'app':'postgres'}},'spec':{'volumes':[{'name':'pg-data','persistentVolumeClaim':{'claimName':'pg-data'}}],'containers':[{'name':'postgres','image':'postgres:17.6','env':[{'name':'POSTGRES_DB','value':'member'},{'name':'POSTGRES_USER','value':'migration_admin'}],'volumeMounts':[{'name':'pg-data','mountPath':'/var/lib/postgresql/data'}],'envFrom':[{'secretRef':{'name':'pg'}}],'ports':[{'containerPort':5432}],'resources':{'requests':{'cpu':'100m','memory':'128Mi'},'limits':{'cpu':'1','memory':'512Mi'}},'readinessProbe':{'exec':{'command':['pg_isready','-U','migration_admin','-d','member']},'periodSeconds':2}}]}}}}, {'apiVersion':'v1','kind':'Service','metadata':{'name':'postgres','namespace':'ott-deps'},'spec':{'selector':{'app':'postgres'},'ports':[{'port':5432,'targetPort':5432}]}}])
 kubectl('-n','ott-deps','rollout','status','deployment/postgres','--timeout=120s')
 database='jdbc:postgresql://postgres.ott-deps.svc.cluster.local:5432/member'
 # Only isolated drill fixture uses shared admin credentials; DML privilege split is tested separately.
 for name,values in [('ott-member-migration',{'MIGRATION_DB_URL':database,'MIGRATION_DB_USER':'migration_admin','MIGRATION_DB_PASSWORD':password}),('ott-member',{'MEMBER_DB_URL':database,'MEMBER_DB_USER':'migration_admin','MEMBER_DB_PASSWORD':password,'OIDC_ISSUER':'https://idp.ott.example/realms/ott','OIDC_JWKS_URL':'https://idp.ott.example/realms/ott/certs'})]:
  apply([{'apiVersion':'v1','kind':'Secret','metadata':{'name':name,'namespace':'ott'},'stringData':values}])
 owners=json.loads((root/'infrastructure/helm/ott/values.yaml').read_text())['owners']
 overlay={'replicas':1,'migrations':{'enabled':True},'resources':{'requests':{'cpu':'100m','memory':'256Mi'},'limits':{'cpu':'1','memory':'1Gi'}},'owners':{owner:{'enabled':owner=='member'} for owner in owners},'sharedEnv':{'OUTBOX_ENABLED':'false','SPRING_FLYWAY_ENABLED':'false'}}
 values=local/'overlay.json';values.write_text(json.dumps(overlay));values.chmod(0o600)
 run('helm','upgrade','--install','ott',str(root/'infrastructure/helm/ott'),'--namespace','ott','--kubeconfig',str(kube),'-f',str(values),'--wait','--timeout','180s')
 kubectl('-n','ott','rollout','status','deployment/ott-member','--timeout=120s')
 pods=json.loads(kubectl('-n','ott','get','pods','-l','app.kubernetes.io/name=ott-member','-o','json'));pod=next(p for p in pods['items'] if p['status'].get('containerStatuses',[{}])[0].get('ready'))
 name=pod['metadata']['name'];restarts=pod['status']['containerStatuses'][0]['restartCount']
 kubectl('-n','ott-deps','scale','deployment/postgres','--replicas=0')
 for _ in range(30):
  state=json.loads(kubectl('-n','ott','get','pod',name,'-o','json'))
  if not state['status']['containerStatuses'][0]['ready']:break
  time.sleep(1)
 else:raise AssertionError('Kubernetes readiness failed to remove DB-unavailable pod')
 assert state['status']['containerStatuses'][0]['restartCount']==restarts
 kubectl('-n','ott-deps','scale','deployment/postgres','--replicas=1')
 kubectl('-n','ott-deps','rollout','status','deployment/postgres','--timeout=120s')
 # Local PVC keeps database state; repeat migration hook to verify idempotent schema recovery.
 run('helm','upgrade','ott',str(root/'infrastructure/helm/ott'),'--namespace','ott','--kubeconfig',str(kube),'-f',str(values),'--wait','--timeout','180s')
 kubectl('-n','ott','rollout','restart','deployment/ott-member')
 kubectl('-n','ott','rollout','status','deployment/ott-member','--timeout=120s')
 result={'cluster':'isolated kind1.37 single-node','chart':'PASS real migration hook and restricted workload startup','failure':'PASS DB unavailable removes readiness without liveness restart','recovery':'PASS schema re-migration and rolling owner restart','notValidated':['Multi-AZ','production ALB/IAM','NetworkPolicy enforcement with kindnet','commercial adapters']}
 (local/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
finally:
 run(str(kind),'delete','cluster','--name',cluster,fail=True)
 if kube.exists():kube.unlink()
