# Task 09: minute currents after a takeover during dropouts

A small, isolated discrepancy. It does not depend on the error tasks.

## Reproduce

```
cd tools/caresens-oracle
python3 differential.py --runs 100 --seed 7 --import-state
# run 99: minute currents differs first at record 1425 (engine started from vendor state after 1120)
```

Spec:

```
{'seed': 121379890, 'readings': 1500, 'start': 1, 'slope100': 6.096608520311732, 'vref': 1.4316686265979786,
 'eapp': 0.08278881958912236, 'lo': 60, 'hi': 410, 'knot_min': 30, 'noise': 5, 'temp_mode': 'vary',
 'temp_lo': 3302, 'decay': 0.5, 'gap_permille': 0, 'gap_max': 40, 'jitter': 0, 'spike_permille': 0,
 'clock_jump_at': -1, 'clock_jump': 40000, 'dropout_permille': 10, 'dropout_min': 3, 'dropout_max': 20,
 'knot_max': 60, 'temp_hi': 3302}
```

Whether the same spec is equal when run fresh, without a takeover, was not
checked; do that first. `research/dg2.py "<spec>" 1425` gives a per-record
dump of a fresh run.

## Suspects

The takeover imports the spike limiter tails, the loess window and the
previous medians from the vendor block (`CareSensAirVendorState.read`).
Dropouts make long low runs where:

- the limiter uses the cleaned history ("four of the last six limited")
- the `flags` import may not match the vendor's six-entry flag history
- the MAD can be 0, which takes the 1.253314 × mean-deviation path

Compare the engine state right after the import with a fresh engine at
record 1120, field by field. `research/kotlin/StateDiff.kt` does that given
records and a vendor state dump. It predates the parameter argument of
`CareSensAirVendorState.read(block, parameters)`, so update that call first. Then find the first field that differs.

## Done when

Takeover sweeps with seeds 7 and 8 show 0 differences. Add a regression
fixture: a takeover in the middle of a dropout run.
