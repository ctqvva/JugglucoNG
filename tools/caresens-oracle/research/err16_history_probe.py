"""Verify sequence-window statistics directly on vendor snapshots in mixed runs."""
import argparse

from err16lib import W
from err16_replay import sweep_case
from err16_state_probe import PREFIX, Replay
from err16_statistics import percentile_bounds
from sc import REF, co, np, runspec


CASES = ((7, 19), (7, 0), (7, 85), (8, 72), (101, 2), (101, 3), (101, 61), (101, 269), (202, 197))


def statistics(state, previous):
    q = state['accu_seq'].astype(int)
    age = q[-1] - q
    field = lambda name: state[PREFIX + name]
    minutes = state['err16_dt_arr']
    signal = field('smooth')[-7:]
    signal = signal[np.isfinite(signal)]
    rate = np.nan
    if q[-7] >= 865 and len(signal) >= 4 and minutes[-1] - minutes[-7] <= 40:
        rate = np.ptp(signal)
    rates = field('roc_value')
    candidates = rates[(age[-len(rates):] <= 288) & (q[-len(rates):] != 0) & np.isfinite(rates)]
    steady = np.nan
    if np.isfinite(rate) and len(candidates) >= 231:
        steady = 100 * np.count_nonzero(candidates <= 1) / len(candidates)
    yield 'roc_value', rate
    yield 'roc_steady', steady

    in_window = q[-692] != 0 and age[-692] <= 864
    for name, suffix, stride, points, need in (
        ('min', '1', 3, 12, 12), ('min', '2', 12, 73, 12), ('mean', '', 12, 24, 24),
    ):
        y = field(f'trend_{name}_value_arr')
        start = np.flatnonzero((age <= 864) & (q != 0) & np.isfinite(y))
        slope = rsq = np.nan
        if len(start) and in_window:
            idx = np.arange(start[0], len(y), stride)[-points:]
            idx = idx[np.isfinite(y[idx])]
            if len(idx) >= need:
                dx = (q[idx] - q[idx[0]]) / 288
                dy = y[idx]
                if (dy == dy[0]).all():
                    slope = 0.0
                else:
                    dx, dy = dx - dx.mean(), dy - dy.mean()
                    xy, xx, yy = dx @ dy, dx @ dx, dy @ dy
                    slope = xy / xx
                    rsq = xy**2 / (xx * yy)
        yield f'trend_{name}_slope{suffix}', slope
        yield f'trend_{name}_rsq{suffix}', rsq

    for name, candidate, count, active, tolerance in (
        ('trend_min_max', 'trend_min_max_temp', 'trend_min_n', in_window and q[-1] >= 865, 0),
        ('trend_mean_max', 'trend_mean_max_temp', 'trend_mean_n', in_window and q[-1] >= 865, 5),
        ('trend_mean_max_early', 'trend_mean_max_temp_early', None, in_window, 5),
        ('roc_min', 'roc_min_temp', 'roc_n', np.isfinite(steady), 2),
    ):
        history = field(candidate)
        window = history[(age <= 24) & (q != 0)]
        value = previous[PREFIX + name] if previous is not None else np.nan
        if count is None and not active:
            value = np.nan
        elif active and count and field(count) == 864:
            _, value = percentile_bounds(history[-864:])
        elif active and (count is None or field(count) < 864):
            if len(window) >= 20 and (np.abs(window - history[-1]) <= tolerance).all():
                value = history[-1]
        yield name, value


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
        for name, actual in statistics(state, states[i - 1] if i else None):
            expected = state[PREFIX + name]
            if expected.ndim:
                expected = expected[-1]
            if not np.isclose(expected, actual, atol=1e-9, rtol=0, equal_nan=True):
                differences.setdefault(name, []).append(int(seq[i]))
        q = state['accu_seq'][-12:].astype(int)
        plasma = np.nan
        if q[0] and q[-1] - q[0] == 11:
            value = state['err_glu_arr'][-12:] @ W[::-1]
            plasma = np.copysign(np.floor(abs(value) + 0.5), value)
        if not np.isclose(plasma, state['err16_CGM_plasma'][-1], atol=0, rtol=0, equal_nan=True):
            differences.setdefault('plasma from rounded vendor glucose', []).append(int(seq[i]))
    print(f'seed {seed} run {index}: {compared} accepted records; {sum(map(len, differences.values()))} differences')
    for name, rows in differences.items():
        print(f'  {name}: {len(rows)}, first seq {rows[0]}')
    return not differences


def verify_condition_boundaries():
    for index, target in ((93, 2672), (214, 1737)):
        spec, _ = sweep_case(101, index, False)
        seq, times, adc, temperature, _, _, _, states, device = runspec(spec, True)
        row = int(np.searchsorted(seq, target))
        assert seq[row] == target
        record = co.records(seq[row:row + 1], times[row:row + 1], adc[row:row + 1], temperature[row:row + 1])
        replay = Replay(device, states[row - 1:row], record)
        _, debug, _ = replay.step()
        if index == 93:
            assert not debug['err16_condi'][1]
            consecutive = seq[row - 1] - np.arange(22, -1, -1)
            _, altered, _ = replay.step([('accu_seq', slice(-23, None), consecutive)])
            assert altered['err16_condi'][1]
        else:
            assert debug['err16_condi'][0]
            for field in ('trend_mean_diff_early', 'trend_mean_ratio_early'):
                for loss, expected in ((-70.0, 1), (-69.999, 0)):
                    _, altered, _ = replay.step([(PREFIX + field, slice(-23, None), loss)])
                    assert altered['err16_condi'][0] == expected
            _, altered, _ = replay.step([(PREFIX + 'trend_mean_n', Ellipsis, 863.0)])
            assert not altered['err16_condi'][0]  # This input completes the main reference.
        print(f'seed 101 run {index}: condition boundary verified at sequence {target}')
        del states


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sweep-case', type=int, nargs=2, action='append', metavar=('SEED', 'INDEX'))
    args = parser.parse_args()
    if REF.sha256 not in co.KNOWN_REFERENCE:
        parser.error(f'unexpected reference SHA-256: {REF.sha256}')
    passed = [verify(*case) for case in args.sweep_case or CASES]
    if not args.sweep_case:
        verify_condition_boundaries()
    return int(not all(passed))


if __name__ == '__main__':
    raise SystemExit(main())
