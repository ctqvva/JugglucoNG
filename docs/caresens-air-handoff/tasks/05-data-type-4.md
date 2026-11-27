# Task 05: data type 4 (low-signal flag) and the error 4 delay tests

Setup and conventions: `00-shared-infrastructure.md`. Background:
`../findings.md`, sections "Data type 4" and "Error 4" (delay tests).

## Status (low-signal delay-test portion complete)

All five delay tests (`D0`–`D4`), phase transitions (including late-start runs),
the `err4_delay_condi` → `err4_delay_flag` rapid-drop gate, the seven-record
`err_delay_arr` trend-withholding FIFO, and the sensitivity-reference / initial-value
valid-current filter (`0.5 <= corrected_re_current <= 50.0`) have been recovered
by black-box perturbation (`tools/caresens-oracle/research/err4_delay_probe.py`),
verified with **0 mismatches** across **1,231 runs / 2,374,726 accepted records**,
and implemented in `CareSensAirDelayFlag.kt`, `CareSensAirCalibration.kt`, and
`CareSensAirCalibrationState.kt` (state `VERSION = 3`, preserving `VERSION = 2`
deserialization).

Task 02's `err1_random_noise_temp_break` remains an isolated boolean input
(`noiseBreak: Boolean = false` on `CareSensAirCalibration.process`).

## Confirmed rules (0 mismatches across 1,231 runs / 2,374,726 accepted records)

### 1. Sensitivity-reference and initial-value current filter

- Contrary to the earlier `e277.py` hypothesis (which correlated with pairs of
  `data_type == 4` records on a dropout trace), controlled state and input
  perturbations (`err4_delay_probe.py --perturbations`) proved that both
  `sum_curr_for_mdc` / `sum_curr_cnt_for_mdc` (`seq 865..2016`) and
  `sum_curr_for_init_value` / `sum_curr_cnt_for_init_value` (`seq 5..24`) are
  **independent** of `data_type == 4`, `err4_delay_flag`, `slope100`, and
  `lot_type`.
- Both accumulators include a record if and only if:
  `0.5 <= corrected_re_current <= 50.0` (nA).

### 2. Wear phases and `diabetes_before_7days_flag` (`b7`)

- `b7` (vendor state offset `10914`) is updated **before** `D0`–`D4` on every
  accepted record using the 12-tap FIR rounded-glucose filter (`err16_CGM_plasma`,
  independent of `err128` revised current) and the day-7 level when `seq >= 2017`
  and `diabetesCount > 288`.
- The three delay-test phases are:
  - **Phase 1 (`seq < 865`):** `D0`, `D3` active.
  - **Phase 2 (`seq >= 865 && !(seq >= 2017 && b7 >= 4)`):** `D0`, `D1`, `D2`, `D3` active.
  - **Phase 3 (`seq >= 2017 && b7 >= 4`):** `D3`, `D4` active.
- This resolves the late-start phase mystery (`e284.py`): after `seq = 2017`,
  runs with `b7 < 4` (including `b7 = 0` when day 3–7 had `<= 288` samples and
  the early accumulator has not yet tripped to `6`, or day-7 level `1..3`)
  remain in Phase 2 instead of switching to Phase 3.

### 3. Delay tests `D0`–`D4`

Let `rawmax = max(tran_inA[0..29])`, `prev_rawmax` = previous accepted record's
`rawmax`, `consecutive = (count > 0 && accu_seq[-1] == seq - 1)`,
`rR = roundAwayFromZero(result_glucose)`, and `t5 = tran_inA_5min` (trimmed
mean of the 5 minute currents before lot-offset subtraction):

| Test | Exact rule | Active phase |
|---|---|---|
| D0 | `consecutive && max(rawmax, prev_rawmax) < 1.1` | Phase 1, Phase 2 (`!phase3`) |
| D1 | `rR < 40 && rawmax < 1.5` | Phase 2 |
| D2 | `rR < 40 && rawmax < 3.0 && rawmax * 100.0 / slope100 < 60.0` | Phase 2 |
| D3 | `rR < 20 || t5 < 0.4` | Phase 1, Phase 2, Phase 3 (all) |
| D4 | `rR < 30 || (rawmax < 0.8 && rawmax * 100.0 / slope100 < 20.0)` | Phase 3 |

### 4. `err4_delay_condi` → `err4_delay_flag` rapid-drop gate

`err4_delay_flag` is **not** simply `any(D0..D4)` for `seq >= 865`:

- If `!any(D0..D4)`: `err4_delay_flag = 0`.
- Else if `seq < 865 || idx == 0 || prev_err4_delay_flag == 1`: `err4_delay_flag = 1`.
- Else (`seq >= 865`, `idx > 0`, `prev_err4_delay_flag == 0`): count prior accepted
  records `n_win` with `accu_seq > 0 && (seq - accu_seq) <= 23` (2-hour window):
  - If `n_win >= 21`: `err4_delay_flag = 1` iff some consecutive pair `(g[k-1], g[k])`
    in the `n_win` prior rounded glucoses followed by `rR` satisfies
    `g[k-1] - g[k] >= 40.0 && g[k] < 100.0`.
  - Else (`n_win < 21`): `err4_delay_flag = 1` iff `D3 == 1 && err_glu_arr[-1] - rR > 50.0`
    (no `< 100` ceiling).

### 5. `data_type == 4` and trend withholding (`err_delay_arr[7]`)

- `data_type == 4` iff `err4_delay_flag || err1_random_noise_temp_break`.
- `err_delay_arr` (vendor offset `17132`, 7 bytes) is a 7-record FIFO of
  `data_type == 4` on accepted records, aligned with history slots `3..9`.
- `trend` is withheld (`100.0`) whenever any entry of `err_delay_arr[0..6]` is `1`.

## Quantified post-day-7 glucose impact (before vs. after valid-current filter)

Measured by `err4_delay_probe.py --glucose-impact` across 1,071 runs (21 fixtures,
900 mixed runs, 150 independent holdouts; 755,272 post-day-7 usable records):

| Suite | Runs affected (before → after) | Post-day-7 records `> 1e-7 mg/dL` (before → after) | Baseline abs median / mean / p95 / max (mg/dL) | Baseline signed range (mg/dL) |
|---|---:|---:|---|---|
| Fixtures (21) | 1 → 0 | 36 / 15,918 → 0 | 0.47 / 0.43 / 0.75 / 0.77 | [−0.77, −0.03] |
| Fresh, seed 101 (300) | 27 → 0 | 31,486 / 228,735 → 0 | 4.55 / 7.78 / 37.22 / 39.48 | [−39.48, +1.04] |
| Fresh, seed 202 (300) | 23 → 0 | 27,451 / 226,052 → 0 | 7.73 / 9.89 / 26.65 / 73.91 | [−73.91, +0.69] |
| Takeover, seed 7 (150) | 6 → 0 | 9,616 / 86,606 → 0 | 5.77 / 11.58 / 34.54 / 45.35 | [−45.35, +0.29] |
| Takeover, seed 8 (150) | 4 → 0 | 2,585 / 98,007 → 0 | 6.01 / 5.92 / 13.49 / 15.38 | [−15.38, +0.11] |
| Holdout fresh, seed 303 (100) | 9 → 0 | 9,860 / 72,482 → 0 | 9.88 / 7.70 / 12.67 / 16.32 | [−16.32, +0.77] |
| Holdout takeover, seed 9 (50) | 1 → 0 | 502 / 27,472 → 0 | 28.81 / 22.64 / 32.41 / 32.69 | [−32.69, −0.10] |

- **Total before fix:** **71 / 1,071 runs** and **81,536 / 755,272 post-day-7 records (10.8 %)**
  had glucose errors exceeding `1e-7 mg/dL`, with under-estimation up to **−73.91 mg/dL**
  (`sweep 202 run 214`, mean −43.45 mg/dL across 326 records), **−45.35 mg/dL** (`takeover 7/49`),
  and **−39.48 mg/dL** (`fresh 101/195`, mean −36.04 mg/dL across 2,662 records).
- **Seed 202, run 127:** 3 of 1,149 day 3–7 records had `corrected_re_current < 0.5`,
  shifting the reference mean from `4.819891 nA` to `4.806419 nA` and shifting all
  2,310 post-day-7 readings by −0.0015 to −0.3090 mg/dL (median −0.2669 mg/dL).
  After the fix, max post-day-7 glucose error is **0.0 mg/dL**, and both the
  `err16_CGM_plasma` shift at seq 2055 and the autonomous `err128` onset boundary
  shift at seq 2361 disappear.
- **Total after fix:** **0 / 1,071 runs** and **0 / 755,272 post-day-7 records**
  differ (`> 1e-7 mg/dL`). In `differential.py`, unclaimed (`exact = false`) usable
  records drop from **272,639** (218,091 fresh + 54,548 takeover) to **0** across
  all 1,050 sweep runs (1,647,921 inputs), with only the two known unrelated
  differences remaining: fresh seed 202 run 16 (record 484, Task 02 `err1` noise
  break) and takeover seed 7 run 99 (record 1425, Task 09).

## Remaining dependency

- **Task 02 (`err1_random_noise_temp_break`):** Pass Task 02's boolean output
  into `CareSensAirCalibration.process(record, noiseBreak)` once decoded.
