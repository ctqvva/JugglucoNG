# CareSens Air vendor error bits: research checkpoint

Raw lab notebook as kept during the session (terse; see README.md and docs/caresens-air-handoff/ for the cleaned version). Script names refer to files in this directory.

Harness: tools/caresens-oracle/research (sc.py runspec/REF; REF.run(dev, recs, state=...) MUTATES state -> pass copies).
Engine branch t3/caresens-air-open-engine (PR #1041), head 99eb5936d at start of this phase.

## Survey (err_survey.py -> err_survey.json; 18 fixtures + 250 random specs)
- Only err16 fires naturally: life-lot2-sensitivity-loss (first rec 2637), holdout-life-other-lot1 (1893),
  holdout-late-takeover-lows (1948), rand67/101/175 (+1). Latched to end once set.
- err1/2/4/8/128 never fire in synthetic data -> need crafted signals.

## Shared mechanism
- state err_glu_arr[288] @17144 = round(result_glucose) per record (24 h), with time_standard_arr[288] @456.
- Per-detector state: err1_* (SG_1min/inA_1min 180, sse 4h...), err2_delay_* (575), err4_inA(390)/min_prev(289)/
  range_prev(51)/min_diff_prev(289)/delay_flag_arr(576), err128_* (40/36), err16_* (865 = 3 days).
- Debug record has per-detector intermediates (err1_*, err2_*, err4_min/range/min_diff/condi, err8_condi,
  err16_* incl condi[7], err128_flag/revised/normal).

## err16 (in progress)
- debug err16_condi[7]; condition index 2 fired in life-lot2-sensitivity-loss.
- CGM_plasma P (debug; state err16_CGM_plasma[36] = its history) = round(sum w_k g_k) over 12 values:
  newest = current result_glucose (w .192038), then err_glu_arr[-1..-11] with weights
  .1652246 .11844988 .07161009 .03473077 .01186997 .00419148 .01186997 .03473077 .07161009 .11844988 .1652246
  (sum 1). Measured by impulses (e213/e214). Not affected by smooth_sig_in.
- ISF_smooth (865) unaffected by plasma36, kalman, err_glu -> from another input (TODO).
- Update cadence (e201): plasma/ISF every record from seq 12; roc_value from 871; roc_steady/min from 1101;
  trend_min/mode/mean from 692; *_max from 865/889; mean slope/rsq from 968.
- CONFIRMED (e220/e222, 100% on 5 scenarios incl. gaps): 12-tap kernel W (err16lib.py, 14 digits; newest first)
  W = .19203786935093 .16522460111444 .11844988518211 .0716100935714 .03473077453465 .01186996852659
      .00419148479069 .01186996852659 .03473077453465 .0716100935714 .11844988518211 .16522460111444
  P   = round(sum W_k * round(result_glucose[k]))   over last 12 ACCEPTED records
  ISF = round(sum W_k * tran_inA_5min[k]*100/slope100)  (5min = minute trimmed mean BEFORE lot offset)
  Both NaN unless those 12 accepted records have consecutive seq (seq span 11). Rejected records: 0 in debug.
- Conditions: debug condi[7] evaluated EVERY record (turn on/off); error bit 16 latches (state err16_result_prev) once any
  condi is 1. Only condi 0,1,2 seen in 160 decay runs (err16_ds.npz 198k rows sampled every 3rd record, seq>=600;
  err16_tr.pkl = 205 full-resolution transitions). condi 0/2 from seq 1728 (day 6), condi 1 from seq 2019 (day 7).
  condi 3-6 never: names suggest user calibrations (cal_cons/cal_day); JugglucoNG passes no calibrations.
- Rule fitting (rules.py greedy AND, e227 pairs, cart.py tree): NOT separable by simple conjunctions of exposed
  debug scalars. Strong components: cond1 ~ trend_mean_ratio <= ~-30 & trend_mean_rsq >= 0.8 & trend_mean_slope <= -5
  & trend_mean_max(_early) <= ~182 & trend_mean_diff <= ~-51 (tree acc high but odd split points -30.2, -29.5).
  cond2 ~ roc_steady >= ~52 & trend_mean_ratio <= ~-20 & trend_min_ratio <= ~-20. cond0 ~ trend_min_ratio <= -45.5
  & trend_mean_diff <= -77.8 ... Many transitions are not explained by a crossing of any exposed scalar ->
  probably uses values not in debug (or prev-record values / per-day schedules).
- STATUS: input stage exact; statistic definitions (trend_min/mode/mean/roc) and decision rules NOT decoded.
  Moved on (time box). Resume: decode each statistic from ISF865 arrays first (cadence table above), then rules
  with recomputed internal values.

## Triggers (host/stress.py: BASE seed 21 1500 rec, transform on records 500..700)
- err1: noise200/noise1000/alt/spike1/ramp_in_rec/dropouts/halfrec (first ~511-543). err2: high3 (511), sat (505).
- err4: low30/low60/zero (503), step_down (503), slow_drop (592), saw (599). err16 also: step_down (1031), slow_drop, sat.
- All latch to end of data. err8, err128: no trigger found (incl. temperature -5..50, NaN, jumps, seq wrap,
  time backwards, duplicate seq, start seq 4000). err8_condi[2], err128_* debug always 0/NaN.
- data_type 4 appears with low glucose and during err1 windows.
## Device block (air.hpp DeviceInfo2Obj, filled from transmitter BLE info packets) carries err params:
  err1_*, err2_*, err345_*, err6_* (sensitivity), err32_dt/n. err32 IGNORES err32_dt/n (e230) -> hard-coded.
  Each detector must be checked for device-parameter dependence.

## err4 (in progress; host/craft.py plateau experiments: records 500..511 flat ADC)
- err4 IGNORES all err345_/err1_/err2_/err6 device params (e237) -> hard-coded.
- state err4_inA[390] = raw 10 s currents of last 13 records; err4_min_prev[289] (min history), err4_range_prev[51],
  err4_min_diff_prev[289], err4_delay_flag_arr[576]. debug err4_min = running min of 5min current,
  err4_range = 5min[i]-5min[i-1], err4_min_diff = min - min_prev.
- debug err4_delay_condi[5] = raw per-record tests (D), err4_condi[5] = persisted tests (C); err set when any C.
- D3 == data_type 4 == round(result_glucose) < 20 (bisected, 3 slopes).
- D0: lot1 corrected (5min-0.7) < 0.4 (5min < 1.1) for all slopes. (check lot2)
- C0: 5min*100/slope100 < 10 AND 5min < 0.5 ; turns on 3rd record of plateau (503 when drop at 500).
- CONFIRMED C0 (e242/e245, 256k records, 84 mismatches all explained? -> recheck): m = max raw 10 s current over
  last 4 records (120 samples, err4_inA); C0 = m < 0.5 && m*100/slope100 < 10.
- CONFIRMED data_type 4 (e248, 0 mismatches / 255858): type4 = err1_random_noise_temp_break || err4_delay_flag,
  err4_delay_flag = any(err4_delay_condi[0..4]). D3 = round(result_glucose) < 20. D0 = 5min < 1.1 (both lots).
- err4 phases (e244): D1,D2 start seq 865; at seq 2017 D0..D2 stop, D4 starts. flat 1.6 nA -> D2 (flatness?),
  flat 0.8 -> D0,D1,D2,D3. C2,C3 fire from 865 on sustained very low (scaled x0.25/x0.4) signals.
- CONFIRMED by bisection (e251; slopes 3.52/7, lots 1/2; plateau 300..2500):
  D0 = 5min < 1.1 (seq < 2017); D1 = 5min < 1.5 (865 <= seq < 2017); D2 = round(glu) < 40 && 5min < 3.0
  (865 <= seq < 2017); D3 = round(glu) < 20 (all); D4 = round(glu) < 30 (seq >= 2017). glu = result_glucose
  (D3 verified on Kalman dips). Phase unit (seq vs record index) untested.
- CONFIRMED D0 = seq<2017 && max(raw max of this record, raw max of previous seq slot if present) < 1.1 (0 mism).
  D1 = 865<=seq<2017 && rawmax(this) < 1.5 (4 mism, all predicted-on/vendor-off). D2 = 865<=seq<2017 &&
  round(R)<40 && rawmax<3 (30 mism, superset). D3/D4 exact.
- C0 window = last 4 SEQ slots; missing or rejected slot -> false (e265). tran_inA == raw currents (pre-limiter).
- SHARED LATCH (e267/e268/e269): err1, err2, err4: err = cond || (prev err && record consecutive (seq+1) && accepted).
  A rejected record (type 2) reports 0 and clears; a seq gap clears; a time jump with consecutive seq keeps.
  err16: permanent latch; rejected record reports 0 for that record only. err32: permanent, also on rejected.
- C2/C3 thresholds relative to history (e254: scale with pre-plateau level); only after C0 in all tests. Not decoded.
- C0 CORRECTED (c0m4.py, 80 noisy-dropout runs, 0 fp / 0 fn): m = max raw current over last 4 accepted records with
  consecutive seq; seq<865: m<0.5 && m*100/slope100<10; seq>=865: m<0.7 && m*100/slope100<20
  (= device default err345_raw [0.1,0.5,0.2,0.7] as ratio/raw pairs). Plateau-only data had hidden the phase.
- PIPELINE EFFECT (e277): vendor reference (sum_curr_for_mdc, 865..2016) skips a record iff it AND the previous
  accepted record are data type 4 (exact count+sum on one dropout run). So type 4 matters beyond trend:
  dropout sweeps fail at seq 2017 (corrected current) without it. State: mdc_start_idx, mdc_first_flag exist.
- D-phase problem: in dropout runs vendor still applies D0..D2 after seq 2017 (runs where err4 latched).
- D1 is really round(init_cg)<40 && rawmax<1.5 (tree e280; 464 mism remaining are phase issues). D3 = R|init <20 (76).
- D phases: in late-start runs (start 600/1500) D0 stays on after seq 2017 for some low records and not others
  (e284), D4 starts at seq 2017; phase-3 slot 0 seems a different test. NOT decoded (time box).
- Engine plan: err4 bit exact (C0 phased + latch). Low flag NOT emitted; superset "maybe type 4" =
  rawmax<3 || round(R)<40 || round(init)<40 marks trend inexact (window) and post-2016 compensation inexact
  if any such record fell in 865..2016 (reference skips type-4 pairs). err1 noise-break part of type 4 still uncovered.

## COMMITTED 3e4446841 (pushed, PR #1041): err4 C0 + latch, maybeLowFlag inexact marking, dropout fixtures.
Sweeps after commit: fresh 600 runs (seeds 101/202): 1 differs (trend withheld by err1 noise-break type 4, known gap);
import 300 runs (seeds 7/8): 1 differs (minute currents after takeover during dropouts, not investigated).
~15% of records not claimed exact (dropout runs: whole post-day-7 part when a flagged pair fell in days 3-7).
- err4 C1: relative fall vs past-day level, fires in late-start runs (start 1500/3000) at 5min/max ratio 0.32-0.61;
  not decoded. C2/C3 negative-current, history-relative. C4 never seen.
NEXT: err2 (trigger high3/sat), err1 (noise; also supplies type-4 noise break), err8/err128 (no trigger).
- err2 (e290/e291): condi[0] at ~11th record of high plateau; crt_glu[0] = glucose > ~800 (819 on/729 off);
  crt_cv = CV% of 5min over 12 records (<~10); crt_current[0] slope-dependent, unresolved (slope 7 never on up to 60 nA).
- err1 noise break (e292 tree): i_sse_d_mean in ~(2.54, 24.7) vs adaptive th_sse_d_mean1/2; not decoded.
## COMMITTED 47549cd25 docs evidence. STOPPED here (time box). Resume order: err16 statistics -> rules; err1; D-phase-3.
