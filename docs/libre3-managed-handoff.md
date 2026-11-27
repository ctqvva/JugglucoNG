# Libre 3 / 3 Plus driver: test handoff

PR: https://github.com/ctqvva/JugglucoNG/pull/839

Status: the only Libre 3 driver. Abbott's `liblibre3extension.so`, `libcrl_dp.so` and
`libinit.so`, their loader and the proprietary Java driver are removed; there is no
switch back. Software validation is recorded in the PR. No phone, watch or physical
sensor has been used to validate this driver. The steps below are the remaining
end-user acceptance tests, not a claim of success.

Sensors the removed driver was reading move over at the first start after the update,
without a rescan: their native record keeps the BLE address, PIN, start time and wear
length, and the driver authorizes with the downloaded identities. The old record is
finished (its history stays) and the main-sensor role moves with it. A record without
a stored PIN or address needs one NFC scan.

## Setup

1. Install the Mobile APK from the PR's successful **current-head** CI build.
   Record the commit, app version, Android version, sensor model and region.
2. Keep a working independent glucose reader available during this first test.
3. Open **Settings → Libre 3**. This page is available on a fresh
   install without a LibreView account or an existing sensor.
4. Open it while online. It automatically downloads the required
   connection credentials from the pinned public source, decompresses them,
   checks SHA-256 and the certificate/key match, and saves them encrypted on the
   phone. Wait for the saved identity set to appear. If a download fails, check
   internet access and select **Download identities** to retry. Once saved,
   credentials work offline. No manual file or URL is needed for normal setup.
5. Leave **Use LibreView account as receiver ID** enabled for sensors started by
   Juggluco. An absent account ID refuses the scan with the legacy 0xF8 result.
   Only choose this phone's own ID deliberately, for the intended activation.
6. Scan the Libre 3 / 3 Plus sensor with NFC once the credentials are ready.

Do not send identity JSON, private keys, NFC PINs, kAuth, account IDs or unredacted
HCI captures in a public issue. App keys and cached authorization are encrypted
with Android Keystore. Connection secrets and the phone-owned receiver ID are
excluded from settings import and export; Android application backup is disabled.

## End-user acceptance tests

| Scenario | Steps | Expected result / evidence |
|---|---|---|
| Fresh activation (A0) | Scan an unused test sensor, accept the dialog, wait for warmup. | Full authorization completes, current reading appears after warmup. Calendar offer initially checked. Record timing and status. |
| Update from a build with the proprietary driver | Install over a build that was reading a Libre 3, online, without scanning. | The sensor reconnects on this driver after the credential download; the old series ends where the new one starts. The selected Libre 3 keeps the main role. |
| Existing-sensor takeover (A8) | With the matching receiver ID, rescan a sensor started by Juggluco or the official app. | One managed callback. Current and recovered history appear. Calendar offer initially unchecked. |
| Watch direct | With a Libre 3 on the phone, choose direct-to-watch. | The watch receives the pairing, credentials and cached key, connects with the cached key, and the phone yields. |
| Cached reconnect | Move out of range, return; then disconnect/reconnect once in settings. | Re-authorizes using cached kAuth without another NFC scan; no duplicate callbacks or duplicate Room rows. |
| Bluetooth toggle | Turn Bluetooth off for two minutes and on again. | Driver recovers; missing minutes backfill without a cursor jump or an endless setup loop. |
| Process death / reboot | After receiving readings, force-stop and reopen; repeat with reboot. | Sensor record survives, cached authorization reconnects, stored history remains. |
| Long outage | Leave disconnected for at least 30 minutes; reconnect. | Clinical minutes and five-minute gap fillers arrive; finer minute readings remain preferred. Compare Room chart with web-server/Nightscout history if enabled. |
| Delayed live frame followed by replay | Let a live frame arrive late, then recover the next sensor minute after a disconnect. | Both minutes appear in history on the same sensor-time grid. |
| Pause with pending backfill | Pause from Sensors while history is arriving, then use Resume. | The button changes to Resume. Queued rows are stored on resume before their progress is saved. |
| Timeout during recovery | Interrupt Bluetooth during backfill, then reconnect. | Fresh authenticated link retries from stored progress. Late replies cannot settle a later request on the abandoned link. |
| Calibration and exports | Apply a recognizable calibration and compare live vs recovered times in the chart and enabled exports. | Stock values are calibrated once; replay does not change the native calibration flag or apply calibration twice. |
| Health Connect live before backfill | Enable Health Connect export, reconnect after an outage so live arrives first. | Earlier recovered minutes are exported too; later keyed retries update the same records. |
| Health Connect corrected gap filler | Let a five-minute gap filler export before its finer clinical minute arrives. | The corrected value updates the existing Health Connect record; identical replays do not repeatedly rewind export progress. |
| Nightscout corrected gap filler | With Nightscout upload enabled, let a five-minute gap filler upload before its clinical minute arrives. | A new upload carries the corrected value with the same date and identifier. Test both configured API versions. |
| Health Connect during repeated backfill | Keep exports enabled while several recovered batches arrive, including near the end of an export. | Requests made during an export run afterward; repeated cursor rewinds continue automatically. Current-sensor export remains enabled when old-sensor history arrives. |
| Remove a sensor with Health Connect work queued | While another sensor exports, remove the old sensor whose recovered history is waiting. | Retained native readings keep their client IDs; replay updates existing Health Connect records instead of duplicating them. |
| Account lookup while settings is open | Leave the receiver-ID page open while LibreView account lookup completes; also change the account number manually. | The displayed receiver ID refreshes to the value NFC will use. |
| Receiver-ID storage failure | If saving the phone-owned ID fails, check the settings page and retry after restoring storage. | An error is shown with Try again; NFC remains blocked until a durable ID write succeeds. |
| Settings migration storage failure | Upgrade an earlier preview when storage cannot save the migrated settings, then reopen setup and retry after restoring storage. | Setup remains usable with the existing choices; legacy settings remain until the new copy is saved. Settings export refuses an incomplete migration. |
| Remove identities | Remove the identity set; then download or import one in Settings. | All cached kAuth is cleared and nothing downloads on its own. The driver reconnects as soon as a set is installed. |
| Credential download offline | Update or hand over a sensor with no network, then reconnect to the internet. | The driver waits for credentials; the download retries (1, 2, 5, 15, then every 30 minutes) and the sensor connects once it succeeds. |
| LibreView upload | With LibreView upload enabled, run a sensor for a few hours, including a short Bluetooth outage. | LibreView shows a Libre 3 sensor with five-minute history (not 15-minute averages). A migrated sensor's earlier history does not appear twice. |
| Identity loading | Reopen settings with an installed set and enable immediately. | Existing set is retained; it is not replaced by a configured download before loading finishes. |
| Two sensors / replacement | Pair two distinct test sensors and switch the current one. | Separate addresses, keys, history, status and retry state. Removing one leaves the other intact. |
| Day 14 → 15 (3 Plus) | Keep the sensor running across day 14; inspect early and recent history. | New mirror uses 32 days of minute capacity; no day-14 reset of native export history. |
| Rejected certificate / device | If encountered naturally, save redacted status and logs; do not repeatedly pair. | No three-second reconnect loop. A rescan or explicit retry is required. |

For every failure report include the step, expected/observed result, approximate
local time, whether live or backfill was affected, and redacted logcat for
`Libre3BLE`, `Libre3Nfc`, `Libre3Registry`, `Libre3Persistence`, `VirtualGlucose`.
A maintainer can request a privately transferred, redacted HCI trace to verify
CCCD ordering, backfill completion and timestamp alignment.

## Implemented and software-tested boundaries

The package `tk.glucodata.drivers.libre3` owns NFC, GATT serialization, full and
cached authentication, CCM framing, record parsing, reconnect and history import.
It uses platform JCA for ECDH/ECDSA and imported key-pair checks. The only native
services used are the generic managed-sensor mirror and lifecycle services.

- NFC A1/A0/A8 bytes match legacy `dp_activation.hpp` vectors; malformed metadata
  and nonzero implausible activation times are rejected. A zero-time rescan keeps
  the known activation start, grid and progress.
- Full and cached handshakes have simulated-sensor tests, plus captured
  certificate/frame and synthetic crypto vectors. A simulated peer does not prove
  compatibility with a physical sensor's firmware.
- Channels authenticate only under their own legacy descriptor. Glucose uses the
  legacy packed-word quality/range rule. No nominal-wear cutoff suppresses valid
  readings. Warmup does suppress readings; error 5 is display-only, 6/8 terminal,
  and error 7 stays nonterminal until trace evidence establishes its meaning.
- Backfill timeout closes the link. Room success is required before durable
  progress advances; failed writes and failed timestamp queries retain batches.
  Live minutes join the blocking flush so progress cannot precede their storage.
  Live and recovered timestamps use one grid per sensor: the time of its life
  count 0, stored once (`lifeZero.<serial>`) and kept across restarts, NFC
  rescans (one reporting a corrected start), removal and re-add, and the watch
  handoff. Room, native slots, five-minute fill-ins and LibreView record numbers
  all hold minutes at the times it gives. A sensor first paired by this build
  gets activation + 1 min, so life count L is dated when its minute ends, at
  activation + (L + 1) min, when the sensor sends it (traced frames arrive 1-5 s
  before that boundary; the removed native driver dated live minutes at
  arrival). If NFC takeover has no known activation time, the first live frame
  fixes the grid before history recovery starts. A sensor an earlier preview
  build stored keeps that build's grid (its learned "anchor", else the NFC
  start: minute L at start + L min, one minute early), because a replay dated
  by the new rule lands on the next minute's time: native overwrites that
  minute with the replayed value and Room drops the replay or doubles the last
  minute. Their stored readings are not rewritten. When the phone and watch
  store a sensor on different grids, neither is moved: the handoff is refused
  (its backfill marks and fill-ins name minutes by the other grid) and WearSync2
  keeps the other device's readings out, logging why. Chunks carry the sender's
  grid after the triples; for an earlier build that sends none, the grid from
  its last handoff decides. A device with no grid for the sensor yet takes the
  other device's. Pause retains pending batches until Resume can flush them.
- A pause (ownership release, the user's pause, teardown) stops reconnecting,
  never cleanup. A release closes the GATT at once instead of waiting for a
  disconnect report Android may send late or never; a late report finds a
  retired GATT and closes only that one. The setup deadline and the stream
  watchdog drop a paused link too.
- One link at a time: a retired link's operations, control requests and timers
  are cleared on the driver thread before a replacement begins. Connections
  start only there; a link retired from another thread (release, connection
  deadline) queues its teardown ahead of any connection request, so taking a
  sensor back while the driver thread is still writing history starts clean.
- Libre 3 gap fills rewind the Health Connect cursor under the poll-write lock.
  An export acknowledgement cannot overwrite a concurrent rewind; invalidated
  batches retry with stable per-minute client IDs. One worker coalesces pending
  requests per sensor, including requests arriving during an export, and queues
  another pass after the rewind limit. History export does not change which live
  callback owns Health Connect. Client-ID selection reads the retained native
  family's tag, so deleting pairing metadata does not make replay unkeyed.
  Replacing an occupied Libre 3 slot with a corrected glucose value also rewinds
  export progress; an identical value does not invalidate the export batch.
- Successful live native writes wake the Nightscout uploader and run the shared
  managed-reading completion step, including enabled Health Connect export.
- Minute values and gap-filling five-minute values use the stock native mirror
  path, preserving downstream calibration. New Libre 3 shells have 32 days of
  minute capacity, including the full 15-day wear interval without a mirror rebase.
- Remove increments a credential revision under the same lock used by installs
  and kAuth saves. Work begun before removal cannot reinstall or cache credentials;
  active drivers also close their authenticated links.
- Settings strings and the new credential/device rejection statuses are supplied
  for all existing translated locales. Language review by native speakers remains
  useful during acceptance testing.

## Integration limitations and unresolved hardware questions

- **Phone → watch direct takeover** carries the NFC record, backfill progress, the
  identity sets the sensor uses and its cached kAuth in the managed handoff payload
  (`Libre3Persistence.exportForWatch` / `importFromPhone`). Keystore-sealed values
  travel unsealed inside the Wear Data Layer and are sealed again on the watch. A watch
  that was reading a Libre 3 through the removed driver converts its own record the same
  way as the phone and downloads credentials itself if it is online. Not hardware-tested.
- **Lingo:** The managed driver recognizes NFC product type 9 with security version 3.
  Import the matching Lingo app identity in Settings → Libre 3, in the existing identity
  JSON format, before scanning. The import requires the certificate
  and matching P-256 scalar for the Lingo v3 app identity. The certificate must match
  the certificate fingerprint in `Libre3Lingo.kt`. The app keeps the identity and
  cached authorization in the device-local store. The regular Libre 3 identity
  download does not supply it. First pairing uses upstream's receiver ID 1001.
  Live readings prefer the sensor's uncapped glucose, as upstream Juggluco does.
  Its minutes are dated by the Libre 3 life count rule and grid above, with no
  Lingo-specific timing. That is assumed, not verified: the only Lingo capture is
  a single frame without its arrival time, so no multi-frame hardware trace shows
  a Lingo sending minute L at activation + (L + 1) min.
  The source port and decoder tests do not establish fresh activation, takeover from
  Abbott's Lingo app, cached reconnect, or live and historical delivery on hardware.

- **Legacy history continuity:** managed ID is `<serial>`; legacy ID was
  `E07A-XX<serial>`. Existing history remains in its original series. The
  proprietary 149-byte auth blob cannot be converted, so the first connection after
  the update is a full authorization with the downloaded identities.
- **Earlier WIP installs:** a native mirror created by an earlier preview may have
  smaller mapped storage geometry. Writes are bounded to the actual capacity,
  and that mirror is never cleared to enlarge it. Restart the app once after
  upgrading: the native constructor grows Libre 3 poll files to 32 days while
  preserving their existing data. No app-data reset is required.
- **Protocol evidence still needed:** whether patchControl is always the final
  completion signal, ordering relative to the last clinical/historic notification,
  timestamp alignment, regional signing-key families, and link-drop inference of
  identity rejection. Test full auth, A8, Bluetooth recovery and long backfill
  against real traces before treating the driver as production-ready.
- **Trend difference:** the arrow fallback intentionally uses
  `(trend - 3) × 1.3 mg/dL/min`; legacy truncated its rate to roughly ±0.01.
- **Identity source:** the normal setup uses the public `app-identities-1` bundle
  already pinned by [xDrip4iOS Libre3IdentityStore](https://github.com/JohanDegraeve/xdripswift/blob/f7447f1c70a194cee1c9c8eea96ebbadf62027b8/xDrip/BluetoothTransmitter/CGM/Libre/Libre3/Libre3IdentityStore.swift).
  Mirrors are Arweave and Turbo Gateway, transaction
  `qWbA3dXIHU2sU51oYtdYlouSfkCx-GczN2swLQb0upk`; the SHA-256 of the
  decompressed JSON is `ab18ce8bcb978526e102cc5e16440f06d2aee19426c26a15dd0013d66e9995ed`.
  Both compressed download and decompressed output are bounded to 64 KiB;
  decompressor memory is limited to 80 MiB (the upstream XZ file declares a 64 MiB dictionary). The APK contains this metadata,
  rather than the private-key payload. Advanced file import/custom sources and
  the `-Plibre3IdentitySource=...` build override remain available.
- **Removal:** Remove clears cached keys and blocks automatic provisioning until
  an explicit download or import. Reopening the page does not undo it.
- **Exported settings:** the receiver-source choice and custom source metadata
  are in a separate exportable preference file. The receiver ID, PINs, identities
  and cached authorization stay local and are excluded from settings exports.
- **Earlier Health Connect exports:** previews used records without client IDs.
  Stable IDs prevent duplicate keyed retries from this build onward; existing
  unkeyed preview records cannot be retrospectively matched to those IDs.
- **Older Clone peers:** native family 9 requires the four-bit family encoding.
  A pre-fourth-bit build reads its low bits as 1 (MQ); update both peers before
  using Clone with this driver.

## Reproducing software validation

Use Java 21, the Android SDK, initialized `Common/src/main/cpp/libjuice`, and a
separate populated `GRADLE_USER_HOME`. No hardware is needed:

```sh
./gradlew --no-daemon :Common:testMobileDebugUnitTest :Common:testWearDebugUnitTest
./gradlew --no-daemon :Common:assembleMobileDebug :Common:assembleWearDebug -PjugglucoAbi=arm64-v8a
python3 scripts/ui-guardrails.py
git diff --check
```

The full suite needs the Room release fixture from `:Common:kspMobileReleaseKotlin`
for its existing migration tests. Test counts, build outcomes and any environment
limitations belong in the PR's current validation record.
