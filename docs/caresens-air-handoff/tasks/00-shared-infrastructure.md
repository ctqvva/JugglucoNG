# Task 00: shared infrastructure (do first; one owner)

Every error task needs three things that do not exist yet:

- a sweep that compares bits 1, 2, 8, 16 and 128
- knowledge of which device-block parameters each detector reads
- one agreed way to add detector state to the engine

If several agents build these separately, they will conflict in the same
files. One agent should own this task, and merge it before the per-error
tasks touch engine code. Investigations can start in parallel; they need
only the harness.

## Setup (all tasks)

```
export CARESENS_REFERENCE_LIB=/abs/path/arm64-v8a/libCALCULATION.so   # sha256 0bc4e28b…02d4cf
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android/sdk
cd tools/caresens-oracle/research && python3 stress.py     # harness smoke test
./gradlew :Common:testMobileDebugUnitTest :Common:testWearDebugUnitTest   # needs JDK 17
```

You need an arm64 host. The library is never committed. Read first:

- `docs/caresens-air-open-calibration.md`
- `docs/caresens-air-handoff/findings.md`
- `tools/caresens-oracle/research/README.md`

## A. Device-parameter sensitivity survey (investigation; no engine change)

**Why.** The transmitter overwrites the device block, `err*` thresholds
included (`airSaveSensorInfo2`, `Common/src/main/cpp/air/java.cpp`).

- Errors 4 and 32 ignore their parameters (e237, e230).
- For errors 1, 2, 8, 16 and 128 it is unknown.
- Several measured thresholds equal `DeviceInfo2Obj` defaults: `err2_glu` 800, `err2_cv[0]` 10, `err2_seq[1]` 11, `err345_raw`.

If a detector reads the block, its rule must read it too. The engine API
would then need the real block, not just slope100, vref and eapp.

**Do.**

- Take a set of trigger scenarios:
  - `stress.py` transforms: `noise200`, `high3`, `sat`, `low30`, `step_down`
  - the error 16 fixtures: `life-lot2-sensitivity-loss`, `holdout-life-other-lot1`
- Copy `co.default_device()` and perturb one `err*` field (or one array element) at a time. Use ×0.5 and ×2; for byte fields, ±1 and 0.
- Record per field and per bit whether the first firing record or the firing count changes. Also check `errcode_version`, `cal_noise` and `sensor_version`.
- Write the table to `docs/caresens-air-handoff/device-parameters.md`, with the script that produced it.

This is a batch of a few hundred runs, about ten minutes. It tells each
error task which thresholds are parameters.

**Done when:** every `err*` field in the device block is classified, per error bit, as read or ignored.

## B. Differential comparison for all bits (tools only)

`differential.py` compares only `MODELLED = 4|32|64`. Extend it:

- Report per bit (1, 2, 4, 8, 16, 32, 64, 128) the records where engine and vendor differ, as counts, not a failure, until the bit is modelled.
- Report "vendor withheld, engine published" separately; that is what users would see.
- Keep the exit status driven by the modelled bits only.
- Make `MODELLED` a command-line option or a single constant that tasks edit.

**Done when:** a sweep prints a per-bit mismatch table. Existing modelled bits keep passing.

## C. Engine conventions for new detectors (decide once, document here)

Agree and write down the following before the first detector lands. The
defaults below follow the existing error 4 code.

- **Code.**
  - Each detector goes in its own file, `CareSensAirErrorN.kt`, in `Common/src/main/java/tk/glucodata/drivers/caresens/calibration/`.
  - It provides an update step called from `CareSensAirCalibration.process` after the Kalman step.
  - On rejected records it is called through the existing rejection path, which clears the 1/2/4 latches.
- **The latch** (`cond || (prev && consecutive && accepted)` for 1, 2 and 4; permanent for 16 and 32) is shared. Factor it once.
- **Error code.** `errorCode` is the OR of the detector bits at the single existing line.
- **State.**
  - Fields go in `CareSensAirCalibrationState`, grouped per detector, each group appended at the end of the serialised form.
  - `VERSION` is bumped once per merged task. A task rebasing onto another's merge renumbers its own bump; old versions keep restoring.
- **Takeover.** Each detector imports its vendor fields in `CareSensAirVendorState.read`. Use the offsets in `tools/caresens-oracle/research/state-offsets.txt`, and cite the offset in a comment.
- **Tests.**
  - Add bits to `modelledErrors` in `CareSensAirReferenceFixtureTest` and to `MODELLED` in `differential.py`.
  - Add a triggering fixture to `make_fixtures.py`. Use a new name; never change existing fixture specs.
  - Regenerate only new fixture files and `index.txt`.
- **Exactness.** A detector that is only partly decoded must set `exact = false` where its undecoded part could fire. It must not raise a bit it cannot predict exactly.

## D. Device-block plumbing (only if A finds parameters that matter)

- Extend `CareSensAirFactoryParameters`, or add a device-parameter object, with the fields A marks as read. Defaults stay the `DeviceInfo2Obj` values.
- Pass them in `EngineCli.kt`, `differential.py`, `make_fixtures.py` (fixture header) and `CareSensAirOpenCalibrationShadow`.
- Do not wire the driver (BLE integration is out of scope); the shadow already gets the vendor's block.

## Shared files and owners

| File | Owner | Others |
|---|---|---|
| `differential.py` comparison and `MODELLED` | task 00 | edit only the `MODELLED` constant and their own exclusion line |
| `CareSensAirCalibration.process` hook points, `errorCode` line | task 00 | add one call and one OR term each |
| `CareSensAirCalibrationState` serialisation, `VERSION` | task 00 | append own group, bump on merge |
| `CareSensAirVendorState.read` | each task | own fields only |
| `make_fixtures.py` `DEVELOPMENT` / `HOLDOUT` | each task | add entries; never edit existing ones |
| `docs/caresens-air-open-calibration.md` status table | each task | own row only |
