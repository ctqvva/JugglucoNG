# Bioecare (`BECGM`) CGM — BLE wire protocol notes

Protocol notes for `Common/src/main/java/tk/glucodata/drivers/bioe/`, derived from the vendor
Android application `com.bioecare.cgm` (`apks/bioecare/base.apk`, JADX output in
`/tmp/bioecare_cgm_jadx`, smali in `/tmp/bioecare_cgm_apktool`). No live hardware capture exists
yet, so every claim below is static RE; byte offsets were cross-checked against smali where JADX
had skipped a method.

## 1. Transport

* The app discovers GATT characteristics by properties, not by fixed UUID: the first characteristic
  with `WRITE`/`WRITE_NO_RESPONSE` is the command channel, the first with `NOTIFY` carries replies.
* Fallback used by the vendor: service `0000ffe1-0000-1000-8000-00805f9b34fb`, notify
  characteristic `0000ffe3-0000-1000-8000-00805f9b34fb`, standard CCCD `00002902-…`.
* No bonding, challenge or encryption is part of the CGM exchange (`parseAuthenticate()` → `true`).
* Advertised device names begin with `BECGM`; this is the only reliable scan filter available.

## 2. Framing

Both directions start with `8A A8`. There is no length, CRC or OTP field.

```
TX: 8A A8 <cmd> <payload…>
RX: 8A A8 <cmd> <payload…>     (cmd at byte 2)
```

Examples built by the vendor (`BleMessage.msgCreator` prepends the preamble):

```
8A A8 02 00 00             GET_DATA
8A A8 04 00 02             GET_LOCAL_DATA, package 2 (big-endian u16)
8A A8 09 02 <lo> <hi>      INQ_FLASH_DATA, start index little-endian u16
8A A8 0A 04 <lo> <hi> <lo> <hi>   GET_FLASH_DATA, offset/count little-endian u16
```

A one-byte `FF` notification is a device heartbeat; it carries no payload.

Commands (decimal): `2` GET_DATA, `3` INQ_LOCAL_DATA, `4` GET_LOCAL_DATA, `5` CLEAR_HIS,
`6` GET_VER, `7` GET_SYSPARA, `9` INQ_FLASH_DATA, `10` GET_FLASH_DATA, `11` CLEAR_FLASH,
`12` GET_CGMPARA, `0xFF` heartbeat.

## 3. Reply payloads

Offsets are relative to the beginning of the notification, including the two preamble bytes.

| cmd | offset | field |
| --- | --- | --- |
| `2` GET_DATA | `4..5` | ADC, big-endian u16 |
| | `6..7`, `8..9`, `10..11` | reference / battery / NTC raw words, big-endian |
| `3` INQ_LOCAL_DATA | `4` | `hasPackage` (1 = history available) |
| | `5..6`, `7..8` | total sample count, package count (big-endian u16) |
| `4` GET_LOCAL_DATA | `3..4` | package index, big-endian u16 (1-based; 100 samples per package) |
| | `5..6` | valid sample count in this package, big-endian u16 |
| | `7..` | ADC words, big-endian u16 |
| `6` GET_VER | `6..8` | combined hardware/firmware version triplet |
| | `9` | manufacturer code |
| `7` GET_SYSPARA | `3`, `4`, `5`, `6`, `7` | sample period, data type, work mode, AFE out, data filter |
| `9` INQ_FLASH_DATA | `4..5` | latest stored index, big-endian u16 |
| | `6..7` | stored sample count, big-endian u16 |
| `10` GET_FLASH_DATA | `3` | payload length |
| | `4..5` | start index, little-endian u16 |
| | `6..7` | sample count, little-endian u16 |
| | `8..` | ADC words, big-endian u16 |

ADC words are capped at `0x0FFF` (4095) in the flash path. In the history path an out-of-range
word is replaced by the previous sample's ADC, or by `776` for the first sample.

Live frames have no timestamp: the vendor stamps them with local time. History timestamps come from
`historyEndTimeStamp - index * 60_000`, flash timestamps from the known start time plus
`index * 60_000`. Missing windows are requested in chunks of at most 100 samples.

## 4. Glucose conversion

```
adcNa  = ((adc * 1.8 / 4096 - 0.34) * 1000 / 30) * 100     // nA × 100
mmol/L = adcNa / 100 * k - b
```

`k` (`convNum`) and `b` (`verifyVal`) are per-sensor coefficients returned by the server API below.
The application's fallback constants `0.03989725` / `-33.257442` are not usable for arbitrary
sensors. Manual entry remains available as a fallback; both paths store the coefficients as decimal
strings to keep full precision. The vendor also gates low electrode current on a per-sensor
threshold (`lowCurrentThresholdNa`), forcing the reading to zero below it; the server value is now
stored and applied by `BioeParser.glucoseMmol`, with 0 nA as the manual-entry default.

The vendor rounds the formula to two decimals with a `DecimalFormat` before display. This driver
keeps the full double precision result and rounds only at presentation.

## 5. Server parameter API

`BioeApi` (`Common/src/main/java/tk/glucodata/drivers/bioe/BioeApi.kt`) fetches the current sensor's
coefficients with the same request shape as the vendor app:

```
POST https://cgm.bioecare.com/api/device/using/list
Content-Type: application/json

{"token":"<user token>","sign":"<md5>","nowTimestamp":<ms>}
```

* The token is entered by the user in `BioeSetupWizard` (masked field) and is stored in the BIOE
  preferences so it can be reused; no token is compiled into the app. The two constant salts are
  protocol constants recovered from `Settings.getSign()` in the APK:
  `sign = md5("mlhdCI6MTUx" + nowTimestamp + "FmyG3nMiGM6H9FNFUR" + "bioecare_app_release")`,
  lower-case hex. The middle value is an app secret, **not** the user token.
* Headers mirror `HttpLogInterceptor`: `djs-api-token`, `djs-client-type: android`, `djs-timestamp`,
  `djs-client-version-no`, `djs-request-id` (md5 of timestamp + random UUID), `djs-client-language`,
  `djs-product-name: scgm`, `djs-app-version: 1.4.80(195)`.
* The vendor disables TLS verification; this client does **not** — normal `HttpURLConnection`
  certificate validation applies. Connect/read timeouts are 15 s / 20 s.
* A JSON-level `code != 0` (or `result == false`) is a protocol error; the UI shows a generic
  failure with the HTTP code and keeps manual entry usable. Network exceptions become transport
  errors. No response body or token is written to logs.

The reply is `ResponModel<DeviceListResponBean>`: `data` (or the legacy alias `source`) contains
`list[]`. Each entry supplies `deviceCode`, `convNum`, `verifyVal`, `lowCurrentThresholdNa`, `useAt`,
`btName`, `btMac`, `sensorLifetimeDays`, `algorithmStartMinutes`, `quickInitCount` and
`quickInitInterval`. `BioeApi.selectDevice` prefers the entry matching the entered device code and
otherwise the first entry with usable coefficients; the wizard fills k/b/threshold/useAt and saving
passes `useAtMs` into `BioeRegistry.addSensor`. Numeric fields are kept as their original decimal
text until validation, so a float's full precision survives the round trip.

## 6. Still unverified

* Physical framing of notifications: the app registers one notify callback and strips `8A A8`
  before dispatch; the driver assumes the raw ATT value includes the preamble.
* Meaning and scaling of the three auxiliary words in the realtime reply.
* Whether all sensor families use `BECGM` names and the same fallback GATT UUIDs.
* Whether the server's float-typed `convNum`/`verifyVal` values are already rounded by the vendor
  backend (they arrive as JSON numbers; this client preserves the exact decimal text).
* Whether `/api/device/using/list` rejects a `djs-app-version` other than the vendor's, and whether
  `djs-request-id` needs the vendor's exact `RandomStr` alphabet — read from static RE only.
* Automatic missing-window detection is still driven by the last persisted flash index only.
