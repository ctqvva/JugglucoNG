# Task 03: error 2 (sustained high signal)

Setup and conventions: `00-shared-infrastructure.md`. Background:
`../findings.md`, section "Error 2".

## Goal

Exact `err2_condi[0..1]`, the delay sub-tests and the error 2 latch.

## Known

- **Latch:** shared rule; a rejected record or a sequence gap clears it.
- **Triggers** (`stress.py`): `high3` (first at 511) and `sat` (505).
- **Plateau experiments** (e290, e291, with `craft.run_plateau`):
  - `err2_condi[0]` turns on at about the 11th record of a high plateau.
  - `err2_crt_glu[0]`: glucose > ~800 (819 fired, 729 did not).
  - `err2_crt_cv`: the CV% of the 5-minute current over 12 records, below ~10.
  - `err2_crt_current[0]` depends on slope100. At slope100 7 it never turned on up to 60 nA. Unresolved.
- **Defaults.** The device defaults `err2_glu` 800, `err2_cv[0]` 10 and `err2_seq[1]` 11 equal what was measured. That makes a parameter dependence likely.

## State

| Field | Offset |
|---|---|
| `err2_delay_condi_prev` | 24857 |
| `err2_delay_flag_prev[575]` | 24858 |
| `err2_delay_roc_prev[575]`, `err2_delay_slope_sharp_prev[575]`, `err2_delay_glucosevalue_prev[575]` | 25440, 30040, 34640 |
| `err2_delay_roc/slope/glu_cummax_prev` | 39240–39263 |
| `err2_delay_pre_condi_prev[3]` | 39264 |
| `err2_delay_revised_value_prev` | 39272 |
| `err2_cummax` | 39280 |
| `err2_cummax_foretime[100]` | 39288 |
| `err2_result_prev` | 40088 |

Debug (1113–1213): `err2_delay_*` (revised value, roc, slope_sharp, cummax,
trimmed means, pre_condi[3], condi[3], flag), `err2_cummax`,
`err2_crt_current[2]`, `err2_crt_glu[2]`, `err2_crt_cv`, `err2_condi[2]`.

## Suspected dependencies

- **Device parameters `err2_*`.** Defaults:

  | Parameter | Default |
  |---|---|
  | start_seq | 289 |
  | seq | {20, 11, 6} |
  | glu | 800 |
  | cv | {10, 0.05, 10} |
  | cummax | 2 |
  | multi | 10 |
  | ycept | 2 |
  | alpha | 2 |

  `ycept` and `alpha` look like the slope100-dependent current limit, `crt_current`. A candidate is current > ycept + alpha·slope100, or similar. The [task 00 survey](../device-parameters.md) found unchanged bit-2 traces for these variations on `high3` and `sat`.
- **Phase.** Probably active only from `err2_start_seq` 289 (day 1); check it.
- **Delay sub-test.** The 575-record histories (2 days) hold rate of change, sharp slope and glucose, so the delay part is a separate test over 2 days.

## Suggested next steps

1. Bisect `crt_current` on near-threshold plateaus at several slope100 values. Compare this debug flag under `err2_ycept` / `err2_alpha` perturbations if the plateaus expose a dependency the survey could not see.
2. Confirm `crt_glu` and `crt_cv`. Check:
   - whether the glucose is R, init or the rescaled value
   - whether the CV uses 12 records (`err2_seq[1]`+1?) or `err2_seq[0]`
   - whether `err2_cv[1]` (0.05) is the limit on a second CV
3. Decode `condi[1]` and the delay chain (pre_condi, condi, flag) with ramps rather than plateaus.

## Implementation and tests

- Add `CareSensAirError2.kt` (task 00 C).
- Add a high-plateau fixture (`high3` and `sat` transforms) and a holdout at another slope100.
- Add bit 2 to `modelledErrors` and `MODELLED`.
