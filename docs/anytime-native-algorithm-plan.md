# Anytime family — reverse `libalgorithm-jni.so` and port to Kotlin

Status: plan + P1/P2 started 2026-09-14. CT3 track: shipped-blob P1/P2 first
pass (2026-09-16). NOTE: the P1/P2 baseline binary is not the shipped one — see
"Binary divergence" under the CT3 findings.

RE artifacts, the vendor `.so`, the Ghidra projects and the oracle harness live
**outside the repo** in `/Users/jetcat/Projects/Dia/anytime-native/`.

## Goal

Replace the two fallbacks that currently guess the vendor algorithm — the
empirical CT-14 model (`AnytimeAlgorithm.computeCt14`) and the CT3 linear default
(`computeLinear`) — with a faithful Kotlin implementation of the vendor
`libalgorithm-jni.so`, covering the whole Anytime family (CT2/CT-14, CT2.5, CT3,
CT4, CT5). The empirical CT-14 model stays as a validated fallback until the
port reproduces it and the reference series.

## Sources

- **Vendor binary**: `lib/arm64-v8a/libalgorithm-jni.so` (1.55 MB) and
  `lib/armeabi-v7a/libalgorithm-jni.so` (1.39 MB) inside
  `POCTech_xDrip_v_0.2.apk`. Kept **outside the repo** in a work directory; never
  committed (see `docs/ct-driver-plan.md` §4 and the legal note in
  `ct14-poctech-plan.md` §2.1).
- **JNI contract** already mirrored in-tree:
  `Common/src/main/java/ist/com/sdk/` — `AlgorithmTools` (entry points),
  `LatestData`, `HistoryData`, `DataInput`, `DataOutput`, `CurrentGlucose`,
  `KRDecodeData`, `EDevice`, `EGattMessage`.
- **State model**: the `ping` SQLite table the xDrip Poctech app persists
  (`d4/d.java` schema). It names every quantity the native chain carries across
  samples:
  `timeSampleNumber, currentIndex, minutesPerTimeSample, numElementsHistory,
   firstCalGain, lastCalGain, lastCalValue, temperature,
   SS1, Gsimple, Gbasis, GsimpleTcomp, GfinalPrevious, GfinalPreviousRaw,
   elapsedHours,
   countIw, emaIw, demaIw, temaIw,
   countIb, emaIb, demaIb, temaIb,
   countG, emaG, demaG, temaG,
   IwHistoryBack, IbHistoryBack, inputTermsBack`
- **Reference series**: the Blueberry/Apex app's own CT-14 rows (3085 records,
  `iw/ib/t/rawGlucose/filteredGlucose/glucose/slope/intercept`) and calibration
  points, captured 2026-09-14 (kept outside the repo).

## Phases

### P1 — Extract and identify entry points
1. Extract both ABIs to the work dir; record SHA-256.
2. Enumerate exports from `AlgorithmTools`: `algorithmLatestGlucose`,
   `algorithmGlucose`, `algorithm`, `decodeCT`, `getVersion`. Confirm the exact
   JNI symbol names (`Java_ist_com_sdk_AlgorithmTools_*`).
3. List the other exported/internal symbols; note any that name the chain
   (`algorithmMain`, `yqidui_PX4`, `wntrulThgs_NG4`, `GBasis`, …).

Acceptance: every entry point the Kotlin `AlgorithmTools` declares has a matching
JNI symbol, with its address and signature.

### P2 — Recover the per-sample chain and state fields
1. Decompile each entry point (Ghidra headless, `~/ghidra_scripts/DecompFuncs.java`),
   following calls into the per-id kernel.
2. Map the `LatestData`/`HistoryData`/`DataInput` fields onto the internal state
   (the `ping` names are the Rosetta stone for the state struct offsets).
3. Recover, for one plain sample: the EMA/DEMA/TEMA filters of Iw and Ib, the
   `Gsimple`/`Gbasis`/`GsimpleTcomp` temperature stage, the `K_BASE`/`K_AUTO`
   pack-in ramp, the `firstCalGain`/`lastCalGain` calibration stage, and the
   final limiter. Record the constants and where `EDevice.algorithm` changes them.

Acceptance: a written per-sample algorithm in Kotlin-ready pseudocode, with every
constant and state field sourced to an address, reproducing `log7`/`log18`
windows of `docs/MK4_FINAL_SUMMARY.md` within ~0.05 mmol/L from a warm start.

### P3 — Kotlin port
1. New `drivers/anytime/AnytimeNativeAlgorithm.kt`: a stateful, per-sensor
   kernel with the P2 chain, keyed by the persistent sensor id (same pattern as
   `AnytimeCalibrator`). No Android dependencies.
2. Family dispatch by `FamilyEntry.algorithm` (1/6/7/8 for CT2, 3 for CT3, 10/12
   for CT4, 11 for CT5), mirroring `nativeAlgorithm()`.
3. Persist/restore the state via `AnytimeRegistry` (extend the existing
   calibrator-state keys).

### P4 — Wiring and fallback order
`NATIVE-PORT → MODEL (empirical CT-14 / MK4) → LINEAR`, surfaced in
`getAlgorithmDiagnostics()` and `sensorDetailTelemetry` so a switch is visible.

### P5 — Validation
- Unit: fixed `log7`/`log18` windows; the Blueberry CT-14 series; per-family
  smoke tests on captured frames.
- Live: CT-14 (SN08), CT3 and CT4 sessions compared against the Reference App.

## Non-goals / constraints
- No vendor binary or resource committed to the repo.
- CT3/CT2.5/CT4/CT5 behaviour must not change until their port is validated; the
  CT-14 empirical model is the only active change so far.
- The `Ib` question (does it participate?) is answered by P2, not by guessing.

## Findings

### P1 — entry points (arm64)
SHA-256: `be2409b8…f05d68` (arm64), `e33b6a88…30cb9` (arm32).

| JNI export | address |
|---|---|
| `algorithmLatestGlucose` | 0x10aec4 |
| `algorithmGlucose` | 0x109984 |
| `decodeCT` | 0x10c55c |
| `getVersion` | 0x10c258 |

No `algorithm(DataInput)` export in this build (matches the `AlgorithmTools`
comment). The `.so` keeps internal names, so no guesswork needed:

```
algorithmLatest / algorithmMain / algorithmHistory
algorithmBody_CT2_1 / _CT2_2 / _CT2_44V / _CT2_55V / _CT2A_44V / _CT2A_XINGJIAN / _CT3 / _CT4 / _YUWELL
glucoseCalculate_CT2_1 / _CT2_2 / _CT2_44V / _CT2_55V / _CT2A_44V / _CT2A_XINGJIAN / _CT3 / _CT4 / _YUWELL
checkInputData_* / update_* / currentCompensation_* / calculationK_* / setTrend_*
adcAlgorithm / adcAlgorithm_YUWELL / updateGlucosePredictionAndState
initializeGlucosePredictionAlg / GLUCOSE_COMPENSATION / GLUCOSE_COMPENSATION_CT3
setInput / setDate
```

### P2 — chain for CT-14 (SN08, `algorithm == 1`)
```
JNI algorithmLatestGlucose
  -> algorithmLatest(Iw, Ib, T, K0, R, …, algorithmId, …)
       setDate(date, glucoseId, day, hour, minute)
       setInput(…, input)
       switch (algorithmId): checkInputData_CT2_1…CT4FromAdapter
       -> algorithmMain(output, algorithmId)
            -> update_CT2_1(input, dynamic, output, date, internalState)
                 -> algorithmBody_CT2_1
                      currentCompensation_CT2_1(state)
                      calculationK_CT2_1(state)
                      raw = (state[0x54] - state[0x800]·c1) / (state[0x78]·(state[0x28]·c2 + 1))
                      step-limit ±1.2 mmol
                      adcAlgorithm(state) -> updateGlucosePredictionAndState(...)
                      setTrend / hypo / hyper warn
                 -> write output struct
```
`algorithmId` 1..9 maps to the EDevice algorithm ids: 1 CT2_1, 2 CT2_2, 3 CT3,
4 YUWELL, 5 CT2_44V, 6 CT2_55V, 7 CT2A_44V, 8 CT2A_XINGJIAN, 9 CT4.

Temperature stage (CT2_1): `temp_mul = (T − refT)·(−0.025) + 1.0`, applied to both
Iw and Ib; then per-sample step clamps (±5.0, ±2.0) and a 480-sample moving
average into `state[0x800]`. Note the slope is `-0.025`, not MK4's `-0.04593`.

#### `input` struct (filled by `setInput`)
`+0x00 flags`, `+0x04 Iw`, `+0x08 Ib`, `+0x0c T`, `+0x18 K0`, `+0x1c R`,
`+0x38 sickDuration`, `+0x3c left`, `+0x40 right`, `+0x44 width`, `+0x48 len_iw`,
`+0x4c enzyme_activity`, `+0x50 membrane_layers`, `+0x54 sensorInfo`, `+0x5c batch`.

#### `output` struct (JNI reads it back into `CurrentGlucose`)
`+0x00 glucoseId`, `+0x04 dayCount`, `+0x0c hour`, `+0x10 minute`, `+0x14 Iw`,
`+0x18 Ib`, `+0x1c Iw2`, `+0x20 Ib2`, `+0x24 Iw3`, `+0x28 Ib3`, `+0x30 T`,
`+0x3c BG`, `+0x40 BGMG`, `+0x44 Glu`, `+0x48 GluMG`, `+0x4c Glu_AI`,
`+0x50 GluMG_AI`, `+0x54 BGCount`, `+0x58 BGICount`, `+0x5c warn`, `+0x60 error`,
`+0x64 trend`, `+0x68 calibrationStatus`, `+0x80 K_BASE`, `+0x84 K_AUTO`,
`+0x88 sensitivity`, `+0x8c Iw48base`, `+0x90 Iw48IIR`, `+0x94 Iw30IIR`,
`+0xa0 Iw4`, `+0xa4 SD_GLU`.

#### `internalState` struct (per sensor, persisted by xDrip's `ping` table)
`+0x10 glucoseId`, `+0x14 samples`, `+0x18 rampIndex`, `+0x1c`, `+0x2c Iw`,
`+0x30 Ib`, `+0x58 T`, `+0x68 refT`, `+0x78 gain`, `+0x80…+0x7fc` float history
(480 entries), `+0x800` history average, `+0x804` history count, `+0x808` raw
glucose, `+0x80c` filtered, `+0x810` mg/dL, `+0x814/0x818` predictor output,
`+0x848` hypo/hyper flag, `+0x890`, `+0x8ac` errorCode, `+0x8b8` abnormal counter.

### P2 — recovered CT2_1 stages (decompiled, constants from data)

All addresses below are Ghidra image addresses (image base 0x100000).

**State init / validation**
- `initializeGlucosePredictionAlg(state, 3)`: zero `state`, `state[8]=3`,
  `EMAfilter_init` on four banks at 0x58/0x68/0x78/0x88 (Iw, Ib, G, aux) with
  alphas 0.05/0.3/0.7, `state[0x54]=1.0`.
- `checkInputData_CT2_1`: `input[0]==0` → init + `memset(state,0,0x8c8)`,
  `state[0xc]=2`; contiguous id (`input[0]==state[0x10]+1`) → keep; else re-init.
  `state[0x10]` = last glucose id, `state[0xc]` = session state.

**Temperature / current stage (`algorithmBody_CT2_1`)** — runs once `state[0x18]>=1`:
```
temp_mul   = (T − refT)·(−0.025) + 1.0        # applied to Iw and Ib
Iw'        = Iw·temp_mul
Ib'        = Ib·temp_mul
Iw'  = clamp-step(Iw', prev_Iw, ±5.0)
Ib'  = clamp-step(Ib', prev_Ib, ±2.0)
hist[state[0x804]++] = Ib'                     # up to 480 entries at state[0x80..0x7fc]
state[0x800] = mean(hist)                       # 480-sample moving average
if (Ib' - state[0x800]) >= 2.0:
    Iw' -= (Ib' - state[0x800]) · input[7]      # input[7] = right (geometry term)
state[0x48] = Iw'
```
`param_1[4]==0x80` (new fingerstick applies to this id) sets
`state[0x68]=input[3]` (refT) and `state[0x60]=input[5]`, `state[0x5c]=input[5]/18`
(reference mmol).

**Compensation (`currentCompensation_CT2_1`)**
```
if state[0x18]==1: φ = 4.0; track = (state[0x48]-state[0x800]) - (state[0x5c]+0.6)·4.0
else:              state[0x4c] = 0.75·state[0x48] + 0.25·state[0x4c]   # EMA
                   state[0x44] = 0.75·state[0x40] + 0.25·state[0x44]
i = state[0x1c]
state[0x50] = state[0x4c] + φ·(−0.6) − exp(−0.03·i)·track
ramp        = min(i, 1440)/1440            # state[0x28]; i capped at 1440 (0x5a0)
state[0x54] = state[0x50] + (i·0.7·φ)/(−1440)
```

**Gain (`calculationK_CT2_1`)** — `state[0x54]/state[0x5c]` blended into
`state[0x6c]/[0x70]/[0x74]` by `state[0x18]` (1/2/3), ±100 % guard, result in
`state[0x78]`.

**Raw glucose** (in `algorithmBody_CT2_1`, the `*0.0` factors are genuine zeros
for CT2_1):
```
raw = state[0x54] / state[0x78]
if state[0x18] > 3: raw = prev_raw + clamp(raw − prev_raw, ±1.2)   # step limiter
state[0x808] = raw ; state[0x810] = (int)(raw·18)
```

**Predictor (`updateGlucosePredictionAndState`, called from `adcAlgorithm`)**
Signature (from the call): `(Iw, Ib, rawMgdl, T, flags=(id==1)<<7, newBg, state, &out)`.
- sample counter `state[0]` wraps at 1920 (0x780); elapsed minutes `state[0x14]`.
- EMA banks: Ib (α=0.05) → `state[0x15]`; `min(Ib, 0.75·Iw)` (α=0.3) and Iw (α=0.7)
  → history rings `state[0x117…]` / `state[0x26…]`.
- `FUN_0011bab8(state, …)` → residual predictor (`state[0x208…]`).
- error codes: 2 (Iw'≤0), 3 (Ib'-Iw'≤0), 4 (no fingerstick), 5 (continuous abnormal).
- history-weighted correction (FIR over `state[index+0x208]`, coefficients from the
  tables below) selected by elapsed minutes (3 / 22 / 167) and `state[8]>=8.0`,
  blend weight 0.5, clamp ratio to [DAT_0x213d90=0.9, DAT_0x213dc0=1.6].
- final EMA with alpha `max(0.3, DAT_0x213e98=0.4 − elapsed/340)`; output clamped
  to [30, 450] mg/dL → `state[0x12]`.

**Numeric constants** (doubles in `.rodata`): `1.6` (0x213dc0), `0.9` (0x213d90),
`0.98` (0x213e90), `0.4` (0x213e98), `0.3` (0x213ea0); EMA alphas `0.05/0.3/0.7`;
temperature slope `−0.025`; history 480; ramp cap 1440; decay `exp(−0.03·i)`.

**Coefficient tables** (each `<int index, float coeff>`, index into the 0x208
history): `0x213ec0` (94), `0x2141b0` (80), `0x214430` (113), `0x2147b8` (111).
Dump in full when porting (Ghidra `DumpData.java`).

**Trend (`setTrend_CT2_1`)** — 5-sample ring at 0x84c and 10-sample at 0x860 of
`state[0x80c]`; `d=(state[0x85c]−state[0x84c])·0.25`; code in `state[0x890]`:
`d>=0.3→2`, `d<=−0.3 && last>5→−2`, `d<=−0.3→−3`, `d>=0.15→1`, `d>−0.15→0`,
else `−1`.

**EMA bank (`EMAfilter_nextSample`, confirmed)** — classic EMA/DEMA/TEMA:
```
first sample: ema = dema = tema = s
else: ema = (1−α)·ema + α·s ; dema = (1−α)·dema + α·ema ; tema = (1−α)·tema + α·dema
out DEMA = 2·ema − dema ; out TEMA = 3·ema − 3·dema + tema
```
`EMAfilter_init` zeroes the bank and seeds the first sample.

### P2 — `FUN_0011bab8` (feature extractor, ~7.1k-line unrolled C)

Effective signature `(float* state, int index, float* inputTerms)`. No loops — the
compiler fully unrolled the generator. It writes **376 input terms**
(`inputTerms[0x0b..0x174]`, xDrip's `inputTermsBack`) that the 4 coefficient
tables then dot into (the linear model).

- **Lag set**: 12…180 minutes step 3 (55 values). Sample lag = `lagMin / state[2]`
  (`state[2]` = minutesPerTimeSample).
- **Term form**: `(ringA[lagNum] − ringB[lagNum]) / (ringA[lagDen] − ringB[lagDen] + 1e-10)`,
  scaled by one of `state[0xf]`, the gain ratio `state[6]/state[5]`, or `+1/−1`.
  Ring A = `state[0x26…]`, ring B = `state[0x117…]`, indexed
  `(index − lag + state[4]) % state[4]` (`state[4]` = numElementsHistory).
- **Constants** (doubles in `.rodata`): `0.9`, `1.05` (0x213ea8), `−0.0178`
  (0x213eb0), `−4.0e-4` (0x213eb8), `0.2` (0x2136c0), `0.4`, `0.11` (0x2136d0),
  `1.6`, `0.98`, `0.4`, `0.3`; gain temp term
  `1 + (−0.00356)·clamp(state[0xb] − 31.5, −11.5, 10.5) + (−4e-4)`.
- Scale histogram: `state[0xf]` ×133, raw ratio ×115, `state[0xd]`-style ×47,
  `state[0xe]`-style ×39, `−1.0f` ×31, `state[0xc]` ×8.

**`FUN_0011bab8` clean view** (signature forced to
`(float *state, int index, float *inputTerms)`; dump in `FUN_0011bab8_clean.c`).
Confirmed generator form, verified in the decompiled body:

```
inputTerms[0] = 1.0                                    # bias
lagDen = 12 / state[2]                                 # state[2] = minutesPerTimeSample
for each term k (1..N), with its own numerator lag L (= LAG[k]/state[2]):
    if L <= state[0] and lagDen <= state[0]:           # state[0] = timeSampleNumber
        iN = (index - L + state[4]) % state[4]          # state[4] = numElementsHistory
        iD = (index - lagDen + state[4]) % state[4]
        num = state[iN+0x26] - state[iN+0x117]
        den = state[iD+0x26] - state[iD+0x117] + 1e-10
        term = num / den
    else:
        term = -1.0
    inputTerms[k] = term * scale_k
```
Pre-scale state: `state[0xc] = state[6]/state[5]`,
`state[0xf] = normalize(state[6])·(ring[index]-ring2[index]) / gainTemp`,
`state[0x10]`, `state[0x11]` likewise; `gainTemp = 1 + (−0.00356)·clamp(state[0xb]−31.5,
−11.5, 10.5) + (−4e-4)`.

`scale_k` is one of `state[0xf]`, the gain ratio, `state[0xd]/[0xe]`-family values,
or `−term`; the 376 terms are organised in several groups with different scales
(a single 55-lag cycle is not enough — see `ct2_1_input_terms.csv`).

### Open items (P2 → P3)
- Turn the term extraction into a full generator: parse `FUN_0011bab8_clean.c`
  (or disassemble) into the exact ordered `(k, LAG[k], scale_k)` table, then emit
  the Kotlin loop. Artifacts so far: `FUN_0011bab8_clean.c`,
  `ct2_1_input_terms.csv`, `ct2_1_coeff_tables.csv` (all outside the repo).
- Map every `ping` column onto `internalState` offsets to confirm the state model.
- Then P3: port as `AnytimeNativeAlgorithm.kt`, keep the empirical CT-14 model as
  the fallback, validate against the Blueberry series and `log7`/`log18`.

## CT3 native track (added 2026-09-16)

Status (2026-09-18): **P1–P4 done; PAUSED at P5.** Full CT3 chain decompiled on the
shipped lib, Kotlin port `AnytimeNativeAlgorithm.kt` validated against the Unicorn oracle
(200/200, worst `5.8e-6`), wired `NATIVE-PORT → LINEAR` (see work log). **P5 (live
validation) is blocked until a real SN16 (CT3) sensor capture exists — do not resume CT3
work without one.** CT3 is the
product's primary target (`SN16` = CT3 4/4H) and the one family with **no
fallback of its own**: with the vendor `.so` absent it drops to `computeLinear`
(a K/R straight line, `AnytimeConstants.kt:339`). The vendor path is still
permitted for CT3/CT5, but nothing ships the blob, so a CT3 user without it gets
the linear guess. This track gives CT3 the same treatment CT-14 and MK4 already
got: a validated, in-tree Kotlin chain.

### Why it is its own track, not constants on CT2
The vendor dispatcher sends CT3 to a separate kernel, not the CT2_1 chain
(`docs/ct-driver-plan.md` §1.1a; in this arm64 build those names are
`update_CT3` / `setSensitivityCoefficient_CT3` — see the findings below), which
runs a statistical-block update the CT2_1 chain never uses. CT3 shares the
`input`/`output`/`internalState` structs and the EMA bank, but its temperature,
statistics and final-filter stages must be recovered separately. Do not assume
CT2 constants apply.

### CT3-P1 — entry points and dispatch
1. Confirm the `algorithm == 3` JNI path: `AlgorithmTools.*` → `algorithmMain` →
   table `0x11bfd4` → `yqidui_PX3`. Cover the ultrasonic remaps the app already
   does in `AnytimeAlgorithm.nativeAlgorithm` (`AnytimeAlgorithm.kt:702`): `3 ↔ 9`
   by `voltageFlag`, `12/9 → 3`, `9 → 10`. Record which symbol each id hits.
2. Enumerate the CT3 symbols: `algorithmBody_CT3`, `glucoseCalculate_CT3`,
   `GLUCOSE_COMPENSATION_CT3`, and the `SUTC_SD_GE3_PD5` helpers they call.
3. Extend the P1 address table with these, plus the ABI SHA-256.

Acceptance: every id a `FAMILY_TABLE` entry can dispatch (SN16/CT3_*,
CT3_PLUS, CT3_YUWELL, CT3_ULTRASONIC) maps to a named function and signature.

### CT3-P2 — recover the per-sample chain
1. Reuse the P2 struct maps (`input`/`output`/`internalState`) — same SDK, only
   the kernel differs.
2. Decompile `yqidui_PX3` / `algorithmBody_CT3` end to end into the same stage
   list CT2-P2 produced (current, temperature, compensation, gain, raw,
   statistics, predictor, trend).
3. Reverse `SUTC_SD_GE3_PD5`: inputs, window lengths, and where its output feeds
   the final filter.
4. Verify the `Iw`/`Ib` order and fixed-point scale at the frame boundary —
   `docs/ct-driver-plan.md:383` warns CT3/CT2.5 swap them and use `/100` vs `/10`;
   the parser is meant to normalise this, so confirm it does.

Acceptance: Kotlin-ready pseudocode with every constant sourced; a CT3 window
(live capture or vendor oracle) replayed to CT2-P2 tolerance (≤0.05 mmol/L).

### CT3 findings — binary divergence, shipped P1/P2 (2026-09-16)

#### Binary divergence (read this first)
The P1/P2 RE baseline (SHA `be2409b8…`, non-obfuscated names, `algorithmMain`
cases 1..9) is **not** the binary that ships. The app packages
`Common/src/main/jniLibs/arm64-v8a/libalgorithm-jni.so` (gitignored; SHA
`1303a448…`), a **newer build**: different layout, **obfuscated symbol names**
(`yqidui_PX3`, `SUTC_SD_GE3_PD5`, `wntrulThgs_NG3`, …), `algorithmMain` cases
**1..12**, and changed constants. CT3 must be ported from the shipped blob; the
baseline decompilation is only a logic template (≈90 % identical, but the
constants moved — see below). Ghidra project for the shipped blob:
`proj_ship/anytime_ship`. Like the baseline, `Ghidra addr = file vaddr + 0x100000`.

#### Shipped CT3 entry points and dispatch
`algorithmMain` case 3 → `yqidui_PX3_QysrKoeqyhs` (12-byte thunk) →
`yqidui_PX3` (kernel). Cases 10/11 both go to `yqidui_PX4_QysrKoeqyhs`
(CT4/CT5), case 12 to `yqidui_PX6_2_PwsxKefsuis`; ids 1/2/4/5/6/7/8/9 map to
`PX2_1/PX2_2/LYHNWY/PX2_44F/PX2_55F/PX2J_44G/PX2J_KSYQASHY/PX6`. So the vendor
now dispatches 10/11/12 itself — the app remap in `AnytimeAlgorithm.kt:702` is
still what picks 3 vs 9 vs 12 for the CT3 sub-families.

| shipped symbol | role (baseline name) | shipped addr | baseline addr |
|---|---|---|---|
| `yqidui_PX3_QysrKoeqyhs` | update_CT3_FromAdapter (thunk) | 0x171e4 | 0x1158a4 |
| `yqidui_PX3` | **update_CT3 (kernel)** | 0x171f0 | 0x1158b0 |
| `emlrsmuokMrif_ND3` | algorithmBody_CT3 | 0x176ac | 0x115d58 |
| `gbqfvpbagtqCNriShLbhnhvpbli_NG3` | calculationRAndIwBackground_CT3 | 0x17c6c | 0x1162dc |
| `kfyRx48_GE3` | getIw48_CT3 | 0x17f68 | 0x1165e8 |
| `wfyBfrtprnynacNspjgnfjioa_NC3` | setSensitivityCoefficient_CT3 | 0x18154 | 0x1167d4 |
| `kmzfpwfPyqfzseyi_MA3` | glucoseCalculate_CT3 | 0x184f0 | 0x116b3c |
| `wfyCsiok_NC3` | setTrend_CT3 | 0x186c8 | 0x116d14 |
| `QSFLVCL_GZVARXDKKSVY_JD3` | GLUCOSE_COMPENSATION_CT3 | 0x119628 | 0x213580 |
| `SUTC_SD_GE3_PD5` | **INIT_IW_CT3** (the `SUTC_SD_GE3_PD5` of the plan) | 0x118cf8 | 0x212bb4 |
| `SUTC_SD3_NC3` | INIT_IW3_CT3 | 0x119158 | 0x212fa8 |
| `xfrsfvbaswhXhms_TD3` | temperatureMain_CT3 | 0x118b70 | 0x212a34 |
| `wntrulThgs_NG3` | smoothMain_CT3 | 0x76ac | 0x1064ec |
| `vbsjfVjtgy_NG3` | rangeLimit_CT3 | 0x7738 | 0x106578 |
| `vbsjfCnvmyk_oyqp_MA3` | rangeSmooth_hull_CT3 | 0x79e0 | 0x106820 |
| `vbsjfCnvmyk_oyqp_wufemi_PX3` | rangeSmooth_hull_stable_CT3 | 0x7b94 | 0x1069d4 |
| `ZBWBL` / `ZBWBL_JUEYPP` | PULSE / PULSE_CHANGE | 0x119164 / 0x119290 | 0x212fb4 / 0x2130a8 |
| `XVTBL_JG3` | NOISE_CT3 | 0x1193f4 | 0x2131c0 |
| `KAENUEHGMZW_PD3` | ATTENUATION_CT3 | 0x119428 | 0x2131d4 |
| `DHVN_YMS_NC3` | TAKE_OFF_CT3 | 0x119460 | 0x21320c |
| `OYCXY_PJ_NC3` | ERROR_IW_CT3 | 0x11949c | 0x213248 |
| `DVFLO_JG3` | TOUCH_CT3 | 0x1194d0 | 0x21327c |
| `tifvf_JG3` | BREAKAGE_CT3 (returns 2/3) | 0x119604 | 0x21326c |
| `GHENYZYBSQ_QNSWEIO_MRN_PJ_NC3` | WATERPROOF_FAILURE_BIG_IW_CT3 | 0x11957c | 0x213324 |
| `GHENYZYBSQ_QNSWEIO_NC3` | WATERPROOF_FAILURE_CT3 | 0x1195e0 | 0x21338c |
| `yqiduiNrshrxlZvjlmdylprHtbDwfai` | updateGlucosePredictionAndState | 0x214d8 | 0x11b39c |

`SUTC_SD_GE3_PD5` is the statistical block the plan flagged; `tifvf_JG3` takes
`(prevTrend, count)` and returns 2/3, so it is the trend helper rather than
`BREAKAGE` — the exact role is a P2 open item.

Full shipped chain dumped in `ct3_ship_chain_clean.c`; mapping and constants in
`ct3_ship_map.txt`. Baseline dumps remain: `ct3_chain_clean.c`,
`ct3_misc_clean.c`, `ct3_constants.txt`. Scripts: `ListCT3`, `DecompCT3`,
`DecompMisc`, `DumpCT3Const` (baseline) and `DecompShip`, `DecompShipCT3`,
`DecompShipCT3b`, `DecompShipCT3c`, `DumpShipConst` (shipped).

#### CT3-P2 — stage chain (first pass)
`update_CT3(input, output, unused, date, state)`:
```
state.glucoseId(+0x10) = input.glucoseId(+0x00)
state(+0x20)           = input.Iw|Ib (8-byte pair; +0x20 Iw, +0x24 Ib)
state(+0x148)=10 ; +0x118=0 ; +0x44=input.T(+0x0c) ; +0x64=input.R(+0x1c)
if date(+8) != state(+8): state(+8)=date(+8); state(+0xc)=0
state(+0x160)=0 ; +0xcc=0
if PULSE(Iw|Ib, state(+0xb8)) == 1: input.Iw = PULSE_CHANGE(input.Iw)   # STOCHASTIC
INIT_IW_CT3(input.Iw, input.glucoseId)
if NOISE(>6) | WATERPROOF_BIG_IW | TOUCH | TAKE_OFF: state(+0x14c)=0xd/0xf/0x10/0xb + reset
else state(+0x14c)=0, compute ramp (state+0x18) + geometry (state+0x50)
newBg (input+0x10==0x80 && state(+0x15c)!=0):
    state(+0x4c)=input.newBg(+0x14) ; state(+0x48)=newBg/18
state(+0x28) = temperatureMain_CT3(iw=+0x20, ib=+0x24, t_ref=s8, t_cur=+0x44)
state(+0x2c) = old state(+0x24) (Ib)
smoothMain_CT3(...) ; INIT_IW3_CT3(...)
if ERROR_IW_CT3(input.glucoseId) != 1: algorithmBody_CT3(input, output, date, state)
write output struct (offsets differ from CT2: +0x11 glucose, +0x12 mgdl,
    +0x13/+0x14 second glucose, +0x15..+0x19 status/trend, +0x20..+0x29 telemetry)
```
`algorithmBody_CT3`:
1. **IW48 stage** state(+0x9c)/count(+0xa4): warm-up mean for first 480, then
   EMA `0.9986111·x + 0.0013889·iw`.
2. `calculationRAndIwBackground_CT3` (`gbqfvpbagtqCNriShLbhnhvpbli_NG3`):
   `exp(-0.008·glucoseId)` decay; the pre-stage picks `Iw·5` and scans down in
   0.5 steps; background counts 240/480 at +0xac with alphas
   `0.9979167/0.00208333`; sets +0x98 (=**0.1**·bg, shipped; baseline 0.05),
   +0x3c = `(bg - bg98) or 1`, +0xa0.
3. `getIw48_CT3` (`kfyRx48_GE3`): +0xb8 EMA (alpha `0.00104167/0.99895835`),
   +0xbc count 960, ratio guards `[-0.2, 0.5)`, baselines +0xc0/+0xc4 set around
   glucoseId 0x975 (2421) with a 20-step ramp.
4. `setSensitivityCoefficient_CT3`: `state(+0xcc) = (b0 - c0)/c0`, ramped by
   `sinf` (SUTC-style); `state(+0xd0) = (b0 - c4)/c4`; error dispatch →
   `KAENUEHGMZW_PD3`(→0xc), `GHENYZYBSQ_QNSWEIO_NC3`(→0x10), `LYPJRKNR_NC3`(→0xe);
   final `state(+0x40) = state(+0x3c) / (state(+0xcc)+1)`.
5. **warm-up gain ramp** state(+0x60→+0x6c) by rampIndex `state(+0x18)` —
   shipped thresholds are **120/320/600** (baseline 60/120) and factor `0.7`:
   `<120`: `0.7·X + 0.1·X·n/120`; `120..319`: `0.8·X + 0.1·X·(n-120)/200`;
   `320..599`: `0.9·X + 0.1·X·(n-320)/280`; then `X`. Also the kernel sets
   `state(+0x60) = input.K0 · **1.35**` (baseline `1.3`).
6. `glucoseCalculate_CT3`: `g = state(+0x40)/state(+0x6c)`; if samples<4 store;
   else gate `g / mean(960-ring) <= 0.9`; `state(+0xd4) =
   GLUCOSE_COMPENSATION_CT3(prev(+0xd4), g, SD_GLU(+0x106c), gate)`;
   `mgdl = round(g·18)` → +0xdc/+0xe4; `+0xe0 = g`; warn `+0x118 = 2 (<3.05) / 3 (>22)`;
   push to 960-ring (+0x16c/+0x168); `+0x106c = sqrt(variance)` (SD_GLU).
7. `setTrend_CT3`: 10-ring of +0xe0 at +0x11c; `d = ((new-old)/9)/3`; thresholds
   `+0.11 → 2`, `-0.11 (last>5) → -2`, `-0.11 → -3`, `+0.06 → 1`, `>-0.06 → 0`, else `-1`.
8. trend → `state(+0x148)`; `algorithmBody_CT3` also has a "stuck" branch:
   `state(+0x14c) != 0 && (1<<id)&0x15000` clears the whole chain.

**Temperature (`temperatureMain_CT3`)** — identical in both builds
(`xfrsfvbaswhXhms_TD3`). Pinned from the baseline call-site disassembly
(`update_CT3+0x3a4`): `s0=state+0x20` (Iw, the multiplier), `s1=state+0x24` (Ib,
**unused**), `s2=s8` (`t_ref`), `s3=state+0x44=input.T` (`t_cur`):
```
clamp12(x)  = clamp(0.12x + 31.4, 32.5, 35.7)
clamp055(x) = clamp(0.055x + 34.585, 35.6, 36.5)
mul = 1 + ((clamp12(t_cur) - clamp12(t_ref)) + (clamp055(t_cur) - clamp055(t_ref))) · (-0.5) · 0.035
return mul · iw      # -> state+0x28 ; state+0x2c = old Ib
```
`t_ref` (`s8`) is `state+0x50` by default, set to **32.0f** in the branch
`input.T <= 0.01 && ramp<=0 && glucoseId >= prev-1`, or to `input.T` in the
newBg branch (`input+0x10==0x80`); it is written back to `state+0x50` later.
This corrects the earlier "geometry/`iVar11`" reading — `iVar11` was `32.0f`.

**`GLUCOSE_COMPENSATION_CT3(prev, next, SD, gate)`** — asymmetric step limit:
down-limit from `prev` (`<3: 0.1`, `3..4: 0.2`, `4..5: 0.3`, else the SD value),
up-limit from SD (`<=1.2: 0.5`, `<=1.8: 0.7`, `<=2.5: 0.9`, else 1.0; the SD
value is `0.4/0.6/0.8/1.0`); clamp `next-prev`; if `prev>15 || gate`:
`next = 0.2·prev + 0.8·next`.

**Non-determinism:** `PULSE` (`ZBWBL`) / `PULSE_CHANGE` (`ZBWBL_JUEYPP`) inject a
random Iw (`rand()` seeded by `time()`); shipped rewrote the trigger to ratio
tests (`Iw/Ib > 2.0` and `Iw/avg10 > 2.5`, or `Iw > 70`; reset on
`0.5 < ratio < 1.5`) and scales the perturbation by the last-5 stddev. Exact
replay is impossible; the port must drop it or model the mean — P3 decision.

**Shipped vs baseline, short list** (port from shipped):
`K0·1.35` (was 1.3); warm-up thresholds 120/320/600 (was 60/120); background
`0.1·bg` (was 0.05); `PULSE` trigger rewritten; `NOISE` now also requires
`SD_GLU>5.6`; constants moved from `.rodata 0x2136xx` to `0x21b7xx/0x21bfxx`.
Everything else in the chain (IW48, getIw48, sensitivity/sinf, temperature,
compensation, trend, output layout) is logically the same.

#### Input struct — corrected map (used by the CT3 chain)
The main-track `input` map has the head mislabelled. Confirmed via
`setInput`/`algorithmLatestGlucose`:
`+0x00 glucoseId` (not flags), `+0x04 Iw`, `+0x08 Ib`, `+0x0c T`,
`+0x10 flags` (0x80 = new fingerstick applies to this id), `+0x14 newBgValue`,
`+0x18 K0`, `+0x1c R`, `+0x20 checkContinuousAbnormal`, `+0x24 userType`,
`+0x28 gender`, `+0x2c age`, `+0x30 height`, `+0x34 weight`, `+0x38 sickDuration`,
`+0x3c left`, `+0x40 right`, `+0x44 width`, `+0x48 len_iw`,
`+0x4c enzyme_activity`, `+0x50 membrane_layers`, `+0x54 sensorInfo`, `+0x5c batch`.
The tail from `+0x38` matches the existing map; the head `+0x00..+0x34` is corrected.

#### Open questions — status
- `SUTC_SD_GE3_PD5` changes the final filter: **yes**, the statistics
  (`+0x40`, `+0xcc`, `+0xd4`, `+0x106c`) feed `glucoseCalculate_CT3` →
  `GLUCOSE_COMPENSATION_CT3`, i.e. the output path, not just shared state.
- Sub-families 3 vs 9 vs 12: `algorithmMain` now dispatches 9→`PX6` and 12→
  `PX6_2`, 10/11→`PX4`; the CT3 kernel is `yqidui_PX3` only. The app remap
  (`AnytimeAlgorithm.kt:702`) is still what sends a CT3_ULTRASONIC id to 3, so
  which vendor kernel a given sub-family really hits needs a quick check against
  the app's post-remap id.
- `Ib`: CT3 stores `Ib` at `state+0x24`, but the recovered temperature stage
  doesn't read it, and no other recovered stage does either. Likely unused, as in
  CT-14 — confirm during the oracle replay.

### CT3-P3 — Kotlin port
1. Extend `AnytimeNativeAlgorithm.kt` with the CT3 kernel, selected by the
   post-remap `algorithm` id in {3, 9, 12}.
2. Reuse `AnytimeNativeState`, `AnytimeEmaBank`, the term generator and
   `guardGain`; add the CT3 statistics stage and its state fields, keeping the
   offset map and the `ping`-column mapping in sync.
3. Persist/restore through `AnytimeRegistry` under the same per-sensor key.

### CT3-P4 — wiring and fallback order  (done 2026-09-16)
`NATIVE-PORT (CT3) → LINEAR` for the CT3 families. When the blob is present the
existing `isNativeAvailable` path still wins (CT3/CT5 precedence unchanged).
Chosen source is surfaced in `getAlgorithmDiagnostics()`.

Implemented:
- `Source.NATIVE_PORT` added to `AnytimeAlgorithm`; `isCt3Family()` covers
  CT3/CT3_PLUS/CT3_YUWELL/CT3_ULTRASONIC.
- In `compute()`, after the native path: CT3 families go to
  `computeCt3NativePort()` when `calibration != null && advanceModelFallback`
  (live only), else `LINEAR`. CT3 never uses MODEL; CT5 is untouched.
- Per-sensor `AnytimeNativeState` pool (`nativePortFor`), tracking the vendor
  `dynamic[0]` previous id; state persisted via
  `AnytimeRegistry.saveCt3NativeState` / `loadCt3NativeState`
  (`AnytimeNativeState.encode/decode`, hex of state+globals+smooth), restored in
  `restoreFromPersistence`, saved in `persistAlgorithmState`, cleared by
  `removeSensor`.
- `getAlgorithmDiagnostics()` renders `"CT3 native port (no vendor .so)"`;
  `sourcePriority` maps NATIVE_PORT to 3 (same as NATIVE).

### CT3-P5 — validation
- Oracle: reuse the Unicorn + `emu_pred.py` approach, pointed at the **shipped**
  `yqidui_PX3` (0x171f0) with an import shim for `expf/sinf/sqrt/rand/srand/time`
  and the `0x28d4xx` globals; the CT4-family `emu_pred.py` address (0x1b39c) is
  the baseline blob and does not apply to the shipped one.
- Unit: fixed CT3 capture replayed through the Kotlin chain vs the oracle, warm
  and cold start, within tolerance.
- Live: `SN16` (CT3 4/4H) vs Reference App; spot-check a CT3_PLUS and an
  ultrasonic id for the dispatch remaps.

### Open questions (CT3)
- Does the statistics stage change the *final* filter? **Yes** — it feeds
  `glucoseCalculate_CT3` (`kmzfpwfPyqfzseyi_MA3`) →
  `QSFLVCL_GZVARXDKKSVY_JD3` (see status above).
- Do the sub-families (3 vs 9 vs 12) differ by constants or by chain? **Partly
  answered**: the shipped `algorithmMain` itself routes 9→`PX6`, 12→`PX6_2`,
  10/11→`PX4`; only the app remap decides which CT3 sub-family reaches
  `yqidui_PX3`. Re-confirm against the app's post-remap id.
- Is `Ib` unused as in CT-14, or does the CT3 statistics stage consume it?
  **Likely unused** — stored but not read by any recovered CT3 stage; to confirm
  in the oracle replay.

## Work log
- 2026-09-14: P1 done — binary extracted, entry points and internal symbols
  enumerated. P2 started — call chain, `input`/`output`/`internalState` layouts
  and the CT2_1 temperature/average stage recovered (see Findings).
- 2026-09-15: P2 — CT2_1 stages fully decompiled (`currentCompensation`,
  `calculationK`, `setTrend`, `checkInputData`, `EMAfilter`, predictor); scalar
  constants and all four coefficient tables dumped. `FUN_0011bab8` signature
  forced to `(float*, int, float*)`, clean C obtained; generator form confirmed
  and the term RHS table extracted (`ct2_1_terms_full.csv`); lag extraction
  resolves 310/399 (remaining are `-term`/passthrough and groups with a
  non-12 denominator).
- 2026-09-15: P3 skeleton drafted **outside the repo**
  (`anytime-native/AnytimeNativeAlgorithm.draft.kt`) — `AnytimeNativeState`
  (offset-mapped fields), `AnytimeEmaBank`, `gainTemperatureFactor`,
  `currentStage`, `compensationAndRaw`, `guardGain`. Pending: `inputTerms`
  generator, predictor (`FUN_0011bab8`), and case replay. Not wired into the app.
- 2026-09-15: **`FUN_0011bab8` ported and validated.** Built a Unicorn emulator of
  the vendor `.so` (oracle) and a C→Kotlin transpiler (`transpile.py`). The
  generated `FeatureTerms` (5 chunks, ByteArray state with `si`/`sf`/`setSf`
  typed accessors) reproduces all 376 input terms with **0 mismatches / max error
  7.6e-5** on two random fixtures. Key insight: `state[0]/[2]/[4]` are integer
  fields (raw bits, `ldr w` + `udiv`), not floats. Remaining: the predictor
  `updateGlucosePredictionAndState` + the 4 coefficient tables, then wire the
  whole CT2_1 chain and replay the Blueberry series. For CT4 the replay target is
  `docs/MK4_FINAL_SUMMARY.md` (log7/log18 windows).
- 2026-09-15: predictor recovered. Clean C (`updateGlucosePredictionAndState_clean.c`,
  291 lines) with forced signature; ABI decoded (s0=iw, s1=ib, s2=rawMgdl,
  s3=extra, w0=flags, w1=newBg, x2=state, x3=out). Logic: error codes 2/3/4/5; a
  coefficient-table FIR (`state[table[i].idx + 0x208] * table[i].coeff`) whose
  table and count are selected by `state[8]` and `state[0x14]` (elapsed minutes:
  3 / 22 / 167); blend `state[0x11]` by weight; ratio clamp `[0.9, 1.15]`; final
  EMA alpha `max(0.3, 0.4 − elapsed/340)`; output clamp `[30, 450]` mg/dL. Oracle
  works: `emu_pred.py` (applies dynamic relocations + TLS, runs the vendor
  function, writes `pred_out.txt` / `pred_state_out.txt`). Next: port the
  predictor + 4 tables to Kotlin and validate against that oracle, then wire the
  whole CT2_1 chain.
- 2026-09-16: **CT3 native track added** (own section above). CT3 is the primary
  product but has no fallback of its own — without the vendor blob it drops to
  `computeLinear`. The track reuses the CT2 struct/EMA work but recovers the CT3
  kernel (`yqidui_PX3`, `algorithmBody_CT3`, `SUTC_SD_GE3_PD5`) separately, then
  ports, wires (`NATIVE-PORT → LINEAR`) and validates against the same Unicorn
  oracle. No code yet.
- 2026-09-16: **CT3-P1 done, CT3-P2 first pass.** Enumerated the CT3 symbols and
  addresses from the existing Ghidra project (no re-import needed); confirmed the
  dispatch `algorithmLatestGlucose → algorithmLatest → checkInputData_CT3 →
  algorithmMain(case 3) → update_CT3`. Decompiled the whole CT3 chain
  (`ct3_chain_clean.c`, `ct3_misc_clean.c`) and dumped its constants
  (`ct3_constants.txt`). Recovered the stage list: IW48/background/`getIw48`/
  sensitivity statistics → warm-up gain ramp → `glucoseCalculate_CT3` (960-ring
  variance, `GLUCOSE_COMPENSATION_CT3`, SD_GLU) → `setTrend_CT3`; new
  `temperatureMain_CT3` two-channel nonlinear law. **Corrected the `input` struct
  head** (`+0x00` is glucoseId, not flags; flags at `+0x10`). Found a
  non-deterministic `PULSE`/`PULSE_CHANGE` (rand seeded by time) that perturbs
  Iw — a P3 decision. Remaining before P3: pin the `temperatureMain_CT3` argument
  mapping and replay a CT3 window through the Unicorn oracle. Scripts:
  `ListCT3.java`, `DecompCT3.java`, `DecompMisc.java`, `DumpCT3Const.java`.
  Ghidra project: `proj/anytime` (program `libalgorithm-jni-arm64.so`), headless
  via `/opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless`.
- 2026-09-16: **Binary divergence found; CT3-P2 re-based on the shipped blob.**
  The RE baseline (`be2409b8…`) is not what ships; the app packages
  `Common/src/main/jniLibs/arm64-v8a/libalgorithm-jni.so` (`1303a448…`), a newer
  obfuscated build (`yqidui_PX3`, `SUTC_SD_GE3_PD5`, …) with `algorithmMain`
  cases 1..12. Imported it into a fresh Ghidra project (`proj_ship/anytime_ship`)
  and decompiled the full CT3 chain; mapped every obfuscated symbol to its role
  and address (`ct3_ship_map.txt`, `ct3_ship_chain_clean.c`). Pinned the
  `temperatureMain_CT3` argument mapping from the call-site disassembly
  (`t_ref`/`t_cur`). Recorded the shipped-vs-baseline constants
  (`K0·1.35`, warm-up 120/320/600, background `0.1·bg`, rewritten `PULSE`,
  `NOISE` + `SD_GLU>5.6`). Next: Unicorn oracle on the shipped `yqidui_PX3`
  (import shim + `0x28d4xx` globals), then the Kotlin port.
- 2026-09-16: **Shipped CT3 oracle runs.** `emu_ct3.py` loads the shipped lib,
  applies dynamic relocs, stubs the libc imports actually used by the chain
  (`memset`, `expf`, `sinf`, `exp`, `time`, `srand`, `rand`, `printf`,
  `__stack_chk_fail`; `time`/`rand` fixed for determinism) and replays 200
  synthetic samples through `yqidui_PX3` (x0=input, x1=dynamic, x2=output,
  x3=date, x4=state). No faults; the kernel executes the whole chain and writes
  its outputs (`ct3_oracle_out.txt`). Caveat: the synthetic fixture has no proper
  calibration (`K0`/gain), so glucose saturates at 27.8 mmol/L and the fingerstick
  is not taken (`state+0x15c == 0` until `trend < 2`); the harness needs a real
  CT3 capture (or a tuned fixture) before it is a validation target. The
  emulation itself is the milestone — the port can now be diffed sample-by-sample
  against the vendor code.
- 2026-09-16: **CT3-P3 first attempt — compiles, does not yet match the oracle.**
  Wrote `AnytimeNativeAlgorithm.kt` (state/globals as offset buffers, the whole
  deterministic CT3 chain) plus a JVM replay test; it compiles clean
  (`:Common:compileMobileDebugKotlin`) and runs, but every sample floors at
  1.7 mmol/L vs the oracle's 24–27.8. First divergence is at k=0:
  the vendor runs `algorithmBody_CT3` while the port skips it because
  `OYCXY_PJ_NC3` (ERROR_IW_CT3) reads `G_IW3` (= vendor `DAT_0028d470`, set by
  `SUTC_SD3_NC3`) as `>1.0`, whereas the port derived it from the pre-body
  `state+0x6c` (0). The disassembly shows the `SUTC_SD3_NC3` argument is not a
  clean reload of `state+0x6c`; its true provenance (register clobber after the
  `smoothMain` call) is unresolved by hand. Port parked **outside the repo**
  (`AnytimeNativeAlgorithm.ct3.draft.kt`, `AnytimeNativeCt3Tests.draft.kt`) — not
  committed, repo clean. Next: differential Unicorn harness (run vendor and port
  side by side per sample, bisect the first divergent stage) instead of more
  manual RE.
- 2026-09-16: **Differential harness built; port now matches k=0/k=1.** Added
  `emu_ct3_trace.py` — the vendor oracle dumps a fixed field set for `state` and
  the `0x28d464` globals (file vaddr `0x18d464`, *not* `0x28d464`: Ghidra prints
  the +0x100000 image address) after each sample; the Kotlin test dumps the same
  and the two are diffed by integer bit pattern. This found and fixed two real
  bugs: (1) the kernel's post-temperature stores — the vendor temperature
  function returns a 4-float pair, and `state+0x28/0x30 = iw·mul`,
  `state+0x2c/0x34 = mul` (with `mul` in `0x34`, not `T`); (2) `INIT_IW3`
  (`SUTC_SD3_NC3`) sets `G_IW3 = state+0x28`, which is what `ERROR_IW_CT3` reads
  (the port had omitted it, so `algorithmBody` was skipped). Also fixed the
  `setTrend_CT3` ramp==1 init (count 0 / trend 10, not count 10). With this the
  first two samples match the oracle (glucose 24.397055, 24.551136) and so does
  almost every field. **Remaining diffs** (found by the harness): (a) from k=2,
  the two temperature pairs diverge (`0x28` vs `0x30` differ in the vendor) — the
  4-output form of `temperatureMain_CT3` is not yet modelled (second pair seems
  to use a different/smoothed temperature); (b) `postStats` globals
  `G[0x3d8]`/`G[0x3e0]` (SD pair / SD10) — the vendor's running-mean bookkeeping
  was only approximated; (c) `S_IW48_BASE` (`0xb0`) is written by the vendor
  (equals `IW48_IIR`) but the port leaves it 0. Harness + drafts parked outside
  the repo (`emu_ct3_trace.py`, `AnytimeNativeAlgorithm.ct3.draft.kt`,
  `AnytimeNativeCt3Tests.draft.kt`).
- 2026-09-16: **Harness iteration — `k=0`/`k=1` now match exactly (0 diffs on
  every dumped field, state and globals).** Applied the literal `SUTC_SD_GE3_PD5`
  port (the `fVar9 = param_1` mean10 seed, the partial-loop thresholds
  `>0xe/>0x27/>0xf/>10`, running-mean-gated variances) — fixes `G[0x3d8]`/`G[0x3e0]`.
  Added the missing second IW48-base EMA (`state+0xb0/+0xb4`, 480, alphas
  `0.9986111/0.0013889`, SD-gated) from `emlrsmuokMrif_ND3` — fixes `S[0xb0]`.
  **`(a)` root cause found by register hook** (`emu_ct3_temphook*.py`): the two
  stores after the temperature call are not both from `temperatureMain_CT3`.
  The kernel does `ldr s2,[x20,#0x6c]`, calls the smoothing function (Ghidra:
  `wntrulThgs_NG3`, call at `0x1175d4`), then `stp s2,s3,[x20,#0x30]`. The
  temperature return is `(s0=iw·mul, s1=mul, s2=mul, s3=0.035)`; the smoothing
  call returns the *second* pair in `s2,s3` → `state+0x30/0x34`. Its globals
  (`0x285000`) hold a 5-sample history of `(iw·mul, mul)` and the call emits a
  range-limited `(value, factor)` pair — hence k=0/1 coincide with the raw
  temperature and k≥2 diverge (`30.1803/0.996886` vs `30.205/0.996876` at k=2).
  Next: port `wntrulThgs_NG3` + `vbsjfVjtgy_NG3` (`rangeLimit_CT3`) +
  `vbsjfCnvmyk_oyqp_MA3` / `..._wufemi_PX3` (hull) over a `0x285000` buffer —
  decompiled C is in `ct3_ship_chain_clean.c:1156-1536`.
- 2026-09-16: **CT3-P3 done — the port reproduces the vendor oracle on the whole
  fixture.** Ported the smoothing group (`wntrulThgs_NG3` + `vbsjfVjtgy_NG3` +
  `vbsjfCnvmyk_oyqp_MA3` + `..._wufemi_PX3`) over a per-sensor `0x285000` buffer;
  the kernel's `state+0x30/+0x34` pair comes from this (`smoothMain` returns it,
  not `temperatureMain_CT3`). Two more harness catches fixed it: the window
  fields `0x14/0x1c/0x24` are stored as **ints**, and `hull1`'s accumulator
  starts at 0, not at its previous output. Result: all 200 fixture samples match
  (`worst error 5.8e-6`, float noise), `k=0..3` match on every dumped field
  (state + both globals buffers). Landed in the repo:
  `Common/src/main/java/tk/glucodata/drivers/anytime/AnytimeNativeAlgorithm.kt`
  + `Common/src/test/java/.../AnytimeNativeCt3Tests.kt` (12 golden samples from
  the oracle). **Not yet wired** into `AnytimeAlgorithm.compute` (CT3-P4).
  Drafts/harness remain in the work dir (`emu_ct3*.py`).
- 2026-09-16: **CT3-P4 done — wired as `NATIVE-PORT → LINEAR`.** `compute()` now
  routes CT3 families to `computeCt3NativePort()` when the vendor `.so` is absent
  (native still wins when present), before the CT3/CT4 `LINEAR` fallback; CT3 no
  longer touches the CT4 MODEL. State is per-sensor, persisted through
  `AnytimeRegistry` (`encode/decode` of state+globals+smooth), and the source is
  shown in `getAlgorithmDiagnostics()` as "CT3 native port (no vendor .so)".
  All `AnytimeNativeCt3Tests` pass, including the state round-trip; the
  `tk.glucodata.drivers.anytime.*` suite is green. Remaining for CT3: P5 live
  validation against a real SN16 capture (oracle replay is already done).
- 2026-09-18: **CT3/CT4 native stage diff + CT2.5 mapping; CT3 paused pending sensor.**
  Decompiled the full shipped CT4 chain via Ghidra headless (`scripts/DecompPair.java`;
  output `ct3_ct4_decomp_clean.c`, 27 functions, 0 failures). Verdict: one framework,
  shared **by address** — stats `SUTC_SD_GE3_PD5` (CT3 via the veneer `SUTC_SD_GE3` =
  `mov w1,0x14; b …`), PULSE, the whole error chain, resets, and the `27.8/(1.7)/500/31`
  output clamp. CT4-only: `rfbNsvpyGtgj` (4180 B), `itf_eiulayltu_M/L_TD4`,
  `wntrul_ycxhy_ME4`, `pjshs_gprylsn_ND4`, `wfyLbpjipfwnvrDxlxf_LA4`, `gprsfrthrnrs_*`.
  Localised diffs: temperature (CT3 differential two-clamp vs CT4 absolute piecewise),
  limiter (`wntrulThgs_NG3` 3-stage vs `NG4` 1-stage), background (`…_NG3` 476 B vs
  `gbqfvpbacV_NG4` 716 B), trend (CT3 10-pt ±0.11/0.06 vs CT4 6-pt poly/945 ±2.0/1.0),
  gain ramp (CT3 120/320/600, `K0·1.35` vs CT4 480, `K0·1.2`, `0.9+id/4800`).
  `MK4_FINAL_SUMMARY.md` is directionally right but under-describes CT4 (`temp_mul` is
  one branch; `1.35` also used). CT2.5 (Juggluco `Family.CT2_5`, SN30/32/36/38/40,
  `algorithm=7`) = shipped `yqidui_PX2J_44G` (1856 B) → body `emlrsmuokMrif_ND2K_44C`
  (952 B), the "2A / 4.4V" (`_44G`) generation; `algorithmMain` case 7 confirmed, not a
  distinct kernel (id 7 is also used by some CT2 prefixes). Artifacts:
  `anytime-native/ct3_ct4_stage_diff.md`, `ct3_ct4_decomp_clean.c`, `ct4_decomp.log`,
  `scripts/DecompPair.java`, `scripts/DecompDispatch.java`. **STOPPED: CT3-P5 (live
  validation) blocked until a real SN16 capture; no other CT3 code changes pending.**
- 2026-10-01: **Family-catalog gap found from a live trace — SN91 hid behind the
  CT5 handshake.** The sensor advertises as `SN9150002398` (trace
  `juggluco-trace-20261001-114702.log`, `D4:FE:28:EB:50:6B`, advertised name
  present, `services=null`). `EDevice` carries `DEVICE_SN91` (CT3_ULTRASONIC,
  `algorithm 10`), but `AnytimeConstants.FAMILY_TABLE` never listed `SN91` — the
  two hand-maintained copies of the vendor catalog had drifted. Consequences:
  `resolveFamily` returned `FAMILY_UNKNOWN`, so `isAnytimeDevice` was false and the
  setup wizard dropped the device unless *see all devices* was toggled; after a
  manual pick, `beginHandshake` fell through to the generic `else -> checkFrame()`
  arm. `EDevice.getEnumDevice` disagreed with `resolveFamily` on the same string.
  Fixed by adding `FamilyEntry("SN91", Family.CT3_ULTRASONIC, 10, 6740)`, and
  `AnytimeProfileTests` now pins `EDevice` ↔ `FAMILY_TABLE` prefix/algorithm/
  endNumber equality so the copies cannot drift again. Note `DEVICE_UNKNOWN`
  reuses `"SN06"` as its nameStart and must be excluded from that comparison.
  This is a **classification** fix only — no CT3/CT2.5/CT4/CT5 kernel behaviour
  changed, so the P5 hold still stands.
