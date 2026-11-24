# Libre 1/2 driver without the vendor library

Libre 1 and Libre 2 are read without any vendor library. There is no switch and no fallback:
the vendor library (`libcalibrate.so`/`libcalibrat2.so`), the screen that imported it from an
installed LibreLink app and the `libnative.so` test executable are gone from the app and the
build inputs.

| Part | Where it runs |
|---|---|
| Gen2 NFC activation, enable streaming, FRAM read | Kotlin (`drivers/libre2`) |
| Gen2 BLE login and frame decryption | Kotlin |
| First-generation NFC payloads, BLE unlock payload, FRAM and BLE decryption | open native backend |
| Sensor family, warm-up and wear times, activation state | open native backend |
| Glucose, trend and history from scans and BLE frames | open native backend |

The open native backend is `Common/src/main/cpp/libre2_imp`, taken from Juggluco 12
(`libre2_imp/backend.cpp` and `processing/`, GPL-3.0-or-later). Two things were left out: its
Gen2 JNI session layer, which the Kotlin code replaces, and the scan-trend field this fork's
result type does not have. `libre2.cpp` calls it directly.

Connection lifecycle, reconnects and the stream watchdog stay in `Libre2GattCallback`.
`Libre2Gen1Crypto` (Kotlin) reproduces the first-generation payloads and is covered by tests,
but the app uses the native backend for them.

Libre 3 is separate. Its legacy path keeps `liblibre3extension.so`, `libcrl_dp.so` and
`libinit.so`; the Kotlin Libre 3 driver in PR #839 replaces them when it is on. Nothing here
touches either Libre 3 path.

## Sensors already running when the app is updated

- The open backend reads the algorithm state the vendor library saved, when its layout and
  wear length match. Streaming continues without a scan.
- A state it cannot use is rebuilt at the next NFC scan from the sensor memory and the saved
  start time. BLE frames wait for that scan, and a running stream then reads its next frame from
  the rebuilt state without a restart. When the state was missing altogether no BLE
  stream existed, so the scan that saves the rebuilt state also restarts Bluetooth with the
  saved login record, also when it shows no glucose (warm-up or a reading error).
- A saved state whose offsets point outside the file is treated as missing.
- The open backend marks each state it saves (flag slot = 2, `libre2_imp::open_backend_state`),
  so a copy on a watch or mirror carries its owner. Update the phone and the watch together:
  a build older than this change would hand that state to the vendor library.
- The vendor library's end-of-wear workaround is gone: no artificial restart in the last hour,
  and the stream no longer moves the start time by a day near the end of wear. The day it gave is
  a plain grace period now, see "End of wear".

The glucose values of the open backend can differ from the vendor library's. When it marks the
rate of change unreliable, the rate is reported as unknown and no trend arrow is shown.

## When a reading has no value

A reading is dropped only when there is no measurement to show:

- the sensor sent none: over BLE such a minute has no ADC value, only an error code; in a scan
  the sensor marks the record with its own error bits;
- the ADC value of the newest minute is at or below the sensor's baseline (checked on the
  minute itself, not only on the five-minute average);
- the result is not positive after the glucose curve, for the current reading and for each
  history point (it is not stored as a clipped low);
- the sensor has run for less than 10 minutes, or, until the end of the warm-up (60 minutes),
  the value before clipping is outside the display range (40-500 mg/dL); history points are
  checked the same way at their own minute.

The native rules used no data before minute 45 and showed nothing before minute 60. From
minute 10 a reading that looks like glucose now shows, and history from then on is kept.

Records the sensor marks with its own error bits in a scan stay dropped: their raw value comes
with flags whose meaning is not decoded.

The native rules dropped much more. JugglucoNG keeps the glucose in each of these cases and
leaves only the rate unknown where the rate is affected:

- the signal jumped by more than 608 ADC units between minutes, or moved more than 208 units
  per minute on average over five minutes (spike and noise flags);
- the slope estimates disagreed by more than 6 mg/dL per minute, or none was usable;
- the history moved more than 10 mg/dL per minute on average over the last hour (this also
  threw away the history);
- flagged history records around now, or a sensor error bit (0x01, 0x04, 0x40) on any one
  record in the last 16 minutes, which invalidated the whole trend window (the newest record
  keeps its own flags, so its minute still drops);
- skin temperature outside 20-40 °C ("too cold", "too hot"): compensation uses the measured
  temperature; with no valid temperature in the window it uses 32.5 °C, where compensation is
  neutral.

The algorithm still averages the raw signal over five minutes and blends each result with the
previous one; further smoothing is the app's.

A BLE frame without a current value still keeps the history it carries and the state it
advances. "Sensor error" shows once three received frames in a row had no value, not on a single
gap; a frame that never arrived does not count. "Replace sensor?" still follows after ten minutes.
Frames during the warm-up never count toward either. A frame the backend refuses (no usable saved
state, or a failed checksum or time check) says nothing about the sensor: it does not count and
does not end an error. The log names the reason. While frames arrive that no saved state fits, the
status is "Scan the sensor" until a scan has rebuilt it.

History points take the sample of their own quarter hour. The native code numbered the points by
position, so a new point computed in the first two minutes of a quarter hour got the sample of
15 minutes later. From about minute 495 on, the oldest of the 31 points has left the sensor's raw
window during those two minutes and is not filled then.

An NFC scan the backend cannot process asks for activation only when the data shows a verified,
not yet started sensor (phase, checksums, ring pointers and age 0).

## End of wear

Frames and scans are read for 24 hours past the nominal wear (`wear_grace_minutes`). The saved
start and the nominal wear stay as they are, and the official and expected ends the app shows do
not move. After that a frame is refused and counts as the end of the sensor: three received frames
show "Replace sensor?". The sensor's own minute counter must be past the nominal wear too, so a
wrong phone clock only refuses frames.

What the app ends on its own, for a sensor with NFC patch info:

- `getmaxtime()` / `checkinfo()` retire it after the same 24 hours, and not while frames arrive.
- The sensor list's own length ended the live updates to a watch or Clone at nominal + 12 h, and
  at 14.5 days for a 15-day sensor added by a first scan. It covers the 24 hours now.
- An automatic handover waits for the 24 hours while the old sensor still reads; its "remove"
  option would have stopped the old sensor at 12 h.
- A stream history point past the mapped history is dropped instead of written.

Left alone: a scan whose newest reading is more than 3 minutes old finishes the sensor. That is
the sensor's own end (the age its footer caps the records at), not a clock here. The LibreView
switch that stops uploads at the official end stays.

## Evidence

**First sensor run (2026-11-17).** One Gen2 sensor (patch type `0x39`, root slot 1), Android 13,
signed build of PR head `f57d11e29` (run 43881832149). That build had the Kotlin Gen2 code and
the open backend, without the later in-file state marker (`9381bfec0`) and without this
removal. The same sensor read with the vendor library first:

- 15:25:56, vendor library: scan 4.6, first BLE frame 4.7 mmol/L (then 4.4, 4.3, 4.4).
- 15:33:06, open path: Kotlin FRAM read and enable streaming accepted; scan 4.6 mmol/L; Kotlin
  BLE login accepted; BLE frames 4.6 (15:33:42) and 4.7 mmol/L (15:34:42). No error, no vendor
  library load.

This confirms the full 19-byte NFC request, the session key derived from the login reply, and
the F002 nonce on real hardware. It covers one scan and two BLE frames in one minute. Long runs,
reconnects, restarts, root slot 2, Libre 1 and first-generation Libre 2 are untested. The first
GATT callback took 27 s on the open path against 0.4 s on the vendor path; repeat to see
whether that recurs.

**Second run (2026-11-20).** Same sensor, Samsung SM-S938B, Android 17, build
`1.3.3-Alpha-phoneDUB`, no other app connected. 28 minutes, all on the open path:

- The first scan read 3.8 mmol/L and enabled streaming. The connection came up at once, but the
  sensor ended it (status 19) before the login and then did not answer for a minute (status 147
  every 10 s). The tester removed the sensor and scanned again; BLE logged in 15 s after that
  scan.
- The first frame after the login had no current measurement (status `0x20` on the newest
  one-minute record, quality `0x8020`) and showed "Sensor error" until the next frame a minute
  later. Such a frame now keeps its history and no longer shows the error. The next 21 frames, 3.8 to 5.7 mmol/L, had no errors.
- After a link timeout the sensor did not answer for 70 s. The next connection lost the login:
  the challenge arrived before Android confirmed our request, the login write was refused as
  busy, and the sensor ended the link before the 100 ms retry. The next login, two minutes
  later, worked, and the frame history filled the gap. The login step now goes out as soon as
  Android confirms the previous write.

Tests:

- Gen2 Speck/KDF/CTR/CRC, the BLE login request and the NFC enable request reproduce the
  synthetic vectors from the reverse-engineering notes and Juggluco's cipher self test.
- Gen2 session-key derivation, frame decryption, FRAM block decryption and reply checks are
  also tested against a simulated sensor written from the same protocol description.
- First-generation payloads and BLE decryption (Kotlin) match the vendor-library fixtures in
  `Common/src/main/cpp` (`getStreamingPayload.h`, `getActivationPayload.h`,
  `getStreamingUnlockPayload.h`).

## Protocol choices

- The NFC request carries all 15 encrypted bytes, as Juggluco 12 does; a real sensor accepted
  it.
- Root slots 1 and 2 have keys. A stored slot of 0 or 0xFFFF, or a 10-byte record without a
  slot, uses slot 1. Any other slot stops with "unsupported root slot".
- Over BLE the last two bytes of the 25-byte login reply are not checked; no checksum for
  them is known. The seven-byte echo of our random value is checked.
- The existing 10/12-byte provisioning record in the sensor file is reused as is, so sensors
  paired by older builds log in without a new scan.

## Test checklist

Send the "Libre2Managed", "Gen2" and "Libre2GattCallback" log lines with each failure. They
carry no keys, unlock payloads or glucose values.

1. Update with a Gen2 sensor running: BLE readings continue without a scan.
2. Leave it streaming for several hours; compare with a second reader or app.
3. Toggle Bluetooth, walk out of range and back, restart the app: BLE logs in again each time
   ("Kotlin Gen2 session"); the status line never shows "Kotlin: …".
4. Activate a new Gen2 sensor.
5. First generation and Libre 1: scan, enable streaming, BLE reconnects and history.
6. Two sensors at once, one of each generation if available.
7. Delete or corrupt the sensor's saved state, scan: the state is rebuilt, no crash.
8. Scan and stream in the last hour of wear and the 24 hours after: every scan and frame reads,
   the shown start and ends do not move, and a watch or Clone keeps receiving.
9. Phone and watch both on this build, watch connected directly to the sensor.
