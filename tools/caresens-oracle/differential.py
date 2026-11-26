#!/usr/bin/env python3
"""Randomised differential test of the open CareSens Air engine.

    CARESENS_REFERENCE_LIB=/path/to/arm64/libCALCULATION.so \\
        tools/caresens-oracle/differential.py [--runs 100] [--seed 1] [--import-state]

Draws random scenarios (factory values, glucose shape, noise, temperature
mode, gaps, clock jitter and jumps, contact spikes, sensitivity loss, start
sequence), runs the reference library and the engine, and prints the first
stage that differs for every run that does not match. With --import-state the
engine instead takes over the reference's state at random records.

Needs kotlinc and a JDK on PATH (or KOTLINC / JAVA); the engine is compiled
from Common/src/main into a temporary jar.
"""
import argparse
import hashlib
import os
import random
import subprocess
import struct
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import caresens_oracle as co  # noqa: E402
import scenarios  # noqa: E402

ENGINE = os.path.join(co.REPO, 'Common', 'src', 'main', 'java', 'tk', 'glucodata', 'drivers', 'caresens', 'calibration')
CLI = os.path.join(co.HERE, 'engine-cli', 'EngineCli.kt')
RESULT = np.dtype([('seq', '<i4'), ('glucose', '<f8'), ('trend', '<f8'), ('error', '<i4'), ('type', '<i4'),
                   ('exact', '<i4'), ('history', '<f8', 6), ('minutes', '<f8', 5), ('current', '<f8'),
                   ('factory', '<f8'), ('rescaled', '<f8')])
SHADOW = 'CareSensAirOpenCalibrationShadow.kt'  # needs Android; not part of the CLI


def engine_jar():
    sources = sorted(os.path.join(ENGINE, f) for f in os.listdir(ENGINE) if f.endswith('.kt') and f != SHADOW) + [CLI]
    digest = hashlib.sha1(b''.join(open(s, 'rb').read() for s in sources)).hexdigest()[:12]
    jar = os.path.join(tempfile.gettempdir(), 'caresens-engine-%s.jar' % digest)
    if not os.path.exists(jar):
        subprocess.run([os.environ.get('KOTLINC', 'kotlinc'), *sources, '-include-runtime', '-d', jar], check=True,
                       stderr=subprocess.DEVNULL)
    return jar


def run_engine(jar, params, recs, vendor_state=None):
    header = struct.pack('<fffii', *params, len(recs), 0 if vendor_state is None else 1)
    data = header + (b'' if vendor_state is None else vendor_state.tobytes()) + recs.tobytes()
    out = subprocess.run([os.environ.get('JAVA', 'java'), '-cp', jar, 'EngineCliKt'], input=data,
                         capture_output=True, check=True).stdout
    return np.frombuffer(out, RESULT)


def random_spec(r):
    lo = r.choice([40, 50, 60, 70, 90, 120])
    spec = dict(seed=r.randrange(1, 10 ** 9), readings=r.choice([300, 800, 1500, 2500, 4320]),
                start=r.choice([1, 1, 1, 3, 30, 600, 1500]), slope100=r.uniform(2.6, 8.0), vref=r.uniform(1.41, 1.59),
                eapp=r.uniform(0.04, 0.112), lo=lo, hi=lo + r.choice([30, 80, 150, 250, 350]),
                knot_min=r.choice([3, 10, 30, 60]), noise=r.choice([5, 12, 25, 50, 100]),
                temp_mode=r.choice(['const', 'vary', 'step']), temp_lo=r.randrange(3000, 3500),
                decay=r.choice([1, 1, 0.9, 0.7, 0.5]), gap_permille=r.choice([0, 0, 10, 40, 100]),
                gap_max=r.choice([1, 3, 12, 40]), jitter=r.choice([0, 0, 5, 30, 45]),
                spike_permille=r.choice([0, 0, 5, 20]), clock_jump_at=r.choice([-1, -1, -1, 200]),
                clock_jump=r.choice([2000, 40000]))
    spec['knot_max'] = spec['knot_min'] * r.choice([2, 5, 10])
    spec['temp_hi'] = spec['temp_lo'] + r.choice([0, 100, 300, 600])
    return spec


def compare(ref, jar, spec, start=None):
    seq, time, adc, temp = scenarios.generate(**spec)
    full = dict(scenarios.DEFAULTS, **spec)
    dev = co.default_device()
    for key in ('slope100', 'vref', 'eapp'):
        dev[key][0] = full[key]
    recs = co.records(seq, time, np.array(adc, 'u2'), temp)
    count, out, dbg, _, snaps = ref.run(dev, recs, snapshots=start is not None)
    last = np.maximum(count, 1) - 1
    idx = np.arange(len(seq))
    o, g = out[idx, last], dbg[idx, last]
    begin = start or 0
    k = run_engine(jar, (full['slope100'], full['vref'], full['eapp']), recs[begin:],
                   None if start is None else snaps[start - 1:start])
    o, g, count = o[begin:], g[begin:], count[begin:]
    filled = np.nonzero(count > 1)[0]
    end = filled[0] if len(filled) else len(k)
    usable = (o['data_type'] != 2) & ((o['errcode'] & 64) == 0) & (np.arange(len(k)) < end)
    exact = usable & (k['exact'] == 1)
    checks = [
        ('rejection', (k['type'] == 2) != (o['data_type'] == 2), np.arange(len(k)) < end),
        ('error 32/64', ((k['error'] ^ o['errcode']) & 96) != 0, np.arange(len(k)) < end),
        ('minute currents', np.abs(k['minutes'] - g['tran_inA_1min']).max(1) > 1e-9, usable),
        ('corrected current', np.abs(k['current'] - g['out_iir']) > 1e-9, usable),
        ('factory glucose', np.abs(k['factory'] - g['init_cg']) > 1e-7, usable),
        ('rescaled', np.abs(k['rescaled'] - g['out_rescale']) > 1e-7, usable),
        ('glucose', np.abs(k['glucose'] - o['result_glucose']) > 1e-7, usable),
        ('history', np.abs(k['history'] - o['smooth_result_glucose']).max(1) > 1e-7, usable),
        ('trend', np.abs(np.nan_to_num(k['trend']) - o['trendrate']) > 1e-7, exact),
    ]
    first = None
    for name, bad, where in checks:
        hits = np.nonzero(bad & where)[0]
        if len(hits) and (first is None or hits[0] < first[1]):
            first = (name, int(hits[0]))
    return first, int(usable.sum() - exact.sum()), len(filled) > 0


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--runs', type=int, default=100)
    a.add_argument('--seed', type=int, default=1)
    a.add_argument('--import-state', action='store_true')
    args = a.parse_args()
    ref = co.Reference()
    jar = engine_jar()
    r = random.Random(args.seed)
    failures = inexact = fills = 0
    for i in range(args.runs):
        spec = random_spec(r)
        start = r.randrange(2, spec['readings'] - 10) if args.import_state else None
        first, n_inexact, filled = compare(ref, jar, spec, start)
        inexact += n_inexact
        fills += filled
        if first:
            failures += 1
            print('run %d: %s differs first at record %d%s\n  %s' % (
                i, first[0], first[1] + 1 + (start or 0), '' if start is None else ' (engine started from vendor state after %d)' % start, spec))
    print('%d runs, %d differ, %d records not claimed exact, %d runs reached record filling' % (args.runs, failures, inexact, fills))
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
