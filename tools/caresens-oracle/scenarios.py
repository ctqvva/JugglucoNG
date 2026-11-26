"""Deterministic CareSens Air record scenarios.

The generator uses SplitMix64 and only +, -, *, / and floor on doubles, so the
Kotlin copy in CareSensAirScenarios.kt produces bit-identical records. The
fixtures therefore store only the reference outputs, never the inputs.

Glucose is turned into ADC counts with a rough inverse of the factory chain
(slope, temperature, applied potential), so the vendor sees currents a real
sensor would produce over the physiological range.
"""
import json
import math

MASK = (1 << 64) - 1
BASE_TIME = 1709726400


class SplitMix:
    def __init__(self, seed):
        self.s = seed & MASK

    def next(self):
        self.s = (self.s + 0x9E3779B97F4A7C15) & MASK
        z = self.s
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK
        return z ^ (z >> 31)

    def below(self, m):
        return (self.next() >> 1) % m

    def between(self, a, b):
        return a + self.below(b - a + 1)


DEFAULTS = dict(
    seed=1, readings=1440, start=1,
    slope100=3.5226, vref=1.49594, eapp=0.10067,
    lo=70, hi=180, knot_min=60, knot_max=300,
    noise=12,
    temp_mode='const', temp_lo=3400, temp_hi=3400, temp_knot_min=30, temp_knot_max=200,
    decay=1.0,
    gap_permille=0, gap_max=1, jitter=0,
    dropout_permille=0, dropout_min=3, dropout_max=40,
    spike_permille=0,
    clock_jump_at=-1, clock_jump=0,
)


def f32(x):
    import struct
    return struct.unpack('<f', struct.pack('<f', x))[0]


def generate(**spec):
    """Returns (sequence, time, adc[30], temperature) lists."""
    p = dict(DEFAULTS, **spec)
    rng = SplitMix(p['seed'])
    n = p['readings']
    slope, vref, eapp = f32(p['slope100']), f32(p['vref']), f32(p['eapp'])
    offset = 0.243 if eapp < 0.075 else 0.7

    # Glucose knots, in tenths of mg/dL, at sample positions.
    total = n * 30
    knots = [(0, rng.between(p['lo'] * 10, p['hi'] * 10))]
    while knots[-1][0] < total:
        knots.append((knots[-1][0] + 30 * rng.between(p['knot_min'], p['knot_max']), rng.between(p['lo'] * 10, p['hi'] * 10)))
    temp_knots = [(0, rng.between(p['temp_lo'], p['temp_hi']))]
    while temp_knots[-1][0] < n:
        temp_knots.append((temp_knots[-1][0] + rng.between(p['temp_knot_min'], p['temp_knot_max']), rng.between(p['temp_lo'], p['temp_hi'])))

    seqs, times, adcs, temps = [], [], [], []
    seq = p['start']
    k = 0
    tk = 0
    dropout = 0
    for r in range(n):
        if r > 0:
            seq += 1
            if p['gap_permille'] and rng.below(1000) < p['gap_permille']:
                seq += rng.between(1, p['gap_max'])
        while temp_knots[tk + 1][0] <= r:
            tk += 1
        (a, ta), (b, tb) = temp_knots[tk], temp_knots[tk + 1]
        if p['temp_mode'] == 'step':
            hundredths = ta
        else:
            hundredths = math.floor(ta + (tb - ta) * (r - a) / (b - a))
        temperature = hundredths / 100.0
        factor = 1.0 - (1.0 - p['decay']) * r / n
        if p['dropout_permille'] and dropout == 0 and rng.below(1000) < p['dropout_permille']:
            dropout = rng.between(p['dropout_min'], p['dropout_max'])
        row = []
        for j in range(30):
            pos = r * 30 + j
            while knots[k + 1][0] <= pos:
                k += 1
            (a, ga), (b, gb) = knots[k], knots[k + 1]
            glucose = (ga + (gb - ga) * (pos - a) / (b - a)) / 10.0
            noise = (rng.between(-p['noise'], p['noise']) + rng.between(-p['noise'], p['noise'])
                     + rng.between(-p['noise'], p['noise']) + rng.between(-p['noise'], p['noise'])) / 2.0
            if dropout:
                current = 0.15
            else:
                current = glucose * slope / 100.0 * (1.0 + 0.0328 * (temperature - 34.0854)) * factor + offset
            adc = math.floor((current / 100.0 + eapp) * 40950.0 / vref + noise + 0.5)
            row.append(min(65535, max(0, adc)))
        if dropout:
            dropout -= 1
        if p['spike_permille'] and rng.below(1000) < p['spike_permille']:
            j = rng.below(2)
            row[j] = min(65535, math.floor(row[j] * 1.6))
        t = BASE_TIME + 300 * seq
        if p['jitter']:
            t += rng.between(-p['jitter'], p['jitter'])
        if 0 <= p['clock_jump_at'] <= r:
            t += p['clock_jump']
        seqs.append(seq & 0xffff)
        times.append(t & 0xffffffff)
        adcs.append(row)
        temps.append(temperature)
    return seqs, times, adcs, temps


def spec_line(spec):
    return json.dumps(dict(DEFAULTS, **spec), sort_keys=True)
