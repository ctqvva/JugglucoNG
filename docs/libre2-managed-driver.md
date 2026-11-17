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
  start time. BLE frames wait for that scan. When the state was missing altogether no BLE
  stream existed, so that scan also restarts Bluetooth with the saved login record.
- A saved state whose offsets point outside the file is treated as missing.
- The open backend marks each state it saves (flag slot = 2, `libre2_imp::open_backend_state`),
  so a copy on a watch or mirror carries its owner. Update the phone and the watch together:
  a build older than this change would hand that state to the vendor library.
- The vendor library's end-of-wear workaround is gone: no artificial restart in the last hour,
  and the stream no longer moves the start time by a day near the end of wear.

The glucose values of the open backend can differ from the vendor library's. When it marks the
rate of change unreliable, the rate is reported as unknown and no trend arrow is shown.

An NFC scan the backend cannot process asks for activation only when the data shows a verified,
not yet started sensor (phase, checksums, ring pointers and age 0).

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
8. Scan in the last hour of wear and the day after: every scan reads.
9. Phone and watch both on this build, watch connected directly to the sensor.
