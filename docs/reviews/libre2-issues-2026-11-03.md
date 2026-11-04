# Libre 2 issue review, 2026-11-03

Reviewed GitHub issue bodies, discussions and current attachments against main
`86470fba8817a19eb9f2dff654dcf7d733d3a961`, verified against GitHub's main ref.
The initial review changed no application behavior. The November 4 follow-up
below adds focused recovery fixes. Raw diagnostic logs are not included.

The evidence supports a mixture of application defects and unresolved Bluetooth
failures. The November 4 capture identifies a healthy-link disconnect and a
duplicate-callback login reset, both addressed by the follow-up patch. The
Connected-status defect is also addressed in the final patch. The evidence does not establish
that Libre 2 glucose noise causes the current connection failures, or that the
remaining failures are all caused by the application or the phone.

## Current reports

### #773: repeated loss and unsuccessful recovery on OnePlus 15 / Libre 2 Plus

[Issue and latest failure report](https://github.com/ctqvva/JugglucoNG/issues/773#issuecomment-6608166156).

- The November 3 BLE-error export identifies 1.2.8 Alpha, Android 17 and OnePlus
  CPH2747. Its retained events show status 8 followed by repeated 147/19 failures
  from 16:21:12 to 16:31:27, at roughly 30-second intervals.
- **Twenty is the retention limit, not a measured total.**
  `BleErrorHistory.kt` keeps at most 20 errors per sensor, 100 globally, for 30
  days. The attachment contains exactly 20 for the current sensor. Earlier
  failures can have been evicted; successful connections are not represented.
- The trace export's newest timestamp is November 3 at 06:19:18 CET, long before
  the afternoon failure. It contains startup records but no detailed Libre 2
  connection sequence for that failure. Its export header identifies the current
  installed version, not the version that produced every historical record.
- The exported logcat ends November 1. It demonstrates fresh GATT creation on
  that date, not the exact November 3 handshake or watchdog behavior.
- The reporter says the problem occurs across three Plus sensors, original
  Juggluco and NG, and OxygenOS and ColorOS. This broadens the suspects to sensor,
  radio and phone behavior but is not a controlled experiment excluding shared
  driver behavior or common settings.

The 150-second deadline from [merged #786](https://github.com/ctqvva/JugglucoNG/pull/786)
is armed on CONNECTED and rearmed by complete 20+18+8-byte packets. It recovers a
connected link that stalls, including a stalled handshake. A DISCONNECTED
callback already takes the fresh-GATT path from #524; the watchdog does not make
an unreachable or repeatedly rejected connection succeed. Complete packets count
before glucose decoding, so invalid glucose alone does not trip this deadline.
The scheduler can fire late during deep sleep; the existing while-idle loss
alarm is the backstop, subject to Android alarm delivery and permissions.

### The positive #773 test is useful but narrower than its description

[VViktorr's report and ZIP](https://github.com/ctqvva/JugglucoNG/issues/773#issuecomment-6563521896)
contain a selected, sanitized trace, not a complete original 24-hour capture.
The README explicitly says the trace starts later than the requested period.

- The trace contains repeated successful recoveries and resumed minute readings.
- It also contains the same alternating 147/19 pattern: on October 31 around
  12:44–12:49 CET, links repeatedly reach CONNECTED, end about three seconds later
  with status 19 and zero packets, then incur a 147 timeout on the next attempt.
  The next glucose reading arrives at 12:50:10, after the previous one at
  12:42:07: a 483-second gap.
- Those short failed links have no service-discovery completion recorded between
  CONNECTED and DISCONNECTED in the selected trace. That suggests a failure
  before the normal login finishes; selection/omission of log entries prevents
  proving exactly how far Android got.
- There are **no recorded stream-watchdog triggers**. This supports eventual
  recovery using the existing fresh-GATT path, not a demonstrated benefit from
  the new silence deadline. It also shows that a 147/19 sequence need not require
  NFC to recover eventually.
- Successful service discovery reports security generation 1. This does not
  validate the precise security path of the affected Plus sensor; its current
  failing handshake is absent from the other reporter's logs.

Do not infer an authentication-key defect from status 19 alone. In particular,
the current native `resetbluetooth` implementation just clears the persisted
streaming-enabled flag and propagates it to backups. The subsequent NFC path
uses that flag to request enabling streaming. Calling it on every failed retry
does not itself refresh Bluetooth authentication or send an NFC command.

### Later November 4 #773 captures: the initial loss is now recorded

The [diagnostic excerpt](https://github.com/ctqvva/JugglucoNG/issues/773#issuecomment-6626020328)
and [healthy-to-failed transition](https://github.com/ctqvva/JugglucoNG/issues/773#issuecomment-6627811406)
supersede the earlier absence of a current failure trace. Both attachments are
selected, redacted timelines rather than complete raw traces.

- The morning excerpt shows 40 status-147 failures and 38 status-19 terminations.
  At 08:41:03 a Gen1 passcode write returns false. The existing code records that
  failure and requests disconnect; a local rejected write is not proof the
  sensor rejected the passcode. Most other short links never report discovery
  completion, so that single write failure cannot explain all of the outage.
- At 09:02:00 a local disconnect and fresh connection precede the recorded NFC
  tag callback at 09:02:01. Discovery and glucose resume at 09:02:02. These
  timestamps do not isolate whether NFC, retry timing or stack state caused
  recovery. The reporter also says earlier NFC scans did not restore streaming.
- The later trace records 80 packets over 4,749 seconds, ending at 10:21:10 with
  status 8, ten seconds after the last packet. This is an actual link-loss
  callback, distinct from #798's alarm-initiated healthy disconnect.
- Through 10:45:38 it records 25 status-147 failures and 24 short connected links
  ending with status 19, zero packets and no service-discovery completion. There
  is no subsequent NFC scan. No duplicate discovery sequence is exposed here.
- The reporter says power-saving options were already disabled. That statement
  alone does not identify the radio/stack cause, but sleep restrictions cannot
  be treated as the established explanation.

The current excerpt shows requests reaching CONNECTED but generally failing
before discovery completes. It does not establish an additional faulty login
operation, prove that immediate direct retries cause the loop, or measure an
alternative retry mode. The patch therefore retains the existing direct-connect
policy. An Android Bluetooth HCI capture spanning healthy reception, the first
status-8 loss and retries would help determine connection/discovery traffic and
link termination reasons; app callbacks alone cannot establish those details.

### #798: mainly nighttime signal loss on OPPO / ColorOS 15

[Issue #798](https://github.com/ctqvva/JugglucoNG/issues/798) identifies 1.2.7 Alpha.
Its BLE-error export contains one loss-of-signal event at 23:43:15 local time.
The attached trace has no Libre2GattCallback sequence explaining that loss.
It does show repeated Satellite meter sessions. Those are a separate confirmed
application problem, addressed by [merged #800](https://github.com/ctqvva/JugglucoNG/pull/800);
that PR explicitly does not fix Libre 2 loss.

Nighttime concentration and the report that charging may prevent the failure
make ColorOS sleep restrictions a reasonable test hypothesis. They do not prove
process suspension or identify it as the cause. A Redmi user also reports loss
in NG and, less often, original Juggluco. Sleep standby optimization should be
tested separately from the ordinary background-activity permission.

**November 4 update:** the [new comment and attachments](https://github.com/ctqvva/JugglucoNG/issues/798#issuecomment-6625368607)
capture the actual failure on 1.2.8 Alpha and establish two application defects.
This supersedes the earlier lack of evidence for this particular event; see the
follow-up below.

### #804: an error label while readings continue

[Issue #804](https://github.com/ctqvva/JugglucoNG/issues/804) identifies 1.2.7 Alpha.
Although the reporter calls the phone a follower, its attached trace actually
contains local Libre 2 characteristic fragments, successful `processStream`
records and glucose publication at minute cadence. No new 147 appears in the
capture. This is evidence of working reception during that capture, with a
retained error label, not evidence of continuing connection timeouts.

Current `SensorCard.kt` already labels retained failures as **Last BLE error**,
dates them, hides them after an hour and suppresses that detail for Clone sources.
Keeping historical errors is intentional. Explaining the route and current
reception separately is still important.

## Reviewed source defect: enabled streaming can be shown as Connected

In the legacy branch of `SensorViewModel.kt`, `isActivelyReceiving` is calculated
from `!isPaused && (nativeStatus.isNotEmpty() || gatt.streamingEnabled())`.
`SuperGattCallback.streamingEnabled()` calls `Natives.askstreamingEnabled`, whose
implementation in `g.cpp` reads the persisted sensor `streamingIsEnabled` flag.
It does not check `hasLocallyConnectedGatt()` or reading freshness.

With an active sensor, empty native status and that flag still set, a current
`Status=147` is excluded from the custom-status branch and the UI falls through
to **Connected**, even when the current local GATT has disconnected. The same
boolean feeds the `streaming` property. This source path is a presentation bug;
it does not explain why Android or the sensor broke the link. A proper change
must distinguish local transport, receipt of fresh readings, and Clone/watch
delivery, preserving warmup, sensor-error and NFC-only behavior. The final patch
uses `hasLocallyConnectedGatt()` for Libre 2's receiving/Connected predicate,
while retaining pause, native warmup/error text, separate Clone/watch status
paths and other legacy drivers' existing native-status rules. A connected GATT
still does not prove that glucose packets are arriving; the packet watchdog
continues to check that separately.

## Other related reports and fixes

| Report | Verified disposition |
| --- | --- |
| [#519](https://github.com/ctqvva/JugglucoNG/issues/519), recovery after range loss | Real application lifecycle defect; [#524](https://github.com/ctqvva/JugglucoNG/pull/524) merged October 8. Fresh bounded GATT attempts and stale-callback guards remain present. |
| [#593](https://github.com/ctqvva/JugglucoNG/issues/593), packets received but live values suppressed | Real WearSync2/native cursor defect; [#603](https://github.com/ctqvva/JugglucoNG/pull/603) merged October 8. Prevents new phone-side corruption; does not rewind existing corruption. |
| [#715](https://github.com/ctqvva/JugglucoNG/issues/715), follower cannot take over BLE | Real Clone ownership/retirement regression; [#733](https://github.com/ctqvva/JugglucoNG/pull/733) merged October 27. Explicit take-over added. Automatic takeover after ten quiet minutes remains ineffective by that PR's stated scope. |
| [#73](https://github.com/ctqvva/JugglucoNG/issues/73), [#134](https://github.com/ctqvva/JugglucoNG/issues/134), [#143](https://github.com/ctqvva/JugglucoNG/issues/143), [#166](https://github.com/ctqvva/JugglucoNG/issues/166), NFC/history gaps and noise | Closed older display/history reports, not evidence of current BLE failure. [#669](https://github.com/ctqvva/JugglucoNG/pull/669), merged October 27, subsequently separated Stream/History, excluded uncompensated NFC trend from those lanes and repaired delayed-history sync. Old screenshots do not validate today's behavior. |
| [#507](https://github.com/ctqvva/JugglucoNG/issues/507), ownership/retry storm | Still open, but [#506](https://github.com/ctqvva/JugglucoNG/pull/506) merged September 29. Shared 15-second peer-standdown confirmation is present. Its retry backoff change was Ottai-specific, not a Libre 2 backoff fix. |
| [#601](https://github.com/ctqvva/JugglucoNG/issues/601), ghost main sensor | Still open, but the follow-up comment retracts the proposed missing removal guard and identifies #600 as fixing the listing problem. The remaining question concerns unexplained directory deletion, not proven Libre 2 reconnection failure. |

## Concrete next work

1. **Completed:** correct the Libre 2 Connected/reception predicate described
   above, with regressions for disconnected, connected-before-flag and paused
   states. Keep last-error history separately labeled.
2. Make the diagnostics export state the recording switch, actual trace time
   range, build identity and retention limits. Add a bounded per-sensor sequence
   of connect/disconnect, service-discovery, login phase, first complete packet
   and recovery reason that is useful even with full trace recording off.
   Avoid authentication payloads and glucose/account data in that sequence.
3. **New app trace received:** #773's later capture now contains the transition.
   An HCI capture is the useful next step for its pre-discovery failure loop.
   Pair it with the app trace and explicitly timed NFC/recovery actions.
4. On ColorOS/OxygenOS, repeat with Sleep standby optimization disabled, then
   compare screen-off/unplugged operation with charging. Change one setting at a
   time, record its state and confirm returned readings, not just a Connected
   label. Exclude other phones/watch apps concurrently holding the sensor.
5. Consider pacing or scan/autoConnect fallback for repeated failures only after
   the capture identifies the failed phase. The current Libre 2 policy always
   uses direct connect and immediately retries after a reported failure. That
   is a plausible improvement area, not a demonstrated cause in these logs.

## Verification and limits

Executed on the reviewed main source:

- `python3 tools/test-libre2-connect.py`: PASS; production lifecycle methods
  against fake Android Bluetooth and scheduler behavior, including the stream
  watchdog, cancellation, ownership and delayed-login guards.
- `python3 tools/test-wear-sync2.py`: all 12 scenarios PASS.

These tests do not exercise a real Android stack, proprietary authentication,
sensor firmware, OS sleep behavior or radio coexistence. No physical sensor
test, application patch, issue reply, push or PR was performed in the initial
November 3 review.

## November 4 follow-up: alarm disconnect and duplicate Gen2 login

The new trace covers a working stream and its failure. Times below use the
reporter's UTC+03:00 timezone. The logcat independently confirms the duplicate
Android callbacks; these are not duplicated lines from our trace exporter.

1. **05:04:54:** a complete packet is processed successfully and glucose is
   published. The loss alarm is rearmed for 05:10:44.
2. **05:05:01:** `LossOfSensorAlarm.onReceive` calls `reconnectall`, which calls
   `Libre2GattCallback.reconnect(now)` directly. Our driver requests disconnect.
   Its link summary reports 45 packets and the last packet six seconds earlier.
   The driver checked only the last failed-glucose time, not the successful
   reading, so the alarm could disconnect a healthy sensor.
3. **05:05:01:** the replacement GATT connects successfully. Android delivers two
   CONNECTED callbacks about 17 ms apart; our driver calls `discoverServices`
   twice. This repeats on subsequent retries.
4. **05:05:14:** Android delivers both successful discovery results. The first
   starts Gen2 security notification setup; its descriptor completion sends the
   security challenge and advances `conphase` to 2. The second discovery calls
   `m2831x` again, resetting `conphase` to 1. Its overlapping descriptor write
   fails. There is then a successful challenge-write completion but no complete
   glucose packet. The unexpected phase reset can prevent the normal challenge
   response from progressing; the capture does not expose a full response.
5. **05:07:31 onward:** the 150-second watchdog is active and repeatedly requests
   fresh GATT recovery. Subsequent links connect and discover services, repeat
   the duplicate-login sequence, and report zero complete packets. There is no
   returned glucose in the capture through export at 05:36.

The initial healthy disconnect is directly demonstrated by source and trace.
The repeated discovery resets are also demonstrated. Hardware confirmation is
still needed to establish that preventing these defects restores this user's
stream, and the logs do not establish the cause of the duplicate Android events.
This is materially stronger evidence for application defects than the original
nighttime-only report and is distinct from #773's recorded 147/19 failure loop.

### Focused changes

- `Libre2GattCallback.reconnect` now requires both successful and unsuccessful
  glucose timestamps to be stale. An already dispatched loss alarm cannot
  disconnect a link that has just delivered glucose. The existing recent-connect
  guard and stale-stream recovery remain in place.
- A repeated successful CONNECTED callback for the current connected GATT is
  ignored before it can cancel a login retry, repeat discovery, reset packet
  counters or extend the stream deadline.
- A repeated successful service-discovery result is ignored once the handshake
  has started. Non-success results still trigger bounded recovery.

The recovery changes are confined to Libre 2. The final patch also corrects the
phone's Libre 2 Connected predicate; diagnostics-export improvements remain
separate work.

### Regression evidence

Added cases to `tools/test-libre2-connect.py`, exercising the production recovery
and callback methods with fake Bluetooth/scheduler/authentication boundaries.
Before their corresponding fixes, the cases failed with:

- `loss alarm disconnected a healthy stream six seconds after glucose`;
- `duplicate CONNECTED restarted service discovery`;
- `duplicate discovery restarted the security handshake`.

The harness also checks recent no-glucose frames, genuinely stale streams,
preserved in-flight login retries, the original silence deadline, and failed
discovery during an active handshake. These tests model login phase transitions;
they do not execute proprietary authentication or a physical Android stack.

Validation after the fixes:

- Standalone `python3 tools/test-libre2-connect.py`: PASS.
- Java 21, isolated Gradle home, `--no-daemon --offline`:
  `:Common:testMobileDebugUnitTest :Common:testWearDebugUnitTest` filtered to
  `Libre2RecoveryTests` and `ConnectModeLeverTests`: BUILD SUCCESSFUL, 6 tests per
  variant, zero failures/errors/skips. Both variants' production Java and Kotlin
  compilation succeeded as prerequisites.
- `git diff --check`: PASS.

The three `LegacySensorReceptionTests` additionally exercise the phone's
receiving predicate. Before its fix, the disconnected-but-enabled case and the
connected-before-flag case failed; the existing other-driver rules passed.
Final targeted Gradle validation includes these tests: 9 phone and 6 Wear tests,
zero failures/errors/skips, with both production variants compiling. The updated
standalone lifecycle harness also replays the new #773 pattern: a real link loss
after recent glucose followed by 24 timeout/short-connection pairs. It verifies
that every transport retires and the healthy-alarm guard does not block recovery
from an actual disconnection; it does not model the cause of those failures.

Validation does not include physical sensor retests, APK/R8 validation or HCI
packet analysis. The patch does not claim to resolve every outage in #773.
