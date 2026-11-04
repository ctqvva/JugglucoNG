# Kotlin-managed Libre 3 driver — handoff

Status: **draft / WIP, never run on a phone or sensor.** PR: ctqvva/JugglucoNG#839, branch `cc/relaxed-ride-1bgnmk`.
Off by default; it is reached only when the user turns it on and imports an app identity.

## 1. What is implemented

All in `Common/src/main/java/tk/glucodata/drivers/libre3/` unless noted. None of this code calls Libre 3-specific JNI or a proprietary `.so`.

| Area | File | Notes |
|---|---|---|
| Byte helpers | `Libre3Bytes.kt` | |
| NFC | `Libre3Nfc.kt` | Covers patch info `A1`, `A0`/`A8` body (`LE32(now-1) ‖ LE32(receiver) ‖ CRC16`) and activation/error parsing. Layout follows `cpp/libre3/nfc.cpp` and `nfcmetadata.hpp`. |
| Receiver ID | `Libre3ReceiverId.kt` | Classic and "Libre by Abbott" account folds, a random own ID, and the wire-hex form. |
| AES-128-CCM | `Libre3AesCcm.kt` | Built on `AES/ECB/NoPadding`, because Android has no CCM `Cipher`. |
| P-256 | `Libre3P256.kt` | JCA ECDH/ECDSA (raw r‖s converted to DER), on-curve check, and d·G used only to validate an imported key. |
| Auth material | `Libre3Security.kt` | App identity (JSON format 1), 140-byte sensor certificate plus the 2 public patch-signing keys, kAuth, Phase 5/6. |
| Handshake | `Libre3Handshake.kt` | Pure state machine for full auth and cached-kAuth reconnect; it returns the GATT writes to perform. |
| Data plane | `Libre3DataPlane.kt` | GATT UUIDs, 20-byte offset-prefixed writes, notify reassembly, 35-byte glucose assembler, per-channel descriptors taken from `cpp/bcrypt/bcrypt.cpp`, outbound sequence starting at 1. |
| Records | `Libre3Records.kt` | Realtime (29 B), patch status (12 B), clinical (14 B), historic pages, event log, control commands. |
| Persistence | `Libre3Persistence.kt` | Prefs file `tk.glucodata_libre3_managed`. Identities and kAuth are sealed with an AndroidKeyStore AES-GCM key. |
| Driver | `Libre3BleManager.kt` | A `SuperGattCallback` + `ManagedBluetoothSensorDriver`, described below. |
| Identity seam | `Libre3IdentityAdapter.kt` | Sensor ID is the 9-character serial; it does not claim the legacy `E07A-XX<serial>` name. |
| NFC flow + registry | `Libre3ManagedNfc.kt` | `scanForUi` for `ScanNfcV`; `Libre3Registry.addSensor` retires the legacy callback. |
| UI | `Common/src/mobile/.../ui/Libre3ManagedDriverSection.kt` | Shown under LibreView settings: enable switch, receiver-ID source, identity import/remove. |

`Libre3BleManager.kt` does the following:
- runs one serialized GATT op queue;
- enables CCCDs in the legacy order;
- applies a 60 s setup deadline and a 5 min stream watchdog;
- rotates identities when the sensor refuses one;
- requests clinical and historic backfill, one request in flight at a time;
- stores readings in Room and the generic native mirror.

Integration edits outside the package:
- `ManagedSensorUiFamily.LIBRE3(9)`, with mappings in `SensorVendor.kt`, `SensorSourceResolver.java` and `NightPost.getSensorStartTime`.
- The adapter is registered in `ManagedSensorIdentityRegistry`.
- `ScanNfcV.libre3scan` hands the scan to the managed path when enabled; the `gethaslibrary()` gate is skipped for managed Libre 3 tags.
- `SettingsExporter` excludes the Libre 3 prefs file in both directions (`DEVICE_LOCAL_PREFERENCES`).

## 2. What was actually tested

- `./gradlew :Common:compileMobileDebugKotlin` passes.
- `Libre3ProtocolTest` (11 tests) passes. It uses:
  - the `dp_activation.hpp` static_assert vectors;
  - patch-info and `A8` response samples taken from comments in `nfc.cpp`;
  - both receiver-ID folds (RoundWhiteDiscKit vectors);
  - RFC 3610 packet vector 1, plus a tampered-frame check;
  - captured glucose, status and historic frames, and a captured sensor certificate (from RoundWhiteDiscKit tests);
  - synthetic-scalar kAuth/Phase 5/Phase 6 vectors (RoundWhiteDiscKit `AppIdentityTests`);
  - a cached handshake driven end to end through the state machine;
  - a check that d·G matches JCA.
- `Libre3DriverGuardTest` (5 tests) passes. These are source pins for the fixes in section 3.
- These suites also pass: `ConnectModeLeverTests`, `ManagedSensorFamilyTests`, `SensorVendorTests`, `SensorSourceResolverTests`, and `tk.glucodata.drivers.*`. In one run `AiDexAlgorithmRuntimeTests` failed because Robolectric could not download its Android jar (HTTP 429); that class passed when rerun.
- `python3 scripts/ui-guardrails.py` exits 0.
- **Not run:** the full unit suite, the wear flavour build, any instrumented test, any device or sensor. I did not read the PR's CI or Greptile output myself; the findings in section 3 came from the owner's review request.

## 3. Defects

**Fixed in commit `b9f9bff`:**
- A backfill control-response timeout no longer settles the backfill marks; only a real response does.
- Marks are persisted only after `importHistory` has run. A thrown store re-queues the readings.
- Settings export no longer includes the BLE PIN, sealed identity or kAuth, and import no longer wipes them.
- The unmeasured `autoConnect` fallback is gone; the driver uses the user's mode. The legacy driver's fallback (#728) was measured only for that driver.
- Two buttons now pass `shapes`.
- Wear-expiry suppression is removed, so glucose availability now matches legacy. One deliberate difference remains: values during warmup (life count < warmup minutes from NFC) are not published. Legacy stores any value in 39..501.

**Confirmed, still open:**
- `VirtualGlucoseSensorBridge.importHistory` returns 0 both for "all duplicates" and for "Room store failed", so a silent store failure still advances the marks.
- The realtime value is stored with `storeCurrentReadingAsync`. The minute mark can be persisted before that async write lands.
- A full-auth test against a simulated sensor is missing; only the cached path is exercised.

**Speculative or unverified:**
- Whether the `patchControl` notification really means "backfill complete". Legacy assumes it; RoundWhiteDiscKit lists completion markers as open.
- Error code 7: shown as "check sensor" and never treated as terminal. RoundWhiteDiscKit's `protocol.md` and its `PatchStatus.swift` disagree.
- Whether a link drop right after `CertificateLoadDone` always means the identity was rejected. Rotating identities on that signal could misfire on flaky links; after 2 full rounds the driver stops until the user acts.
- Clinical backfill timestamps assume `life count × 60 s` from an anchor derived from realtime arrivals, deduplicated in a ±50 s window. Clock drift and duplication have not been measured.

## 4. Protocol assumptions and evidence

- **Wire layouts:** primarily the legacy Android code (`cpp/libre3/*.cpp`, `cpp/bcrypt/bcrypt.cpp`, `Libre3GattCallback.java`), cross-checked against RoundWhiteDiscKit (MIT, commit `7dd6396`, `protocol.md` and `Sources/`).
- **Security notifications:** the length is announced on the command characteristic as `[kind, len]`; fragments are `seq ‖ payload`. Phase 3 writes 65 bytes padded to 72, which is byte-identical to legacy's padded 20-byte writes.
- **Data-plane descriptors:** control out 0, control in 1, status 2, glucose 3, historic 4, clinical 5, event log 6, factory 7 (legacy). If the expected descriptor fails, the others are tried and the mismatch is logged.
- **Usable glucose:** packed word with bits 13–15 clear and value in 39..501. This equals legacy's whole-word check. Below- and above-range values become status text, never readings.
- **Backfill bounds:** history is committed about 16 minutes behind (`((current-16)/5)*5`, legacy). The first clinical request is capped at 6 h.
- **kAuth key schedule:** `SHA-256(00000001 ‖ Ze ‖ Zs)[:16]` is from RoundWhiteDiscKit only. It is checked against their synthetic vectors, not against a real sensor.
- **Cached kAuth:** cleared on every NFC scan (legacy clears `haskAuth` too) and after any failed cached reconnect.

## 5. Untested behaviour

None of these has been exercised:
- first pair (`A0`) and full handshake on real firmware, Libre 3 vs 3 Plus, regional variants;
- `A8` takeover of a sensor activated by the legacy driver or by an Abbott app; the receiver ID defaults to the LibreView account number, as legacy does;
- cached reconnect, process death, Bluetooth toggle, reboot, long outages;
- backfill size and timing, Room deduplication, history after an `A8` takeover mid-life;
- two Libre 3 sensors at once, and sensor replacement overlap;
- the NFC transceive retries (`AlgNfcV.wholenfccmd` retries a state-changing command up to 10 times, as legacy does).

## 6. Integration limitations

- **Native mirror:** minute readings go through the generic `VirtualGlucoseSensorBridge` mirror (shell name = serial, family code 9) for the web server and Nightscout. 5-minute history points are not mirrored. Older app versions receiving code 9 over Clone fall back to GENERIC.
- **History continuity:** the managed sensor is `<serial>`; legacy used `E07A-XX<serial>`. Switching drivers starts a new series. Registration finishes the legacy native sensor and removes its callback.
- **Phone/watch handoff:** not examined. `shouldUseSharedCurrentSensorHandoffOnTerminate()` returns false, copied from Medtrum. The wear flavour has not been compiled.
- **Legacy stack:** untouched and still the default. `NightPost` sensor-start uses the persisted NFC start time.

## 7. Credential provisioning

- The driver needs a P-256 private scalar plus the matching 162-byte app certificate that the sensor accepts. The import format is RoundWhiteDiscKit's JSON (`format:1`, `curve:"P-256"`, `identities[{label, privateKeyHex, certificateHex}]`). The key is validated against the certificate on import.
- The keys in `ECDHCrypto.java` are wrapped for the proprietary library, so this driver cannot use them. This work made no attempt to unwrap them.
- xDrip4iOS 7.2.0, per the research dossier (not re-read here): downloads versioned identity bundles from a remote JSON, checks them against compiled-in SHA-256 digests, stores them device-only in the Keychain, and pins a paired sensor to its identity version. A digest proves integrity, not secrecy or permission to redistribute.
- **Open decisions:**
  - which certificate versions each sensor family accepts (patch-info `securityVersion` is logged but unused);
  - the provenance and legality of any identity file;
  - whether to support any automatic provisioning (none was built);
  - how to recover after reinstall or a device change: today the user re-imports and rescans.

## 8. Next investigations and how to reproduce

1. Device test with a disposable sensor and logcat tags `Libre3BLE`, `Libre3Nfc`, `Libre3Registry`. Capture an HCI snoop log of the first full handshake.
2. Add a simulated-sensor full-auth test: generate a signing key, sign a fake sensor cert, pass `signingKeys`, and act as the sensor using the RoundWhiteDiscKit scalars.
3. Make `importHistory` report store failure separately from duplicates before trusting the marks.
4. Verify the backfill completion signal and the clinical timestamp alignment against a trace.
5. Compile wear: `:Common:compileWearDebugKotlin`.

Build setup used here (no Android SDK was installed by default):
```
# SDK: cmdline-tools → sdkmanager "platforms;android-37.1" "build-tools;37.0.0"; local.properties: sdk.dir=/opt/android-sdk
# Maven Central returned 429: a local ~/.gradle/init.d script redirected it to
# https://maven-central.storage-download.googleapis.com/maven2/ and dropped the plugin portal (not committed).
./gradlew --no-configuration-cache :Common:compileMobileDebugKotlin
./gradlew --no-configuration-cache :Common:testMobileDebugUnitTest --tests "tk.glucodata.drivers.libre3.*" --tests "tk.glucodata.ConnectModeLeverTests"
python3 scripts/ui-guardrails.py
```
