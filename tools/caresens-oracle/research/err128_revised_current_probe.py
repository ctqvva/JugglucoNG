"""Probe and verify the err128 revised-current stage against the pinned reference.

Uses controlled black-box state perturbations on key sweep runs to establish:
- activation gate (accepted record count and pre-record diabetes profile)
- onset conditions (12-record sequence continuity, 11-record <=120 mg/dL ceiling,
  9-of-10 prior stability intervals strictly below 0.8 mg/dL/min, and current
  drop rate strictly below -2.5 mg/dL/min)
- revised-current calculation (holds err128_revised_value_prev while active;
  passes tran_inA_5min otherwise)
- persistence and recovery (36-record flag history slot 4, |dseq| <= 3,
  recovery at rounded glucose >= 80 or >= err128_normal_prev)
- rejection invariance (data_type == 2 leaves err128 state unchanged)

Also validates the autonomous rule directly against vendor snapshots on
fixtures, the seed-11 decay corpus, and randomised fresh/takeover sweeps.
"""
import argparse
import itertools
import math

from err16lib import decay_specs
from err16_replay import sweep_case, sweep_cases
from err16_state_probe import Replay
from sc import REF, co, mf, np, scenarios


SOURCE = 'err128_CGM_c_noise_revised_value'


def run_spec(spec):
    device = co.default_device()
    for field in ('slope100', 'vref', 'eapp'):
        device[field] = spec.get(field, scenarios.DEFAULTS[field])
    inputs = co.records(*scenarios.generate(**spec))
    count, output, debug, _, states = REF.run(device, inputs, snapshots=True)
    return device, inputs, count, output[:, 0], debug[:, 0], states


def step_revised_current(idx, profile, accu_seq, err_glu, time_std, flag_prev, normal_prev, rev_prev,
                         cur_seq, cur_time, cur_glu, cur_5min):
    """Advance one accepted record of the err128 revised-current state machine."""
    if idx < 36 or profile > 2:
        return 0, math.nan, cur_5min
    if flag_prev[-1] == 1:
        if (flag_prev[4] == 0
                and abs(cur_seq - accu_seq[-1]) <= 3
                and not (cur_glu >= 80.0 or cur_glu >= normal_prev)):
            return 1, normal_prev, rev_prev
        return 0, math.nan, cur_5min
    seq_ok = accu_seq[-11] != 0 and 1 <= (cur_seq - accu_seq[-11]) <= 11
    ceil_ok = all(not (err_glu[k] > 120.0) for k in range(1, 12))
    stable = 0
    for k in range(1, 11):
        dt_k = (time_std[k + 1] - time_std[k]) & 0xffffffff
        if dt_k != 0:
            rate_k = (err_glu[k + 1] - err_glu[k]) / (dt_k / 60.0)
            if abs(rate_k) < 0.8:
                stable += 1
    dt_cur = (cur_time - time_std[-1]) & 0xffffffff
    drop_ok = dt_cur != 0 and (cur_glu - err_glu[-1]) / (dt_cur / 60.0) < -2.5
    if seq_ok and ceil_ok and stable >= 9 and drop_ok:
        return 1, err_glu[-1], rev_prev
    return 0, math.nan, cur_5min


def verify_run(name, spec, start=None):
    _, inputs, count, out, dbg, states = run_spec(spec)
    begin = start or 0
    fill = np.flatnonzero(count[begin:] > 1)
    end = len(inputs) if not len(fill) else begin + int(fill[0])

    if start is not None:
        st0 = states[start - 1]
        idx = int(st0['idx'])
        accu_seq = [int(x) for x in st0['accu_seq'][-12:]]
        err_glu = [float(x) for x in st0['err_glu_arr'][-12:]]
        time_std = [int(x) for x in st0['time_standard_arr'][-12:]]
        flag_prev = [int(x) for x in st0['err128_flag_prev']]
        normal_prev = float(st0['err128_normal_prev'])
        rev_prev = float(st0['err128_revised_value_prev'])
        profile = int(st0['diabetes_before_7days_flag'])
    else:
        idx = 0
        accu_seq = [0] * 12
        err_glu = [math.nan] * 12
        time_std = [0] * 12
        flag_prev = [0] * 40
        normal_prev = math.nan
        rev_prev = math.nan
        profile = 100

    accepted_count = 0
    active_count = 0
    mismatches = 0
    first_diff = None

    for i in range(begin, end):
        accepted = (count[i] != 0) and (out[i]['data_type'] != 2) and ((out[i]['errcode'] & 64) == 0)
        if not accepted:
            continue
        accepted_count += 1
        cur_seq = int(inputs[i]['seq_number'])
        cur_time = int(inputs[i]['measurement_time_standard'])
        cur_glu = float(states[i]['err_glu_arr'][-1])
        cur_5min = float(dbg[i]['tran_inA_5min'])
        flag, normal, revised = step_revised_current(
            idx, profile, accu_seq, err_glu, time_std, flag_prev, normal_prev, rev_prev,
            cur_seq, cur_time, cur_glu, cur_5min,
        )
        idx += 1
        accu_seq = accu_seq[1:] + [cur_seq]
        err_glu = err_glu[1:] + [cur_glu]
        time_std = time_std[1:] + [cur_time]
        flag_prev = flag_prev[1:] + [flag]
        normal_prev = normal
        rev_prev = revised
        profile = int(states[i]['diabetes_before_7days_flag'])

        vf = int(dbg[i]['err128_flag'])
        vr = float(dbg[i]['err128_revised_value'])
        vn = float(dbg[i]['err128_normal'])
        va = float(states[i][SOURCE][-1])
        if vf:
            active_count += 1
        ok = (
            flag == vf
            and np.isclose(revised, vr, rtol=0, atol=0, equal_nan=True)
            and np.isclose(normal, vn, rtol=0, atol=0, equal_nan=True)
            and np.isclose(revised, va, rtol=0, atol=0, equal_nan=True)
        )
        if not ok:
            mismatches += 1
            if first_diff is None:
                first_diff = (name, i + 1, cur_seq, flag, vf, normal, vn, revised, vr, va)
    return accepted_count, active_count, mismatches, first_diff


def run_perturbations():
    """Execute controlled black-box state perturbations establishing every boundary."""
    # 1. Fresh seed 101, run 61: onset (1600), persist on type-0 (1603), recover (1604),
    #    non-onset after unstable history (1609), 36-record timeout (1698),
    #    low-normal recovery at normal=73 (1758), post-day-7 profile gate (2096).
    spec61, _ = sweep_case(101, 61, False)
    dev61, inp61, _, out61, dbg61, st61 = run_spec(spec61)
    assert [int(dbg61[i]['err128_flag']) for i in range(99, 105)] == [0, 1, 1, 1, 1, 0]
    assert int(out61[103]['data_type']) == 0 and int(dbg61[103]['err128_flag']) == 1
    assert float(dbg61[100]['err128_normal']) == 89.0
    assert float(dbg61[100]['err128_revised_value']) == float(dbg61[99]['tran_inA_5min'])
    assert int(dbg61[109]['err128_flag']) == 0  # seq 1609: unstable prior 10 intervals
    assert int(dbg61[197]['err128_flag']) == 1 and int(dbg61[198]['err128_flag']) == 0  # 36-record timeout
    assert int(dbg61[257]['err128_flag']) == 1 and int(dbg61[258]['err128_flag']) == 0  # normal=73, 72->73
    assert int(dbg61[596]['err128_flag']) == 0 and int(st61[595]['diabetes_before_7days_flag']) > 2

    r100 = Replay(dev61, st61[99:100], inp61[100:101])
    r103 = Replay(dev61, st61[102:103], inp61[103:104])
    r104 = Replay(dev61, st61[103:104], inp61[104:105])
    r198 = Replay(dev61, st61[197:198], inp61[198:199])

    # Accepted record count gate: idx >= 36 on both onset and persistence.
    assert r100.step([('idx', Ellipsis, 35)])[1]['err128_flag'] == 0
    assert r100.step([('idx', Ellipsis, 36)])[1]['err128_flag'] == 1
    assert r103.step([('idx', Ellipsis, 35)])[1]['err128_flag'] == 0
    assert r103.step([('idx', Ellipsis, 36)])[1]['err128_flag'] == 1

    # Pre-record diabetes_before_7days_flag gate: {0, 1, 2} enabled, {3..6, 100} disabled.
    for v in (0, 1, 2):
        assert r100.step([('diabetes_before_7days_flag', Ellipsis, v)])[1]['err128_flag'] == 1
        assert r103.step([('diabetes_before_7days_flag', Ellipsis, v)])[1]['err128_flag'] == 1
    for v in (3, 4, 5, 6, 100):
        assert r100.step([('diabetes_before_7days_flag', Ellipsis, v)])[1]['err128_flag'] == 0
        assert r103.step([('diabetes_before_7days_flag', Ellipsis, v)])[1]['err128_flag'] == 0

    # Pre-record timing on seq 864 (100 -> 0) and seq 2017 (0 -> 6, 6 -> 1).
    inp_864 = inp61[100:101].copy()
    inp_864['seq_number'] = 864
    st_864 = st61[99:100].copy()
    st_864['accu_seq'][0, -36:] = np.arange(864 - 36, 864)
    st_864['seq_prev'][0] = 863
    st_864['diabetes_before_7days_flag'][0] = 100
    _, g864, s864 = Replay(dev61, st_864, inp_864).step()
    assert int(s864['diabetes_before_7days_flag']) == 0 and int(g864['err128_flag']) == 0

    inp_2017 = inp61[100:101].copy()
    inp_2017['seq_number'] = 2017
    st_2017 = st61[99:100].copy()
    st_2017['accu_seq'][0, -36:] = np.arange(2017 - 36, 2017)
    st_2017['seq_prev'][0] = 2016
    st_2017['diabetes_before_7days_flag'][0] = 0
    st_2017['diabetes_cnt_idx'][0] = 300
    st_2017['diabetes_cnt_hi_glu'][0] = 200
    st_2017['diabetes_mean_x'][0] = 200.0
    st_2017['diabetes_M2'][0] = 100000.0
    _, g2017, s2017 = Replay(dev61, st_2017, inp_2017).step()
    assert int(s2017['diabetes_before_7days_flag']) == 6 and int(g2017['err128_flag']) == 1

    # Onset reads err128_revised_value_prev directly (not curr_avg_arr[-1]).
    _, g_src, _ = r100.step([('curr_avg_arr', 864, 9.999), ('err128_revised_value_prev', Ellipsis, 7.777)])
    assert int(g_src['err128_flag']) == 1 and float(g_src['err128_revised_value']) == 7.777

    # Clean 300s-spaced state for exact onset threshold checks.
    t0 = int(inp61[100]['measurement_time_standard'])
    st_clean = st61[99:100].copy()
    for k in range(288):
        st_clean['time_standard_arr'][0, k] = t0 - 300 * (288 - k)
    st_clean['err_glu_arr'][0, -15:] = 100.0
    rc = Replay(dev61, st_clean, inp61[100:101])

    # Sequence continuity: accu_seq[-11] != 0 and 1 <= cur_seq - accu_seq[-11] <= 11.
    cur_seq = int(inp61[100]['seq_number'])
    for diff, want in ((0, 0), (1, 1), (11, 1), (12, 0)):
        assert rc.step([('accu_seq', 865 - 11, cur_seq - diff)])[1]['err128_flag'] == want

    # Ceiling: all 11 prior rounded glucose values <= 120.0.
    assert rc.step([('err_glu_arr', slice(-11, None), 120.0)])[1]['err128_flag'] == 1
    assert rc.step([('err_glu_arr', slice(-11, None), 120.001)])[1]['err128_flag'] == 0

    # Stability: at least 9 of the 10 prior intervals have |dg / (dt / 60)| < 0.8 (strict).
    assert rc.step([('err_glu_arr', slice(-11, None), np.array([90.0] * 6 + [100.0] * 5))])[1]['err128_flag'] == 1
    assert rc.step([('err_glu_arr', slice(-11, None), np.array([80.0] * 4 + [90.0] * 4 + [100.0] * 3))])[1]['err128_flag'] == 0
    assert rc.step([('err_glu_arr', slice(-11, None), np.array([92.002] * 4 + [96.001] * 4 + [100.0] * 3))])[1]['err128_flag'] == 1
    assert rc.step([('err_glu_arr', slice(-11, None), np.array([92.0] * 4 + [96.0] * 4 + [100.0] * 3))])[1]['err128_flag'] == 0
    assert rc.step([('err_glu_arr', slice(-11, None), np.array([100.0] * 4 + [96.001] * 4 + [92.002] * 3))])[1]['err128_flag'] == 1
    assert rc.step([('err_glu_arr', slice(-11, None), np.array([100.0] * 4 + [96.0] * 4 + [92.0] * 3))])[1]['err128_flag'] == 0

    # Drop rate: (cur_glu - prev_glu) / (dt / 60) < -2.5 (strict), tested at exact -2.5 (dt=240s, dg=-10).
    st_240 = st_clean.copy()
    st_240['time_standard_arr'][0, 287] = t0 - 240
    r240 = Replay(dev61, st_240, inp61[100:101])
    o0, _, _ = r240.step([('cal_result_in_smooth_ycept', Ellipsis, 0.0)])
    o1, _, _ = r240.step([('cal_result_in_smooth_ycept', Ellipsis, 30.0)])
    slope_y = (o1['result_glucose'] - o0['result_glucose']) / 30.0
    for target, want in ((89.0, 1), (90.0, 0)):
        y = (target - o0['result_glucose']) / slope_y
        _, g, s = r240.step([('cal_result_in_smooth_ycept', Ellipsis, y)])
        assert float(s['err_glu_arr'][-1]) == target and int(g['err128_flag']) == want

    # Persistence: slot 4 (36 accepted records ago), |cur_seq - prev_seq| <= 3, recovery >= min(80, normal).
    assert r198.step([('err128_flag_prev', 4, 0)])[1]['err128_flag'] == 1
    assert r198.step([('err128_flag_prev', 5, 0)])[1]['err128_flag'] == 0
    st_only4 = st61[102:103].copy()
    st_only4['err128_flag_prev'][0, :] = 0
    st_only4['err128_flag_prev'][0, 39] = 1
    st_only4['err128_flag_prev'][0, 4] = 1
    assert Replay(dev61, st_only4, inp61[103:104]).step()[1]['err128_flag'] == 0
    st_no4 = st61[102:103].copy()
    st_no4['err128_flag_prev'][0, :] = 1
    st_no4['err128_flag_prev'][0, 4] = 0
    assert Replay(dev61, st_no4, inp61[103:104]).step()[1]['err128_flag'] == 1

    seq103 = int(inp61[103]['seq_number'])
    for diff, want in ((-4, 0), (-3, 1), (0, 1), (3, 1), (4, 0)):
        assert r103.step([('accu_seq', 864, seq103 - diff)])[1]['err128_flag'] == want

    o0, _, _ = r104.step([('cal_result_in_smooth_ycept', Ellipsis, 0.0)])
    o1, _, _ = r104.step([('cal_result_in_smooth_ycept', Ellipsis, 30.0)])
    slope_r = (o1['result_glucose'] - o0['result_glucose']) / 30.0
    for norm_v, target, want in ((73.0, 72.0, 1), (73.0, 73.0, 0), (80.0, 79.0, 1), (80.0, 80.0, 0),
                                 (100.0, 79.0, 1), (100.0, 80.0, 0), (math.nan, 79.0, 1), (math.nan, 80.0, 0)):
        y = (target - o0['result_glucose']) / slope_r
        _, g, s = r104.step([('err128_normal_prev', Ellipsis, norm_v), ('cal_result_in_smooth_ycept', Ellipsis, y)])
        assert float(s['err_glu_arr'][-1]) == target and int(g['err128_flag']) == want
    assert r103.step([('cal_result_in_smooth_ycept', Ellipsis, math.nan)])[1]['err128_flag'] == 1

    # 2. Fresh seed 202, run 127: onset (seq 1009), rejection invariance during active revision (seq 2376),
    #    non-onset due to sequence gap (seq 2617).
    spec127, _ = sweep_case(202, 127, False)
    _, _, _, out127, dbg127, st127 = run_spec(spec127)
    assert int(dbg127[979]['err128_flag']) == 1 and float(dbg127[979]['err128_normal']) == 105.0
    assert int(out127[2346]['data_type']) == 2
    for field in ('err128_flag_prev', 'err128_normal_prev', 'err128_revised_value_prev', SOURCE):
        assert np.array_equal(st127[2345][field], st127[2346][field], equal_nan=True)
    assert int(dbg127[2587]['err128_flag']) == 0  # seq 2617: gap at seq 2610 breaks 12-seq continuity

    # 3. Takeover seed 7 run 12 (type-0 onset at seq 4037) and seed 8 run 72 (onset at seq 4188).
    for seed, idx, row, want_type in ((7, 12, 4036, 0), (8, 72, 4187, 4)):
        spec, start = sweep_case(seed, idx, True)
        _, _, _, out_t, dbg_t, _ = run_spec(spec)
        assert int(out_t[row]['data_type']) == want_type and int(dbg_t[row]['err128_flag']) == 1
        _, _, mismatches, first_diff = verify_run(f'takeover {seed}/{idx}', spec, start)
        assert mismatches == 0, first_diff

    print('All controlled black-box perturbation assertions passed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--perturbations', action='store_true', help='run black-box state perturbation assertions')
    parser.add_argument('--fixtures', action='store_true', help='verify all development and holdout fixtures')
    parser.add_argument('--decay', type=int, metavar='RUNS', help='verify the first N seed-11 decay runs')
    parser.add_argument('--sweep', type=int, metavar='SEED', help='verify a randomised sweep seed')
    parser.add_argument('--runs', type=int, default=300)
    parser.add_argument('--import-state', action='store_true')
    args = parser.parse_args()
    if REF.sha256 not in co.KNOWN_REFERENCE:
        parser.error(f'unexpected reference SHA-256: {REF.sha256}')

    if not any((args.perturbations, args.fixtures, args.decay, args.sweep is not None)):
        args.perturbations = True
        args.fixtures = True

    if args.perturbations:
        run_perturbations()

    cases = []
    if args.fixtures:
        specs = mf.DEVELOPMENT | mf.HOLDOUT
        cases.extend((name, specs[name], None) for name in specs)
    if args.decay:
        cases.extend((f'decay {i}', spec, None) for i, spec in enumerate(itertools.islice(decay_specs(), args.decay)))
    if args.sweep is not None:
        cases.extend(itertools.islice(sweep_cases(args.sweep, args.import_state), args.runs))

    if cases:
        total_accepted = total_active = total_mismatches = bad_runs = 0
        for name, spec, start in cases:
            acc, active, mis, diff = verify_run(name, spec, start)
            total_accepted += acc
            total_active += active
            total_mismatches += mis
            if mis:
                bad_runs += 1
                print(f'MISMATCH {name}: {diff}')
        print(f'{len(cases)} runs, {total_accepted} accepted records, '
              f'{total_active} active err128 records, {total_mismatches} mismatches ({bad_runs} runs)')
        return int(bool(total_mismatches))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
