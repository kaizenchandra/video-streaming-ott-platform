#!/usr/bin/env python3
"""Build only known executable owner images; no credentials or publishing."""
import os,subprocess,sys,tempfile,shutil
from pathlib import Path
owners=('member','content','commerce','playback','media','experience','engagement','insights','operations')
requested=sys.argv[1:] or list(owners)
if any(o not in owners+('worker',) for o in requested):raise SystemExit('Unknown image owner')
root=Path(__file__).resolve().parents[1]
platform=os.environ.get('OTT_IMAGE_PLATFORM','')
if platform and platform not in ('linux/amd64','linux/arm64'):raise SystemExit('Unsupported OTT_IMAGE_PLATFORM')
for owner in requested:
 name='media' if owner=='worker' else owner
 jar=root/f'services/{name}-service/target/{name}-service-0.1.0-SNAPSHOT-exec.jar'
 if not jar.exists():raise SystemExit('Build executable jars before images')
 cmd=['docker','build','-f','media/worker/Dockerfile' if owner=='worker' else 'platform/images/Dockerfile']
 if owner!='worker':
  cmd+=['--build-arg','OWNER='+owner]
  if platform:cmd+=['--platform',platform]
 else:cmd+=['--platform','linux/amd64']
 cmd+=['-t',f'ott-{owner if owner!="worker" else "media-worker"}:local','.']
 with tempfile.TemporaryDirectory(prefix='image-context-',dir=root/'.local') as directory:
  context=Path(directory)
  relative=jar.relative_to(root);(context/relative).parent.mkdir(parents=True,exist_ok=True)
  shutil.copy2(jar,context/relative)
  for relative in ('platform/images/Dockerfile','platform/images/HealthProbe.java','media/worker/Dockerfile'):
   (context/relative).parent.mkdir(parents=True,exist_ok=True);shutil.copy2(root/relative,context/relative)
  subprocess.run(cmd,cwd=context,check=True)
