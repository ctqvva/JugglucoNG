# CareSens Air open engine: handoff for independent agents

**State on 2026-11-27.** PR #1041, branch `t3/caresens-air-open-engine`.

The open Kotlin engine
(`Common/src/main/java/tk/glucodata/drivers/caresens/calibration/`)
reproduces the vendor's glucose, trend, history, contact rejection and
errors 4 (main condition), 32 and 64. Equal means within floating-point
rounding. The vendor library stays authoritative in the driver, and debug
builds shadow-compare the two.

What is missing is listed below. Each item has a self-contained specification
in `tasks/`.

- `findings.md`: everything confirmed, rejected or untried, with the script behind each result.
- `device-parameters.md`: task 00's per-field, per-bit device block survey.
- `../caresens-air-open-calibration.md`: the engine as implemented, verification, provenance.
- `../../tools/caresens-oracle/research/`: every experiment script, small datasets, the state offset map and dataset generators.

## Rules for every task

- **Black-box only.** Use inputs, outputs, the debug record and the state block, run through `tools/caresens-oracle`. No disassembly or decompilation of `libCALCULATION.so`, and no code from OpenCareSens-air (GPL, decompiled). See "Provenance" in the engine doc.
- **The library is never committed.** Supply it with `CARESENS_REFERENCE_LIB`.
- **Unit tests** need JDK 17: `./gradlew :Common:testMobileDebugUnitTest :Common:testWearDebugUnitTest`.
- **Exactness.** A rule enters the engine only when it is exact on the fixtures and the sweep. A partly decoded detector sets `exact = false` where its unknown part could act; it never raises a bit it cannot predict.
- **Out of scope:** driver wiring, BLE, JNI and UI. Whether errors withhold readings in the app is the owner's decision (see "Removing the library").

## Tasks

| # | Task | Bit / output | Status | Depends on |
|---|---|---|---|---|
| 00 | [Shared infrastructure](tasks/00-shared-infrastructure.md): device-parameter survey, per-bit sweep, engine conventions | all | complete; bits 8 and 128 unexercised | — |
| 01 | [Error 16](tasks/01-error-16.md), sensitivity / end of life | 16 | autonomous research prototype matches mixed, decay, fixture and holdout condition and latch replay | 00, revised-current stage in 07 |
| 02 | [Error 1](tasks/02-error-1.md), noise, plus the noise break | 1, type 4 input | triggers known | 00 A (`err1_*`), 00 C |
| 03 | [Error 2](tasks/03-error-2.md), sustained high | 2 | partly known | 00 A (`err2_*`), 00 C |
| 04 | [Error 4 conditions 1–4](tasks/04-error-4-conditions.md) | 4 | condition 0 done | 00 C |
| 05 | [Data type 4](tasks/05-data-type-4.md) and the error 4 delay tests | type 4, sensitivity reference | delay-flag, trend-withholding, and valid-current reference filter implemented (`CareSensAirDelayFlag.kt`); awaiting Task 02 `noiseBreak` input | 02 (noise break), 00 C |
| 06 | [Error 8](tasks/06-error-8.md) | 8 | never triggered | 00 A (`err345_*`) |
| 07 | [Error 128](tasks/07-error-128.md) | 128 | revised-current stage reproduced (`Error128RevisedCurrent.kt`); emitted bit never triggered | 00 A (`cal_noise`, `errcode_version`) |
| 08 | [Record filling](tasks/08-record-filling.md) after a 480–1379 s step | extra outputs | trigger and linearity known | — (API change, see below) |
| 09 | [Takeover during dropouts](tasks/09-takeover-during-dropouts.md) | minute currents | one sweep run differs | — |

## Shared dependencies and how to avoid conflicts

**1. Device block (task 00 A, one owner, first).** The transmitter overwrites the device block, `err*` thresholds included.

- The [survey](device-parameters.md) found no change in emitted bits 1, 2, 4, 16, 32 and 64 across the tested cases. It could not establish dependencies for bits 8 and 128.
- Detector tasks can use the existing factory parameter API. Revisit device-block plumbing together if a new triggering case demonstrates a dependency.

**2. Latch and hook points.** Errors 1, 2 and 4 share one latch rule; errors 16 and 32 latch permanently.

- Task 00 C provides the shared latch, accepted and rejected hooks in `CareSensAirCalibration.process`, and the common `detectorErrors` mask.
- Detector tasks add one call and one OR term each, in their own file.

**3. State serialisation.** `CareSensAirCalibrationState` has one `VERSION`.

- Each task appends its own field group and bumps the version on merge. The second merger renumbers its bump.
- Each task imports only its own fields in `CareSensAirVendorState.read`.
- Offsets are in `tools/caresens-oracle/research/state-offsets.txt`.

**4. Error 4 state is split between two tasks.**

| Task | Owns |
|---|---|
| 04 | `err4_min_prev`, `err4_range_prev`, `err4_min_diff_prev` (conditions 1–4) |
| 05 | `err4_delay_flag_arr` and the `mdc` reference fields (D tests, type 4) |

Both keep `err4_inA` / condition 0 as they are.

**5. Data type 4 takes one input from task 02.**

- The noise break (`err1_random_noise_temp_break`) is decoded only in task 02.
- Task 05 consumes it through one per-record boolean.
- Until task 02 lands, task 05 keeps the noise half inexact.

**6. Inexact marking.** The engine's `exact = false` stand-ins are:

- `maybeLowFlag`, `historyMaybeFlagged`, `referenceMaybeFlagged`: type 4, owned by task 05
- the `dt in 480 until 1380` check: filling, owned by task 08

Remove a stand-in only in the task that replaces it.

**7. Filling changes the engine API.** One input can produce up to four results. Task 08 owns that change. Detector tasks need nothing special: filled records go through the normal per-record path.

**8. Sweep and fixtures.**

- `differential.py` comparison logic belongs to task 00 B. Use `--modelled` while developing a detector. Update the default `MODELLED` constant and its corresponding test mask when the detector lands.
  - `other_low` belongs to task 04.
  - The `filled` cut-off belongs to task 08.
- Fixtures: add new names; never change existing specs. Regenerate only the new files and `index.txt`.

**9. Revised current.** Task 07 owns the current-revision stage. Task 01
consumes its per-record current for the ISF filter. The stage can revise
current on a type-0 record while bit 128 stays clear.

**10. Docs.** Each task updates its own row in the status table of `../caresens-air-open-calibration.md` and its section in `findings.md`.

Tasks 01, 02, 03, 04, 06, 07, 08 and 09 can be investigated in parallel; their
investigation needs only the harness. Engine changes should land after task
00 C, and task 05 after task 02.

## Removing the library: minimum requirements

The driver withholds a reading whenever the vendor's `errcode` is non-zero
(`air/java.cpp`, `if (res && !output.errcode)`), and the vendor often keeps
an error set to the end of the sensor. Removing the library means choosing,
per error, between vendor parity and an equivalent protection of our own.

### Necessary: reliability protections

Without these, the open engine would show values the vendor deliberately
suppresses for physical reasons.

| Protection | Today | Minimum to remove the library |
|---|---|---|
| Invalid factory values (64) | exact | done |
| Sensor-contact rejection (type 2) | exact | done |
| Lost signal (4, condition 0) | exact | done. Conditions 1–4 (relative fall, negative current) are needed only if real logs show them firing; otherwise our own fall/negative-current guard is enough. |
| Transmitter clock faults (32) | exact | done |
| Sensitivity loss / end of life (16) | not reproduced | **required in some form**. It is the only unreproduced error that fires on realistic data, from day 6, and it protects against falsely low values from a failing sensor. Either parity (task 01) or a documented own detector over the same exact ISF and plasma inputs. |
| Noise (1) | not reproduced | **required in some form**: a noisy signal gives unreliable values. Parity (task 02) or an own noise guard over the minute currents. |
| Low-signal trend suppression (type 4) | delay-flag & `[0.5, 50.0]` nA reference filter exact; `noiseBreak` hook ready for Task 02 | done for delay-flag and sensitivity reference (eliminates up to 73.9 mg/dL post-day-7 glucose error); wire Task 02 `noiseBreak` when available |
| Takeover of running sensors | exact except task 09 | required only if switching mid-wear. Otherwise switch at the next sensor start. |
| Real-sensor evidence | none | **required.** A tester's debug-build shadow log over a full wear (logcat tag `CareSensAirOpen`), including the device block the transmitter sent. |

### Optional: vendor parity only

- **Error 2** (sustained high above about 800 mg/dL). It is far above the displayed range, so an own "out of range" handling suffices.
- **Emitted bits 8 and 128.** Neither bit appeared in the tests. Error-16
  vendor parity still needs the upstream revised-current behavior from
  Task 07, independently of whether bit 128 is emitted.
- **Record filling.** The vendor's interpolated readings for missed records after a 480–1379 s step are not measurements. Needed: the engine must stay stable and sane after such a step, which the sweep does not yet compare (task 08, items 3–4). Exact fill values are optional.
- Error 4 conditions 1–4 and the exact D-test phases, beyond the conservative stand-ins.
- Bit-exact sample currents (±4 ulp today).

### Decisions for the owner, not for agents

- Whether the open engine withholds readings for its own protections (errors 16 and 1) the way the vendor does, or publishes them flagged. The project's standing rule is not to stop publishing readings on inference.
- Whether an own detector, where exact parity stays out of reach, is acceptable for errors 16 and 1.
