# Task 00: shared infrastructure

Task 00 is complete. Detector implementations, type 4, filling, and
takeover during dropouts remain in their own tasks.

## Setup

Run from the repository root with an arm64 host, Python 3.9 or later with
NumPy, a C compiler, Kotlin, and JDK 17. See the
[research README](../../../tools/caresens-oracle/research/README.md) for
the legacy helpers and dataset prerequisites.

```sh
export CARESENS_REFERENCE_LIB=/abs/path/arm64-v8a/libCALCULATION.so
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android/sdk
python3 tools/caresens-oracle/research/device_parameter_survey.py
python3 -m unittest discover -s tools/caresens-oracle -p test_differential.py
python3 tools/caresens-oracle/differential.py --runs 30 --seed 1041
python3 tools/caresens-oracle/differential.py --runs 20 --seed 1042 --import-state
./gradlew :Common:testMobileDebugUnitTest :Common:testWearDebugUnitTest
```

## A. Device parameters

[Survey results and method](../device-parameters.md). All 89 target elements
were perturbed across nine scenarios. None changed an emitted bit trace.
Bits 8 and 128 never fired, so the survey leaves their dependencies open.
Use the report's per-bit coverage when interpreting any specific field.

## B. Differential comparisons

The sweep counts all eight bits from the engine's start or takeover to
the next filled input. The table shows vendor set counts, raw mismatch
counts and scored mismatch counts. `--modelled` accepts a decimal or hexadecimal mask. The
default is 4, 16, 32 and 64. Only scored bit mismatches affect the verdict,
alongside the existing rejection and numerical checks and the output sequence
check. The exclusion for error 4 conditions 1–4 applies solely to bit 4 scoring.
It covers the suffix after the first such condition, including when it fired
before the engine took over.

The publication count uses the native error and range gate: output present,
no error, and a glucose value strictly between 35 and 505 mg/dL. It reports
how many inputs the vendor withheld that an engine output would pass, and how
many of those had a nonzero vendor error or bit 16. This is an offline estimate that
includes inexact records. Later runtime delivery checks are outside its scope.

Task 00 ran 30 fresh scenarios and 20 takeover scenarios from the commands
above. All passed the scored checks. The fresh sweep saw 23 raw bit-4
mismatches in its excluded range and 2,844 bit-16 mismatches. The same 2,844
inputs were vendor-withheld and engine publication-eligible. There were 12,627
inexact usable records in the fresh sweep and 1,880 in takeover. Neither
random seed reached filling. Mobile and Wear unit suites passed, with
6,328 and 4,532 cases respectively, including 1 and 9 skips. The comparison
unit tests separately verify the fill cut-off, bit mask isolation and
one-sided NaN detection.

Use `--fixture NAME` to limit a run to one or more fixture specs. Each
detector task adds a new triggering spec to `make_fixtures.py` and can generate
just that payload and the index with `make_fixtures.py --fixture NAME`.
Established fixture specs and payloads should stay intact. Add the completed
bit to the Python default `MODELLED` and Kotlin fixture `modelledErrors`
together when its implementation lands.

## C. Detector hooks and state

- Put each detector in `CareSensAirErrorN.kt`. Follow error 4's update hook
  after Kalman and before history rotation. Capture inputs at the stage a
  detector uses, including pre-rotation state where required.
- The contact rejection path has its own hook. Use it to reset or advance
  the detector's state as the vendor does. Timing observes both paths.
- Use `CareSensAirDetectorLatch.consecutive` for errors 1, 2 and 4. An
  accepted trigger starts the latch. It persists on consecutive accepted
  sequence numbers and clears on rejection or a sequence gap without a new
  trigger. Use `permanent` for 16 and 32. Error 16's latch persists through
  rejection, but the bit is masked for that record. Error 32 remains visible.
- Add each emitted bit to `CareSensAirCalibration.detectorErrors`, which
  serves accepted and rejected results. Invalid factory data still exits
  before the detector hooks with error 64.
- Append each state group to `CareSensAirCalibrationState`'s serialized
  format. On a schema change, bump `VERSION`, preserve reads of previously
  supported versions, and test defaults for the newly appended fields. The
  baseline is version 2. Task 00 changed no fields or bytes. Rebase and
  renumber if another detector has used the next version.
- Import detector state in `CareSensAirVendorState.read`. Cite the field and
  offset from `tools/caresens-oracle/research/state-offsets.txt`.
- Mark affected records `exact = false` where an undecoded condition could
  change the result. The sweep's numerical exactness checks use this flag.
  During development keep the partial bit diagnostic or use a specific
  evidence-backed exclusion for its undecoded condition.

## D. Device-block plumbing

The survey found no field requiring new plumbing for the exercised bits.
`EngineCli.kt`, fixtures and the shadow continue to use the existing three
factory values. If a future detector demonstrates a dependency, extend the
parameter API, CLI, fixture header and shadow together. BLE driver wiring is
outside these tasks.
