# Medtrum Native Glucose Predictor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reimplement Medtrum's vendor glucose predictor (`libmdkjnidemo.so`) as pure Kotlin in JugglucoNG, proven equal to the vendor binary by differential testing, with no vendor bytes in the repo.

**Architecture:** The vendor `.so` is used only as an offline oracle: an NDK harness on the existing arm64 emulator calls its three exported C symbols and emits golden vectors. A stateful Kotlin object reproducing the vendor's state block is then driven through the same vectors; the observable outputs are integer truncations, so the assertion is exact equality. The port lands with no caller.

**Tech Stack:** Kotlin (JVM, no Android dependencies), JUnit 4, C with `dlopen`/`dlsym` built by the Android NDK, `adb` against the `medtrum_re` AVD, Python 3 for fixture generation.

**Spec:** `docs/superpowers/specs/2026-10-17-medtrum-native-glucose-filter-design.md`

**Normative external sources** (read by every task that needs algorithm detail):

- `~/Projects/Dia/medtrum-re/native/decomp/arm64_functions.c` — full Ghidra decompilation, 25 functions
- `~/Projects/Dia/medtrum-re/native/decomp/armv7_functions.c` — 32-bit cross-check
- `~/Projects/Dia/medtrum-re/native/FINDINGS.md` — recovered chain, JNI contract, record layout
- `~/Projects/Dia/medtrum-re/native/arm64/libmdkjnidemo.so` — the oracle

## Global Constraints

- **No vendor bytes in the repo:** no `.so`, no disassembly, no decompiled dump, no constant
  tables, no address lists. The in-tree findings document carries structure and narrative only.
- **No Android dependencies in the port:** no `Applic`, no `Natives`, no `SharedPreferences`,
  no `System.loadLibrary`, no `jniLibs` directory. Plain JVM Kotlin in `Common/src/main/java`.
- **Build gates stay green:** `ArchitectureGateTests` and `ConnectModeLeverTests`
  (`Common/src/test/resources/arch/` allowlists are shrink-only). If the port trips an
  allowlist, the port is wrong, not the allowlist.
- **Test command:** `./gradlew :Common:testMobileDebugUnitTest` (the bare `testDebugUnitTest`
  is ambiguous on `origin/main`; flavours are `mobile|wear`). Baseline: 3842 tests, 0 failures.
- **Worktree:** `.worktrees/medtrum-clean`, branch `feature/medtrum-driver-clean`, base
  `origin/main` = `0b3f4daad`. One-time setup in the worktree:
  `cp <main-checkout>/local.properties .` and `git submodule update --init --recursive`.
- **Exact equality, no tolerance** (spec §2.2). A vector that cannot be matched exactly is
  escalated with its input.
- **Oracle host is the emulator only.** Because the blob is a bionic build
  (`DT_NEEDED libc.so libstdc++.so`), it runs on the `medtrum_re` AVD (system image
  `android-35 google_apis arm64-v8a`). Never push it to a physical device.
- **Repo style:** no comments unless they carry a decision or an uncertainty; `ponytail:`
  marks a deliberate shortcut together with its ceiling.
- **Commits:** the repo owner's standing rule is that nothing is committed without an explicit
  request. Every task below therefore **ends at a staged tree**; the `git commit` command is
  given so the work can be committed the moment that approval is granted, not as a licence to
  run it. All commits happen in the `feature/medtrum-driver-clean` worktree.
- **Do not touch** `Common/src/main/java/tk/glucodata/drivers/medtrum/` or
  `Common/src/main/java/com/example/` — that is a parallel session's untracked WIP. The
  vendor-path removal belongs to that session (spec §9, deliverable 4).

## Review Focus

Inputs and conditions the spec implies, most likely to bite first, each pinned by a test in
the owning task:

1. **Window shorter than 164 records.** The vendor JNI always passes 164 and never defines a
   shorter window; a reasonable reader would expect either a refusal or the vendor's
   behaviour, never a silently different computation. Task 5 pins the refusal.
2. **`predict()` before any `push()`.** The vendor tables live in zero-initialised `.bss`, so
   a fresh process answers with zeros. Task 6 pins zero rather than null or a throw.
3. **`predictTime` outside `{5,10,15,20,25,30,35,40}`.** Vendor status `2`; Task 6 pins `null`.
4. **All records rejected by the status filter** (`status & 0xfffe != 4` for every row, past
   the 8 retries). This is the branch a naive fit forgets; Task 7 carries a vector for it.
5. **`reset()` in the middle of a stream.** The vendor app calls it on every BLE connect, so
   it must clear the tables too, not only the filters. Task 6 pins zeros after reset, Task 7 a
   post-reset vector.

---

### Task 1: Pin the exact chain from the decompilation

**Files:**
- Create: `~/Projects/Dia/medtrum-re/native/ALGORITHM.md`

**Interfaces:**
- Produces: the numbered checkpoint list `C1..C12` referenced by Tasks 4–7; the fixture record
  format shared by Tasks 3 and 7.

- [ ] **Step 1: Extract the checkpoints from `decomp/arm64_functions.c`**

For each of the following, write the exact recovered behaviour plus the address it came from:

| id | what to pin |
|---|---|
| C1 | the record struct field offsets and their widths |
| C2 | the `qsort` comparator's key |
| C3 | the contiguity walk: how the tracked sequence is updated, both branches |
| C4 | the EMA stage: exact weights, exact order, which field feeds which |
| C5 | the 3-deep signal history and the exact shift order per iteration |
| C6 | the least-squares design-matrix construction: the real stride over records, the sample count, and what is put in each column |
| C7 | the status filter and the retry bound, including what happens when retries are exhausted |
| C8 | which coefficient slots the 8 triplets occupy, the averaging, and the exact multiply factor |
| C9 | the `.rodata` constant at vaddr `0x1031f0`: its value, its type, and the comparison it takes part in |
| C10 | the prediction guard: all three clauses, exact operands |
| C11 | the iterated extrapolation: the recurrence and its order, and how many horizons it produces |
| C12 | the truncation to `int16`, the write order into the current table, and the shift of the current table into the previous one |

Value of the constant at C9: read it from the ELF (`rabin2 -x` or a hexdump of file offset
`0x31f0`) and state it as a `float` and as raw bytes.

- [ ] **Step 2: Cross-check every checkpoint against the 32-bit build**

For each `C1..C12`, confirm the same structure in `decomp/armv7_functions.c` and record
"confirmed" or the difference. Any difference goes in the document as an open item.

- [ ] **Step 3: Pin the sub-164 tail behaviour**

State explicitly what the chain does for `count < 164` and for a window whose sequences are
non-contiguous. This is the answer Task 5's refusal depends on; if the vendor behaviour is
undefined garbage, say so in those words.

- [ ] **Step 4: Pin the fixture format**

Append to `ALGORITHM.md` the exact text fixture format used by Tasks 3, 4 and 7:

```
# comment
window <id> <count>
rec <zero> <status> <sequence> <signalDup> <signal>
...                                 (one per record, in window order)
reset
predict <minutes> <max>
```

`reset` clears the predictor state before the following `predict` directives or the next
`window`.

- [ ] **Step 5: Verify the document is complete**

Run: `grep -c '^| C' ~/Projects/Dia/medtrum-re/native/ALGORITHM.md`
Expected: `12`, and every row names at least one address.

---

### Task 2: Oracle harness that calls the vendor's exported symbols

**Files:**
- Create: `~/Projects/Dia/medtrum-re/native/harness/main.c`
- Create: `~/Projects/Dia/medtrum-re/native/harness/CMakeLists.txt`
- Create: `~/Projects/Dia/medtrum-re/native/harness/run.sh`

**Interfaces:**
- Consumes: the fixture format from Task 1; `arm64/libmdkjnidemo.so`.
- Produces: `main` reading a fixture file path as `argv[1]` and printing one CSV line per
  `predict` directive: `window_id,minutes,max,status,out1,out2`. Also, when the directive is
  `matrix <op>`, one line `matrix,<op>,<index>,<value>` per output cell — used by Task 4.

- [ ] **Step 1: Write `main.c`**

`dlopen(argv[2])`, `dlsym` the three symbols — `glucose_predict_task`,
`glucose_predict_get_predict`, `glucose_predict_reset` — plus `matrix_multiply`,
`matrix_inverse`, `matrix_det_3X3`, and fail loudly (non-zero exit, message on stderr) if any
is missing. Typedefs, matching the vendor ABI exactly:

```c
typedef void (*reset_fn)(void);
typedef void (*task_fn)(void *records, size_t count);          // count passed as size_t
typedef unsigned (*predict_fn)(unsigned minutes, short *out1, short *out2, int isMax);
```

Parse the fixture, build the 164-record window as a packed 12-byte struct array, call
`reset_fn()`, `task_fn(records, 164)`, then one `predict_fn` per `predict` directive, and
print the CSV lines.

- [ ] **Step 2: Write `CMakeLists.txt` and `run.sh`**

`CMakeLists.txt`: C project, one executable `oracle` from `main.c`, linked with `dl`.

`run.sh` must, in this order: require exactly one `adb` target; abort with a non-zero exit and
a message if `adb shell getprop ro.kernel.qemu` is not `1`; `adb push` the executable and
`arm64/libmdkjnidemo.so` into `/data/local/tmp/medtrum-oracle/`; `adb shell` the executable
with `LD_LIBRARY_PATH` set to that directory; stream stdout back.

- [ ] **Step 3: Start the emulator and build**

```bash
~/Library/Android/sdk/emulator/emulator -avd medtrum_re -no-window -no-audio &
adb wait-for-device

export ANDROID_NDK=~/Library/Android/sdk/ndk/29.0.14206865
cd ~/Projects/Dia/medtrum-re/native/harness
cmake -B build -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release
cmake --build build
```

Expected: `build/oracle` exists and `rabin2 -I build/oracle` reports `arm64`.

- [ ] **Step 4: Smoke-test against a hand-written fixture**

Write `~/Projects/Dia/medtrum-re/native/vectors/smoke.txt` with one window of 164 records:
`zero 0`, `status 5`, `sequence` = `n`, `signalDup` = `signal` = `1000`, and one
`predict 30 1` directive. Run `./run.sh ~/Projects/Dia/medtrum-re/native/vectors/smoke.txt`.

Expected: exactly one CSV line; `status` is `0`; both outputs are non-negative and equal to
each other (a constant trace cannot predict a different previous value).

- [ ] **Step 5: Verify the physical-device guard**

Run: `./run.sh ~/Projects/Dia/medtrum-re/native/vectors/smoke.txt` with the emulator stopped
and the rooted phone attached.
Expected: non-zero exit, message naming the non-emulator target, nothing pushed.

---

### Task 3: Fixture generator and vector corpus

**Files:**
- Create: `~/Projects/Dia/medtrum-re/native/vectors/generate.py`
- Create: `~/Projects/Dia/medtrum-re/native/vectors/expected/*.csv` (generated)

**Interfaces:**
- Consumes: the fixture format from Task 1; the harness from Task 2.
- Produces: `vectors/*.txt` fixtures and, for each, a matching `vectors/expected/<name>.csv`
  with the harness's stdout verbatim. Task 7 reads these two files.

- [ ] **Step 1: Write the generator**

Deterministic (fixed seed, no wall clock), one fixture file per scenario, each with a window of
exactly 164 records and `predict` directives for all eight `predictTime` values × `max` in
`{0,1}`:

| scenario | trace |
|---|---|
| `steady` | flat signal with small noise |
| `fall` | slow linear fall — the alarm's real use |
| `fastfall` | fall steep enough to trip the prediction guard |
| `rise` | slow linear rise |
| `constant` | one signal value repeated |
| `zero` | all five fields zero |
| `gap` | `sequence` jumps by 100 mid-window |
| `badstatus` | every record `status = 3` (the all-rejected branch) |
| `mixed` | half the records `status = 4`, half `5` |
| `twocycles` | two windows in one file, so state carries over |
| `resetmid` | window, `reset`, window, so the second is a fresh start |

- [ ] **Step 2: Generate**

Run: `python3 ~/Projects/Dia/medtrum-re/native/vectors/generate.py`
Expected: 11 `.txt` files, each with 164 `rec` lines per window.

- [ ] **Step 3: Capture the expected outputs with the oracle**

Run: for each fixture, `./run.sh <fixture> > expected/<name>.csv`.
Expected: 11 CSV files, 16 `predict` lines for single-window scenarios and 32 for
`twocycles`/`resetmid`; no empty outputs; `status` ∈ `{0,2}`.

- [ ] **Step 4: Sanity-read three of them**

Manually inspect `steady`, `fastfall` and `badstatus`: in `fastfall`, at least one horizon must
differ from `steady`'s corresponding line; in `badstatus`, record the observed `status` and
outputs in the commit message so the branch is documented, not merely captured.

- [ ] **Step 5: Record the corpus**

The corpus is not a git artifact of JugglucoNG; list its path and the 11 file names in the
Driver Session Note created in Task 8 (text only, no numbers).

---

### Task 4: Faithful float matrix ops

**Files:**
- Create: `Common/src/main/java/tk/glucodata/drivers/medtrum/native/MedtrumMatrixOps.kt`
- Test: `Common/src/test/java/tk/glucodata/drivers/medtrum/native/MedtrumMatrixOpsTest.kt`
- Create: `~/Projects/Dia/medtrum-re/native/vectors/matrix/*.txt` (oracle inputs)

**Interfaces:**
- Produces: `internal object MedtrumMatrixOps` with
  `fun multiply(a: FloatArray, aRows: Int, aCols: Int, b: FloatArray, bCols: Int): FloatArray?`,
  `fun transpose(a: FloatArray, rows: Int, cols: Int): FloatArray`, and
  `fun inverse3(a: FloatArray): FloatArray?` (null = singular). Row-major `FloatArray`; null
  mirrors a non-zero vendor return code.
  Later tasks call only these three.

- [ ] **Step 1: Capture matrix oracle vectors**

Extend the harness's `matrix` directive support (Task 2 Step 1 already reserves it): write
three fixture files with hand-chosen 3×3 and 3×1 operands — one well-conditioned, one
near-singular, one exactly singular — and capture the outputs of `matrix_multiply`,
`matrix_inverse`, `matrix_det_3X3`.

- [ ] **Step 2: Write the failing test**

`MedtrumMatrixOpsTest.kt` — one test per captured case, asserting the captured floats
**bit-for-bit** via `assertEquals(expected, actual, 0.0f)`, and one test asserting `inverse3`
returns `null` for the singular operand.

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests '*MedtrumMatrixOpsTest'`
Expected: FAIL — `MedtrumMatrixOps` is unresolved.

- [ ] **Step 4: Implement `MedtrumMatrixOps`**

Port `matrix_multiply`, `matrix_transpositon` and `matrix_inverse` from
`decomp/arm64_functions.c` preserving the accumulation order and the pivot search, restricted
to the sizes the predictor uses. Do not "improve" the arithmetic: the exactness bar (§2.2)
depends on it.

- [ ] **Step 5: Run it to verify it passes**

Run as in Step 3. Expected: PASS on every captured case.

- [ ] **Step 6: Commit**

```bash
git add Common/src/main/java/tk/glucodata/drivers/medtrum/native/MedtrumMatrixOps.kt \
        Common/src/test/java/tk/glucodata/drivers/medtrum/native/MedtrumMatrixOpsTest.kt
git commit -m "feat(medtrum): add faithful float matrix ops for the native predictor port"
```

---

### Task 5: Predictor state and window intake

**Files:**
- Create: `Common/src/main/java/tk/glucodata/drivers/medtrum/native/MedtrumGlucosePredictor.kt`
- Test: `Common/src/test/java/tk/glucodata/drivers/medtrum/native/MedtrumGlucosePredictorTest.kt`

**Interfaces:**
- Consumes: `MedtrumMatrixOps` (Task 4) and the chain from Task 1.
- Produces:

```kotlin
data class MedtrumPredictSample(
    val zero: Int,
    val status: Short,
    val sequence: Short,
    val signalDup: Short,
    val signal: Short,
)

class MedtrumGlucosePredictor {
    fun reset()
    fun push(window: List<MedtrumPredictSample>)   // exactly 164, else IllegalArgumentException
}
```

- [ ] **Step 1: Write the failing tests**

Assertions: `push` of 163 or 165 throws `IllegalArgumentException` whose message names the
required size (Review Focus 1); `push` of 164 does not throw; two consecutive `push` calls do
not throw and the second is accepted as a carry-over cycle.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests '*MedtrumGlucosePredictorTest'`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implement `push`**

Port `glucose_predict_task` per checkpoints C1–C8: sort the 164 samples by `sequence`, walk the
contiguity gate, run the EMA stages, fit the quadratic over the sliding window, average the 8
triplets. State lives in private fields mirroring the vendor's `.bss` block, including the
3-deep signal history and the current/previous prediction tables.

- [ ] **Step 4: Run to verify it passes**

Run as in Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Common/src/main/java/tk/glucodata/drivers/medtrum/native/MedtrumGlucosePredictor.kt \
        Common/src/test/java/tk/glucodata/drivers/medtrum/native/MedtrumGlucosePredictorTest.kt
git commit -m "feat(medtrum): port the native predictor window intake"
```

---

### Task 6: Prediction read-out and reset

**Files:**
- Modify: `Common/src/main/java/tk/glucodata/drivers/medtrum/native/MedtrumGlucosePredictor.kt`
- Modify: `Common/src/test/java/tk/glucodata/drivers/medtrum/native/MedtrumGlucosePredictorTest.kt`

**Interfaces:**
- Produces: `data class MedtrumPrediction(val current: Int, val previous: Int)` and
  `fun predict(minutes: Int, max: Boolean): MedtrumPrediction?` — `null` means the vendor's
  status `2`.

- [ ] **Step 1: Write the failing tests**

Assertions: a fresh instance returns `MedtrumPrediction(0, 0)` for `minutes = 30` (Review
Focus 2 — the vendor tables are zeroed, so this is not a throw); `minutes` of `4`, `9`, `21`
or `45` returns `null` (Review Focus 3); after `push` then `reset`, every `minutes` in
`{5,10,15,20,25,30,35,40}` returns `MedtrumPrediction(0, 0)` (Review Focus 5).

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :Common:testMobileDebugUnitTest --tests '*MedtrumGlucosePredictorTest'`
Expected: FAIL — `predict` unresolved.

- [ ] **Step 3: Implement `predict` and `reset`**

Port `glucose_predict_get_predict` per C12: map `minutes` to the horizon index, aggregate with
the running max or min over the first `index` entries of the current and previous tables, and
truncate to `Short` before widening back to `Int`. Extend `reset()` to clear the tables
alongside the filters.

- [ ] **Step 4: Run to verify it passes**

Run as in Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Common/src/main/java/tk/glucodata/drivers/medtrum/native/MedtrumGlucosePredictor.kt \
        Common/src/test/java/tk/glucodata/drivers/medtrum/native/MedtrumGlucosePredictorTest.kt
git commit -m "feat(medtrum): port the native predictor read-out and reset"
```

---

### Task 7: Differential parity test

**Files:**
- Create: `Common/src/test/java/tk/glucodata/drivers/medtrum/native/MedtrumPredictorParityTest.kt`

**Interfaces:**
- Consumes: the port (Tasks 4–6) and the corpus (Task 3).
- Produces: nothing downstream; this is the acceptance gate for §2.2.

- [ ] **Step 1: Write the parity test**

Read the corpus root from `System.getenv("MEDTRUM_PREDICTOR_VECTORS")`. If it is unset or the
directory is absent, the test calls `Assume.assumeTrue` with a message stating the exact
command to run it. Otherwise, for every `vectors/*.txt` with a matching
`vectors/expected/*.csv`, feed the port and assert every CSV row exactly: same `status`
(`null` ⇔ `2`), same `current`, same `previous`.

Add the fixture parser as a test-only helper; it must fail the test on a malformed fixture
rather than skipping.

- [ ] **Step 2: Run it against the corpus**

Run:
```bash
MEDTRUM_PREDICTOR_VECTORS=~/Projects/Dia/medtrum-re/native/vectors \
  ./gradlew :Common:testMobileDebugUnitTest --tests '*MedtrumPredictorParityTest'
```
Expected: PASS on all 11 fixtures. If Gradle's daemon does not see the variable, re-run with
`--no-daemon`.

- [ ] **Step 3: Confirm it actually ran**

Run the Step 2 command again with the variable omitted.
Expected: the test is reported as skipped, not passed — proving the corpus was loaded in
Step 2 rather than silently absent.

- [ ] **Step 4: Triage any mismatch**

Any mismatch is escalated as a blocker with the fixture name, the `predictTime`/`max` pair,
the two triples and the responsible checkpoint from Task 1. Do not add a tolerance (§2.2). If a
mismatch survives a faithful re-read of the checkpoint, record it in the Driver Session Note as
an open divergence and stop.

- [ ] **Step 5: Commit**

```bash
git add Common/src/test/java/tk/glucodata/drivers/medtrum/native/MedtrumPredictorParityTest.kt
git commit -m "test(medtrum): add oracle parity test for the native predictor port"
```

---

### Task 8: Findings document and the note for the driver session

**Files:**
- Create: `docs/medtrum-native-predictor-findings.md`

**Interfaces:**
- Consumes: Task 1's checkpoints as source material.
- Produces: the in-tree record of what was ported, and the removal requirement handed to the
  driver session.

- [ ] **Step 1: Write the findings document**

Structure and narrative only: what the vendor library is, what it computes, why the driver's
current use of it is wrong (a 5–40 minute prediction invoked as a smoother), how the port is
verified, and where the out-of-repo artifacts live. **No constants, no addresses, no tables,
no vector dumps** (spec §2.1).

- [ ] **Step 2: Add the requirement for the driver session**

A final section stating that the vendor path must be deleted from
`com/example/shengk/jni008/JniUtil.java`, `MedtrumNativeLoader.kt` and
`MedtrumBleManager.kt` — and that the deletion belongs to the driver session, because those
files are its untracked WIP (spec §9, deliverable 4). Name the call sites by file, not by
constant.

- [ ] **Step 3: Verify the no-numbers rule**

Run: `grep -nE '0x[0-9a-fA-F]{4,}|[0-9]+\.?[0-9]*e-?[0-9]+' docs/medtrum-native-predictor-findings.md`
Expected: no matches.

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew :Common:testMobileDebugUnitTest`
Expected: baseline 3842 plus the new tests, 0 failures; `ArchitectureGateTests` and
`ConnectModeLeverTests` green.

- [ ] **Step 5: Commit**

```bash
git add docs/medtrum-native-predictor-findings.md
git commit -m "docs(medtrum): record the native predictor port and its verification"
```

---

## Self-Review

**Spec coverage:** §1 intent → Task 8 Step 1; §2.1 byte-free → Global Constraints + Task 8
Step 3; §2.2 parity → Tasks 1–3, 7; §2.3 JVM-only → Task 4–6 (no Android imports); §2.4
dormant → no task adds a caller; §3 non-goals → no task touches the transport, the matrix
library is ported only as far as the predictor needs; §5 constraints → Global Constraints;
§6 summary → Task 1 is the RE-verification it demands; §7 shape → Tasks 4–6; §8 validation →
Tasks 2, 3, 7; §9 deliverables → Tasks 1, 3, 5, 6, 8; §11 calls → dormant, `medtrum.native`
package, vectors outside with the parity test gated.

**Step scan:** every step names a file, a command, or an assertion. The three "record what you
observed" steps (Task 3 Step 4, Task 7 Step 4, Task 8 Step 2) carry a decision the implementer
cannot make alone — the fate of an unmatchable vector, and the ownership boundary — and are
otherwise verifiable by the command given.

**Type consistency:** `MedtrumPredictSample` fields are used unchanged in Tasks 5–7;
`MedtrumPrediction(current, previous)` matches the CSV columns `out1, out2`; `push` keeps its
`List<MedtrumPredictSample>` signature across Tasks 5–7; `inverse3` is the only inverse used
by `push`.

**Review Focus:** the five lines are pinned in Tasks 5 (1), 6 (2, 3, 5) and 7 (4, via the
`badstatus` fixture).
