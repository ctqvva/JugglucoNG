# Medtrum EasySense — BLE capture 2026-10-17

First real-device capture of the Medtrum patch. Supersedes the "HYPOTHESIS" and
"to confirm via BLE HCI sniff" sections of
`superpowers/specs/2026-05-26-medtrum-easytouche-cgm-driver-design.md`.

Raw artifact: `~/Projects/Dia/medtrum-re/easysense-2026-10-17.curf` (plain btsnoop,
3.2 MB; Android 13 names it `BT_HCI_*.cfa.curf` but the content is standard btsnoop v1,
datalink 1002 = HCI UART/H4). Not committed: it contains the phone's other BLE traffic.

## Device

| | |
|---|---|
| Advertised name | **`MT`** (not the decimal serial) |
| Address in capture | `<redacted>` (random) |
| Manufacturer ID | `0x4781` |
| Serial | `0x8ADF6216` = **2329895446** |
| deviceType | `0x88` (136) |
| version | `0x18` (24) |

Manufacturer data (22 bytes, `81 47` = 0x4781 LE):
```
81 47 | 16 62 df 8a | 88 | 18 | 8d 19 21 37 8d 19 2c 55 8d 19 27 55 8d 19
id    | SN (4, LE)  |typ |ver | <trailing fields, meaning unknown>
```
This confirms `ManufacturerData.kt`: SN = bytes[0..3] LE, then deviceType, then version.

## GATT (measured, contradicts the old spec)

| item | spec said | actual |
|---|---|---|
| service | `669a9001-…` | **`669a9002-0008-968f-e311-6050405558b3`** (handle 0x000b) |
| notify char | `669a9120-…` | **`669a9140-…`** @ value handle `0x000d`, properties `0x10` (Notify) |
| write char | `669a9101-…`, WRITE_NO_RESPONSE | `669a9101-…` @ value handle `0x0010`, properties `0x28` (**Write + Indicate**) |
| CCCD | 0x2902 | present on both: handles `0x000e` (for 0x000d), `0x0011` (for 0x0010) |

**Data channel.** The app enabled both CCCDs (`0x000e` and `0x0011`), but every
patch→phone frame in the session arrived as an **indication on `0x0010`
(`669a9101`)**, never as a notification on `0x000d`. A driver that only subscribes
to the notify char receives nothing.

## keyGen — validated against hardware

The AUTH_REQ write ends with `keyGen(SN)` little-endian:

```
AUTH write: 0a 00 05 c9 00 00 00 00 13 44 06 f0
            len  ?  AUTH  token/role  keyGen(0x8ADF6216) = 0xF0064413
```

`MedtrumCrypt.keyGen(2329895446) == 0xF0064413` → bytes `13 44 06 f0` (verified in the
Kotlin port and independently in Python). This is the port's first *independent* vector;
it is now pinned in `MedtrumCryptTest.keyGenMatchesCapturedHardwareAuthRequest`.
The old `keyGen(12345678) = 2305779289` vector was self-pinned and remains consistent.

## Raw session (connection handle 0x201)

Writes to handle `0x0010` (ATT opcode `0x12` = Write Command):

```
62338  0a 00 05 c9 00 00 00 00 13 44 06 f0     AUTH_REQ  (opCode 0x05 = CommandType.AUTH_REQ)
62346  01 00 41
62355  06 00 57 01 25 f6 0f 18
62360  17 00 54 03 3f 00 00 90 01 00 90 01 00 0f 00 00 0f 00 01 00
62364  00 50 00 f0 00
62369  05 00 42 00 00 02 00
62375  06 00 44 06 0c 00 01 00
```

Indications from handle `0x0010` (ATT opcode `0x1d` = Handle Value Indication):

```
62341  0d 00 95 05 00 00 c9 88 01 46 18 07 18 ff ff
62349  25 00 83 41 00 00 18 00 00 04 64 0c 00 00 00 00 00 00 00 00
62352  00 00 00 00 00 00 00 02 00 00 00 00 00 00 00 60 54 cc 08
62358  04 00 8d 57 00 00
62367  04 00 8a 54 00 00
62373  04 00 84 42 10 00
62379  05 00 81 44 06 12 00
62385  05 00 81 44 07 12 00
62392  04 00 9a 0a 00 00
```

## Second pass — 4 sessions (same capture, grown to 4.8 MB)

Sessions start at H4 frame ids 62338 / 65043 / 91806 / 95076 (~11:00–11:12). Raw file
`~/Projects/Dia/medtrum-re/easysense-2026-10-17.curf`.

### Envelope — derived

Both directions use `[len][0x00][opCode][payload…]` with **`len = total_size - 2`**.
Verified across 12-, 15-, 25- and 39-byte messages. Messages larger than the 20-byte ATT
payload are chunked as consecutive write commands / indications, and **continuation PDUs
carry no header**. The earlier "counterexample" `00 50 00 f0 00` is the 5-byte tail of the
25-byte `0x54` command (20 + 5, `len = 0x17 = 23`), not a message of its own.

### AUTH is deterministic

Identical in all four sessions:

```
-> 0a 00 05 c9 00 00 00 00 13 44 06 f0     AUTH_REQ
<- 0d 00 95 05 00 00 c9 88 01 46 18 07 18 ff ff
               op   res=0    ^  deviceType=0x88   ver 1.70.24
```

- The 4 bytes `c9 00 00 00` are **constant across sessions** — not a random session token.
  Which of them is `role` vs `token` is still undetermined (spec unknown #1).
- `deviceType = 0x88` matches the value advertised in manufacturer data, and the version
  `01 46 18` = 1.70.24 (the advertiser's version byte was 0x18 = 24).

### Medtrum time

A 4-byte little-endian counter appears in the `0x57` write and echoed in the `0x41`
record. One value `0x180FF623 = 403697187` is **constant across sessions** (candidate:
patch/sensor start time); the other increments (current time; s2→s3 delta 6288 s ≈ 105 min,
matching the frame gap). Epoch base not yet mapped to wall clock.

### EasySense command set (app -> patch)

`0x05` (AUTH), `0x0a`, `0x41`, `0x42`, `0x44`, `0x50`, `0x54`, `0x57`. Only `0x05` and
`0x0a` exist in AndroidAPS `CommandType`; the rest are EasySense-specific.
Responses are indications that echo the request opCode.

Per-session sequence (s1):

```
-> 0a 00 05 c9 00 00 00 00 13 44 06 f0     <- 0d 00 95 05 00 00 c9 88 01 46 18 07 18 ff ff
-> 01 00 41                                  <- 25 00 83 41 … (39-byte record, chunked over 2 indications)
-> 06 00 57 01 <time4>                       <- 04 00 8d 57 00 00
-> 17 00 54 03 3f 00 00 90 01 00 90 01 00 0f 00 00 0f 00 01 00
   + 00 50 00 f0 00   (tail of the 0x54 command)   <- 04 00 8a 54 00 00
-> 05 00 42 00 00 02 00   (s4: 05 00 42 ff ff 03 00)  <- 04 00 84 42 10 00
-> 06 00 44 06 0c 00 01 00                   <- 05 00 81 44 06 12 00
-> 06 00 44 07 0c 00 01 00                   <- 05 00 81 44 07 12 00
-> 02 00 0a 01                               <- 04 00 9a 0a 00 00
```

The `0x42` payload differs between sessions (`00 00 02 00` vs `ff ff 03 00`) — candidate
warm-up / sensor-state flag; track it against a post-warm-up capture.

`0x000d` (notify `669a9140`) received exactly **one** notification in four sessions — the
data channel is the indications on `0x0010`.

### Still missing

**No glucose value** — the patch was in warm-up. Payload layout and units (spec #2/#3)
remain open until a capture where EasySense displays a reading.

## Third pass — traffic keeps flowing during warm-up

Log grew to 5.3 MB / 8 sessions (adds @100464, @102087, @102772, @107293). The app is not
idle during warm-up; it backfills history.

### `0x42` = read-record, indexed

Request `05 00 42 <index LE16> <n LE16>`, index advances across requests:

```
sess0 @62338  0x0000
sess3 @95076  0xffff, 0x0000, 0x0001, 0x0002
sess4 @100464 0x0004
sess5 @102087 0x0006
sess7 @107293 0x0008, 0x000c
```

The response length follows `n`; payload is a count header then a series of LE16 samples:

```
sess4 [17] 0f 00 84 42 | 00 00 04 00 | 00 02 00 00 00 | 83 03  4d 04          -> 899, 1101
sess7 [21] 13 00 84 42 | 00 00 08 00 | 01 04 00 00 00 | bd 05 2b 06 79 06 9b 06 -> 1469, 1579, 1657, 1691
```

### `0x41` carries a 4-slot sample ring

Bytes 15..22, four little-endian `uint16`, newest first, `0` = empty:

```
sess0-3  (before warm-up)   0, 0, 0, 0
sess4 @100464               1196, 1101, 1101, 1101
sess5 @102087 / sess6       1333, 1196, 1101, 1101
sess7 @107293               1691, 1691, 1657, 1579
```

Sample values rise monotonically through warm-up:
`899 -> 1101 -> 1196 -> 1333 -> 1469 -> 1579 -> 1657 -> 1691`.

Interpretation is **not** settled: impossible as mg/dL; as mmol×1000 it is 0.9–1.7; as
mmol×100 it is 9–17. Most likely a raw sensor signal before calibration. Units still need
a capture with a displayed value.

### `0x41` fixed fields

`[len][0x00][0x83][0x41] 00 00 18 <flag> <flag> <LE16?> 00 <count> 00 <ring8> 07 00 00 00
<LE32 now> <LE32 lag> 0f 00 <crc16?>`. Two 4-byte LE times advance; the second lags the
first (e.g. 403706254 vs 403704697), so neither is a fixed sensor-start time.
`byte23 = 0x07` and the `0f 00` word are constant in this log.

### Frame layout refined

Responses carry an extra byte before the opCode: `[len][0x00][status][opCode][payload…]`
(e.g. `04 00 84 42 10 00`), while requests are `[len][0x00][opCode][payload…]`
(e.g. `05 00 42 00 00 02 00`). The `status` byte is constant per request opCode
(AUTH->0x95, 0x41->0x83, 0x42->0x84, 0x44->0x81, 0x54->0x8a, 0x57->0x8d, 0x0a->0x9a).



- **CGM payload / units.** No glucose reading was produced in the window; the
  session is auth + a few short commands. Unknowns #2 (units) and #3 (field layout)
  are still open.
- **Envelope.** The captured writes do **not** match `MedtrumProtocol.buildSinglePacket`
  (which emits `[len][opCode][seq][pkgIndex][payload][crc8][0x00]`): the real AUTH is
  12 bytes with no CRC-8, no pkgIndex, and a leading `0x00`. For 4 of the 7 writes
  `byte0 == size-2`; `00 50 00 f0 00` breaks that rule, so the envelope is not yet
  derived. Do **not** rewrite the builder from this alone.
- **AUTH field order.** After opCode `0x05` there are 9 bytes
  `c9 00 00 00 00 13 44 06 f0`. Only the last 4 are pinned (keyGen). Whether it is
  `role(1)=0xc9 + token(4)=0 + key`, or `token(4)=0xc9 + role(1)=0 + key`, or something
  else, is undetermined. `role` for a CGM-only reader is still unknown (spec unknown #1).
- **Command set.** Opcodes `0x41, 0x42, 0x44, 0x50, 0x54, 0x57` are not in AndroidAPS's
  `CommandType` (only AUTH `0x05` matched). EasySense has its own command set; the
  port's `SUBSCRIBE=0x04` / `SYNCHRONIZE=0x03` are unobserved here.
- **Notification format** (`state | fieldMask | fields`) is unobserved in this capture;
  all patch→phone traffic was command indications echoing the request opcode.

## Reproduce

```sh
adb shell su -c "rm -f /data/misc/bluetooth/logs/*"
adb shell su -c "svc bluetooth disable"; sleep 2; adb shell su -c "svc bluetooth enable"
# open EasySense, connect to the patch, wait for a glucose reading
adb shell su -c "ls -la /data/misc/bluetooth/logs/"
adb exec-out su -c "cat /data/misc/bluetooth/logs/<file>" > capture.curf
```
Requires root (Magisk present on the capture device) and Developer Options →
Bluetooth HCI snoop log enabled (`settings get secure bluetooth_hci_log` = 1).
Analyse with `tshark` (reads the `.curf` directly; on this host
`/Applications/Wireshark.app/Contents/MacOS/tshark`) or Wireshark.

## Next capture must show a glucose value

To close units/layout (#2/#3), capture a session where EasySense displays a reading,
and correlate the displayed mmol/L against the raw bytes. The 5-byte CGM field
(`fieldMask 0x1000`) is the target.
