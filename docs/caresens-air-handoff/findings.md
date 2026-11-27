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
  - Errors 4 and 32 ignore their device parameters (e237, e230): their thresholds are hard-coded.
  - The other detectors' dependence on the block was **not tested**.
  - The defaults match measured thresholds in several places:
    - `err2_glu = 800` (the error 2 glucose limit)
    - `err2_cv[0] = 10` (the error 2 CV limit)
    - `err2_seq[1] = 11` (error 2 fires on about the 11th record)
    - `err345_raw = {0.1, 0.5, 0.2, 0.7}` (error 4 condition 0)
    - `err345_seq1[1] = 576`, `err345_seq3[1] = 864` (phase boundaries)

  Treat the remaining `err*` defaults as candidate thresholds.

## Error 16 (sensitivity)

Confirmed:

- **Inputs** (e213, e214, e220, e222; 100 % on 5 scenarios including gaps):
  - `W` = .19203786935092992, .16522460111444, .11844988518211, .0716100935714, .03473077453465, .01186996852659, .00419148479069, .01186996852659, .03473077453465, .0716100935714, .11844988518211, .16522460111444 (newest first, sum 1; `err16lib.py`).
  - `P` (`err16_CGM_plasma`) = round(Σ W·round(R)) over the last 12 accepted records.
  - `ISF` (`err16_CGM_ISF_smooth`) = round(Σ W·5min·100/slope100).
  - Both are NaN unless those 12 records have consecutive sequence numbers; on rejected records the debug shows 0.
  - P is not affected by the history smoothing (`smooth_sig_in`).
  - ISF does not depend on the plasma history, the Kalman state or `err_glu_arr`.
- **Update cadence** (e201): plasma and ISF update every record from seq 12. Other statistics start updating at:

  | Statistic | From seq |
  |---|---|
  | `roc_value` | 871 |
  | `trend_min`, `trend_mode`, `trend_mean` | 692 |
  | `*_max` | 865 / 889 |
  | `roc_steady`, `roc_min` | 1101 |
  | mean slope and rsq | 968 |

- **Conditions.** `err16_condi[7]` is evaluated every record. Only conditions 0, 1 and 2 were ever seen, in 160 decaying-sensitivity runs (`err16_ds.npz`, `err16_tr.pkl`). Conditions 0 and 2 start from seq 1728 (day 6), condition 1 from seq 2019 (day 7).
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

Not explored:

- How each statistic (`trend_min/mode/mean`, `roc_*`, `*_max(_early)`, slope, rsq, ratio, diff) is computed from the 865-entry state arrays.
- Perturbing the `err6_CGM_*` device parameters. Defaults: rpd 0.55, slp −0.2, low3d 24/0.32, low1d 24/0.3, prct 30/50/70, day 1/3, BLE_bad 12/96, poly2 0.7. They look like error 16's thresholds.
- Conditions 3–6. The state names (`err16_cal_cons_*`, `err16_cal_day_*`) and `err6_cal_*` point at user calibrations; JugglucoNG passes no calibration list.

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

The delay tests D0–D4 (`err4_delay_condi`, any → `err4_delay_flag`) are not implemented:

| Test | Rule | Phase | Exactness |
|---|---|---|---|
| D0 | max(rawmax, rawmax of the previous consecutive record) < 1.1 | seq < 2017 | exact for start-1 runs |
| D1 | round(init) < 40 ∧ rawmax < 1.5 (tree e280 on `d1X/d1Y.npy`) | 865–2016 | 464 mismatches left, all phase-related |
| D2 | round(R) < 40 ∧ rawmax < 3.0 | 865–2016 | 30 mismatches, superset |
| D3 | round(R) < 20 ∨ round(init) < 20 | all | 76 mismatches as a pure R test |
| D4 | round(R) < 30 | seq ≥ 2017 | exact |

- The table's rules come from bisection on 3.52/7 slopes and both lots (e251); the D0 refinement is e263 and e266.
- **The phase after day 7 is wrong for late-start runs** (start 600 or 1500, e284). D0 stays on after seq 2017 for some low records and not for others, so slot 0 in phase 3 seems to be a different test.
- Whether phases count seq or record index is untested.

Rejected (D tests): D0 = 5min < 1.1, and D1 = 5min < 1.5. Both were replaced by the rules above.

## Data type 4 (low-signal flag)

Confirmed:

- **Definition** (e248, 0 mismatches in 255 858 records): `data_type 4 = err1_random_noise_temp_break || err4_delay_flag`.
- **Trend.** Type 4 withholds the trend. The engine's trend window is history entries 3–9.
- **Sensitivity reference** (e277, exact count and sum on a dropout run). The day 3–7 reference (`sum_curr_for_mdc` 10976, `sum_curr_cnt_for_mdc` 10984; also `mdc_start_idx`, `mdc_first_flag`) skips a record if and only if it and the previous accepted record are both type 4. So type 4 changes the corrected current after day 7, not only the trend.

Implemented as a stand-in: the engine does not emit type 4.

- It marks `exact = false` when `maybeLowFlag` holds: rawMax < 1.1 ∨ R < 40.5 ∨ init < 20.5 ∨ (init < 40.5 ∧ rawMax < 1.5).
- A flagged pair in 865–2016 makes everything after seq 2016 inexact.
- A flagged record in the trend window makes the trend inexact.
- This misses the err1 noise-break half: fresh sweep run 16 of seed 202 (start 600) differs at record 484 on the trend.

Rejected: the first superset (rawMax < 3 ∨ R < 40 ∨ init < 40) was too broad. It made most of the wear inexact.

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

No input was found that triggers either (`stress.py`, `stress2.py`). `err8_condi[2]` and the `err128_*` debug fields stayed 0 or NaN throughout. What was tried:

- all 18 signal transforms
- temperatures 10–50 °C, 0 °C, −5 °C, NaN, and alternating 30/38 °C
- a sequence wrap at 65535
- the clock going backwards
- a duplicate sequence number
- a start at seq 4000

State:

- **error 8:** only `err8_result_prev` (48825).
- **error 128:** `err128_flag_prev[40]`, `err128_normal_prev`, `err128_revised_value_prev`, `err128_CGM_c_noise_revised_value[36]` (48826–49175). The names suggest a noise-revised glucose.

Not explored:

- state perturbation (setting `err128_*` and `err8_result_prev`)
- the device parameters `errcode_version` (2), `cal_noise` (3.0) and `err345_filtered/min/range/md` as gates
- calibration-list input

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

Last sweep results on head 47549cd25:

- **Fresh**, seeds 101 and 202 (300 runs each): 1 of 600 differs, seed 202 run 16 (the trend under the err1 noise break). 218 091 records were not claimed exact.
- **Takeover**, seeds 7 and 8 (150 runs each): 1 of 300 differs, seed 7 run 99. 54 548 records were not claimed exact.

**What the sweep does not count as a difference:**

- errors 1, 2, 8, 16 and 128 (only bits 4, 32 and 64 are compared)
- readings the vendor withholds under them
- records where the engine says `exact = false`
- everything from the first filled record on
- bit 4 from the first record where an err4 condition other than 0 fired
