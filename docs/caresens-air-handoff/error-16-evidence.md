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
If both bounds are equal, it averages the whole window.
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

After applying the measured long-slope rounding, the direct rule comparison
matched every condition in the 160 decay specs and the committed fixture
specs. In decay run 84 at sequence 3852, the vendor's slope test changes
state at a half-step of 5e−11 away from the decimal threshold. Conditions
3–6 stayed clear throughout the empty-list runs. Their activation with
nonempty lists remains uncharacterized. The direct rule comparison uses
vendor statistic histories, so its result covers the decision rules.

## The actual ISF input

```sh
python3 err16_isf_source.py
```

The ISF kernel reads the current history at vendor offset 48888,
`err128_CGM_c_noise_revised_value[36]`. The ordinary five-minute current is
often numerically identical to this input, which explained the earlier
matches. On fresh sweep seed 101, run 61, the ordinary current gives 85
different ISF values out of 2,453 finite values. The revised current gives
zero differences. In a replay of input 104 (sequence 1603), changing only
the preceding revised currents changes ISF from 82 to 16 while the output
data type and ordinary five-minute current stay the same.

The revision is active on sequences 1600–1603. It persists after the
reading returns to data type 0 and is absent on a later data-type-4 interval
in the same trace. Bit 128 stays clear for the entire run. A type-4 flag
therefore cannot substitute for the revised current. The prototype can
import its recent values on takeover. Advancing the history from fresh
inputs requires the revision stage documented in [Task 07](tasks/07-error-128.md).

## Kotlin prototype and boundary

`err16_replay.py` builds a research-only Kotlin detector. It passes the
ordinary five-minute current from the open engine, imports the vendor
histories when given a takeover point, and compares every condition and
the latch before filling. The application uses its existing detector
set. Keep the bit-16 scoring mask unchanged until this replay achieves
parity on the full fresh and takeover sweeps.

```sh
python3 err16_replay.py --runs 160
python3 err16_replay.py --fixture life-lot2-sensitivity-loss \
  --fixture holdout-life-other-lot1 --fixture holdout-late-takeover-lows \
  --fixture holdout-err16-decay
python3 err16_replay.py --sweep-case 101 61
python3 err16_replay.py --sweep-case 7 19 --import-state
python3 err16_replay.py --sweep 101 --runs 300
python3 err16_replay.py --sweep 202 --runs 300
python3 err16_replay.py --sweep 7 --runs 150 --import-state
python3 err16_replay.py --sweep 8 --runs 150 --import-state
```

The decay corpus matched over 691,200 records. The three established
firing fixtures and the new holdout matched over 14,960 records, including
all three reachable conditions. The mixed sweeps return a failing exit
status and report the first discrepancy per run. Counts use the shared
pre-fill comparison boundary.

| Sweep | Compared records | Condition mismatches (0, 1, 2) | Latch mismatches |
|---|---:|---:|---:|
| Fresh, seed 101 | 556,380 | 48, 362, 115 | 4,729 |
| Fresh, seed 202 | 543,600 | 239, 415, 201 | 23 |
| Takeover, seed 7 | 165,932 | 396, 246, 105 | 704 |
| Takeover, seed 8 | 163,618 | 0, 606, 0 | 97 |

Conditions 3–6 stayed clear in these sweeps. In addition to the
current-source dependency, the prototype's statistic histories diverge on
some gapped traces. Sweep seed 7, run 19 imports state after input 581 and
first disagrees at input 4148 (sequence 4218), where the prototype sets
condition 0. Two useful checks in that case are the sampled regression
coordinates and the cadence of the steady-rate reference.

## Isolating statistic updates from revised current

`err16_replay.py --revised-current` supplies the last value of the vendor's
post-input `err128_CGM_c_noise_revised_value` history to the research
prototype on each input. The open calibration engine still supplies its
own acceptance result, glucose and diabetes level. Takeover retains the
ordinary snapshot import. The comparison ends at the existing pre-fill
boundary in both current modes.

Each run reports the first state-tail divergence, first condition
divergence and first emitted-latch divergence separately. `--details`
reports the earliest mismatch for each observed statistic. A false-positive
failure means only the prototype emitted bit 16 within the compared span;
a missed failure means only the vendor did. If both emitted bit 16, an
early or late detection compares their first output positions. Latch
record counts include all records while the two emitted bits differ.

```sh
python3 err16_replay.py --sweep-case 7 19 --import-state --revised-current --details
python3 err16_replay.py --sweep 101 --runs 300 --revised-current
python3 err16_replay.py --sweep 202 --runs 300 --revised-current
python3 err16_replay.py --sweep 7 --runs 150 --import-state --revised-current
python3 err16_replay.py --sweep 8 --runs 150 --import-state --revised-current
```

The trend regressions use the oldest finite trend value within 864
sequence steps as the common sampling origin. Sample every third or
twelfth accepted slot from that origin, then keep the newest 12, 73 or
24 samples for the respective fits. The regression x coordinates are
the actual sequence distances divided by 288. This accounts for the
equal fits on consecutive records before the sampling phase moves, as
well as the first short-slope discrepancy at seed 7, run 19, input 726
(sequence 752). The same run's long slope first diverged earlier, at
input 692 (sequence 718), because the prototype used uninitialized slots.

The rate uses the newest seven accepted slots with at least four finite
ISF values and a 40-minute time guard across those slots. It can retain
points more than six sequence steps old. The steady-rate window requires
a finite current rate in addition to the trailing count. The early
diabetes accumulator counts accepted records even when plasma is NaN.

`python3 err16_history_probe.py` verifies the fit and rate definitions on
27,135 accepted records across eight of the mixed runs using the vendor's
own state tails. The Kotlin changes are exercised independently by the
replay harness. Seed 7, run 19 now agrees on every condition and latch
with the revised-current input supplied.
