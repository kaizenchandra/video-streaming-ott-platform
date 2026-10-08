#!/usr/bin/env python3
"""Register typed local schemas with compatibility checks; no business publishing."""
import json, urllib.request, sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
base=sys.argv[1] if len(sys.argv)>1 else 'http://localhost:8085'
if not base.startswith(('http://localhost:', 'http://127.0.0.1:')):
    raise SystemExit('This bootstrap is restricted to local Schema Registry.')
def request(path,data,method='POST'):
    body=json.dumps(data).encode()
    req=urllib.request.Request(base+path,data=body,method=method,headers={'Content-Type':'application/vnd.schemaregistry.v1+json'})
    with urllib.request.urlopen(req,timeout=10) as response: return json.load(response)
topics=json.loads((root/'contracts/events/topics.json').read_text())
for topic in topics:
    subject=topic['topic']+'-value'
    schema=(root/'contracts/events'/topic['schema']).read_text()
    request('/config/'+subject,{'compatibility':'BACKWARD_TRANSITIVE'},'PUT')
    request('/subjects/'+subject+'/versions',{'schemaType':'AVRO','schema':schema})
    result=request('/compatibility/subjects/'+subject+'/versions/latest',{'schemaType':'AVRO','schema':schema})
    if not result.get('is_compatible'): raise SystemExit('Compatibility failed: '+subject)
print(f'PASS: {len(topics)} typed Avro subjects registered and checked against latest versions (initial baseline, not historical evolution evidence).')
