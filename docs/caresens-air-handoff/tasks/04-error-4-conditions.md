# Task 04: error 4 conditions 1–4

Condition 0 and the latch are implemented and exact. This task covers the
other four conditions of `err4_condi[5]`.

Setup and conventions: `00-shared-infrastructure.md`. Background:
`../findings.md`, section "Error 4".

The delay tests D0–D4 (`err4_delay_condi`) belong to task 05, because they
only feed data type 4. The two tasks share the `err4_*` state import. Task
04 owns `err4_min_prev`, `err4_range_prev` and `err4_min_diff_prev`; task
05 owns `err4_delay_flag_arr`.

## Goal

- Exact conditions 1–3.
- Condition 4 documented, or shown unreachable.
- Then drop the `other_low` exclusion in `differential.py`, which stops comparing bit 4 from the first record where conditions 1–4 fired.

## Known

- **Engine.** Condition 0 is in `CareSensAirCalibration.process` (`lowRawMax`, `lowRawCount`, `lowError`). The latch is shared with errors 1 and 2. Bits 1–4 are OR-ed into the same bit 4.
- **Condition 1:** a relative fall against the past day's level. It fires in late-start runs (start seq 1500 or 3000) at a 5min/max ratio of 0.32–0.61.
  - Dataset: `c1runs.pkl` from `e285.py` (`random.Random(9)`, 120 runs). It holds `err4_condi`, `err4_range`, `err4_min`, `err4_min_diff`, the 5-minute current and the type.
- **Conditions 2 and 3:** negative-current tests that fire from seq 865 on sustained very low signals (ADC scaled ×0.25 or ×0.4). Their limits scale with the level before the plateau (e254). In every test so far, condition 0 had already fired.
- **Condition 4:** never seen.
- **Device parameters.** All are ignored by error 4 (e237), so thresholds are constants. The `err345_*` defaults are still the best candidates for those constants: `filtered` {0.2, 1.0}, `min` {0.5, 0.3}, `range` −1, `n_range` 2, `md` 0, `n_md` 6, and the seq tables `err345_seq1..5`.

## State

| Field | Offset | Contents |
|---|---|---|
| `err4_inA[390]` | 40096 | raw, last 13 records |
| `err4_min_prev[289]` | 43216 | |
| `err4_range_prev[51]` | 45528 | |
| `err4_min_diff_prev[289]` | 45936 | |
| `err4_result_prev` | 48824 | |

Debug: `err4_min` (running minimum of the 5-minute current), `err4_range`
(5min[i] − 5min[i−1]), `err4_min_diff` (min − min_prev), and
`err4_condi[5]`.

## Suggested next steps

1. **Condition 1.**
   - On `c1runs.pkl`, compute candidates: the current against the maximum, the median or the mean of `err4_min_prev` over 288 records, with ratio thresholds from `err345_*` (0.3, 0.5, …).
   - Separate the conditions by start seq; phases seem to follow `err345_seq*`.
2. **Conditions 2 and 3.**
   - Use plateaus that go negative without tripping condition 0 first. Condition 0 needs a max < 0.5/0.7 nA, so use a plateau whose raw max stays above that while the 5-minute or filtered current goes negative: alternate high and very negative samples within a record.
   - Then bisect against the pre-plateau level.
3. **Condition 4.** Try the remaining shapes: a range collapse (`err4_range` near 0 for `err345_n_range` records) and a sustained `min_diff`.

## Implementation and tests

- Extend the existing error 4 code; do not move condition 0.
- Add fixtures:
  - a late-start fall for condition 1, for example the start-1500 spec from `c1runs`
  - a negative-current plateau for conditions 2 and 3
- Remove the `other_low` exclusion from `differential.py` once all four conditions are exact.
