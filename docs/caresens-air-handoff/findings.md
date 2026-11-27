# CareSens Air: research findings (status 2026-11-27)

This file lists everything learned about the vendor library
(`libCALCULATION.so`, `air1_opcal4_algorithm`), for the parts the open engine
does not reproduce yet. Every result comes from black-box runs. Script names
refer to `tools/caresens-oracle/research/`; the raw notebook is
`checkpoint-2026-11-27.md` there. "Exact" means 0 mismatches on the stated data.

Field names and offsets come from `Common/src/main/cpp/air/air.hpp`; the full
map is `tools/caresens-oracle/research/state-offsets.txt` (regenerate with
`python3 state_map.py`). Abbreviations used below:

| Name | Meaning |
|---|---|
| raw | the 30 per-record 10 s currents (`tran_inA`, before the spike limiter) |
| rawmax | the maximum of raw for one record |
| 5min | `tran_inA_5min`, the record current before the lot offset |
| R | `result_glucose` |
| init | `init_cg`, factory glucose |
| seq | the transmitter sequence number |
| accepted | not data type 2 (contact rejection) and not error 64 |

## Common mechanisms

Confirmed:

- **Evaluation.** Every detector is evaluated on every record; the debug
  record shows each one's conditions (`err4_condi`, `err16_condi`, …) turning
  on and off. The error bit is a latch over those conditions.
- **Latch, errors 1, 2 and 4** (e267, e268, e269). The bit is
  `cond || (previous bit && record consecutive (seq = previous + 1) && accepted)`.
  - A rejected record reports 0 and clears the latch.
  - A sequence gap clears it.
  - A time jump with a consecutive sequence keeps it.
  - State: `err1_result_prev` 24816, `err2_result_prev` 40088, `err4_result_prev` 48824.
- **Latch, error 16.** Permanent once any `err16_condi` is 1 (`err16_result_prev` 117280). A rejected record reports 0 for that record only.
- **Latch, error 32.** Permanent, and also reported on rejected records. Implemented.
- **Shared glucose buffer.** `err_glu_arr[288]` (offset 17144) holds round(R) for the last 288 records (24 h), with `time_standard_arr[288]` (456) alongside. It is filled per record (e209, e210). It feeds error 16's plasma filter; whether the other detectors read it was not tested.
- **`err_delay_arr[7]`** (17132) exists; not studied.
- **Device block.** The transmitter overwrites the whole device block from its BLE info packets: `airSaveSensorInfo2` in `air/java.cpp` copies 125–205 bytes into `DeviceInfo3Obj`. That block carries `err1_*`, `err2_*`, `err345_*`, `err6_*` and `err32_*` parameters (defaults in `DeviceInfo2Obj`). Real sensors may therefore send non-default thresholds.
  - The prior targeted probes for errors 4 and 32 (e237, e230) found no effect.
  - The [task 00 survey](device-parameters.md) found no changes in emitted bits 1, 2, 4, 16, 32 and 64 across 89 field elements. Bits 8 and 128 stayed inactive, so their dependencies remain unknown.
  - The defaults match measured thresholds in several places:
    - `err2_glu = 800` (the error 2 glucose limit)
    - `err2_cv[0] = 10` (the error 2 CV limit)
    - `err2_seq[1] = 11` (error 2 fires on about the 11th record)
    - `err345_raw = {0.1, 0.5, 0.2, 0.7}` (error 4 condition 0)
    - `err345_seq1[1] = 576`, `err345_seq3[1] = 864` (phase boundaries)

  Treat the remaining `err*` defaults as candidate thresholds.

## Error 16 (sensitivity)

Confirmed:

- **Inputs** (e213, e214, e220, e222 and the [source experiment](error-16-evidence.md#the-actual-isf-input)):
  - `W` = .19203786935092992, .16522460111444, .11844988518211, .0716100935714, .03473077453465, .01186996852659, .00419148479069, .01186996852659, .03473077453465, .0716100935714, .11844988518211, .16522460111444 (newest first, sum 1; `err16lib.py`).
  - `P` (`err16_CGM_plasma`) = round(Σ W·round(R)) over the last 12 accepted records.
  - `ISF` (`err16_CGM_ISF_smooth`) = round(Σ W·revised current·100/slope100). Black-box replay identifies the revised-current history at offset 48888 from the error-128 path. It differs from the ordinary 5-minute current on low-signal recovery records.
  - Both are NaN unless those 12 records have consecutive sequence numbers; on rejected records the debug shows 0.
  - P is not affected by the history smoothing (`smooth_sig_in`).
  - Direct perturbation identifies the revised-current array as the filter input. The earlier tests on regular signals could not establish whether stages upstream of that array depend on glucose.
- **Update cadence** (e201): on a gapless run from sequence 1, plasma and ISF
  first become finite at sequence 12. Other statistics can begin updating at:

  | Statistic | From seq |
  |---|---|
  | `roc_value` | 871 |
  | `trend_min`, `trend_mode`, `trend_mean` | 692 |
  | `*_max` | 865 / 889 |
  | `roc_steady`, `roc_min` | 1101 |
  | mean slope and rsq | 968 |

- **Conditions.** Only conditions 0, 1 and 2 fired on empty-list replay.
  They use 24 consecutive accepted sequence numbers, the corresponding
  statistic histories and the pre-record diabetes profile. Condition 0 can
  also fire on the early mean reference from sequence 1728. The research
  prototype matches all three conditions and the latch in the mixed
  sweeps with revised current supplied. [Task 01 evidence](error-16-evidence.md)
  gives the update windows, thresholds and replay results.
- **Where it fires** (`err_survey.py`, `err_survey.json`): naturally in 7 of 268 runs:
  - fixtures `life-lot2-sensitivity-loss` (first record 2637), `holdout-life-other-lot1` (1893) and `holdout-late-takeover-lows` (1948)
  - random runs 67, 101 and 175 of the survey
  - with signal transforms (`stress.py`): `step_down` (1031), `slow_drop`, `sat`

Rejected:

- Simple AND rules over the exposed debug scalars do not separate the three conditions:
  - greedy AND (`rules.py`, run interactively on `err16_ds.npz`)
  - pairwise thresholds (e227)
  - CART trees (`cart.py`, run interactively)
  - hand-built conjunctions for condition 1 (e229)

  The best partial fits are below. Their odd split points (−30.2, −29.5) suggest non-exposed or previous-record values.
  - condition 1 ≈ `trend_mean_ratio` ≤ ~−30 ∧ `trend_mean_rsq` ≥ 0.8 ∧ `trend_mean_slope` ≤ −5 ∧ `trend_mean_max(_early)` ≤ ~182 ∧ `trend_mean_diff` ≤ ~−51
  - condition 2 ≈ `roc_steady` ≥ ~52 ∧ `trend_mean_ratio` ≤ ~−20 ∧ `trend_min_ratio` ≤ ~−20
  - condition 0 ≈ `trend_min_ratio` ≤ −45.5 ∧ `trend_mean_diff` ≤ −77.8 …
- Many transitions in `err16_tr.pkl` coincide with no threshold crossing of any exposed scalar.

Still open:

- Generating the upstream revised current (Task 07). The open engine's
  glucose can also differ after day seven, which changes plasma in the
  research replay. The mixed sweeps with revised current supplied show no
  resulting condition or latch discrepancies.
- Conditions 3–6 stayed inactive with empty lists. The state names (`err16_cal_cons_*`, `err16_cal_day_*`) and `err6_cal_*` point at user calibrations. Their behavior with a calibration list remains unknown.

## Error 4 (signal lost), conditions other than 0, and the delay flags

Confirmed and implemented:

- **Condition 0** (c0m4.py on `c0runs.pkl`, 80 noisy-dropout runs, 0 false positives / 0 false negatives). m = max raw over the last 4 accepted records with consecutive sequence numbers. The condition is:
  - seq < 865: m < 0.5 ∧ m·100/slope100 < 10
  - seq ≥ 865: m < 0.7 ∧ m·100/slope100 < 20
- **State.**
  - `err4_inA[390]` (40096): raw of the last 13 records, per accepted record.
  - `err4_min_prev[289]`, `err4_range_prev[51]`, `err4_min_diff_prev[289]`.
  - `err4_delay_flag_arr[576]` (48248).
  - Debug `err4_min` = running minimum of 5min; `err4_range` = 5min[i] − 5min[i−1]; `err4_min_diff` = min − min_prev.

Rejected (condition 0):

- The last 4 sequence slots, with a missing or rejected slot giving false (e265). Superseded: the vendor counts per accepted record.
- One threshold pair for the whole wear: the apparent hysteresis was the phase at seq 865.
- The variants in `c0m4.py`'s `L` dictionary: mean-max, ISF < 25 or < 30.

Partly known (conditions 1–4, not implemented):

- **Condition 1:** a relative fall against the past day's level. It fires in late-start runs (start seq 1500 or 3000) at a 5min/max ratio of 0.32–0.61 (`c1runs.pkl`, e285). Rule not decoded.
- **Conditions 2 and 3:** negative-current tests that fire from seq 865 on sustained very low signals (scaled ×0.25 / ×0.4). Their limits scale with the level before the plateau (e254), and in every test condition 0 had already fired.
- **Condition 4:** never seen.

The delay tests D0–D4, the `err4_delay_condi` → `err4_delay_flag` rapid-drop gate, and the sensitivity-reference current band are confirmed and implemented (`CareSensAirDelayFlag.kt`, `CareSensAirCalibration.kt`, `CareSensAirCalibrationState.kt`, verified by `err4_delay_probe.py` with 0 mismatches across 1,231 runs / 2,374,726 accepted records):

- **Phases** (controlled by `seq` and pre-D updated `diabetes_before_7days_flag` `b7`, offset 10914):
  - Phase 1 (`seq < 865`): D0, D3.
  - Phase 2 (`seq >= 865 && !(seq >= 2017 && b7 >= 4)`): D0, D1, D2, D3 (resolves the `e284.py` late-start phase mystery: runs with `b7 < 4` remain in Phase 2 past `seq = 2017`).
  - Phase 3 (`seq >= 2017 && b7 >= 4`): D3, D4.
- **Tests** (`rR = roundAwayFromZero(result_glucose)`, `t5 = tran_inA_5min`, `consecutive = idx > 0 && accu_seq[-1] == seq - 1`):

  | Test | Exact rule | Active phase |
  |---|---|---|
  | D0 | `consecutive && max(rawmax, prev_rawmax) < 1.1` | Phase 1, Phase 2 |
  | D1 | `rR < 40 && rawmax < 1.5` | Phase 2 |
  | D2 | `rR < 40 && rawmax < 3.0 && rawmax * 100 / slope100 < 60` | Phase 2 |
  | D3 | `rR < 20 \|\| t5 < 0.4` | All |
  | D4 | `rR < 30 \|\| (rawmax < 0.8 && rawmax * 100 / slope100 < 20)` | Phase 3 |

- **Gate (`err4_delay_condi` → `err4_delay_flag`):** when `any(D0..D4)` holds:
  - If `seq < 865 || idx == 0 || prev_err4_delay_flag == 1`: `err4_delay_flag = 1`.
  - Else count prior accepted records `n_win` with `accu_seq > 0 && seq - accu_seq <= 23`:
    - If `n_win >= 21`: requires a consecutive rounded-glucose drop `g[k-1] - g[k] >= 40` ending at `g[k] < 100` in the `n_win` prior entries plus `rR`.
    - Else (`n_win < 21`): requires `D3 && err_glu_arr[-1] - rR > 50` (no `< 100` ceiling).

Rejected (D tests): D0 = 5min < 1.1, D1 = 5min < 1.5, and the assumption `err4_delay_flag == any(err4_delay_condi[0..4])` (superseded by the `seq >= 865` rapid-drop gate above).

## Data type 4 (low-signal flag)

Confirmed and implemented (`err4_delay_probe.py`, `CareSensAirDelayFlag.kt`, `CareSensAirCalibration.kt`; 0 mismatches across 1,231 runs / 2,374,726 accepted records):

- **Definition** (`e248`, `err4_delay_probe.py`): `data_type 4 = err1_random_noise_temp_break || err4_delay_flag`. `CareSensAirCalibration.process(record, noiseBreak = false)` isolates Task 02's `err1_random_noise_temp_break` as a boolean input.
- **Trend.** `err_delay_arr[7]` (offset `17132`) is a 7-record FIFO of `data_type == 4` on accepted records, aligned with history slots 3–9. Any `1` in `err_delay_arr[0..6]` withholds `trend` (`100.0`).
- **Sensitivity reference and initial high-pass mean** (disproves `e277.py`'s type-4 pair hypothesis): both `sum_curr_for_mdc` / `sum_curr_cnt_for_mdc` (`seq 865..2016`) and `sum_curr_for_init_value` / `sum_curr_cnt_for_init_value` (`seq 5..24`) filter strictly by `0.5 <= corrected_re_current <= 50.0` nA, independent of `data_type == 4`, `slope100`, and `lot_type`.
- **Glucose accuracy impact (`err4_delay_probe.py --glucose-impact`):** before applying the `[0.5, 50.0]` filter, 71/1,071 runs (81,536/755,272 post-day-7 records, 10.8 %) had glucose discrepancies up to −73.91 mg/dL (seed 202 run 214; seed 202 run 127 had 2,310/2,310 post-day-7 records shifted by up to −0.309 mg/dL from 3 skipped records). After the fix, 0/1,071 runs and 0/755,272 post-day-7 records differ (`> 1e-7 mg/dL`).
- **Stand-ins removed:** `maybeLowFlag`, `historyMaybeFlagged` and `referenceMaybeFlagged` are removed; `exact = false` now marks only record filling (`dt in 480 until 1380`). The single remaining `err1_random_noise_temp_break` trend difference is fresh sweep seed 202, run 16, record 484 (Task 02).

Rejected: `e277.py`'s hypothesis that `sum_curr_for_mdc` skips consecutive `data_type == 4` pairs; the first superset (`rawMax < 3 || R < 40 || init < 40`).

## Error 2 (high signal)

Partly known (e290, e291, `stress.py` transforms `high3`, `sat`):

- `err2_condi[0]` turns on at about the 11th record of a high plateau.
- `crt_glu[0]` is glucose > ~800: 819 fired, 729 did not.
- `crt_cv` is the CV% of 5min over 12 records, below ~10.
- `crt_current[0]` depends on slope100: at slope100 7 it never turned on up to 60 nA. Unresolved.
- The state holds 575-entry `err2_delay_*` histories (roc, slope_sharp, glucose) and cummax values.

Not explored: perturbing the `err2_*` device parameters. Defaults: start_seq 289, seq {20, 11, 6}, glu 800, cv {10, 0.05, 10}, cummax 2, multi 10, ycept 2, alpha 2.

## Error 1 (noise) and the noise break

Partly known:

- **Triggers** (`stress.py`): noise200, noise1000, alt, spike1, ramp_in_rec, dropouts, halfrec. First records are 511–543 for a transform starting at record 500.
- **Noise break.** `err1_random_noise_temp_break` (part of type 4) tracks `err1_i_sse_d_mean`, roughly between 2.54 and 24.7, against the adaptive `err1_th_sse_d_mean1/2` (e292 tree). Not decoded.
- **State** (19448–24857): thresholds `err1_th_*`, `err1_i_sse_d_mean4h[100]`, `err1_SG_1min[180]`, `err1_inA_1min[180]`, `err1_time_1min[180]`, `err1_is_contact_bad1h[100]`, `err1_current_avg_diff_prev[100]`, the TD (temporary break) trio and counts.

Not explored: the `err1_*` device parameters. Defaults include seq {23, 47, 11}, contact_bad 0.5, th_diff 2, th_sse_dmean {0.05, 0.1, 0.5}, th_n1 {43, 40, 37, 34}, th_n2, n_consecutive 6, i_sse_dmean_now {3, 0.001}, count_sse_dmean 12, n_last 288, multi {10, 10}, current_avg_diff 1e-15.

## Errors 8 and 128

No input was found that emits bit 8 or bit 128 (`stress.py`, `stress2.py`).
`err8_condi[2]` and bit 128 stayed 0 throughout. What was tried for bit emission:

- all 18 signal transforms
- temperatures 10–50 °C, 0 °C, −5 °C, NaN, and alternating 30/38 °C
- a sequence wrap at 65535
- the clock going backwards
- a duplicate sequence number
- a start at seq 4000

Confirmed for the **error-128 revised-current stage** (`err128_revised_current_probe.py`,
`Error128RevisedCurrent.kt`; 0 mismatches across 2,374,726 accepted records and
1,894 active records over 1,231 runs including fixtures, decay corpus, 900 mixed
runs and 150 independent holdout runs):

- **State:** `err128_flag_prev[40]` (48826), `err128_normal_prev` (48872),
  `err128_revised_value_prev` (48880), `err128_CGM_c_noise_revised_value[36]` (48888).
  Rejected records (`data_type == 2` or error 64) leave all four fields unchanged.
- **Gate:** active only when `idx >= 36` (prior accepted records) and pre-record
  `diabetes_before_7days_flag` is in `{0, 1, 2}`. Otherwise `err128_flag = 0`,
  `err128_normal = NaN`, and `err128_revised_value = tran_inA_5min`.
- **Onset (`err128_flag_prev[39] == 0`):** fires when the 11 prior accepted
  records satisfy `accu_seq[-11] != 0`, `1 <= seq - accu_seq[-11] <= 11`, none
  exceeds `120.0` mg/dL, at least 9 of their 10 consecutive intervals have
  `dt != 0` and `|Δg / (dt / 60)| < 0.8` mg/dL/min, and the current step has
  `dt != 0` and `(g - err_glu_arr[-1]) / (dt / 60) < -2.5` mg/dL/min (using
  rounded glucose `err_glu_arr` and unsigned 32-bit `dt`). Onset sets
  `err128_normal = err_glu_arr[-1]` and holds `err128_revised_value_prev`.
- **Persistence (`err128_flag_prev[39] == 1`):** stays active (holding
  `err128_normal_prev` and `err128_revised_value_prev`) iff `err128_flag_prev[4] == 0`
  (36 accepted records ago), `|seq - accu_seq[-1]| <= 3`, and
  `!(g >= 80.0 || g >= err128_normal_prev)`.
- **Update:** on every accepted record, `err128_flag_prev` shifts left by 1 and
  appends `err128_flag`, `err128_CGM_c_noise_revised_value` shifts left by 1 and
  appends `err128_revised_value`, and the `*_prev` scalars store the new values.
- **Device parameters:** `cal_noise`, `errcode_version` and `sensor_version`
  perturbations did not affect revised-current activation or values.

State (error 8): only `err8_result_prev` (48825).

Not explored:

- bit-8 and bit-128 emission with state perturbation (`err8_result_prev`),
  `err345_filtered/min/range/md` gates, or calibration-list input

## Record filling

Confirmed (commit 99eb5936d; legacy harness, e89–e109):

- **When.** With a consecutive sequence number and a time step of 480–1379 s, `air1_opcal4_algorithm` writes up to four outputs and debug records for one input, filling the missed slots.
  - It returns the count; the oracle gives each input four slots (`count[i]`).
  - The app now has room for four (native fix); before, it overran by about 4.7 KB.
- **Synthesised samples.** The filled ADC samples (`workout` of the filled outputs) are a **linear** function of the inputs (superposition holds).
  - They depend on the three 10-sample block means of the previous record and on the first block of the current record.
  - The current record's samples 10–29 have no effect.
  - The unit responses are measured to about 1e-5 (e98, `fillB.npy` from that script).

Rejected interpolants: none reproduce the unit responses.

- a cubic spline through the block means at −250, −150, −50 and 350 s (or shifted by 5 s), with natural, not-a-knot, clamped or ((1,0),(2,0)) ends (e98)
- a cubic Lagrange polynomial through the same means, and PCHIP- or Akima-type end slopes, tried interactively (no script kept)

The right end has zero curvature. The slope at −50 s is a combination of the three secants, with weights about −0.163, 0.879 and 0.284, which depend on the gap (e104 prints them for gaps 480–1379).

Not explored:

- how the number of filled records depends on the gap
- whether the filled records go through the full chain: errors, history, trend and Kalman (they probably do, since the oracle compares their outputs)
- the engine state after a fill; the sweep stops comparing at the first fill

## Other open differences

- **Takeover during dropouts.** In run 99 of the import sweep with seed 7 (spec seed 121379890, started from vendor state after record 1120), the minute currents differ at record 1425. Not investigated. Reproduce:
  ```
  python3 differential.py --runs 100 --seed 7 --import-state
  ```
  then `dg.py` on that spec.
- **Sample current.** ADC to current agrees to ±4 ulp, not bit for bit (`e184.py`, `e186.py` tried fma and operation-order variants). A spike-limiter decision exactly on its threshold could flip; none was seen in 900 runs.

## Reproduction commands

```
export CARESENS_REFERENCE_LIB=/abs/path/arm64-v8a/libCALCULATION.so
export JAVA_HOME=/path/to/jdk17
cd tools/caresens-oracle
python3 differential.py --runs 300 --seed 101          # fresh sweep (also --seed 202)
python3 differential.py --runs 150 --seed 7 --import-state   # takeover sweep (also --seed 8)
cd research
python3 stress.py           # trigger table for 18 transforms
python3 stress2.py          # err8/err128 search: temperature, sequence, clock
python3 err_survey.py       # bits over fixtures + 250 random specs -> err_survey.json
python3 e226.py; python3 e228.py   # err16 datasets
python3 e273.py; python3 c0m4.py   # err4 condition 0 dataset and model score
python3 e248.py             # data type 4 identity check
python3 e267.py; python3 e268.py; python3 e269.py   # latch rules
python3 dg.py "<spec dict>" <record>   # vendor vs engine around one record
```

Last sweep results after Task 05 (`22e306bea`):

- **Fresh**, seeds 101 and 202 (300 runs each, 1,099,980 inputs): 1 of 600 differs — seed 202 run 16 (trend at record 484 under the unmodelled `err1` noise break, Task 02). **0** usable records were not claimed exact (down from 218,091).
- **Takeover**, seeds 7 and 8 (150 runs each, 329,550 inputs): 1 of 300 differs — seed 7 run 99 (minute currents at record 1425, Task 09). **0** usable records were not claimed exact (down from 54,548).
- **Holdout**, fresh seed 303 (100 runs, 168,220 inputs) and takeover seed 9 (50 runs, 50,171 inputs): **0 of 150 differ**, **0** usable records not claimed exact.

**What the sweep does not count as a difference:**

- errors 1, 2, 8, 16 and 128 (only bits 4, 32 and 64 are compared)
- readings the vendor withholds under them
- records where the engine says `exact = false`
- everything from the first filled record on
- bit 4 from the first record where an err4 condition other than 0 fired
