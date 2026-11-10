# Ottai OTA: static RE (Blutter, com.ottai.tag 1.44 arm64) — results

Status: P1 (static RE) — the main part is closed, 2026-10-26.
Plan: `docs/ottai-firmware-ota-re-plan.md`. Artifacts (outside the repo):
`~/Projects/Dia/ottai-ota/`.

## 0. How the result was obtained

- `Ottai_1.50.0_APKPure.xapk` contains only `armeabi-v7a` (same as 1.38): Blutter
  does not handle ARM32. On top of that **1.50 is obfuscated at the Dart level**:
  names like `init:_TJe@601043202`, `_fph`, `_qna`; the symbols `CgmOTA`, `startCgmOTA`,
  `OTAUpgradeModel`, `_startDeviceOtaUpgrade` are absent from `libapp.so` (they were in 1.38).
  Meanwhile the constants `/ota/upgradeVersion/judge`, `03A617EB`, `V1.8.`, `.GBL`,
  `writeOTAControl:`, `download_ota_package_*`, `update_ota_status_*` are all still in 1.50.
  Conclusion: same code, renamed symbols → 1.50 is unusable for code reading.
- The working static target: **`~/Downloads/Ottai_mod_1_44.apk`** = `com.ottai.tag`
  (CN build) 1.44, `lib/arm64-v8a/libapp.so` 25 MB, Dart 3.10.9, **unobfuscated**,
  snapshot flags product, compressed-pointers, no-shared_data, arm64.
  Same `sy_embeded` module, same constants as in 1.44/1.50 `com.ottai.seas`.
- Blutter (worawit/blutter, commit 4a60ac6) + Dart SDK 3.10.9 built locally.
  An ICU fix was needed (Homebrew `brew --prefix icu4c` → `icu4c@79` is missing libicuuc;
  a fallback to `icu4c@78` was added to `scripts/CMakeLists.txt`) and an
  `ICU_INCLUDE_DIR` fix in the CMakeCache.
- Output: `~/Projects/Dia/ottai-ota/work/blutter-1.44/asm/` — listings per source file
  (`package:sy_embeded/...`), `pp.txt` — the object pool dump. The addresses below
  (`0x…`) are addresses in `libapp.so` 1.44 arm64, for tracing.

Limitation: the APK is modified, not from a store. Protocol identity with
`com.ottai.seas` is confirmed by constants and state names, but the final cross-check
comes only from P2 (an HCI log).

## 1. The Ottai GATT map (recovered in full)

`CgmCharacteristicType` (`pp.txt`, objects `Obj!CgmCharacteristicType`) — 21 values;
the index order matches the UUID order in the pool (`pp+0x278b0…0x27950`).
Verified against anchors: `softVersion` = `2a28`, `currentTime` = `2a2b`,
`ctlDevice` = `6aa799b6`, `activeDuartion` = `b8fd9848`. (Corrected: `ctlDevice` is not
the ACTIVATE_CMD channel. The activation `[0x03]` goes via `writeCmd` into `convertCmd`
= `d78d0706`, same as the JugglucoNG driver; `ctlDevice` is a multiplexed control
channel, see §10.)

| # | enum | UUID | Note |
|---|---|---|---|
| 0 | deviceInfo | `0000180a-0000-1000-8000-00805f9b34fb` | Device Information |
| 1 | softVersion | `00002a28-0000-1000-8000-00805f9b34fb` | Software Revision (ASCII) |
| 2 | errorInfo | `cb627922-4e79-42e3-b107-a10e816f6caa` | |
| 3 | currentTime | `00002a2b-0000-1000-8000-00805f9b34fb` | Current Time |
| 4 | serviceAuth | `e06e1d43-1319-4ebf-94b0-5b0e5313b1f4` | |
| 5 | authHost | `1756ef6e-884b-4eb0-b646-f04ab18408f9` | |
| 6 | authDev | `86805092-92b5-4d8c-9d73-0785ff6f9147` | |
| 7 | authFlag | `785022c6-08c0-48af-ad17-684bb889aa83` | |
| 8 | serviceCGM | `0000181f-0000-1000-8000-00805f9b34fb` | |
| 9 | monitorGlucose | `00002aa7-0000-1000-8000-00805f9b34fb` | |
| 10 | glucoseRecord | `69e4f45f-a180-422c-83c0-324146402112` | |
| 11 | requestByCount | `ccecb015-6750-41fd-ba78-3fb77d350574` | |
| 12 | convertCmd | `d78d0706-c775-448d-8a78-01215e7c2e11` | the "vendor command" from the plan |
| 13 | selfTest | `cb627922-4e79-42e3-b107-a10e816f5b99` | |
| 14 | mackey | `12347922-4e79-42e3-b107-a10e816f5b99` | |
| 15 | serviceOTA | `1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0` | SiLabs OTA |
| 16 | otaControl | `f7bf3564-fb6d-4e53-88a4-5e37e0326063` | |
| 17 | otaData | `984227f3-34fc-4045-a5d0-2c581f81a153` | |
| 18 | ctlDevice | `6aa799b6-b374-4148-8f36-6d440c0ec203` | control channel: retain (`…‖0x04`), coefficients (`0x09…`), calibration (`0x0B…`), §10 |
| 19 | ctlGluDataIdx | `c8693b6f-f850-44cb-b9b4-ac87ed962bac` | do not write (destructive) |
| 20 | activeDuartion | `b8fd9848-0ccd-423f-bd34-2419aa7ea004` | maxActive, 4 bytes |

Service names for logs: `device_info`, `service_auth`, `service_CGM`, `service_OTA`
(`pp+0x27860`); characteristics: `soft_version`, `error_info`, `current_time`, `auth_host`,
`auth_dev`, `auth_flag`, `new_glucose`, `glucose_record`, `request_by_count`, `cmd`,
`self_test`, `mac_key`, `ota_control`, `ota_package`, `ctl_device`, `ctl_glu_data_idx`,
`active_duartion` (typo in the code).

## 2. The activation flow and the place of OTA (answer: variant A)

`CgmBindFlowManger` (`asm/sy_embeded/src/flow/cgm_bind_manger.dart`) — states
(the full enum `CgmBindFlowState`, 89 values, `_pushState @0xc0b6cc`) come in this order:

```
nfc_* → ble_permit → ble_scan/connect/discover_service
  → read_device_version_profile → auth
  → check_active
      ├─ already activated → check expired → continue
      └─ not activated:
           upgrade_check (GET /ota/upgradeVersion/judge)
             ├─ requiredUpgrade=true → download_ota_package → ota_start/success/error
             │                        → update_ota_status → **rescan 60 s** → flow restarts
             └─ otherwise → upgrade_check_success / upgrade_count_limt → continue
  → set_device_coefficient → write_calibration_parameter
  → set_active_duration (b8fd9848) → set_retain_duration
  → active_start (writeRTC → writeCmd [0x03] into convertCmd d78d0706)
  → bind_device (POST /deviceBind/composite/bind)
```

Facts:

- OTA happens **before** writing `maxActive` and **before** `ACTIVATE_CMD` → this is
  variant **A** in the plan; a window between OTA and activation exists.
- OTA is not considered for an already-activated sensor at all: the
  `checkNeedDeviceUpgrade` branch is entered only when `check_active != true`
  (`cgm_bind_manger.dart` 0xc042a4 → 0xc042c8).
- After a successful OTA the app calls `startScan(mac, 60 s, isFromBind: true)`
  (`_startOtaMiniFlow @0xc07638`, Duration = `0x3938700` µs) and **restarts the whole
  flow from scratch** (scan → connect → discover → read version → auth → check_active).
  There is no separate "post-OTA" logic.
- If OTA fails — `_startBleScan` is not called, the flow simply ends (the sensor remains
  unactivated; the app shows the bind step error).
- The decision to OTA is made by the **cloud**; `requiredUpgrade=true` → OTA is mandatory
  (what the user sees in the official app). `allowUpgrade=true && requiredUpgrade=false` →
  OTA is **not** performed, the state `upgradeCountLimt` is set, and the current pass of
  the flow **ends** here (the UI shows an "update available/limit" screen); continuing is
  only via a fresh pass. `downloadUrl == null` → `upgradeCheckError`, also the end of the pass.

## 3. The OTA transport (SiLabs AppLoader)

`CgmOTA.startCgmOTA(path)` — `asm/sy_embeded/src/ota/cgm_ota.dart` @0xc07bec:

1. `CgmOTATool.readOTAFile(path)` — the file from `getApplicationDocumentsDirectory()`,
   existence check + `path.toUpperCase().endsWith(".GBL")` + `verifyFile(bytes)`
   (see §4). On error — `false`.
2. `requestMTU(mac)` → the plugin requests MTU **247** (`0xf7`, `sy_bluetooth_plugin.dart`
   @0xc08448); the actually negotiated value is used.
3. `chunk = (mtu ~/ 4) * 4` → at 247 that is **244** (= MTU-3).
4. `pushOTAProgressState(0.0)`.
5. `writeOtaControl([0x00])` — into `f7bf3564…`, **write-with-response**
   (`SyBluetoothPlugin.writeCharacteristic`). If not `true` — exit with `false`.
6. Loop `i = 0; i < len; i += chunk`: `writeOtaData(file.sublist(i, min(i+chunk, len)))`
   into `984227f3…`, also write-with-response, sequentially (each awaited).
   After each successful chunk `pushOTAProgressState(i / len)` (in the UI — `otaProgress`).
   The first failure → `false`, **no retries**.
7. `pushOTAProgressState(1.0)`.
8. `writeOtaControl([0x06])` → `true`.

What matters:

- There is no vendor command / authorization immediately before `0x00`:
  auth (`authHost/authDev/authFlag`, key write into `mackey`) happens earlier as a regular
  flow step. `d78d0706` (convertCmd) does not participate in the OTA path.
- Between chunks the app keeps a pause from settings (`write()` does a
  `Future.delayed(...)` after every write; the keys `key_ble_*` come from the
  `sdk_init` profile, the default values are not hardcoded) — the exact pacing
  must come from P2.
- The finish is `0x06`, not `0x03` as in the classic SiLabs description
  ("0x03 = upload finished"). Either the Ottai AppLoader uses a different opcode set, or
  `0x06` = "finish + reboot". The distinction is critical and is closed by the HCI log.
- The sensor restart is expected **after** `0x06`: right after success the flow goes to
  a 60-second scan and reconnects to the same MAC (`isFromBind: true`).
  Whether the MAC/name changes across the reboot is not visible statically.

## 4. Verification of the `.gbl` package (in the app)

`CgmOTATool.verifyFile(bytes, startIndex = 0)` @0xc086c0 — a recursive walk over GBL tags:

- `tagId` = 4 bytes from the tag start, read **in reverse order**, into a hex string;
- allowed `tagId` values (hex, as in the code): `03A617EB` (header), `5EA617EB` (bootloader),
  `F40A0AF4` (erase&prog), `F50909F5` (end), `F70A0AF7` (signature), `F60808F6`,
  `FC0404FC`, `FE0101FE`, `FD0303FD`, `FD0505FD`, `FD0707FD`
  (the last group is encryption/metadata/secure-boot; the exact names against UG103.7
  were not cross-checked);
- `tagLength` = 4 bytes little-endian; next tag at `start+4+len`;
  the end must equal the file length exactly;
- if the walk threw but the remaining file is entirely `0xFF` — considered valid
  (an "erased" tail is allowed).

**No hash, CRC or signature check in the app.** Integrity/signature is checked by
the Gecko Bootloader on the sensor via the signature/encryption tags.

## 5. Cloud

| What | Details |
|---|---|
| Version check | `GET /ota/upgradeVersion/judge?mac=<mac>&deviceVersion=<cachedVersion>&deviceType=cgm` (`cgm_api.dart` @0xc094c8) |
| Response | `OTAUpgradeModel.fromJson`: `downloadUrl`, `requiredUpgrade` (bool), `allowUpgrade` (bool), `errorCode` (`ota_upgrade_model.dart`) |
| `deviceVersion` in the request | **from the local cache** `EmbededVersionManger.getSoftwareVersionByMac(mac)`, not read from the sensor |
| Download | `downloadOTAFile(url)`: name = last URL segment, saved into `getApplicationDocumentsDirectory()/<name>`; if the file is absent — `DioNet.downloadFile(url, path)`; no re-download once it exists |
| Report | `POST /device/ota/upgrade` with `{mac, sourceVersion, targetVersion, upgradeStatus, remark}`; `upgradeStatus = 1` on success, `0` on error; `targetVersion` = the filename minus `.gbl` (`_getFileName`), `sourceVersion` = the cached version (i.e. the **old** one) |
| More | `/device/getUranusVersion`, `/device/validateDeviceByMacV2` |

Consequence for P0: the cloud `deviceVersion` updates only when the app reads `2a28`
again (`readSoftVersion`) in the new flow pass and writes it to the cache/server
(`EmbededVersionManger.setTargetDeviceVersion`). The OTA report itself does not refresh it.

## 6. Version profiles (important for H2)

`SyEmbededVersionProfile.getVersionProfileFromSupport(version)` @0xbf4a98:
by the `softVersion` prefix a profile `V1.3. … V1.8.` is selected
(factories `SyEmbededVersionProfile.v_1_3 … v_1_8`). A profile = the set of supported
characteristics (`isSupportCharacter`), i.e. effectively the firmware capability map.

- `serviceOTA`/`otaControl`/`otaData` are present in **all** profiles V1.3…V1.8.
- `activeDuartion` (`b8fd9848`) appears only from **V1.4** — on an older firmware
  `maxActive` is not written at all.
- `readSoftVersion` reads `2a28` and does `createFromCharCodes` (ASCII), i.e. the version
  is a string like `V1.x.y`.
- `setTargetDeviceVersion` compares the read version against the profile: a mismatch →
  `readDeviceVersionProfileError` and the flow does not continue. So the list of allowed
  versions is hardcoded in the app — the full list of supported firmwares can be extracted
  from 1.44 (next step, cheap).

## 7. Answers to the P1 questions

| Plan question | Answer |
|---|---|
| Where OTA sits relative to `ACTIVATE_CMD` | Before: `check_active → upgrade → coefficient → calibration → active_duration → ACTIVATE_CMD → bind`. Variant A |
| Reactivation needed after the reboot | After OTA the flow restarts in full and reaches `ACTIVATE_CMD`; the sensor is still unactivated at that point |
| What is written before `0x00` | Nothing beyond the regular auth. `0x00` goes straight into `f7bf3564…` |
| How it is found after the reboot | Scan for 60 s, `isFromBind: true`, reconnect to the same MAC; full service re-discovery |
| Chunk / MTU / write type | MTU request 247, chunk `floor(mtu/4)*4` (244), write-with-response, sequential, pause from settings, no retries |
| Package verification | Only the GBL tag walk + the `.GBL` extension; no hash/signature in the app |
| `forceUpgrade` / limits | `requiredUpgrade` (mandatory), `allowUpgrade` (allowed but not required → skip), `errorCode` |
| What follows OTA | Rescan 60 s → read `2a28` → auth → check_active → coefficient/calibration → maxActive → ACTIVATE_CMD → bind; then `POST /device/ota/upgrade` (the report was already before the rescan) |
| `maxActive` cap on the new firmware | No cap in the app: a 4-byte value is written, **encrypted** via `CgmAuth.encrypt` when needed and sent into `b8fd9848`. Any limit lives on the cloud/firmware side; not closable statically, needs P2/P4 |

## 8. What remains (dynamics only, P2)

1. Semantics of `0x06` vs `0x03` at the end, and the actual moment of disconnect/reboot.
2. The real write pacing (the `key_ble_*` pause), behavior on a mid-transfer disconnect.
3. Whether MAC/name/adv data change after the reboot into AppLoader.
4. The lifetime cap on the new firmware (stop criterion) — experiment only.
5. Binding of a `.gbl` to a batch/MAC, and whether it is signed (from the file itself, P3).
6. Cross-check of the 1.44 (CN, mod) protocol against 1.50 (`com.ottai.seas`) via an HCI log.

## 9. What it means for P5

- Implementable by an exact replay of the §3 sequence (control `0x00` → 244-byte chunks
  write-with-response → control `0x06` → wait for the reboot → rescan/reconnect by MAC).
- Positioning in JugglucoNG: OTA goes exactly where it does in the official app — **before**
  writing `maxActive` and `ACTIVATE_CMD`; after OTA — reconnect and a regular activation
  with the long `maxActive`.
- Checking the version after OTA logically goes through `2a28` (read-only), as already
  planned in P0.
- Risk: confirmation of `0x00`/`0x06` and of the reboot behavior must be obtained before
  the first write to a sensor; otherwise the chance of leaving the device in AppLoader is high.

## 10. Coefficients and calibration written into the sensor (2026-10-26)

Source: `asm/sy_embeded/src/active/cgm_active*.dart`,
`src/cgm/cgm_transmit_manger.dart`, `src/flow/cgm_bind_manger.dart`,
`sy_embeded.dart` (`SyEmbededVersionProfile`).

### 10.1 Activation (refinement of §1/§2)

`CgmActive.startCgmActive @0xc054b4`: `readCmd` (`d78d0706`); `cmd > 3` → `false`,
`cmd == 3` → `true` (already active), otherwise `_active @0xc055d4`:
`writeRTC` → `writeCmd([0x03])`. `writeCmd @0xc0571c` encrypts via `CgmAuth.encrypt`
when `isNeedEncrypt`, and writes into **`convertCmd`** (`Obj!CgmCharacteristicType@156f991`,
index `0xc`) = `d78d0706`. Matches the JugglucoNG driver.

### 10.2 Flow steps and firmware version gates

`CgmBindFlowManger._setDeviceCoefficientIfNeed` and `_setCalibrationParameterIfNeed`:
a step runs only if the version profile supports it
(`EmbededVersionManger.getIsSupportSetCoefficientByMac` → `profile.field_27`,
`getIsSupportCalibrationParameterByMac` → `profile.field_3b`). States:
`setDeviceCoefficientStart/Success/Error`, `writeCalibrationParameterStart/Success/Error`.
(`HardwareKeyboard::lookUpLayout` in the listing is an artifact of identical code being
folded together; it is in fact the profile lookup by MAC.)

Flags of `SyEmbededVersionProfile.v_1_0 … v_1_8` (with register tracking):

| flag | V1.0–1.3 | V1.4 | V1.5 | V1.6 | V1.7 | V1.8 | meaning |
|---|---|---|---|---|---|---|---|
| `field_1b` | – | ✓ | ✓ | ✓ | ✓ | ✓ | `maxActive` (matches "fw ≥ 1.4") |
| `field_1f`, `field_23` | – | – | ✓ | ✓ | ✓ | ✓ | retain/destruction (matches "fw ≥ 1.5") |
| `field_27` | – | – | – | ✓ | ✓ | ✓ | **writing coefficients into the sensor** |
| `field_37` | – | – | – | – | – | ✓ | not resolved |
| `field_3b` | – | – | – | – | – | ✓ | **writing calibration k/b into the sensor** |

### 10.3 Coefficients: `writeCoefficientList @0xbcba18` → `ctlDevice`

```
payload = 0x09 ‖ N (1 byte) ‖ N × float32 LE ‖ CRC16/MODBUS(everything before it), LE (lo, hi)
```

- The list = the decrypted CSV `coefficient` (`CgmActiveCoefficientModel.
  decipherCoefficientContent`: `decryptAes`, then `split(",")` → double → float32).
  This is the same string JugglucoNG already decrypts into `materials.coefficients`.
- **No encryption** (`CgmAuth.encrypt` is not called).
- CRC16/MODBUS: init `0xFFFF`, poly `0xA001` (reflected), result `[crc & 0xFF, crc >> 8]`.
- Written via `write()` with `handleError`; on a profile < V1.6 the function returns
  `false` immediately, with the log `cgm is not support set coefficient`.

### 10.4 Calibration: `writeCalibrationParameter @0xc069e8` → `ctlDevice`

```
payload = 0x0B ‖ E(k as float64 LE ‖ b as float64 LE)    // 1 + 16 bytes
E = CgmAuth.encrypt (session AES, same as for maxActive) when isNeedEncrypt; otherwise plaintext
```

- `k`, `b` = `calibrationValueK`, `calibrationValueB` from `CgmBindDeviceModel.fromJson`
  (`sy_embeded.dart` @0xbb8d88 / @0xbb8e08) — the cloud's device answer, type `double?`,
  **not encrypted**. If either is `null` — error
  `set_calibration_parameter_error: k is null` / `b is null`, no write happens.
- They are placed into the manager via `CgmActiveCalibrationParameter.addCalibrationParameterToManger`
  → `CgmActive.setCalibrationParameterByMac`.
- Log: `write_calibration_parameter:kValue-… bValue-… paramsBytes:… writeBytes:…`.

### 10.5 What it means

- The JugglucoNG driver does none of these steps: it writes only RTC, `maxActive`,
  retain and `ACTIVATE_CMD`. `calibrationValueK/B` is not parsed in `OttaiCloudClient`.
- Our sensors, judging by the versions in the driver code, are `V1.7`. On V1.7 the official
  app writes the coefficients (`0x09`) into the sensor, but not the calibration. The `k/b`
  calibration appears only from **V1.8**, and it is probably to V1.8 that the mandatory
  OTA moves sensors.
- Hypothesis for the plan: "adequate right after OTA" = firmware V1.8 + the factory
  calibration `k/b` from the cloud written before activation. "Back to normal on day
  2–6" = a sensor without coefficients and a calibration converging on its own.
- Not resolved: whether what the sensor serves over BLE (raw current or an already
  corrected value) depends on these writes, what `field_37` means, what `isNeedEncrypt`
  depends on. P2 (the HCI log) and a before/after stream comparison close this.
