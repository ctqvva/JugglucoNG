# Ottai: RE of sensor firmware updates (OTA) — plan

Status: plan, 2026-10-26. P1 (static RE) is mostly closed the same day —
results in `docs/ottai-ota-static-re-1.44.md`. The sensor's own firmware was not studied
before that; the driver (`Common/src/main/java/tk/glucodata/drivers/ottai/`) does not do
OTA and does not touch the OTA service.

P1 in short: OTA happens **before** `ACTIVATE_CMD` (variant A), the GATT map and the whole
SiLabs transport are recovered, the cloud endpoints and the response model are known, and
there is no signature check in the app. Only dynamic confirmation remains (§P2):
`0x06` vs `0x03` at the end, write pacing, behavior across the reboot.

## Motivation (field reports)

- Two Ottai sensors activated in JugglucoNG without OTA: one came back to normal around
  day 6, the other around day 2.
- In the official app, activation with the firmware update gives adequate values almost
  immediately (all else being equal).
- User's working hypothesis: on the old firmware the autocalibration inside the sensor
  takes a long time, and the newer firmware fixes it.

## What is already known (triage of APK `com.ottai.seas` 1.38.0, 2026-10-26)

Source: `~/Projects/Dia/Ottai_1.38.0_APKPure.xapk`, `config.armeabi_v7a.apk` →
`lib/armeabi-v7a/libapp.so` (Flutter AOT, ARM32), sampling via `strings`.

**OTA is present and built into the activation/connection flow.**
- Assets: `monitor_connect_ota_update_{tips,warning,limit,error,done}.webp`.
- UI/strings: "For a safer and better experience, we need you to perform a firmware upgrade,
  which takes about 1 minute", `UpgradeIgnore`, `UpgradeNextTime`, `UpgradeUpdate`.
- Bind-step errors: `BindStatusOTACheckInfoError`, `BindStatusOTADownloadError`,
  `BindStatusOTAInstallPackageError`, `BindStatusOTAUpdateLimitError`. So OTA is part of
  the bind/activate flow, not a separate settings screen.
- Dart symbols: `CgmOTA`, `CgmOTATool`, `CgmOTAFlowState`, `_startDeviceOtaUpgrade`,
  `_startOtaMiniFlow`, `downloadOTAFile`, `readOTAFile`, `writeOTAControl`, `writeOTAData`,
  `clearOtaSection`, `upgradeOTAVersion`, `upgradeOTAStatus`, `_uploadOtaUpgradeStatue`,
  `pushOTAProgressState`, `checkOtaError`.
- Model: `OTAUpgradeModel.fromJson` with fields `forceUpgrade`, `upgradeFlag`, `isOtaing`,
  `otaProgress`.

**Cloud.**
- `/ota/upgradeVersion/judge` — checks by name whether an update is needed (likely
  read-only). Input/output unknown at that point.
- `/device/ota/upgrade` — probably a status report or package delivery.
- `CgmApi|getUpgradeOTAVersion`, `CgmApi|postUpgradeOTAVersion`.
- No firmware image inside the APK: it is downloaded (`downloadOTAFile`).
- The business code `AppDevice_Upgrade_Version` (ours: `OttaiCloudClient.BIZ_UPGRADE_VERSION`,
  currently treated as "switch to V3") needs re-checking: it may mean "the device or the
  app needs an upgrade".

**BLE transport: Silicon Labs AppLoader OTA** (standard SiLabs UUIDs, present in `libapp.so`):

| Role | UUID |
|---|---|
| OTA service | `1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0` |
| OTA control | `f7bf3564-fb6d-4e53-88a4-5e37e0326063` |
| OTA data | `984227f3-34fc-4045-a5d0-2c581f81a153` |

Conclusion: the sensor SoC is likely SiLabs EFR32/BG2x, the image is `.gbl` (Gecko Bootloader).
Classic SiLabs (AN1086): `0x00` in control → stream the `.gbl` into data → `0x03` → `0x04`.
Ottai differs per the 1.44 statics: after a normal auth `0x00` → 244-byte chunks,
write-with-response, no retries → `0x06`; then a 60 s rescan on the same MAC. Details in
`docs/ottai-ota-static-re-1.44.md` §3.

**UUIDs in 1.38 that are absent from the driver** (to classify; some may belong to the
watch or other products): `ceb33896-4ac1-4fb8-a426-c6e3ff6f4a97`,
`12347922-4e79-42e3-b107-a10e816f5b99`, `cb627922-4e79-42e3-b107-a10e816f5b99`,
`e06e1d43-1319-4ebf-94b0-5b0e5318a2f1`, `e06e1d43-1339-*` (3 of them),
`c8693b6f-f850-44cb-b9b4-ac87ed962bac` (a known no-data/destructive one — do not touch),
`00002ef7-0000-1000-8000-00888f9b34fb`.

## Competing hypotheses (check before any OTA experiments)

The app computes glucose, not the sensor: `OttaiFormula` evaluates the RPN expression
`method` with `coefficient` from the cloud. Variables: `V0` current, `V1` temperature,
`V2` runtime, `V4` runtime/3600, `V5` voltage. The `method`+`coefficient` package arrives
in bind/validate and depends on the device and `deviceVersion`. Therefore:

- **H1 — firmware.** The new firmware changes the raw signal (current, filtering, internal
  stabilization algorithm), and the data is good right away.
- **H2 — formula.** After OTA the `deviceVersion` changes, and the cloud returns different
  `method`/`coefficient` (a different sensitivity curve over `V4`). Same sensor, new formula.
- **H3 — both factors** (the new firmware needs a new formula).
- **H4 — not the version.** The difference is something else: activation parameters
  (`maxActive`, `destructionTime`), warmup, our filter/suppression (`OttaiOutputFilter`,
  warmup gate), spread between sensors.
- **H5 — a skipped activation step (new, from the 1.44 statics).** Before `maxActive` the
  official flow runs `set_device_coefficient → write_calibration_parameter`, i.e. it writes
  coefficients and calibration parameters **into the sensor**. The JugglucoNG driver does
  none of that: it writes only RTC, `maxActive`, retain and `ACTIVATE_CMD`, and uses
  `coefficient` only in the app (`OttaiParser.toReading`). If the sensor's internal
  algorithm starts without these parameters and converges on its own, this explains
  "back to normal on day 2–6" **without any OTA**. Checkable statically; higher priority
  than OTA. **Static analysis closed (report §10):** both steps write into `ctlDevice`
  (`6aa799b6`) and are gated by firmware version. Coefficients
  (`0x09 ‖ N ‖ float32 LE… ‖ CRC16-Modbus`, plaintext) — from **V1.6**, payload = our own
  decrypted `coefficient`. Calibration (`0x0B ‖ AES(k f64 LE ‖ b f64 LE)`) — from **V1.8**
  only, `k`/`b` = `calibrationValueK/B` from the cloud's device answer (our client does not
  parse them). Refined hypothesis: "good values right after the mandatory OTA" = V1.8 +
  writing the factory calibration `k/b`; on our V1.7 JugglucoNG additionally skips the
  coefficients the official app does write.

H2 can be checked with zero risk to the sensor. If it holds, OTA for improving the first
days may not be needed at all: the right formula is enough.

## Phases

User-reported fact (2026-10-26): **in the official app OTA at activation is mandatory.**
So every sensor enrolled in the official app is already on the new firmware, and every
sensor activated in JugglucoNG stays on the factory one. Each activation in the official
app is a ready-made opportunity for a P2 capture.

### P0. Facts without RE (risk 0)

Where the version comes from:
- In JugglucoNG `deviceVersion` arrives **from the cloud** (`validateDeviceByMacV2/V3`), not
  from the sensor. It is stored in materials (`ottai_device_version_*`, JSON export), shown
  in the sensor card as "Firmware" (`SensorCard.kt`, `vendorFirmwareVersion`) and written to
  logcat: `Ottai: V3 validation ok mac=… version=…`. For sensors activated in JugglucoNG,
  this is the version **before** OTA.
- The sensor itself serves the version in the standard Device Information characteristic
  `0000180a/00002a28` (Software Revision). The driver currently does **not** read it.
  The source of truth after OTA is this characteristic: the cloud record may lag.

Steps:
1. Problem sensors: take the "Firmware" field from the card or `deviceVersion` from the JSON export.
2. A sensor updated via OTA in the official app: close the official app (one BLE connection),
   connect nRF Connect → Device Information → Software Revision String (`2a28`).
   Usually readable without authorization. If not — add read-only `2a28` reading to the
   driver at connection with a log and a comparison against the cloud `deviceVersion`
   (safe, writes nothing).
3. Separately check the cloud `deviceVersion` of the same sensor (`deviceBind/list`):
   does `_uploadOtaUpgradeStatue` refresh it after OTA.
4. From `deviceBind/list` and the account's bind answers, extract `method`+`coefficient`
   for sensors on the old and the new firmware. Decrypt (`OttaiCrypto`) and compare the
   expressions: do the terms with `V2`/`V4` (runtime) differ?
5. If raw records of the problem sensors exist: recompute the first 6 days with the
   "new" firmware formula (offline, a unit harness like the side-smoothing study) and
   compare with finger-prick measurements. A match → H2.

6. The workaround "activate in the official app → move to JugglucoNG" is **not acceptable
   as a solution**: an official activation pins `maxActive` = 15 days (`b8fd9848`), while
   JugglucoNG can activate for a longer period. It is only usable as a one-off measuring
   sensor for P4 (new firmware, 15 days).

**Main requirement for the outcome:** OTA must happen **before** activation and must not
interfere with writing the long `maxActive` in JugglucoNG.

Fact from the user: OTA happens **at the moment of activation** (inside one flow).
**The order is closed by statics: variant A** (OTA before `maxActive` and `ACTIVATE_CMD`;
after OTA the flow restarts in full). The table below is kept as the history of reasoning.

| Option | Order | Meaning for us |
|---|---|---|
| A | bind → **OTA** → reboot → RTC → `maxActive` → `ACTIVATE_CMD` | There is a window between OTA and writing the lifetime. OTA in JugglucoNG goes before the existing activation. |
| B | bind → RTC → `maxActive` (15 d) → `ACTIVATE_CMD` → **OTA** | No window. If activation survives the reboot, the official app always gives the sensor 15 days; in JugglucoNG we must update before activation, and it is unknown whether the old firmware accepts OTA in an inactive state. |
| C | bind → RTC → `maxActive` → **OTA** → re-activation | Probably `maxActive` is written again after OTA; the window is unclear. |

Confirmed in `cgm_bind_manger.dart` (1.44): the OTA branch is entered only for a
**non-activated** sensor. Consequence: a sensor already activated in JugglucoNG cannot be
updated by the official app — OTA in JugglucoNG must happen before activation.

P0 deliverable: whether OTA is needed at all, or the task reduces to formula choice.

### P1. Static RE of the official app (risk 0) — DONE (see `ottai-ota-static-re-1.44.md`)

0. **Outcome of 2026-10-26.** `Ottai_1.50.0_APKPure.xapk` — ARMv7-only again **and** the
   code is obfuscated at the Dart level (names `init:_TJe@…`, `_fph`; `CgmOTA`, `startCgmOTA`,
   `OTAUpgradeModel` absent). So we worked with **`Ottai_mod_1_44.apk`**
   (`com.ottai.tag`, 1.44, `lib/arm64-v8a/libapp.so`, unobfuscated, Dart 3.10.9) —
   Blutter ran through, with all of `sy_embeded/src/ota/*`, `flow/cgm_bind_manger.dart`,
   `cgm/cgm_transmit_manger.dart`. Identity with `com.ottai.seas` was confirmed by
   constants (`/ota/upgradeVersion/judge`, `03A617EB`, `V1.8.`, `.GBL`).
   Questions 1–6 below are closed statically; 7 (lifetime) — dynamics only.

   **Discrepancies against the static report:**
   - ~~`ctlDevice` = ACTIVATE_CMD~~ — **closed**: the label was wrong. `_active` =
     `writeRTC` → `writeCmd([0x03])` into `convertCmd` = `d78d0706`, same as the driver.
     `ctlDevice` is a control channel (retain, coefficients, calibration). The report is fixed.
   - The branch `allowUpgrade=true && requiredUpgrade=false → upgradeCountLimt → end of
     pass` looks odd (an optional update blocks activation). Re-check the branching
     condition; the fields may be swapped.

   **Next static steps (no sensor needed):**
   - ~~H5: format of coefficients and calibration~~ — **done**, report §10.
   - ~~Profiles V1.0…V1.8~~ — **done**, report §10.2. `field_37` (V1.8 only) not understood.
   - Check the saved cloud answers (`validateDeviceByMacV3`, `bindV3`, `deviceBind/list`)
     for whether `calibrationValueK/B` arrive for our sensors and with which values.
     No sensor needed.
   - Get the `.gbl` without a sensor: `GET /ota/upgradeVersion/judge?mac=…&deviceVersion=…
     &deviceType=cgm` with the MAC and version of an already-used sensor from the account —
     a read-only request. If it returns `downloadUrl`, download the file and do P3 right
     away. The answer for an old or expired sensor may differ; do **not** call
     `POST /device/ota/upgrade`.

1. **Cheap sources first.** Check whether OTA code exists in readable Java builds:
   `Ottai_mod_1_44.apk`, watch-APK 1.1.0 (`com.ottai.tag.watch`), the older CN `com.ottai.tag`.
   If OTA is Java/Kotlin there, the whole protocol reads out in jadx.
   → No OTA in Java/Kotlin (dex is clean), everything is in Dart. But `Ottai_mod_1_44.apk` gave arm64.
2. **Dart AOT.** → Done on 1.44 arm64 (blutter); 1.38/1.50 are ARMv7-only, 1.50 obfuscated.
3. Targets: `CgmOTA`, `CgmOTATool`, `CgmOTAFlowState`, `_startDeviceOtaUpgrade`,
   `_startOtaMiniFlow`, `downloadOTAFile`, `readOTAFile`, `writeOTAControl`/`writeOTAData`,
   `upgradeOTAVersion`, `_uploadOtaUpgradeStatue`, `OTAUpgradeModel.fromJson`,
   `CgmApi.getUpgradeOTAVersion`/`postUpgradeOTAVersion`.
4. Questions to answer:
   - where in the bind/activate flow OTA sits: before `ACTIVATE_CMD` (0x03) or after?
     Is reactivation needed after the reboot?;
   - what the app writes to the sensor before `0x00` in OTA control: a vendor command into
     `d78d0706`, authorization, nothing?;
   - how the device is found after the reboot into AppLoader: same MAC or MAC+1, name,
     service in the advertisement;
   - chunk size, MTU, write-with/without-response, pauses, retries, behavior on disconnect;
   - package verification: md5/crc/signature in the cloud response, version check before/after;
   - `forceUpgrade` / `upgradeFlag` / `UpdateLimit`: can it be declined, is there a limit of attempts;
   - what follows OTA: a repeat Auth V2, a repeat `validateDeviceByMac*`, a new bind,
     new `method`/`coefficient`;
   - **lifetime on the new firmware:** whether the new firmware caps `maxActive` or has a check that
     rejects anything above 15 days. Look in 1.38: what `activeExpireTime` comes
     from (cloud or a constant), whether the app handles a NACK to `b8fd9848`. If the cap
     is in the firmware, OTA and a long lifetime are incompatible. This is the
     **stop criterion** of the whole direction, settle it before P5;
   - is there a "window" between a successful OTA and `ACTIVATE_CMD`: can the official flow
     be stopped after OTA (before writing `maxActive`) and activated in JugglucoNG. Do this
     only when the step order is exactly known from P1/P2 — otherwise it risks producing a
     sensor activated for 15 days, or a half-activated one.

### P2. Dynamic capture (risk low: the official app does the work)

On a **new** sensor that the user is going to activate with OTA in the official app anyway:

1. Android HCI snoop log (Developer options) across the whole path: scan → bind → OTA →
   activation → the first 30 minutes of data.
2. HTTPS: Frida + pinning bypass (`libnative-keys-pinning-container.so`, `toolChecker` —
   anti-tamper present) or reFlutter. Record the request and response of
   `/ota/upgradeVersion/judge`, the download package URL and headers, `/device/ota/upgrade`.
3. Keep the `.gbl` itself (from the response or the app cache via `run-as`/root).
4. Record `2a28` before and after, and `method`/`coefficient` before and after
   (`deviceBind/list`).
5. In the HCI log, mark the exact order: OTA control/data → reboot → RTC →
   `b8fd9848` (what value) → `6AA799B6` → `ACTIVATE_CMD`. This defines where the window
   for "OTA without the official activation" is.
6. The final opcode: `0x06` (as in 1.44) or `0x03`/`0x04` (classic SiLabs); when the
   connection drops, whether MAC or adv change after the reboot, the real pause between chunks.
7. What `set_device_coefficient` / `write_calibration_parameter` write (H5) and where
   `ACTIVATE_CMD` goes (`d78d0706` or `6aa799b6`).
8. Keep the artifacts outside the repo (like `~/Projects/Dia/anytime-native/`), e.g.
   `~/Projects/Dia/ottai-ota/`. Firmware image and raw captures **are not committed**.

### P3. Analysis of the `.gbl` image (risk 0)

1. Parse GBL tags (header, application info with version, prog, encryption, signature).
2. If the image is encrypted and signed (AES-CCM + ECDSA in Gecko Bootloader): it cannot be
   modified, but delivery does not need that — retransmitting byte-for-byte is enough. If
   plaintext: version and app properties; with two versions available — a binary diff in
   Ghidra (Cortex-M33, base from app properties) over areas handling current or stabilization.
3. Package binding: one image for all or per-MAC/per-batch (affects whether the file can be
   pulled from the cloud by version).

### P4. Experiment "firmware vs formula" (risk: one sensor)

Matrix on sensors of one batch, identical placement, with finger measurements over the
first 72 hours:

| | old formula | new formula |
|---|---|---|
| old firmware | current JugglucoNG | P0 offline recomputation |
| new firmware | offline recomputation with the old formula | official app (reference); JugglucoNG on a sensor after the official activation (the cloud serves the formula — cross-check against official) |

The "old firmware + new formula" cell cannot be produced in the official app (OTA is
mandatory), only by offline recomputation.

The matrix decides the implementation:
- formula is enough → P5 not needed, fix the `method`/`coefficient` selection;
- firmware is needed → P5.

### P5. Implementation in JugglucoNG (only after P2 + P4)

- `OttaiOtaManager`: judge → download → hash check → SiLabs OTA → reconnect → `2a28` check →
  re-auth/validate → new materials.
- Explicit user action only, with a risk warning; never automatic, never in background.
  Preconditions: phone battery, RSSI, screen on, no other BLE sessions.
- Recovery: device "stuck" in AppLoader → find it and re-send the image.
- Order relative to `ACTIVATE_CMD` — exactly as in the official app (from P1/P2):
  OTA → reconnect → JugglucoNG activation with the long `maxActive`. After activation,
  check the `maxActive` ACK and record the actual lifetime (`PREF` of the accepted `maxActive`).
- First live run — on a disposable sensor, verifying that the new firmware accepted a
  lifetime above 15 days and keeps it past day 15.
- Tests against a fake GATT: the control opcode sequence, chunking, disconnect/retry,
  address change. No live OTA in CI.
- First a field-test build with an explicit label.

## Stop rules and safety

- The sensor is disposable; a failed OTA means a lost sensor. No homemade OTA writes before
  a complete P2 capture, and only on a spare sensor.
- Do not write to `c8693b6f…` and unknown characteristics of `84c5b711…`
  (the no-data/destructive path).
- Do not change the current warmup/filter/formula "by eye" without P0/P4 data.
- Keep firmware, captures, and keys outside the repository.

## What is needed from the user

1. The "Firmware" line from the cards of the two problem sensors (see P0).
2. `2a28` via nRF Connect from a sensor updated in the official app.
3. HCI snoop + HTTPS on the next activation in the official app.
4. arm64 APK 1.38+ — **not needed for static work**: 1.50 is obfuscated, the analysis was
   done on 1.44 arm64. Only needed if we must compare 1.50 opcode-by-opcode;
   in that case an HCI log (item 3) is better straight away.
5. The `.gbl` itself (from the app cache or from the cloud) — for P3 and as the reference
   for tests.

Closed: OTA at activation in the official app is mandatory.
