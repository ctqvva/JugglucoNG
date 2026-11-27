# CareSens Air error 16 evidence

## Isolating the condition histories

`err16_state_probe.py` regenerates a named run from the existing seed-11
decay corpus, runs its prefix with an empty calibration list, and replays a
single input against copies of the preceding state. It varies only named
fields from the public state layout. This isolates the observed conditions
from the output latch, which may already be set.

```sh
cd tools/caresens-oracle/research
python3 err16_state_probe.py 98 1728
python3 err16_state_probe.py 123 2018
python3 err16_state_probe.py 123 1728
```

The unmodified states fire conditions 0, 1 and 2 respectively, in
zero-based condition order. In particular, condition 1 can fire at sequence
2018 with an empty calibration list. Replacing the mean-fit slope history
or the fit's R² history clears it. Condition 2 responds to the steady-rate
history and the minimum and mean trend histories. Condition 0 has more
than one path. For example, changing the minimum-trend regression clears
condition 0 in run 98 at sequence 1728, while it remains set in run 23 at
sequence 3732.

The history dependence explains why fitting conditions to only the last
reported scalar per statistic missed transitions.

## Recovered statistics and condition inputs

```sh
python3 err16_statistics.py
python3 err16_state_probe.py --profiles 123 2017
python3 err16_state_probe.py --profiles 0 2018
python3 err16_rules.py --fixtures
```

The mature calculations matched 5,186 accepted records on the lot-2
sensitivity-loss fixture and the lot-1 holdout. The rate statistic is the
range of the newest seven ISF points. The steady percentage counts rates at
most 1 among available rates in the newest 289 entries. The minimum trend
chooses the 10th percentile by rounded one-based rank. Mean trend averages
all values between the 20th and 80th percentile bounds, including ties.
Minimum trend regressions use the newest 12 points sampled every third
slot and up to 73 points sampled every twelfth slot. Mean trend regression
uses the newest 24 points sampled every twelfth slot. Slopes are per day.

The condition rules consume the newest 24 entries in those statistic
histories. Their branches depend on the **pre-record** value of
`diabetes_before_7days_flag` at state offset 10914. In run 123 at sequence
2017, the flag is 6 on entry and 1 on return, and condition 2 fires. Replaying
the same input with flag 1 on entry fires condition 1. The time guard used
by condition 2 and one condition-0 branch compares the current time with
the ISF time six accepted records earlier, with a 40-minute upper limit.
Changing only that history point past the limit suppresses the condition.

On the 160 decay specs and committed fixture specs, the direct rule
comparison identified one threshold discrepancy before applying measured
long-slope rounding. Replaying that near-threshold case measured the
transition at a half-step of 5e−11 away from the decimal slope threshold.
The same run saw no activation of conditions 3–6 with empty calibration
lists. This identifies the classifications in the measured corpus. The
verification script reads statistic histories from vendor snapshots, so
full engine parity also requires reproducing how those histories update.
