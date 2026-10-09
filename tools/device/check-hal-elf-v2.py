#!/usr/bin/env python3
"""Read-only ELF/loader compatibility checks against real phone libraries.

Passing this script is not device playback, routing, or ABI behavior acceptance.
"""
import argparse
import hashlib
import json
import re
import struct
import subprocess
from pathlib import Path


def inspect(path):
    data = path.read_bytes()
    if data[:6] != b'\x7fELF\x01\x01':
        raise ValueError('Expected little-endian ELF32: ' + str(path))
    if struct.unpack_from('<H', data, 18)[0] != 40:
        raise ValueError('Expected ARM: ' + str(path))
    text = subprocess.check_output(
        ['readelf', '-h', '-d', '-l', '-A', '--dyn-syms', '--wide', str(path)], text=True)
    symbols = []
    for line in text.splitlines():
        # Some GNU readelf versions render ARM IFUNC as "<OS specific>: 10".
        # Match the binding column rather than assuming a one-word type.
        match = re.match(
            r'\s*\d+:\s+[0-9a-fA-F]+\s+(\d+)\s+(.+?)\s+'
            r'(LOCAL|GLOBAL|WEAK|UNIQUE)\s+(DEFAULT|INTERNAL|HIDDEN|PROTECTED)\s+'
            r'(\S+)\s+(\S+)', line)
        if match:
            size, kind, bind, vis, index, name = match.groups()
            symbols.append({'name': name, 'size': int(size), 'type': kind,
                            'bind': bind, 'vis': vis, 'index': index})
    attrs = {}
    for line in text.splitlines():
        match = re.match(r'\s*(Tag_[^:]+):\s*(.*)', line)
        if match:
            attrs[match[1]] = match[2]
    soname = re.findall(r'\(SONAME\).*\[([^]]+)\]', text)
    stack = next((line.split()[-2] for line in text.splitlines()
                  if line.strip().startswith('GNU_STACK ')), None)
    return {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data),
            'eflags': struct.unpack_from('<I', data, 36)[0],
            'needed': re.findall(r'\(NEEDED\).*\[([^]]+)\]', text),
            'soname': soname[0] if soname else None, 'attrs': attrs,
            'bind_now': 'BIND_NOW' in text or bool(re.search(r'\(FLAGS_1\).*\bNOW\b', text)),
            'relro': 'GNU_RELRO' in text, 'stack': stack, 'symbols': symbols}


def evaluate(candidate, reference, libraries):
    c, r = inspect(candidate), inspect(reference)
    manifest = json.loads((libraries/'manifest.json').read_text())
    recorded = {item['name']: item for item in manifest['libraries']}
    closure, todo, library_data, errors = set(), list(c['needed']), {}, []
    while todo:
        name = todo.pop()
        if name in closure:
            continue
        if name not in recorded or Path(name).name != name:
            errors.append('Missing recorded dependency: ' + name)
            continue
        path = libraries/name
        if hashlib.sha256(path.read_bytes()).hexdigest() != recorded[name]['sha256']:
            errors.append('Dependency snapshot changed: ' + name)
            continue
        info = inspect(path)
        closure.add(name)
        library_data[name] = info
        todo.extend(info['needed'])
    providers = {}
    for name, info in library_data.items():
        for sym in info['symbols']:
            if sym['index'] == 'UND' or sym['bind'] not in {'GLOBAL', 'WEAK'} or sym['vis'] not in {'DEFAULT', 'PROTECTED'}:
                continue
            normalized = sym['name'].replace('@@', '@')
            providers.setdefault(normalized, set()).add(name)
            if '@@' in sym['name'] or '@' not in sym['name']:
                providers.setdefault(sym['name'].split('@')[0], set()).add(name)
    missing = sorted({sym['name'] for sym in c['symbols']
                      if sym['index'] == 'UND' and sym['bind'] == 'GLOBAL'
                      and sym['name'].replace('@@', '@') not in providers})
    if missing:
        errors.append('Strong imports with no matching symbol/version: ' + ', '.join(missing))
    if c['soname'] != 'audio.primary.msm8994.so' or c['soname'] != r['soname']:
        errors.append('HAL SONAME mismatch')
    if c['eflags'] != r['eflags']:
        errors.append('ARM EABI/float flags mismatch')
    for tag in ['Tag_ABI_VFP_args', 'Tag_CPU_arch', 'Tag_ABI_enum_size', 'Tag_ABI_PCS_wchar_t']:
        if c['attrs'].get(tag) != r['attrs'].get(tag):
            errors.append('ARM attribute mismatch: ' + tag)
    for feature in ['bind_now', 'relro']:
        if r[feature] and not c[feature]:
            errors.append('Hardening regression: ' + feature)
    if c['stack'] is None or 'E' in c['stack']:
        errors.append('Missing non-executable GNU_STACK')
    def hmi(info):
        return [(s['size'], s['type'], s['bind'], s['vis']) for s in info['symbols']
                if s['name'] == 'HMI' and s['index'] != 'UND']
    if not hmi(c) or hmi(c) != hmi(r):
        errors.append('HAL HMI export shape mismatch')
    clean = lambda info: {key: value for key, value in info.items() if key != 'symbols'}
    return {'status': 'STATIC_LOADER_CHECKS_PASSED' if not errors else 'NO_GO',
            'candidate': clean(c), 'reference': clean(r),
            'dependency_closure': sorted(closure), 'missing_strong_imports': missing,
            'errors': errors, 'device_loaded': False, 'playback_verified': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('candidate', type=Path)
    parser.add_argument('reference', type=Path)
    parser.add_argument('libraries', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    result = evaluate(args.candidate, args.reference, args.libraries)
    with args.report.open('x') as out:
        json.dump(result, out, indent=2)
    print(result['status'], 'dependencies:', len(result['dependency_closure']))
    for error in result['errors']:
        print(error)
    raise SystemExit(0 if not result['errors'] else 1)
