# iCan (SinoCare) CGM — BLE wire protocol notes

Protocol notes for `Common/src/main/java/tk/glucodata/drivers/icanhealth/`, assembled from three
independent sources:

1. the vendor Android SDK (`com.sinocare.cgm.cgm_sdk`, RU build 01.16.01.02 — plain-text dex);
2. driver logs from two live sensor families (`iCGM-t6` sensors, one activated with the vendor app
   and one with ours);
3. a PacketLogger capture of the **official iOS app** talking to a single `iCGM-t3` sensor for
   ~2 h (2026-09-26/27) — the vendor client's own traffic, used to validate the crypto, the history
   flow and the live-notification format.

Written 2026-10-19. The calibration fix for this driver is PR #635; the follow-ups that are *not*
implemented yet are tracked in issue #676.

**Conventions.** `TX` = phone → sensor, `RX` = sensor → phone. Handles are the ones the sensors in
those captures actually used, not spec defaults. All multi-byte integers are little-endian unless
stated otherwise.

---

## 1. GATT map

The sensors expose the standard Continuous Glucose Monitoring service (`0x181F`) plus the standard
Device Information service (`0x180A`), and the vendor overloads some of the standard
characteristics with proprietary opcodes.

| handle | characteristic | access | used for |
| --- | --- | --- | --- |
| `0x0016` | `0x2AA7` CGM Measurement | notify | history batches (`SN…`) and the 19-byte live packet |
| `0x0017` | — CCCD of `0x2AA7` | write | `01 00` (notify) |
| `0x0021` | `0x2A52` Record Access Control Point | write | history requests (`F1 03 …`) |
| `0x0022` | — CCCD of `0x2A52` | write | `02 00` (indicate) |
| `0x0024` | `0x2AAC` (declared UUID) | write | vendor opcodes: auth + lifecycle (`F3`, `F4 10 …`, `F6`, `F7`, `F2`) |
| `0x0025` | — CCCD of `0x2AAC` | write | `02 00` (indicate) |
| `0x001B` | `0x2AA9` CGM Status | read | 6 bytes: time offset + status (see §7) |
| `0x001D` | `0x2AAA` CGM Session Start Time | read | 9 bytes: wall clock + zone byte (see §7) |
| `0x0028` | vendor | read | 2-byte ASCII model family, e.g. `69 33` = `"i3"` |
| `0x002A` | vendor | read | ASCII model, e.g. `"iCGM-t3"` |
| `0x002C` | vendor | read | ASCII sensor serial (24 B, zero-padded in the capture) |
| `0x0035` | vendor | read | 5-byte value, purpose unknown (`64 XX XX 16 00`) |
| DIS `0x2A24/0x2A25/0x2A28` | `0x180A` | read | model / serial / software version the driver keys its crypto family off |

`0x0004` (Service Changed) is written `02 00` by the vendor app on every connection; it is not part
of the CGM protocol.

The vendor opcode space is **not** the SIG's Specific Ops Control Point protocol — only the
characteristic UUID is standard. Likewise the RACP writes use a vendor opcode prefix (`F1`) instead
of the SIG's opcodes.

## 2. Connection flow (observed, official iOS app, ~65 connections)

```
connect → MTU exchange → service/characteristic discovery
  → TX CCCD 0x0004=0200, 0x0017=0100, 0x0022=0200, 0x0025=0200
  → RX identity reads: 0x0028 ("i3"), 0x002A ("iCGM-t3"), 0x002C (serial), 0x0035 (5 B)
  → RX 0x001B (CGM status) and 0x001D (session start time)
  → TX 0x0024 F3                 RX 06 F3 01 <4 B challenge>
  → TX 0x0024 F4 10 <16 B token> RX 1C F4 01 (accepted) | 1C F4 02 <payload> (rejected)
  → TX 0x0021 F1 03 <subtype> 01 <offset u16>      (history request)
                                 RX 0x0016 SN…        (encrypted batch)
                                 RX 0x0021 06 00 F1 01 (RACP complete)
  → at the next 3-minute boundary: RX 0x0016 19-byte live packet
  → disconnect
```

Nothing else is written in steady state: no code-state read, no calibration, no lifecycle command.

## 3. Authentication

* `TX 0x0024 F3` → `RX 06 F3 01 <challenge:4 B>`. The challenge is the session nonce; every
  encrypted frame in the session is IV'd from it (§9).
* The reply token is `F4 10 <16 B>`: AES-128-CBC/PKCS7 over a 12-char user id padded to the block
  size, keyed with the auth key of the sensor's key family, IV = challenge-derived
  (`ICanHealthParser.deriveAuthToken`, `buildAuthPlaintext`).
* `RX 1C F4 01` = accepted. `RX 1C F4 02 <payload>` = rejected; the payload carries the user id the
  sensor expects, which is what `ICanHealthParser.recoverUserIdFromAuthReject` uses to recover from
  a sensor that was started by a different app instance.
* The user id is an activation fingerprint: a sensor that has never seen a token accepts the first
  one it is given, and later rejects other ids. This is what lets the driver tell "started by our
  app" from "started by the vendor app" independently of `F6` (§6).
* The vendor app authenticates on every connection; auth is *not* part of provisioning.

## 4. History

### 4.1 Requests

```
TX 0x0021: F1 03 <subtype> 01 <offset u16 LE>
```

* `subtype 02` = glucose records, `subtype 01` = "original" records (glucose + current + temperature).
* `offset` is the **sequence number**, which equals minutes since the session started; the sensor
  produces one record every 3 minutes, so sequence numbers advance in steps of 3.
* `F1 01 <n>` is the vendor's "report all records" variant.
* Results come back as an indication on the RACP characteristic:
  `06 00 F1 01` = completed, `06 00 F1 02` = not supported, `06 00 F1 06` = no data,
  `06 00 F1 0A` = failed — see §6 for the one case that matters.

### 4.2 Batches

A batch arrives as a single notification on `0x2AA7`:

```
53 4E <size-3> 2A 52 <subtype> <ciphertext> <trailer>
```

The ciphertext is AES-128-CBC/PKCS7 with the session IV (§9) under the glucose key (`subtype 02`) or
the original-history key (`subtype 01`); the plaintext is

```
55 <baseSeq u16 LE> <records…> AA   + PKCS7 padding
```

Records are fixed-size: 2 bytes for `subtype 02`, 4 bytes for `subtype 01`.

* glucose record: `low | ((high & 0xF) << 8)` in hundredths of mmol/L; the high nibble is not part of
  the value. Some firmware needs the "direct" 16-bit form instead (`usesDirectSnHistoryGlucoseEncoding`).
* original record: current (u16, hundredths of mmol/L) + temperature (u16, tenths of °C).
* Values produced during warm-up are XOR-obfuscated; `ICanHealthParser.decodeHistoryGlucoseBytes`
  mirrors the vendor's table. The official app's own stream shows the same low/odd values at the
  start of a session, with the plain decode.

### 4.3 Offsets and backfill

The vendor app requests from **its own last-known offset**, not from "now", and the sensor serves
holes behind the live edge. In the capture, the counter already read 42 while the request offset was
30, and one batch returned records 30, 33, 36, 39, 42. Subtypes 01 and 02 are tracked as two
independent tails, so one can lag the other.

Our driver instead catches up from `lastKnown + interval` (`ICanHealthHistoryPolicy`), which never
fills a hole behind the live edge. See issue #676 for the decision.

## 5. Live notifications

The 19-byte notification on `0x2AA7`:

```
<seq u16 LE> 02 <16-byte ciphertext>
```

Decrypted with AES-128-CBC/PKCS7, session IV, glucose key:

```
0D 80 <value 2 B> <seq u16 LE> 01 00 00 00 00 00 00 00 | 03 03 03
 │  │  │           │             │                        └ PKCS7 padding
 │  │  │           │             └ constant 0x0001 ("one new record")
 │  │  │           └ same sequence number as the outer header
 │  │  └ value field — see below
 │  └ 0x80 (launcher state: running)
 └ 0x0D = 13 = 16 − 3
```

* On `iCGM-t6` the value field is the glucose, in the same encoding as a history record: it matches
  the history record with the same sequence number exactly (verified against a batch decrypted from
  the same session).
* On `iCGM-t3` it is **not** glucose: no relation to the history record at the same sequence, no
  affine or monotone mapping, no CRC-16 (8 standard variants tested), not the temperature and not the
  current field. It behaves like a nonce, and the packet reads as a "one new record is available"
  trigger — which is how the vendor app treats it (it requests history seconds after receiving one).
* Our driver currently decrypts this frame with AES/ECB. That happens to preserve the glucose field
  (the IV only touches bytes 1/6/10/15) but corrupts the parsed `statusByte` and `currentValue`, and
  makes the logged "flags" byte `0x80 ⊕ challenge[0]`. See issue #676.

## 6. Provisioning: why an unprovisioned sensor refuses history

* History *reads* need a per-sensor calibration table (`correctCode`, 25 floats) that the vendor's
  server hands out for a sensor serial and the vendor app writes into the sensor. Without it the
  sensor refuses every RACP history request with `RX 06 00 F1 0A` and returns no records at all —
  proven by an A/B on hardware: a sensor activated with our app never served a single record, one
  activated with the vendor app served records normally.
* The state is readable: `TX 0x0024 F6` → `RX 1C F6 01 <lo> <hi>`. `0xFFFF` means "no code written".
  Observed written values: 500, 510, 530, 600.
* The vendor app writes the code during its own activation/binding flow, never during normal sync:
  in the iOS capture there is **no** `F6` request and **no** `F2/F7` write in the whole window (the
  byte pair `1C F6` does not occur at all), and history works because the sensor was provisioned
  before the capture started.
* Consequences for us: we cannot repair a sensor that was started with our app (the table comes from
  the vendor's server and is applied by native, packed code), and pointing the vendor app at an
  unknown sensor is not a repair path — its lifecycle operations act on the account's bound device.
  The workable order is: vendor app first, Juggluco afterwards. A log line when `F6 == 0xFFFF` is
  proposed in issue #676.

## 7. Session clock

`0x2AAA` (read once per connection) is 9 bytes:

```
<year u16 LE> <month> <day> <hour> <min> <sec> <zone> <dst>
```

* The wall clock is the **local time of the phone that started the session**, not UTC.
* Byte 7 is a **constant** the vendor's writer puts there (`20` = +5:00) regardless of the phone's
  zone — verified on two captures: an Android phone at +08 and an iPhone at +05, both showing `14` in
  this byte while the counter below agreed with the *local* wall clock. Treat it as "unknown" and
  resolve the wall clock in the device zone (`ICanHealthConstants`/`parseSessionStartTime`; fixed in
  PR #635). The vendor's own reader has the same problem from the other side: it interprets the wall
  clock as `GMT+8` and never looks at bytes 7–8.
* `0x2AA9` (CGM Status, 6 bytes) starts with the **time offset in minutes** — the same number as the
  history sequence number — followed by status/CRC bytes that our driver ignores. In one capture the
  last byte flipped from `00` to `01` about two hours into the session; the meaning is unconfirmed.

## 8. Crypto summary

Two key families, selected from the sensor serial / software version
(`ICanHealthConstants.resolveBundledKeyFamily`). Each family has a glucose key, an
original-history key and an auth key (`DEFAULT_{OLD,NEW}_{GLUCOSE,ORIGINAL_HISTORY,AUTH}_AES_KEY_ASCII`).

* Everything encrypted in a session uses **AES-128-CBC/PKCS7** with a 16-byte IV built from the
  session challenge: the four challenge bytes go to IV positions 1, 6, 10 and 15, everything else is
  zero (`ICanHealthParser.buildAuthIv`).
* The IV layout is why an ECB decrypt still yields the right glucose (bytes 2–5 of the record are
  unaffected) — do not read that as evidence that ECB is correct anywhere in this protocol.
  `ICanHealthConstants` still carries an outdated header comment claiming "glucose payloads:
  AES-128-ECB"; it should say CBC/PKCS7 (tracked in #676).
* Encryption direction (auth token, F2/F7 writes) is CBC/PKCS7 too
  (`ICanHealthCrypto.encryptCbcPkcs7`).

## 9. Lifecycle opcodes

Not implemented here, and none of them appear in steady-state traffic from either app:

* `F5` — start/session command (our onboarding writes it),
* `F2` — write the calibration table in chunks, `F7` — commit it,
* `F6` — read the code state (see §6),
* terminate flows reachable from the vendor app's sensor management, not from syncing.

## 10. How the evidence was produced

* **Android**: HCI snoop logs from a rooted test phone, filtered to the driver's own log lines
  (`WIRE tx write …` / `WIRE rx notify …` in `adb logcat -s ICanHealth ICanHealthParser`). The
  driver logs both directions, which makes the vendor app unnecessary for protocol work.
* **iOS**: a PacketLogger (`.pklg`) capture. Useful facts when re-parsing one: HCI ACL frames carry a
  5-byte header (type, handle+flags, length) followed by an L2CAP header (length, CID); the ATT PDU
  starts at byte 9. ATT **read responses carry no handle** — pair `0x0A` requests with the following
  `0x0B` responses to attribute values. tshark's CGM dissector hides the vendor payloads, so parse
  the hex dump directly instead of trusting `-T fields -e btatt.value`.
* **Per-frame device attribution** matters as soon as a capture contains more than one sensor: match
  connection handles to addresses from the HCI connection events, then carry that mapping forward in
  time.
* The vendor SDK references in these notes (`j2.java` etc.) come from the RU build's plain-text dex;
  the current "pure iCan" build has the same SDK behind an ijiami-packed dex, so use the RU build for
  reading and the current build for behaviour.

## 11. Open questions

1. The `iCGM-t3` live value field (nonce? MAC? something else) — and whether it is safe to ignore the
   live lane entirely on that family.
2. Exact meaning of the non-`0xFFFF` code-state values and of RACP `0x0A`.
3. The 4 bytes after the time offset in `0x2AA9`.
4. The vendor's `0x0035` read.
5. Whether the sensor serves arbitrarily old history ranges (a deliberate old-offset request on a live
   sensor would settle it).
