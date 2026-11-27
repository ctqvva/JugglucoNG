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

At commit `81f999bf2`, the decay corpus matched over 691,200 records. The
three established firing fixtures and the new holdout matched over 14,960
records. The mixed sweeps at that checkpoint failed. Counts use the
shared pre-fill comparison boundary.

| Sweep | Compared records | Condition mismatches (0, 1, 2) | Latch mismatches |
|---|---:|---:|---:|
| Fresh, seed 101 | 556,380 | 48, 362, 115 | 4,729 |
| Fresh, seed 202 | 543,600 | 239, 415, 201 | 23 |
| Takeover, seed 7 | 165,932 | 396, 246, 105 | 704 |
| Takeover, seed 8 | 163,618 | 0, 606, 0 | 97 |

Conditions 3–6 stayed clear in these sweeps. The earlier prototype set
condition 0 at input 4148 (sequence 4218) in takeover seed 7, run 19,
where the vendor's latch stayed clear.

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

At `68bb0296b`, current injection alone left 749 runs with statistic
discrepancies, 27 with condition discrepancies and 19 with latch
discrepancies. The 19 included six false-positive failures, one missed
failure, nine early detections and three late detections. The cumulative
latch differences comprised 4,663 false-positive records and 881 missed
records. The following rows give one-based input positions followed by
sequence numbers for the first statistic divergence and the first
emitted bit in each affected run.

| Seed / run | Takeover after input | First statistic divergence | Vendor first bit 16 | Prototype first bit 16 |
|---|---:|---|---|---|
| 101 / 93 | — | `trend_min_diff` 704 (760) | — | 2418 (2672) |
| 101 / 103 | — | `trend_min_diff` 705 (802) | — | 1798 (2002) |
| 101 / 143 | — | `trend_min_diff` 697 (715) | 2330 (2376) | 2333 (2379) |
| 101 / 195 | — | `trend_min_diff` 694 (841) | — | 2096 (2525) |
| 101 / 214 | — | `trend_min_diff` 692 (731) | 1630 (1737) | — |
| 101 / 221 | — | `trend_min_diff` 692 (751) | 2386 (2628) | 2318 (2555) |
| 101 / 269 | — | `trend_min_diff` 707 (707) | — | 1746 (1746) |
| 101 / 273 | — | `trend_min_diff` 696 (725) | 2412 (2441) | 2405 (2434) |
| 101 / 295 | — | `trend_min_diff` 703 (768) | 4282 (4589) | 4245 (4549) |
| 202 / 145 | — | `trend_min_diff` 705 (705) | 3172 (3172) | 3176 (3176) |
| 202 / 197 | — | `diabetes_before_7days_n_valid` 1 (1500) | 1683 (3201) | 1686 (3204) |
| 202 / 226 | — | `trend_min_diff` 694 (700) | 4138 (4172) | 4128 (4162) |
| 7 / 19 | 581 | `trend_min_slope2` 692 (718) | — | 4148 (4218) |
| 7 / 71 | 833 | `roc_steady` 834 (1455) | 1757 (2389) | 1756 (2388) |
| 7 / 75 | 166 | `trend_min_slope2` 692 (713) | — | 2127 (2185) |
| 7 / 85 | 287 | `trend_min_slope2` 692 (817) | 3355 (3977) | 3198 (3786) |
| 8 / 72 | 4030 | `trend_min_slope2` 4031 (4031) | 4066 (4066) | 4061 (4061) |
| 8 / 78 | 19 | `trend_min_slope2` 697 (766) | 1850 (2018) | 1814 (1979) |
| 8 / 130 | 809 | `trend_min_slope1` 810 (882) | 3816 (4125) | 3758 (4065) |

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
points more than six sequence steps old. Its oldest slot must be at or
beyond sequence 865. In seed 101, run 269, input 870 is rejected and the
rate first updates at sequence 872 because sequence 871 still looks back
to sequence 864. The steady-rate window requires a finite current rate
and at least 231 finite rates within the last 288 sequence steps.

Reference promotion requires at least 20 candidates within 24 sequence
steps, with every candidate inside the reference's tolerance. The main
reference freezes on its 864th eligible update using the newest 864
candidate slots. The early mean reference starts with the first eligible
trend, never freezes, and resets when the three-day trend window lacks
enough accepted records. Seed 7, run 85 initializes the main mean
reference at sequence 889 after 20 updates. Seed 202, run 197 starts at
sequence 1500 and initializes it at sequence 2219 after 23 updates.

All three conditions require 24 consecutive accepted sequence numbers.
On fresh seed 101, run 93, changing only the preceding sequence tail to
close the gaps enables condition 1 at sequence 2672. Condition 0 also
has an early branch. From sequence 1728 until the main mean reference is
ready, the last 24 early-reference losses must each reach 70 in both
absolute and percentage terms. The replay at seed 101, run 214, input
1630 (sequence 1737) fires with 813 eligible main-reference updates.
Changing either early history from −70 to −69.999 suppresses it.

The early diabetes accumulator counts accepted records even when plasma
is NaN and counts plasma of exactly 180 in both TAR and TIR. It continues
past sequence 2017 when the day-seven profile has too few samples. In
takeover seed 8, run 27, it reaches 288 only at sequence 2021. The detector
uses the pre-record profile for its current conditions.

`python3 err16_history_probe.py` verifies fits, rates and reference
updates directly against vendor state on 29,592 accepted records. It also
replays the two condition boundaries above.

### Replay after the statistic and rule fixes

With revised current supplied, all 900 mixed runs matched on every
condition and the emitted latch. There were zero false-positive, missed,
early or late failures and zero cumulative latch mismatches. All 59
remaining affected runs differ only in plasma among the reported
statistics. The first divergence is shown by earliest input within each
sweep, with the sequence number in parentheses. `--details` lists the
first differing record for every affected run.

| Sweep | Runs | Compared records | Statistic differences by run | Earliest plasma difference |
|---|---:|---:|---:|---|
| Fresh, seed 101 | 300 | 556,380 | 27 | run 100, input 484 (2069), 89 → 88 |
| Fresh, seed 202 | 300 | 543,600 | 22 | run 18, input 521 (2020), 156 → 155 |
| Takeover, seed 7 | 150 | 165,932 | 6 | run 140, input 582 (2081), 90 → 89 |
| Takeover, seed 8 | 150 | 163,618 | 4 | run 9, input 1380 (2040), 101 → 100 |

The plasma probe recomputes the filter using the vendor's rounded glucose
buffer. On fresh seed 202, run 197, input 528 (sequence 2030), the vendor
and open calibration supply different rounded readings. The same taps
give 176.783 before rounding from the vendor values and 176.379 from the
open values, which explains the observed plasma difference of 177 versus
176. That run agrees on the conditions and latch. The research CLI keeps
using the open engine's glucose in both current modes.

### Replay with the ordinary current

Rerun the mixed commands without `--revised-current` to exercise the
prototype with its autonomous inputs. Statistic differences occur in
103 runs (43 and 41 fresh, 7 and 12 takeover, in seed order above).
Four runs have condition differences, totaling 10 condition-1 records
and 25 condition-2 records. Two runs differ on the emitted latch. There
are no false-positive or early detections, one missed failure and one
late failure. The cumulative latch total is seven missed records and
zero false-positive records.

The four runs with condition differences are listed below. Input
positions are one-based, with sequence numbers in parentheses.

| Seed / run | Takeover after input | First ISF divergence | First condition divergence | Effect on bit 16 |
|---|---:|---|---|---|
| 101 / 61 | — | 101 (1600), 80 → 67 | condition 2 at 2500 (3999) | missed failure, 1 record |
| 202 / 127 | — | 980 (1009), 110 → 92 | condition 1 at 4272 (4301) | late failure, first prototype detection at 4278 (4307) |
| 7 / 12 | 3880 | 4037 (4037), 68 → 57 | condition 2 at 4120 (4120) | latch agrees |
| 8 / 72 | 4030 | 4188 (4188), 87 → 74 | condition 1 at 4276 (4276) | latch agrees |

The 160-case decay corpus and the four firing fixtures still agree in
ordinary-current mode on all 706,160 compared records. The mixed
ordinary-current failures keep Task 01's autonomous release gate open.
