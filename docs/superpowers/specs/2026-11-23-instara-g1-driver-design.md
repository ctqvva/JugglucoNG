# Instara G1 — managed BLE driver for JugglucoNG

Date: 2026-11-23
Status: design, approved scope — implementation pending

## Intent and why

The user wants JugglucoNG to read a **Instara G1** continuous glucose sensor directly over BLE,
replacing the vendor app (`com.teljane.instara`, v1.0.2) as the receiver. The vendor app was pulled
from a connected device over adb and decompiled; its protocol is now understood well enough to
implement a managed driver. No physical G1 sensor is available yet, so hardware validation is
deferred and the driver must be verifiable offline with unit tests.

Full scope requested: automatic reconnect, live glucose stream, history download, battery and
device status. Implementation follows the established managed-driver pattern used by
`sibionics`, `aidex` and `medtrum`.

## Goals

- Add a self-contained managed BLE driver under `tk.glucodata.drivers.instara`.
- Discover G1 by advertised service UUID `8653000A-43E6-47B7-9CB0-5FC21D4AE340`.
- Perform the vendor handshake (device version notify → ACK → SN auth → use-time), then:
  - poll/subscribe realtime glucose (`0x01` query / `0x10` push),
  - download history (`0x03` sequence id → `0x30` payload frames),
  - read battery (`0x02`/`0x20`), device status (`0x06`/`0x60`), duration (`0x0A`), temperature (`0x08`/`0x70`).
- Publish glucose through the standard app funnel: `LiveReadingLanes.stock(...)` →
  `SuperGattCallback.processExternalCurrentReading(...)` → `HistorySyncAccess.storeCurrentReadingAsync`
  → `VirtualGlucoseSensorBridge.mirrorToNative(...)`.
- Persist SN + last BLE address; restore the driver on app start through the identity registry.
- Add a setup wizard so the user can enter the SN and add the sensor.
- Unit-test the protocol codec, parser, state machine and persistence parsing without hardware.

## Non-goals

- No vendor cloud/account API, no login, no Nightscout/xDrip-specific code (the standard outbound
  funnel already covers those).
- No vendor calibration algorithm: the device already returns a finished glucose value; the driver
  uses the stock lane so downstream calibration is applied once. The complex `0x04` function-model
  command (15 float parameters) is **not** implemented in v1.
- No firmware update, reset or clear-history flows in v1 (stubs may exist but are not wired to UI).
- No new native/JNI code. Family code `9` fits the existing 4-bit native field.

## Anti-goals (what would make this a failure)

- Claiming the driver works on real hardware without a capture — it cannot be validated here.
- Hard-coding an unconfirmed unit. The parser returns `u16 / 10.0`; whether that is mmol/L or
  mg/dL is inferred as **mmol/L** from the vendor's `BloodUnitUtils` (factor 18.0) but must be
  confirmed on hardware. The driver keeps the raw `u16/10` value in one place (`InstaraProtocol`)
  so the conversion is a one-line change after confirmation.
- Sending glucose straight to native storage without the mirror → breaks web server / Clone.

## Constraints and evidence

Source of truth: `com.teljane.instara` 1.0.2, decompiled with jadx to
`~/…/opencode/instara-re/jadx/sources/com/chips/tj/`. Key files:
`core/ble/UUIDConstant.kt`, `CommandType.kt`, `BleSendCommand.kt`, `base/parser/BleDataParser.kt`,
`base/coordinator/BleReceiveCoordinator.kt`, `core/ble/core/BleGattClient.kt`.

### BLE

| Item | Value |
|---|---|
| Service (also advertised) | `8653000A-43E6-47B7-9CB0-5FC21D4AE340` |
| Write characteristic | `8653000C-43E6-47B7-9CB0-5FC21D4AE340` |
| Notify characteristic | `8653000B-43E6-47B7-9CB0-5FC21D4AE340` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` (standard) |

### Frame format

`55 AA | cmd:u8 | len:u8 | payload[len] | crc8:u8`
CRC-8/MAXIM: init `0`, polynomial `0x8C` (reflected `0x1D`), computed over all bytes before CRC.
An existing implementation exists at `drivers/aidex/native/crypto/Crc8Maxim.kt` — reuse or copy
it into a pure-Kotlin protocol object so protocol tests do not depend on Aidex internals.

### Commands (host → device)

| cmd | name | payload |
|---|---|---|
| `0x01` | *(no host builder exists — notification-only opcode)* | — |
| `0x02` | query battery | none |
| `0x03` | send sequence id | u16 BE sequence id (history cursor) |
| `0x04` | send function model | count + floats (v1: not implemented) |
| `0x05` | clear history | — |
| `0x06` | query device status | none |
| `0x07` | send SN | exactly 8 ASCII bytes, `len` hard-coded to `8` by the vendor |
| `0x08` | query temperature | none |
| `0x09` | set duration | u16 BE minutes |
| `0x0A` | query blood duration | none |
| `0x0B` | set use time | 1 byte days |
| `0xF0` | handshake ACK | none |

Every reply is ACKed with `[55 AA, sameCmd, 00, crc]` (`BleSendCommand.getReplyCommand`).

### Notifications (device → host)

| cmd | meaning | payload layout |
|---|---|---|
| `0x01` / `0x10` | realtime glucose | `[4..5]` sequence id u16 BE, `[6..7]` glucose u16 BE ÷ 10 |
| `0x03` | history header | `[4]` number of **frames** (the vendor decrements it once per `0x30` frame, not per reading) |
| `0x30` | history payload | `[3]` index, `[4..5]` u16, `[6..7]` u16, `[8..len-2]` pairs of u16 ÷ 10 |
| `0x02` / `0x20` | battery | `[4]` percent |
| `0x06` / `0x60` | device running status | `[4]` status byte |
| `0x07` | SN auth result | `[4]` 1 = success |
| `0x08` / `0x70` | temperature | raw |
| `0x0A` | blood duration | `[4..5]` u16 |
| `0xF0` | handshake | `[4]` hardware version, `[5]` software version (nibble-coded) |
| `0x40` / `0x50` / `0xFF` | device error / date over / protocol error | — |

### Handshake sequence (from `BleReceiveCoordinator`)

1. device pushes `0xF0` with hw/sw version bytes;
2. host replies `0xF0` ACK;
3. after ~100 ms host sends `0x07` with the SN string;
4. device replies `0x07` status `1` → host sends `0x0B` use-time (days from device info, default reasonable value);
5. realtime query/push and history flow begin. **Without a correct SN the device does not release data.**

## Builder-check resolutions

A fresh builder review raised seven ambiguities. They were resolved from the decompiled artifact or
as bounded builder calls; none need a user decision.

1. **ACK policy (from code).** The vendor ACKs only `0xF0` (handshake), `0x01`/`0x10` (realtime),
   `0x02`/`0x20` (battery), `0x08`/`0x70` (temperature), `0x30` (history payload) and the error
   commands `0x40`/`0x50`/`0xFF`. It does **not** ACK `0x07` (SN result), `0x03` (history header),
   `0x0A` (duration) or the `0x04`/`0x05`/`0x09`/`0x0B` command acks. The driver mirrors this exactly.
2. **No `0x01` query (from code).** `BleSendCommand` has no builder for opcode `0x01`; the app only
   parses `0x01` *notifications* ("from app query" vs "from device push"). The driver does not send
   `0x01` and relies on device-pushed `0x10` notifications.
3. **SN format (from code).** `getSendSnCommand` hard-codes `len=8` and the vendor UI enforces an
   eight-character code. The wizard validates exactly 8 ASCII characters; the frame length is 8.
4. **History header (from code).** The header byte counts `0x30` **frames**, not glucose points;
   completion is "frames received == header count".
5. **Activation time and sampling interval (builder call).** The vendor gets `initTime` and
   `duration` from its cloud `DeviceInfoBean`, which this driver deliberately does not use. The
   wizard therefore takes an optional activation time (default: now) persisted as `sensorStartMs`.
   The sampling interval comes from the `0x0A` reply when it is a plausible number of minutes,
   otherwise a `DEFAULT_INTERVAL_MIN = 5` constant. History points are backdated as
   `activationMs + sequenceId × interval`; live points use arrival time. Both assumptions are
   isolated in one place and listed in the hardware checklist.
6. **Function model `0x04` (builder call + risk).** The 15 float parameters come from the vendor
   cloud and are not present in the APK, so `0x04` is not sent. After SN auth the driver sends
   `0x0B` use-time (days) and `0x09` set-duration (minutes). If a real sensor gates streaming on
   `0x04`, capturing one vendor session is required to add it; this is the largest hardware risk.
7. **Post-processing (builder call).** The vendor runs stored points through a cloud-configured
   analyzer before display. This driver publishes the device's own value straight to the stock lane
   (where this app's calibration is applied once). A small difference from the vendor app is
   expected and acceptable; the raw value is kept unchanged.

## Decisions (calls made by the builder, not user choices)

- Family: add `INSTARA(9)` to `ManagedSensorUiFamily` (`nativeCode = 9`; 0..15 valid, 8 used by MEDTRUM).
- Sensor kind: `SENSOR_KIND_INSTARA = 0x1009` in `SensorSourceResolver`.
- Vendor: add `INSTARA` to `SensorVendor` and its name/badge mappings.
- SN is a **string**, so canonical sensor id = trimmed SN string (unlike Medtrum's decimal Long).
  `isInstaraSensorId` accepts a printable SN of bounded length (to be fixed in code).
- Units: store `glucose = u16 / 10` and treat it as mmol/L in the stock lane; convert with
  `MGDL_PER_MMOL = 18.0182f` if mmol confirmation goes the other way. Isolate in `InstaraProtocol`.
- `SENSOR_GEN` for `SuperGattCallback` is `0`, the same value every managed driver uses — the
  stamped family (not the generation tag) is what distinguishes them.
- No scan filter from `getService()` (return the service UUID so discovery works while the app is
  already running; reconnect goes straight to the stored address, with a scan fallback).
- Watchdog/reconnect/timeouts mirror Medtrum's policy rather than inventing new ones.

## File plan (what the builder will create/modify)

Create under `Common/src/main/java/tk/glucodata/drivers/instara/`:

- `InstaraProtocol.kt` — UUIDs, opcodes, frame build/parse, CRC-8, typed parse results (pure Kotlin, unit-tested).
- `InstaraPersistence.kt` — SN, last BLE address, sensor id, sensor start time.
- `InstaraDriver.kt` — `interface : ManagedBluetoothSensorDriver, ManagedSensorMaintenanceDriver`.
- `InstaraBleManager.kt` — `SuperGattCallback` + `InstaraDriver`: scan match, GATT FSM, notify subscribe,
  write queue, handshake, realtime/history/battery/status parsing, publish path.
- `InstaraRegistry.kt` — `addSensor`, `findDriver`, retire stale drivers.
- `InstaraIdentityAdapter.kt` — `ManagedSensorIdentityAdapter` implementation.

Create under `Common/src/mobile/java/tk/glucodata/ui/setup/`:
- `InstaraSetupWizard.kt` — SN entry and add-sensor flow, modeled on `MedtrumSetupWizard`.

Modify:
- `drivers/ManagedSensorIdentityRegistry.kt` — add adapter to `all`.
- `drivers/ManagedBluetoothSensorDriver.kt` — add `INSTARA(9)`.
- `SensorSourceResolver.java`, `SensorVendor.kt` — kind/vendor/name/badge mappings.
- `res/values/strings.xml` (+ setup strings).
- `ui/components/SensorTypePicker.kt`, `ui/SensorScreen.kt`, `ui/DashboardScreen.kt` — onboarding entry points.
- Tests: `ManagedSensorFamilyTests`, `SensorVendorTests`, plus new `InstaraProtocolTests`, `InstaraParserTests`,
  `InstaraHandshakeTests` under `Common/src/test/java/tk/glucodata/drivers/instara/`.

`SensorBluetooth.java` needs no change (managed callbacks are added via the registry).

## Verification

- `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.drivers.instara.*"`
- `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.drivers.ManagedSensorFamilyTests"`
- `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.SensorVendorTests"`
- Compile debug: `./gradlew :Common:assembleMobileDebug -PjugglucoAbi=arm64-v8a` (if toolchain available).
- Hardware checklist (deferred, requires a real G1 + SN): verify units, SN format, use-time value,
  history frame ordering/ACK policy, notification MTU fragmentation, reconnect behavior.

## What is left to the builder

All of the above is decided; no open questions block implementation. The only external unknown is
hardware validation, which is explicitly out of scope until a sensor is available.

## Implementation deviations (recorded 2026-11-23)

- `InstaraProtocol.Packet.HistoryPayload` uses the vendor's real field names:
  `bloodDataLength` (frame length byte), `startSequenceId`, `duration`, `values`. History readings are
  timestamped from `startSequenceId`, not from a locally guessed cursor.
- `InstaraIdentityAdapter` / `InstaraRegistry` were implemented together with `InstaraBleManager` in
  the transport task, because both reference the manager class and main sources compile as a unit.
- `InstaraBleManager.onConnectionStateChange` calls `noteFirstGattCallback(...)`, required by the
  repository's `ConnectModeLeverTests` (the wear suite caught its absence).
- The setup wizard takes an activation-time string (`yyyy-MM-dd HH:mm`), defaulting to now, and
  persists it as `sensorStartMs`; invalid input falls back to the current time.
- Every managed driver's GATT callbacks cannot be exercised by JVM unit tests; the GATT transport is
  covered by compilation, the protocol/session tests and the repository's own cross-driver tests.
- After the final review, `InstaraActionRouter` / `InstaraPublishSink` were added as a pure seam so a
  JVM test proves that a history reading's computed `timestampMs` reaches storage. History is written
  with `HistorySyncAccess.storeSensorHistoryBatchAsync`; only live `0x10` readings go through
  `processExternalCurrentReading`. The router also deduplicates by sequence id for the driver's life.
- `connectDevice` coalesces only on a live GATT (never on the remembered device address), and
  `onConnectionStateChange` rejects retired GATT handles before delegating to the base class.
- `parse` validates a minimum payload length per opcode before reading any payload byte.
- Follow-up from the decompiled app and review: the driver now sends the `0x03` history cursor
  (`last stored sequenceId`), polls the battery as a keepalive every 25 s after 20 s idle, and uses
  the `0x0A` duration reply as the history sampling interval.
- Greptile review fixes: SN no longer appears in logs; future activation times are clamped; history
  storage dedup is separate from live delivery; pause/rejected-device/Bluetooth-off states stop
  reconnect loops and clear transport state; failed writes restart the link; the native mirror is
  finished on removal and the worker thread is quit; all setup strings are translated.

## Source of truth for units and history timing

`BleReceiveCoordinator.createDeviceDataBean`: `dataTime = initTime + duration * sequenceId * 60_000`.
`BloodUnitUtils.getCorrectValue2`: mmol/L is stored as-is, mg/dL is `source * 18`.

## Hardware validation checklist (requires a real G1 + its 8-character code)

1. **Units — confirmed from the vendor code.** `BleReceiveCoordinator.createDeviceDataBean` stores
   `u16 / 10` unchanged, and `BloodUnitUtils.getCorrectValue2` multiplies by 18 only when mg/dL is
   selected, so the device value is mmol/L. The driver's `MGDL_PER_MMOL` conversion stands; a quick
   real-sensor sanity check is still worthwhile.
2. **SN format** — confirm the printed code is exactly 8 ASCII alphanumerics and that `0x07` always
   carries `len = 8` (hard-coded by the vendor app).
3. **`0x04` function model** — confirm whether streaming/history is gated on the two function-model
   commands the vendor sends after SN auth. Their 15 floats come from the vendor cloud and are not
   in the APK; if gated, capture one vendor session and add the command.
4. **Use time / duration** — confirm `0x0B` days (driver default 14) and `0x09` minutes (driver default
   from a 5-minute interval) match what the sensor expects.
5. **`0x0A` interval** — read the duration reply and use it as the history backdating step instead of
   the 5-minute fallback if it differs.
6. **History framing** — confirm `0x03` frame count, `0x30` `startSequenceId`/`duration` semantics,
   ordering of values, and that the ACK policy (ACK only `0xF0`, `0x01`/`0x10`, `0x02`/`0x20`,
   `0x08`/`0x70`, `0x30`, errors) satisfies the device.
7. **MTU / notification fragmentation** — check whether any packet exceeds one notification and needs
   reassembly; the current parser assumes one complete frame per notification.
8. **Reconnect** — verify the 3-second reconnect and 250 ms discovery delay against real link drops
   and phone sleep.
9. **Post-processing** — compare a few displayed values with the vendor app; this driver publishes the
   device value unchanged to the stock lane, so a small difference from the vendor analyzer is expected.
10. **History request** — confirm whether the host must send a `0x03` cursor command before the device
    pushes `0x30` frames. The vendor parser only handles `0x03` as a response, so this driver waits.
11. **Per-opcode payload lengths** — capture one real session and confirm the minimum lengths used by
    the strict `parse` validation (realtime 4, battery/status/SN/temperature 1, duration 2, handshake 2,
    history 4 + an even value area).
