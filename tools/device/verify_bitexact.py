#!/usr/bin/env python3
"""A: bit-exactness check of a HAL capture against the fixture's LFSR pattern.

usage: verify_bitexact.py <capture.raw> [rate]
The capture is raw S16_LE stereo as handed to pcm_write (leo-capture.raw).
The fixture loops one second (rate frames) of: 16-bit Galois LFSR, taps 0xB400,
seed 0xACE1; L = (state & 0x3FF) - 512, R = ~L.  The pattern is regenerated here
with integer arithmetic only, the capture is aligned on it, and every captured
frame is classified as an exact match or not.
"""
import struct
import sys

def pattern(rate):
    state, out = 0xACE1, []
    for _ in range(rate):
        lsb = state & 1
        state >>= 1
        if lsb:
            state ^= 0xB400
        left = (state & 0x3FF) - 512
        out.append((left, ~left))
    return out

def main():
    path = sys.argv[1]
    rate = int(sys.argv[2]) if len(sys.argv) > 2 else 44100
    data = open(path, 'rb').read()
    n = len(data) // 4
    frames = list(struct.iter_unpack('<hh', data[:n * 4]))
    ref = pattern(rate)
    index = {}
    for i, f in enumerate(ref):
        index.setdefault(f, []).append(i)

    match = 0
    longest = run = 0
    first_match = None
    pos = None
    mismatches = []
    for k, f in enumerate(frames):
        if pos is not None and ref[pos] == f:
            match += 1; run += 1; longest = max(longest, run)
            pos = (pos + 1) % rate
            continue
        run = 0
        # (re)align: a frame is accepted as a new anchor only if the next 8 frames also match
        anchored = False
        for cand in index.get(f, []):
            if all(k + j < len(frames) and frames[k + j] == ref[(cand + j) % rate] for j in range(1, 9)):
                if first_match is None:
                    first_match = k
                pos = (cand + 1) % rate
                match += 1; run = 1; longest = max(longest, run)
                anchored = True
                break
        if not anchored:
            pos = None
            if first_match is not None and len(mismatches) < 10:
                mismatches.append((k, f))

    tail = n - (first_match or 0)
    zeros = sum(1 for f in frames if f == (0, 0))
    print(f"capture: {n} frames ({n / rate:.2f} s), all-zero frames: {zeros}")
    print(f"first aligned frame: {first_match}")
    print(f"exact-match frames after alignment: {match} / {tail} ({100.0 * match / tail if tail else 0:.4f}%)")
    print(f"longest exact run: {longest} frames ({longest / rate:.2f} s)")
    swapped = sum(1 for f in frames if (f[1], f[0]) in index and f not in index)
    print(f"frames that match only with L/R swapped: {swapped}")
    if mismatches:
        print("first mismatches after alignment (frame, (L, R)):", mismatches)
    verdict = 'BIT_EXACT' if tail and match == tail else 'NOT_BIT_EXACT'
    print('VERDICT', verdict)

if __name__ == '__main__':
    main()
