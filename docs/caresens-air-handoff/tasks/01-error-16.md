# Task 01: error 16 (sensitivity / end of life)

**Priority:** highest. It is the only unreproduced error that fires on
realistic data, in 7 of 268 synthetic runs, from day 6 on. It latches to the
end of the wear, and the driver withholds readings while it is set.

Setup and conventions: `00-shared-infrastructure.md`. Background:
`../findings.md`, section "Error 16". New replay measurements are in
[error-16-evidence.md](../error-16-evidence.md).

The rules for conditions 0–2 now match on vendor histories with an empty
calibration list. The Kotlin prototype matches the decay corpus and firing
fixtures. Its raw-input comparisons still diverge in the mixed sweeps.
The current-revision stage from task 07 is an input dependency.

## Goal

- Reproduce `err16_condi[0..2]` and the error 16 latch exactly on the fixtures and in the random sweep.
- Document conditions 3–6, or show they cannot be reached without a calibration list.

## Known (exact)

**Inputs.** `W` = the 12 taps in `tools/caresens-oracle/research/err16lib.py`, newest first.

- Plasma: `P = round(Σ W·round(result_glucose))`.
- ISF: `round(Σ W·revised_current·100/slope100)`, using
  `err128_CGM_c_noise_revised_value` at offset 48888. Most of the earlier
  traces had `revised_current == tran_inA_5min`.
- Both use the last 12 accepted records and are NaN unless their sequence numbers are consecutive. On a rejected record the debug shows 0.
- Evidence: e213, e214 (impulse responses), e220, e222 (100 % match).

**Latch.** Permanent once any condition is 1. A rejected record reports 0 for itself only.

**Cadence** (e201): see the table in `../findings.md`, "Error 16".

**Firing.** Conditions 0 and 2 can start from seq 1728. Condition 1 was
observed at seq 2018 in decay run 123 with an empty calibration list.

## Where it fires

- Fixtures, under `Common/src/test/resources/caresens/`:

  | Fixture | First record |
  |---|---|
  | `life-lot2-sensitivity-loss` | 2638 |
  | `holdout-life-other-lot1` | 1894 |
  | `holdout-late-takeover-lows` | 1949 |
  | `holdout-err16-decay` | 1857 |

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
| `err16_CGM_ISF_roc_n`, `roc_value[577]`, `roc_steady[36]`, `roc_min`, `roc_min_temp[865]`, `roc_diff[36]`, `roc_ratio[36]` | 58856–71279 | rate statistics |
| `err16_CGM_ISF_trend_min_*`, `_mode_*`, `_mean_*` (`*_value_arr[865]`, `*_max_temp[865]`, slope/rsq/diff/ratio[36], `*_early`) | 71280–117279 | trend statistics |
| `err16_dt_arr[36]`, `err16_time5_first` | 49176–49471 | |
| `err16_cal_cons_*`, `err16_cal_day_*` | 49472–51647 | calibration consistency, probably conditions 3–6 |
| `err16_result_prev` | 117280 | latch |

The debug record carries the scalar of each (offsets 1251–1554) and
`err16_condi[7]` at 1555. Conditions 0–2 inspect the newest 24 entries in
the 36-entry statistic histories.

## Rejected

- Conjunctions or trees over the 38 exposed scalars (`rules.py`, `cart.py`, e227, e229). See the best partial fits in `../findings.md`.
- A scalar threshold crossing alone does not explain many transitions. The
  recovered conditions use a history of statistic values and the pre-record
  diabetes profile.

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
- Data type 4 alone cannot determine whether current is revised. The
  [source experiment](../error-16-evidence.md#the-actual-isf-input) has
  both a type-0 input with a revised current and a type-4 interval
  with ordinary current.

## Suggested next steps

1. Use the `err6_*` defaults as candidate thresholds. Revisit device-parameter
   sensitivity if a new case changes an internal condition or the emitted bit.
2. Use the [source experiment](../error-16-evidence.md#the-actual-isf-input)
   to verify the revised current as a separate per-record input.
3. Validate statistic history updates across gaps and late starts. The
   comparison with vendor snapshots in `err16_rules.py` already verifies
   the decision rules.
4. Verify both the per-condition and latch outputs with `err16_replay.py`
   before moving the detector into the application engine.
5. **Conditions 3–6:**
   - pass a calibration list (`air1_opcal4_cal_list_t`) in the oracle and see whether they ever fire
   - if only with calibrations, document it, model them as never firing, and add a test that an empty list never fires them

## Implementation and tests

- Promote the research prototype to `CareSensAirError16.kt` after full
  parity, following task 00 C.
- Import the arrays above in `CareSensAirVendorState.read`; about 50 KB of the block.
- Add bit 16 to `modelledErrors` and `MODELLED`.
- The three original firing fixtures and `holdout-err16-decay` cover the
  three reachable conditions.
- Sweep check: a per-bit table with 0 mismatches for bit 16 over 600 fresh and 300 takeover runs.
