"""Probe and verify the CareSens Air low-signal delay tests (Task 05) against the pinned reference.

Uses controlled black-box state/input perturbations to establish:
- D0-D4 rules and exact phase transitions controlled by seq and diabetes_before_7days_flag (b7):
    Phase 1 (seq < 865):                              D0, D3
    Phase 2 (seq >= 865 && !(seq >= 2017 && b7 >= 4)): D0, D1, D2, D3
    Phase 3 (seq >= 2017 && b7 >= 4):                 D3, D4
- err4_delay_condi -> err4_delay_flag gate for seq >= 865:
    - idx == 0 or prev err4_delay_flag == 1: passes directly
    - else if count(cur_seq - accu_seq <= 23) >= 21: requires a single-step drop
      g[k-1] - g[k] >= 40 with g[k] < 100 in the window (ending at roundAwayFromZero(R))
    - else (count < 21): requires D3 && (err_glu_arr[-1] - roundAwayFromZero(R) > 50)
- data_type == 4 == err4_delay_flag || err1_random_noise_temp_break (Task 02 isolated by injection)
- err_delay_arr[7] FIFO trend withholding (any of the 7 newest accepted records == data_type 4)
- sensitivity-reference (sum_curr_for_mdc / sum_curr_cnt_for_mdc) inclusion band:
    0.5 <= corrected_re_current <= 50.0 (independent of data_type == 4, slope100, lot_type)

Also validates all autonomous rules directly against the pinned vendor library on fixtures,
the seed-11 decay corpus, and randomised fresh/takeover sweeps.
"""
import argparse
import itertools
import math

from err16lib import decay_specs
from err16_replay import sweep_case, sweep_cases
from err16_state_probe import Replay
from sc import REF, co, mf, np, scenarios
import differential as df


FIR_WEIGHTS = (
    0.19203786935092992, 0.16522460111444, 0.11844988518211, 0.0716100935714,
    0.03473077453465, 0.01186996852659, 0.00419148479069, 0.01186996852659,
    0.03473077453465, 0.0716100935714, 0.11844988518211, 0.16522460111444,
)


def round_away(x):
    return math.floor(x + 0.5) if x >= 0.0 else math.ceil(x - 0.5)


def make_device(spec):
    device = co.default_device()
    for field in ('slope100', 'vref', 'eapp'):
        device[field] = spec.get(field, scenarios.DEFAULTS[field])
    return device


def run_spec(spec, snapshots=True):
    device = make_device(spec)
    inputs = co.records(*scenarios.generate(**spec))
    count, output, debug, final_state, states = REF.run(device, inputs, snapshots=snapshots)
    return device, inputs, count, output[:, 0], debug[:, 0], final_state, states


def diabetes_level(dcnt, dmean, dm2, dhigh, dlow):
    if dcnt <= 288:
        return 6
    above = dhigh / dcnt * 100.0
    below = dlow / dcnt * 100.0
    cv = math.sqrt(dm2 / (dcnt - 1)) / dmean * 100.0
    if below > 5.0 or cv > 36.0:
        return 6
    if above > 60.0:
        return 5
    if above > 30.0:
        return 4
    if above >= 15.0 or cv > 30.0:
        return 3
    if above < 5.0 and cv <= 20.0:
        return 1
    return 2


def eval_delay_step(seq, idx, slope100, b7, rawmax, prev_rawmax, consecutive, rR, t5,
                    accu_seq_24, err_glu_24, prev_f4d):
    phase1 = seq < 865
    phase3 = seq >= 2017 and b7 >= 4
    phase2 = not phase1 and not phase3

    d0 = 1 if ((not phase3) and consecutive and max(rawmax, prev_rawmax) < 1.1) else 0
    d1 = 1 if (phase2 and rR < 40.0 and rawmax < 1.5) else 0
    d2 = 1 if (phase2 and rR < 40.0 and rawmax < 3.0 and rawmax * 100.0 / slope100 < 60.0) else 0
    d3 = 1 if (rR < 20.0 or t5 < 0.4) else 0
    d4 = 1 if (phase3 and (rR < 30.0 or (rawmax < 0.8 and rawmax * 100.0 / slope100 < 20.0))) else 0
    condi = (d0, d1, d2, d3, d4)

    if not any(condi):
        f4d = 0
    elif phase1 or idx == 0 or prev_f4d == 1:
        f4d = 1
    else:
        n_win = 0
        for s_val in accu_seq_24:
            if s_val > 0 and (seq - s_val) <= 23:
                n_win += 1
        if n_win >= 21:
            tail = err_glu_24[len(err_glu_24) - n_win:] + [rR]
            ok_drop = False
            for k in range(1, len(tail)):
                if (tail[k - 1] - tail[k]) >= 40.0 and tail[k] < 100.0:
                    ok_drop = True
                    break
            f4d = 1 if ok_drop else 0
        else:
            f4d = 1 if (d3 == 1 and (err_glu_24[-1] - rR) > 50.0) else 0
    return condi, f4d


def verify_run(name, spec, start=None, per_record_snapshots=False):
    dev = make_device(spec)
    slope100 = float(dev['slope100'].item())
    inputs = co.records(*scenarios.generate(**spec))

    if start is not None:
        _, _, _, st0_arr, _ = REF.run(dev, inputs[:start], snapshots=False)
        st0 = st0_arr[0]
        count_b, out_b, dbg_b, final_arr, states_b = REF.run(
            dev, inputs[start:], state=st0_arr.copy(), snapshots=per_record_snapshots
        )
        band_inputs = inputs[start:]
        out = out_b[:, 0]
        dbg = dbg_b[:, 0]
        count = count_b
        states = states_b
        begin_offset = start
    else:
        st0 = None
        count, out_b, dbg_b, final_arr, states = REF.run(dev, inputs, snapshots=per_record_snapshots)
        band_inputs = inputs
        out = out_b[:, 0]
        dbg = dbg_b[:, 0]
        begin_offset = 0

    fill = np.flatnonzero(count > 1)
    end = len(band_inputs) if not len(fill) else int(fill[0])
    if len(fill) and not per_record_snapshots:
        # Re-run truncated prefix to obtain exact final state right before filling starts
        init_st = st0.reshape(1).copy() if st0 is not None else None
        _, _, _, final_arr, _ = REF.run(dev, band_inputs[:end], state=init_st, snapshots=False)
    final_st = final_arr[0]

    if st0 is not None:
        idx = int(st0['idx'])
        accu_seq_24 = [int(x) for x in st0['accu_seq'][-24:]]
        err_glu_24 = [float(x) for x in st0['err_glu_arr'][-24:]]
        plasma = [float(x) for x in st0['err16_CGM_plasma'][-12:]]
        prev_f4d = int(st0['err4_delay_flag_arr'][575])
        prev_rawmax = float(np.max(st0['err4_inA'][360:390])) if idx > 0 else math.nan
        err_delay_arr = [int(x) for x in st0['err_delay_arr']]
        mdc_sum = float(st0['sum_curr_for_mdc'])
        mdc_cnt = int(st0['sum_curr_cnt_for_mdc'])
        dcnt = int(st0['diabetes_cnt_idx'])
        dmean = float(st0['diabetes_mean_x'])
        dm2 = float(st0['diabetes_M2'])
        dhigh = int(st0['diabetes_cnt_hi_glu'])
        dlow = int(st0['diabetes_cnt_low_glu'])
        dlevel = int(st0['diabetes_level_diabetes'])
        dlevel_done = int(st0['seq_prev']) >= 2017
        last_glu = float(st0['smooth_sig_in'][-1]) if idx > 0 else math.nan
        has_last_glu = idx > 0
        b7 = int(st0['diabetes_before_7days_flag'])
        b7_cnt = int(st0['diabetes_before_7days_n_valid'])
        b7_sum = float(st0['diabetes_before_7days_eAG'])
        b7_above = float(st0['diabetes_before_7days_TAR'])
        b7_in = float(st0['diabetes_before_7days_TIR'])
    else:
        idx = 0
        accu_seq_24 = [0] * 24
        err_glu_24 = [math.nan] * 24
        plasma = [math.nan] * 12
        prev_f4d = 0
        prev_rawmax = math.nan
        err_delay_arr = [0] * 7
        mdc_sum = 0.0
        mdc_cnt = 0
        dcnt = 0
        dmean = 0.0
        dm2 = 0.0
        dhigh = 0
        dlow = 0
        dlevel = 6
        dlevel_done = False
        last_glu = math.nan
        has_last_glu = False
        b7 = 100
        b7_cnt = 0
        b7_sum = 0.0
        b7_above = 0.0
        b7_in = 0.0

    mism = {k: 0 for k in ('condi', 'f4d', 'dt4', 'err_delay_arr', 'mdc_cnt', 'mdc_sum', 'b7')}
    first_mism = {}
    accepted = f4d_count = dt4_count = mdc_skip_count = 0

    # Pre-extract columns for fast Python iteration
    acc_mask = ((count[:end] != 0) & (out['data_type'][:end] != 2) & ((out['errcode'][:end] & 64) == 0)).tolist()
    seqs = band_inputs['seq_number'][:end].tolist()
    corrs = dbg['corrected_re_current'][:end].tolist()
    glus = out['result_glucose'][:end].tolist()
    rawmaxes = dbg['tran_inA'][:end].max(axis=1).tolist()
    t5s = dbg['tran_inA_5min'][:end].tolist()
    nbs = dbg['err1_random_noise_temp_break'][:end].tolist()
    v_condis = dbg['err4_delay_condi'][:end].tolist()
    v_f4ds = dbg['err4_delay_flag'][:end].tolist()
    v_dts = out['data_type'][:end].tolist()

    for i in range(end):
        if not acc_mask[i]:
            continue
        accepted += 1
        seq = int(seqs[i])
        corr = corrs[i]
        glu = glus[i]
        rR = float(round_away(glu))
        rawmax = rawmaxes[i]
        t5 = t5s[i]
        nb = nbs[i]
        consecutive = idx > 0 and accu_seq_24[-1] == seq - 1

        # Sensitivity reference & day-7 diabetes stats (seq 865..2016)
        if 865 <= seq <= 2016:
            if 0.5 <= corr <= 50.0:
                mdc_sum += corr
                mdc_cnt += 1
            else:
                mdc_skip_count += 1
            if has_last_glu and 40.0 <= last_glu <= 600.0:
                dcnt += 1
                delta = last_glu - dmean
                dmean += delta / dcnt
                dm2 += delta * (last_glu - dmean)
                if last_glu > 180.0:
                    dhigh += 1
                if last_glu < 70.0:
                    dlow += 1
        if seq >= 2017 and not dlevel_done:
            dlevel_done = True
            dlevel = diabetes_level(dcnt, dmean, dm2, dhigh, dlow)

        # 12-tap FIR smoothed glucose (plasma) and b7 update before D0..D4
        new_seq_24 = accu_seq_24[1:] + [seq]
        new_glu_24 = err_glu_24[1:] + [rR]
        cont12 = new_seq_24[-12] != 0 and (seq - new_seq_24[-12]) == 11
        if cont12:
            fir = (
                FIR_WEIGHTS[0] * new_glu_24[23] + FIR_WEIGHTS[1] * new_glu_24[22]
                + FIR_WEIGHTS[2] * new_glu_24[21] + FIR_WEIGHTS[3] * new_glu_24[20]
                + FIR_WEIGHTS[4] * new_glu_24[19] + FIR_WEIGHTS[5] * new_glu_24[18]
                + FIR_WEIGHTS[6] * new_glu_24[17] + FIR_WEIGHTS[7] * new_glu_24[16]
                + FIR_WEIGHTS[8] * new_glu_24[15] + FIR_WEIGHTS[9] * new_glu_24[14]
                + FIR_WEIGHTS[10] * new_glu_24[13] + FIR_WEIGHTS[11] * new_glu_24[12]
            )
            smoothed_glu = float(round_away(fir))
        else:
            smoothed_glu = math.nan
        plasma = plasma[1:] + [smoothed_glu]

        if seq >= 2017 and dcnt > 288:
            b7 = dlevel
        elif seq >= 864:
            if b7 == 100:
                b7 = 0
            if seq > 864 and b7 == 0:
                b7_cnt += 1
                b7_sum += smoothed_glu
                if smoothed_glu >= 180.0:
                    b7_above += 1.0
                if 70.0 <= smoothed_glu <= 180.0:
                    b7_in += 1.0
                if all(p >= 300.0 for p in plasma) or (b7_cnt >= 288 and (
                        b7_sum > 140.0 * b7_cnt or b7_above > 0.1 * b7_cnt or b7_in < 0.8 * b7_cnt)):
                    b7 = 6

        # Evaluate D0..D4 and err4_delay_flag
        condi, f4d = eval_delay_step(
            seq, idx, slope100, b7, rawmax, prev_rawmax, consecutive, rR, t5,
            accu_seq_24, err_glu_24, prev_f4d
        )
        v_condi = tuple(v_condis[i])
        v_f4d = v_f4ds[i]
        if condi != v_condi:
            mism['condi'] += 1
            first_mism.setdefault('condi', (begin_offset + i, seq, condi, v_condi, b7, round(glu, 2), round(rawmax, 4), round(t5, 4)))
        if f4d != v_f4d:
            mism['f4d'] += 1
            first_mism.setdefault('f4d', (begin_offset + i, seq, f4d, v_f4d, condi, v_condi, idx, prev_f4d, round(glu, 2)))
        if v_f4d:
            f4d_count += 1

        dt4 = 1 if (f4d == 1 or nb == 1) else 0
        v_dt4 = 1 if v_dts[i] == 4 else 0
        if dt4 != v_dt4:
            mism['dt4'] += 1
            first_mism.setdefault('dt4', (begin_offset + i, seq, dt4, v_dt4, f4d, nb))
        if v_dt4:
            dt4_count += 1

        err_delay_arr = err_delay_arr[1:] + [dt4]

        if per_record_snapshots:
            st_i = states[i]
            v_b7 = int(st_i['diabetes_before_7days_flag'])
            if b7 != v_b7:
                mism['b7'] += 1
                first_mism.setdefault('b7', (begin_offset + i, seq, b7, v_b7, dcnt, b7_cnt))
            v_eda = [int(x) for x in st_i['err_delay_arr']]
            if err_delay_arr != v_eda:
                mism['err_delay_arr'] += 1
                first_mism.setdefault('err_delay_arr', (begin_offset + i, seq, err_delay_arr, v_eda))
            v_mdc_cnt = int(st_i['sum_curr_cnt_for_mdc'])
            v_mdc_sum = float(st_i['sum_curr_for_mdc'])
            if mdc_cnt != v_mdc_cnt:
                mism['mdc_cnt'] += 1
                first_mism.setdefault('mdc_cnt', (begin_offset + i, seq, mdc_cnt, v_mdc_cnt, round(corr, 6)))
            if abs(mdc_sum - v_mdc_sum) > 1e-9:
                mism['mdc_sum'] += 1
                first_mism.setdefault('mdc_sum', (begin_offset + i, seq, mdc_sum, v_mdc_sum, abs(mdc_sum - v_mdc_sum)))

        # Advance state
        accu_seq_24 = new_seq_24
        err_glu_24 = new_glu_24
        prev_f4d = f4d
        prev_rawmax = rawmax
        last_glu = glu
        has_last_glu = True
        idx += 1

    # Always verify final state fields even when per_record_snapshots is False
    if not per_record_snapshots and end > 0:
        v_b7 = int(final_st['diabetes_before_7days_flag'])
        if b7 != v_b7:
            mism['b7'] += 1
            first_mism.setdefault('b7', ('final', b7, v_b7, dcnt, b7_cnt))
        v_eda = [int(x) for x in final_st['err_delay_arr']]
        if err_delay_arr != v_eda:
            mism['err_delay_arr'] += 1
            first_mism.setdefault('err_delay_arr', ('final', err_delay_arr, v_eda))
        v_mdc_cnt = int(final_st['sum_curr_cnt_for_mdc'])
        v_mdc_sum = float(final_st['sum_curr_for_mdc'])
        if mdc_cnt != v_mdc_cnt:
            mism['mdc_cnt'] += 1
            first_mism.setdefault('mdc_cnt', ('final', mdc_cnt, v_mdc_cnt))
        if abs(mdc_sum - v_mdc_sum) > 1e-9:
            mism['mdc_sum'] += 1
            first_mism.setdefault('mdc_sum', ('final', mdc_sum, v_mdc_sum, abs(mdc_sum - v_mdc_sum)))

    return accepted, f4d_count, dt4_count, mdc_skip_count, mism, first_mism


def run_perturbations():
    """Controlled state/input perturbations isolating every rule and boundary."""
    # 1. Phase transitions & b7 control on holdout-dropouts-late row 1469 (seq=2022)
    dev_dl, inp_dl, _, out_dl, _, _, st_dl = run_spec(mf.HOLDOUT['holdout-dropouts-late'], snapshots=True)
    rep_dl = Replay(dev_dl, st_dl[1468:1469], inp_dl[1469:1470])
    for b7_in in (0, 1, 2, 3):
        _, g, _ = rep_dl.step([('diabetes_cnt_idx', Ellipsis, 0), ('diabetes_before_7days_n_valid', Ellipsis, 0),
                               ('diabetes_before_7days_flag', Ellipsis, b7_in), ('err16_CGM_plasma', Ellipsis, 100.0)])
        assert g['err4_delay_condi'].tolist() == [1, 1, 1, 1, 0], f'b7={b7_in} expected Phase 2, got {g["err4_delay_condi"]}'
    for b7_in in (4, 5, 6):
        _, g, _ = rep_dl.step([('diabetes_cnt_idx', Ellipsis, 0), ('diabetes_before_7days_n_valid', Ellipsis, 0),
                               ('diabetes_before_7days_flag', Ellipsis, b7_in), ('err16_CGM_plasma', Ellipsis, 100.0)])
        assert g['err4_delay_condi'].tolist() == [0, 0, 0, 1, 1], f'b7={b7_in} expected Phase 3, got {g["err4_delay_condi"]}'
    _, g_upd, s_upd = rep_dl.step([('diabetes_cnt_idx', Ellipsis, 0), ('diabetes_before_7days_n_valid', Ellipsis, 287),
                                   ('diabetes_before_7days_eAG', Ellipsis, 50000.0), ('diabetes_before_7days_flag', Ellipsis, 0),
                                   ('err16_CGM_plasma', Ellipsis, 100.0)])
    assert int(s_upd['diabetes_before_7days_flag']) == 6 and g_upd['err4_delay_condi'].tolist() == [0, 0, 0, 1, 1]
    print('  [ok] phase transitions & b7 >= 4 gate (seq=2022, b7 in 0..6, pre-D update)')

    # 2. Drop gate (>=21 in <=23 -> diff>=40 & dest<100; <21 -> D3 & prev-rR>50; seq<865 bypass)
    dev30, inp30, _, _, _, _, st30 = run_spec(sweep_case(101, 30, False)[0], snapshots=True)
    dev58, inp58, _, _, _, _, st58 = run_spec(sweep_case(101, 58, False)[0], snapshots=True)
    dev51, inp51, _, _, _, _, st51 = run_spec(sweep_case(101, 51, False)[0], snapshots=True)
    rep58 = Replay(dev58, st58[1246:1247], inp58[1247:1248])
    assert rep58.step([('err_glu_arr', Ellipsis, 39.0), ('err_glu_arr', 287, 78.99)])[1]['err4_delay_flag'] == 0
    assert rep58.step([('err_glu_arr', Ellipsis, 39.0), ('err_glu_arr', 287, 79.00)])[1]['err4_delay_flag'] == 1
    assert rep58.step([('err4_delay_flag_arr', 575, 1)])[1]['err4_delay_flag'] == 1
    rep51 = Replay(dev51, st51[2825:2826], inp51[2826:2827])
    o0, _, _ = rep51.step([('cal_result_in_smooth_ycept', Ellipsis, 0.0)])
    o1, _, _ = rep51.step([('cal_result_in_smooth_ycept', Ellipsis, 30.0)])
    sy51 = (o1['result_glucose'] - o0['result_glucose']) / 30.0
    assert rep51.step([('cal_result_in_smooth_ycept', Ellipsis, (99.49 - o0['result_glucose']) / sy51),
                       ('err_glu_arr', Ellipsis, 200.0)])[1]['err4_delay_flag'] == 1
    assert rep51.step([('cal_result_in_smooth_ycept', Ellipsis, (99.51 - o0['result_glucose']) / sy51),
                       ('err_glu_arr', Ellipsis, 200.0)])[1]['err4_delay_flag'] == 0
    rep57 = Replay(dev30, st30[1256:1257], inp30[1257:1258])
    assert rep57.step([('err_glu_arr', 287, 34.00)])[1]['err4_delay_flag'] == 0
    assert rep57.step([('err_glu_arr', 287, 34.01)])[1]['err4_delay_flag'] == 1
    assert rep57.step([('idx', Ellipsis, 0)])[1]['err4_delay_flag'] == 1
    for sq, exp_f4d in ((864, 1), (865, 0)):
        inp_m = inp30[1257:1258].copy(); inp_m['seq_number'] = sq
        st_m = st30[1256:1257].copy()
        st_m['accu_seq'][0] = np.arange(sq - 865, sq, dtype=np.int32)
        st_m['seq_prev'][0] = sq - 1
        st_m['err_glu_arr'][0, :] = -16.0
        st_m['diabetes_cnt_idx'][0] = 0
        assert Replay(dev30, st_m, inp_m).step()[1]['err4_delay_flag'] == exp_f4d
    print('  [ok] err4_delay_flag drop gate (>=21/<=23, >=40 & <100, <21 D3 >50 fallback, seq<865 & prev_f4d bypass)')

    # 3. Sensitivity reference [0.5, 50.0] band across slopes & lots
    dev202, inp202, _, _, _, _, st202 = run_spec(sweep_case(202, 127, False)[0], snapshots=True)
    r_mdc = 979
    prev_cnt = int(st202[r_mdc - 1]['sum_curr_cnt_for_mdc'])
    for adc, exp_inc in ((2513, 0), (2514, 1)):
        inp_m = inp202[r_mdc:r_mdc + 1].copy(); inp_m['workout'][0, :] = adc
        _, g, s = Replay(dev202, st202[r_mdc - 1:r_mdc], inp_m).step()
        assert int(s['sum_curr_cnt_for_mdc']) - prev_cnt == exp_inc
    for sl in (2.5, 5.105, 8.0):
        dev_m = dev202.copy(); dev_m['slope100'] = sl
        for adc in (18995, 18996):
            inp_m = inp202[r_mdc:r_mdc + 1].copy(); inp_m['workout'][0, :] = adc
            _, g, s = Replay(dev_m, st202[r_mdc - 1:r_mdc], inp_m).step()
            assert (0.5 <= float(g['corrected_re_current']) <= 50.0) == (int(s['sum_curr_cnt_for_mdc']) > prev_cnt)
    print('  [ok] sum_curr_for_mdc band 0.5 <= corrected_re_current <= 50.0 (slope-independent)')

    # 4. Trend withholding via err_delay_arr[7]
    valid_tr = np.flatnonzero(out_dl['trendrate'] != 100.0)
    rt = int(valid_tr[50])
    rep_t = Replay(dev_dl, st_dl[rt - 1:rt], inp_dl[rt:rt + 1])
    assert rep_t.step([('err_delay_arr', 0, 1)])[0]['trendrate'] != 100.0
    for k in range(1, 7):
        assert rep_t.step([('err_delay_arr', k, 1)])[0]['trendrate'] == 100.0
    print('  [ok] err_delay_arr[7] FIFO trend withholding (old slots 1..6 + current)')


def verify_suite(label, cases, per_record_snapshots=False):
    tot_acc = tot_f4d = tot_dt4 = tot_skip = 0
    tot_mism = {k: 0 for k in ('condi', 'f4d', 'dt4', 'err_delay_arr', 'mdc_cnt', 'mdc_sum', 'b7')}
    bad = []
    for name, spec, start in cases:
        acc, f4d_c, dt4_c, skip_c, mism, first_mism = verify_run(name, spec, start, per_record_snapshots)
        tot_acc += acc
        tot_f4d += f4d_c
        tot_dt4 += dt4_c
        tot_skip += skip_c
        for k, v in mism.items():
            tot_mism[k] += v
        if any(mism.values()):
            bad.append((name, {k: v for k, v in mism.items() if v}, first_mism))
    print(f'{label}: {len(cases)} runs, {tot_acc} accepted, {tot_f4d} f4d, {tot_dt4} dt4, {tot_skip} mdc_skip | mismatches={tot_mism}', flush=True)
    for row in bad[:15]:
        print('  MISMATCH:', row, flush=True)
    return sum(tot_mism.values())


def measure_glucose_impact(label, cases, jar):
    """Quantify post-day-7 (seq > 2016) glucose differences between the Kotlin engine and vendor."""
    total_runs = len(cases)
    affected_runs = 0
    total_post7 = 0
    affected_recs = 0
    all_diffs = []
    worst_runs = []
    run_202_127 = None

    for name, spec, start in cases:
        dev = make_device(spec)
        params = tuple(spec.get(f, scenarios.DEFAULTS[f]) for f in ('slope100', 'vref', 'eapp'))
        inputs = co.records(*scenarios.generate(**spec))
        if start is not None:
            _, _, _, st0_arr, _ = REF.run(dev, inputs[:start], snapshots=False)
            count, out_b, dbg_b, _, _ = REF.run(dev, inputs[start:], state=st0_arr.copy(), snapshots=False)
            band = inputs[start:]
            k = df.run_engine(jar, params, band, st0_arr)
        else:
            count, out_b, dbg_b, _, _ = REF.run(dev, inputs, snapshots=False)
            band = inputs
            k = df.run_engine(jar, params, band, None)
        out = out_b[:, 0]
        dbg = dbg_b[:, 0]
        fill = np.flatnonzero(count > 1)
        end = len(band) if not len(fill) else int(fill[0])
        usable = (count[:end] != 0) & (out['data_type'][:end] != 2) & ((out['errcode'][:end] & 64) == 0)
        post7 = usable & (band['seq_number'][:end] > 2016)
        n_post7 = int(post7.sum())
        total_post7 += n_post7
        if n_post7 == 0:
            continue
        signed = k['glucose'][:end][post7] - out['result_glucose'][:end][post7]
        abs_diff = np.abs(signed)
        bad = abs_diff > 1e-7
        n_bad = int(bad.sum())
        if n_bad:
            affected_runs += 1
            affected_recs += n_bad
            all_diffs.append(signed[bad])
            seqs_bad = band['seq_number'][:end][post7][bad]
            worst_runs.append((
                float(abs_diff[bad].max()),
                float(abs_diff[bad].mean()),
                float(signed[bad].min()),
                float(signed[bad].max()),
                n_bad,
                n_post7,
                int(seqs_bad[0]),
                name,
            ))
        if '202 run 127' in name or name == '202/127':
            day37 = usable & (band['seq_number'][:end] >= 865) & (band['seq_number'][:end] <= 2016)
            corrs37 = dbg['corrected_re_current'][:end][day37]
            lo_skips = int(np.sum(corrs37 < 0.5))
            hi_skips = int(np.sum(corrs37 > 50.0))
            in_band = (corrs37 >= 0.5) & (corrs37 <= 50.0)
            run_202_127 = dict(
                name=name,
                day37_usable=int(day37.sum()),
                lo_skips=lo_skips,
                hi_skips=hi_skips,
                vendor_ref_mean=round(float(corrs37[in_band].mean()), 6) if in_band.any() else 0.0,
                unfiltered_ref_mean=round(float(corrs37.mean()), 6) if len(corrs37) else 0.0,
                post7_usable=n_post7,
                post7_bad=n_bad,
                max_abs=round(float(abs_diff.max()), 4) if n_post7 else 0.0,
                mean_abs_bad=round(float(abs_diff[bad].mean()), 4) if n_bad else 0.0,
                median_abs_bad=round(float(np.median(abs_diff[bad])), 4) if n_bad else 0.0,
                min_signed=round(float(signed[bad].min()), 4) if n_bad else 0.0,
                max_signed=round(float(signed[bad].max()), 4) if n_bad else 0.0,
                first_bad_seq=int(band['seq_number'][:end][post7][bad][0]) if n_bad else None,
            )

    if all_diffs:
        cat = np.concatenate(all_diffs)
        abs_cat = np.abs(cat)
        print(
            f'glucose-impact {label}: {affected_runs}/{total_runs} runs affected, '
            f'{affected_recs}/{total_post7} post-day-7 records (>1e-7 mg/dL) | '
            f'abs mg/dL median={np.median(abs_cat):.4f} mean={abs_cat.mean():.4f} '
            f'p95={np.percentile(abs_cat, 95):.4f} max={abs_cat.max():.4f} | '
            f'signed=[{cat.min():.4f}, {cat.max():.4f}]',
            flush=True,
        )
        worst_runs.sort(reverse=True)
        for max_a, mean_a, min_s, max_s, nb, np7, fseq, rname in worst_runs[:8]:
            print(
                f'  top {rname}: max={max_a:.4f} mean={mean_a:.4f} signed=[{min_s:.4f},{max_s:.4f}] '
                f'({nb}/{np7} recs, first seq {fseq})',
                flush=True,
            )
    else:
        print(f'glucose-impact {label}: 0/{total_runs} runs affected, 0/{total_post7} post-day-7 records (>1e-7 mg/dL)', flush=True)
    if run_202_127 is not None:
        print(f'  detail {run_202_127}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--perturbations', action='store_true')
    parser.add_argument('--fixtures', action='store_true')
    parser.add_argument('--decay', action='store_true')
    parser.add_argument('--glucose-impact', action='store_true')
    parser.add_argument('--per-record-snapshots', action='store_true')
    parser.add_argument('--sweep', nargs=2, type=int, metavar=('SEED', 'COUNT'), action='append', default=[])
    parser.add_argument('--takeover-sweep', nargs=2, type=int, metavar=('SEED', 'COUNT'), action='append', default=[])
    args = parser.parse_args()

    if REF.sha256 not in co.KNOWN_REFERENCE:
        parser.error(f'unexpected reference SHA-256: {REF.sha256}')

    if not any((args.perturbations, args.fixtures, args.decay, args.glucose_impact, args.sweep, args.takeover_sweep)):
        args.perturbations = True
        args.fixtures = True

    failures = 0
    if args.glucose_impact:
        jar = df.engine_jar()
        if args.fixtures:
            specs = mf.DEVELOPMENT | mf.HOLDOUT
            measure_glucose_impact('fixtures', [(name, specs[name], None) for name in specs], jar)
        for seed, count in args.sweep:
            cases = list(itertools.islice(sweep_cases(seed, False), count))
            measure_glucose_impact(f'fresh seed {seed} ({count})', cases, jar)
        for seed, count in args.takeover_sweep:
            cases = list(itertools.islice(sweep_cases(seed, True), count))
            measure_glucose_impact(f'takeover seed {seed} ({count})', cases, jar)
        return

    if args.perturbations:
        print('Running controlled perturbations:', flush=True)
        run_perturbations()
    if args.fixtures:
        specs = mf.DEVELOPMENT | mf.HOLDOUT
        cases = [(name, specs[name], None) for name in specs]
        failures += verify_suite('fixtures', cases, per_record_snapshots=True)
    if args.decay:
        cases = [(f'decay-{i}', spec, None) for i, spec in enumerate(itertools.islice(decay_specs(), 160))]
        failures += verify_suite('decay', cases, per_record_snapshots=args.per_record_snapshots)
    for seed, count in args.sweep:
        cases = list(itertools.islice(sweep_cases(seed, False), count))
        failures += verify_suite(f'fresh seed {seed} ({count})', cases, per_record_snapshots=args.per_record_snapshots)
    for seed, count in args.takeover_sweep:
        cases = list(itertools.islice(sweep_cases(seed, True), count))
        failures += verify_suite(f'takeover seed {seed} ({count})', cases, per_record_snapshots=args.per_record_snapshots)

    if failures:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
