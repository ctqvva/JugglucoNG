"""Verify sequence-window statistics directly on vendor snapshots in mixed runs."""
import argparse

from err16_replay import sweep_case
from err16_state_probe import PREFIX
from sc import REF, co, np, runspec


CASES = ((7, 19), (7, 0), (7, 85), (8, 72), (101, 2), (101, 3), (101, 61), (202, 197))


def statistics(state):
    q = state['accu_seq'].astype(int)
    age = q[-1] - q
    field = lambda name: state[PREFIX + name]
    minutes = state['err16_dt_arr']
    signal = field('smooth')[-7:]
    signal = signal[np.isfinite(signal)]
    rate = np.nan
    if q[-1] >= 871 and len(signal) >= 4 and minutes[-1] - minutes[-7] <= 40:
        rate = np.ptp(signal)
    rates = field('roc_value')
    candidates = rates[(age[-len(rates):] <= 288) & (q[-len(rates):] != 0) & np.isfinite(rates)]
    steady = np.nan
    if np.isfinite(rate) and len(candidates) >= 231:
        steady = 100 * np.count_nonzero(candidates <= 1) / len(candidates)
    yield 'roc_value', rate
    yield 'roc_steady', steady

    for name, suffix, stride, points, need in (
        ('min', '1', 3, 12, 12), ('min', '2', 12, 73, 12), ('mean', '', 12, 24, 24),
    ):
        y = field(f'trend_{name}_value_arr')
        start = np.flatnonzero((age <= 864) & (q != 0) & np.isfinite(y))
        slope = rsq = np.nan
        if len(start) and q[-692] != 0 and age[-692] <= 864:
            idx = np.arange(start[0], len(y), stride)[-points:]
            idx = idx[np.isfinite(y[idx])]
            if len(idx) >= need:
                dx = (q[idx] - q[idx[0]]) / 288
                dy = y[idx]
                dx, dy = dx - dx.mean(), dy - dy.mean()
                xy, xx, yy = dx @ dy, dx @ dx, dy @ dy
                slope = xy / xx
                rsq = xy**2 / (xx * yy) if yy else np.nan
        yield f'trend_{name}_slope{suffix}', slope
        yield f'trend_{name}_rsq{suffix}', rsq


def verify(seed, index):
    spec, _ = sweep_case(seed, index, seed in (7, 8))
    seq, _, _, _, count, output, _, states, _ = runspec(spec, True)
    differences = {}
    compared = 0
    for i, state in enumerate(states):
        if count[i] > 1:
            break
        if not count[i] or output['data_type'][i] == 2:
            continue
        compared += 1
        for name, actual in statistics(state):
            expected = state[PREFIX + name][-1]
            if not np.isclose(expected, actual, atol=1e-9, rtol=0, equal_nan=True):
                differences.setdefault(name, []).append(int(seq[i]))
    print(f'seed {seed} run {index}: {compared} accepted records; {sum(map(len, differences.values()))} differences')
    for name, rows in differences.items():
        print(f'  {name}: {len(rows)}, first seq {rows[0]}')
    return not differences


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sweep-case', type=int, nargs=2, action='append', metavar=('SEED', 'INDEX'))
    args = parser.parse_args()
    if REF.sha256 not in co.KNOWN_REFERENCE:
        parser.error(f'unexpected reference SHA-256: {REF.sha256}')
    passed = [verify(*case) for case in args.sweep_case or CASES]
    return int(not all(passed))


if __name__ == '__main__':
    raise SystemExit(main())
