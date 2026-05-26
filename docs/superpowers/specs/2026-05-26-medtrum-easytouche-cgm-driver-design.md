# Medtrum EasyTouch CGM — Driver Design for JugglucoNG

**Date:** 2026-05-26  
**Status:** Approved — pending implementation  
**Branches:** JugglucoNG `primary`  
**Source for RE:** `Medtrum EasyTouch mmol_L_1.4.67_APKPure.apk`, `Medtrum EasySense mmol_L_1.4.79_APKPure.apk`  
**Reference implementation:** AndroidAPS `pump/medtrum/` module

---

## Context

The Medtrum EasyTouch is an AIO (pump + CGM) disposable patch. The EasySense is a CGM-only patch. Both communicate over BLE using the same proprietary Medtrum protocol.

AndroidAPS already has a fully working BLE driver for the EasyTouch (pump control), but **intentionally ignores CGM data** — the notification mask `0x1000` is received but skipped with a "not handled" log message.

The goal is to implement a standalone CGM driver in JugglucoNG that reads glucose from EasyTouch/EasySense patches, following the `ManagedBluetoothSensorDriver` / `MQBleManager` driver pattern.

**APK protection note:** Both APKs are protected with 360 Jiagu (`assets/libjiagu*.so`). Static Java decompilation yields only stub classes. Protocol details are reconstructed from AndroidAPS source code and native library string analysis.

---

## BLE Protocol Reference

### Device identification (BLE advertising)
```
Manufacturer ID: 0x4781 (18305)
Manufacturer data (6 bytes, little-endian):
  [0-3]: Device Serial Number (uint32 LE)  ← user enters this in settings
  [4]:   Device Type (uint8)
  [5]:   Protocol Version (uint8)
```

Scan filter: `manufacturerId == 0x4781` AND advertised SN matches user-configured SN.

### GATT service layout
```
Service UUID:  669A9001-0008-968F-E311-6050405558B3
  Char NOTIFY: 669a9120-0008-968f-e311-6050405558b3  (READ + NOTIFY)
  Char WRITE:  669a9101-0008-968f-e311-6050405558b3  (WRITE_NO_RESPONSE)
  CCCD:        00002902-0000-1000-8000-00805f9b34fb   (standard)
```

### Packet framing

**Outgoing (Write char):**
```
[0]     sequence number (uint8, wraps at 255)
[1]     opCode (see command table below)
[2..n]  command payload
[n+1]   CRC low  } CRC-16 Modbus over bytes [0..n]
[n+2]   CRC high }
```
MTU is at most 20 bytes per BLE frame. Longer commands are split; the pump reassembles by sequence number.

**Incoming (Notify char):**
```
[0]     sequence number
[1]     opCode (echo of command opCode, or 0x00 for async notifications)
[2-3]   unknown flags (uint16 LE)
[4-5]   result code (uint16 LE)
          0x0000 = success
          0x4000 = waiting (command still executing, more responses coming)
          other  = error
[6..n]  response payload
```

### Command table (opCodes)
| Code | Name         | Direction | Notes |
|------|--------------|-----------|-------|
| 0x03 | SYNCHRONIZE  | →         | Get current pump+CGM state |
| 0x04 | SUBSCRIBE    | →         | Subscribe to notification fields |
| 0x05 | AUTH_REQ     | →         | Authenticate session |
| 0x06 | GET_DEV_TYPE | →         | Device type + firmware version |
| 0x0A | SET_TIME     | →         | Sync RTC |
| 0x63 | GET_RECORD   | →         | Fetch historical record by index |
| 0x73 | CLEAR_ALARM  | →         | Acknowledge alarm |

### Authentication (AUTH_REQ, opCode=0x05)

**Request payload:**
```
[0]    role (uint8)
         2 = pump controller (used by AndroidAPS)
         TBD = CGM-only reader  ← must confirm via BLE sniff
[1-4]  sessionToken (4 random bytes, chosen by client)
[5-8]  key = Crypt.keyGen(serialNumber)
```

**keyGen algorithm** (ported from AndroidAPS `Crypt.kt`):
- Rijndael S-box substitution on the 4-byte serial number
- See `MedtrumCrypt.kt` for the full port

**Response payload (at [6..]):**
```
[6]    deviceType (uint8)
[7]    swVersionX
[8]    swVersionY
[9]    swVersionZ
```

### SUBSCRIBE (opCode=0x04)

**Request payload:**
```
[0-1]  subscriptionMask (uint16 LE)
```

For CGM-only: `mask = 0x1000`  
For all fields (pump+CGM): `mask = 0x1FFF`

**Subscription mask bits:**
```
0x0001  SUSPEND
0x0002  NORMAL_BOLUS
0x0004  EXTENDED_BOLUS
0x0008  BASAL
0x0010  SETUP
0x0020  RESERVOIR
0x0040  START_TIME
0x0080  BATTERY
0x0100  STORAGE
0x0200  ALARM
0x0400  AGE
0x0800  MAGNETO_PLACE
0x1000  CGM ← the glucose channel
0x2000  COMMAND_CONFIRM
0x4000  AUTO_STATUS
0x8000  LEGACY
```

### Async notification format

Notifications arrive unsolicited on the Notify characteristic:

```
[0]     device state (enum, see MedtrumPumpState)
[1-2]   fieldMask (uint16 LE) — bitmask of fields present in this notification
[3..]   concatenated field payloads, in ascending mask-bit order
```

Field sizes (bytes) per mask bit:
```
SUSPEND           0x0001  4
NORMAL_BOLUS      0x0002  3
EXTENDED_BOLUS    0x0004  3
BASAL             0x0008  12
SETUP             0x0010  1
RESERVOIR         0x0020  2
START_TIME        0x0040  4
BATTERY           0x0080  3
STORAGE           0x0100  4
ALARM             0x0200  4
AGE               0x0400  4
MAGNETO_PLACE     0x0800  2
CGM               0x1000  5  ← glucose field
COMMAND_CONFIRM   0x2000  2
AUTO_STATUS       0x4000  2
LEGACY            0x8000  2
```

---

## CGM Field Format (⚠️ HYPOTHESIS — requires BLE HCI validation)

The 5-byte CGM field (mask 0x1000) format is **not confirmed**. Best-guess based on similar CGM sensors and field size:

```
[0]     status flags (uint8)
          bit 0: sensor valid
          bit 1: warming up (new sensor, ~60 min warmup)
          bit 2: sensor error / out of range
          bit 3-7: TBD
[1-2]   glucose value (uint16 LE)
          Units TBD: mg/dL? or mmol×100? or mmol×10?
          Likely mg/dL (standard for internal representation)
[3-4]   secondary data (uint16 LE)
          TBD: trend rate (mg/dL/min ×10)? raw ADC? time offset?
```

**To confirm:** Enable BLE HCI Snoop Log on Android (Developer Options → Enable Bluetooth HCI Snoop Log), run EasyTouch app, pull `/data/misc/bluetooth/logs/btsnoop_hci.log`, open in Wireshark, filter by `btle`, find ATT notification packets on handle `669a9120-...`.

### Glucose prediction (libmdkjnidemo.so)

The EasyTouch app uses a native glucose prediction filter via JNI:
- `com.example.shengk.jni008.JniUtil.predictGlucose(float[] rawSamples)` → `float predictedGlucose`
- `com.example.shengk.jni008.JniUtil.glucosePredictReset()`

The native functions (`glucose_predict_task`, `glucose_predict_get_predict`) implement a matrix-based Kalman-like filter. The JugglucoNG driver **does not need to replicate this** — JugglucoNG has its own smoothing pipeline. Pass the raw value from the 5-byte field into Natives.

---

## JugglucoNG Driver Architecture

### File layout
```
Common/src/main/java/tk/glucodata/drivers/medtrum/
├── MedtrumDriver.kt              interface: ManagedBluetoothSensorDriver
├── MedtrumBleManager.kt          BLE GATT + state machine + command queue
├── MedtrumProtocol.kt            packet builders + notification parser
├── MedtrumCrypt.kt               keyGen port from AndroidAPS Crypt.kt
├── MedtrumIdentityAdapter.kt     ManagedSensorIdentityAdapter impl
└── MedtrumPersistence.kt         SharedPreferences wrapper
```

### MedtrumDriver.kt (interface)
Extends `ManagedBluetoothSensorDriver`. Key overrides:
```kotlin
override fun canConnectWithoutDataptr(): Boolean = true
override fun managesLiveRoomStorage(): Boolean = true
override fun hasNativeSensorBacking(): Boolean = false
override fun shouldUseNativeHistorySync(): Boolean = false
```

### MedtrumBleManager.kt (main BLE manager)
Extends `SuperGattCallback`, implements `MedtrumDriver`.

State machine phases:
```
IDLE → SCANNING → CONNECTING → DISCOVERING → AUTH → SUBSCRIBING → STREAMING
```

Key callbacks:
```kotlin
onConnectionStateChange(CONNECTED) → discoverServices()
onServicesDiscovered()             → enableNotify(NOTIFY_UUID) → sendAuth()
onDescriptorWrite()                → sendSubscribe(mask = 0x1000)
onCharacteristicChanged(NOTIFY)    → MedtrumProtocol.parseNotification(data)
```

Watchdog: if no CGM notification for 10 minutes, disconnect + reconnect.

### MedtrumProtocol.kt
```kotlin
object MedtrumProtocol {
    fun buildAuthRequest(serialNumber: Long, sessionToken: ByteArray): ByteArray
    fun buildSubscribeRequest(mask: Int = 0x1000): ByteArray
    fun parseNotification(data: ByteArray): MedtrumNotification
    fun parseCgmField(data: ByteArray, offset: Int): MedtrumCgmReading
}

data class MedtrumCgmReading(
    val statusFlags: Int,
    val glucoseRaw: Int,     // TODO: confirm units (mg/dL assumed)
    val secondaryData: Int,  // TODO: confirm meaning (trend? ADC?)
    val isValid: Boolean = (statusFlags and 0x01) != 0,
    val isWarmingUp: Boolean = (statusFlags and 0x02) != 0,
)
```

### MedtrumCrypt.kt
Direct Kotlin port of AndroidAPS `pump/medtrum/src/.../encryption/Crypt.kt`:
- Rijndael S-box lookup table
- `fun keyGen(serialNumber: Long): Long`

### MedtrumIdentityAdapter.kt
```kotlin
object MedtrumIdentityAdapter : ManagedSensorIdentityAdapter {
    override fun hasPersistedManagedRecord(sensorId: String): Boolean
    override fun createManagedCallback(context, sensorId, dataptr): SuperGattCallback?
    override fun persistedSensorIds(context): List<String>
    // ...
}
```

Register in `ManagedSensorIdentityRegistry.all`.

### SensorSourceResolver changes
```java
public static final int SENSOR_KIND_MEDTRUM = 0x50;
// Add to resolveSensorKind, resolveXdripSourceInfo, sourceForKind
```

### Glucose storage
```kotlin
// In parseCgmField handler, after validation:
val glucoseMgDl = reading.glucoseRaw  // TODO: unit conversion if not mg/dL
Natives.addGlucoseEntry(dataptr, System.currentTimeMillis(), glucoseMgDl, reading.secondaryData.toFloat(), reading.statusFlags)
```

---

## Error handling

| Condition | Action |
|-----------|--------|
| Auth fails (wrong SN) | Show error, stop scanning |
| CGM warming up (bit 1) | Store `SENSOR_WARMING_UP` state, no glucose entry |
| CGM error (bit 2) | Log warning, no glucose entry, alert after 3 consecutive errors |
| No notification for 10 min | Disconnect + reconnect |
| GATT error | Exponential backoff reconnect (1s, 2s, 4s, max 60s) |

---

## Critical unknowns (TODO before merge)

1. **Auth role byte**: Is role=2 accepted by the CGM-only EasySense? Or is there a role=1 for CGM readers? → Confirm via BLE sniff on EasySense app.

2. **CGM field units**: mg/dL, mmol×10, or mmol×100? → Confirm via BLE sniff: compare raw uint16 value against displayed mmol/L reading in EasyTouch app.

3. **CGM field bit layout**: Is byte 0 status flags? Or could it be different ordering? → Confirm via BLE sniff.

4. **Subscribe mask**: Does role=CGM need SUBSCRIBE at all, or do notifications arrive automatically after AUTH? → Confirm.

5. **EasySense vs EasyTouch protocol**: Both use the same Medtrum BLE service UUIDs (confirmed from APK listing). Auth role may differ.

---

## BLE HCI Sniff guide

1. Android phone with EasyTouch app installed + active sensor
2. Developer Options → Enable Bluetooth HCI Snoop Log (on)
3. Run EasyTouch app, wait for one glucose reading
4. `adb pull /data/misc/bluetooth/logs/btsnoop_hci.log`
5. Open in Wireshark: filter `btatt && btatt.opcode == 0x1b` (ATT Handle Value Notification)
6. Find notifications on handle for `669a9120-...`
7. Locate bytes where the 16-bit field mask contains `0x10 0x10` (fieldMask bit 12 set)
8. Extract the 5 bytes after that position

---

## Testing plan

1. **Unit tests**: `MedtrumProtocol` parsing with known byte sequences (mock data)
2. **Integration test**: Connect to EasyTouch with modified AndroidAPS (add CGM logging to `handleUnusedCGM`) → capture raw 5 bytes → validate against displayed reading
3. **JugglucoNG integration**: Install on phone with active sensor → confirm glucose appears in JugglucoNG graph

---

## Files to create

| File | Action |
|------|--------|
| `drivers/medtrum/MedtrumDriver.kt` | Create |
| `drivers/medtrum/MedtrumBleManager.kt` | Create |
| `drivers/medtrum/MedtrumProtocol.kt` | Create |
| `drivers/medtrum/MedtrumCrypt.kt` | Create (port from AndroidAPS) |
| `drivers/medtrum/MedtrumIdentityAdapter.kt` | Create |
| `drivers/medtrum/MedtrumPersistence.kt` | Create |
| `drivers/ManagedSensorIdentityRegistry.kt` | Modify: add `MedtrumIdentityAdapter` |
| `SensorSourceResolver.java` | Modify: add `SENSOR_KIND_MEDTRUM = 0x50` |
