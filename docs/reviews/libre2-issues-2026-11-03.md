# Libre 2 issue review, 2026-11-03

Reviewed GitHub issue bodies, discussions and current attachments against main
`86470fba8817a19eb9f2dff654dcf7d733d3a961`, verified against GitHub's main ref.
This review changes no application behavior. Raw diagnostic logs are not included.

The evidence supports a mixture of past application defects, a remaining status
presentation defect, and unresolved Bluetooth failures. It does not establish
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

## Current source defect: enabled streaming can be shown as Connected

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
delivery, preserving warmup, sensor-error and NFC-only behavior.

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

1. Correct the Connected/reception status path described above with route-aware
   regression coverage. Keep last-error history separately labeled.
2. Make the diagnostics export state the recording switch, actual trace time
   range, build identity and retention limits. Add a bounded per-sensor sequence
   of connect/disconnect, service-discovery, login phase, first complete packet
   and recovery reason that is useful even with full trace recording off.
   Avoid authentication payloads and glucose/account data in that sequence.
3. For #773, enable **Debug logs → Trace log → Record diagnostics** before the
   failure. Capture from healthy minute reception through the retry loop and an
   explicitly timed NFC scan, exporting immediately afterward. The critical
   distinction is whether each status-19 link reaches service discovery, starts
   login, enables notifications, or delivers a complete packet. Include the
   sensor security generation without keys or challenge bytes.
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
test, application patch, issue reply, push or PR was performed by this review.
