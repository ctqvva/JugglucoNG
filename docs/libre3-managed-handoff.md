# Kotlin-managed Libre 3 / 3 Plus: test handoff

PR: https://github.com/ctqvva/JugglucoNG/pull/839

Status: experimental, off by default. Software validation is recorded in the PR.
No phone, watch or physical sensor has been used to validate this driver. The
steps below are the remaining end-user acceptance tests, not a claim of success.

## Setup

1. Install the Mobile APK from the PR's successful **current-head** CI build.
   Record the commit, app version, Android version, sensor model and region.
2. Keep a working independent glucose reader available during this first test.
3. Open LibreView settings → Kotlin Libre 3 driver. Import an identity JSON file
   you are authorized to use, or enter its HTTPS URL and SHA-256. This app ships
   no identity or source. Format: `{"format":1,"curve":"P-256","identities":[
   {"label":"…","privateKeyHex":"…","certificateHex":"…"}]}`.
4. Confirm that the imported set and version appear before enabling the driver.
   Identity import does not change the sensor. Downloads are bounded to 64 KiB,
   SHA-256 checked before parsing, and installed as immutable versions.
5. Leave **Use LibreView account as receiver ID** enabled for sensors started by
   Juggluco. An absent account ID refuses the scan with the legacy 0xF8 result.
   Only choose this phone's own ID deliberately, for the intended activation.
6. Enable the Kotlin driver and scan the Libre 3 / 3 Plus sensor with NFC.
   Enabling affects new NFC scans; it does not convert existing pairings.

Do not send identity JSON, private keys, NFC PINs, kAuth, account IDs or unredacted
HCI captures in a public issue. App keys and cached authorization are encrypted
with Android Keystore. Libre 3 preferences are excluded from settings import and
export; Android application backup is disabled.

## End-user acceptance tests

| Scenario | Steps | Expected result / evidence |
|---|---|---|
| Fresh activation (A0) | Scan an unused test sensor, accept the dialog, wait for warmup. | Full authorization completes, current reading appears after warmup. Calendar offer initially checked. Record timing and status. |
| Existing-sensor takeover (A8) | With the matching receiver ID, rescan a sensor started by the legacy driver. | One managed callback; legacy callback retired. Current and recovered history appear. Calendar offer initially unchecked. |
| Cached reconnect | Move out of range, return; then disconnect/reconnect once in settings. | Re-authorizes using cached kAuth without another NFC scan; no duplicate callbacks or duplicate Room rows. |
| Bluetooth toggle | Turn Bluetooth off for two minutes and on again. | Driver recovers; missing minutes backfill without a cursor jump or an endless setup loop. |
| Process death / reboot | After receiving readings, force-stop and reopen; repeat with reboot. | Sensor record survives, cached authorization reconnects, stored history remains. |
| Long outage | Leave disconnected for at least 30 minutes; reconnect. | Clinical minutes and five-minute gap fillers arrive; finer minute readings remain preferred. Compare Room chart with web-server/Nightscout history if enabled. |
| Timeout during recovery | Interrupt Bluetooth during backfill, then reconnect. | Fresh authenticated link retries from stored progress. Late replies cannot settle a later request on the abandoned link. |
| Calibration and exports | Apply a recognizable calibration and compare live vs recovered times in the chart and enabled exports. | Stock values are calibrated once; replay does not change the native calibration flag or apply calibration twice. |
| Remove identities | Remove the identity set; then request reconnect. | All cached kAuth is cleared. Driver stops authorizing until an identity is imported and an explicit retry or NFC scan occurs. |
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
  the known activation start, anchor and progress.
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

- **Phone → watch direct takeover is unsupported.** Libre 3 records and
  device-bound Keystore secrets are not transferred by `ManagedSensorHandoff`.
  Direct-routing requests and automatic provisioning are refused for this family.
  Keep this sensor reading on the phone; displaying forwarded phone data on a
  watch is separate. Wear compilation/tests do not establish direct takeover.
- **Legacy history continuity:** managed ID is `<serial>`; legacy ID was
  `E07A-XX<serial>`. Existing history remains in its original series. A8 is needed
  to pair an active legacy sensor; the proprietary native auth blob cannot be
  converted. The new driver retires the legacy callback to avoid competing links.
- **Earlier WIP installs:** a native mirror created by an earlier preview may have
  smaller fixed storage geometry. The new driver does not erase that mirror to
  enlarge it. Use a fresh test pairing/database when testing full-lifetime export
  capacity; keep any existing history before resetting app data.
- **Protocol evidence still needed:** whether patchControl is always the final
  completion signal, ordering relative to the last clinical/historic notification,
  timestamp alignment, regional signing-key families, and link-drop inference of
  identity rejection. Test full auth, A8, Bluetooth recovery and long backfill
  against real traces before treating the driver as production-ready.
- **Trend difference:** the arrow fallback intentionally uses
  `(trend - 3) × 1.3 mg/dL/min`; legacy truncated its rate to roughly ±0.01.
- **Identity source:** no default is shipped. Builds can set the case-sensitive
  `-Plibre3IdentitySource='version|sha256|https://mirror1 https://mirror2'`.
  Newline-separated mirrors are supported and escaped in BuildConfig. A digest
  verifies bytes; it does not establish permission to redistribute key material.

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
