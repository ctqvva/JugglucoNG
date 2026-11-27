#!/usr/bin/env python3
"""Writes the reference fixtures the Kotlin tests compare against.

    CARESENS_REFERENCE_LIB=/path/to/arm64/libCALCULATION.so tools/caresens-oracle/make_fixtures.py

Each fixture holds the scenario spec and, per record, what the reference
returned: record count, final sequence, error code, data type, glucose,
trend, the oldest of the six history values and the factory glucose before
the early-wear rescale (an intermediate, to localise differences). Inputs are
regenerated from the spec by CareSensAirScenarios.kt and checked against the
stored SHA-256 of the packed input records.

The holdout set uses seeds, factory values and scenario mixes that were not
used while the engine was written. Check it only to confirm a change; do not
tune against it.
"""
import argparse
import gzip
import hashlib
import os
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import caresens_oracle as co  # noqa: E402
import scenarios  # noqa: E402

OUT = os.path.join(co.REPO, 'Common', 'src', 'test', 'resources', 'caresens')

FULL = 4320  # 15 days of 5-minute records

DEVELOPMENT = {
    'life-normal': dict(seed=101, readings=FULL, lo=70, hi=180, temp_mode='vary', temp_lo=3350, temp_hi=3650),
    'life-wide': dict(seed=102, readings=FULL, lo=40, hi=400, knot_min=10, knot_max=60, noise=25,
                      temp_mode='vary', temp_lo=3200, temp_hi=3750),
    'life-lot2-sensitivity-loss': dict(seed=103, readings=FULL, slope100=4.6, vref=1.45, eapp=0.055, decay=0.5,
                                       lo=80, hi=250, temp_mode='vary', temp_lo=3300, temp_hi=3600),
    'sustained-lows': dict(seed=104, readings=1500, lo=40, hi=90, knot_min=30, knot_max=200),
    'sustained-highs': dict(seed=105, readings=1500, lo=250, hi=450),
    'temperature-steps': dict(seed=106, readings=1500, temp_mode='step', temp_lo=3000, temp_hi=3900,
                              temp_knot_min=5, temp_knot_max=60, lo=60, hi=250),
    'gaps-and-jitter': dict(seed=107, readings=1500, gap_permille=30, gap_max=12, jitter=40, lo=60, hi=300),
    'contact-spikes': dict(seed=108, readings=800, spike_permille=15),
    'dropouts': dict(seed=110, readings=1500, noise=25, dropout_permille=8, dropout_min=1, dropout_max=20,
                     gap_permille=20, gap_max=3, spike_permille=8),
    'clock-jump': dict(seed=109, readings=1200, clock_jump_at=700, clock_jump=40000),
    'takeover-mid-wear': dict(seed=110, start=500, readings=1000, lo=60, hi=260),
    'warmup-fast-changes': dict(seed=111, readings=400, lo=40, hi=350, knot_min=3, knot_max=15, noise=30),
    'invalid-parameters': dict(seed=112, readings=50, slope100=9.0),
}

HOLDOUT = {
    'holdout-life-other-lot1': dict(seed=9001, readings=FULL, slope100=2.9, vref=1.52, eapp=0.085, lo=60, hi=250,
                                    temp_mode='vary', temp_lo=3350, temp_hi=3600),
    'holdout-life-lot2-decay': dict(seed=9002, readings=FULL, slope100=5.7, vref=1.42, eapp=0.045, decay=0.7,
                                    lo=90, hi=200, temp_mode='vary', temp_lo=3300, temp_hi=3700),
    'holdout-fast-swings': dict(seed=9003, readings=2500, lo=40, hi=350, knot_min=6, knot_max=30, noise=40,
                                temp_mode='step', temp_lo=3100, temp_hi=3800, temp_knot_min=20, temp_knot_max=90),
    'holdout-gaps-spikes': dict(seed=9004, readings=1500, gap_permille=60, gap_max=30, jitter=25, spike_permille=8),
    'holdout-late-takeover-lows': dict(seed=9005, start=1200, readings=2000, lo=40, hi=110, decay=0.8),
    'holdout-invalid-vref': dict(seed=9006, readings=40, vref=1.7),
    'holdout-dropouts-late': dict(seed=9007, start=500, readings=1500, slope100=6.1, vref=1.47, eapp=0.06, noise=50,
                                  dropout_permille=6, dropout_min=2, dropout_max=40, gap_permille=15, gap_max=2,
                                  spike_permille=5),
}

RECORD = struct.Struct('<BHHBffff')


def write(ref, name, spec):
    seq, time, adc, temp = scenarios.generate(**spec)
    full = dict(scenarios.DEFAULTS, **spec)
    dev = co.default_device()
    for key in ('slope100', 'vref', 'eapp'):
        dev[key][0] = full[key]
    recs = co.records(seq, time, np.array(adc, 'u2'), temp)
    count, out, dbg, _, _ = ref.run(dev, recs)
    body = bytearray()
    for i in range(len(seq)):
        o = out[i, count[i] - 1] if count[i] else out[i, 0]
        d = dbg[i, count[i] - 1] if count[i] else dbg[i, 0]
        body += RECORD.pack(count[i], int(o['seq_number_final']), int(o['errcode']), int(o['data_type']),
                            float(o['result_glucose']), float(o['trendrate']), float(o['smooth_result_glucose'][0]),
                            float(d['init_cg']))
    header = scenarios.spec_line(spec).encode()
    blob = b'CSAF1' + struct.pack('<H', len(header)) + header + bytes.fromhex(ref.sha256) + hashlib.sha256(recs.tobytes()).digest() + struct.pack('<I', len(seq)) + body
    path = os.path.join(OUT, name + '.bin.gz')
    with gzip.GzipFile(path, 'wb', mtime=0) as f:
        f.write(blob)
    errors = sorted(set(int(e) for e in out[np.arange(len(seq)), np.maximum(count, 1) - 1]['errcode']))
    print('%-34s %5d records, error codes %s, %d bytes' % (name, len(seq), errors, os.path.getsize(path)))


IMPORT_FROM = ('gaps-and-jitter', (3, 290, 1100))


def write_vendor_states(ref):
    """Vendor state blocks after the given records, for the importer test."""
    name, points = IMPORT_FROM
    seq, time, adc, temp = scenarios.generate(**DEVELOPMENT[name])
    full = dict(scenarios.DEFAULTS, **DEVELOPMENT[name])
    dev = co.default_device()
    for key in ('slope100', 'vref', 'eapp'):
        dev[key][0] = full[key]
    _, _, _, _, snaps = ref.run(dev, co.records(seq, time, np.array(adc, 'u2'), temp), snapshots=True)
    blob = b'CSAS1' + struct.pack('<B', len(name)) + name.encode() + struct.pack('<B', len(points))
    for p in points:
        blob += struct.pack('<I', p) + snaps[p - 1].tobytes()
    path = os.path.join(OUT, 'vendor-states.bin.gz')
    with gzip.GzipFile(path, 'wb', mtime=0) as f:
        f.write(blob)
    print('vendor states after records %s of %s, %d bytes' % (points, name, os.path.getsize(path)))


def main():
    specs = DEVELOPMENT | HOLDOUT
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', action='append', choices=tuple(specs),
                        help='generate only this fixture and update the index (repeatable)')
    args = parser.parse_args()
    ref = co.Reference()
    if ref.sha256 not in co.KNOWN_REFERENCE:
        print('warning: reference library %s is not the one the fixtures were made with' % ref.sha256)
    os.makedirs(OUT, exist_ok=True)
    for name in args.fixture or specs:
        write(ref, name, specs[name])
    if args.fixture is None:
        write_vendor_states(ref)
    with open(os.path.join(OUT, 'index.txt'), 'w') as f:
        for name in DEVELOPMENT:
            f.write('development %s\n' % name)
        for name in HOLDOUT:
            f.write('holdout %s\n' % name)


if __name__ == '__main__':
    main()
