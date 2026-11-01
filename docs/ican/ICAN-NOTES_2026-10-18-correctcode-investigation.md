# iCan / SinoCare — the "server blob", `correctCode`, and history (investigation)

Date: 2026-10-18 · Branch: `t3/ican-history-fetch` · PR: #635 · Working copy: `/Users/ctqwa/.t3/worktrees/JugglucoNG/t3-3c1a7693`

Living document: everything accumulated during this work —
(1) verification of the claim "history only reads after a big blob arrives from the server and is written into the sensor";
(2) what the vendor app does — primary reference: the current pure-iCan build (`com.sinocare.ican.health.ce` 01.17.00.19; Flutter part disassembled, Java part packed) — §3.6, §9; for comparison: the RU build `luch-ru-cgm-0.1.0-vc39` (plaintext dex), which contains the same `com.sinocare.cgm.cgm_sdk`;
(3) analysis of the August TECNO LI7 logs (two iCGM-t6 sensors: "ours" `04SQ` and "official" `05FE`);
(4) side finding: byte `0x14` in `0x2AAA` and GMT+8;
(5) CE auto-calibration fix (PR #635);
(6) the vendor app's own sensor lifecycle — why "repair an unprovisioned sensor by connecting the vendor app" is not viable (§3.6).

---

## TL;DR

1. **The claim is confirmed — only the location of the gate needed correcting.** The "big blob from the server" = `correctCode` (25 coefficients). The official app writes it into the sensor (at activation, or on its first connect to an unprovisioned sensor), and **without it the sensor refuses RACP history**: `TX 2A52 F1 …` → `RX 2A52 06 00 F1 0A`, zero records. Proven on hardware (§3.5): a sensor activated by OUR app never served a single history record; a sensor activated by the official app did.
2. **The exact tell-tale is F6:** `RX 1C F6 01 xx xx`; `0xFFFF` ⇒ code not written (`hasWriteCorrectCode=false`, `j2.java:718-727`), otherwise written (500/510/530/600 observed). All known FFFF sensors were activated with our app (TECNO `01TH04SQ…` Aug 17–18; HONOR `7EC00B8C…` Sep 29); all provisioned ones (F6 ≠ FFFF) had been serviced by the official app.
3. **Second, independent story:** on the PROVISIONED sensor from the chat log (iCGM-t6 `01TH05FE…`, F6=500) history read fine, but exactly **one record** per connection: the driver only catches up its own tail (`from seq=<lastKnown+interval>`) and never re-reads the session. That is driver policy, separate from correctCode.
4. **Side finding (important for the driver):** byte 7 of `0x2AAA` (`0x14` = 20) is a **vendor constant**, not a BT-spec timezone. The vendor SDK always writes `20, 0` there, and when reading it **ignores bytes 7–8** and interprets the wall clock as **GMT+8**. Our parser honestly reads `0x14` as +5:00 → session start shifts by (activation zone − 5h); in the August log that is exactly +3 hours (`drift=177..179min`) and a permanent `Distrusting session timeline`.
5. **No repair path for an unprovisioned sensor.** "Connect the vendor app once to provision it" is withdrawn (§3.6): the vendor app gates on its cloud, does not adopt unknown sensors, and its sensor-lifecycle operations are unbind/terminate flows (owner's note: it would just kill the *wrong* sensor). The only working order is: **vendor app first, Juggluco after**.
6. **"Pure iCan" app examined (2026-10-18).** The current build (`com.sinocare.ican.health.ce` 01.17.00.19) carries the same `com.sinocare.cgm.cgm_sdk` (JNI bridge `libsinoCgmSdk.so` → `AlgManager`), but its Java side is ijiami-packed; what is readable is the Flutter layer, which we have now fully disassembled (§9). It shows the app's cloud contract: `SinoCgmService` = `get-user-without-login` + `country/setting/detail` + **`user/active`**; the other endpoints are account re-link/unlink only. Sensor adoption/provisioning is nowhere in Dart. On a rooted phone the app kills itself within seconds (anti-tamper, §3.6), so the BLE flow itself can't be watched live.

---

## 1. What `correctCode` is

* `CodeValueBean` — JSON with exactly 25 float fields `x1..x25` (declaration order matches the JSON found in the official app's heap).
* It is `SdkCgmDeviceInfo.correctCode`; it arrives **from the server**:
  `GET sino-deviceaccess/sdk/cgm-code-encrypt` (`deviceSn`, `key`, `timestamp`)
  → `encryptedKey` (RSA, key in `network/f.java`) + `encryptedValue` (AES-CBC, IV = `key[0..16]`)
  → native transform → JSON → `parameterCode`.
  Vendor logs (`network/c.java`): `encryptedKey:…, decryptAesKey:…, encryptedValue:…, parameterCode:…`, retries `request code Parameter, retryCount:`.
* Local fallback when the server is unreachable: `SN.substring(9,11)` (or 10,12) + 25 SN-derived values (native). For our SN `8760080A2604A` those slices would give `"60"`/`"04"`, but the heap contained real server values — i.e. our sensor used the **server** code.
* Chunk assembly and AES live in the fully VMP-native `C0532g` (ijiami `NCall`) — not statically readable; from Java only the order and number of chunks are visible.

### Reading code state: F6

```
TX h=0x0024: F6
RX h=0x0024: 1C F6 01 lo hi          ; i8 = (hi<<8)|lo
0xFFFF ⇒ hasWriteCorrectCode = false ; otherwise true, and the activation-time write is skipped
```

Decoded in `j2.java` (case 246 = 0xF6 → "read code response"):

```java
int i8 = ((value[4] & 0xFF) << 8) | (value[3] & 0xFF);
if (i8 == 65535) {
    f4734L = false;
    setHasWriteCorrectCode(false);          // code not written
} else {
    f4734L = true;
    setHasWriteCorrectCode(true);           // code written
}
logPrintUtils.i(TAG, ",read code response: " + i8);
```

Write trigger (start-time callback, `j2.java` ~930): if `launcherState == 0` and `!hasWriteCorrectCode` → `a(0, true)` → fetches `correctCode` (server `cgm-code-encrypt`; for some launchers a fallback from `SN.substring(9,11)/(10,12)`) and writes chunks (`j2.java:1760-1830`; vendor log `writeCorrectCode: index: …, full code: …`).

Observed values (F6 ≠ 0xFFFF = provisioned):

* our i3 (capture `re-work/05a-dump/gatt_auth_log.txt`, official app via LSPosed): `1c f6 01 58 02` → **0x0258 = 600**;
* iCGM-t6 `01TH05FE…` (Aug 20, official activation): `1c f6 01 f4 01` → **0x01F4 = 500**;
* realme RMX5566 (Aug 17): `01 FE` = 510; motorola edge 50 fusion (Aug 7): `12 02` = 530;
* **`FF FF` (not provisioned)**: TECNO `01TH04SQ…` (Aug 17–18, activated with our app) and HONOR `7EC00B8C…` (Sep 29) — see §3.5.

The exact meaning of a non-FFFF value is unknown (a marker of the written parameter set; the sensor never returns the coefficients themselves).

### Writing the code (when 0xFFFF)

1. `F7` `initParameter`: chunks, count = `bean.a.a(sn) ? 2 : bean.a.b(sn) ? 4 : 2`;
2. `F2` `correctCode`: chunks, count = `f4730H ? 4 : 2`, where `f4730H` = `launcherVersion.substring(14,16)` (hex) == 1;
3. each chunk is a write-type 2 to `f4739Q`; every `1C F2`/`1C F7` ack advances the counter; final ack → `setHasWriteCorrectCode(true)`.

---

## 2. Why history is "only after the code" — exact spot in the SDK

`f1.java`:

* **`t(deviceInfo)`** = `onWriteCorrectCodeCompleted` (`f1.java:202-214`):
  ```java
  if (getStartTime() > 0 && !getHasGluDataCompleted()) {
      n(deviceInfo);              // onPrepareReadHistoryData
  } else if (getStartTime() <= 0 || getHasOriginalDataCompleted()) {
      return;
  } else {
      o(deviceInfo);              // original data
  }
  ```
* **`n(deviceInfo)`** = `onPrepareReadHistoryData` (`f1.java:148-157`):
  ```java
  logPrintUtils.i(TAG, "onPrepareReadHistoryData cgmDeviceInfo: " + sn
        + ", isAutoReadHistory:" + CgmDeviceConfig.INSTANCE.isAutoReadHistory());
  if (CgmDeviceConfig.INSTANCE.isAutoReadHistory()) {
      launch(new l0(this, deviceInfo, null));   // native history-reading coroutine
  }
  ```

So the SDK will **not** send RACP history before `onWriteCorrectCodeCompleted`: `t()` is invoked when the `correctCode` write completes, meaning on a "bare" sensor it is F7+F2 first, then history. This is exactly what the claim describes.

Useful nearby bits (for history):
* `j2.a(int index)` (`readHistoryGluRecord`): first a **read** of the characteristic with the current index (`f4737O`), `i4 = (value[1]<<8)+value[0]`; if `i4 < index` → `onNoNeedToRead`; otherwise writes RACP `C0532g.a(index, true, modern)` (write-type 2) to `f4740R`;
* after the write → `f1.a(deviceInfo, index, currentEndIndex)`;
* `setEndTime(gluSn * 60000 + startTime)` (`f1.java:515`) — **1 `gluSn` unit = 1 minute**;
* default interval 3 min: `j2` uses `launcherVersion.substring(16,18)`; if ≥ 60 → `/60` (else 3);
* the modern command family is chosen by MTU: `i3 < 247 ⇒ f4728F = false` (our driver: `MTU changed to 247; using modern vendor history command family`).

---

## 3. August logs (TECNO LI7): iCGM-t6 sensor

Files (copies — `re-research/ican-2026-08/`):

| file | device | contents |
|---|---|---|
| `juggluco-trace-20260820-080139-tecno.log` | TECNO LI7, Android 15 | 07:49–08:01, live only, no history request at all |
| `juggluco-trace-20260820-083057-tecno.log` | TECNO LI7 | 07:49–08:30, same capture + manual reconnect at 08:26 and a history cycle |
| `juggluco-trace-20260820-141034-pixel-anytime.log` | Google Pixel 8 Pro, Android 17 | different driver (**Anytime CT5**), for history-behaviour comparison |
| `chat-2026-08-20-history.png` | — | the Aug 20 chat (Maxim Isaev / Evgeny Y.) this whole story came from |

### 3.1 Hardware and versions (log 083057)

```
Connected to 8C:73:DA:73:CE:24
MTU changed to 247; using modern vendor history command family
WIRE rx read 2A25 30 31 54 48 30 35 46 45 30 30 30 33 30 31 30 31 30 30 30 30 30 30 30 30   → 01TH05FE00030101
WIRE rx read 2A24 69 43 47 4D 2D 74 36                                                      → "iCGM-t6"
WIRE rx read 2A28 56 30 31 2E 30 35 2E 30 30 2E 30 30 5F 42 30 30 30 31                     → "V01.05.00.00_B0001"
WIRE rx read 2A26 34 2E 32 2E 33                                                            → "4.2.3"
WIRE rx read 2AAB E0 01                                                                     → 480h (20 days)
setSensorWearDays: 01TH05FE00030101 days=28 wear=40320   (driver declared 28 days)
WIRE rx read 2AA8 01 40 00 23 FF FF  → cgmFeature=0x004001, calibrationSupported=true, multiSession=true, type=0x3
```
Device advertisement: `deviceName=Sinocare CGM`, GATT name `ICN-ZP1TH05FEE50…`.

### 3.2 Authorization (the important storyline: someone else's userId)

```
WIRE tx write 2AAC F3
WIRE rx notify 2AAC 06 F3 01 F1 18 6B D2                     ; challenge
authUserId=P1TH05FEE505 from source=ZP1TH05FEE505 …          ; driver derived the userId from the name
WIRE tx write 2AAC F4 10 E5 DF … 
WIRE rx notify 2AAC 1C F4 02 85 31 66 EB BB 41 16 C1 35 ED 64 96 97 79 66 53 01   ; REJECT 0x02 + payload
Persisted recovered userId for 01TH05FE00030101: len=12, suffix=6753
Auth rejected (0x02) but recovered realUserId=len=12, suffix=6753 — retrying auth
WIRE tx write 2AAC F3 → 06 F3 01 F1 18 6B D2
authUserId=851361226753 from source=851361226753 …
WIRE tx write 2AAC F4 10 F7 0C …
WIRE rx notify 2AAC 1C F4 01                                 ; OK
WIRE tx write 2AAC F6 → 1C F6 01 F4 01                       ; correctCode marker = 500
```

The reject payload is AES-CBC (key = bundled auth key, IV from the challenge) and contains the ASCII userId already stored in the sensor (`ICanHealthParser.recoverUserIdFromAuthReject`). The driver decrypted it, remembered it, and retried — the working scenario for "sensor activated by another account".

### 3.3 History: what actually happened

```
WIRE rx read 2AA9 45 03 00 00 00 01          ; CGM status: offset=837, launcherState=0x00
WIRE rx read 2AAA EA 07 08 13 12 1B 07 14 00 ; session start
Session start epochMs=1787146027000
Requesting glucose history from seq=837 using F1 command family
   (after authenticated session metadata refresh (after fresh status read))
WIRE tx write 2A52 F1 03 02 01 45 03         ; F1 03 02 01 <offset LE>=0x0345=837
WIRE rx notify 2AA7 53 4E 14 2A 52 02 …      ; "SN" 0x14 "2A52" 02 … (one record)
Parsed SN glucose history batch base=837 records=1 imported=1 skippedUnanchored=0
WIRE rx notify 2A52 06 00 F1 01              ; RACP transfer complete
Persisting 1 direct RACP history readings to Room
Authenticated glucose-history cycle completed with 1 imported records
```

Request bytes (`ICanHealthConstants.buildRacpReportFromGlucose`): `F1` = modern prefix (the F1 family is selected at MTU ≥ 247), `03` = "from offset", `02` = glucose history, `01`, then offset LE (837).

Live stream: one notification every 3 minutes, `seq` +3 per notification (1 unit = 1 min), `flags` 0xC7 → later 0x71, `status` 0x73 → 0x19. Values were stored as usual (`savepollallIDsonly count=799<id=801` … `count=837`).

**Why "it doesn't load history":**
* history is requested **only during connection setup** and only "from our tail": the resolver takes `latestKnown = max(lastHandledLiveSequence, persistedTail)` and returns `+readingInterval`; at reconnect time the driver's tail was 834 (the `2AA9` status said 837) ⇒ request `from seq=837`;
* the sensor returns exactly what was asked: one record (837) + `06 00 F1 01`;
* older session records (0…833) are **never** re-read once the driver's tail is considered current. The hole "behind" the live edge is not healable this way (the Aug 20 build already contained the Aug 7 commit `4bdd48986`, which re-arms backfill on a *live-counter jump*, but not on a DB hole);
* the 08:01 log (first 12 minutes) contains no history requests at all, and the connect/authorization are not visible: logcat was started after the connect (log starts 07:49:53, `startlogcat` at 07:51:41, first `I/ICanHealth` at 07:52+). So "what exactly the user saw as 'no history'" cannot be reliably reconstructed from these logs — only the catch-up mechanism above is visible.

### 3.4 Comparison: the third log (Pixel, Anytime driver, CT5)

```
I/Anytime Connected to D7:6C:7B:B3:68:A3
I/Anytime Starting history backfill (ct5-reconnect-catchup) from id=1667 stopBefore=1668
I/Anytime CT5 history 1667..1667 received: 0 existing, 1 inserted, 0 warm-up/no-glucose
I/Anytime History pull interrupted by disconnect; range kept for the next session
I/Anytime Imported 1 Anytime NATIVE history points into Room (ids=1667..1667 …)
… Reconnect requested: GATT disconnect status=257 (over and over)
```
Same "one record per connection" picture, for a different reason (the sensor drops the link after the first record). Useful as a second example: "1 record" ≠ "history is broken".

---

### 3.5 Sensor started by OUR app: `01TH04SQ00030101` (Aug 17–18) — A/B vs the official one

Found on Oct 18 by scanning every `juggluco-trace*` on disk. Five logs (copies in `ican-2026-08/`):

| file | window |
|---|---|
| `juggluco-trace-20260817-191054-tecno-04SQ-ourstarted.log` | Aug 17 18:53–19:11 |
| `juggluco-trace-20260817-215156-tecno-04SQ-ourstarted.log` | Aug 17, fragment (92 lines) |
| `juggluco-trace-20260817-222906-tecno-04SQ-ourstarted.log` | Aug 17 21:54–22:29 |
| `juggluco-trace-20260818-185912-tecno-04SQ-ourstarted.log` | Aug 18 07:56–08:53 |
| `juggluco-trace-20260818-203321-tecno-04SQ-ourstarted.log` | Aug 18 09:56–10:53 |

Hardware is the same family: `iCGM-t6`, fw `4.2.3`, sw `V01.05.00.00_B0001`, `2AAB` = 480 h, address `8C:73:DA:7C:BB:7A`.
Counter: first log `offset=72` (18:53) ⇒ start ~17:41; last log `offset=1590` (≈26.5 h of life). History: **zero records across all five logs** — not a single `glucose history batch`/`imported`, ~59 read attempts (2+4+20+33).

**Who activated it** — visible from authorization: `authUserId=P1TH04SQE525 from source=ZP1TH04SQE525 (recovered=null configured=null onboarding=…)`, and the sensor accepted our onboarding id **on the first try** (`1C F4 01`). That never happens with a sensor activated by the official app: there our id is rejected first (F4 → `1C F4 02 <payload>`) and the numeric id must be recovered (§3.2). ⇒ this sensor was activated by our scan; the official app never connected to it.

**F6 (correctCode state)** — identical in all four logs that contain it:

```
WIRE tx write 2AAC F6
WIRE rx notify 2AAC 1C F6 01 FF FF
I/ICanHealth Sensor info: 1C F6 01 FF FF
```

**History — refusal after refusal** (cycle from the Aug 18 07:56 log, repeats on every connect):

```
I/ICanHealth Requesting full iCan glucose history from first slot for timezone repair
I/ICanHealth Requesting glucose history from seq=3 using F1 command family (after first confirmed live reading (after fresh status read))
WIRE tx write 2A52 F1 03 02 01 03 00
WIRE rx notify 2A52 06 00 F1 0A              ← refusal
W/ICanHealth Modern glucose-history RACP command failed; retrying with legacy command family
WIRE tx write 2A52 01 03 02 01 03 00
WIRE rx notify 2A52 06 00 01 0A              ← refusal
W/ICanHealth Filtered glucose-history rejected on both prefixes; falling back to report-all
WIRE tx write 2A52 F1 01 02 ; RX 06 00 F1 0A
WIRE tx write 2A52 01 01 02 ; RX 06 00 01 0A
W/ICanHealth RACP history request failed; will retry on a later authenticated reconnect
```

**All four** variants are rejected: both families (F1 and 01) and even report-all. This is not an "empty range" — by then the sensor had 855+ minutes of live records; the live stream runs fine (`savepollallIDsonly count=856<id=858`, glucose 7.2 mmol/L, etc.). `from seq=3` because the pending timezone repair schedules a full re-pull "from first slot" every time, and it can never complete (no data ever arrives).

**A/B across the two sensors from the same TECNO LI7:**

| | `01TH04SQ…` (activated with our app) | `01TH05FE…` (activated with the official app) |
|---|---|---|
| period | Aug 17–18 | Aug 19–20 |
| F4 on connect | our id accepted immediately | our id rejected → recovered `851361226753` → accepted |
| F6 | `FF FF` (code not written) | `F4 01` = 500 (written) |
| history | 59 attempts, **0 records**, all answers `06 00 F1 0A` / `06 00 01 0A` | 1 request → `06 00 F1 01` (OK), `cycle completed with 1 imported records` |

Same driver, same phone — the only difference is who activated the sensor.

**Second independent case (Sep 29, HONOR PTP-N49, driver v1.2.2):** sensor `7EC00B8C00070000` (`onboarding=7EC00B8CE493A` — the same "activated with our app" fingerprint), F6 = `FF FF`, and the same picture: `F1 03 02 01 03 00` → `06 00 F1 0A`, `01 …` → `06 00 01 0A`, zero imports. Copies — `juggluco-trace-20260929-*-honor-7EC0-ourstarted.log`.

**Mechanics (decompilation, see §1):** `j2.java` case 246 (0xF6): `0xFFFF ⇒ setHasWriteCorrectCode(false)`. On connect, if `launcherState == 0` and `!hasWriteCorrectCode`, the SDK calls `a(0, true)` → fetches `correctCode` (server / SN fallback) and writes chunks. Our driver never performs those writes (on 2AAC only F3/F4/F6; F2/F7 — zero occurrences in the logs) and cannot: the vendor key/credentials + VMP-native chunker. A sensor that never received the code **does not serve** RACP history.

**Consequences:**

* "History never worked on the first sensor" is exactly this case: a sensor activated by our app serves no history at all, not a single record, no matter how many reconnects. It is neither the "one record" policy nor a parser bug.
* Sensors that have been serviced by the official app (F6 ≠ FFFF) read fine in our app.
* **No "repair path" — withdrawn prediction (2026-10-18).** ~~A single connect of the official app should be enough to provision an unprovisioned sensor.~~ Technically the app *could* write the code, but in its own implementation it does not adopt a sensor it didn't activate: it gates on its cloud, and its sensor-lifecycle operations are unbinds/terminations — per the owner's note it would just kill the *wrong* sensor (§3.6). Not to be tested on a live sensor.

### 3.6 Why "connect the vendor app once" won't repair an unprovisioned sensor

Owner's correction (2026-10-18): technically the app could write the `correctCode`, but in their implementation it would just kill the *wrong* sensor — not provision the intended one. What we can see in the apps that matches:

**The current pure-iCan build** (`com.sinocare.ican.health.ce`, 01.17.00.19, versionCode 39, Google-Play-signed; the reference artifact):

* It is the **same SDK family** as every other build: `lib/arm64-v8a/libsinoCgmSdk.so` is only a 36 KB JNI bridge exposing `com.sinocare.cgm.cgm_sdk.util.AlgManager` (`planCalibJNI`, `startCalibJNI` — the calibration library of PR #635). The BLE state machine is the same `j2.java`/`f1.java` family documented in §1–§2.
* Unlike the RU build, the Java side is **packed** here (ijiami DexHelper: 87 KB shell `classes.dex`, `libDexHelper.so`/`libdexjni.so`, 27.7 MB `libdatajar.so` with an ~10 MB high-entropy tail; VMP strings `class_defs_off`, `classes_to_restore`). The SDK is not statically readable from this APK — the RU build is what let us read it at all.
* The **Flutter layer is not packed** and is now fully disassembled (§9, Dart 3.9.0). It carries the app's cloud contract: `SinoCgmService` has exactly three CGM calls — `sino-cgm/v1/user/get-user-without-login`, `sino-cgm/v1/country/setting/detail`, **`sino-cgm/v1/user/active`** — and the rest of the account side (`rebindsSave`, `rebindsSaveAccount`, `unbindAccount` → `sino-ican/v1/user/rebind/save`, `…/save-account`, `user/unbind`) is *account* re-linking/unlinking (resolved via the shipped i18n files: "Phone number unlinked successfully."), not sensors. I.e. the Dart layer only ever asks the cloud about the *currently active sensor*; the CGM UI/BLE work itself lives in the packed native layer. The i18n assets also carry the same binding constraint as the RU build's `DEVICE_ALREADY_BOUND`: *"Oops! This {v0} is already in use. Please disconnect it from the other account and try again."* — a device is an account-bound object, not something the app adopts ad hoc.
* On a **rooted** phone the app dies 2–10 s after launch: the Bugly tombstone shows `SIGSEGV SEGV_MAPERR pc=0x97c, sp=0` re-raised through `libBugly.so+0x7f8c` (SI_USER) — an anti-tamper kill, so the CGM flow cannot even be watched live on this device.

**For comparison, the RU build** (`ru.luch.testcgm`, `luch-ru-cgm-0.1.0-vc39`, plaintext Java, same `cgm_sdk`): it wraps the SDK in its own cloud layer (`luch-cgm.ru`, `luch.testdevweb.ru/api/v1`) with a device-binding model (`luch.sensor.bound_sn`/`bound_token`, `DEVICE_ALREADY_BOUND`, `_isAlreadyBoundError`) and an explicit gate before any BLE work — `sensor.gate: cloud rejected sensor (terminated/not found)`, `bind.cleanup_after_cloud_reject`; the SDK itself fetches credentials first (`sino-deviceaccess/sdk/authentication` → `getDeviceToken`, plus `sdk/check-device`). Its lifecycle operations are terminations/unbinds, not adoption: `endMonitoring` = backend unbind + SDK unbind, `terminateCgm(TerminateType)` → native `h2` (sensor-side case 245, "reset reason response" — an opcode our driver never sends), `_replaceTerminatedSensor`, `terminated_banner`. The start path (`startCgmTest`, `f2.java`) stamps start = now + sensor offset and cannot join a session already in progress — an already-started sensor answers `StartMonitorFailedReason.DUPLICATE_START` (`j2.java`, opcode 0x1A response).

**Takeaway:** a sensor started by our app (F6 = FFFF) cannot be "repaired" by a vendor-app connect; users must activate the sensor with the vendor app **first** and only then use Juggluco.

---

## 4. Why the drift is exactly 3 hours: byte `0x14` and GMT+8

### 4.1 What the log shows

On every reading:
```
W/ICanHealth Distrusting session timeline: start=1787146027000 seq=837
    implies 1787196247000 but the counter was read at 1787185568171 (drift=177min);
    anchoring off the live sequence instead
… later: W/ICanHealth Rejecting future live timestamp seq=840 candidate=1787185748171 now=1787185629898
```
I.e. `start + seq·1min` drifts ~3 hours into the future → the driver stops trusting session time and anchors off the live counter (the safety net from `4bdd48986` "stop the ghost curve", Aug 7 — it did its job).

### 4.2 What we read in `2AAA`

`EA 07 08 13 12 1B 07 14 00` = `2026-08-19 18:27:07`, byte 7 = `0x14`, byte 8 = `0x00`.

Our parser (`ICanHealthParser.parseSessionStartTime`, `toEpochMillis`): byte 7 = `0x14` = 20 (valid per the BT spec, not `-128`) → `+20 × 15 min = +5:00` → `18:27:07 − 5h = 13:27:07 UTC` = `1787146027000` (exactly what the log shows).

### 4.3 What the vendor SDK does

**Writes** (`j2.java:1115`, `c(long)`):
```java
byte[] bArr = {yearLo, yearHi, month, day, hour, min, sec, 20, 0};
```
— `20` and `0` are **hard-coded**, not a timezone.

**Reads** (`BleDataUtilKt.java:15-36`):
```java
int i3 = ((bArr[1] & 0xFF) << 8) + (bArr[0] & 0xFF);   // year LE
if (i3 == 0 || i3 == 65535) return -1L;
int i4 = bArr[2] … i8 = bArr[6];                        // month..second
Calendar calendar = Calendar.getInstance();
TimeZone timeZone = DesugarTimeZone.getTimeZone("GMT+8");   // ← decoded from an XOR string
calendar.setTimeZone(timeZone);
calendar.set(i3, i4 - 1, i5, i6, i7, i8);
calendar.set(14, 0);                                     // milliseconds
return calendar.getTimeInMillis();
```
Bytes 7 and 8 are **never read at all**. (String-decode verification: `{75,73,27,72}`/"20b112" → `yyyy`, `{126,40}`/"3ec134" → `MM`, … `{35,123,103,25,8}`/"d63202" → `GMT+8`.)

### 4.4 Counter check (arithmetic matches within seconds)

* counter-implied start = moment the `2AA9` was read minus 837 min
  = `1787185568 − 837 min` = `Aug 19 18:27:09 +08`;
* wall clock from `2AAA` = `Aug 19 18:27:07`;
* match within ±3 seconds — when interpreted **in the device's zone (+08)**;
* our parser's interpretation (`+05:00`) puts the start 3 hours later → that is the `drift=177..179min`;
* the vendor's `GMT+8` gives the same result as "device zone" because the phone in this log was +0800.

**Conclusion:** the `2AAA` wall clock is recorded in some fixed zone (in this log — +8); byte `0x14` has nothing to do with it. What exactly that zone is remains an **open question**: either the local zone of the phone that activated the sensor (then for +5 sensors the vendor's GMT+8 is off by 3 h and our `+5` is accidentally right), or the vendor always normalizes to GMT+8 (then our `+5` is always wrong outside +8). See §6.

---

## 5. What this means for the driver (ideas; not implemented yet)

1. **`parseSessionStartTime`**: treat byte 7 == `0x14` (and probably `0x00`) as a "vendor constant/unknown" → resolve the wall clock in the device zone (as is already done for `0`/`0x80`) instead of `+5:00`. Tests: `2AAA` with `0x14` and with `0x00` must yield the same epoch, all else equal.
2. **Audit the consumers of `sessionStartEpochMs`**: `observedEndedStatusEndMs()`, `hasCompleteEndedStatusHistory()`, "capped" session-end windows, the one-time `HISTORY_TIMEZONE_REPAIR_GENERATION` — they all compute from session start; a ±3 h error can skew session-end logic.
3. **Warn log when F6 arrives as `0xFFFF`** — after §3.5 this is no longer "optional": it explains half of all "history doesn't load" complaints (sensor activated with our app and never provisioned). Wording like: "sensor has no correct code (F6=0xFFFF); history reads will be refused until the sensor is provisioned by the vendor app". No protocol change, pure diagnostics. (Owner's decision — pending.)
4. **History:** the "catch-up from our tail" does not repair a hole behind the live edge. Worth checking whether we should re-read the range from the last persisted edge (cf. `ffe870f41` + PR #216 "iCan: backfill live tail gaps"). Practical test — on a live sensor request a deliberately old offset (e.g. `from seq=300`) and see whether it serves those records (device-side proof, outside the software).
5. Do not reproduce the `correctCode` write path (F7/F2): `0xFFFF` now shows up regularly — it is the normal state for "activated with our app" — but the chunker and key are server + VMP-native; and "connect the vendor app once later" is not a safe workaround either (§3.6). Users must start the sensor with the vendor app from the beginning.

---

## 6. Open questions

1. **Timezone of the `2AAA` wall clock**: the activating phone's local zone or always GMT+8?
   Test on our i3 (activated with the official app on Mar 29, phone +5): read `2AAA` and the counter (`2AA9`) at the same moment; compare the wall clock with the counter-implied start in +5 and in +8. If it matches in +5 — "phone's local zone"; if in +8 — "vendor normalizes to GMT+8".
   NB: there are no logs of our i3 with the `Distrusting session timeline` line on disk (searched), so the question remains open.
2. **Does the sensor serve arbitrarily old ranges** (request `from seq=300` on a live sensor) — determines whether fixing the "hole behind the tail" is worth anything at all.
3. **Our F4 handshake**: on iCGM-t6 the `ICanHealthParser.recoverUserIdFromAuthReject` path fired — worth checking that the same path also works on "our" sensor with a foreign userId (there are `ICanHealthParserTests`; no live case yet).
4. **August confusion resolved:** the "first" sensor = `01TH04SQ…` (activated with our app; Aug 17–18 logs, history rejected with `0A`), the "working" one from the chat = `01TH05FE…` (activated with the official app). What Maxim saw in the UI cannot be recovered from logs, but the mechanics are clear (§3.5).
5. Vendor app behaviour outside the +8 zone (does it display shifted times?) — not checked.
6. **Repairing an unprovisioned sensor via the vendor app — closed as non-viable (2026-10-18).** The "connect the vendor app once" prediction is withdrawn (§3.6): the app gates on its cloud and terminates sensors rather than adopting unknown ones; per the owner it would kill the *wrong* sensor. Only a sensor started with the vendor app in the first place is a valid setup — nothing to test here.
7. **Exact semantics** of code `0x0A` (`06 00 F1 0A`) and of non-FFFF F6 values (500/510/530/600) — unknown; `0xFFFF` and `0x0A` suffice as "not provisioned" / "refused" markers.
8. **The "kill the wrong sensor" mechanism** (owner's 2026-10-18 note, §3.6) — how exactly would a vendor-app connect terminate the *wrong* sensor? Not verifiable statically in the CE build (native layer packed). Working hypothesis only: the app's lifecycle operations act on the device **bound to the account in its cloud** (`terminateCgm`/`_replaceTerminatedSensor` on the active device), so a connect+terminate flow would act on whatever device that account holds — not necessarily the sensor physically at hand. Needs the owner's confirmation before being written down as fact.

---

## 7. CE auto-calibration fix (PR #635)

* In the `ICanHealthCeCalibration.startCalib` port the auto-calibration formula diverged from `libAlg.so` — in a dead code branch.
* Brought in line with the library's semantics (including the odd cases documented in a comment); the porter's "improvements" removed.
* Tests: `ICanHealthCeCalibrationTests` — 8/8; `:Common:testMobileDebugUnitTest --tests "tk.glucodata.drivers.icanhealth.*"` — BUILD SUCCESSFUL.
* Commit `d8d2d610d` on branch `t3/ican-history-fetch`, PR #635 (open, linked; not watching, not merging).

---

## 8. Artifacts and how to reproduce

**Logs (copies, this folder):** `ican-2026-08/`
* chat of Aug 20, official activation (05FE): `juggluco-trace-20260820-080139-tecno.log`, `juggluco-trace-20260820-083057-tecno.log`;
* our activation (04SQ, Aug 17–18, §3.5): `juggluco-trace-20260817-191054-tecno-04SQ-ourstarted.log`, `…-215156-…`, `…-222906-…`, `…-20260818-185912-…`, `…-20260818-203321-…`;
* second FFFF case (HONOR, Sep 29): `juggluco-trace-20260929-033421-honor-7EC0-ourstarted.log`, `…-173112-…`;
* for comparison: `juggluco-trace-20260820-141034-pixel-anytime.log`, `chat-2026-08-20-history.png`.

Found on Oct 18 by scanning every `juggluco-trace*` on disk; originals — in `~/Downloads/`.

**Vendor decompilation (plaintext SDK):** `/Users/ctqwa/Downloads/luch-ru-cgm-0.1.0-vc39/`
jadx output: `/private/var/folders/r0/sdf4rvb53pv9w_vxk9jf2ywm0000gn/T/opencode/luch-jadx/out/sources/com/sinocare/cgm/cgm_sdk/`
* `j2.java` — BLE state machine (F3/F4/F6/F7/F2, RACP, `writeStartTime` 1115, `readHistoryGluRecord` ~1866-1940, F6 decode case 246 ~718, `a(0,true)` trigger ~930, chunk writer ~1760-1830)
* `f1.java` — callbacks/gates (`n` onPrepareReadHistoryData 148, `t` onWriteCorrectCodeCompleted 202, `setEndTime` 515)
* `BleDataUtilKt.java` — `2AAA` parsing (15-36, GMT+8)
* `bean/a.java`, `bean/CodeValueBean.java` — JSON ↔ 25 floats
* `network/c.java`, `network/f.java` — the `cgm-code-encrypt` server code; `network/a.java` — `getDeviceToken` (`sdk/authentication`); `network/f.java` also lists `sdk/check-device`
* `SinoBleManager.java` — public SDK API (`bindCgm(sn, userId, timeout)`, `startCgmTest`, `terminateCgm(TerminateType, testTime)`, `getShortSnFromActiveCode`); `cgm/TerminateType.java`, `d0.java` (`onDeviceTerminate`), `h2.java` (native terminate) — the terminate path; `j2.java` case 245 — its "reset reason" response
* `AbstractC0533h.java` — date/byte helpers (native, `NCall`)

**iCan CE APK (pure iCan, primary reference):** `re-research/ican-health-01-17-00-19.apk` (`com.sinocare.ican.health.ce`, 01.17.00.19, vc39). Persistent extracts: `re-research/icantmp/ce-20261018/` — Dart sources of the app's kit (`dart-flutter_modular_base/`), `pp.txt`/`objs.txt`/`blutter_frida.js`, `fix_ican_dynsym.py` (SHT_DYNSYM repair), `libapp_fixed.so` (blutter-ready input), `libsinoCgmSdk.so`, `ce-lang-en.json`, `blutter_ce_run4.log`. Full temp work dir: `$TMP/icanfresh/` (`/private/var/folders/r0/sdf4rvb53pv9w_vxk9jf2ywm0000gn/T/opencode/icanfresh`): extracted `lib/arm64-v8a/`, `blutter_ce_out4/` (complete disassembly), `ce_maps.txt`, `ce_shot*.png`, `tombstone_latest.txt` (anti-tamper crash). Also inside the APK: an `assets/agp/` web bundle (in-app page) — not analysed. NB: `dexA.bin`/`dexB.bin` in the same temp dir are from the unrelated AiDEX X app (`com.microtech.aidexx`) — ignored.

**Vendor RU-app Dart snapshot:** `~/Downloads/luch-ru-cgm-0.1.0-vc39/lib/arm64-v8a/libapp.so` → `strings -a` dump at `re-research/icantmp/luch-app-libapp-strings-0.1.0-vc39.txt` (flow/error names quoted in §3.6: `sensor.gate: cloud rejected sensor (terminated/not found)`, `DEVICE_ALREADY_BOUND`, `bind.cleanup_after_cloud_reject`, `endMonitoring`, `luch.sensor.last_terminated_sn`, …).

**Our dumps/data:** `re-work/05a-dump/gatt_auth_log.txt` (live i3 auth capture, `1c f6 01 58 02`), `re-research/icantmp/` (heap: JSON x1..x25), `re-research/project_icanhealth_re.md` (base protocol RE notes).

**Driver:** `Common/src/main/java/tk/glucodata/drivers/icanhealth/{ICanHealthBleManager.kt, ICanHealthParser.kt, ICanHealthConstants.kt}`, tests — `Common/src/test/java/.../icanhealth/`.

**Tests:** `export ANDROID_HOME=~/Library/Android/sdk` → `./gradlew :Common:testMobileDebugUnitTest --tests "tk.glucodata.drivers.icanhealth.*"`

**Commits on the topic (main):** `4bdd48986` (ghost curve + re-arm backfill), `ffe870f41` + PR #216 (live tail gaps), `ec34507ba` (live one hour late + ghost main sensor, PR #600), `d8d2d610d` (calibration, PR #635).

---

## 9. Unpacking the CE app — tooling diary (2026-10-18)

**Only the Flutter part is readable.** The Java/Kotlin side is ijiami-packed (shell `classes.dex` → DexHelper + JNI; payload in `libdatajar.so`; no `dex` magic statically, VMP strings `class_defs_off`/`classes_to_restore`), and the `libsinoCgmSdk.so` bridge only exposes `AlgManager` JNI. The Dart layer contains no CGM/BLE logic — account/events/nav only, plus a Pigeon `FlutterNavigatorApi` bridge to the native screens. The APK's own assets confirm the device coverage: connect animations `device_connect_i3.json`, `device_connect_i6.json`, `device_connect_i7.json` (i.e. our sensor family), plus a shipped per-language i18n tree (`assets/flutter_assets/assets/lang/*.json`).

**`libapp.so` is "section-header-mangled".** A 4-entry section header table with wiped names, `e_shstrndx=4` (out of range), and — the blocker — the `SHT_DYNSYM` entry overwritten by a decoy `SHT_NOBITS` entry. The symbol **bytes** are intact: six 24-byte `Elf64_Sym` records at file offset `0x270`, immediately after the `SHT_STRTAB` at `0x1e8` that still carries the `_kDartVmSnapshotData` / `_kDartIsolateSnapshotData` / `…Instructions` / `…BuildId` names.

Fixes (persisted in `re-research/icantmp/ce-20261018/` resp. the blutter checkout):
* `fix_ican_dynsym.py` rewrites the decoy entry back to a real DYNSYM header (`type=11, addr=off=0x270, size=0x90, link=strtab(2), info=1, entsize=24`) — a 9-field patch in a copy of the `.so`. Stock blutter then works end-to-end.
* blutter's `extract_dart_info.py` (`/Users/ctqwa/Dev/Apple/iglucco/blutter/`; backup saved as `extract_dart_info.py.orig`) is made tolerant for future files: `_kDartVmSnapshotData` is found by section **type** (or by the strtab that contains the name, parsing symbols right after it), and `extract_libflutter_info` scans the whole file when `.rodata` is missing (`libflutter.so` has the same wiped table). Note: LIEF (`lief.write`, `lief.ELF.Builder`) does **not** rebuild these names/sections.
* Result: `blutter_ce_out4/` — full Dart 3.9.0 disassembly (`asm/`, `pp.txt`, `objs.txt`, `blutter_frida.js`); snapshot hash `97ff04a728735e6b6b098bdf983faaba`, flags `product, no-code_comments, …, compressed-pointers` (identical to the RU build → same engine).
* The RU build's `libapp.so` kept its DYNSYM entry (only names are wiped), so blutter's C++ part accepts it as-is; the Python pre-step there needs the same fallback (or the `--dart-version` bypass).

**Runtime note:** the CE app cannot be watched live on the rooted test device — it dies 2–10 s after launch (Bugly tombstone: `SIGSEGV SEGV_MAPERR pc=0x97c, sp=0`, re-raised via `libBugly.so+0x7f8c`; frida spawn makes it die faster). Magisk denylist/Shamiko entry did not help.
