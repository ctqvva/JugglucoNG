# Task 05: data type 4 (low-signal flag) and the error 4 delay tests

Setup and conventions: `00-shared-infrastructure.md`. Background:
`../findings.md`, sections "Data type 4" and "Error 4" (delay tests).

## Why it matters

Type 4 is more than a label:

- It withholds the trend.
- The day 3–7 sensitivity reference skips a record when it and the previous accepted record are both type 4. So it changes every corrected current after day 7.

The engine does not emit type 4. It marks `exact = false` on a superset
instead, which leaves about 15 % of sweep records unclaimed.

## Goal

- Emit data type 4 exactly.
- Apply the reference skip.
- Remove the `maybeLowFlag`, `historyMaybeFlagged` and `referenceMaybeFlagged` stand-ins.
- Then the inexact share in the sweep should fall to the filling-only level (about 1.6 % before error 4 work).

## Known (exact)

- `data_type 4 = err1_random_noise_temp_break || any(err4_delay_condi[0..4])` (e248, 0 mismatches in 255 858 records).
- **Reference skip** (e277, exact count and sum): `sum_curr_for_mdc` (10976) and `sum_curr_cnt_for_mdc` (10984) leave out a record when it and the previous accepted record are type 4. Related state: `mdc_start_idx` (11018), `mdc_first_flag` (11020).
- **Delay tests:**

  | Test | Rule | Phase | Exactness |
  |---|---|---|---|
  | D0 | max(rawmax, rawmax of the previous consecutive record) < 1.1 | seq < 2017 | exact for start-1 runs |
  | D1 | round(init) < 40 ∧ rawmax < 1.5 | 865–2016 | 464 mismatches, all phase-related |
  | D2 | round(R) < 40 ∧ rawmax < 3.0 | 865–2016 | 30 mismatches, superset |
  | D3 | round(R) < 20 ∨ round(init) < 20 | all | 76 mismatches as a pure R test |
  | D4 | round(R) < 30 | seq ≥ 2017 | exact |

  Rawmax is the record's raw maximum, R is `result_glucose` and init is `init_cg`.
- **History.** `err4_delay_flag_arr[576]` (48248) holds 2 days of the flag. The engine imports entries 566–575 as `historyMaybeFlagged`.

## Open

- **Phase after day 7 for late-start runs** (e284).
  - In runs starting at seq 600 or 1500, D0 stays on after seq 2017 for some low records and not others.
  - D4 starts at 2017. Slot 0 in phase 3 seems to be a different test.
  - Untested: whether phases count seq, record index, or records since start.
- **D1 and D2 mismatches.** Re-fit them with the phase fixed.
- **The noise-break half.** It comes from task 02; do not decode it here. Until task 02 lands, keep `exact = false` for the noise part. Candidate: any record where task 02's partial model cannot rule the break out.

## Upstream glucose dependency for Task 01

In the mixed replay with vendor revised current supplied, 59 of 900 runs
still differed in the error-16 plasma statistic. Every condition and latch
matched. The plasma filter uses rounded glucose. On seed 202, run 197,
input 528 (sequence 2030), the same filter produced 177 from the vendor's
rounded readings and 176 from the open engine's. See the
[Task 01 replay results](../error-16-evidence.md#replay-after-the-statistic-and-rule-fixes).

Task 05 owns investigation of the upstream glucose calculation. Compare
which accepted records contribute to the day 3–7 sensitivity reference,
then check its sum and count at the day-seven transition. Follow any
divergence through sensitivity compensation, corrected current and final
glucose. The reference skip and post-day-seven phase are starting points
for the investigation. The replay establishes the glucose input difference,+and the first differing upstream stage remains open.

From the repository root, with the oracle environment set up as in Task 00:

```sh
python3 tools/caresens-oracle/research/err16_replay.py \
  --sweep-case 202 197 --revised-current --details
```

## Datasets and scripts

- `c0runs.pkl` (`e273.py`, 80 noisy-dropout runs) holds D, C, raw max/mean, 5min, R and init per record.
- `d1X.npy` / `d1Y.npy` (`e280.py`) hold the D1 tree data. `d2rows.npy` (`e263.py`) holds D2.
- Fixtures `dropouts` and `holdout-dropouts-late` contain many type 4 records. The late holdout used to fail at seq 2017 without the reference skip.

## Suggested next steps

1. On late-start specs (starts 30, 600, 1500, 3000), tabulate D0–D4 against seq, record index and `idx` / `idx_origin_seq` (state offsets 1608 / 8) to find what the phase counts.
2. Re-fit D0–D2 in each phase; the tree in `cart.py` is enough.
3. Implement the flag, the trend suppression and the reference skip together. Import `err4_delay_flag_arr` and the `mdc` fields for takeover.

## Implementation and tests

- Engine:
  - `dataType = 4` output
  - the trend withheld when any of history entries 3–9 is type 4 (check the window)
  - the reference skip in the sensitivity compensation
- The fixture test compares `dataType` for 0, 2 and 4.
- `differential.py` compares data type 4 and keeps `exact` only for filling.
- Coordinate with task 02 on one interface: `noiseBreak: Boolean` per record.
