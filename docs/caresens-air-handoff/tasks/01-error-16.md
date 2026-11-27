# Task 01: error 16 (sensitivity / end of life)

**Priority:** highest. It is the only unreproduced error that fires on
realistic data, in 7 of 268 synthetic runs, from day 6 on. It latches to the
end of the wear, and the driver withholds readings while it is set.

Setup and conventions: `00-shared-infrastructure.md`. Background:
`../findings.md`, section "Error 16".

## Goal

- Reproduce `err16_condi[0..2]` and the error 16 latch exactly on the fixtures and in the random sweep.
- Document conditions 3–6, or show they cannot be reached without a calibration list.

## Known (exact)

**Inputs.** `W` = the 12 taps in `tools/caresens-oracle/research/err16lib.py`, newest first.

- Plasma: `P = round(Σ W·round(result_glucose))`.
- ISF: `round(Σ W·tran_inA_5min·100/slope100)`.
- Both use the last 12 accepted records and are NaN unless their sequence numbers are consecutive. On a rejected record the debug shows 0.
- Evidence: e213, e214 (impulse responses), e220, e222 (100 % match).

**Latch.** Permanent once any condition is 1. A rejected record reports 0 for itself only.

**Cadence** (e201): see the table in `../findings.md`, "Error 16".

**Firing.** Conditions 0 and 2 can start from seq 1728, condition 1 from 2019.

## Where it fires

- Fixtures, under `Common/src/test/resources/caresens/`:

  | Fixture | First record |
  |---|---|
  | `life-lot2-sensitivity-loss` | 2637 |
  | `holdout-life-other-lot1` | 1893 |
  | `holdout-late-takeover-lows` | 1948 |

- Random runs 67, 101 and 175 in `err_survey.json`; each entry has its spec.
- Transforms (`stress.py`): `step_down` (1031), `slow_drop`, `sat`.
- 160 decay runs, from `e226.py` / `e228.py` (`random.Random(11)`):

  | File | Contents |
  |---|---|
  | `err16_ds.npz` | 198 560 rows, 38 scalars and 7 condition labels; regenerate, 65 MB |
  | `err16_tr.pkl` | 205 transitions with every scalar before and after; committed |

## State (vendor block offsets)

| Field | Offset | Notes |
|---|---|---|
| `err16_CGM_ISF_smooth[865]` | 51648 | ISF history, 3 days |
| `err16_CGM_plasma[36]` | 58568 | plasma history |
| `err16_CGM_ISF_roc_value[577]`, `roc_steady[36]`, `roc_min`, `roc_min_temp[865]`, `roc_diff[36]`, `roc_ratio[36]` | 58856–71279 | rate statistics |
| `err16_CGM_ISF_trend_min_*`, `_mode_*`, `_mean_*` (`*_value_arr[865]`, `*_max_temp[865]`, slope/rsq/diff/ratio[36], `*_early`) | 71280–117279 | trend statistics |
| `err16_dt_arr[36]`, `err16_time5_first` | 49176–49471 | |
| `err16_cal_cons_*`, `err16_cal_day_*` | 49472–51647 | calibration consistency, probably conditions 3–6 |
| `err16_result_prev` | 117280 | latch |

The debug record carries the scalar of each (offsets 1251–1554) and
`err16_condi[7]` at 1555. The 36-entry arrays are probably per-two-hour
summaries; that is a guess from the size.

## Rejected

- Conjunctions or trees over the 38 exposed scalars (`rules.py`, `cart.py`, e227, e229). See the best partial fits in `../findings.md`.
- Many transitions coincide with no threshold crossing, so the rules use something not exposed in the debug record: a previous value, an array element or a schedule.

## Suspected dependencies

- **Device parameters `err6_CGM_*`.** Defaults:

  | Parameter | Default |
  |---|---|
  | rpd | 0.55 |
  | slp | −0.2 |
  | low3d_seq / low3d_p | 24 / 0.32 |
  | low1d_seq / low1d_p | 24 / 0.3 |
  | prct | {30, 50, 70} |
  | day | {1, 3} |
  | BLE_bad | {12, 96} |
  | poly2 | 0.7 |

  The names fit the statistics: percentiles, low over 3 days and over 1 day, a quadratic. The [task 00 survey](../device-parameters.md) found unchanged bit-16 traces for every `err6_*` variation.
- **Calibration list.** Conditions 3–6 probably need one; JugglucoNG never passes it.
- Not data type 4: ISF uses the 5-minute current, which type 4 does not change.

## Suggested next steps

1. Use the `err6_*` defaults as candidate thresholds. Revisit device-parameter
   sensitivity if a new case changes an internal condition or the emitted bit.
2. **Decode each statistic from its state array** by state perturbation, the same way plasma was decoded:
   - take a snapshot before record k
   - set one element of, for example, `err16_CGM_ISF_trend_min_value_arr`
   - run record k and see which debug scalars change
   - repeat per array and per statistic: min, mode, mean, then slope and rsq (probably a regression over the array), ratio and diff (probably against `*_max` / `*_early`)

   Keep `REF.run(..., state=copy)` in mind; it mutates its argument.
3. **Reimplement the statistics in Python** and check them against the debug record on `err16_ds` regenerated from `e226.py`.
4. **Fit the condition rules** with the full internal values: the current value and the previous record's, plus the array elements the statistic uses. Expect thresholds equal to `err6_*` defaults or simple fractions of them.
5. **Conditions 3–6:**
   - pass a calibration list (`air1_opcal4_cal_list_t`) in the oracle and see whether they ever fire
   - if only with calibrations, document it, model them as never firing, and add a test that an empty list never fires them

## Implementation and tests

- New `CareSensAirError16.kt` following task 00 C.
- Import the arrays above in `CareSensAirVendorState.read`; about 50 KB of the block.
- Add bit 16 to `modelledErrors` and `MODELLED`.
- The three firing fixtures already exist; add a holdout decay fixture with another seed.
- Sweep check: a per-bit table with 0 mismatches for bit 16 over 600 fresh and 300 takeover runs.
