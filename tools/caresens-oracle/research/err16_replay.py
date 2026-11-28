"""Compare error-16 statistics, conditions and latch with the pinned reference."""
import argparse
from collections import Counter
import itertools
import random
from pathlib import Path

from err16lib import decay_specs
from err16_state_probe import PREFIX
from sc import REF, co, mf, np, scenarios
import differential as df


KOTLIN = Path(__file__).resolve().parent / 'kotlin'
PROTOTYPE_INPUT = np.dtype([('record', co.INPUT), ('mode', 'u1'), ('revised_current', '<f8')])
SOURCE = 'err128_CGM_c_noise_revised_value'
# Field order matches Error16Cli and Error16Prototype.writeStatistics. For histories, read
# the newest state slot, which stays observable even on a rejected input.
STATS = (SOURCE, 'err16_time5_first', 'err16_dt_arr', PREFIX + 'smooth', 'err16_CGM_plasma') + tuple(
    PREFIX + key for key in (
        'roc_value', 'roc_steady', 'roc_n', 'roc_min_temp', 'roc_min', 'roc_diff', 'roc_ratio',
        'trend_min_value_arr', 'trend_mean_value_arr',
        'trend_min_slope1', 'trend_min_rsq1', 'trend_min_slope2', 'trend_min_rsq2', 'trend_mean_slope', 'trend_mean_rsq',
        'trend_min_n', 'trend_min_max_temp', 'trend_min_max', 'trend_mean_n', 'trend_mean_max_temp', 'trend_mean_max',
        'trend_min_diff', 'trend_min_ratio', 'trend_mean_diff', 'trend_mean_ratio',
        'trend_mean_max_temp_early', 'trend_mean_max_early', 'trend_mean_diff_early', 'trend_mean_ratio_early',
    )
) + ('diabetes_before_7days_flag', 'diabetes_before_7days_n_valid')
PROTOTYPE_RESULT = np.dtype([('error16_conditions', '<i4'), ('error', '<i4'), ('accepted', '<i4'),
                             *[(field, '<f8') for field in STATS]])


def first(mask):
    indices = np.flatnonzero(mask)
    return int(indices[0]) if len(indices) else None


def compare(jar, name, spec, start, current_mode, details):
    subject = 'engine' if current_mode == 3 else 'prototype'
    device = co.default_device()
    params = tuple(spec.get(name, scenarios.DEFAULTS[name]) for name in ('slope100', 'vref', 'eapp'))
    for field, value in zip(('slope100', 'vref', 'eapp'), params):
        device[field] = value
    inputs = co.records(*scenarios.generate(**spec))
    count, output, debug, _, states = REF.run(device, inputs, snapshots=True)
    begin = start or 0
    takeover = states[start - 1:start] if start is not None else None
    # Same comparison boundary as the shared differential tool.
    fill = first(count[begin:] > 1)
    end = len(inputs) if fill is None else begin + fill
    band = slice(begin, end)
    recs = np.zeros(end - begin, dtype=PROTOTYPE_INPUT)
    recs['record'] = inputs[band]
    recs['mode'] = current_mode
    if current_mode == 1:
        recs['revised_current'] = states[SOURCE][band, -1]
    engine = df.run_engine(jar, params, recs, takeover, main_class='Error16CliKt', result_type=PROTOTYPE_RESULT)
    vendor = output[band, 0]
    expected = debug[band, 0]['err16_condi']
    bits = 1 << np.arange(7)
    have = (engine['error16_conditions'][:, None] & bits) != 0
    condition_errors = have != expected
    vendor_latched = (vendor['errcode'] & 16) != 0
    engine_latched = (engine['error'] & 16) != 0
    false_positive = ~vendor_latched & engine_latched
    missed = vendor_latched & ~engine_latched

    def position(i):
        row = begin + i
        seq = inputs[row]['seq_number']
        gap = int(seq) - int(inputs[row - 1]['seq_number']) if row else 0
        return (f'input {row + 1} (seq {seq}, input step {gap}, '
                f'vendor type {vendor[i]["data_type"]}, engine accepted {engine[i]["accepted"]})')

    differences = []
    vendor_accepts = (count[band] != 0) & (vendor['data_type'] != 2) & ((vendor['errcode'] & 64) == 0)
    acceptance = first(vendor_accepts != (engine['accepted'] != 0))
    if acceptance is not None:
        differences.append((acceptance, 'accepted', vendor_accepts[acceptance], engine['accepted'][acceptance]))
    for field in STATS:
        values = states[field][band]
        if values.ndim > 1:
            values = values[:, -1]
        index = first(~np.isclose(values, engine[field], atol=1e-9, rtol=0, equal_nan=True))
        if index is not None:
            differences.append((index, field.removeprefix(PREFIX), values[index], engine[field][index]))
    if differences:
        chronological = sorted(differences, key=lambda entry: entry[0])
        for index, field, want, got in chronological if details else chronological[:1]:
            print(f'{name}: {field} first differs at {position(index)}: vendor={want} {subject}={got}')
    first_condition = first(condition_errors.any(axis=1))
    if first_condition is not None:
        i = first_condition
        print(f'{name}: first condition divergence at {position(i)}: '
              f'vendor {expected[i].tolist()}, {subject} {have[i].astype(int).tolist()}')
    first_latch = first(false_positive | missed)
    if first_latch is not None:
        i = first_latch
        print(f'{name}: first latch divergence at {position(i)}: '
              f'vendor {int(vendor_latched[i])}, {subject} {int(engine_latched[i])}')
    counts = Counter()
    counts['statistic runs'] = int(bool(differences))
    counts['condition runs'] = int(first_condition is not None)
    counts['latch runs'] = int(first_latch is not None)
    counts['false-positive latch records'] = int(false_positive.sum())
    counts['missed latch records'] = int(missed.sum())
    vendor_first, engine_first = first(vendor_latched), first(engine_latched)
    if vendor_first != engine_first:
        if vendor_first is None:
            detection = 'false-positive failure'
        elif engine_first is None:
            detection = 'missed failure'
        else:
            detection = 'early failure' if engine_first < vendor_first else 'late failure'
        counts[detection] += 1
        want = 'none' if vendor_first is None else position(vendor_first)
        got = 'none' if engine_first is None else position(engine_first)
        print(f'{name}: {detection}: vendor first {want}; {subject} first {got}')
    return len(recs), expected.sum(axis=0, dtype=int), condition_errors.sum(axis=0), counts


def sweep_cases(seed, takeover):
    rng = random.Random(seed)
    for index in itertools.count():
        spec = df.random_spec(rng)
        start = rng.randrange(2, spec['readings'] - 10) if takeover else None
        yield f'sweep {seed} run {index} (takeover {start})', spec, start


def sweep_case(seed, index, takeover):
    _, spec, start = next(itertools.islice(sweep_cases(seed, takeover), index, None))
    return spec, start


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=160)
    cases_arg = parser.add_mutually_exclusive_group()
    cases_arg.add_argument('--fixture', action='append', choices=tuple(mf.DEVELOPMENT | mf.HOLDOUT))
    cases_arg.add_argument('--sweep-case', nargs=2, type=int, metavar=('SEED', 'INDEX'))
    cases_arg.add_argument('--sweep', type=int, metavar='SEED')
    state_arg = parser.add_mutually_exclusive_group()
    state_arg.add_argument('--import-state', action='store_true')
    state_arg.add_argument('--takeover-after', type=int, metavar='RECORDS',
                           help='start from vendor state after this many inputs of a named fixture')
    current_arg = parser.add_mutually_exclusive_group()
    current_arg.add_argument('--revised-current', action='store_true',
                             help='inject only the vendor revised current into Error16Prototype')
    current_arg.add_argument('--ordinary-current', action='store_true',
                             help='pass the ordinary five-minute current without err128 revision')
    current_arg.add_argument('--engine', action='store_true', help='compare the actual calibration engine detector hooks')
    parser.add_argument('--details', action='store_true', help='print the first discrepancy for every statistic in each run')
    args = parser.parse_args()
    if REF.sha256 not in co.KNOWN_REFERENCE:
        parser.error(f'unexpected reference SHA-256: {REF.sha256}')
    if args.import_state and not (args.sweep_case or args.sweep is not None):
        parser.error('--import-state requires --sweep or --sweep-case')
    if args.takeover_after is not None:
        specs = mf.DEVELOPMENT | mf.HOLDOUT
        if not args.fixture or any(not 0 < args.takeover_after < specs[name]['readings'] for name in args.fixture):
            parser.error('--takeover-after requires fixtures and a record count inside each fixture')
    if args.runs < 1 or (args.sweep_case and args.sweep_case[1] < 0):
        parser.error('run count must be positive and run index nonnegative')

    jar = df.engine_jar(KOTLIN / 'Error16Cli.kt', [KOTLIN / 'Error16Prototype.kt', KOTLIN / 'Error128RevisedCurrent.kt'])
    if args.sweep_case:
        seed, index = args.sweep_case
        spec, start = sweep_case(seed, index, args.import_state)
        cases = [(f'sweep {seed} run {index} (takeover {start})', spec, start)]
    elif args.sweep is not None:
        cases = itertools.islice(sweep_cases(args.sweep, args.import_state), args.runs)
    elif args.fixture:
        specs = mf.DEVELOPMENT | mf.HOLDOUT
        cases = [(name, specs[name], args.takeover_after) for name in args.fixture]
    else:
        cases = ((n, spec, None) for n, spec in enumerate(itertools.islice(decay_specs(), args.runs)))
    current_mode = 0
    if args.revised_current:
        current_mode = 1
    elif args.ordinary_current:
        current_mode = 2
    elif args.engine:
        current_mode = 3
    records = runs = 0
    total = Counter()
    active, mismatch = np.zeros((2, 7), dtype=int)
    for name, spec, start in cases:
        n, seen, wrong, counts = compare(jar, name, spec, start, current_mode, args.details)
        runs += 1
        records += n
        active += seen
        mismatch += wrong
        total.update(counts)
    print(f'{runs} runs, {records} compared records; vendor condition hits {active.tolist()}')
    print(f'Per-condition mismatches {mismatch.tolist()}')
    for label in ('statistic runs', 'condition runs', 'latch runs', 'false-positive failure', 'missed failure',
                  'early failure', 'late failure', 'false-positive latch records', 'missed latch records'):
        print(f'{label}: {total[label]}')
    return int(bool(total['condition runs'] or total['latch runs']))


if __name__ == '__main__':
    raise SystemExit(main())
