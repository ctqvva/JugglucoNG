"""Verify recovered error-16 statistics against packed oracle snapshots."""
import argparse
from functools import lru_cache

from err16_state_probe import PREFIX, Replay
from sc import co, mf, np, REF, runspec


def percentile_bounds(samples):
    """Use the rounded one-based rank and average the inclusive 20–80% band."""
    values = np.sort(samples[~np.isnan(samples)])
    if not len(values):
        return np.nan, np.nan
    rank = lambda pct: max(0, (len(values) * pct + 50) // 100 - 1)
    low, high = values[rank(20)], values[rank(80)]
    mean = values[(values >= low) & (values <= high)].mean()
    return values[rank(10)], mean


@lru_cache(maxsize=None)
def centered_days(count, stride):
    x = np.arange(count) * stride / 288
    return x - x.mean()


def regression(series, count, stride):
    """Return least-squares slope per day and R² for the last sampled points."""
    y = series[-1::-stride][:count][::-1]
    good = ~np.isnan(y)
    if not good.all():
        y = y[good]
        x = centered_days(count, stride)[good]
        x = x - x.mean()
    else:
        x = centered_days(count, stride)
    dy = y - y.mean()
    dot = x @ dy
    xx, yy = x @ x, dy @ dy
    return (dot / xx if xx else np.nan, dot ** 2 / (xx * yy) if xx and yy else np.nan)


def statistics(state):
    field = lambda name: state[PREFIX + name]
    signal = field('smooth')
    rates = field('roc_value')[-289:]
    n_rates = np.count_nonzero(~np.isnan(rates))
    low, center = percentile_bounds(signal)
    ans = dict(
        trend_min_value=low,
        trend_mean_value=center,
        roc_value=np.ptp(signal[-7:]),
        roc_steady=100 * np.count_nonzero(rates <= 1) / n_rates if n_rates else np.nan,
    )
    for name, count, stride, suffix in (
        ('trend_min', 12, 3, '1'), ('trend_min', 73, 12, '2'), ('trend_mean', 24, 12, ''),
    ):
        slope, rsq = regression(field(name + '_value_arr'), count, stride)
        ans[name + '_slope' + suffix] = slope
        ans[name + '_rsq' + suffix] = rsq
    return ans


def verify_fixture(name):
    spec = (mf.DEVELOPMENT | mf.HOLDOUT)[name]
    seq, _, _, _, _, output, debug, states, _ = runspec(spec, True)
    differences = {}
    compared = {}
    for i, state in enumerate(states):
        # Check the numeric definitions after their active window. Update
        # eligibility and early fallback are investigated separately.
        if seq[i] < 1728 or output['data_type'][i] == 2:
            continue
        for key, actual in statistics(state).items():
            expected = debug[PREFIX + key][i]
            compared[key] = compared.get(key, 0) + 1
            if not np.isclose(actual, expected, atol=1e-9, rtol=0, equal_nan=True):
                differences.setdefault(key, []).append(int(seq[i]))
    print(name)
    for key, count in compared.items():
        bad = differences.get(key, [])
        print(f'  {key:20} {len(bad)}/{count} mismatches', f'first {bad[:5]}' if bad else '')


def verify_rate_probe():
    replay = Replay(98, 1728)
    _, debug, state = replay.step([(PREFIX + 'smooth', 862, 500.0)])
    signal = state[PREFIX + 'smooth'][-7:]
    assert debug[PREFIX + 'roc_value'] == np.ptp(signal)
    assert debug[PREFIX + 'roc_value'] != abs(signal[-1] - signal[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fixtures', nargs='*', default=['life-lot2-sensitivity-loss', 'holdout-life-other-lot1'])
    args = parser.parse_args()
    if REF.sha256 not in co.KNOWN_REFERENCE:
        parser.error(f'unexpected reference SHA-256: {REF.sha256}')
    verify_rate_probe()
    for name in args.fixtures:
        verify_fixture(name)


if __name__ == '__main__':
    main()
