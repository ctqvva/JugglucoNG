# CareSens Air: open calibration engine

`Common/src/main/java/tk/glucodata/drivers/caresens/calibration/` is an open
reimplementation of the glucose calculation that CareSens Air sensors
otherwise get from i-SENS' `air1_opcal4_algorithm` in `libCALCULATION.so`.
The Bluetooth driver (`AirGattCallback`, `air/java.cpp`) is unchanged; it
still uses the vendor library for what the app shows.

## Status

| Part | Status |
|---|---|
| ADC to current, spike limiter, 90-sample robust loess, minute averaging | reproduced |
| Lot offset, drift correction (first ~6 days), temperature compensation | reproduced |
| Sensitivity-loss compensation after day 7 (high-pass, 4-day reference, diabetes level) | reproduced |
| Early-wear rescale (first 1.5 days), Kalman filter, warm-up blend, restarts | reproduced |
| History smoothing (the six values the app writes back), trend | reproduced |
| Contact rejection (data type 2), parameter error 64, timing error 32 | reproduced |
| Error 4 (signal lost), its main condition and how long it stays set | reproduced; its other four conditions are not (seen only on extreme or late-start signals) |
| Taking over a running sensor from the vendor library's state block | reproduced; one sweep run differs in the minute currents when taken over during signal dropouts |
| Error checks 1, 2, 8, 16, 128 | **not reproduced**; the engine never raises them |
| Low-signal flag (data type 4) | delay-flag (`D0`–`D4`, rapid-drop gate), 7-record trend suppression (`err_delay_arr`), and `[0.5, 50.0]` nA reference/init-current filter reproduced; `err1` noise-break half accepted via `process(record, noiseBreak)` hook (Task 02 not yet decoded) |
| Filling records after a clock jump (consecutive sequence, 480–1379 s gap) | not reproduced; reported as `exact = false` |
| Fingerstick calibration | not part of the engine; JugglucoNG never passes a calibration list |

"Reproduced" means equal to the vendor output to floating-point rounding
(about 1e-12 mg/dL), not "close". The vendor library stays in use until the
error checks are reproduced and the engine has run against real sensors.

The error checks matter: the vendor withholds readings while one of them is
active (often until the end of the sensor), and the engine would show values
there. In the synthetic tests error 16 (sensitivity) occurs in 3 of the 18
original fixture scenarios and in 3 of 250 random ones; the others occur only
with deliberately broken signals (errors 8 and 128 never).

### What is known about the checks not reproduced

Found with `tools/caresens-oracle` (triggers, state perturbation, bisection).
The details, rejected hypotheses and scripts are in
`docs/caresens-air-handoff/` (`findings.md`, one task per open item) and
`tools/caresens-oracle/research/`; they are not needed to run the engine.

- **All error bits** are evaluated per record. Errors 1, 2 and 4 stay set
  only while records are accepted and consecutive; errors 16 and 32 stay set
  to the end (a rejected record reports no error 16, but error 32).
  The [device-parameter survey](caresens-air-handoff/device-parameters.md)
  found no changes in the six exercised bits. Bits 8 and 128 never fired in
  the survey.
- **Error 16** (sensitivity, from day 6): two 12-tap
  filters over the last 12 accepted, consecutive records with weights
  .19204, .16522, .11845, .07161, .03473, .01187, .00419, .01187, .03473,
  .07161, .11845, .16522 (newest first): over rounded glucose ("plasma"), and
  over the noise-revised current × 100 / slope100 ("ISF"), both rounded.
  Three conditions fire without calibrations in the recorded tests. The
  Kotlin research prototype matches their conditions and latch across
  mixed fresh and takeover runs with vendor revised current supplied.
  [Task 01 evidence](caresens-air-handoff/error-16-evidence.md) records the
  remaining input differences and the autonomous replay requirement.
- **Error 2** fired only for a sustained signal above about 800 mg/dL
  with a 12-record CV below about 10 %.
- **Error 1** (noise) and its break flag follow a noise statistic against
  adaptive thresholds; not decoded. The break flag also sets data type 4.
- **Errors 8 and 128**: no input found that emits either bit. The
  error-128 revised-current stage consumed by error 16 is reproduced in the
  research harness (`Error128RevisedCurrent.kt`, verified against 2.37 million
  accepted vendor records and autonomous error-16 replay).

## How it was verified

The reference is the arm64 `libCALCULATION.so` the app ships
(sha256 `0bc4e28b…02d4cf`), run in-process on an arm64 Mac by
`tools/caresens-oracle`. It is supplied locally and never committed.

- **Fixtures** (`Common/src/test/resources/caresens`): vendor outputs
  for 21 scenarios, regenerated inputs: four 15-day wears (both lot types,
  varying temperature, sensitivity loss), sustained lows and highs, fast
  swings, temperature steps, gaps with clock jitter, contact spikes, a clock
  jump, start in the middle of a wear, warm-up, invalid factory values, signal
  dropouts, and a
  separate holdout set with other seeds and factory values that was not used
  while writing the engine (it found two of the rules below). The unit test
  checks glucose, trend, history, factory glucose, sequence, rejections and
  the reproduced error bits on every record, and that state survives a
  save/restore anywhere and can be taken over from the vendor's state block.
- **Randomised sweep** (`tools/caresens-oracle/differential.py`): random factory
  values over the whole accepted range, noise, glucose shapes, temperature
  modes, gaps, jitter, clock jumps, spikes, sensitivity loss and start
  sequence and signal dropouts. Latest sweeps after Task 05 (`22e306bea`):
  - **Fresh runs**, seeds 101 and 202 (300 runs each, 1,099,980 inputs): 1 of 600 differs (seed 202 run 16, record 484: trend withheld by the unmodelled `err1` noise break, Task 02). **0** usable records not claimed exact (down from 218,091).
  - **Runs taking over vendor state** at a random record, seeds 7 and 8 (150 runs each, 329,550 inputs): 1 of 300 differs (seed 7 run 99, record 1425: minute currents after takeover during dropouts, Task 09). **0** usable records not claimed exact (down from 54,548).
  - **Independent holdout sweeps**, fresh seed 303 (100 runs, 168,220 inputs) and takeover seed 9 (50 runs, 50,171 inputs): **0 of 150 differ**, **0** usable records not claimed exact.
  - **Not claimed exact:** only records reaching record filling (`dt in 480 until 1380`).

  Two rules were found this way after the fixtures passed: the diabetes level
  thresholds and the unit of the timing-error window.

  The updated [task 00 sweep](caresens-air-handoff/tasks/00-shared-infrastructure.md)
  reports all eight bits and vendor-withheld, engine-publication-eligible
  inputs. By default only bits 4, 32 and 64 affect the bit verdict. Inexact
  records remain in the bit and publication counts, but are excluded from
  exact numerical checks. All comparisons stop at the first filled record.
  Unreproduced error 4 conditions exclude only bit 4 from scoring.
- **Intermediates**: the harness compares minute currents, corrected current,
  factory glucose, rescaled glucose, history and trend separately, so a
  difference points at the stage that causes it.

Remaining known differences: the sample current (step 1) agrees with the
reference to a few units in the last place (±4 ulp over 13 500 samples), not
bit for bit; the operation order the vendor uses was not found. Identical ADC
values still give identical currents, so the spike limiter's equality tests
agree; a decision exactly on its threshold (to 1e-15) could go either way. None
was seen in the last 900 runs. When taking over vendor state, the previous
record's samples are recomputed from the ADC values the state keeps, because
mixing the two roundings did flip the limiter once.

### Real-sensor check

Debug builds call `CareSensAirOpenCalibrationShadow` for every record: the
engine takes over the vendor state from before the record, processes it, and
the results are compared and logged under the tag `CareSensAirOpen` (a
summary every 288 records). The vendor result is the one used. A tester's log
over a full wear is the real-sensor evidence still needed.

## Provenance and licence

The engine was derived by black-box testing of the vendor library through the
interface the app already uses, declared in `air/air.hpp` (inherited from
upstream Juggluco): its inputs, outputs, the 1 579-byte debug record and the
117 312-byte state record. Inputs and states were set, perturbed and compared
against the outputs and intermediates; constants were measured, not copied.

- No disassembly or decompilation was used for this engine. An earlier review
  produced a disassembly listing; only its function names and instruction
  counts were looked at before it was deleted. A decompiler run was blocked
  before it started, and the download was deleted.
- No code or tables were taken from the vendor library or from
  OpenCareSens-air (GPL-2.0, and itself based on decompiled code). That
  project's temperature formula had been read during the earlier review; the
  engine's temperature compensation was measured independently and differs
  from it.
- The fixtures contain vendor outputs for synthetic inputs, as test vectors.
- The code is GPL-3.0-or-later like the rest of the app.

Black-box study of a program's behaviour to learn its underlying ideas is
what EU Directive 2009/24/EC art. 5(3) permits, and functional rules and
constants are not protected expression. This is a description of what was
done, not legal advice; whether to ship it is the maintainer's decision.

## Algorithm

All currents in nA, glucose in mg/dL. `seq` is the transmitter's sequence
number (one record per 5 minutes), `t` its time in seconds.

1. **Current.** `(adc × vref / 40950 − eapp) × 100` for each of the 30 samples
   (10 s apart). Lot type 2 if `eapp < 0.075`. Factory values outside
   slope100 2.5–8.1, vref 1.4–1.6, eapp 0.0385–0.11295 give error 64.
2. **Contact rejection**, once the number of accepted records reaches the
   first sequence number of 6 or more: the record is dropped (data type 2) if its
   maximum is in the first two samples, exceeds 1.2× the mean of the others,
   the standard deviation of all 30 is 1.5× that without the maximum and its
   larger neighbour, and less than 1.1× that without the minimum.
3. **Spike limiter** (only for the next sequence number): each sample is
   compared with the median of itself and the five before (raw, or cleaned
   when four of the last six were limited). Scale 1.486 × MAD, or
   1.253314 × mean absolute deviation when MAD is 0. A sample more than
   1.5 scale from the median becomes the previous cleaned sample ± scale; after
   five identical samples it must also differ by more than 0.0036.
4. **Smoothing.** From the third record, linear loess over the last 90 samples
   (tricube on the 87 nearest, three fits with bisquare weights at 6 × median
   residual), when they span less than 898 s. Then per minute: moving median
   over [i−3, i+2], a (−1, 4, 7, 8, 7, 4, −1)/28 kernel continuing the
   previous record's last three medians (if it ended less than 18 s before),
   and the mean without minimum and maximum. The record's current is the
   trimmed mean of its five minutes, minus 0.7 (lot 1) or 0.243 (lot 2).
5. **Sensitivity loss.** A high-pass `y = 0.99891 (x − x₋₁) + 0.99782 y₋₁`
   from seq 24 (starting from the mean of seq 5–24, counting only records with
   `0.5 <= corrected <= 50.0` nA). Reference: mean current over seq 865–2016
   within the same `0.5 <= corrected <= 50.0` nA band. At seq 2017 a level is
   set from the glucose of that window (more than 288 values within 40–600): 6
   if over 5 % below 70 or CV over 36 %; 5 or 4 if over 60 or 30 % above 180; 3
   if at least 15 % above 180 or CV over 30 %; 1 if under 5 % above 180 and CV
   at most 20 %; else 2. Factor 1.0, 0.95, 0.85, 0.80, 0.75 for levels 1–5;
   none for 6. While the low-passed current is below factor × reference, it is
   raised by `min(1, (seq − 2016)/288) × (factor × reference − low-passed)`.
6. **Drift** below seq 1829: divide by
   `min(1, 0.92319967 + 4.76441685e-5 s + 5.39473347e-9 s² − 4.63640418e-12 s³)`.
7. **Factory glucose** `current × 100 / slope100 / r` with
   `r = clamp(0.0328 T̄ − 0.118, 0.882, 1.134)`, T̄ the mean of the temperatures
   of the last four sequence numbers (missing ones left out).
8. **Early-wear rescale** below 180: `+ 30 (1 − g/180)(1 − g/385)`, full to
   seq 288, fading to 0 at 432.
9. **Kalman filter** `x = A x + K (z − (A x)₀)` with
   A = [[e^−½, 1−e^−½, 0], [0, 1, 1], [0, 0, 1]], K = (0.6729, 1.761, 0.1279);
   output `w x₁ + (1 − w) z`, w = (n−1)/24 since the last restart. Restarts on
   the first record, seq < 10, a gap over 1500 s (1080 s after a rejected
   record), or a rate over 5 mg/dL/min unless the previous three rates average
   over 1 in the same direction; after such a jump it stays restarted until
   six records in a row change by at most 3 mg/dL/min.
10. **History.** The last ten values; when consecutive times are all under
    600 s apart, the six behind the newest are smoothed with
    (−1.6, 2.7, 6.6, 5.6, 5.4, 3.9, −1.6)/21 (newest repeated at the end).
11. **Trend** `(h₉ − h₃) / minutes`, none if they are over 35 minutes apart, a
    value is outside 40–500, or any of the newest 7 accepted records (`err_delay_arr`)
    had data type 4; if it exceeds 1 mg/dL/min but the last step goes the other
    way, the last step's rate if the step before also did, else 0.
12. **Low-signal flag (data type 4).** `err4_delay_flag || noiseBreak`, where
    `err4_delay_flag` evaluates D0–D4 across three phases (`seq < 865`;
    `seq >= 865` with `seq < 2017 || b7 < 4`; `seq >= 2017 && b7 >= 4`, using
    pre-D updated `diabetes_before_7days_flag`) gated from `seq >= 865` by a
    2-hour rapid-drop check (`>= 40` mg/dL ending `< 100` when `>= 21` prior
    records lie within 23 sequence steps, else `D3` with single-step drop
    `> 50` mg/dL, bypassed when `idx == 0` or the previous record was flagged).
13. **Error 4**, signal lost: over the last four accepted records with
    consecutive sequence numbers, the highest raw 10 s current is below
    0.5 nA and below 0.1 × slope100 (before seq 865), or below 0.7 nA and
    0.2 × slope100 (from seq 865). It stays set while the following records
    are accepted and consecutive; a rejected record or a missed sequence
    number clears it (errors 1 and 2 behave the same; error 16 and 32 stay).
    The reference has four more conditions, not reproduced: a relative fall
    of the current over the past day (seen in late-start sensors) and two
    for negative currents whose limits depend on the earlier signal level.
14. **Timing error 32**, latched: an interruption (consecutive sequence, time
    step ≥ 23 min) four times within 864 records, ≥ 60 min twice, or ≥ 10 h
    once. The window counts accepted records (the record's index + 1, also
    for a rejected record), not sequence numbers.

## Running the harness

```
CARESENS_REFERENCE_LIB=/path/to/arm64-v8a/libCALCULATION.so tools/caresens-oracle/make_fixtures.py
CARESENS_REFERENCE_LIB=... tools/caresens-oracle/differential.py --runs 200
CARESENS_REFERENCE_LIB=... tools/caresens-oracle/differential.py --runs 100 --import-state
```

The experiment scripts behind the findings are in
`tools/caresens-oracle/research/` (see its README for setup and datasets).

The library is the one `scripts/restore-build-inputs.sh` places in
`Common/src/main/jniLibs/arm64-v8a/`. The loader needs an arm64 host
(Apple silicon or arm64 Linux), Python 3 with numpy, a C compiler, and for
`differential.py` kotlinc and a JDK.
