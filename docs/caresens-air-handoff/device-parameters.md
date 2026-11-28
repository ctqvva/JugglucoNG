# CareSens Air device-parameter sensitivity

Task 00 ran 212 distinct one-field perturbations over nine synthetic scenarios
(1,908 perturbed runs plus nine controls). The sweep covered all 89 elements
of the `err*` fields, `cal_noise` and `sensor_version` in the packed device
block. The controls use five existing stress transforms and four existing
fixture specs. Each trial starts with fresh vendor state and the same input
records as its control.

No perturbed field changed an emitted error-bit trace. This is evidence of
output insensitivity for exercised bits 1, 2, 4, 16, 32 and 64 within these
cases. Bits 8 and 128 stayed inactive, so their device dependencies remain
unknown. The survey observes error output only. Internal conditions, other
outputs, parameter interactions, and behavior with a different vendor build
or device block can still differ. Task 00 leaves device-block plumbing
unchanged. A detector task should revisit this only if its own triggering
inputs show an effect.

Run from the repository root after setting `CARESENS_REFERENCE_LIB` to the
pinned arm64 library:

```sh
python3 tools/caresens-oracle/research/device_parameter_survey.py
```

Each array element is changed in isolation. Byte values use ±1 and 0.
Other values use ×0.5 and ×2, truncating integers and using ±1 for zero-valued
floats. Values outside the field's storage range and duplicate values are
skipped. The scenario-specific calibration values stay in place. For inputs
with multiple results the table uses the same last output as the driver.

The control cells are **first input record/count**, with records numbered
from 1. `–` means the bit stayed clear. For perturbations, `=` means the
bit fired in at least one control and none of its traces changed. `?` means
no trigger was observed in any control or trial. A changed trace would be
listed even if its first firing and count stayed the same.

Reference SHA-256 `0bc4e28b98b8ba4e9ea47db0f4cf413febf596eb94bfe87162432031cd02d4cf`.

## Default-block controls

| Scenario | 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| noise200 | 543/958 | – | – | – | – | – | – | – |
| high3 | – | 512/989 | – | – | – | – | – | – |
| sat | – | 506/995 | – | – | 1415/86 | – | – | – |
| low30 | – | – | 504/997 | – | – | – | – | – |
| step_down | – | – | 504/997 | – | 1032/469 | – | – | – |
| life-lot2-sensitivity-loss | – | – | – | – | 2638/1683 | – | – | – |
| holdout-life-other-lot1 | – | – | – | – | 1894/2427 | – | – | – |
| clock-jump | – | – | – | – | – | 701/500 | – | – |
| invalid-parameters | – | – | – | – | – | – | 1/50 | – |

## One-field perturbations

| Device field | Default | Values tried | 1 | 2 | 4 | 8 | 16 | 32 | 64 | 128 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `sensor_version` | 1 | 0, 2 | = | = | = | ? | = | = | = | ? |
| `cal_noise` | 3 | 1.5, 6 | = | = | = | ? | = | = | = | ? |
| `errcode_version` | 2 | 0, 1, 3 | = | = | = | ? | = | = | = | ? |
| `err1_seq[0]` | 23 | 0, 22, 24 | = | = | = | ? | = | = | = | ? |
| `err1_seq[1]` | 47 | 0, 46, 48 | = | = | = | ? | = | = | = | ? |
| `err1_seq[2]` | 11 | 0, 10, 12 | = | = | = | ? | = | = | = | ? |
| `err1_contact_bad` | 0.5 | 0.25, 1 | = | = | = | ? | = | = | = | ? |
| `err1_th_diff` | 2 | 1, 4 | = | = | = | ? | = | = | = | ? |
| `err1_th_sse_dmean[0]` | 0.050000001 | 0.025, 0.1 | = | = | = | ? | = | = | = | ? |
| `err1_th_sse_dmean[1]` | 0.1 | 0.050000001, 0.2 | = | = | = | ? | = | = | = | ? |
| `err1_th_sse_dmean[2]` | 0.5 | 0.25, 1 | = | = | = | ? | = | = | = | ? |
| `err1_th_n1[0]` | 43 | 0, 42, 44 | = | = | = | ? | = | = | = | ? |
| `err1_th_n1[1]` | 40 | 0, 39, 41 | = | = | = | ? | = | = | = | ? |
| `err1_th_n1[2]` | 37 | 0, 36, 38 | = | = | = | ? | = | = | = | ? |
| `err1_th_n1[3]` | 34 | 0, 33, 35 | = | = | = | ? | = | = | = | ? |
| `err1_th_n2[0][0]` | 2 | 0, 1, 3 | = | = | = | ? | = | = | = | ? |
| `err1_th_n2[0][1]` | 6 | 0, 5, 7 | = | = | = | ? | = | = | = | ? |
| `err1_th_n2[1][0]` | 4 | 0, 3, 5 | = | = | = | ? | = | = | = | ? |
| `err1_th_n2[1][1]` | 8 | 0, 7, 9 | = | = | = | ? | = | = | = | ? |
| `err1_n_consecutive` | 6 | 0, 5, 7 | = | = | = | ? | = | = | = | ? |
| `err1_i_sse_dmean_now[0]` | 3 | 1.5, 6 | = | = | = | ? | = | = | = | ? |
| `err1_i_sse_dmean_now[1]` | 0.001 | 0.00050000002, 0.0020000001 | = | = | = | ? | = | = | = | ? |
| `err1_count_sse_dmean` | 12 | 0, 11, 13 | = | = | = | ? | = | = | = | ? |
| `err1_n_last` | 288 | 144, 576 | = | = | = | ? | = | = | = | ? |
| `err1_multi[0]` | 10 | 0, 9, 11 | = | = | = | ? | = | = | = | ? |
| `err1_multi[1]` | 10 | 0, 9, 11 | = | = | = | ? | = | = | = | ? |
| `err1_current_avg_diff` | 1e-15 | 5e-16, 2e-15 | = | = | = | ? | = | = | = | ? |
| `err2_start_seq` | 289 | 144, 578 | = | = | = | ? | = | = | = | ? |
| `err2_seq[0]` | 20 | 0, 19, 21 | = | = | = | ? | = | = | = | ? |
| `err2_seq[1]` | 11 | 0, 10, 12 | = | = | = | ? | = | = | = | ? |
| `err2_seq[2]` | 6 | 0, 5, 7 | = | = | = | ? | = | = | = | ? |
| `err2_glu` | 800 | 400, 1600 | = | = | = | ? | = | = | = | ? |
| `err2_cv[0]` | 10 | 5, 20 | = | = | = | ? | = | = | = | ? |
| `err2_cv[1]` | 0.050000001 | 0.025, 0.1 | = | = | = | ? | = | = | = | ? |
| `err2_cv[2]` | 10 | 5, 20 | = | = | = | ? | = | = | = | ? |
| `err2_cummax` | 2 | 0, 1, 3 | = | = | = | ? | = | = | = | ? |
| `err2_multi` | 10 | 0, 9, 11 | = | = | = | ? | = | = | = | ? |
| `err2_ycept` | 2 | 1, 4 | = | = | = | ? | = | = | = | ? |
| `err2_alpha` | 2 | 1, 4 | = | = | = | ? | = | = | = | ? |
| `err345_seq1[0]` | 3 | 1, 6 | = | = | = | ? | = | = | = | ? |
| `err345_seq1[1]` | 576 | 288, 1152 | = | = | = | ? | = | = | = | ? |
| `err345_seq2` | 5 | 0, 4, 6 | = | = | = | ? | = | = | = | ? |
| `err345_seq3[0]` | 5 | 2, 10 | = | = | = | ? | = | = | = | ? |
| `err345_seq3[1]` | 864 | 432, 1728 | = | = | = | ? | = | = | = | ? |
| `err345_seq3[2]` | 24 | 12, 48 | = | = | = | ? | = | = | = | ? |
| `err345_seq4[0]` | 11 | 5, 22 | = | = | = | ? | = | = | = | ? |
| `err345_seq4[1]` | 23 | 11, 46 | = | = | = | ? | = | = | = | ? |
| `err345_seq4[2]` | 12 | 6, 24 | = | = | = | ? | = | = | = | ? |
| `err345_seq4[3]` | 288 | 144, 576 | = | = | = | ? | = | = | = | ? |
| `err345_seq4[4]` | 24 | 12, 48 | = | = | = | ? | = | = | = | ? |
| `err345_seq5[0]` | 11 | 5, 22 | = | = | = | ? | = | = | = | ? |
| `err345_seq5[1]` | 36 | 18, 72 | = | = | = | ? | = | = | = | ? |
| `err345_seq5[2]` | 288 | 144, 576 | = | = | = | ? | = | = | = | ? |
| `err345_raw[0]` | 0.1 | 0.050000001, 0.2 | = | = | = | ? | = | = | = | ? |
| `err345_raw[1]` | 0.5 | 0.25, 1 | = | = | = | ? | = | = | = | ? |
| `err345_raw[2]` | 0.2 | 0.1, 0.40000001 | = | = | = | ? | = | = | = | ? |
| `err345_raw[3]` | 0.69999999 | 0.34999999, 1.4 | = | = | = | ? | = | = | = | ? |
| `err345_filtered[0]` | 0.2 | 0.1, 0.40000001 | = | = | = | ? | = | = | = | ? |
| `err345_filtered[1]` | 1 | 0.5, 2 | = | = | = | ? | = | = | = | ? |
| `err345_min[0]` | 0.5 | 0.25, 1 | = | = | = | ? | = | = | = | ? |
| `err345_min[1]` | 0.30000001 | 0.15000001, 0.60000002 | = | = | = | ? | = | = | = | ? |
| `err345_range` | -1 | -2, -0.5 | = | = | = | ? | = | = | = | ? |
| `err345_n_range` | 2 | 0, 1, 3 | = | = | = | ? | = | = | = | ? |
| `err345_md` | 0 | -1, 1 | = | = | = | ? | = | = | = | ? |
| `err345_n_md` | 6 | 3, 12 | = | = | = | ? | = | = | = | ? |
| `err6_cal_n_pts` | 3 | 0, 2, 4 | = | = | = | ? | = | = | = | ? |
| `err6_cal_basic_prct` | 0.30000001 | 0.15000001, 0.60000002 | = | = | = | ? | = | = | = | ? |
| `err6_cal_basic_seq` | 1440 | 720, 2880 | = | = | = | ? | = | = | = | ? |
| `err6_cal_origin_slope` | 30 | 15, 60 | = | = | = | ? | = | = | = | ? |
| `err6_cal_in_vitro[0]` | 0 | -1, 1 | = | = | = | ? | = | = | = | ? |
| `err6_cal_in_vitro[1]` | 2 | 1, 4 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_rpd` | 0.55000001 | 0.27500001, 1.1 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_slp` | -0.2 | -0.40000001, -0.1 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_low3d_seq` | 24 | 0, 23, 25 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_low3d_p` | 0.31999999 | 0.16, 0.63999999 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_low1d_seq` | 24 | 0, 23, 25 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_low1d_p` | 0.30000001 | 0.15000001, 0.60000002 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_prct[0]` | 30 | 0, 29, 31 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_prct[1]` | 50 | 0, 49, 51 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_prct[2]` | 70 | 0, 69, 71 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_day[0]` | 1 | 0, 2 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_day[1]` | 3 | 0, 2, 4 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_BLE_bad[0]` | 12 | 6, 24 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_BLE_bad[1]` | 96 | 48, 192 | = | = | = | ? | = | = | = | ? |
| `err6_CGM_poly2` | 0.69999999 | 0.34999999, 1.4 | = | = | = | ? | = | = | = | ? |
| `err32_dt[0]` | 23 | 0, 22, 24 | = | = | = | ? | = | = | = | ? |
| `err32_dt[1]` | 60 | 0, 59, 61 | = | = | = | ? | = | = | = | ? |
| `err32_n[0]` | 3 | 0, 2, 4 | = | = | = | ? | = | = | = | ? |
| `err32_n[1]` | 2 | 0, 1, 3 | = | = | = | ? | = | = | = | ? |

## Changed traces

No bit trace changed.
