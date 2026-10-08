#!/usr/bin/env python3
"""Create only local Compose broker topics; never target remote Kafka."""
import json,shutil,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
cli=shutil.which('docker')
if not cli:
    bundled=Path('/Applications/OrbStack.app/Contents/MacOS/xbin/docker')
    cli=str(bundled) if bundled.exists() else None
if not cli:raise SystemExit('Docker CLI unavailable')
# Deterministic Compose container ownership; do not mutate an arbitrary broker.
container='ott-local-kafka-1'
topics=[t['topic'] for t in json.loads((root/'contracts/events/topics.json').read_text())]
topics+=['media.ready.v1.dlq','catalog.published.v1.dlq','playback.started.v1.dlq','live.changed.v1.dlq','payment.failed.v1.dlq','notification.requested.v1.dlq','subscription.changed.v1.dlq','ad.impression.v1.dlq']
for topic in topics:
    result=subprocess.run([cli,'exec',container,'/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','localhost:19092','--create','--if-not-exists','--topic',topic,'--partitions','1','--replication-factor','1'],capture_output=True,text=True)
    if result.returncode:raise SystemExit(f'Failed local topic bootstrap: {topic}')
print(f'PASS: {len(topics)} local topics exist (dev partitions1/replication1; production topology is separate).')
