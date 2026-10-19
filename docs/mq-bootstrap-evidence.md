# MQ first-reference initialization, 2026-09-13

Evidence source: the locally extracted official Glutec APK at
`/Users/ctqwa/RE/29m-dump/mq_re/extracted/base.apk`, SHA-256
`de3c497d03d137fb42ac16460ad3a92b4cc96453c719aff48d4b7a2a3e8d5faf`.

`RuGlutec_Begin_Wearing07.intothedatabase2()` reads raw QR sensitivity
from `spare02` and the transmitter scale flag from `spare05`. When that flag
is `"1"`, it multiplies sensitivity by ten and truncates to one decimal place
(`BigDecimal.setScale(1, 1)`, where rounding mode 1 is DOWN). The BLE lookup
supplies `transmitter10`; the QR lookup alone cannot establish the scale.
Both QR bootstrap and continued-wear bootstrap must apply this conversion.

The first-reference call in
`RuGlutec_BloodGlucoseData_Service.onCharacteristicChanged()` uses these
arguments for `RuGlutec_GlucoseCalculationArrayNew.GlucoseCalculation` after
packet 18, when sensitivity is present and no reference has been established:

```
[1, packet, sampleCurrent, 0, previousReviseCurrent2, sensitivity, 0, 2, 0, packages, multiplier]
```

The first argument is the constant 1, not elapsed session minutes. The service
uses output index 7 as the initial reference. Passing elapsed time at startup
enables the calculator's smoothing against an empty previous sample. The
2026-09-13 trace reproduces that defect at packet 24: current 175 becomes
processed current 3, produces reference 17 (mmol/L times ten), then solves K
as 101.76. Subsequent samples remain near the calculator's lower bound.

`MQBootstrapSeedTests` checks the recovered initialization arguments and a
fixture with an explicitly selected 10x configuration: raw sensitivity 2.93
becomes 29.3; current 175 minus offset 2 gives processed current 173, reference
59, and solved K 29.32. This is source-derived calculation evidence, not a
paired official-app/device glucose measurement. The trace does not establish
the transmitter scale by itself, so production code requires BLE metadata.

The service method exceeds JADX's structured decompilation limits. To inspect
its instruction dump, use `jadx --single-class
com.ruapp.glutec.RuGlutec_BloodGlucoseData_Service --single-class-output
<output.java> --show-bad-code --comments-level debug <base.apk>`. Inspect the
initial-reference calls, not the separate later recalibration call.

Previously persisted K/B values and historical readings have no reliable
provenance identifying this startup bug. They are not rewritten by this fix.
A fresh local MQ registration and fresh QR/bootstrap lookup exercise the
corrected path; physical transmitter reset is not required for that test.

## Follow-up, 2026-09-14

The user reports the upper clamp (40 mmol/L) after QR bootstrap, followed by
usable readings after manual calibration. The supplied attachment is still
the earlier 1.7 mmol/L trace. Automatic calibration is therefore not considered
device-validated. A new trace must establish raw QR sensitivity, BLE
`transmitter10`, normalized sensitivity and the resulting K/B; the driver now
logs these without account credentials. Do not infer a scale from the displayed
glucose or overwrite the user's current calibration based on it.

Two additional defects were confirmed in source:

- `fetchBestEffortOnce` discarded `fetchContinueWearConfig`'s history by always
  returning an empty list. Valid continued-wear history now reaches its caller;
  reset suppression still prevents the session lookup.
- Both native mirror callers divided `mgdlTimes10` by ten, then the mirror
  divided by ten again. `g.cpp:storeGlucoseStreamSample` expects true mg/dL and
  performs its own internal times-ten conversion. `MQNativeGlucoseMirror`
  now passes `MQAlgorithm.Result.mgdl` directly and converts only milliseconds
  to seconds. Previously stored rows are not guessed or rewritten.

Short-gap BLE replay and cloud snapshot restore are separate mechanisms. No
full-history BLE request command has been verified. Disconnect diagnostics now
include phase, connection duration and protocol-frame age; the older trace's
status 147 alone does not establish the cause of link loss.

## Serial identity and the 03:09 trace

`juggluco-trace-20260914-030941.log` contains packet 237/current 87, QR
sensitivity 2.93, `transmitter10=0`, and calculated 29.0 mmol/L. The shared
log initially prints 522.5 mg/dL, then 26.627777 after the unit setting changes.
These are not different wire units. The start endpoint fails with
`For input string: "FD8EBDDF969"`.

The official app sends the printed transmitter serial (`W25101399`) as `bleId`.
`RuGlutec_Begin_Wearing07.checkPhoneAndBleId` receives the scanned serial
without its four-character label and uses it in `findByBleId`.
`RuGlutec_Begin_Wearing05` and `RuGlutec_BGD_UpData_Service` independently read
`IdentNumber`, strip the UI label, and submit it as `bleId`, with the colon-form
Bluetooth MAC in a separate `mac` field. JugglucoNG instead used its native
MAC identity for both, including metadata lookup and session comparison.
`MQVendorIdentity` separates these identifiers without changing native storage
ownership. Local tests validate the request form. The corrected live server
lookup and resulting calibration still require verification.

Setup also discarded history after successful prefetch by retaining only the
configuration. All setup/restore paths now import the returned history under
the local MAC-based sensor identity. The captured notifications contain only
one record each (237, then 238); they do not contain older history to recover.

When no reliable session start exists, the three-minute packet cadence gives
an estimate: packet 237 at 03:06:53 implies approximately 15:15:53 on September
13. The fallback now uses that estimate and repairs a cached connection-time
start when it contradicts the counter by more than two intervals. This is not
a recovered exact activation timestamp.

The battery byte is raw telemetry, not a percentage. The official service
compares it with 28 and maintains separate `SystemInformation.Battery` display
state (including persistent low-battery logic); it does not display the raw
byte directly. Keep `0x32` intact in parser/cloud payloads, but expose unknown
percentage until a validated conversion is implemented.

An explicit vendor-bootstrap refresh with a valid sensitivity and no restored
K now clears the previous locally solved K/B/processed-current state before
the next sample. Otherwise fixing metadata would leave an old incorrectly
scaled K in use. Ordinary reconnects do not clear calibration or history.

## 03:51 trace verification and cloud recovery

`juggluco-trace-20260914-035139.log` confirms the corrected authenticated
lookup returns `transmitter10=1` and normalizes 2.93 to 29.3. Packets 247–252
produce 3.4–4.2 mmol/L, and reports for 249, 251 and 252 are acknowledged by
the server. This verifies the scaling and upload paths, not accuracy against
a paired glucose reference. The separate authorized metadata probe lacked a
token and returned an authentication error; no credentials were obtained from
the device for that probe.

The captured teardown is an explicit removal (`setPause`, native removal,
`free`) followed by re-add, not an unexplained radio disconnect. BLE packets
continue at approximately three-minute intervals.

The backend returns `code=302, success=true, message=手机号在监测周期中` to
`goOn` while accepting glucose uploads. Treat this endpoint-specific response
as a reason to stop redundant continue attempts; do not classify all 302 or
`success=true` responses as successful actions.

Time-range history returns no usable data in the trace. The request matches
the official app's millisecond start/end parameters. Add the existing snapshot
detail endpoint as a fallback rather than changing units or inventing dates.
Log received/parsed/rejected counts and returned timestamp bounds; malformed
responses must not masquerade as empty history. No claim of recovered cloud
history is supported until the next device trace shows imported records.

Packet 248 also exposes decimal-parameter drift: K 29.41 becomes 29.40 when a
persisted Float is widened to Double before the vendor's decimal truncation.
Recover Float parameters via their decimal string, matching the vendor's
stored decimal representation. A repeated-packet test verifies K remains
29.41 across 100 save/restore/calculate cycles without a new reference.

## September 14 daytime signal excursion: unresolved accuracy failure

`juggluco-trace-20260914-124115.log` extends the earlier capture. The user
reports two other CGMs remained below 6 mmol/L. This is not an official-app
comparison using the MQ transmitter. `Screenshot_20260914-131938.png` shows
the 32.5 mmol/L peak and a later sharp drop which the user identifies as a
manual calibration, not spontaneous recovery.

Times below are Asia/Yekaterinburg (UTC+05:00). Values are the app's calculated
output, **not validated glucose measurements**.

| Time | Packet | Raw current | Processed current | K | Output mmol/L |
| --- | ---: | ---: | ---: | ---: | ---: |
| 08:27:27 | 344 | 109 | 104 | 20.12 | 5.1 |
| 08:30:27 | 345 | 449 | 110 | 20.12 | 5.5 |
| 09:24:28 | 363 | 257 | 255 | 20.12 | 12.7 |
| 11:57:30 | 414 | 655 | 653 | 20.12 | 32.5 |
| 12:39:31 | 428 | 605 | 530 | 20.12 | 26.3 |

Packet 345's record is `40 59 01 C1 01 32`; its unsigned little-endian current
is 449. Packet 344 is `40 58 01 6D 00 32`, current 109. Thus the abrupt change
is already present in received current bytes. There is no coincident K change
or unit switch at this boundary. Earlier manual calibration at 04:38:03
changes K from 29.18 to 20.13, then the old Float truncation path reduces it
to 20.12. That coefficient stays unchanged during the excursion. The decimal
fix does not explain or repair the several-fold rise in raw current.

Arithmetic replay of all 85 logged calculations for packets 344–428 matches
the recorded processed current and mmol output exactly: bound current around
`previousProcessed + B` by 5% plus 0.5, subtract B, divide by K, apply vendor
rounding. At packet 345 the bounded current after subtracting B is 109.8;
at packet 414 it is 653. The smoothing delays the raw rise; it does not
reject a sustained anomalous signal. This replay is a characterization of the
failure, not evidence that these glucose values are correct.

The official APK's `RuGlutec_BloodGlucoseData_Service.onCharacteristicChanged`
decodes the same two current bytes as unsigned little-endian, without a
high-byte flag mask. Its call to `RuGlutec_GlucoseCalculationArrayNew` uses
that current, and the calculator contains the same smoothing/division path.
The surrounding service also contains history-dependent K/B adjustments
using daily time windows and calibration events, plus a packet-260 reference
insertion conditional on event count. These are not all implemented by the
standalone calculator port. Their presence is **not proof** that one would
correct this particular excursion; packet 260 also precedes the user's later
packet-267 manual calibration.

The earlier scaling fix established metadata normalization only. Full vendor
automatic-calibration parity and sensor accuracy remain unverified. A run of
this same transmitter in the official app, with its output and calibration
state, is needed to distinguish missing vendor compensation from anomalous
sensor output. Do not introduce a guessed current mask, glucose cap, or
automatic calibration against the other CGMs to conceal this discrepancy.

## 13:27 fresh-launch trace: restored coefficient and future-dated history

`juggluco-connection-CFD8EBDDF969-20260914-132741.txt` shows snapshot packet
438 restoring processed current 500, K 140.84508 and B 0. Packet 443 then
arrives as `40 BB 01 20 02 32` (raw 544) and produces 3.9 mmol/L with K
140.84; packet 444, raw 494, produces 3.5. The near-reference output therefore
uses a much larger restored slope than the earlier 20.12. The restore code
reconstructs K from a rounded cloud reading (`500 / (3.6 - 0.05)`), rather
than receiving an independently authoritative K parameter from the vendor.
This is not evidence that QR-default auto-calibration fixed the excursion.

The detail fallback returns 189 parsed records, but the local bridge rejects
all 189 as invalid. Their timestamp endpoints are 1789383631000 and
1789417651000, later than the phone's 1789374276041 fetch time. In combination
with packet endpoints 249 and 438 from the preceding trace, both imply the
same cloud origin, 1789338811000 (`rd - packet * 180000`), about 12 hours
18 minutes later than the transmitter's live-counter origin. This supports a
new cloud session counting forward from an already-running packet number;
it is not a seconds/milliseconds or glucose-unit error. The earlier backfill
log misleadingly called this synced and advanced its cursor into the future.

The manager now defers future history until directly received BLE data can
anchor it. Timestamp recovery requires at least two distinct packet numbers,
none ahead of the live counter, a common cloud origin within one minute,
and a positive origin shift exceeding two sample intervals. Recover using
the same packet/cadence anchor as BLE history and explicitly log timestamps
as estimated. Preserve glucose values and missing packet gaps; leave already
valid timestamps untouched. Deferred data is tied to the snapshot ID and
cleared on sensor reset. Advance the cursor only after an actual import,
using the recovered timestamps. This still needs device validation; it does
not repair or validate the earlier high glucose readings.

## September 15 link timeouts and unrequested transmitter restart

The 01:32 and 02:25 traces overlap; do not add their event counts together.
`juggluco-trace-20260915-022515.log` captures Pixel 8 Pro / Android 17 SDK 37
from 23:49 to 02:25. It contains 20 disconnects with status 147: 16 while
streaming and four while connecting. Android defines 147 as
`BluetoothGatt.GATT_CONNECTION_TIMEOUT`. Connection attempts in the latter
group end after about 30 seconds. Some streaming failures follow a valid
frame by only 6.5 or 9.5 seconds; the local no-data watchdog is not logged as
initiating them. The app schedules recovery after the Android disconnect
callback. The only explicit local close/removal at the start is at 23:49:42.

At 01:52:48 another status-147 disconnect occurs after packet 90. The next
connection receives command 0x06 BEGIN_WORK at 01:53:24, before the app sends
the with-init acknowledgement. No outgoing reset command is logged before
this. The user confirms they did not initiate the restart, including through
the official app. A generic status-257 GATT failure follows at 01:53:46;
after reconnect the transmitter supplies packets 1–3, then 4, with zero
current. Packet 5 has current 121, and packet 10 has 510. This is evidence of
a transmitter session restart, not merely a UI age/calibration reset. It
does not establish whether power, firmware, or another link-level failure
caused it. Raw battery remains 0x32, which is not a validated percentage or
proof of a healthy battery. No RSSI or controller-level disconnect reason
is available in these captures.

Device backfill is now directly observed: one reconnect replays packets
72–75; another replays 84–86. These must not be described as absent device
history. Cloud recovery is separately blocked: the local snapshot ID is
missing, and start repeatedly returns code 302 with the account already
monitoring message and no snapshot ID. The future-timestamp repair cannot
recover cloud data until the matching session is identified.

The pre-restart glucose calculations use K 29.37, rather than the previous
day's restored 140.84. Their high output therefore cannot by itself diagnose
sensor/filament failure. Repeated radio timeouts plus the unrequested session
restart establish instability; a specific hardware failure remains unproven.
