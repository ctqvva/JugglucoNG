# AiDEX G9 (CGM3) — Protocol Teardown & Implementation Guide

Status: static analysis complete (3 APKs, native lib, resources, cloud client).
**Not yet done: live-device capture** (Section 9 checklist). No G9 hardware was available;
everything about the live handshake is marked accordingly.

Related Juggluco code lives under `Common/src/main/java/tk/glucodata/drivers/aidex/`
(cited below as `aidex/…`). Reference APK artifacts are listed in Section 10.

---

## 1. Product facts (G9 hardware)

| Item | Fact | Source |
|---|---|---|
| Name | 微泰G9 / MicroTech G9 动态血糖仪 | retail listing; NetEase/Gelonghui 2026-09-28 |
| Launch | Sep 23 2026 (数贸会), NMPA批件 Sep 7 2026 | NetEase article `L7TQ8GAG05198ETO` |
| Architecture | **Split**: reusable transmitter (2-year life) + disposable probe/sensor | same; retail "分体式 可复用" |
| Wear | 15 days per probe, 4-pack = 60 days, ¥298 | retail listing |
| Sampling | 1-minute updates, phone reads live | NetEase (MARD 8.66%, IP68) |
| App | 微泰动态 (Weitai Dynamic), same app as AiDEX X | NetEase; futunn G9 piece |
| Matrix | 一体式 (AiDEX X, convenience) + 分体式 (G9, long-term cost) in one app | NetEase |

The G9 photo (transmitter puck snapping into a round sensor base via applicator)
is mechanically close to Sibionics GS2. That analogy is only mechanical —
the vendor BLE stack is MicroTech's own (`libblecomm-lib.so`), not Sibionics'.

---

## 2. App generations and packages

| App | Package | Generation | BLE identity | Juggluco support |
|---|---|---|---|---|
| 微泰动泰 | `com.microtechmd.cgms` (v1.5.2 on Tencent/Xiaomi) | AiDEX split, 1st gen (transmitter 4 yr + 14-day sensor, 5-min) | transmitter, F005-era stack | **none** |
| AiDEX / AiDEX mg/dL / mmol/L, GlucoRx AiDEX | `com.microtechmd.cgms.mg` / `.mmol` … | same as above, international | same | **none** |
| 微泰动态 | `com.microtech.aidexx` (v1.11.0 → v1.16.1) | AiDEX X all-in-one (15-day, 1-min) **+ G9 split (v1.16.x)** | sensor SN (X); **transmitter SN + probe SN (G9)** | X fully; G9 **not yet** |
| LinX CGM, Smart CGM | `com.microtech.aidexx.mgdl`, `.smart.mmoll` … | AiDEX X rebrands | sensor SN | covered by X driver (name regex) |

Tencent AppBao lists 微泰动泰 but hosts no binary (external distribution).
Google Play carries only the international X-era apps. The CN G9-era app
(`com.microtech.aidexx` ≥ v1.16) is on CN mirrors only (Section 10).

---

## 3. Analyzed APKs

All `package="com.microtech.aidexx"`, vendor cert `META-INF/WEITAI.*`.
Java/Kotlin code is packed (`smali/` = loader shell only) and additionally
defeats BlackDex ("Unpack failed") and Frida (spawn/attach process suicide);
it was recovered via the Section 5.5 memory-carve instead. Resources,
manifest and native libs are in the clear.

| Version (code) | Size | Built | MD5 | Source |
|---|---|---|---|---|
| v1.11.0 (11100) | 51 MB | cert Dec 22 2025 | `b903202f052678e7a6a0b7a187eb1ca8` | 闪电下载网 CDN (needs page as `Referer`) |
| v1.13.0 (11300) | 55 MB | dex Apr 15 2026 | `e27ab51985c3d04204a6a203db1ae11c` | 多多软件站 API (needs page as `Referer`) |
| **v1.16.1 (11601)** | 60 MB | dex Sep 21 2026 (2 days pre-G9-launch) | `e53006518dba4f5244379271b8d90e26` — **matches 87g's published MD5** | 87g + iuuu9 (bit-identical from both) |

Deltas that matter: v1.11.0 → v1.13.0 adds `setCgmxNameList` + `getRebootTimes`
JNI; v1.13.0 → v1.16.1 adds the whole **Cgm3 stack** (Section 5), 3 pairing
activities, `AidexCompanionDeviceService`, `BleScanReceiver`.
minSdk 24 (v111) / compileSdk 33 (v111), 36 (v113/v161).

---

## 4. Vendor BLE platform (`libblecomm-lib.so`)

One unified native library for MicroTech's whole BLE portfolio. JNI surface
in v1.16.1 (arm64, 1 002 872 B, `.text` 0x89618), grouped:

| Family | Controller methods | Parser methods | Product |
|---|---|---|---|
| `AidexXController` | 36 | `AidexXParser` 10 | AiDEX X / LinX (all-in-one) |
| **`Cgm3Controller`** | **45** | **`Cgm3Parser` 8** | **G9 split (new in v1.16.x)** |
| `CgmController` | 16 | `CgmParser` 10 | older CGM |
| `PumpController` | 26 | `PumpParser` 7 | Equil insulin pump |
| `BgmController` | 4 | `BgmParser` 4 | BG meters |
| `BleController` (base) | 29 | — | shared (`setSn` ×2 overloads in v161) |
| `BleAdapter` | ~13 | — | GATT plumbing, `setCgmxNameList`, F001/F002/F003/F005 UUID getters |

Provenance anchors (byte-level, verified in our copies):

- CRC-8/MAXIM table (poly 0x31 reflected) at file offset **0x3a34d** (v111),
  0x3b375 (v113), **0x42828** (v161) — identical bytes; Juggluco's
  `aidex/native/crypto/Crc8Maxim.kt:12-29` is this table.
- `LibChecksum_GetChecksum8Bit` (`Crc8Maxim.kt:34`), `AidexXController::setSn`
  (`SerialCrypto.kt:5`), `ByteUtils::snToBytes` (`SerialCrypto.kt:26-28`).
- Built-in CGM-X BLE-name allowlist: **3950 entries** of `222222P*` / `AO22222*`
  10-char names in all three libs (rodata, unchanged set) — this is what
  `setCgmxNameList` overrides at runtime.
- Opcodes drift slightly per release (capstone immediates): `0x203f`→`0x203e`,
  `0x2072`→`0x2074`, new `0xfece` in v161. Same family, new revision.
- GATT UUIDs are **not** in the native lib (no base-UUID bytes, no 16-bit
  immediates) — they come from the (encrypted) Java layer. Do not assume;
  capture live (Section 9).
- Legacy `F005` getters (`getCharacteristicUUIDF005`, `isF005Paired`,
  `onF005NotExist`) are present but have no UI and no Juggluco counterpart —
  1st-gen transmitter carryover, almost certainly not G9.

### 4.1 GATT UUIDs — recovered from native getters (all product lines)

The `getServiceUUID` / `getCharacteristicUUID*` methods are 8-byte stubs
(`mov w0, #imm; ret`), so every 16-bit ID is readable statically
(`tools/blecomm_dis.py` on the addresses from `.dynsym`):

| Controller | Service | Char | Private char | F005 | F003 |
|---|---|---|---|---|---|
| **Cgm3Controller (G9)** | **0x181F** | **0xF002** | **0xF001** | 0xF005 | 0xF003 |
| AidexXController | 0x181F | 0xF002 | 0xF001 | 0xF005 | 0xF003 |
| CgmController (old) | 0xF000 | 0xF001 | 0xF101 | 0 | 0 |
| PumpController | 0xF000 | 0xF001 | 0 | 0 | 0 |
| BgmController | 0xFFE0 | 0xFFE1 | 0 | 0 | 0 |

**G9 uses the identical GATT profile as X.** Juggluco's scan filter (0x181F),
service discovery, and CCCD chain attach to a G9 transmitter unmodified.
(All IDs are 16-bit UUIDs under the standard Bluetooth base UUID.)

### 4.2 Crypto inventory (from PLT imports + bundled code)

- Bundled textbook AES (`AesEncryptor`: SBox/ShiftRows/MixColumns/
  KeyExpansion, `CFB_encrypt`/`CFB_decrypt`, `setKey`/`setIv`) — this is what
  BLE traffic uses (same as X's `AesCfb128`).
- `MD5::digest`, `LibChecksum_GetChecksum8Bit/16Bit_CCITT`.
- mbedTLS: GCM, CTR-DRBG (random), SHA256, AES-ECB — present in imports, no
  observed BLE use (likely pump/cloud-adjacent features).
- **No EC/ECDH/P-256/X25519 imports anywhere**: the seed handshake is
  symmetric-crypto based.
- `Cgm3Controller::setSn` is instruction-for-instruction the X derivation:
  NEON `movi` 13/17/19 + scalar `madd` (×13) / ×17 / +19 loop, i.e. the same
  `secret = MD5(sn·(b·13+61))`, `iv = MD5(sn·(b·17+19))` as
  `SerialCrypto.kt` (normalized diff vs `AidexXController::setSn`: 17 trivial
  scheduling lines out of ~200). The F001-equivalent challenge crypto is shared.

### 4.3 Built-in CGM-X name allowlist

3950 entries in rodata, all 10 chars: 3573 × `222222Pxxx` + 377 × `AO2222xxxx`
(32-symbol alphabet, Crockford-style without confusables). Shape matches
10-char sensor serials (cf. Juggluco's `2222267V4E` test vector) — most likely
the SN validation/generation corpus behind `checkAndGenSensorSn` /
`checkAndParseSensorSn` and the default behind the runtime `setCgmxNameList`.
Testable offline with Unicorn (feed corpus vs random strings).

---

## 5. CGM3 (G9) specifics — what v1.16.1 reveals

- **Transmitter**: own SN (`发射器SN`, `cgm3_info_trans_sn`), pair time,
  2-year expiry (`ble_state_transmitter_alert_didExpired = 发射器已过期`),
  fault state, history list (`cgm3_history_trans`), QR **on the transmitter**
  (`请扫描包装或发射器上的二维码`).
- **Probe** (探头): own SN (`探头序列号`), activation time, 15-day max
  (`一次性探头，最多佩戴15天`), replace-only flow (`只更换一次性探头`),
  reconnect detection (`系统检测到发射器与探头已重新连接…是否已更换探头`,
  `未更换探头` / `已更换新探头`).
- Sensor activation is a separate step with a **sensor activation code**
  (`步骤2：激活传感器`, `核对传感器激活码`, `请输入激活探头SN号` /
  `扫码激活`; bottom cover carries QR, "完成激活前请暂时保留").

### 5.2 Pairing/lifecycle flow (from strings + `assets/pairH5/guide_locales.json`)

1. 植入传感器 — applicator implant (arm/abdomen), keep QR cover.
2. 激活传感器 — verify activation code (manual SN entry or QR).
3. 安装发射器 — snap transmitter on (battery seats click).
4. 配对发射器 — pair new transmitter or reuse paired one
   (`已配对发射器` / `配对新发射器`); unpair required on old phone first
   (`配对被拒绝！请在原设备上正常解配该发射器…`), else forced-delete path.
Probe swap under a bonded transmitter goes through the reconnect dialog,
not a full re-pair. Pairing uses Android **CompanionDeviceManager**
(`AidexCompanionDeviceService`, new in v161 manifest) plus new
`Cgm3TransOperationActivity`, `TransmitterStepActivity`,
`Cgm3QrCodeScanActivity`, `BleScanReceiver`.

### 5.3 `Cgm3Controller` API (new handshake!)

Mirrors `AidexXController` method-for-method (calibration, histories, default
params, status, start time, sensor check, logs, reset, deleteBond) **plus**:

- `getSeed`, `getSessionKey` / `setSessionKey`, `pairWithAppType` —
  a **seed/session-key handshake, different from the X F001-challenge flow**.
  Static framing is now known (see 5.3.1); only the seed→session-key
  derivation bytes still need a live sample or runtime dump.
- `setSensorSn` / `getSensorSn`, `getSensorNum` (transmitter tracks probe
  count), `newSensorOnline`, `resumeSensor` (probe-swap resume).
- `getDeviceSpec` / `getSoftwareInfo` (`Cgm3Parser`: `getCgm3DeviceSpecEntity`,
  `getCgm3SoftwareInfoEntity`), `getFactoryCalib` / `setFactoryCalibration`,
  `setDefaultParamByteData`, `setNotificationCondition`,
  `setNotificationPeriod` (notification cadence is configurable!),
  `setTimeOffset`, `setSendTimeout`, `keepConnection`,
  `enableBroadcastData`, `getControllerPhase`, `getCrc16`/`getCrc8`
  (same checksum family as X).
- `Cgm3Parser`: `getCgm3BroadcastEntity`, `getCgm3StatusEntity`,
  `getCgm3NotificationEntity`, `getCgm3Calbrations`,
  `checkAndGenSensorSn` / `checkAndParseSensorSn`.

#### 5.3.1 Cgm3 wire opcodes — recovered statically

Every request builder calls the vtable send helper with `w1` = raw wire opcode
byte (`tools/blecomm_skim.py` extraction; spot-verified by full disassembly:
`getStatus`→`0x05`, `reset`→`0x29`, `getSensorNum`→`0x04`, `getStartTime`→`0x01`).
Wrapper op-ids (`0x2xx/0x3xx/…`, cf. X's `0x300/0x301`) are internal only.

| Wire | Meaning | Wrapper | X equivalent |
|---|---|---|---|
| 0x01 | get start time | 0x20B | 0x21 |
| 0x02 | get sensor SN | 0x209 | — (new) |
| 0x03 | get factory calib | 0x20A | — (new) |
| 0x04 | get sensor num | 0x208 | — (new) |
| 0x05 | get status | 0x400 | (status frames only) |
| 0x07 | sensor check | 0x405 | 0x32 |
| 0x08 | history range | 0x401 | 0x22 |
| 0x09 | histories | 0x402 | 0x23 |
| 0x0A | raw histories | 0x404 | 0x24 |
| 0x0B | calibration range | 0x501 | 0x26 |
| 0x0C | calibration record | 0x502 | 0x27 |
| 0x0D | log range | 0x601 | — |
| 0x0E | log histories | 0x602 | — |
| 0x0F | error log | 0x603 | — |
| 0x20 | new sensor | 0x203 | 0x20 |
| 0x21 | set factory calibration | 0x202 | — (new) |
| 0x23 | resume sensor | 0x204 | — (new) |
| 0x24 | set calibration | 0x500 | 0x25 |
| 0x26 | keep connection | 0x300 | — (new) |
| 0x27 | notif period / condition | 0x301 | ≈0x34/0x35 |
| 0x28 | enable broadcast data | 0x302 | ≈0x35 |
| 0x29 | reset | 0xF00 | 0xF0 |
| 0x2A | delete bond | 0x102 | 0xF2 |
| 0x2B | clear storage | 0xF02 | 0xF3 |
| 0x2D | set time offset | — | — (new) |
| 0x2E | **activation chunks** (probe) | — | — (new) |
| (0x30/0x31 equiv) | set/get default params (`LongAttribute` chunking) | — | 0x30/0x31 |

`handleCommand` dispatches receive frames by length (16/17 B) and `0xFF/0xFE`
markers. DP uses the same `LongAttribute` long-write machinery as X
(`send`/`query`/`sendResp`/`queryResp`, 16-B aligned chunks).

#### 5.3.2 Pairing/handshake framing — recovered statically

- `sendPairCommand`: writes **17 bytes to 0xF001** = 16-B secret (same store
  offset as X's `snSecret`) + 1-B appType (`[x0+0x100]`, set by
  `pairWithAppType`; log tag `sendPairCommand appType=%d`). Same characteristic
  and shape as X's F001 challenge.
- `getSeed`: requests **20 bytes** (w1=0x14) via the read path; status `0x104`.
- `setSessionKey(const uchar*)`: consumes a **16-byte key** (AES-128 size).
- `startEncryption(const uchar*)`: verifies a 17-B seed response
  (16 B + check byte via `LibChecksum_GetChecksum8Bit`), then sets the
  encrypted-traffic flag — same gate pattern as X's post-BOND config.
- `sendBondCommand`: `Ble::read(...)` after a state check.
- Crypto calls used: `AesEncryptor::setKey/setIv/decrypt`,
  `DevComm::select`, `MD5::digest`. No asymmetric crypto anywhere.

#### 5.3.3 Parser entity layouts — recovered statically

Cursor is `InputByteStream` (`readUnsignedByte/readUnsignedShort/readByte`,
Java-stream convention → multi-byte fields read **big-endian**; confirm live
against one sample). Layouts (sequential reads):

- **Status** (`Cgm3StatusParser::getStatus`, ≥6 B): u16, u16 split as
  2-bit flags (`>>14`) + 14-bit value (`&0x3FFF`), u8, u8.
- **Broadcast** (`Cgm3BroadcastParser::getBroadcast`, tags `getBroadcast` /
  `Broadcast Parse Error`): same 6-B header, then **up to 2 entries**, each:
  signed byte → float /10.0, u16 split as 10-bit glucose (`&0x3FF`) + 2-bit
  flags (`>>10`), u8, validity = `u16 != 0xFFFF` (note: **0xFFFF sentinel,
  not X's 1023**).
- **DeviceSpec** (`Cgm3DeviceSpecParser::getDeviceSpec`, 14 B payload):
  `[u8, u16, u16, u16, u8, u8, u16, u8, u8, u8&1]`.
- **Notification** (`getCgm3NotificationEntity`): response struct
  `[u16@+2, u8@+4, u16@+6, u8@+8, u8@+9, u8@+1]` (period + conditions).

### 5.5 Java-layer pairing architecture (decompiled from memory dump)

BlackDex ("Unpack failed") and Frida (spawn- and attach-time process suicide;
the app `getattr`-scans `/data/local/tmp/re.frida.server`) are both detected
by the packer, so the dex was recovered without injection: rooted phone, app
at login screen, `kill -STOP`, `dd` of the two `[anon:dalvik-DEX data]`
regions (16 + 15 MB) via `/proc/<pid>/mem`, carve `dex\n037` by header
`file_size`, restamp Adler32 (ART quickening dirties it in memory), jadx.
4 dex → ~15k files (`jadx_c0`–`c3`). Method: `tools/`-side notes in Section 10.

#### Bring-up (one-time per transmitter)

`Cgm3TransmitterModel.Companion.instance(entity, proxy)`:
`setMac` → `setSn(deviceSn)` → `setName` → `setHostAddress(userId UTF-8)` →
`setId(accessId)` → **`setKey(encryptionKey)`** (session key from cloud,
may be null on first pair) →
`setSn(deviceSn, true, secretB64, ivB64)` (MMKV dynamic secret first,
entity `publicKey`/`communicationKey` fallback) →
`setSupportKeepConnect(true)` → `MessageCallback`. Timeouts: pair 40 s
(`TIMEOUT_MILLIS`), resume-sensor 30 s (`RESUME_SENSOR_TIMEOUT_MILLIS`).

#### Dynamic secrets (`DynamicSecretManager`, singleton)

- Hardcoded TEST pairs: `TTTT000003/4`, `TEST000005` (fixed secret+IV —
  factory test transmitters; harmless, useful for lab testing).
- `isUseDynamicSecret(bleName)`: default true iff name is `AiDEX F`/`AiDEX S`;
  else cloud `BleNameInfo{bluetoothName,id,isSpecial,remark,sort,status}`
  list (`bluetooth/dict/v2/findAllBluetoothDictList`), `special==1`.
- `needBlockByLanguage`: `deviceType==3` exempt; "special" names refused
  unless OS locale is Chinese (geo-fence lives in the app, not the sensor).
- `get/setDynamicSecret(sn)`: MMKV per-SN
  `PairDynamicKeyInfo{publicKey, communicationKey, deviceSn}`.
- `checkIsIpInChina(sn)` coroutine (same CN-gating as X's `getSnConfig`).

#### Persistence (ObjectBox)

`TransmitterEntity{deviceSn, transmitterSn, sensorId, sensorStartTime,
encryptionKey, publicKey, communicationKey, accessId, et, userId, idx,
isUnpaired, ...}` + `HistoryDeviceInfo` mirror (note the naming quirk:
`HistoryDeviceInfo.sensorSn` actually carries the **transmitter** SN).
`encryptionKey` (session key, Base64 `deviceKey`) survives reconnects and is
re-installed via `setKey()` on every bring-up; cleared on delete-pair.

#### Pairing (`CGMPairUtil`, 927 lines)

- `startPair`/`doPair` → `controller.pairWithType(1)` (appType **1** = phone)
  or `pair()`, then `getTransInfo()`; `BondStateReceiver`, retries.
- `Operation`: PAIR/ACTIVATE/…; `Component`: SENSOR/…; phase machine with
  `pairFlowNeedStatus`, `isPairThenActivate`.
- Activation: `prepareActivateParams(seed)` →
  `doNewSensorOnline(controller, decodeActivationCode(first16chars))` with
  cloud `ActivateParam{iv, ciphertext, tag}` (hex) + timestamp →
  native `newSensorOnline(datetime, iv, ciphertext, tag, sn)`.
- Activation code (fully recovered): `encode(16 B)` = 16×`byteToActivationChar`
  (0–9→`0`–`9`, 10–35→`A`–`Z`) + CRC-16/CCITT-FALSE (init 0xFFFF, poly 0x1021,
  bit-by-bit) over the ASCII, base-32 over
  `fCalCharset="VAPFWGUZYQ832JTRCMH69KNE5D4S7BX"`, pad `V` to 4 chars,
  reversed → **20 chars**. `decode` inverts per char (case-insensitive);
  only the first 16 chars are used.
- `Cgm3Operation.java:5-41` (wrapper op-ids, low byte often = wire opcode):
  `GET_STATUS=1024, GET_HISTORY_RANGE=1025, GET_HISTORIES=1026,
  GET_HISTORIES_RAW=1028, GET_SENSOR_CHECK=1029, GET_CALIBRATION=1282,
  GET_CALIBRATION_RANGE=1281, SET_CALIBRATION=1280, GET_DEVICE_SPEC=256,
  GET_SOFTWARE_INFO=257, GET_SEED=260, GET_SENSOR_NUM=520, GET_SENSOR_SN=521,
  GET_FACTORY_CALIBRATION=522, GET_START_TIME_3=523, SET_SENSOR_SN=513,
  SET_FACTORY_CALIBRATION=514, SET_NEW_SENSOR=515, SET_RESUME_SENSOR=516,
  NEW_SENSOR_ONLINE=526, SET_TIME_OFFSET=525, GET_ACTIVATED_NUMBER=524,
  SET_NOTIFICATION=769, KEEP_CONNECTION=768, ENABLE_BROADCAST_DATA=770,
  RESET=3840, CLEAR_STORAGE=3842, DELETE_BOND_3=258,
  SET/GET_DEFAULT_PARAM=3585/3586, GET_MODE_NUMBER=2454,
  AUTO_UPDATE_FULL_HISTORY/CALIBRATION=65025/65026, SET_SN=65281`,
  plus log/error ops (`GET_LOG_RANGE/HISTORIES=1537/1538, GET_ERROR_LOG=1539`).

#### Seed/session keys — verdict

`getSeed`/`setSessionKey`/`getSessionKey` have **no Java callers in any dumped
dex** (verified across all four trees). The live flow is: cloud material +
cloud session key → `setSn`/`setKey` → `pairWithType(1)` → native F001 →
native-driven session (`startEncryption` gate). **Juggluco does not need any
seed derivation**: replicate the cloud-key flow. (The JNI remains for the
watch app / future use; `pairWithAppType` exists precisely for non-phone
app types.)

#### Entity field lists (for driver struct mapping)

- `Cgm3BroadcastEntity{status, historyCount:byte, history:List<Cgm3History>}`.
- `Cgm3HistoryEntity{glucose, quality, status, timeOffset, trend:float,
  isValid}`; `Cgm3RawHistoryEntity{i1,i2,vc:float, timeOffset, isValid}`.
- `Cgm3StatusEntity{battery, calibrationNum, phase, timeOffset, warning}`.
- `Cgm3SensorSnEntity{sn, sensorModel, year, month, day, offset, cf,
  expiryDays, result}`.
- `Cgm3DeviceSpecEntity{flag, lowerLimit, upperLimit, period:byte}`;
  `Cgm3SoftwareInfoEntity{majorVersion, minorVersion, build, branch, test,
  revison}`.
- `Cgm3NotificationEntity{type:byte, onlyStatus}`; `Cgm3CalibrationEntity{cf,
  offset:short, index, referenceGlucose, referenceTimeOffset,
  finishTimeOffset, isFinished, isValid, result}`;
  `Cgm3ActivatedNumberEntity{activatedSensorNumber}`.

#### Cloud endpoints (G9-relevant, `ApiServiceKt`, 136 total)

`cgmDevice/activation`, `userDeviceRegister`, `deviceUnRegister`,
`getFirstAndLastDeviceInfo` (= `HistoryDeviceInfo` source),
`getUserDeviceInfo`, `deviceHeartBeat`, `deviceStatusUpdate`, `getSnConfig`
(same as X), `v2/verifySensorSn`, `bluetooth/dict/v2/findAllBluetoothDictList`,
`getDeviceHistoryByUserIdAndSensorIds`. Same host + RSA envelope as X —
Juggluco's `AiDexCnCloudClient` pattern ports directly; only per-endpoint
request fields need mapping from callers (e.g.
`TransmitterManager$buildModel$historyDevice`).

### 5.4 What is still unknown (needs live device or runtime dump)

~~GATT service/characteristic UUIDs for Cgm3~~ — solved statically (4.1:
0x181F/F001/F002/F003/F005, same as X). ~~Opcode map~~ — solved (5.3.1).
~~Handshake framing~~ — solved (5.3.2). ~~Parser layouts~~ — solved (5.3.3).
Remaining, in priority order (seed derivation CLOSED — see 5.5: no Java
callers; replicate the cloud-key flow instead):

1. **Activation/cloud request fields**: params for `activation`,
   `v2/verifySensorSn`, `userDeviceRegister`, `getFirstAndLastDeviceInfo`
   (map from callers; same envelope as X).
2. **Activation-code algorithm** (`0x2E` chunks): static shape known, code
   content unknown — same recovery path as (1).
3. **Adv-name pattern + MAC behavior across probe swaps** (needs any live
   transmitter, even without pairing: nRF Connect logs).
4. **`getSnConfig` keying** for transmitter vs probe SNs (proxy one login +
   both SNs through the existing CN client).
5. **DP `versionKey` + row for G9 firmware** (0x31-probe once talking).
6. **Byte-order confirmation** for `InputByteStream` multibyte fields
   (big-endian per Java-stream convention — one sample settles it).
7. CompanionDevice association flow details (UX-level, minor).

---

## 6. X-family protocol reference (what Juggluco implements today)

This is the all-in-one stack. G9 reuses its shapes where noted, but Section 5
differences apply.

### 6.1 GATT

`aidex/native/ble/AiDexBleManager.kt:170-183` — service `0x181F`
(`0000181f-…`, = `SERVICE_F000`), chars `F001/F002/F003`
(`0000f00X-…`); DIS `0x180A` (model `2A24`, sw rev `2A28`, mfr `2A29`);
CGM session start `2AAA` + session run `2AAB` under `0x181F`
(`AiDexBleManager.kt:4004-4010`). Scan is pinned to the `0x181F` service UUID
(`AiDexBleManager.kt:6645-6656`); `getService()` returns `SERVICE_F000`
(`:1794`); missing-service triggers recovery (`:2328-2330`).

### 6.2 Key exchange (X)

`aidex/native/protocol/AiDexKeyExchange.kt:14-22`:
1. write `snSecret` → F001 (challenge);
2. 16-byte PAIR key ← F001 notify (stable until sensor-side UNPAIR);
3. read 17-byte BOND ← F002, decrypt with PAIR key + SN IV
   (`AesCfb128.decryptBondData`, CRC-8 check) → session key (per connection);
4. write post-BOND config, plaintext always `10 C1 F3`, encrypted;
5. F003 + F002 traffic under session key + SN IV (`AesCfb128` CFB-128).

Serial-derived fallback when no provisioned material
(`SerialCrypto.kt:7-12`): `secret = MD5(snToBytes·(b·13+61))`,
`iv = MD5(snToBytes·(b·17+19))`, `snToBytes`: `0-9→0-9`, `A-Z→10-35`
(`:26-44`); verified against HCI trace for sensor `2222267V4E`
(`Secret=4b7616…873d2`). Since app 1.15.1 the vendor prefers per-sensor
provisioned material via native `setSn(serial, dynamic, secret, iv)`
(`AiDexPairingMaterial.kt:9-13`).

### 6.3 F002 opcodes

`aidex/native/protocol/AiDexOpcodes.kt:9-121` (verified against ARM64
disassembly): `0x10` startup device-info (= post-bond config),
`0x11` broadcast-data query, `0x20` set new sensor, `0x21` start time,
`0x22` history range, **`0x23` raw/calibrated history (2-B rows)**,
**`0x24` brief/ADC history (5-B rows)**, `0x25` set calibration,
`0x26/0x27` calibration range/record, `0x30/0x31` set/get default params
(wrapper op ids `0x300/0x301`), `0x32` sensor check, `0x33/0x34` auto-update
status get/set, `0x35` dynamic-adv mode, `0xF0` reset, `0xF1` shelf,
`0xF2` delete bond, `0xF3` clear storage. F002 replies echo the command byte
(`AiDexParser.kt:245-248`).

### 6.4 F003 live frames

17-byte DATA (`AiDexParser.kt:74-106`): `[0]` opcode (scaling:
direct `{0xA1,0xA4,0x5B,0xD7}` ×1.0, `{0xD2}` ×0.5 — `AiDexOpcodes.kt:145-155`),
`[4..5]` u16LE offset minutes, `[6..7]` u16LE packed glucose (`& 0x03FF`,
sentinel 1023, range 20–500), `[8..9]` i1 /100, `[10..11]` i2 /100,
`[15..16]` CRC-16 LE. **Quirk: `i2` is skin temperature in °C, not a second
glucose current** (co-worn verified, `DataClasses.kt:29-36`). 5-byte STATUS
frames carry only a header byte (`:110-114`). Expected live cadence 60 s
(`AiDexBleManager.kt:229`).

### 6.5 Broadcast advertisements

Manufacturer data `0xFF` (`AiDexBleManager.kt:6852-6884`): `[0..3]` u32LE
offset minutes, `[4]` i8 trend, `[5..6]` 10-bit packed glucose
(`lo | (carry & 0x03) << 8`) with u8 fallback at `[5]`
(`:6904-6941`). Rejection reasons are logged with payload hex (`:6943-6954`);
duplicates suppressed 70 s (`:7017-7031`); cadence phase-locked 45–90 s
(`:273-284`). Local name parsed from AD types `0x08/0x09`
(`:100-120`); names matched to serials (`:60-64`) with family prefixes
(`:74-76`, plus `AiDexSerialIdentity.kt:18-44` generation/family regexes and
11-char bare fallback). Known prefixes include AiDEX/LinX/GlucoRx/Vista/
LumiFlex/Wellion/CGM/Smart- (`AiDexOpcodes.kt:174`, `AIDEX_TITLE_PREFIX`).

### 6.6 History, calibration, sessions

- 0x23 rows: startOffset u16LE + 2-B rows (`glucose = b0 | (b1&0x03)<<8`,
  status `(b1&0x04)`) — `AiDexParser.kt:126-150`.
- 0x24 rows: 5-B `[i1_lo,i1_hi,i2_lo,i2_hi,vc]`, `/100`; all-zero = invalid;
  `raw = i1·10`, `sensorGlucose = i1·18.0182` — `:167-196`.
- Calibration 0x27 rows: 8-B `[offsetMin u16, ref u16, cf u16/100,
  offset s16/100]` — `:211-238`.
- Startup info (0x10): 16 B `[status u16==0, fwMaj, fwMin, hwMaj, hwMin,
  wearDays, model ASCII…]` — `:259-283`. Start time: `2AAA`/0x21 payload
  `[year u16, mon, day, h, m, s, tzQ, dstQ]`; all-zeros = uninitialized →
  auto `0x20` activation — `:303-327`, `AiDexBleManager.kt:887-901`.
- Default params: 0x31 probe → compare vs catalog → 0x30 chunked apply,
  ≤160 B payloads, 10 s ACK watchdog
  (`AiDexBleManager.kt:245-246,501-512`; `AiDexDefaultParamProvisioning.kt`;
  catalog match = `settingType` + `aidexVersion=="X"` + firmware `versionKey`,
  `:369-373`; import OTA rows via `AiDexNativeFactory.kt:100-108`).
- Company ID in adv: `0x0059` (`AiDexOpcodes.kt:171`).

### 6.7 Crypto inventory

- `AesCfb128` — BOND/session/F002/F003 traffic (X).
- `SerialCrypto` — serial→secret/IV derivation (above).
- `Crc8Maxim` — BOND integrity (`decrypted[16] == CRC8(decrypted[0..15])`).
- `Crc16CcittFalse` — frame CRCs (poly 0x1021, init 0xFFFF).
- `Ascon`/`Ascon128a` (`Common/src/mobile/java/tk/glucodata/aidex/`) —
  vendor suite member; exact traffic scope per call sites.
- Provisioned material: 2×16 B (secret=F001 challenge, iv) from CN cloud
  or QR/file import, Android-Keystore persisted
  (`AiDexProvisioningStore.kt`, `AiDexPairKeyVault.kt`,
  `AiDexPairingMaterialRegistry`).

### 6.8 Cloud + QR/file formats

- `AiDexCnCloudClient.kt:41-46`: `https://china.pancares.com`,
  `/backend/aidex-x/user/…` (SMS + password login), `/…/cgmDevice/getSnConfig`
  → `{deviceSn, publicKey, communicationKey}` (hex-or-base64 16 B,
  `:257-264`). RSA envelope + MD5 integrity sign (`:228-298`); UA impersonates
  `com.microtech.aidexx,1.15.1` (`:191`); `getSnConfig` needs CN egress
  (X-Forwarded-For rotation, `:57-64`).
- Portable pairing file (`AiDexPairingMaterialFile.kt:8-33`):
  `{"format":"juggluco-aidex-pairing-material","version":1,
  "sensorSerial","publicKey","communicationKey"}`; decode accepts
  `deviceSn`/`serial`, `secret`/`iv` aliases (`:35-53`).

### 6.9 Quirks and pitfalls (from driver comments — keep these)

MTU exchange wedges GATT if discovery/CCCD starts too early: MTU-callback
fallback + 750 ms settle before first CCCD (`AiDexBleManager.kt:195-225,601-614`);
dropped CCCD callbacks assumed complete after bounded waits (`:615-666`);
GATT-op watchdog 15 s (`:332-351`); peer-initiated second MTU exchange;
bonded-reconnect vs fresh-pair paths with persisted failure counters and
`savedKeyExhausted` latching (`:396-435`); pre-auth encrypted-traffic watchdog
(`:562-599`); `CLEAR_STORAGE` quiet window + post-reset `0x20` auto-activation
(`:692-721,887-901`); 7-min warmup gate + 60-min first-valid window
(`:253-254`); broadcast fallback + connected-broadcast request + history
backfill before CCCD refresh on no-stream (`:724-786`); history resume offsets
persisted per sensor (`:903-945,1003-1015`); 0x23/0x24 merge cache with
cross-page fallback (`:1017-1026`); `MAX_OFFSET_DAYS = 30`
(`AiDexBleManager.kt:252`, `SensorInfo.maxDays = 15` default).

---

## 7. G9 gap analysis (X driver → Cgm3 driver)

| Area | X today | G9 (Cgm3) needs | Reuse |
|---|---|---|---|
| Scan/discovery | 0x181F filter + name regexes | add transmitter-name pattern (service confirmed 0x181F) | scan plumbing, phase-locked broadcast scan |
| Identity | 1 serial (sensor) | **2 identities**: transmitter SN (bond) + probe SN (session) + activation code | serial-identity module (extend, don't bend) |
| Pairing handshake | F001 challenge → PAIR → BOND → session key | cloud-key flow: `setSn`+material, `setKey(deviceKey)`, `pairWithType(1)` — no derivation | F001 shape, AES/CRC primitives, bring-up order (5.5) |
| Crypto | AES-CFB-128 + serial/provisioned material | same primitives likely, new KDF/scope — verify | `AesCfb128`, CRCs, Keystore vault |
| Live data | F003 17-B frames | verify Cgm3 frame layout vs X (lengths known: 16/17 B) | parser shapes |
| History/calibration | 0x22–0x27 | adapt to Cgm3 opcodes 0x08–0x0C/0x24 (table 5.3.1) | request/download/merge machinery |
| Default params | 0x30/0x31 + catalog by fw versionKey | capture G9 fw `versionKey` + row via 0x31 probe; import path exists | `importDefaultParamCatalogJson` |
| Probe lifecycle | N/A (whole unit) | activation codes, reconnect dialog, `newSensorOnline`/`resumeSensor`, `getSensorNum` | new state machine |
| Pairing UX | SN/QR → key exchange | CompanionDevice flow + transmitter/probe pickers + activation screen | onboarding screens (new) |
| Provisioning | `getSnConfig(sensorSn)` | same endpoint + `getFirstAndLastDeviceInfo` (deviceKey) + `PairDynamicKeyInfo`; map request fields | client pattern, envelope, test keys (5.5) |

Do **not** shoehorn Cgm3 into `AiDexBleManager`'s single-serial session —
the Sibionics split precedent (`SibionicsConstants.kt:134`,
`SibionicsSessionPolicy.kt:35`) shows the two-identity pattern works better
as its own manager sharing only crypto/store utilities.

---

## 8. Implementation plan (phases)

1. **~~Seed derivation~~ — closed**: replicate `setSn`/`setKey`/`pairWithType(1)`
   per 5.5; no seed math needed. Remaining cloud-field mapping (phase 4) is
   read-only RE of already-dumped callers.
2. **Driver layer** (`tk.glucodata.drivers.aidex.cgm3`, implemented +
   unit-tested, dependency-free): `Cgm3Opcodes` (wire/wrapper IDs, UUIDs,
   framing), `Cgm3ActivationCode` (full codec + CRC), `Cgm3Parser`
   (status/broadcast/deviceSpec/notification per 5.3.3),
   `Cgm3Provisioning` (transmitter-scoped material, dynamic-key JSON,
   test keys), `Cgm3Cloud` (endpoint paths), `Cgm3Session` (bring-up order,
   lifecycle states). Tests: `Cgm3ActivationCodeTests` (16 tests, green).
   Remaining: `Cgm3BleManager` GATT session (needs one live session).
3. **Provisioning**: extend CN client for transmitter/probe `getSnConfig`
   semantics; activation-code flow (format TBD from capture/H5 guide).
4. **DP catalog**: 0x31-probe a G9, add row(s) keyed by fw `versionKey`
   (import path already exists — no code change needed for the row itself).
5. **Onboarding UI**: 4-step flow (implant → activate → snap → pair),
   transmitter picker, probe-swap dialog, CompanionDevice association.
6. **Validation matrix**: adv-only mode → bonded pair → live stream → history
   backfill → probe swap under bond → transmitter expiry path → re-pair on
   new phone (forced-delete semantics).

---

## 9. Live-device capture checklist

Static RE has already closed the UUID, opcode, framing and layout rows —
what remains is behavior + the two byte-derivations:

- [x] GATT services/characteristics — **done statically** (4.1).
- [x] Wire opcode map — **done statically** (5.3.1).
- [x] Pair/handshake framing (F001 17 B, seed 20 B, session key 16 B) —
  **done statically** (5.3.2).
- [x] Parser layouts (status/broadcast/deviceSpec/notification) —
  **done statically** (5.3.3).
- [ ] nRF Connect adv sample (name, MAC, company ID, mfr payload hex, RSSI,
  cadence over 10 min; then re-sample **after a probe swap** — does the
  name/MAC change?).
- [ ] HCI snoop (Android "Enable Bluetooth HCI snoop log") of full vendor-app
  flow: scan → pair transmitter → activate probe → first glucose →
  history download. Export btsnoop + screen recording with timestamps.
  (Needed for: seed-to-session-key bytes, activation-code content.)
- [ ] `getSnConfig` request/response for transmitter SN and probe SN
  (which one returns material? both? — proxy one login + both SNs through
  the existing CN client).
- [ ] 0x31-equivalent DP probe bytes + firmware/model strings from
  `getDeviceSpec`/`getSoftwareInfo`.
- [ ] QR contents: transmitter QR, probe/bottom-cover QR, activation code
  format (photo + decoded text).
- [ ] Transmitter info screen values (SN, pair time, firmware) + probe screen
  (SN, activation/expiry) for field mapping.
- [ ] CompanionDevice association prompt flow (screenshots).
- [ ] Byte-order confirmation: compare one live frame against the 5.3.3
  layouts (big-endian assumed).
- [ ] `setNotificationPeriod` effect: change cadence in vendor app if exposed,
  observe adv/GATT timing change.

---

## 10. Artifact inventory

- `com.microtech.aidexx` v1.11.0 / v1.13.0 / **v1.16.1** APKs + decoded trees
  (`wtdt_dec`, `d13_dec`, `v161_dec`), lib strings (`ble_strings.txt`,
  `ble13_strings.txt`, `ble161_strings.txt`), G9 flow strings
  (`cgm3_flow_strings.txt`) — kept outside the repo in the analysis dir.
- CN mirror how-to: iuuu9 `d.php?androidpack=…` / dianlut + ddooo CDNs, all
  requiring the listing page as HTTP `Referer`; 87g direct link is
  network-dependent; Tencent AppBao hosts no binary; APKPure/Aptoide don't
  carry the CN package; Uptodown carries only the old intl AiDEX behind
  Turnstile; Xiaomi hosts 微泰动泰 behind Verify.
- RE toolkit: `tools/blecomm_dis.py`, `tools/blecomm_skim.py` (capstone-based; see 4.x).
- Dump set: 4 carved dex (~15k decompiled files), `api_endpoints.txt` (136),
  `cgm3_flow_strings.txt`, carved+restamped dex method in 5.5.
- Prior art: `aidex/native/{ble,crypto,data,protocol}/`, `AiDexCnCloudClient.kt`,
  `AiDexProvisioningStore.kt`, `AiDexPairingMaterialFile.kt`,
  `tk.glucodata.aidex.Ascon*` (mobile sourceset), tests
  (`ExchangeTrendTests`, `AiDexParserDataFrameTests`).

## 11. Open questions

1. ~~Seed derivation~~ — closed (5.5). Residual: exact `activation` /
   `verifySensorSn` / register request fields (read off dumped callers).
2. `getSnConfig` keying for transmitter vs probe SNs.
3. Activation-code algorithm (server-issued per probe? derived?).
4. G9 DP `versionKey`(s) and `settingType` coverage.
5. Adv-name pattern + MAC behavior across probe swaps.
6. Intl G9 variant (if any): separate app/package or same `aidexx` + cloud?
7. Wecare watch direct-connect protocol (BLE or via phone relay?).
8. `InputByteStream` byte order confirmation (big-endian assumed).
