"""Compare candidate error-16 history rules with empty-list oracle outputs."""
import argparse
import itertools

from err16lib import decay_specs
from err16_state_probe import EARLY_PROFILE, PREFIX
from sc import REF, co, mf, np, runspec


LONG_SLOPE_HALF_STEP = 5e-11


def predict(states, profile):
    field = lambda name: states[PREFIX + name]
    lo = lambda name: field(name)[:, -24:].min(axis=1)
    hi = lambda name: field(name)[:, -24:].max(axis=1)

    def drop(name, threshold):
        return (hi(name + '_diff') <= -threshold) & (hi(name + '_ratio') <= -threshold)

    def fall(name, suffix, slope):
        # Long-trend slope comparisons round at the 1e-10 boundary.
        margin = LONG_SLOPE_HALF_STEP if suffix == '2' else 0
        return (hi(name + '_slope' + suffix) <= -slope - margin if margin else
                hi(name + '_slope' + suffix) < -slope) & (lo(name + '_rsq' + suffix) > 0.8)

    moderate = (profile == 3) | (profile == 4)
    strong = (profile == 5) | (profile == 6)
    diabetes_low = profile <= 2
    steady = lo('roc_steady')
    rise = (steady > 50) & (lo('roc_diff') > 50) & (lo('roc_ratio') > 50)
    near = (states['err16_dt_arr'][:, -1] - states['err16_dt_arr'][:, -7]) <= 40
    ready = field('trend_mean_n') >= 864

    major_loss = drop('trend_min', 50) & drop('trend_mean', 40)
    condition0 = ready & (
        ((diabetes_low | (profile == 100)) & drop('trend_mean', 50))
        | (moderate & major_loss)
        | (strong & major_loss & fall('trend_min', '2', 10) & (steady > 5) & near)
    )
    condition1 = ready & diabetes_low & (
        ((hi('trend_mean_diff') <= -50) & fall('trend_mean', '', 5))
        | ((hi('trend_min_diff') <= -50) & fall('trend_min', '1', 15))
    )
    flat = ((hi('trend_min_slope2') < 4 - LONG_SLOPE_HALF_STEP)
            & (lo('trend_min_slope2') > -4 + LONG_SLOPE_HALF_STEP)
            & (lo('trend_min_rsq2') > 0.9))
    contraction = drop('trend_min', 20) & drop('trend_mean', 20)
    middle = contraction & ((steady > 60) | rise)
    condition2 = ready & near & (
        ((diabetes_low | (profile == 100)) & drop('trend_mean', 35) & rise)
        | (moderate & middle)
        | (strong & middle & (fall('trend_min', '2', 7) | flat))
    )
    return np.column_stack([condition0, condition1, condition2])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=160, help='number of existing seed-11 decay scenarios')
    parser.add_argument('--fixtures', action='store_true', help='also compare the committed fixture specs')
    args = parser.parse_args()
    if REF.sha256 not in co.KNOWN_REFERENCE:
        parser.error(f'unexpected reference SHA-256: {REF.sha256}')

    fixtures = (mf.DEVELOPMENT | mf.HOLDOUT).items() if args.fixtures else ()
    cases = itertools.chain(enumerate(itertools.islice(decay_specs(), args.runs)), fixtures)
    seen, missing, extra = np.zeros((3, 7), dtype=int)
    for name, spec in cases:
        seq, _, _, _, count, output, debug, states, _ = runspec(spec, True)
        profile = np.r_[100, states[EARLY_PROFILE][:-1]]
        target = debug['err16_condi'].astype(bool)
        actual = predict(states, profile)
        actual &= ((output['data_type'] != 2) & ((output['errcode'] & 64) == 0))[:, None]
        bad = actual != target[:, :3]
        seen += target.sum(axis=0)
        missing[:3] += (target[:, :3] & ~actual).sum(axis=0)
        extra[:3] += (actual & ~target[:, :3]).sum(axis=0)
        if bad.any() or (count > 1).any():
            rows = np.flatnonzero(bad.any(axis=1))
            print(f'{name}: {len(rows)} differing rows, {int((count > 1).sum())} filled inputs')
            for i in rows[:4]:
                print(int(seq[i]), int(profile[i]), target[i, :3].astype(int).tolist(), actual[i].astype(int).tolist())
        del states
    print(f'Condition hits: {seen.tolist()}')
    print(f'Missed: {missing[:3].tolist()}; extra: {extra[:3].tolist()}')


if __name__ == '__main__':
    main()
