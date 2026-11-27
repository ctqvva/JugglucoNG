# Task 02: error 1 (noise) and its noise break

Error 1 is the noise detector. Its by-product, `err1_random_noise_temp_break`,
is half of data type 4 (task 05). This task owns both. Task 05 consumes the
noise break and must not decode it separately.

Setup and conventions: `00-shared-infrastructure.md`. Background:
`../findings.md`, section "Error 1".

## Goal

Exact `err1_result`, the error 1 latch, and `err1_random_noise_temp_break`.
The TD (temporary break) parts are included if they feed either.

## Known

- **Latch:** shared rule, `cond || (prev && consecutive && accepted)`. A rejected record or a sequence gap clears it (e267–e269).
- **Triggers** (`stress.py`, base seed 21, transform on records 500–700): `noise200`, `noise1000`, `alt`, `spike1`, `ramp_in_rec`, `dropouts`, `halfrec`. The first error 1 is at record 511–543.
- **Noise break.** It tracks `err1_i_sse_d_mean`, roughly between 2.54 and 24.7, against the adaptive `err1_th_sse_d_mean1/2` (e292, CART). Not exact.
- **Random sweep.** The break fires in noisy random runs too. Fresh sweep seed 202, run 16 (start 600, noise 50, dropouts) differs on the trend at record 484 because of it.

## State

| Field | Offset |
|---|---|
| `err1_th_sse_d_mean1/2`, `err1_th_sse_d_mean`, `err1_th_diff1/2`, `err1_th_diff` | 19448–19495 |
| `err1_n`, `err1_isfirst0..2` | 19496–19500 |
| `err1_prev_last_1min_curr` | 19504 |
| `err1_is_contact_bad1h[100]` | 19512 |
| `err1_i_sse_d_mean4h[100]` | 19616 |
| `err1_current_avg_diff_prev[100]` | 20416 |
| `err1_SG_1min[180]`, `err1_time_1min[180]`, `err1_inA_1min[180]` | 21216, 22656, 23376 |
| `err1_result_prev` | 24816 |
| `err1_TD_temporary_break_flag_past_range[36]`, `err1_sum_result_condition_TD`, `err1_any_result_condition_TD` | 24817–24856 |

Debug (989–1112): `err1_i_sse_d_mean`, the thresholds, `err1_is_contact_bad`,
`err1_current_avg_diff`, `err1_length_t*_max/trio`, `err1_result_TD`,
`err1_result_condition_TD[2]`, `err1_TD_*`.

## Suspected dependencies

- **Device parameters `err1_*`.** Defaults:

  | Parameter | Default |
  |---|---|
  | seq | {23, 47, 11} |
  | contact_bad | 0.5 |
  | th_diff | 2 |
  | th_sse_dmean | {0.05, 0.1, 0.5} |
  | th_n1 | {43, 40, 37, 34} |
  | th_n2 | {{2, 6}, {4, 8}} |
  | n_consecutive | 6 |
  | i_sse_dmean_now | {3, 0.001} |
  | count_sse_dmean | 12 |
  | n_last | 288 |
  | multi | {10, 10} |
  | current_avg_diff | 1e-15 |

  The [task 00 survey](../device-parameters.md) found unchanged bit-1 traces
  for these variations on its noise trigger. The noise-break sub-test remains open.
- **Minute buffers.** `err1_SG_1min` / `err1_inA_1min` hold 180 minutes (36 records) of per-minute values, probably the engine's `minuteCurrents` and a per-minute glucose. Check which.
- **Thresholds.** `th_sse_d_mean1/2` adapt over 4 h (`i_sse_d_mean4h[100]`).

## Suggested next steps

1. **Decode `err1_i_sse_d_mean`.** It is probably a sum of squared errors of the minute currents against a fit, with the mean of the differences. Perturb one minute of one record and look at the debug value.
   - Compare with the engine's `minuteCurrents`.
   - Compare with `err1_inA_1min` as stored in the state.
2. **Decode the threshold update** from `err1_i_sse_d_mean4h` and `err1_isfirst*` / `err1_n`. Set the state arrays and run one record.
3. **Fit the noise break and `err1_result`** on these decoded values, with the `err1_*` defaults as candidate thresholds.
4. The TD trio (`err1_TD_time_trio` / `value_trio`, `length_t*`) looks like a temporary-break detector over three points. Decode it only if it feeds the result or the break.

## Implementation and tests

- Add `CareSensAirError1.kt` (task 00 C), and import the fields listed above.
- Expose the noise break to the data type 4 logic, which task 05 owns.
- Add a noisy fixture, for example `noise200` on the base spec. You can also re-use the random spec of seed 202, run 16 as a holdout fixture.
- Add bit 1 to `modelledErrors` and `MODELLED`.
