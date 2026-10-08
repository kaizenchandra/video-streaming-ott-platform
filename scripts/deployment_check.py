#!/usr/bin/env python3
"""Check rendered security semantics and reject invalid production overlays; requires PyYAML and Helm."""
import json,re,subprocess,tempfile
from pathlib import Path
import yaml
root=Path(__file__).resolve().parents[1]
chart=root/'infrastructure/helm/ott'
def render(extra=(),fail=False):
 p=subprocess.run(['helm','template','ott',str(chart),'--namespace','ott',*extra],capture_output=True,text=True)
 if fail:
  assert p.returncode != 0, 'Unsafe production configuration unexpectedly rendered'
  return
 assert p.returncode == 0,p.stderr
 return [d for d in yaml.safe_load_all(p.stdout) if d]
base=render(['--set','worker.enabled=true','--set','migrations.enabled=true','--set','autoscaling.enabled=true'])
def resources(kind):return [d for d in base if d['kind']==kind]
assert len(resources('Deployment'))==10
assert len(resources('Job'))==9
assert len(resources('HorizontalPodAutoscaler'))==9
worker=next(d for d in resources('Deployment') if d['metadata']['name']=='ott-media-worker')
assert worker['spec']['template']['spec']['nodeSelector']['kubernetes.io/arch']=='amd64'
for d in resources('Deployment')+resources('Job'):
 s=d['spec']['template']['spec'];assert not s['automountServiceAccountToken']
 assert s['securityContext']['runAsNonRoot'] and s['securityContext']['seccompProfile']['type']=='RuntimeDefault'
 for c in s['containers']:
  assert c['securityContext']['readOnlyRootFilesystem'] and not c['securityContext']['allowPrivilegeEscalation']
  assert c['securityContext']['capabilities']['drop']==['ALL']
  assert c['resources']['requests'] and c['resources']['limits']
  if d['kind']=='Deployment':assert all(k in c for k in ('startupProbe','livenessProbe','readinessProbe'))
  else:assert 'PropertiesLauncher' in c['command'][-1] and 'DatabaseMigration' in c['command'][1]
assert all(d['kind']!='Secret' for d in base)
prod=['-f',str(chart/'values-production.yaml')]
render(prod,fail=True)
overlay={'externalEgressCidrs':['10.42.0.0/16'],'sharedEnv':{'SCHEMA_REGISTRY_URL':'https://registry.ott.example','KAFKA_BOOTSTRAP_SERVERS':'kafka.ott.example:9096','MEMBER_URL':'https://member.ott.example','CONTENT_URL':'https://content.ott.example','COMMERCE_URL':'https://commerce.ott.example','PLAYBACK_URL':'https://playback.ott.example'},'ingress':{'host':'api.ott.example','tlsSecret':'api-tls','certificateArn':'arn:aws:acm:us-east-1:123456789012:certificate/fixture','wafAclArn':'arn:aws:wafv2:us-east-1:123456789012:regional/webacl/ott/fixture'},'owners':{}}
for owner in json.loads((chart/'values.yaml').read_text())['owners']:
 overlay['owners'][owner]={'image':f'example/{owner}@sha256:'+64*'a','roleArn':f'arn:aws:iam::123456789012:role/{owner}'}
with tempfile.TemporaryDirectory() as directory:
 p=Path(directory)/'overlay.json';p.write_text(json.dumps(overlay))
 manifest=render([*prod,'-f',str(p)])
 assert len([d for d in manifest if d['kind']=='Deployment'])==6
 render([*prod,'-f',str(p),'--set','owners.playback.enabled=true'],fail=True)
 render([*prod,'-f',str(p),'--set','ingress.wafAclArn='],fail=True)
 render([*prod,'-f',str(p),'--namespace','other'],fail=True)
 render([*prod,'-f',str(p),'--set','owners.member.image=mutable:latest'],fail=True)
 ingress=next(d for d in manifest if d['kind']=='Ingress')
 assert ingress['metadata']['annotations']['alb.ingress.kubernetes.io/wafv2-acl-arn']==overlay['ingress']['wafAclArn']
 assert all(not route['path'].startswith(('/internal','/actuator')) for route in ingress['spec']['rules'][0]['http']['paths'])
# The explicit edge list follows source paths, including shared-profile and live routing.
routes={}
for src in (root/'services').glob('*/src/main/java/**/adapter/inbound/rest/*.java'):
 owner=src.parts[src.parts.index('services')+1].removesuffix('-service')
 for path in re.findall(r'@(?:Get|Post|Put|Delete|Patch|Request)Mapping\("([^\"]+)"',src.read_text()):
  if path.startswith(('/internal','/actuator')):continue
  routes[re.sub(r'\{[^}]+\}','*',path)]=owner
configured={r['path']:r['owner'] for r in json.loads((chart/'values.yaml').read_text())['routes']}
assert routes==configured,'Controller/edge route drift'
print(f'PASS deployment semantics: 10 deployments, 9 migration jobs/HPA, {len(routes)} routes, safe production guards')
