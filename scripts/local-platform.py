#!/usr/bin/env python3
"""Start/status/stop task-owned localhost APIs; reuse per-owner credential allowlists."""
import json
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / '.local/apis'
PORTS = {'member': 8081, 'content': 8082, 'commerce': 8083, 'playback': 8084,
         'media': 8086, 'experience': 8087, 'engagement': 8088, 'insights': 8089, 'operations': 8090}

def owned_pid(owner):
    path = LOCAL / (owner + '.pid')
    if not path.exists():
        return None
    pid = int(path.read_text())
    command = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True).stdout
    expected = str(ROOT / f'services/{owner}-service/target/{owner}-service-0.1.0-SNAPSHOT-exec.jar')
    return pid if expected in command else None

def ready(owner):
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{PORTS[owner]}/actuator/health/readiness', timeout=2) as response:
            return response.status == 200
    except (OSError, TimeoutError):
        return False

def occupied(port):
    with socket.socket() as connection:
        connection.settimeout(.5)
        return connection.connect_ex(('127.0.0.1', port)) == 0

if len(sys.argv) < 2 or sys.argv[1] not in ('start', 'status', 'stop'):
    raise SystemExit('Usage: local-platform.py start|status|stop [owner ...]')
action = sys.argv[1]
owners = sys.argv[2:] or list(PORTS)
if any(owner not in PORTS for owner in owners):
    raise SystemExit('Unknown owner')
LOCAL.mkdir(parents=True, exist_ok=True, mode=0o700)
results = {}
for owner in owners:
    pid = owned_pid(owner)
    if action == 'start':
        if pid is None:
            if occupied(PORTS[owner]):
                raise SystemExit(f'{owner}: port occupied by an untracked listener; no process changed')
            fd = os.open(LOCAL / (owner + '.log'), os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, 'a') as log:
                process = subprocess.Popen([sys.executable, str(ROOT / 'scripts/run-local.py'), owner],
                                           cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            path = LOCAL / (owner + '.pid')
            path.write_text(str(process.pid))
            path.chmod(0o600)
        deadline = time.monotonic() + 120
        while not (owned_pid(owner) and ready(owner)):
            if pid is None and process.poll() is not None:
                raise SystemExit(f'{owner}: startup failed; inspect its ignored local log')
            if time.monotonic() >= deadline:
                raise SystemExit(f'{owner}: readiness deadline exhausted; inspect its ignored local log')
            time.sleep(.5)
        results[owner] = {'state': 'READY', 'port': PORTS[owner]}
    elif action == 'stop':
        if pid is not None:
            os.kill(pid, signal.SIGTERM)
            deadline = time.monotonic() + 35
            while owned_pid(owner):
                if time.monotonic() >= deadline:
                    raise SystemExit(f'{owner}: graceful shutdown deadline exhausted; no force kill performed')
                time.sleep(.25)
        (LOCAL / (owner + '.pid')).unlink(missing_ok=True)
        results[owner] = {'state': 'STOPPED'}
    else:
        results[owner] = {'state': 'READY' if pid and ready(owner) else 'NOT_READY', 'port': PORTS[owner]}
print(json.dumps(results, indent=2))
