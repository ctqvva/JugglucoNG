"""Compare engine error-16 conditions and latch with the pinned reference."""
import argparse
import itertools
import random
from pathlib import Path

from err16lib import decay_specs
from sc import REF, co, mf, np, scenarios
import differential as df


KOTLIN = Path(__file__).resolve().parent / 'kotlin'
PROTOTYPE_RESULT = np.dtype([('error16_conditions', '<i4'), ('error', '<i4')])


def compare(jar, name, spec, start=None):
    device = co.default_device()
    params = tuple(spec.get(name, scenarios.DEFAULTS[name]) for name in ('slope100', 'vref', 'eapp'))
    for field, value in zip(('slope100', 'vref', 'eapp'), params):
        device[field] = value
    inputs = co.records(*scenarios.generate(**spec))
    count, output, debug, _, states = REF.run(device, inputs, snapshots=start is not None)
    begin = start or 0
    takeover = states[start - 1:start] if start is not None else None
    engine = df.run_engine(jar, params, inputs[begin:], takeover,
                           main_class='Error16CliKt', result_type=PROTOTYPE_RESULT)
    slot = np.maximum(count, 1) - 1
    rows = np.arange(begin, len(inputs))
    # Same comparison boundary as the shared differential tool.
    fills = np.flatnonzero(count[rows] > 1)
    if fills.size:
        rows = rows[:fills[0]]
        engine = engine[:fills[0]]
    expected = debug[rows, slot[rows]]['err16_condi']
    bits = 1 << np.arange(7)
    have = (engine['error16_conditions'][:, None] & bits) != 0
    condition_errors = have != expected
    latch_errors = ((output[rows, slot[rows]]['errcode'] ^ engine['error']) & 16) != 0
    for label, bad in [('conditions', condition_errors.any(axis=1)), ('latch', latch_errors)]:
        if bad.any():
            first = np.flatnonzero(bad)[0]
            seq = inputs[rows[first]]['seq_number']
            print(f'{name}: {label} mismatch on {bad.sum()} records, first input {rows[first] + 1} (seq {seq}), '
                  f'vendor {expected[first].tolist()}, engine {have[first].astype(int).tolist()}')
    return len(rows), expected.sum(axis=0, dtype=int), condition_errors.sum(axis=0), int(latch_errors.sum())


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
    parser.add_argument('--import-state', action='store_true')
    args = parser.parse_args()
    if REF.sha256 not in co.KNOWN_REFERENCE:
        parser.error(f'unexpected reference SHA-256: {REF.sha256}')
    if args.import_state and not (args.sweep_case or args.sweep is not None):
        parser.error('--import-state requires --sweep or --sweep-case')

    jar = df.engine_jar(KOTLIN / 'Error16Cli.kt', [KOTLIN / 'Error16Prototype.kt'])
    if args.sweep_case:
        spec, start = sweep_case(*args.sweep_case, args.import_state)
        cases = [(f'sweep {args.sweep_case} (takeover {start})', spec, start)]
    elif args.sweep is not None:
        cases = itertools.islice(sweep_cases(args.sweep, args.import_state), args.runs)
    elif args.fixture:
        specs = mf.DEVELOPMENT | mf.HOLDOUT
        cases = [(name, specs[name], None) for name in args.fixture]
    else:
        cases = ((n, spec, None) for n, spec in enumerate(itertools.islice(decay_specs(), args.runs)))
    records, latch = 0, 0
    active, mismatch = np.zeros((2, 7), dtype=int)
    for name, spec, start in cases:
        n, seen, wrong, latches = compare(jar, name, spec, start)
        records += n
        active += seen
        mismatch += wrong
        latch += latches
    print(f'{records} compared records; vendor condition hits {active.tolist()}')
    print(f'Per-condition mismatches {mismatch.tolist()}; latch mismatches {latch}')
    return int(mismatch.any() or latch != 0)


if __name__ == '__main__':
    raise SystemExit(main())
