#!/usr/bin/env python3
"""Read-only audio evidence collection; does not read account data or clear logs."""
import argparse
import datetime
import json
import os
import subprocess
from pathlib import Path

ADB = os.environ.get('ADB', 'adb')
SERIAL = os.environ['LEO_SERIAL']
COMMANDS = {
    'identity': 'getprop ro.product.device; cat /proc/sys/kernel/random/boot_id; sha256sum /system/vendor/lib/hw/audio.primary.msm8994.so /system/vendor/lib64/hw/audio.primary.msm8994.so',
    'fixture': 'dumpsys activity service com.leoaudio.fixture/.FixtureService',
    'audio_flinger': 'dumpsys media.audio_flinger',
    'audio_policy': 'dumpsys media.audio_policy',
    'audio_service': 'dumpsys audio',
    'mixer': 'tinymix',
    'pcm': 'for f in /proc/asound/card0/pcm*p/sub0/status /proc/asound/card0/pcm*p/sub0/hw_params; do echo "$f"; cat "$f"; done',
    'services': 'pidof audioserver; pidof android.hardware.audio@2.0-service; getprop init.svc.audioserver; getprop init.svc.vendor.audio-hal-2-0',
    'audio_log': 'logcat -d -t 1000 -v threadtime leo_hifi:V audio_hw_primary:D audio_route:D LeoFixture:I AudioTrack:W AndroidRuntime:E DEBUG:E "*:S"',
}

parser = argparse.ArgumentParser()
parser.add_argument('label')
args = parser.parse_args()
if not args.label.replace('-', '').replace('_', '').isalnum():
    parser.error('simple label required')
root = Path(__file__).resolve().parent / args.label
root.mkdir(exist_ok=False)
records = {}
for name, command in COMMANDS.items():
    result = subprocess.run([ADB, '-s', SERIAL, 'shell', command], capture_output=True, timeout=20)
    (root / (name + '.txt')).write_bytes(result.stdout)
    if result.stderr:
        (root / (name + '.stderr')).write_bytes(result.stderr)
    records[name] = {'returncode': result.returncode, 'bytes': len(result.stdout)}
(root / 'capture.json').write_text(json.dumps({
    'captured_at': datetime.datetime.now().astimezone().isoformat(),
    'serial': SERIAL, 'read_only': True, 'commands': records,
}, indent=2) + '\n')
print(json.dumps(records))
if any(row['returncode'] != 0 for row in records.values()):
    raise SystemExit(1)
