#!/usr/bin/env python3
"""Perturb one packed device parameter at a time and report bit sensitivity."""
import itertools

from sc import REF, co, mf, np, scenarios
from stress import base, tf


BITS = (1, 2, 4, 8, 16, 32, 64, 128)
FIXTURES = ('life-lot2-sensitivity-loss', 'holdout-life-other-lot1', 'clock-jump', 'invalid-parameters')


def cases():
    seq, time, adc, temp = base()
    for name in ('noise200', 'high3', 'sat', 'low30', 'step_down'):
        signal = tf(name, adc, 500, 700, random=np.random.default_rng(1))
        yield name, co.default_device(), co.records(seq, time, signal, temp)
    for name in FIXTURES:
        spec = (mf.DEVELOPMENT | mf.HOLDOUT)[name]
        full = scenarios.DEFAULTS | spec
        device = co.default_device()
        for key in ('slope100', 'vref', 'eapp'):
            device[key][0] = full[key]
        yield name, device, co.records(*scenarios.generate(**spec))


def parameters():
    device = co.default_device()
    for name in device.dtype.names:
        if name.startswith('err') or name in ('cal_noise', 'sensor_version'):
            for index in np.ndindex(device[name][0].shape):
                value = device[name][(0,) + index]
                if value.dtype.itemsize == 1:
                    candidates = (int(value) - 1, int(value) + 1, 0)
                elif value == 0:
                    candidates = (-1, 1)  # scaling zero cannot change it
                else:
                    candidates = (value.item() * 0.5, value.item() * 2)
                if np.issubdtype(value.dtype, np.integer):
                    bounds = np.iinfo(value.dtype)
                    candidates = (int(v) for v in candidates if bounds.min <= v <= bounds.max)
                values = sorted(set(np.array(list(candidates), dtype=value.dtype)) - {value})
                label = name + ''.join(f'[{i}]' for i in index)
                yield name, (0,) + index, label, value, values


def error_trace(device, records):
    count, output, _, _, _ = REF.run(device.copy(), records.copy())
    # Same slot as air/java.cpp, including slot zero when res is zero.
    last = np.maximum(count, 1) - 1
    return output[np.arange(len(records)), last]['errcode']


def bit_summary(trace, bit):
    hits = np.flatnonzero(trace & bit)
    return f'{hits[0] + 1}/{hits.size}' if hits.size else '–'


def row(cells):
    print('| ' + ' | '.join(map(str, cells)) + ' |')


def main():
    if REF.sha256 not in co.KNOWN_REFERENCE:
        raise SystemExit(f'Unexpected reference SHA-256: {REF.sha256}')
    suite = [(name, device, records, error_trace(device, records)) for name, device, records in cases()]
    seen = int(np.bitwise_or.reduce([np.bitwise_or.reduce(trace) for _, _, _, trace in suite]))
    print(f'Reference SHA-256 `{REF.sha256}`.\n')
    print('## Default-block controls\n')
    row(['Scenario', *BITS])
    row(['---'] * (len(BITS) + 1))
    for name, _, _, trace in suite:
        row([name, *(bit_summary(trace, bit) for bit in BITS)])

    print('\n## One-field perturbations\n')
    row(['Device field', 'Default', 'Values tried', *BITS])
    row(['---'] * (len(BITS) + 3))
    details = []
    for name, index, label, default, values in parameters():
        changed = 0
        for value, (case, device, records, baseline) in itertools.product(values, suite):
            mutated = device.copy()
            mutated[name][index] = value
            trace = error_trace(mutated, records)
            difference = int(np.bitwise_or.reduce(trace ^ baseline))
            changed |= difference
            for bit in BITS:
                if difference & bit:
                    first = np.flatnonzero((trace ^ baseline) & bit)[0] + 1
                    details.append([label, f'{value:.8g}', case, bit, first,
                                    bit_summary(baseline, bit), bit_summary(trace, bit)])
        row([f'`{label}`', f'{default:.8g}', ', '.join(f'{v:.8g}' for v in values),
             *('Changed' if changed & bit else '=' if seen & bit else '?' for bit in BITS)])

    print('\n## Changed traces\n')
    if details:
        row(['Field', 'Value', 'Scenario', 'Bit', 'First difference', 'Default first/count', 'Perturbed first/count'])
        row(['---'] * 7)
        for detail in details:
            row(detail)
    else:
        print('No bit trace changed.')


if __name__ == '__main__':
    main()
