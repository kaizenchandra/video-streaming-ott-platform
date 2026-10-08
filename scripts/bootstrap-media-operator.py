#!/usr/bin/env python3
"""Create only the scoped task-owned media operator client in the task-owned local IdP."""
from pathlib import Path
import json,urllib.request,urllib.parse,base64
root=Path(__file__).resolve().parents[1]
env=dict(line.split('=',1) for line in (root/'.local/compose.env').read_text().splitlines() if '=' in line)
base='http://localhost:8080'
def request(path,data=None,token=None,form=False,method=None):
    headers={}
    if token:headers['Authorization']='Bearer '+token
    if data is not None:headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
    body=(urllib.parse.urlencode(data).encode() if form else json.dumps(data).encode()) if data is not None else None
    with urllib.request.urlopen(urllib.request.Request(base+path,data=body,headers=headers,method=method),timeout=5) as response:
        raw=response.read();return json.loads(raw) if raw else None
admin=request('/realms/master/protocol/openid-connect/token',{'grant_type':'password','client_id':'admin-cli','username':'local-admin','password':env['KEYCLOAK_ADMIN_PASSWORD']},form=True)['access_token']
clients=request('/admin/realms/ott/clients?clientId=ott-local-media-operator',token=admin)
if not clients:
    request('/admin/realms/ott/clients',{'clientId':'ott-local-media-operator','enabled':True,'publicClient':False,'secret':env['MEDIA_OPERATOR_SECRET'],'serviceAccountsEnabled':True,'standardFlowEnabled':False,'directAccessGrantsEnabled':False,'protocol':'openid-connect','protocolMappers':[{'name':'ott-api','protocol':'openid-connect','protocolMapper':'oidc-audience-mapper','config':{'included.custom.audience':'ott-api','access.token.claim':'true'}},{'name':'scoped-workload','protocol':'openid-connect','protocolMapper':'oidc-hardcoded-claim-mapper','config':{'claim.name':'scope','claim.value':'media.write catalog.write catalog.publish','jsonType.label':'String','access.token.claim':'true'}}]},token=admin)
client=request('/admin/realms/ott/clients?clientId=ott-local-media-operator',token=admin)[0]
client_path='/admin/realms/ott/clients/'+client['id']
for mapper in request(client_path+'/protocol-mappers/models',token=admin):
    if mapper['name']=='scoped-workload':request(client_path+'/protocol-mappers/models/'+mapper['id'],token=admin,method='DELETE')
scopes={x['name']:x['id'] for x in request('/admin/realms/ott/client-scopes',token=admin)}
for name in ['media.write','catalog.write','catalog.publish','live.write']:
    if name not in scopes:
        request('/admin/realms/ott/client-scopes',{'name':name,'protocol':'openid-connect','attributes':{'include.in.token.scope':'true','display.on.consent.screen':'false'}},token=admin)
        scopes={x['name']:x['id'] for x in request('/admin/realms/ott/client-scopes',token=admin)}
    request(client_path+'/default-client-scopes/'+scopes[name],token=admin,method='PUT')
result=request('/realms/ott/protocol/openid-connect/token',{'grant_type':'client_credentials','client_id':'ott-local-media-operator','client_secret':env['MEDIA_OPERATOR_SECRET']},form=True)
claims=json.loads(base64.urlsafe_b64decode(result['access_token'].split('.')[1]+'=='))
aud=claims.get('aud',[]);aud=[aud] if isinstance(aud,str) else aud
assert 'ott-api' in aud
assert {'media.write','catalog.write','catalog.publish','live.write'}<=set(claims['scope'].split())
print('PASS: local media operator client issued the expected audience and operator scopes; token and secret withheld.')
