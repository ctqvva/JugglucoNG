# Sibionics GS3 CN — BLE wire protocol and managed driver

Protocol notes for the managed GS3 CN driver in
`Common/src/main/java/tk/glucodata/drivers/sibionics/` (`SibionicsGs3*`). The protocol comes from
static analysis of the Chinese vendor app; nothing here has been confirmed against a live sensor
yet. §8 lists what the first hardware trace must confirm.

**Conventions.** `TX` = phone → sensor, `RX` = sensor → phone. Multi-byte integers are
little-endian. "Vendor" refers to the decompiled vendor driver; where this driver deliberately does
something else, it says so.

GS3 CN is **not** the international GS3 (`Variant.GS3`, legacy native subtype 4, V120/RC4 with the
`com.sisensing.gs3` key). It pairs with the CN app (`com.sisensing.sisensingcgm`), so its session key
is the one the existing CN registration key derives (`GKSHGDU0TYA456G4`), and it speaks the
AES-framed protocol below. It is a separate variant, `Variant.GS3_CN` (id `gs3cn`), with no legacy
subtype. The international GS3 stays on the legacy native path; `SibionicsLegacyMigration` still
skips it.

| Concern | File |
| --- | --- |
| Crypto, framing, packet builders, frame parsing | `SibionicsGs3Protocol.kt` |
| Handshake/streaming state machine (no Android) | `SibionicsGs3Session.kt` |
| Identity MAC from `0x2A25` / advertisement | `SibionicsGs3Identity.kt` |
| GATT, timers, storage, UI | `SibionicsGs3BleManager.kt` |
| Record routing (`createRestoredCallback`), identity persistence | `SibionicsRegistry.kt` |

---

## 1. Transport and identity

| Role | UUID |
| --- | --- |
| Service | `0000ff30-0000-1000-8000-00805f9b34fb` |
| Notify | `0000ff31-…` |
| Write | `0000ff32-…` |
| CCCD | `00002902-…` |

The 6-byte **identity MAC** goes into the auth packet and the `REGISTERED_V3` IV. It comes from
the sensor, never from the BLE address:

1. Device Information `0x180A` / Serial Number String `0x2A25`, read on every connection before
   notifications are enabled. Text forms `AA:BB:…`, `AA-BB-…` and a free-standing run of twelve hex
   digits are accepted; a 6-byte non-text value is taken as the MAC itself.
2. Advertisement: manufacturer data for company `0x55AA`, else service data for UUID `0x5347` —
   the first six bytes of the payload.

The last identity seen is persisted per sensor. With no identity at all the driver does **not**
handshake: it scans the saved address for an advertisement carrying one and retries.

No MTU request is made (vendor behaviour); `onMtuChanged` is logged.

A device whose service discovery **completes** without FF30/FF31/FF32 is rejected: its address is
never reconnected to automatically, and the status says it is not a GS3 CN sensor. Only an
explicit reconnect from the UI clears that. A discovery that *fails* is retried as in §5.

Auth frames carry the session key, so their bytes are never logged — only the first two bytes and
the length. (In `PLAIN` the wire bytes are the key itself; the encrypted forms are reversible with
the constants in this file.)

## 2. Framing and crypto

```
byte 0      len    bytes before the CRC
byte 1      cmd
byte 2..    payload
byte len    crc8   (-sum(bytes[0..len-1])) & 0xFF
```

Total length `len + 1`. The whole frame, length and CRC included, is encrypted as one buffer.

| Cipher | Use | Key / IV |
| --- | --- | --- |
| `PLAIN` | first V3 fallback | — |
| `REGISTERED_V3` | default | AES-128 key `01 38 0B 9A 00 5B 02 5D CD 9E C3 99 09 37 AA E8`, IV = MAC + 10 zero bytes |
| `AES_OFB_ZERO` | decode fallback | same key, zero IV |
| `RC4` | V2 fallback | RC4 with the same 16 bytes (the existing V120 master key) |

AES is used as an OFB keystream (`iv = AES-ECB(key, iv)` per 16-byte block, XOR onto the input);
the unit tests pin it to the JCE's own `AES/OFB/NoPadding`. Outgoing frames use the session's
current cipher. Incoming notifications are tried as plain, current, `REGISTERED_V3`,
`AES_OFB_ZERO`, `RC4`; the first buffer whose length byte and CRC agree wins.

Every cipher is a stream cipher, so a frame split over several notifications (only possible on the
default 23-byte MTU) decrypts once its fragments are concatenated. The session holds a full
20-byte notification that does not decode for up to 1 s and retries it with the next one.

Session key `GKSHGDU0TYA456G4`, glucose key `11 B8 AB 00 01 4B 2A 5F CB 91 F3 AA 99 27 BF E0`,
fixed nonce `1C 7C 8B DB 4E 02 00 00 00 00 00 00`.

## 3. Handshake

ACK frames are `04 <cmd> <result> <arg> CRC` (BindIn may use a 10-byte form); the reply type is
`(requestCmd | 0xC000) + 1`, i.e. the ACK carries `requestCmd + 1`.

| TX | Plaintext | ACK |
| --- | --- | --- |
| Auth V3 | `25 E2 00 <mac 6> <session 16> <nonce 12> CRC` | `0xC0E3` |
| Auth V2 | `19 01 00 <mac 6> <session 16> CRC` | `0xC002` |
| BindIn | `12 35 <time32> <nonce 12> CRC` | `0xC036` (or `0xC039`) |
| SysInfo | `03 F0 <subtype> CRC` (`03 F0 0F FE`, `03 F0 01 0C`, `03 F0 07 06`) | reply with that subtype |
| IdBind | `0F 13 <seq> <nonce 12> CRC` | `0xC014` |
| Activate | `06 0F <time32> CRC` | `0xC010` |
| Time | `06 03 <time32> CRC` | `0xC004` |
| GetData V3 | `06 39 <start16> 00 00 CRC` | `0xC03A`, data as `0x39` |
| GetData V2 | `06 14 <start16> 00 00 CRC` | `0xC015`, data as `0x32` |

```
V3: Auth V3 -> BindIn -> SysInfo(15) -> GetData V3 -> STREAMING
V2: Auth V2 -> IdBind(seq 1) -> SysInfo(1) -> SysInfo(7) -> Activate -> Time -> GetData V2 -> STREAMING
```

Auth falls back V3/`REGISTERED_V3` → the same V3 frame in `PLAIN` → V2/`RC4` → failed. A step is
taken when the reply is an unexpected ACK or frame, when it decodes under no cipher, or after 5 s
of silence (our deadline; the vendor relies on its general watchdog). Pages and SysInfo replies
arriving before auth are ignored, not counted as an answer.

IdBind: result `2` is a sensor bound to another account (fail); `0` continues; anything else
retries once with `seq = 2`, then fails. Other ACKs advance on their type, as the vendor does;
`result`/`arg` are logged. After auth each handshake step has a 15 s deadline; a missed one fails
the connection, and a failed connection retries after 60 s. Every request actually sent gets its
own deadline (the IdBind `seq = 2` retry included); a notification that is ignored does not extend
the current one.

The data request starts at `cursor == 0 ? 1 : cursor + 1`; after each non-empty page the cursor
becomes the page's first index (kept monotonic here) and is persisted with the sensor.

## 4. Glucose pages

```
len, cmd, count, startLo, startHi, records[count * 16], lastLo, lastHi, crc
len = count * 16 + 7
```

`cmd` is `0x32` (V2, live) or `0x39` (V3, history). Per record `i` (16 bytes at `5 + 16 i`):

| Record offset | Frame offset (record 0) | Meaning |
| --- | --- | --- |
| 4..5 | 9..10 | glucose, mmol/L × 10, LE, under a second layer: AES-OFB with the glucose key and a zero IV |
| 14 | 19 | trend `(byte >>> 3) & 7` |

Index = `start + i`. Age in records: `0x39` → `(last + count - 1) - i`, so the trailer is the
number of records the sensor holds beyond the page; `0x32` → 0 (vendor). A multi-record `0x32`
page is counted back from its newest record here so its readings do not share a minute.

Values of 0 or above 50 mmol/L are dropped. One reading per minute is assumed (the Sibionics
family's interval).

**Timestamps.** A record's time is `start time + index × 60 s`. The start time is derived from the
newest record in a page (`now − age × 60 s − index × 60 s`), persisted, and moved only by a current
(age 0) record that disagrees by more than 3 minutes. The native stream window is **not** rebased
when that happens: `rebaseDirectStreamWindow` clears every stored native sample, and the cursor has
already moved past the pages that wrote them. The window only sets the origin of the minute slots;
records keep their absolute times. (Only readings from before the first anchor's index 0, i.e. the
first minutes of wear, could fall outside it; Room keeps them regardless.)
Index-anchored times make the deliberately overlapping re-requests land on the same minutes
instead of creating near-duplicates. The newest record of a page is published live when its age is
0 and it is within 3 minutes of now; this does not depend on the `0x32`/`0x39` distinction, whose
vendor-side `isLive` flag could not be read reliably.

## 5. Timers

| Timer | Value | Action |
| --- | --- | --- |
| Service discovery retry | 3 s, max 2 | re-run discovery, then disconnect |
| Write callback | 4 s | reconnect (see below) |
| Write retry | 120 ms (25 attempts) | retry a write the stack refused |
| Stale data | 90 s | > 180 s without a page: disconnect; else request the next page |
| Connect watchdog | 90 s × 2ⁿ (n ≤ 3) | `CONNECTING` is stale; reconnect |
| Link watchdog | max(180 s, 3 × 60 s + 15 s) | no notification at all: reconnect |
| Auth step | 5 s | next auth fallback |
| Handshake step | 15 s | fail the connection |

Writes are serialised: one in flight, the next after its `onCharacteristicWrite`. The vendor
unblocks the queue after 4 s without a callback; here the connection is restarted instead, because
a write callback carries no identity of its own — a late one for the timed-out write would
otherwise complete whichever write went out next and release later commands early.

## 6. Storage

Same paths as the other managed drivers: `HistorySyncAccess.storeSensorHistoryBatchAsync` for
history, `storeCurrentReadingAsync` + `processExternalCurrentReading` for live, and the native
mirror through `Natives.ensureSensorShellWithCapacity` (21 days),
`setSensorManagedFamily(SIBIONICS)` and `addGlucoseStreamBatchWithRawTemp`. The sensor reports
finished values, so the raw lane carries the same value and no local algorithm, calibration
integration, journal or reset is offered. The UI reports the model as "Sibionics GS3" so the GS3
badge applies.

## 7. Setup

"Sibionics GS3 (China)" is offered in the Sibionics setup. No label or QR format is known for it,
so it is added only by picking the sensor from the FF30 Bluetooth list; the QR scanner, gallery and
manual code entry are hidden for this type, and its record is bound to that BLE address. Scan
matching is exact (address, recorded BLE name, or an advertisement carrying the known identity).

Changing an existing sensor between GS3 CN and another Sibionics type in setup takes effect once
the running driver is recreated (app restart, or remove and re-add), as with the other types.

## 8. To confirm on hardware

1. **Identity byte order.** The MAC is used in the order `0x2A25`/the advertisement give it, not
   reversed (the V120 auth reverses the BLE address). The first log line
   `serial number … identity=` together with the auth outcome settles it.
2. **Auth outcome.** Which attempt (`V3_REGISTERED`, `V3_PLAIN`, `V2_RC4`) is accepted, and the
   cipher the ACK decoded under (logged as `reply cipher=`).
3. **Live/history.** Whether V3 current readings arrive as `0x39` pages with trailer 0 (age 0), and
   whether the `0x39` trailer is a remaining-count as the age formula implies (`page … trailer=`
   log lines, and `session anchor` lines that should appear once, not repeatedly).
4. **Reading interval.** One record per minute. A repeated `session anchor … previous=` line every
   few minutes would mean a different interval.
5. **MTU.** Whether the sensor raises the MTU itself (`MTU changed` line); if not, whether the
   38-byte auth write goes through and pages arrive fragmented.
6. **SysInfo reply framing.** Replies are accepted as `cmd 0xF0` or `0xF1` with the subtype at
   byte 2.
