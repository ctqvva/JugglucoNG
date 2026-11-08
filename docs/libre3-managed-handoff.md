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
| Provisioning | `Libre3IdentityProvisioning.kt` | Pinned, versioned identity sets: source spec, digest check, mirror fallback, bounded fetch (section 7). |
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

- `:Common:compileMobileDebugKotlin` and `:Common:compileWearDebugKotlin` both build.
- 27 JVM tests in `Common/src/test/java/tk/glucodata/drivers/libre3/` pass:
  - `Libre3ProtocolTest` (11): legacy `dp_activation.hpp` vectors, NFC parsing, receiver-ID folds, RFC 3610 CCM, captured RoundWhiteDiscKit data-plane frames and sensor certificate, synthetic kAuth/Phase 5/6 vectors, cached handshake through the state machine.
  - `Libre3FullAuthSimulationTest` (2): first-pair authorization end to end against a simulated sensor (its own signing key, signed certificate, ECDH, Phase 5/6), and refusal of a certificate from an unknown signer.
  - `Libre3ProvisioningTest` (7): source-spec parsing (https only, 64-hex digest), digest checked before parsing, mirror fallback, bounded reads, legacy-equivalent NFC plausibility, strict per-channel descriptors, ASCII-only hex.
  - `Libre3DriverGuardTest` (7): source pins for the backfill, export, connect-mode, device-rejection and end-of-wear fixes.
- `ConnectModeLeverTests`, `ManagedSensorFamilyTests`, `SensorVendorTests` and `SensorSourceResolverTests` pass; `scripts/ui-guardrails.py` exits 0.
- **Not run:** the full unit suite, instrumented tests, any device or sensor. Nothing here proves a real sensor accepts these messages.

## 3. Defects

**Fixed** (`b9f9bff`, `5de6d15a`), all from Greptile/cubic review threads on #839:
- backfill timeout no longer settles marks; marks persist only after Room storage;
- a completed history request settles its range even with no pages;
- the Libre 3 prefs (PIN, sealed identity and kAuth) are excluded from settings export and import;
- the unmeasured autoConnect fallback is removed;
- a device without the Libre 3 services is not retried every 3 s; it waits for a rescan or a user retry;
- end of wear (errorData 5) is display-only; only shutdown (6/8) stops reconnects;
- no wear-duration cut-off on glucose (matches legacy);
- the native mirror window opens at the sensor start, and gap-filling 5-minute points are mirrored;
- each channel decrypts under its own descriptor only (legacy `intDecrypt`);
- NFC rejects implausible warmup/wear and nonzero implausible activation times like legacy; rescans without a reported time keep the known start;
- a missing LibreView account ID is refused with 0xF8 like legacy, instead of silently using another receiver ID;
- a terminated NFC scan ends the managed sensor;
- a successful scan shows the legacy ready/calendar dialog;
- identity import reads at most 64 KiB; Remove also clears cached kAuth;
- buttons pass `shapes`.

**Confirmed, still open:**
- `importHistory` returns 0 both for "all duplicates" and for "Room store failed", so a silent store failure still advances marks.
- The realtime value goes through `storeCurrentReadingAsync`; its minute mark can persist before that write lands.
- Phone → watch takeover is unsupported (section 6).
- New strings are English only.
- `Libre3P256.publicPointFromScalar` is variable-time BigInteger arithmetic, run once at import (cubic P3); not changed.

**Deliberate differences from legacy:**
- no readings during warmup;
- the trend-arrow rate fallback is (trend − 3) × 1.3 mg/dL/min; legacy truncated it to ±0.01.

**Speculative or unverified:** `patchControl` as the backfill-complete signal; error 7 severity; identity-rejection inference from a link drop after `CertificateLoadDone`; clinical timestamp alignment.

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

- **Native mirror:** minute readings and gap-filling 5-minute points go through the generic `VirtualGlucoseSensorBridge` mirror (shell name = serial, family code 9). The window is opened at the sensor start; the mirror geometry is 14 days, so the last day of a 15-day sensor triggers the generic rebase. Older app versions receiving code 9 over Clone show GENERIC.
- **History continuity:** the managed sensor is `<serial>`; legacy used `E07A-XX<serial>`. Switching drivers starts a new series. Registration finishes the legacy native sensor and removes its callback. An active legacy sensor has to be rescanned (A8); its 149-byte native auth blob is not convertible.
- **Phone/watch:** watch takeover is **unsupported**. `ManagedSensorHandoff` does not carry `tk.glucodata_libre3_managed`, and sealed values cannot move between devices' Keystores. No data is lost: the phone stands down only after the watch actually reads the sensor, so the phone keeps reading. Supporting it needs a deliberate transfer of the sensor record, plus either a re-import of the identity on the watch or an explicit unsealed transfer. That is undecided.

## 7. Credential provisioning

- **Need:** a P-256 private scalar plus the matching 162-byte app certificate the sensor accepts. The legacy keys in `ECDHCrypto.java` are wrapped for the proprietary library and are not used.
- **Implemented** (`Libre3IdentityProvisioning.kt`, `Libre3Persistence.kt`, settings UI), on the xDrip4iOS `Libre3IdentityStore` pattern:
  - an identity set is an immutable version pinned by SHA-256;
  - a source is `version|sha256|https://mirror …`;
  - any mirror may serve it, but only bytes matching the digest are parsed;
  - sets are sealed under the device Keystore and work offline;
  - reinstalling a version id with other content is refused;
  - each sensor stores `<version>/<label>` and tries its pinned set first;
  - a file import becomes version `import-<sha12>`;
  - turning the driver on downloads from the effective source if nothing is installed.
- **Source:** none is shipped. A build sets one with `-Plibre3IdentitySource='version|sha256|https://…'` (BuildConfig `LIBRE3_IDENTITY_SOURCE`). A user can enter a URL + SHA-256 in settings, which takes precedence, or import a file.
- **xDrip4iOS** (read 2026-11-08, `Libre3IdentityStore.swift`) pins `version: "app-identities-1"` with a compiled-in digest and two Arweave gateways (`arweave.net`, `turbo-gateway.com`).
- **Open decision:** whether this project points its builds at that bundle. It is extracted Abbott app key material; the digest proves integrity, not the right to distribute it. Wiring it in is a one-line Gradle property.
- **Other open decisions:**
  - which certificate versions each sensor family accepts (patch-info `securityVersion` is logged but unused);
  - whether to auto-download at NFC scan time (today the scan is refused with a message if no set is installed).

## 8. Next investigations and how to reproduce

1. Device test with a disposable sensor and logcat tags `Libre3BLE`, `Libre3Nfc`, `Libre3Registry`. Capture an HCI snoop log of the first full handshake.
2. Decide the identity source (section 7) and run a device test with it.
3. Make `importHistory` report store failure separately from duplicates before trusting the marks.
4. Verify the backfill completion signal and the clinical timestamp alignment against a trace.
5. Decide whether watch takeover is wanted, and how identities reach the watch.

Build setup used here (no Android SDK was installed by default):
```
# SDK: cmdline-tools → sdkmanager "platforms;android-37.1" "build-tools;37.0.0"; local.properties: sdk.dir=/opt/android-sdk
# Maven Central returned 429: a local ~/.gradle/init.d script redirected it to
# https://maven-central.storage-download.googleapis.com/maven2/ and dropped the plugin portal (not committed).
./gradlew --no-configuration-cache :Common:compileMobileDebugKotlin
./gradlew --no-configuration-cache :Common:compileWearDebugKotlin
./gradlew --no-configuration-cache :Common:testMobileDebugUnitTest --tests "tk.glucodata.drivers.libre3.*" --tests "tk.glucodata.ConnectModeLeverTests" --tests "tk.glucodata.SensorVendorTests"
python3 scripts/ui-guardrails.py
```
