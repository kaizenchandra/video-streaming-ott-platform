#!/usr/bin/env python3
"""Exercise only task-owned localhost media APIs, IdP and S3 emulator; withhold tokens/URLs."""
from pathlib import Path
import json,urllib.request,urllib.parse,urllib.error,hashlib,time
root=Path(__file__).resolve().parents[1]
artifact=root/'.local/media-smoke';artifact.mkdir(parents=True,exist_ok=True)
env=dict(line.split('=',1) for line in (root/'.local/compose.env').read_text().splitlines() if '=' in line)
def call(url,data=None,token=None,form=False,method=None,headers=None):
    h=dict(headers or {})
    if token:h['Authorization']='Bearer '+token
    if data is not None and not isinstance(data,bytes):
        h['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
        data=urllib.parse.urlencode(data).encode() if form else json.dumps(data).encode()
    with urllib.request.urlopen(urllib.request.Request(url,data=data,headers=h,method=method),timeout=10) as response:
        body=response.read();return json.loads(body) if body else None
source=(artifact/'source.mp4').read_bytes();assert source,'Generate synthetic source before the smoke test'
identity=call('http://localhost:8080/realms/ott/protocol/openid-connect/token',{'grant_type':'client_credentials','client_id':'ott-local-media-operator','client_secret':env['MEDIA_OPERATOR_SECRET']},form=True)
token=identity['access_token']
item=call('http://localhost:8082/v1/admin/catalog',{'type':'MOVIE','maturity':7,'locale':'en','title':'Standalone media smoke','synopsis':'Synthetic local fixture'},token)
source=(artifact/'source.mp4').read_bytes();checksum=hashlib.sha256(source).hexdigest()
intake=call('http://localhost:8086/v1/admin/media/uploads',{'contentId':item['id'],'assetVersion':1,'bytes':len(source),'sha256':checksum,'encodingProfile':'VOD'},token)
upload=intake['upload'];uri=urllib.parse.urlparse(upload['url'])
assert uri.hostname=='localhost' and uri.port==4566,'Only task-owned S3 emulator uploads allowed'
headers={name:values[0] for name,values in upload['headers'].items()}
call(upload['url'],source,method='PUT',headers=headers)
id=intake['job']['id'];call('http://localhost:8086/v1/admin/media/jobs/'+id+'/upload-completed',{},token)
deadline=time.monotonic()+90
while True:
    job=call('http://localhost:8086/v1/admin/media/jobs/'+id,token=token)
    if job['state'] in ('READY','FAILED'):break
    if time.monotonic()>deadline:raise SystemExit('Media workflow did not finish within smoke deadline; inspect task-owned logs.')
    time.sleep(.25)
(artifact/'job.json').write_text(json.dumps(job,indent=2)+'\n')
assert job['state']=='READY', 'Media workflow failed: '+str(job.get('failureCode'))
call('http://localhost:8082/v1/admin/catalog/'+item['id']+'/review',{'version':1,'locale':'en'},token)
deadline=time.monotonic()+30
while True:
    try:
        published=call('http://localhost:8082/v1/admin/catalog/'+item['id']+'/publish',{'version':2,'assetVersion':1,'locale':'en'},token);break
    except urllib.error.HTTPError as e:
        if e.code!=409 or time.monotonic()>deadline:raise
        time.sleep(.25)
(artifact/'published.json').write_text(json.dumps(published,indent=2)+'\n')
print('PASS: actual Keycloak → catalog/media APIs → private upload → headless native worker → Temporal/S3 → Kafka → catalog publication. Tokens and upload URLs withheld.')
