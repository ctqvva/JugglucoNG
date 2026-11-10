# Medtrum native glucose predictor — design

**Status:** draft for review
**Track:** Medtrum (worktree `.worktrees/medtrum-clean`, branch `feature/medtrum-driver-clean`,
base `origin/main` = `0b3f4daad`)
**Author:** session of 2026-10-17

---

## 1. Intent

Medtrum's EasySense app ships a vendor algorithm as `libmdkjnidemo.so` — a glucose
predictor used for the "predicted low glucose" alarm. JugglucoNG's house rule is that a
vendor binary is never committed to the repo (precedent: `libalgorithm-jni.so`, see
`docs/ct14-poctech-plan.md` §2.1 and `CLAUDE.md`), and that vendor algorithms are
reimplemented in-tree where possible (precedent: the Anytime / CT-14 track,
`docs/anytime-native-algorithm-plan.md`).

This track does that for `libmdkjnidemo.so`: a self-contained, pure-Kotlin reimplementation
of the predictor, plus the differential-test harness that proves it matches the vendor
binary.

The current Medtrum driver on `feature/medtrum-driver-clean` references the vendor library
through a guessed JNI stub, and the stub is wrong (`static native void
predictGlucose(float)` vs the real `void predictGlucose(int, int[], int[], int[], int[],
int[])`). It also uses the result as a *smoothed current reading*, which the vendor API does
not produce. Both errors disappear with this design.

## 2. Goals

1. **Byte-free of vendor code.** No `.so`, no disassembly, no derived constants dump inside
   the repo. Artifacts stay outside, in `~/Projects/Dia/medtrum-re/native/`. The in-tree
   findings document (deliverable 5) carries **structure and narrative only** — no constant
   tables, no addresses, no vector dumps. (Decided 2026-10-17; §6 of this document is
   deliberately coarse for the same reason and is the maximum numeric detail retained
   in-tree — my call, flag if you want it coarser.)
2. **Behavioural parity** with the vendor predictor, proven by differential testing rather
   than asserted: the observable outputs are integer truncations, so the acceptance bar is
   **exact equality**, not a tolerance. There is **no divergence budget** — a vector that
   cannot be matched exactly is escalated with its input, never accepted as a documented
   exception. (Decided 2026-10-17.)
3. **Deterministic and testable on the JVM.** No Android dependencies, no I/O, no time
   source, no randomness — the same class of artifact as `AnytimeNativeAlgorithm`.
4. **Dormant landing.** The port ships with tests and no caller. Wiring it to an alarm is a
   separate, later decision (see §8), because the driver's transport is still blocked on a
   capture with a live glucose reading.

## 3. Non-goals

- Not a smoothing filter. This algorithm extrapolates 5–40 minutes ahead. Using it as a
  display filter is the bug this design removes.
- Not a protocol/driver change. `MedtrumProtocol`, `MedtrumBleManager` and the transport
  rewrite (handoff phases P1–P4) are out of scope.
- Not the matrix library. The 13 `matrix_*` functions need not be reproduced as an API; only
  their arithmetic *order* matters where it affects truncation.
- No new `ManagedSensorUiFamily`, no `SensorSourceResolver` change, no UI.

## 4. Anti-goals

- Committing the vendor `.so`, its disassembly, or a decompiled-code dump.
- Reproducing only the happy path: state carry-over, the 8-retry rejection loop, the
  contiguity window and the degenerate branches are part of the contract.
- Accepting a near miss as good enough. Exact equality is the bar (§2.2); a vector that
  cannot be matched is a blocker that is escalated with its input, not smoothed over by a
  tolerance and not hidden.

## 5. Constraints

- **Repo style:** no comments unless they carry a decision or an uncertainty; `ponytail:`
  marks a deliberate shortcut with its ceiling.
- **Build gates:** `ConnectModeLeverTests` and `ArchitectureGateTests` (shrink-only
  allowlists in `Common/src/test/resources/arch/`) must stay green. A plain Kotlin object in
  `Common` that touches no composable, `Natives.*`, `Applic.*` or `SharedPreferences`
  satisfies both without touching an allowlist.
- **Test command:** `./gradlew :Common:testMobileDebugUnitTest` (flavours on `origin/main` are
  `mobile|wear`, so the bare `testDebugUnitTest` is ambiguous). Baseline was 3842 tests, 0
  failures.
- **Artifacts location:** `~/Projects/Dia/medtrum-re/native/` (already populated: both ABIs,
  `SHA256SUMS`, `decomp/arm64_functions.c`, `decomp/armv7_functions.c`, `FINDINGS.md`).

## 6. Recovered behaviour (input to the port)

Full derivation with addresses is in `~/Projects/Dia/medtrum-re/native/FINDINGS.md`.
**This section is a summary, not the specification.** The normative source is the
decompilation itself (`~/Projects/Dia/medtrum-re/native/decomp/arm64_functions.c`) plus the
oracle in §8, so the plan must open with an RE-verification step that pins at least:

- the exact least-squares windowing (the read loop walks records at stride 3, so "a sliding
  window of 50 samples" in this summary is not precise enough to port from),
- the `.rodata` constant at `0x1031f0`, which participates in the prediction guard,
- the behaviour when the window is shorter than 164 records (the vendor JNI always passes
  `164`, so the tail is undefined and must be pinned deliberately).

Summary of what is already established:

- **API** (instance methods, matching the vendor Java call site):
  `predictGlucose(count, signalX100, sequence, zero, signalX100Dup, status)`,
  `getPredictGlucose(predictTimeMinutes, getMax): Int`,
  `glucosePredictReset()`.
- **Window:** 164 records of 12 bytes, sorted by `sequence`; window length is hard-coded
  natively.
- **Chain:** contiguity gate → EMA `0.75/0.25` (two cascaded stages) → least-squares
  quadratic fit over a 50-sample sliding window, rejecting records with
  `status & 0xfffe != 4` up to 8 times → average 8 coefficient triplets (`× 0.125`) →
  guarded iterated Horner extrapolation into 6 horizons → truncate to `int16` and shift the
  prediction tables.
- **Read-out:** `predictTime` 5/10/15|20/25/30/35|40 → index 1..6, anything else returns
  status `2`; the value is a running max (`getMax = 1`) or min over the first `index` entries
  of the current table, plus the previous cycle's table as the second output.
- **State:** entirely in `.bss`, zero-initialised, cleared by `glucosePredictReset`, which
  the vendor app calls on every BLE connect.

## 7. Shape of the port (my call)

**Port boundary — decided by me.** The port sits at the **12-byte record layer**, i.e. the
layer the oracle harness (§8.1) feeds directly. `push()` takes one full window of exactly
**164** records with `Int16` fields (`zero: Int32`, `status: Int16`, `sequence: Int16`,
`signalDup: Int16`, `signal: Int16`), and all state lives in the instance's fields, exactly as
the vendor's `.bss` block does. The port does **not** reproduce the vendor's Java wrapper —
`GetIntArrayRegion` narrowing, the `(short)` truncation of `signal × 100`, and bionic `qsort`
tie-breaking for equal sequences are properties of Medtrum's Java layer, not of the algorithm,
and are therefore out of the contract. `qsort` ordering *is* reproduced for the comparator's
defined key (ascending `sequence`); ties are pinned by the fixture generator instead.

- `Common/src/main/java/tk/glucodata/drivers/medtrum/native/MedtrumGlucosePredictor.kt` —
  one class holding the state block as fields, with
  `fun reset()`, `fun push(...)` (the 164-record window) and
  `fun predict(minutes: Int, max: Boolean): Int?` (null = vendor status `2`).
  The two-value read-out is exposed as a small data class, because the vendor API returns two
  numbers.
- Tests at `Common/src/test/java/tk/glucodata/drivers/medtrum/native/`.
- Golden vectors stay **outside** the repo (`~/Projects/Dia/medtrum-re/native/vectors/`),
  consistent with §2.1's strict posture — they are vendor-derived numbers. The parity test
  reads them from a path in `MEDTRUM_PREDICTOR_VECTORS` and is skipped when unset; the
  in-repo tests carry structural invariants and the vector generator itself.
- No JNI, no `System.loadLibrary`, no `jniLibs` directory anywhere in the tree.

**Decided by me, flag for review:** the predictor API is expressed in the vendor's integer
units (`signal × 100`) rather than converted to mmol/L, because any conversion belongs to the
caller and would only add rounding.

## 8. Validation strategy (the part that makes this trustworthy)

The vendor `.so` stays usable as an **oracle** without being distributed:

1. `~/Projects/Dia/medtrum-re/native/harness/` — a tiny NDK CMake project that links
   `libmdkjnidemo.so` and calls the three exported C symbols directly
   (`glucose_predict_task`, `glucose_predict_get_predict`, `glucose_predict_reset` — all
   exported, so JNI is not needed). It reads a deterministic fixture and prints
   `(status, out1, out2)` per cycle.
2. Run it on the AVD that already exists (`medtrum_re.avd`, system image
   `android-35 google_apis arm64-v8a`) — **AVD only, never a physical device**. The harness
   script must assert that the selected `adb` target is an emulator and abort otherwise; a
   physical device may be attached to `adb` at any time and the vendor blob must not be
   pushed to it. This is preferred over a glibc container because the blob is a bionic build
   (`DT_NEEDED libc.so libstdc++.so`). arm64 is the normative ABI; armv7 is a cross-check.
3. Compare against the Kotlin implementation in a unit test. Because the outputs are
   truncated integers, equality is exact.
4. Fixtures: the fixture generator is seeded, so vectors are reproducible. Cases must cover
   a steady trace, a falling trace (the alarm's actual use), a fast fall, a rise, a constant
   signal, all-zero records, non-contiguous sequences, `status` values other than 4/5, and
   multi-cycle state carry-over including `reset()`.
5. Once the driver's capture phase (handoff P0) yields real EasySense signal windows, the
   same vectors are re-run against the port as an end-to-end sanity check.

## 9. Deliverables

| # | artifact | where |
|---|---|---|
| 1 | RE findings, both ABIs, decompilation, hashes | `~/Projects/Dia/medtrum-re/native/` (done) |
| 2 | Oracle harness + vector generator | `~/Projects/Dia/medtrum-re/native/harness/` |
| 3 | Kotlin predictor + golden-vector tests | worktree, `Common/src/.../drivers/medtrum/native/` |
| 4 | Requirement recorded for the driver session: delete the vendor path (3 files) | worktree `docs/medtrum-native-predictor-findings.md` — this track does not touch that session's untracked WIP |
| 5 | Findings summary for future readers (structure and narrative only) | worktree `docs/medtrum-native-predictor-findings.md` |

## 10. Left to the builder

- Exact class/function naming and file split; §7 names are a suggestion.
- Whether the 3×3 normal-equations step is hand-written or routed through a ported
  `matrix_*` subset — the constraint is identical arithmetic order at truncation boundaries.
- The vector generator's input distributions.
- Whether golden vectors are committed as resources or generated on demand (see §11).

## 11. Open questions for review

Settled on 2026-10-17 (answers folded into §2, §4, §7, §9):

- **Parity bar:** exact equality on every vector, no divergence budget; a mismatch is
  escalated, not tolerated.
- **Ownership of the vendor-path removal:** the driver session removes its own untracked WIP;
  this track only records the requirement.
- **In-tree findings content:** structure and narrative only; all numbers, addresses and
  vectors live in `~/Projects/Dia/medtrum-re/native/`.

Still open — all three are my calls, override any of them:

1. **Wire or not:** land dormant. The port ships with tests and no caller (§2.4); the alarm
   needs a stable glucose stream, which the driver does not have yet.
2. **Location of the port:** inside the `medtrum` driver package
   (`tk.glucodata.drivers.medtrum.native`), not as a vendor-neutral `data/prediction/`
   participant — this algorithm is Medtrum-specific and must not be picked up by the generic
   prediction path.
3. **CI visibility consequence:** keeping the vectors outside the repo (§7) means CI cannot
   run the parity test at all — only structural invariants. I recommend keeping them outside
   for now and, once the port is green locally, committing at most a couple of vectors as
   regression pins rather than a corpus. If you want CI-level parity now, say so and I will
   put a small vector set in the repo instead.
