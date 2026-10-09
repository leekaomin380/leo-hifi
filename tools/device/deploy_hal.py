#!/usr/bin/env python3
"""Leo HiFi HAL deployment, bound to a reviewed manifest and to the HAL that is
installed now (the rollback target).  The manifest names both hashes.

Default is read-only preflight. --deploy makes backups and swaps the sole ELF32
HAL. --rollback uses this run's verified backup and never selects factory HAL.
Interrupted/failed writes leave an explicit record; device loss may prevent
automatic rollback, which must then be retried after reconnection.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import shlex
import subprocess
import time
from pathlib import Path

ADB = os.environ.get('ADB', 'adb')
SERIAL = os.environ['LEO_SERIAL']  # adb serial of the target phone
HAL = '/system/vendor/lib/hw/audio.primary.msm8994.so'
HAL64 = '/system/vendor/lib64/hw/audio.primary.msm8994.so'
CURRENT_SHA = None    # set from manifest['rollback_sha256']
CURRENT64_SHA = None  # set from manifest['untouched_lib64_sha256']
CONTEXT = 'u:object_r:vendor_file:s0'

def require(condition, message):
    if not condition:
        raise RuntimeError(message)

def adb(*args, timeout=20):
    return subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, check=True, timeout=timeout).stdout

def shell(command):
    return adb('shell', command).decode().replace('\r', '').strip()

def sha(path):
    output = shell('sha256sum ' + shlex.quote(path))
    require(re.fullmatch(r'[a-f0-9]{64}\s+\S+', output) is not None, 'invalid hash readback')
    return output.split()[0]

def mount():
    rows = [line.split() for line in shell('cat /proc/mounts').splitlines()]
    rows = [row for row in rows if row[1] == '/' and row[2] == 'ext4' and row[0].startswith('/dev/block/')]
    require(len(rows) == 1 and rows[0][0].endswith('/system'), 'ambiguous system mount')
    modes = set(rows[0][3].split(','))
    require(('ro' in modes) != ('rw' in modes), 'ambiguous mount mode')
    return rows[0][0], 'ro' if 'ro' in modes else 'rw'

def identities():
    result = {}
    for service, executable in [('audioserver', 'audioserver'), ('vendor.audio-hal-2-0', 'android.hardware.audio@2.0-service')]:
        require(shell('getprop init.svc.' + service) == 'running', 'service not running: ' + service)
        pid = shell('pidof ' + shlex.quote(executable))
        require(re.fullmatch(r'[1-9][0-9]*', pid) is not None, 'ambiguous process: ' + service)
        stat = shell('cat /proc/' + pid + '/stat')
        rest = stat[stat.rfind(')') + 2:].split()
        result[service] = pid + ':' + rest[19]
    return result

def attributes():
    values = shell('stat -c "%a %u %g %d %i %s" ' + HAL).split()
    require(len(values) == 6 and values[:3] == ['644', '0', '0'], 'HAL permissions/owner mismatch')
    require(CONTEXT in shell('ls -Z ' + HAL).split(), 'HAL SELinux context mismatch')
    return dict(zip(['mode', 'uid', 'gid', 'device', 'inode', 'size'], values))

def mapped(attrs, ids):
    pid = ids['vendor.audio-hal-2-0'].split(':')[0]
    rows = [line.split(None, 5) for line in shell('cat /proc/' + pid + '/maps').splitlines()]
    rows = [row for row in rows if len(row) == 6 and row[5].startswith(HAL)]
    require(rows and all(row[5] == HAL for row in rows), 'missing or deleted HAL mappings')
    device = int(attrs['device'])
    major = (device >> 8) & 0xfff
    minor = (device & 0xff) | ((device >> 12) & 0xffffff00)
    require(all(row[4] == attrs['inode'] and tuple(int(x, 16) for x in row[3].split(':')) == (major, minor) for row in rows), 'HAL mapping device/inode mismatch')

def check(expected_sha):
    require(shell('getprop ro.product.device') == 'leo', 'wrong product')
    require(shell('id -u') == '0', 'root shell required')
    require(sha(HAL) == expected_sha, 'HAL hash mismatch')
    require(sha(HAL64) == CURRENT64_SHA, 'lib64 changed')
    require(mount()[1] == 'ro', 'system is not read-only')
    # Candidate B idles the DAC at 205/205; candidate C idles at its fail-quiet
    # floor 135/135 (-60 dB).  Anything else, or unequal channels, is drift.
    require(shell('tinymix Volume') in ('Volume: 205 205 (dsrange 0->255)',
                                       'Volume: 135 135 (dsrange 0->255)'), 'DAC drift')
    attrs = attributes()
    ids = identities()
    mapped(attrs, ids)
    return {'sha256': expected_sha, 'attributes': attrs, 'processes': ids,
            'boot_id': shell('cat /proc/sys/kernel/random/boot_id'), 'system_mount': mount()[0]}

def cycle(before):
    shell('setprop ctl.restart audioserver')
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        time.sleep(1)
        try:
            now = identities()
            if all(now[key] != before[key] for key in before):
                return now
        except (RuntimeError, subprocess.SubprocessError):
            pass
    raise RuntimeError('service restart did not change both process identities')

def read_only():
    block, mode = mount()
    if mode == 'ro':
        return False
    try:
        shell('mount -o ro,remount ' + shlex.quote(block) + ' /')
    except subprocess.CalledProcessError:
        pass
    if mount()[1] == 'ro':
        return False
    old_boot = shell('cat /proc/sys/kernel/random/boot_id')
    adb('reboot')
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        time.sleep(2)
        try:
            if shell('getprop sys.boot_completed') == '1' and shell('cat /proc/sys/kernel/random/boot_id') != old_boot and mount()[1] == 'ro':
                return True
        except (RuntimeError, subprocess.SubprocessError):
            pass
    raise RuntimeError('reboot did not establish read-only system')

def replace(source, expected_sha, stage):
    block, _ = mount()
    shell('mount -o rw,remount ' + shlex.quote(block) + ' /')
    require(mount()[1] == 'rw', 'system remount rw failed')
    shell('test ! -e ' + shlex.quote(stage))
    shell('cp ' + shlex.quote(source) + ' ' + shlex.quote(stage))
    require(sha(stage) == expected_sha, 'staged content mismatch')
    for command in ['chown 0:0 ', 'chmod 644 ', 'chcon ' + CONTEXT + ' ']:
        shell(command + shlex.quote(stage))
    shell('mv ' + shlex.quote(stage) + ' ' + HAL)
    shell('sync')
    require(sha(HAL) == expected_sha, 'replacement hash mismatch')
    return read_only()

def rollback(record, folder):
    backup = folder / 'current-hal-backup.so'
    require(backup.is_file() and hashlib.sha256(backup.read_bytes()).hexdigest() == CURRENT_SHA, 'verified host rollback missing')
    require(shell('getprop ro.product.device') == 'leo', 'wrong rollback product')
    changed = sha(HAL) != CURRENT_SHA
    if changed:
        adb('push', str(backup), record['device_dir'] + '/rollback.so')
        require(sha(record['device_dir'] + '/rollback.so') == CURRENT_SHA, 'rollback transfer mismatch')
        before = identities() if record.get('services_healthy_at_failure', False) else None
        rebooted = replace(record['device_dir'] + '/rollback.so', CURRENT_SHA, HAL + '.leo-rollback-' + folder.name)
        if not rebooted:
            if before is None:
                shell('setprop ctl.restart audioserver')
                time.sleep(5)
            else:
                cycle(before)
    elif mount()[1] != 'ro':
        read_only()
    state = check(CURRENT_SHA)
    state['playback_verified'] = False
    record['rollback_verified'] = state

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('manifest', type=Path)
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--deploy', action='store_true')
    group.add_argument('--rollback', type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    global CURRENT_SHA, CURRENT64_SHA
    require(manifest['device'] == 'leo' and manifest['serial'] == SERIAL, 'wrong manifest')
    require(manifest['target'] == HAL, 'wrong deployment scope')
    CURRENT_SHA = manifest['rollback_sha256']
    CURRENT64_SHA = manifest['untouched_lib64_sha256']
    if args.rollback:
        folder = args.rollback
        record = json.loads((folder / 'record.json').read_text())
        rollback(record, folder)
        (folder / 'record.json').write_text(json.dumps(record, indent=2) + '\n')
        print('ROLLBACK_FILE_AND_LOADING_VERIFIED; playback not yet verified')
        return
    candidate = Path(manifest['candidate_path'])
    digest = hashlib.sha256(candidate.read_bytes()).hexdigest()
    require(digest == manifest['candidate_sha256'], 'local candidate hash mismatch')
    abi_path = Path(manifest['abi_report'])
    require(hashlib.sha256(abi_path.read_bytes()).hexdigest() == manifest['abi_report_sha256'], 'ABI evidence hash mismatch')
    require(manifest['formal_build_verified'] and manifest['actual_compile_flags_reviewed'] and manifest['static_abi_passed'], 'build/ABI gates incomplete')
    before = check(CURRENT_SHA)
    print('PREFLIGHT_PASSED', json.dumps(before))
    if not args.deploy:
        return
    # Do not start a write window while any playback PCM is open.
    pcm = shell('for f in /proc/asound/card0/pcm*p/sub0/status; do cat "$f"; done')
    require(pcm.splitlines() and all(line.strip() == 'closed' for line in pcm.splitlines()), 'playback PCM remains open')
    stamp = datetime.datetime.now().astimezone().strftime('%Y%m%dT%H%M%S')
    folder = args.manifest.resolve().parent / ('deployment-' + stamp)
    folder.mkdir(exist_ok=False)
    record = {'started_at': datetime.datetime.now().astimezone().isoformat(), 'manifest': manifest,
              'before': before, 'device_dir': '/data/local/tmp/leo-hal-deploy-' + stamp,
              'replacement_attempted': False, 'playback_verified': False}
    def save():
        (folder / 'record.json').write_text(json.dumps(record, indent=2) + '\n')
    save()
    try:
        backup = adb('exec-out', 'cat', HAL)
        require(hashlib.sha256(backup).hexdigest() == CURRENT_SHA, 'fresh host backup hash mismatch')
        (folder / 'current-hal-backup.so').write_bytes(backup)
        shell('test ! -e ' + record['device_dir'] + ' && mkdir ' + record['device_dir'])
        shell('cp ' + HAL + ' ' + record['device_dir'] + '/current-hal-backup.so')
        require(sha(record['device_dir'] + '/current-hal-backup.so') == CURRENT_SHA, 'fresh device backup hash mismatch')
        adb('push', str(candidate), record['device_dir'] + '/candidate.so')
        require(sha(record['device_dir'] + '/candidate.so') == digest, 'candidate transfer hash mismatch')
        require(sha(HAL) == CURRENT_SHA, 'current HAL changed during preparation')
        record['replacement_attempted'] = True; save()
        rebooted = replace(record['device_dir'] + '/candidate.so', digest, HAL + '.leo-candidate-' + stamp)
        if not rebooted:
            cycle(before['processes'])
        after = check(digest)
        require(all(after['processes'][key] != before['processes'][key] for key in before['processes']), 'old services survived deployment')
        time.sleep(10)
        require(identities() == after['processes'], 'services changed again during stability check')
        record['after'] = after; record['deployment_loaded_verified'] = True; save()
        print('CANDIDATE_FILE_AND_LOADING_VERIFIED; playback not yet verified', folder)
    except BaseException as error:
        record['error'] = repr(error); save()
        if record['replacement_attempted']:
            try:
                record['services_healthy_at_failure'] = bool(identities())
            except BaseException:
                record['services_healthy_at_failure'] = False
            try:
                rollback(record, folder)
            except BaseException as rollback_error:
                record['rollback_error'] = repr(rollback_error)
            save()
        raise

if __name__ == '__main__':
    main()
