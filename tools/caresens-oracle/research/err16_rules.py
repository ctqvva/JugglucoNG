"""Compare candidate error-16 history rules with empty-list oracle outputs."""
import argparse
import itertools

from err16lib import decay_specs
from err16_replay import first, sweep_cases
from err16_state_probe import EARLY_PROFILE, PREFIX
from sc import REF, co, mf, np, runspec


LONG_SLOPE_HALF_STEP = 5e-11


def predict(states, profile):
    field = lambda name: states[PREFIX + name]
    lo = lambda name: field(name)[:, -24:].min(axis=1)
    hi = lambda name: field(name)[:, -24:].max(axis=1)

    def drop(name, threshold, suffix=''):
        return (hi(name + '_diff' + suffix) <= -threshold) & (hi(name + '_ratio' + suffix) <= -threshold)

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
    sequence = states['accu_seq'].astype(int)
    consecutive = sequence[:, -1] - sequence[:, -24] == 23

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
    early = (sequence[:, -1] >= 1728) & ~ready & drop('trend_mean', 70, '_early')
    return np.column_stack([condition0 | early, condition1, condition2]) & consecutive[:, None]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=160, help='number of existing seed-11 decay scenarios')
    parser.add_argument('--fixtures', action='store_true', help='also compare the committed fixture specs')
    parser.add_argument('--sweep', type=int, metavar='SEED', help='use the mixed sweep instead of the decay corpus')
    parser.add_argument('--import-state', action='store_true', help='compare only the takeover suffix of mixed runs')
    args = parser.parse_args()
    if REF.sha256 not in co.KNOWN_REFERENCE:
        parser.error(f'unexpected reference SHA-256: {REF.sha256}')
    if args.import_state and args.sweep is None:
        parser.error('--import-state requires --sweep')

    fixtures = (mf.DEVELOPMENT | mf.HOLDOUT).items() if args.fixtures else ()
    if args.sweep is None:
        cases = ((name, spec, None) for name, spec in itertools.chain(
            enumerate(itertools.islice(decay_specs(), args.runs)), fixtures))
    else:
        cases = itertools.islice(sweep_cases(args.sweep, args.import_state), args.runs)
    seen, missing, extra = np.zeros((3, 7), dtype=int)
    for name, spec, start in cases:
        seq, _, _, _, count, output, debug, states, _ = runspec(spec, True)
        profile = np.r_[100, states[EARLY_PROFILE][:-1]]
        target = debug['err16_condi'].astype(bool)
        actual = predict(states, profile)
        actual &= ((count != 0) & (output['data_type'] != 2) & ((output['errcode'] & 64) == 0))[:, None]
        if args.sweep is not None:
            begin = start or 0
            fill = first(count[begin:] > 1)
            band = slice(begin, len(count) if fill is None else begin + fill)
            seq, profile, target, actual = seq[band], profile[band], target[band], actual[band]
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
    return int(bool(missing.any() or extra.any()))


if __name__ == '__main__':
    raise SystemExit(main())
