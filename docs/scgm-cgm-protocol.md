# Bioecare SCGM (`CGMProtocolV2`) — BLE wire protocol notes

Static RE notes for `Common/src/main/java/tk/glucodata/drivers/scgm/`, from the vendor app
`com.bioecare.cgm` 1.4.80 (JADX output at `/tmp/bioecare_cgm_jadx`). No live hardware capture was
used; every byte offset below was read from `CGMBluetoothPDU`, `CGMProtocolV2`,
`CGMBluetoothController`, `CGMDataProcessor` and `LinearRegression`, not guessed from a trace.
This is a different wire protocol from `BECGM` (`docs/bioe-cgm-protocol.md`), even though both
families ship in the same app and share the parameter API.

## 1. Device discovery

* `CGMBluetoothScanner.BLE_DEVICE_NAMES = {"SCGM", "BECGM"}`. The vendor's `CGMBluetoothType`
  maps `BECGM`→BIOE, `DN-`→NOVO, everything else→SCGM. A `CGMBleDevice` with an `SCGM` name has
  `type = 0`; its `deviceCode` is the lower-case hex of the whole manufacturer-specific AD payload
  (AD type `0xFF`). For `BECGM` the device code is the advertised name instead.
* The driver matches only the `SCGM` prefix (plus an already-connected address) so the `BECGM`
  family stays with `drivers/bioe/`. The advertised hex code is read from `ScanResult`.
* GATT characteristics are discovered by properties: command = first with `WRITE` or
  `WRITE_NO_RESPONSE`, replies = first with `NOTIFY`. System services `1800`/`1801` are skipped.
  Vendor fallback: service `0000ffe1-…`, notify characteristic `0000ffe3-…`; standard CCCD
  `00002902-…` is written through the descriptor, no proprietary indicator is used.
* Requested MTU is 220; the write queue splits a frame into 51-byte chunks. Frames here are short
  (≤ 20 bytes), so the split is a transport guard rather than a normal path.
* No bonding, challenge or encryption: `parseAuthenticate()` returns `true`. Command `17`
  (auth: random BE16 + 13-byte key) exists in the PDU builder but is never sent in this flow.
* Replies are correlated by the command byte (`data[2]` of the raw notification).

## 2. Framing

`CGMBluetoothPDU.pduCreator(msg)` builds `msg = [cmd, payload…]` into:

```
TX: 5A A5 <cmd> <len = msg.length - 1> <payload…> 00 00 00 00
RX: 5A A5 <cmd> <len> <payload…>
```

`pduReader(data)` validates `0x5AA5` and `data.length >= len + 4`, then returns `[cmd][len][payload…]`
(no preamble, no trailer). The driver parser accepts both shapes. All multi-byte fields are
**little-endian**. Several frames in one notification are not supported by the vendor.

Example commands (raw TX, including the four trailer bytes):

```
5A A5 05 01 01 00 00 00 00   ACTIVATION
5A A5 10 00 00 00 00 00      TERMINAL INFO
5A A5 01 00 00 00 00 00      CURRENT RESULT
5A A5 02 02 00 00 00 00 00 00 00   QUERY HISTORY
5A A5 03 04 <off lo> <off hi> <count lo> <count hi> 00 00 00 00   HISTORY PAGE
```

Commands (decimal): `0` heartbeat (unix seconds LE32, unused here), `1` current result,
`2` query history, `3` history page, `4` clear history (unused), `5` activation (`01`),
`16` terminal info, `17` auth (unused). Session start: `05 01` → `16` → `01`.

## 3. Reply payloads

Offsets are relative to the raw notification (`5A A5 cmd len …`).

| cmd | offset | field |
| --- | --- | --- |
| `1` | `4..5`, `6..7`, `8..9`, `10..11` | sample index, ADC, battery mV, NTC; all LE u16 |
| `2` | `4..5`, `6..7` | `latestIndex`, `dataCount`; LE u16 |
| `3` | `4..5`, `6..7`, `8..` | page start index, sample count, ADC words (LE u16) |
| `16` | `4..19`, `20..23` | 16-byte device code, 4-byte firmware version |
| | `24..25`, `26..27`, `28..29` | run time (min), bias voltage (mV), package count; LE u16 |
| `0` | `4` | heartbeat status |

`index == 0` in cmd `1` is discarded (initialization not finished). Battery at or below `1900 mV`
is a low-power condition. The 16-byte terminal-info code is ASCII on known devices; the parser
falls back to lower-case hex for non-printable data.

Vendor bug that is part of the wire contract: `setDeviceInfo(runTime, biasVoltage)` is called with
`packageCount` as its first argument, so `bootAt = now - packageCount * 60` seconds. The driver
persists that derived boot time rather than the (unused) `runTime` word.

## 4. Glucose conversion

With `param1 = 1.0`, `param2 = 0.0` (the SCGM defaults in `CGMDataProcessor`):

```
electricityNa = round2(adc)                 // adc is already nA × 100
mmol/L        = round2(electricityNa / 100 * k - b)
```

`k` (`convNum`) and `b` (`verifyVal`) come from the same Bioecare server API as the `BECGM` family
and are reused through `BioeApi` / `BioeCalibration`; the wizard accepts manual values as a
fallback. `LinearRegression` uses `DecimalFormat("#.##")`, hence the two-decimal rounding in the
parser. No smoothing or drift compensation is applied on this path. The app's low-current check is
`0 < electricityNa <= 1900` for the *battery/auxiliary* signal, not a glucose gate; the shared
per-sensor `lowCurrentThresholdNa` is only an optional guard in this driver: the vendor's
`isLowPower` check applies to battery voltage (`0 < batteryMv <= 1900`).

## 5. Timestamps and history backfill

* Live replies have no timestamp: the driver stamps them with `System.currentTimeMillis()`.
* History sample `i` uses the last realtime sample as the anchor:
  `ts(i) = minuteBucket(lastTimeMs) - max(lastPid - i + 1, 1) * 60_000 + (lastTimeMs % 60_000)`.
  When no realtime sample exists yet, `lastPid = (now/1000 - bootAt) / 60`.
* A realtime index jumping ahead of the stored `lastPid` triggers cmd `2`. The vendor computes
  `shouldRead = dataCount - (nextBrokePosition - latestIndex)` where `nextBrokePosition` is the
  offset of the first missing sample, clamped up to `latestIndex`. Pages of at most 100 samples
  are then requested with cmd `3` from the first missing index. Missing minutes are interpolated
  by the app's existing `DataSupplement` path (not reimplemented in the driver).

## 6. Still unverified

* Whether the four trailer bytes on TX must be exactly zero (the vendor allocates a zeroed buffer).
* Whether SCGM devices ever advertise a manufacturer payload whose hex is not the sensor code.
* Physical response timing and the exact meaning of the firmware word / bias voltage.
* Whether several cmd `3` pages may arrive reordered: the driver assumes in-order notifications.
