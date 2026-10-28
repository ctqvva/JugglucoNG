# Ottai and Syai firmware field test

This implements an experimental alternative to the official-app capture proposed in
[PR #727](https://github.com/ctqvva/JugglucoNG/pull/727). No official app is required on
the test phone. Saved sensor credentials and a signed-in account on the matching backend are required.
CN phone, Ottai Global and Syai accounts can run the experiment. Checks and reports use
that account's backend and issuing identity; firmware is never fetched from another region
as a fallback. Choose
**Fresh sensor** for the preactivation experiment or **Started sensor** for a running-sensor
OTA experiment. Both require the sensor to be disconnected from managed monitoring first.

## Regions and verification

PR #739 is stacked on the CN mobile secure-transport fix in #755. Its test build includes
both changes: ordinary CN account/device lookup, validation and credential bootstrap use
the encrypted channel before the separate OTA flow can run. The supplied fresh-CN trace
`juggluco-trace-20261029-020202.log` shows why this dependency is required: the old
`/deviceBind/list` and `/device/validateDeviceByMacV2` routes both return HTTP 403 with
`NET_SECURITY_PLAINTEXT_ROUTE_FORBIDDEN`. Splitting the reviews must not omit this setup
dependency from the test build. Signed-in CN behavior still needs verification with it included.

New experiments are available in debug and release builds, on the selected sensor card, for
signed-in CN phone, Ottai Global and Syai sessions with saved keys and a BLE address.
An existing unfinished journal remains accessible without account
credentials, so its recovery record can always be discarded. Reopening preselects the saved
Fresh/Started mode. An unreadable journal or a different BLE address exposes only discard
and close, without offering a new experiment. Recovery uses the validated saved package;
it never replaces it with a new cloud offer. Discard finishes even if BLE disconnects while
confirmation is queued. The wizard pauses its scanner and NFC capture while the dialog owns BLE.
A failed API call or `AppDevice_Upgrade_Version` response is not itself a firmware offer:
that code can indicate an app/API compatibility mismatch. Installation requires a successfully
authenticated offer with an actual downloadable package.

The regional transport is verified separately through anonymous encrypted requests. Real
account firmware offers, downloads, activation and physical OTA still need phone testing.
The BLE sequence was recovered from CN evidence; its global/Syai compatibility is a field-test
assumption, not hardware proof. Use Fresh sensor or Started sensor as appropriate; installation
and parameter writes remain separate explicit actions.

Fresh sensor installation requires a target with a supported V1 factory-preparation profile.
An unsupported target such as V2 is rejected with `factory_parameter_profile` before the
journal is created and before any OTA write, including when retrying a saved recovery image.
This prevents a successful flash from leaving activation blocked by an impossible preparation
step. Started sensor mode does not perform factory preparation and keeps its cross-major
update experiment. An existing journal is retained when an unsupported recovery is rejected.

## Test on the phone

1. Enable Android Bluetooth HCI snoop logging if available. This records **our own** traffic;
   it is diagnostic evidence, not an independent official-protocol reference.
2. Open Sensors → add Ottai. Sign in to the matching CN, Ottai Global or Syai account, enter/scan the sensor code, and
   select its actual Bluetooth advertisement. NFC wake may be needed. Let the existing
   credential fetch/bootstrap finish, then export the credentials JSON for recovery.
3. **Do not press the normal Connect/activate button yet.** Choose **Check firmware
   (experimental)** below it, then **Fresh sensor**. This stops the setup scanner and opens a separate BLE client,
   outside the managed-sensor list. It reads software revision `2a28`, checks the sensor's
   auth signature against the saved keys, authenticates, then reads the command status.
   Only status 0–2 can continue.
4. The check uses the actual BLE revision in the CN `/ota/upgradeVersion/judge` request.
   If an update is offered it downloads the package and displays the source/target versions,
   file size and SHA-256. **The check does not start a firmware transfer.**
   The judge and advisory report use the current CN encrypted API prefix
   `/cgm/security/app/server`; the old plaintext route is rejected by the server even over
   HTTPS. [Protocol, provenance and transport verification](ottai-ota-network-security.md).
5. Choose one explicit experiment:
   - **Write factory parameters only** refreshes cloud materials for the read revision,
     writes supported factory parameters, and leaves the firmware as it was.
   - **Install firmware** transfers the offered image. After a successful finish ACK it
     reconnects to the same Bluetooth address, requires the target revision from `2a28`,
     reauthenticates and confirms the sensor is still unactivated. It then waits at
     **Firmware verified**. Choose **Write factory parameters only** and confirm to refresh
     materials and write parameters. Reopening at the saved target also waits for this action;
     it does not write factory parameters or re-send a success report automatically.
6. On **Preparation complete**, close the dialog and use the normal Connect action to
   activate. The existing `maxActive` negotiation still runs there. No firmware-test path
   writes RTC, `maxActive`, retain duration or `ACTIVATE_CMD`.
7. Export the trace log, particularly `OttaiOTA`, `Ottai` and `AUTHWIRE` entries. Record the
   displayed revisions, result, and any diagnostic code. After activation, retain the normal
   activation/status/lifetime-readback logs and first readings for comparison.

Keep the dialog open and the phone near the sensor during installation. It keeps the screen
awake. It is a foreground field test, not a background updater. Android can still stop the
process, so an unfinished attempt is persisted before the first OTA write.

The test connection waits 1.25 seconds after the last MTU callback before service discovery,
matching the normal Ottai driver's handling of duplicate MTU callbacks. A missing MTU callback
falls back after 2.5 seconds; a refused MTU request uses a 300 ms fallback. `OttaiOTA` records
`mtu_requested`, `discovery_requested` and `discovery_callback`; discovery has a separate
20-second timeout. On the initial connection, a stall before discovery precedes authentication
and OTA.

If the check stops with `firmware_profile`, the dialog displays the reported software revision
(up to 64 printable characters). The trace records `software_revision` with the original byte
length and first 64 bytes in hex, including padding. This diagnoses unexpected revision formats;
empty, non-ASCII and control-containing values still stop the check. Software revisions are
vendor strings, not semantic versions: the exact field response
`E1.1.4(V1.7.S2530.1),STDRD` is retained for the account API, display and recovery journal, with only
surrounding padding removed. Unfamiliar printable vendor strings can reach the cloud check.
When understood, the embedded application profile (`V1.7` here) supplies numeric upgrade
ordering and factory-parameter gates; the leading `E1.1.4` does not supply those gates.
For an unfamiliar source or differently labelled builds within the same application profile,
update eligibility comes from the authenticated CN offer; no build-label ordering is guessed.

A bare package target such as `V1.8.0` can match a composite reboot revision containing the
same V profile. This confirms the offered application profile, not an exact build absent from
the package name. A package naming a full composite revision or explicit build must match that
identity; recovery of the old application also retains the full composite source identity.
Factory commands remain separate from the Started sensor OTA experiment.

With one sensor, an OTA followed by parameter writes can establish that combined setup path.
It cannot isolate the effect of firmware from factory provisioning on early readings.

## Test an already-started sensor

If the ordinary check says no update is offered, choose **Recheck with V3 cloud
authentication** in the same dialog. This comparison is available in release builds too:
it sends a fresh sensor challenge to `/cgmAuth/verify` using the captured account, writes
the authenticated server response back over BLE, waits the normal V3 settling interval,
and requests an offer before replacing that server-authenticated BLE session. It then
restores local authentication and checks the sensor state before exposing installation.
It does not call bindV3, activate, or write factory parameters. A package still needs a
separate Install confirmation. Saved-package recovery is excluded from this new check.

The trace contains `cloud_auth_recheck=begin`, `writeback_complete`, `judge`, and
`local_auth_restored`, plus the usual offer flags. This tests whether fresh server-mediated
authentication changes eligibility; it does not assume the backend requires that proof or
that it will offer firmware. Challenges, signatures and server authentication values are
not logged. A failure or closing the dialog retires outstanding callbacks.

Field result from `juggluco-trace-20261028-224815.log.txt`: the started Global sensor
on `E1.2.6(V1.7.SH2616.1),STDRD` reached `writeback_complete` and `judge` three times.
All three authenticated offer responses were `required=false allowed=false image=false`.
Two rechecks also reached `local_auth_restored` and `NO_UPDATE`, retaining active status
3; those complete runs took 29 and 24 seconds. Other rechecks ended in `CLOSED` during
authentication/restoration, without a logged failure. Fresh V3 proof alone did not change
offer eligibility for this sensor/account. This does not establish an activation-state
restriction or parity with the official app's request/client identity. No installation was
offered in these attempts.

1. Export its credentials and any history you want to keep. Disconnect it from monitoring
   while retaining local credentials/history. Stop other apps from reconnecting to it. A
   managed record or callback in this test app still prevents the separate BLE session;
   choosing Started sensor does not take over a live monitoring connection.
2. In Ottai setup select the sensor, choose **Check firmware (experimental)**, then
   **Started sensor**. This mode requires an authenticated command status of **3** before
   the experiment. Fresh, ended and unknown entry states are refused.
3. The baseline displays command status, provisioned lifetime in seconds, latest decoded
   live-record number and RTC seconds. Missing/undecodable optional fields show `—`.
   These are observations, not prerequisites that the update must preserve.
4. Review the offered target/version/digest and choose **Install firmware**. The account backend
   must offer an actual newer package for this sensor; there is no guessed image, downgrade
   or bypass of a cloud refusal.
5. After reboot the target firmware must match `2a28` and the sensor must reauthenticate.
   Post-update command states **0–2 (unactivated/reset), 3 (active), and 4 (ended)** are all
   accepted test outcomes. The dialog and `OttaiOTA` trace show before/after observations.
   A reset, erased readings, changed lifetime or ended state does not fail the experiment.
6. Export the trace before reconnecting monitoring. This mode performs **no automatic
   reactivation, factory-parameter writes, cloud material rebind, local-history rewrite or
   monitoring restart**. Its purpose is to observe the update, including resets. If it reset
   to an unactivated state, fresh-sensor preparation and any subsequent activation are
   separate explicit actions. This test alone does not reconcile a reset sensor's old
   local/cloud history, timestamps or formula for continued monitoring.

The latest decoded record number is a limited continuity observation, not a full history
comparison or proof of a renewed usable lifetime. No requirement to preserve either is
imposed by the test. The existing fresh-sensor experiment still requires status 0–2 and keeps
its separate provisioning flow.

## Transfer and recovery

- The BLE revision is read up to its first NUL terminator. A Global field trace returned
  `E1.2.6(V1.7.SH2616.1),STDRD` followed by `00 47`; bytes after `00` are excluded from
  cloud offers, saved revision identity and reboot verification. The bounded raw hex
  remains in the trace for diagnosis. Empty or malformed revision prefixes still fail.
- CN 1.44's reported sequence is used: control `00`, exact file bytes on the data
  characteristic, then control `06`. Every write uses a response and waits for its callback.
  The finish byte is deliberately **not** guessed again as `03` on failure.
- Each write has a 60 ms delay, a conservative field-test setting rather than recovered
  official pacing. Chunks are at most 244 bytes and never exceed negotiated MTU minus 3;
  four-byte alignment is preserved except for the final chunk.
- Progress counts acknowledged bytes. A rejected write, false Android start result,
  timeout or unexpected disconnect stops the transaction; uncertain chunks are not retried.
- The exact validated package, original/target revisions, sensor ID, Bluetooth address and
  digest are stored atomically under the app's private `files/ottai-ota/` before transfer.
  No download URL or account credentials are placed in this journal.
- Reopen **Check firmware** after an interruption. A bootloader-only GATT can offer
  an explicit restart of that saved package **only on the recorded address**. It never
  scans neighbouring devices or picks a different image. Address changes and a corrupt or
  missing recovery package require investigation, rather than speculative writes.
- An unfinished journal blocks normal connection and activation with a specific recovery
  message. It is cleared after successful Fresh preparation, verified Started completion,
  or an explicit **Discard firmware test** confirmation. Discard first cancels the dialog's
  BLE/worker callbacks, then deletes only that sensor's local package/journal. It is disabled
  during transfer or factory writes. It keeps credentials, readings and other sensors intact.
  It does not restore previous firmware/settings or repair a sensor stuck in the bootloader.
  Failure, missing calibration, changed authentication and unreadable journals still expose
  the discard path; clearing app data is not required. If the old application still boots, the
  separate parameter-only action can finish preparation without completing the update.
- When the old application still boots on the saved source revision, the validated journal
  package is reused without another firmware offer or download. In Fresh sensor mode the
  authenticated parameter-only action remains available, including after a firmware-download
  failure. It still needs the account API to refresh materials and successful factory-write ACKs;
  bootloader-only recovery cannot run that action.
- An OTA success report is attempted only after matching BLE version readback and a fresh
  authenticated status allowed by the selected mode. Report failure does not substitute for sensor evidence.
- This report mirrors the official flow and is advisory. No server acknowledgment gates OTA
  success, activation or journal cleanup. Successful shutdown releases BLE/the handler at once
  and lets queued/running reporting finish with normal HTTP timeouts; failure/cancellation still
  interrupts worker tasks. There is no persistent reporting outbox or report-only recovery journal.
  Whether the backend uses these reports for future update eligibility remains unverified.
- Recovery retains the original experiment mode and started-sensor baseline. Select the same
  mode when reopening it. If the new application already boots after an interrupted finish,
  the matching saved target can be verified without flashing again. Old preactivation
  recovery journals remain readable.

## Validation and remaining hardware questions

Package parsing uses the Silicon Labs GBL v3 layout: 8-byte tag headers, `FC0404FC` end tag,
bounded lengths and the end CRC32. An erased `FF` tail is allowed. SHA-256 identifies the
downloaded bytes; it does not authenticate the manufacturer. Bootloader signature enforcement,
package compatibility and actual Ottai acceptance still require hardware evidence.

Factory provisioning follows #727's reported version gates:

- V1.6+ coefficients: `09 | count | float32 LE values | CRC16-Modbus LE`.
- V1.8 calibration: `0B | AES(session, float64 LE k | float64 LE b)`.
- Missing/nonfinite values stop preparation; no calibration defaults are invented. The
  journal can then be explicitly discarded. In V1.8 both packets are constructed and
  validated before either factory write. No default calibration is invented.
- Refreshed preactivation materials are saved with `activeTimeMs = 0`: the bind response's
  echoed request timestamp is not an activation time. Warmup starts from the later actual
  activation, and corroborated live anchors can then establish the confirmed start.

Only the recovered V1.3–V1.8 profiles and version-named HTTPS `.gbl` offers are accepted.
Download redirects must remain HTTPS; cloud authorization headers are not sent to download
hosts. File size is limited to 2 MiB. Unknown formats fail before transfer.

The local Blutter artifacts cited by #727 were unavailable here. The `06` finish byte,
60 ms pacing, same-address reboot, cloud contract and factory-parameter behavior are therefore
field-test assumptions, not independently confirmed wire facts. A transfer ACK is not a
successful update: target-version readback is required. Existing operation beyond day 15 is
already established; firmware-specific changes to that behavior would be a regression question.

Tests exercise the exact controller used by the session with fake transport completions:
identity/status gates, no implicit installation, acknowledged chunk progression, rejection,
cancellation, duplicate/late callbacks, bootloader recovery, version verification, fresh
materials and parameter gates. They do not establish compatibility with a physical sensor.

References for the generic container/transport, not Ottai-specific opcode semantics:

- [Silicon Labs GBL format definitions](https://github.com/SiliconLabs/gecko_sdk/blob/gsdk_4.4/platform/bootloader/parser/gbl/btl_gbl_format.h)
- [Silicon Labs OTA documentation](https://docs.silabs.com/mcu-bootloader/latest/using-gecko-bootloader-with-bluetooth-apps/03-bluetooth-ota-upgrade)

Firmware files, credentials and raw captures must stay outside the repository.

## Cloud scope

Ordinary CN login, account lookup, binding and activation use the encrypted migration reviewed
in the base [draft PR #755](https://github.com/ctqvva/JugglucoNG/pull/755). The #739 test build
includes both branches so setup is available before the OTA experiment. A signed-in CN account
test with that combined build remains pending. OTA checks, the explicit V3 authentication
comparison, reports and preparation binds use their own captured client. Global/Syai keep
main's regional transport.

Preparation tolerates cloud version metadata that omits the sensor's variant or supplies its
application profile. A conflicting named build or variant is still rejected, and MAC/auth-key
checks remain mandatory. Failure always leaves the explicit discard path available.
