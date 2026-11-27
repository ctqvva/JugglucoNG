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
from __future__ import annotations

import argparse
import hashlib
import os
import random
import subprocess
import struct
import sys
import tempfile
from dataclasses import dataclass

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import caresens_oracle as co  # noqa: E402
import make_fixtures as fixtures  # noqa: E402
import scenarios  # noqa: E402

ENGINE = os.path.join(co.REPO, 'Common', 'src', 'main', 'java', 'tk', 'glucodata', 'drivers', 'caresens', 'calibration')
CLI = os.path.join(co.HERE, 'engine-cli', 'EngineCli.kt')
RESULT = np.dtype([('seq', '<i4'), ('glucose', '<f8'), ('trend', '<f8'), ('error', '<i4'), ('type', '<i4'),
                   ('exact', '<i4'), ('history', '<f8', 6), ('minutes', '<f8', 5), ('current', '<f8'),
                   ('factory', '<f8'), ('rescaled', '<f8')])
SHADOW = 'CareSensAirOpenCalibrationShadow.kt'  # needs Android; not part of the CLI
MODELLED = 4 | 32 | 64  # error bits the engine reproduces
BITS = (1, 2, 4, 8, 16, 32, 64, 128)


@dataclass
class Comparison:
    first: tuple[str, int] | None
    inexact: int
    after_fill: int
    compared: int
    vendor_active: np.ndarray
    mismatches: np.ndarray
    scored_mismatches: np.ndarray
    excluded_error4: int
    withheld: int
    withheld_error: int


def engine_jar(cli=CLI, extra_sources=()):
    engine_sources = sorted(os.path.join(ENGINE, f) for f in os.listdir(ENGINE) if f.endswith('.kt') and f != SHADOW)
    sources = [*engine_sources, cli, *extra_sources]
    digest = hashlib.sha1(b''.join(open(s, 'rb').read() for s in sources)).hexdigest()[:12]
    jar = os.path.join(tempfile.gettempdir(), 'caresens-engine-%s.jar' % digest)
    if not os.path.exists(jar):
        with tempfile.TemporaryDirectory(dir=os.path.dirname(jar)) as work:
            temporary_jar = os.path.join(work, 'engine.jar')
            subprocess.run([os.environ.get('KOTLINC', 'kotlinc'), *sources, '-include-runtime', '-d', temporary_jar],
                           check=True)
            os.replace(temporary_jar, jar)
    return jar


def run_engine(jar, params, recs, vendor_state=None, *, main_class='EngineCliKt', result_type=RESULT):
    header = struct.pack('<fffii', *params, len(recs), 0 if vendor_state is None else 1)
    data = header + (b'' if vendor_state is None else vendor_state.tobytes()) + recs.tobytes()
    java_home = os.environ.get('JAVA_HOME')
    java = os.environ.get('JAVA') or (os.path.join(java_home, 'bin', 'java') if java_home else 'java')
    proc = subprocess.run([java, '-cp', jar, main_class], input=data, capture_output=True)
    if proc.returncode:
        raise RuntimeError(f'Engine CLI exited with {proc.returncode}: {proc.stderr.decode(errors="replace")}')
    out = proc.stdout
    if len(out) != len(recs) * result_type.itemsize:
        raise ValueError(f'Engine CLI returned {len(out)} bytes for {len(recs)} records')
    return np.frombuffer(out, result_type)


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
                clock_jump=r.choice([2000, 40000]), dropout_permille=r.choice([0, 0, 0, 2, 10]),
                dropout_min=r.choice([1, 3, 6]), dropout_max=r.choice([6, 20, 60]))
    spec['dropout_max'] = max(spec['dropout_max'], spec['dropout_min'])
    spec['knot_max'] = spec['knot_min'] * r.choice([2, 5, 10])
    spec['temp_hi'] = spec['temp_lo'] + r.choice([0, 100, 300, 600])
    return spec


def compare(ref, jar, spec, start=None, modelled=MODELLED):
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
    # Unmodelled conditions can still affect a latch imported at takeover.
    other_low = np.maximum.accumulate(g['err4_condi'][:, 1:].any(1))
    return compare_records(k, o[begin:], g[begin:], count[begin:], other_low[begin:], modelled)


def compare_records(k, o, g, count, other_low, modelled):
    filled = np.flatnonzero(count > 1)
    end = int(filled[0]) if filled.size else len(k)
    before_fill = np.arange(len(k)) < end
    usable = (o['data_type'] != 2) & ((o['errcode'] & 64) == 0) & before_fill
    exact = usable & (k['exact'] == 1)
    bits = np.array(BITS)
    active = (o['errcode'][:, None] & bits) != 0
    different = ((k['error'] ^ o['errcode'])[:, None] & bits) != 0
    observed = different & before_fill[:, None]
    # Error 4 conditions 1–4 are unmodelled. This exclusion applies only to bit 4.
    eligible = before_fill[:, None] & ((bits != 4) | ~other_low[:, None])
    scored = different & eligible & ((bits & modelled) != 0)

    # Match the vendor's native error/range gate in air/java.cpp. Contact
    # rejection produces no engine reading. Later live-publication checks
    # (timestamps, duplicate IDs, etc.) are outside this offline comparison.
    engine_publishable = (k['type'] != 2) & (k['error'] == 0) & (k['glucose'] > 35) & (k['glucose'] < 505)
    vendor_publishable = (count > 0) & (o['errcode'] == 0) & (o['result_glucose'] > 35) & (o['result_glucose'] < 505)
    withheld = before_fill & engine_publishable & ~vendor_publishable

    def differs(actual, expected, tolerance):
        bad = ~np.isclose(actual, expected, rtol=0, atol=tolerance, equal_nan=True)
        return bad.any(axis=1) if bad.ndim == 2 else bad

    checks = [
        ('rejection', (k['type'] == 2) != (o['data_type'] == 2), before_fill),
        ('modelled errors', scored.any(axis=1), before_fill),
        ('sequence', k['seq'] != o['seq_number_final'], before_fill),
        ('minute currents', differs(k['minutes'], g['tran_inA_1min'], 1e-9), usable),
        ('corrected current', differs(k['current'], g['out_iir'], 1e-9), exact),
        ('factory glucose', differs(k['factory'], g['init_cg'], 1e-7), exact),
        ('rescaled', differs(k['rescaled'], g['out_rescale'], 1e-7), exact),
        ('glucose', differs(k['glucose'], o['result_glucose'], 1e-7), exact),
        ('history', differs(k['history'], o['smooth_result_glucose'], 1e-7), exact),
        ('trend', differs(k['trend'], o['trendrate'], 1e-7), exact),
    ]
    first = None
    for name, bad, where in checks:
        hits = np.nonzero(bad & where)[0]
        if len(hits) and (first is None or hits[0] < first[1]):
            first = (name, int(hits[0]))
    return Comparison(first, int((usable & ~exact).sum()), len(k) - end, end,
                      (active & before_fill[:, None]).sum(axis=0), observed.sum(axis=0), scored.sum(axis=0),
                      int((other_low & before_fill).sum()), int(withheld.sum()),
                      int((withheld & (o['errcode'] != 0)).sum()))


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--runs', type=int, default=100)
    a.add_argument('--seed', type=int, default=1)
    a.add_argument('--import-state', action='store_true')
    a.add_argument('--modelled', type=lambda value: int(value, 0), default=MODELLED,
                   help='error-bit mask for the exit status (default: %(default)s)')
    named = fixtures.DEVELOPMENT | fixtures.HOLDOUT
    a.add_argument('--fixture', choices=tuple(named), action='append',
                   help='run this fixture spec instead of random scenarios (repeatable)')
    args = a.parse_args()
    if not 0 <= args.modelled <= sum(BITS):
        a.error('--modelled must be an 8-bit mask')
    if args.runs < 1:
        a.error('--runs must be positive')
    ref = co.Reference()
    print(f'Reference SHA-256: {ref.sha256}', flush=True)
    jar = engine_jar()
    r = random.Random(args.seed)
    cases = ((name, named[name]) for name in args.fixture) if args.fixture else (
        (f'run {i}', random_spec(r)) for i in range(args.runs))
    results = []
    for label, spec in cases:
        start = r.randrange(2, spec['readings'] - 10) if args.import_state else None
        result = compare(ref, jar, spec, start, args.modelled)
        results.append(result)
        if result.first:
            stage, index = result.first
            takeover = '' if start is None else f' (engine started from vendor state after {start})'
            print(f'{label}: {stage} differs first at record {index + 1 + (start or 0)}{takeover}\n  {spec}')
    failures = sum(result.first is not None for result in results)
    print(f'{len(results)} runs, {failures} differ, {sum(r.compared for r in results)} inputs before filling, '
          f'{sum(r.inexact for r in results)} usable records not claimed exact')
    print(f'{sum(r.after_fill > 0 for r in results)} runs reached record filling; '
          f'{sum(r.after_fill for r in results)} inputs excluded at/after first fill')
    print(f'{sum(r.excluded_error4 for r in results)} inputs excluded from bit 4 scoring (conditions 1–4)')
    print(' bit  vendor set  mismatches  scored')
    counts = [sum(getattr(r, key) for r in results) for key in ('vendor_active', 'mismatches', 'scored_mismatches')]
    for bit, active, raw, scored in zip(BITS, *counts):
        score = str(scored) if bit & args.modelled else '–'
        print(f'{bit:4} {active:11} {raw:11} {score:>7}')
    print(f'Vendor withheld, engine publication-eligible: {sum(r.withheld for r in results)} '
          f'({sum(r.withheld_error for r in results)} with a vendor error)')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
