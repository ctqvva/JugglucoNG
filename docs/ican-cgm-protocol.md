# iCan (SinoCare) CGM — BLE wire protocol notes

Protocol notes for `Common/src/main/java/tk/glucodata/drivers/icanhealth/`, assembled from three
independent sources:

1. the vendor Android SDK (`com.sinocare.cgm.cgm_sdk`, RU build 01.16.01.02 — plain-text dex);
2. driver logs from two live sensor families (`iCGM-t6` sensors, one activated with the vendor app
   and one with ours);
3. a PacketLogger capture of the **official iOS app** talking to a single `iCGM-t3` sensor for
   ~2 h (2026-09-26/27) — the vendor client's own traffic, used to validate the crypto, the history
   flow and the live-notification format.

Written 2026-10-19; §6 and §7 were corrected the same day after a field report from a user whose
`iCGM-t3` stopped serving glucose — the two claims those sections used to make ("byte 7 is a
constant", "a sensor without a code refuses history") turned out to be wider than the evidence. The
calibration fix for this driver is PR #635; the follow-ups that are *not* implemented yet are
tracked in issue #676.

**Conventions.** `TX` = phone → sensor, `RX` = sensor → phone. Handles are the ones the sensors in
those captures actually used, not spec defaults. All multi-byte integers are little-endian unless
stated otherwise. Claims are distinguished where it matters: directly observed bytes, deductions
from the vendor code, and working hypotheses that still need hardware confirmation (marked as such).

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
* The reply token is `F4 10 <16 B>`: AES-128-CBC/PKCS7, keyed with the auth key of the sensor's key
  family, IV = challenge-derived. The plaintext is 14 bytes — `F4 0C <12-byte user id>` — padded to
  16 by PKCS7 (`ICanHealthParser.deriveAuthToken`, `buildAuthPlaintext`). Getting this layout wrong
  (e.g. omitting the `F4 0C` prefix) yields a different token and fails authentication.
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
* original record: current (u16) + temperature (u16, tenths of °C). The current field is **not** a
  plain glucose unit: the vendor XORs each byte with `0x88` and reads the result as a 5-digit
  supplier-formatted number (`decodeCurrentValue`; kept for internal use only).
* Values produced during warm-up are XOR-obfuscated; `ICanHealthParser.decodeHistoryGlucoseBytes`
  mirrors the vendor's table. The official app's own stream shows the same low/odd values at the
  start of a session, with the plain decode.

### 4.3 Offsets and backfill

The vendor app requests from **its own last-known offset**, not from "now", and the sensor serves
holes behind the live edge. In the capture, the counter already read 42 while the request offset was
30, and one batch returned records 30, 33, 36, 39, 42. Subtypes 01 and 02 are tracked as two
independent tails, so one can lag the other.

Our driver catches up from `lastKnown + interval` (`ICanHealthHistoryPolicy`) and *does* repair
known holes behind the live edge: when a live notification exposes a sequence gap,
`automaticGlucoseHistoryStartSequence` prefers `pendingLiveGap.startSequence` (a regression test
covers requesting 303 after the live counter reaches 312). What it does **not** do is reconstruct a
session it has no stored records for: with no history at all it asks from offset 3, which is far
behind the live edge of any session that has been running for a while — see §6 for why those
requests come back `0x0A`. See issue #676 for the decision.

## 5. Live notifications

The 19-byte notification on `0x2AA7`:

```
<seq u16 LE> 02 <16-byte ciphertext>
```

Decrypted with AES-128-CBC/PKCS7, session IV, glucose key:

```
0D 80 <value 2 B> <seq u16 LE> 01 00 00 00 00 00 00 | 03 03 03
 │  │  │           │             │                     └ PKCS7 padding
 │  │  │           │             └ constant 0x0001 ("one new record")
 │  │  │           └ same sequence number as the outer header
 │  │  └ value field at `[2:4]` — see below
 │  └ 0x80 (launcher state: running)
 └ 0x0D = 13 = 16 − 3
```

* Verified against the capture: challenge `F1 18 6B D2` decrypts one `iCGM-t6` notification to
  `0D 80 0D 02 48 03 01 00 00 00 00 00 00 03 03 03` — six zero bytes, not seven.

* On `iCGM-t6` the value field is the glucose, in the same encoding as a history record: it matches
  the history record with the same sequence number exactly (verified against a batch decrypted from
  the same session).
* On the captured (global) `iCGM-t3` it is **not** glucose: no relation to the history record at the
  same sequence, no affine or monotone mapping, no CRC-16 (8 standard variants tested), not the
  temperature and not the current field. It behaves like a nonce, and the packet reads as a "one new
  record is available" trigger — which is how the vendor app treats it (it requests history seconds
  after receiving one). One CN `iCGM-t3` field report looked different (plausible values), so the
  field's semantics may be variant-specific — issue #676 tracks the open decision and the
  live-vs-history comparison that resolves it. The driver now compares live and history raw values
  at the same sequence number, classifies each sensor (`VERIFIED_GLUCOSE` / `TRIGGER_ONLY` /
  `UNKNOWN`) and stops publishing live values for `TRIGGER_ONLY` sensors (PR #683).
* The driver used to decrypt this frame with AES/ECB — that happened to preserve the glucose field
  (the IV only touches bytes 1/6/10/15) but corrupted the parsed `statusByte` and `currentValue`, and
  made the logged "flags" byte `0x80 ⊕ challenge[0]`. Fixed to CBC/PKCS7 in PR #683, with captured
  vectors as regression tests (see also the semantics note below).

## 6. Provisioning: `correctCode`, and what a refused history request means

* A per-sensor calibration table (`correctCode`, 25 floats) is handed out by the vendor's server for
  a sensor serial and written into the sensor during the vendor app's activation/binding. Whether
  the **sensor** itself requires it for history is not established — see the confound below. The
  state is readable: `TX 0x0024 F6` → `RX 1C F6 01 <lo> <hi>`; `0xFFFF` means "no code written".
  Observed written values: 500, 510, 530, 600 — and 570 on a provisioned `iCGM-t3`
  (`1C F6 01 3A 02`); treat any value other than `0xFFFF` as provisioned.
* The vendor app never asks for history before that code has been written — the gate is in the SDK
  (`hasWriteCorrectCode`, `onPrepareReadHistoryData`, `onWriteCorrectCodeCompleted`) — and the write
  happens during activation/binding, never during normal sync: the iOS capture contains no `F6` read
  and no `F2/F7` write at all, and history works there because the sensor was provisioned before the
  capture started.
* On the sensor side a refusal is `RX 06 00 F1 0A` (legacy `01` family: `06 00 01 0A`), i.e. status
  `0x0A` = "operation failed" rather than `0x06` = "no records found". What can be said about the
  cause is less than it used to be. **Every refusal on record pairs an `F6 == 0xFFFF` sensor with a
  request far behind its live edge** (asked from offset 3 while the `0x2AA9` counter read 72…8598),
  and **every successful request on record is a tail request** on a sensor with a code (from the
  counter itself or a few minutes behind it — which is also all the vendor app ever asks for, §4.3).
  Provisioning and request range are therefore perfectly confounded, and the earlier reading "a
  sensor started with our app refuses history" is not established by those logs.
* Two one-line experiments would separate the explanations; each needs one live sensor:
  1. on an `F6 == 0xFFFF` sensor request the *tail* (counter or counter − 3) — served ⇒ the sensor
     does not gate on the code, and the failures are a request-range/driver-policy matter;
  2. on a provisioned sensor request a *very old* offset (offset 3 in a session that is days old) —
     refused ⇒ the sensor limits how far back it will serve.
  Until then, read `0x0A` as "the sensor would not serve that range" and do not treat `0xFFFF` as
  proof of the reason. A driver that retried from the live edge after `0x0A` — instead of repeating
  the same old offset, which is what produced the 59-attempt run in the logs — is both the experiment
  and, if experiment 1 wins, the fix: filed in issue #676 and implemented in PR #678 (one live-edge
  retry per connection, after both command families fail).
* Independent of that: the code table comes from the vendor's server, and the write into the sensor
  is implemented by packed (ijiami) native code — but both halves are more reachable than an earlier
  revision of this section claimed. The API paths and the data model are readable in the RU SDK
  (`sino-deviceaccess/sdk/authentication|check-device|cgm-code-encrypt`, a 25-float JSON code), the
  write key material is the sensor's own `0xF3` payload rather than a server secret — the vendor
  passes the whole 7-byte notification into its native chunker, and how that expands into a 16-byte
  key is not statically readable — the write sequence is
  visible in Java with `F6` readable afterwards as an on-hardware check of each attempt, and the
  native pieces can be invoked rather than reimplemented. The plan is therefore that the driver
  fetches and writes the table itself during its own activation — tracked in issue #679. Pointing the
  vendor app at an unknown sensor remains a non-repair (its lifecycle operations act on the account's
  bound device), and a log line when `F6 == 0xFFFF` is proposed in #676.
* The cloud half of that plan is mapped as of 2026-10-19 (tracked in #679). All of the SDK's endpoints
  are plain `GET`s; `sino-deviceaccess/sdk/cgm-code-encrypt` takes `deviceSn`, `key`, `timestamp`,
  `sign`, and the server rejects the call without `deviceSn` and then `sign`. `sign` is an MD5-family
  digest over `appId=…&deviceSn=…&timestamp=…&key=…`, where `appId` and `key` come from native getters —
  the one piece with no readable implementation, so a capture of one real fetch is the remaining
  evidence. The response is doubly encrypted: `data.k` RSA-decrypts (1024-bit, PKCS#1; the public key
  is a literal in the SDK) to an `aesKey` string, and `data.v` is AES/CBC/PKCS7 with that key and its
  first 16 characters as the IV; the plaintext is `{"code":200,"data":{"code":"<25-float JSON>"}}`.
  Auth-family requests sign with `lowercaseHex(MD5(joined fields + "&salt=" + nonce + millis))` and
  carry an `appSignature` that is the uppercase-hex SHA1 of the APK signing certificate. None of this
  gates the write path: the hardware test writes a table directly and reads `F6` back.

## 7. Session clock

`0x2AAA` (read once per connection) is 9 bytes:

```
<year u16 LE> <month> <day> <hour> <min> <sec> <zone> <dst>
```

* The wall clock is the **local time of the phone that started the session**, not UTC.
* Byte 7 is a zone offset in 15-minute units (`sint8`), and it is **not** a constant:
  * the vendor app's writer hard-codes `20` = +5:00 next to the wall clock (`j2.java:1115`),
    whatever the phone's zone is — an Aug 2026 Android at `+08` reading a vendor-started sensor
    shows `0x14`, and the `0x2AA9` counter contradicts that stored offset by exactly the 3 h that
    `+08` versus `+05` predicts (`drift=179min` in the driver's own warning);
  * every sensor this driver started itself stores its real zone — `0x20` (+08), `0x28` (+10) and
    `0x0C` (+03) in three different logs.
  So `20` reads as "written by the vendor app" *or* "a device genuinely at UTC+5:00", and the
  payload cannot tell the two apart. `parseSessionStartTime` treats `20` as unknown and resolves the
  wall clock in the device zone (PR #635 filters `TIME_ZONE_VENDOR_CONSTANT`) — correct whenever the
  reader is in the writer's zone, off by the difference in the remaining case. The vendor's own reader has a related problem: it reads
  the wall clock as `GMT+8` and never looks at bytes 7–8.
* The stored time is when the app *wrote* it (activation/binding), which is not necessarily when the
  sensor's session started — the count below is relative to the session, not to the write. A CN
  `iCGM-t3` activated by our app stored `10:27:58` while the counter put its session start 2 h 14 min
  later, at `12:42:11`; timestamps derived from the stored time lag the truth by that gap until the
  counter re-anchors them. That is what the driver's "request the whole session again for timezone
  repair" is for — and that request is exactly the refused pattern of §6.
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
* The IV layout is why an ECB decrypt still yielded the right glucose (bytes 2–5 of the record are
  unaffected) — do not read that as evidence that ECB is correct anywhere in this protocol.
  `ICanHealthConstants` carried an outdated header comment claiming "glucose payloads:
  AES-128-ECB"; corrected to CBC/PKCS7 in PR #683 (tracked in #676).
* Encryption direction (auth token, F2/F7 writes) is CBC/PKCS7 too
  (`ICanHealthCrypto.encryptCbcPkcs7`).

## 9. Lifecycle opcodes

Not implemented here, and none of them appear in steady-state traffic from either app:

* `0x1A` — start/session command (the byte our onboarding writes, `CMD_START_SENSOR`; earlier notes
  called it `F5`),
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
2. Exact meaning of the non-`0xFFFF` code-state values, and of RACP `0x0A` — which is confounded
   between "no correct code" and "range not served" (§6); two one-line experiments settle it.
3. The 4 bytes after the time offset in `0x2AA9` (the byte our driver calls `launcherState`; `0x01`
   was seen on a stopped sensor and on the live `iCGM-t3`, `0x00` on working `iCGM-t6`s).
4. The vendor's `0x0035` read.
5. Whether the sensor serves arbitrarily old history ranges. This is now one half of the §6
   experiment — and the driver's "timezone repair" re-read (offset 3 on a long-running session) is
   already an accidental instance of it, which has failed every time.
