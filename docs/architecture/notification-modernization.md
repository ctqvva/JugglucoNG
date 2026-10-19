# Notification modernization proposal

Status: startup/timestamp fixes merged in [PR #485](https://github.com/ctqvva/JugglucoNG/pull/485).
[PR #504](https://github.com/ctqvva/JugglucoNG/pull/504) merged only the
bounded lifecycle fixes below. Rendering corrections and native text/vector
presentation are separate follow-ups. No structural track changes are made.

### Current lifecycle slice

- Keep the last real reading and its reading-time header when it becomes stale.
  Show the existing translated stale label; remove current chart, arrow and forecast.
  Freshness deadlines remain best effort during Doze, with reconciliation on resume,
  screen-on and clock changes.
- Remove automatic timeout disappearance only from ongoing phone glucose content.
  Preserve alarm and Wear lifetimes and genuine watch-mirroring delivery.
- Coalesce data/status requests at the first queued one-second deadline, rather than
  restarting the delay on each event. Resolve/render on the notification worker.
- Invalidate superseded startup and visual work; service teardown cancels only work
  owned by that service. Retry failed publication once through quiet reconciliation.
- Keep service status only while existing service/display rules require it. Never
  stop the service implicitly or rebroadcast a reading for a visual refresh.

This PR uses the existing renderer and layouts. Glyph-height and compact-chart
corrections can be reviewed separately, without holding up lifecycle bug fixes.
The maintainer's next presentation recipe keeps DecoratedCustomViewStyle, uses
real TextViews and resource vector arrows, and retains a bitmap chart. Bundled
font support through XML must be checked in the actual System UI host before
choosing a fallback. No animation or native-motion claim is made here.

### Separate presentation corrections

The [sizing follow-up, PR #548](https://github.com/ctqvva/JugglucoNG/pull/548),
keeps the custom value hierarchy and font preferences. It
bounds transported bitmap glyphs with actual font metrics and applies FIT_XY to
the compact chart's 48dp container, while keeping the expanded chart at FIT_CENTER.
Its dedicated phone layouts leave independent alarm and Wear surfaces unchanged.
These are rendering corrections, not a native-motion redesign. Native TextView
and vector-arrow rendering is a further, separately reviewable change.

### Native value/arrow follow-up

The next presentation change keeps the same value hierarchy, peer tint, custom
arrow shapes, chart preferences and DecoratedCustomViewStyle. Values are native
TextViews using platform notification text appearance; arrows are resource vectors
inside RotateDrawables, with independent head-count resources and rotation levels.
The chart remains a bitmap. Unknown primary trend does not hide a known peer trend,
and reapplication clears peer children before inserting the current peers.

A standalone Pixel 8 Pro/API 37 probe renders compact/expanded native values,
rotating/tinted vectors and double arrows; reopening retains its latest value.
The XML bundled-font check used the app's actual IBM Plex file: local inflation
loads it, but System UI substitutes its font. The family preference therefore
uses an app-rendered text-only IBM bitmap (default), while System uses native
TextViews with the device's configured headline family via named TypefaceSpan.
On Pixel this is Google Sans, not generic sans-serif/Roboto. Light System weight
uses the text-only painter so numeric weight does not replace the configured
family. All arrows remain resource vectors. Size/weight preferences and both
settings selectors remain supported; changing a sheet setting refreshes the
notification after persistence.

The production probe confirms IBM/System/IBM switching in the actual shade:
the System crop differs, and switching back reproduces IBM exactly. See the
[reproducible probe and cropped device evidence](../../tools/notification-native-view-probe/README.md).
API 26/34 presenter tests cover restricted hosts and family reapplication, including
clearing hidden bitmap payloads. Phone/Wear debug/release arm64 APK builds include
R8 validation. These checks do not establish full-app lifecycle behavior, internal
animation, TalkBack traversal, light-shade behavior or large accessibility settings.

### No standard text on the ongoing phone notification

[#645](https://github.com/ctqvva/JugglucoNG/issues/645): watch companion apps
such as Haylou Fun forward any notification that has a standard title or text,
treat every update as a new message, and offer no per-channel filter. #548 put the
value and delta into those fields, so the watch buzzed on every reading. The
ongoing phone glucose notification leaves them empty, as it did up to 1.2.2-Alpha.
Its custom views already give TalkBack the value as native text or a content
description, plus the arrow and status. Alarm, stale/status and Wear notifications
keep their standard text, so alarms still reach the watch.

### Compatibility & forwarding settings

The Watch settings screen now lets the user choose what listener apps can read:
a "Via phone notifications" section at the bottom, after the dedicated
integrations (Transport, Wear OS, Garmin). Its "Glucose forwarding" row opens the usual single-choice dialog (Standard Android, Alarms only, Every new
reading, At an interval; `NotificationForwardingSettings.MODE_CHOICES`). At an
interval adds an "Interval" row below it with its own 3/5/10-minute dialog. A choice
applies at once; both rows store one `NotificationForwardingSettings.mode`, and
choosing At an interval again brings back the last interval. The screen's help
explains the modes. Alarms only is the 1.2.5 behaviour. Every mode works only through the standard
title/text of the ongoing notification (81431); there is no second notification.
Its custom views ignore title/text, so the phone looks the same either way. Alarm
notifications are the same under every combination.

The same change took two legacy Juggluco switches off that screen. "Notify" set
`Notify.alertwatch`, which re-alerted the ongoing notification on each reading;
it was never persisted, so it was off again after every process restart. "Separate"
set `Notify.alertseparate`, which `Notify` forces to true at start-up; switching it
off stopped alarm notifications being posted until the next restart. The old
View-based Watch screen and the Wear exchange toggle still expose them.

- **Standard Android** (`notification_standard_text`). When on, every
  post of the ongoing notification has a standard title and text again, as in
  1.2.3–1.2.4. The title is the value(s) and trend glyph, without unit. The text
  is the status line. When off, the fields are absent rather than empty.
  The default is pinned once per installation. A fresh install starts with it on.
  An update starts with it off, because `firstInstallTime != lastUpdateTime`.
  This keeps the 1.2.5 behaviour for people who already rely on #646. A stored
  value is never overwritten.
- **Every new reading / At an interval** (`periodic_glucose_notifications`,
  interval `periodic_glucose_interval_minutes` ∈ {1, 3, 5, 10}, default 5;
  anything else reads as 5). Interval 1 is "Every new reading": each new
  measurement is announced once, with no time spacing. Unlike standard text,
  unrelated refreshes of the notification (repaints, chart or status changes)
  stay without title/text.
  The render asks `PeriodicGlucoseUpdates.claim` whether this post announces the
  displayed reading. Only that post gets the title and, as text, the measurement
  time; every other post, including repaints of the same reading, has none.
  Listener apps forward the announcing post and skip the rest, so the watch gets
  one message per reading or interval. `PeriodicGlucoseNotificationPolicy` announces only a
  reading that is new, fresh by `glucosetimeout` and outside the interval. The
  interval uses elapsed realtime within one boot and falls back to wall time after
  a reboot. The last reading announced is persisted once its post succeeded (a
  render that is superseded or fails to post hands the announcement back, and the
  next render makes it), and readings measured before the process started are a
  baseline, so a restart never replays a cached value. After the clock is set back,
  the markers stop holding new readings back, but the reading last announced is
  never announced again.
  A due reading settles for 2 s before a re-render announces it, because custom
  alerts run after the ongoing notification is posted and a forwarded message
  cannot be taken back. An alarm notification posted for the same measurement
  (native alarms, posted first) or while the reading settled (custom alerts, which
  stamp their own time) counts as that interval's message. Picking one of these
  modes announces the current fresh reading once, without settling. The alarm itself
  is never touched or delayed.
- The two are exclusive by construction: the settings store exactly one mode.
  Rendering also enforces the rule (periodic wins), in case stored values from a
  backup ever conflict.

Device findings so far, from user reports rather than our own testing: Mi Fitness
(Mi Band 10) on 1.2.4 forwarded the ongoing notification only after a disconnect
or an alarm. The forwarded message showed "9,0 / Discovering". Its standard text
was empty except while the status line had content. This suggests Mi Fitness
forwards updates of the ongoing notification but skips ones without body text.
That is why an announcing post always carries the measurement time. Haylou Fun
forwarded every post that had a title (#645). Treat both as hypotheses until
tested on a device.

### Repeated posts of the same reading

Each reading used to post the ongoing notification up to three times: directly,
then a 750 ms interactive follow-up, then a 1 s data-changed reconcile. Listener
apps see every post, so that is up to three forwards per reading when standard
text is on. Two bounded changes reduce this. Both paths still exist.

- The interactive follow-up stands down when a data-changed refresh is already
  queued. That refresh runs after the follow-up would have, so it renders a state
  that is at least as new.
- A deferred refresh skips the post when its content fingerprint
  (`NotificationFingerprint`) equals what the same owner last published. The
  fingerprint covers the render inputs, the chart pixels and the notification
  preferences. The refresh still arms the freshness deadline. Backfill,
  calibration, chart, freshness and preference changes alter the fingerprint, so
  they still post. Any unfingerprinted post resets the record and forces the next
  post. That includes startup placeholders, alarms or loss messages on 81431,
  cancels, a swipe on Android 14+, and the floating overlay service's own post.

Validation includes focused freshness, refresh coalescing, startup supersession,
service ownership and publication-recovery tests on phone and Wear. Full-app
stale transitions and Doze still require device checks; a standalone presentation
preview cannot validate those behaviors.

## Outcome and scope

Make the ongoing phone notification a timely, accessible view of the same
resolved glucose state as the dashboard, with explicit freshness and useful
compact and expanded layouts. It must work while the activity is closed and
after process restart. Preserve ingestion, storage, calibration, units,
sensor policy and independent glucose-alarm delivery.

The current slice advances N1 and the bounded N4 presentation described above.
N2/N3 structural work and N5 remain deferred under D4. The primary surfaces are the dashboard adapter and ongoing
phone notification. WidgetDisplaySource/ExpressiveAppWidget, Floating,
GlucoseUpdateBroadcaster and other notification consumers remain compatibility
surfaces; they are not implicitly migrated by N1.

This plan does not add a journal action. Journal interaction needs its own
serious design review. It does not add a lock-screen privacy preference.

### Maintainer decisions and recommendations

| Choice | Status and scope |
| --- | --- |
| Replace bitmap IBM Plex values | Custom presentation is approved to retain the intended hierarchy and app arrows. Preserve `notification_font_*` preferences; glyph bitmaps remain necessary for bundled IBM Plex in System UI. |
| Lock-screen privacy option | Rejected for this work; preserve existing visibility choices. |
| Journal action | Deferred to a separate interaction design proposal. |
| No-sensor notification | Tentatively supported only as required by actual service and existing display modes; never stop the service implicitly. |
| Replace `setTimeoutAfter` for ongoing phone content | Recommendation below, not a previously approved global timeout change. Preserve alert and Wear lifetime rules; isolate the phone lifetime patch from the safe timestamp/startup subset. |

## Evidence in current source

Paths below are relative to Common/src/.

| Finding | Evidence | Planning consequence |
| --- | --- | --- |
| A non-null snapshot does not establish freshness | Notify.glucosetimeout is 330 seconds. CurrentDisplaySource.resolveCurrentInternal passes it to CurrentGlucoseSource.getFresh, but can then construct a non-null snapshot with source="history" from the 25-minute DisplayTrendSource window. | Apply the shared freshness policy to the resolved reading timestamp after all fallbacks. Test 6–25-minute-old history with no fresh live reading; do not assume stale always means null. |
| Startup has a longer history fallback | getforgroundnotification resolves current, then calls NotificationHistorySource.getDisplayHistory. On phone, HistoryRepository.getHistoryForNotificationForSensor reaches Room through runBlocking before foregroundno calls startForeground. A latest history point may be used when it is within 15 minutes. | Show a short FGS “Restoring readings” placeholder first and restore asynchronously. Do not confuse a stale history fallback with a fresh reading. |
| Restore errors and missing data are collapsed | resolveNotificationCurrentSnapshot catches any Throwable, logs and returns null; CurrentDisplaySource also converts history-query failures to an empty list. showoldglucose and both refresh runnables return for a null/invalid snapshot, but accept a non-null historical one. | Cover both failure modes: a stale historical snapshot accepted as current, and a silent return leaving prior content untouched. Preserve error information in the narrow startup restore path; do not claim null identifies the cause. |
| Ordinary builder lifetime and timestamp are different from alarm behavior | makearrownotification calls setTimeoutAfter(glucosetimeout), then stamps ordinary notif.when with System.currentTimeMillis(). The alarm builder stamps notif.when with glucose.time and posts glucosealarmid. | Record the lifetime change as a recommendation pending device evidence; do not change alarm timeout or Wear auto-cancel as a side effect. |
| Publish target depends on service and variant | fornotify sends glucosealarmid on Wear; on phone it calls startForeground(glucosenotificationid, notif) when keeprunning.theservice exists and otherwise calls notificationManager.notify(glucosenotificationid, notif). foregroundno builds history-backed content before startForeground. | Preserve service readiness, ordinary notify and Wear paths as separate acceptance cases. Never stop a required service implicitly. |
| Status-only refresh does not schedule notification work | UiRefreshBus.requestDataRefresh schedules a debounced refresh; requestStatusRefresh emits StatusOnly and invalidates Floating only. | N1 may add a contained status-to-notification invalidation and tests; do not invent a new state architecture. |
| Data refresh is a resettable trailing debounce | scheduleDataChangedNotificationRefresh removes and reposts a 1,000 ms callback. isSameForegroundGlucose compares only time, primary value and rate. | Bound the refresh policy and expand the render/update reason only when N0 reproduces the gap. |
| Live glucose text is always rasterized | makearrownotification calls drawMultiGlucoseText for both collapsed and expanded values, hides their TextViews and shows bitmaps even when useSystemFont is true. Status labels remain native text. | Native TextView replacement is acceptable where it materially improves accessibility, font scaling or allocation cost. M3 does not require abandoning IBM Plex; retain it where it works and measure the tradeoff. |

The seven synchronous showoldglucose callers are:

1. [DashboardViewModel.kt:1719](../../Common/src/mobile/java/tk/glucodata/ui/viewmodel/DashboardViewModel.kt#L1719),
   notification-chart setting path.
2. [DashboardViewModel.kt:2094](../../Common/src/mobile/java/tk/glucodata/ui/viewmodel/DashboardViewModel.kt#L2094),
   notification-prediction refresh path.
3. [GlucosePaletteState.kt:92](../../Common/src/mobile/java/tk/glucodata/ui/GlucosePaletteState.kt#L92),
   refreshNotification.
4. [ICanHealthBleManager.kt:282](../../Common/src/main/java/tk/glucodata/drivers/icanhealth/ICanHealthBleManager.kt#L282),
   foreground refresh runnable.
5. [OutboundApiJournalSnapshot.kt:102](../../Common/src/mobile/java/tk/glucodata/OutboundApiJournalSnapshot.kt#L102),
   journal-change worker.
6. [OutboundApiJournalSnapshot.kt:252](../../Common/src/mobile/java/tk/glucodata/OutboundApiJournalSnapshot.kt#L252),
   journal refresh path.
7. [Applic.java:1373](../../Common/src/main/java/tk/glucodata/Applic.java#L1373),
   sensor/settings resume path.

The two Notify-internal calls are separate from those seven. Some callers already
run on workers. Guaranteeing off-main rendering for all seven belongs to N3; N1 does
not edit drivers or perform a broad caller migration.

Relevant source links: [Notify.java](../../Common/src/main/java/tk/glucodata/Notify.java),
[UiRefreshBus.kt](../../Common/src/main/java/tk/glucodata/UiRefreshBus.kt),
[CurrentDisplaySource.kt](../../Common/src/main/java/tk/glucodata/CurrentDisplaySource.kt),
[DisplayDataState.kt](../../Common/src/main/java/tk/glucodata/DisplayDataState.kt),
[NotificationHistorySource.kt](../../Common/src/main/java/tk/glucodata/NotificationHistorySource.kt),
[HistoryRepository.kt](../../Common/src/mobile/java/tk/glucodata/data/HistoryRepository.kt),
[GlucoseUpdateBroadcaster.kt](../../Common/src/main/java/tk/glucodata/GlucoseUpdateBroadcaster.kt),
[WidgetDisplaySource.kt](../../Common/src/main/java/tk/glucodata/WidgetDisplaySource.kt).

## Product and lifetime policy

Reading freshness, transport status, service readiness and history-loading
status are independent. A reconnecting sensor can still have a fresh reading;
a Room exception is not an empty history result.

N1 recommendation for review, with device/OEM evidence required before rollout:
the ordinary phone notification should publish a stale state at the 330-second
freshness deadline, retaining the last reading with an explicit age/timestamp
and no current arrow or prediction. Its lifetime should be governed by the
existing display/service modes, rather than allowing setTimeoutAfter(glucosetimeout)
to cancel it while a required service or display mode still requires it. Isolate
that builder-lifetime change from the stale-state patch. If the product keeps a
builder timeout during the experiment, it must be tested as a separate,
observable failure mode. Alarm notification lifetime and Wear auto-cancel remain
unchanged.

The current mode gates are evidence, not a guessed truth table:

| Mode | Current source behavior to preserve during N1 |
| --- | --- |
| Phone showalways=true | normal readings use the ordinary glucose notification; keep it while existing service/display rules require it. |
| Phone showalways=false | Toggling Notify.glucosestatus(false) calls novalue(), which publishes getforgroundnotification() through fornotify; it does not immediately cancel. On a later normalglucose call with waiting=false, alertwatch=false and hasvalue=true, keeprunning.started selects novalue(); otherwise the ordinary ID is canceled. Preserve these distinct transitions, including required FGS attachment. |
| Phone alertwatch=true | a genuine per-reading delivery uses once=false, high priority and alarm category through arrowglucosenotification; preserve its mirroring intent. An age/settings/chart redraw is visual-only and must not create a duplicate alarm-style delivery. |
| No sensor | A service-only ongoing placeholder is tentatively supported when the service is actually required. No notification change may stop that service implicitly. |
| Wear | fornotify uses glucosealarmid, OngoingNotificationAccess and Wear's existing auto-cancel behavior. Do not reuse phone lifetime assumptions. |

The coordinator must carry an explicit update reason/mode: genuine reading
delivery, status, age/stale transition, settings/theme, history/chart or
startup restore. Only the first may preserve alertwatch's per-reading
mirroring intent. Rendering must not store, invent or rebroadcast a glucose
reading. GlucoseUpdateBroadcaster remains a compatibility consumer until a
separate migration proves otherwise.

Android 16 AOSP passes FLAG_FOREGROUND_SERVICE in the mustNotHaveFlags mask,
excluding notifications carrying that flag from timeout cancellation in NotificationManagerService
[source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java#2158).
That is useful evidence for the FGS path, not a blanket Android/OEM product
guarantee; device testing is still required.

## Immediate work: N0 and trimmed N1

### N0: evidence and characterization

Use the following local report keys for the user's 2026-09-27 thread reports;
they are not invented GitHub issue numbers:

- thread-2026-09-27-startup: startup content can be misleading or delayed.
- thread-2026-09-27-lag: ongoing notification may update only after opening the app.
- thread-2026-09-27-stopped-feed: stopped input may leave a fresh-looking value.

GitHub issue [#58](https://github.com/ctqvva/JugglucoNG/issues/58) is an open
Pixel 8/GrapheneOS lag regression target. It does not establish the cause of
the thread reports. Closed issue
[#14](https://github.com/ctqvva/JugglucoNG/issues/14) is a compatibility
reference for disabled glucoseNotification breaking glucoseAlarm/band mirroring;
it is not evidence to change alert behavior.

Characterize cold start, process recreation, no sensor, stopped feed, status-only
changes, history catch-up, screen off/Doze, resume and recovery with a fake
clock and recording publisher. Capture resolver outcome, source timestamp,
notification ID, post/startForeground path, visible-shade latency and
duplicate/broadcast side effects. Reproduce each report or label it
unobserved. Pin current behavior before moving code.

### N1: contained reliability fix

N1 may include only these behavior fixes and their focused tests:

1. Post a minimal FGS startup header (“Restoring readings”) early, then resolve
   Room/history asynchronously. Replace it with current, stale, waiting,
   no-sensor or temporary-unavailable text after restore. The header must not
   claim a sensor connection or display an empty grid as data.
2. Set Notification.when from the actual resolved reading timestamp using
   setWhen(reading.timeMillis) and setShowWhen(true) for both fresh and stale
   reading-bearing notifications; remove the later render-time overwrite.
   Restoring/waiting/no-sensor content without a reading must not fabricate a
   reading timestamp. Test builder metadata separately from custom labels.
3. Classify freshness from that timestamp after live and history fallbacks,
   including non-null historical snapshots. Schedule/reconcile the transition
   at the shared 330-second freshness boundary even without a new event. Retain
   truthful last-reading content only under the existing mode/service rules
   above; hasvalue alone is not authorization to retain a disabled display.
4. Reconcile on service restart, screen-on/resume and available time-change
   events. Do not promise exact Doze timing. A bounded eventual update is the
   acceptance target.
5. Make status-only refresh schedule the same bounded notification invalidation
   when the existing mode requires it. Keep the current 1-second trailing rate
   limit, but prevent an unbounded reset loop when N0 demonstrates starvation.
6. Fix identity omissions demonstrated by N0 with contained tests. If a complete
   render identity needs the new coordinator, defer that part to N2/N3 rather
   than widening N1. Age, settings and chart redraws must not invoke alertwatch
   new-reading delivery.
7. Keep normal phone notify versus startForeground, Wear glucosealarmid,
   alert timeout and Wear auto-cancel as explicit test cases. Do not touch
   glucose drivers or perform the seven-caller off-main extraction here.

N1 acceptance includes fresh-to-stale with no new input, stale-to-fresh
recovery, a service-only no-sensor case, showalways=false, alertwatch genuine
new-reading versus visual-only refresh, ordinary notify versus startForeground,
Wear timeout/auto-cancel, Doze timestamp handling and resume reconciliation.
OS and OEM timing claims require device evidence. The truthful early FGS header
and reading-time metadata are an independently shippable N1 subset. Pending
lifetime decisions or device evidence must not block that subset; deliver the
phone timeout/lifetime change separately. Test stale historical snapshots at
6, 15 and just under 25 minutes as well as null-current/no-history cases.

## Deferred work under D4 and P5

Structural and behavior changes must not share a PR. N2/N3 and N5 wait for
D4's structural slot; the maintainer-authorized N4 subset above is a feature change
without that structural extraction. The broader proposal remains:

| Step | Deliverable | Acceptance |
| --- | --- | --- |
| N2 structural pilot | Extract ongoing presentation/coordinator/renderer/publisher seams as delegating adapters, with no behavior change. | Characterization parity, startup registration, no new storage owner, phone and Wear checks. |
| N3 shared-state adoption | Dashboard and notification consume the shared resolved state; move the seven showoldglucose callers behind an off-main contract; retain widget, Floating and broadcaster compatibility. | Matching values, units, trend and freshness; delayed history cannot overwrite live state; no extra store/rebroadcast. |
| N4 visual replacement | Improve compact/expanded hierarchy, native text/icons and chart accessibility after N1 is stable. Choose bitmap versus TextView per measured accessibility/performance and IBM Plex compatibility evidence; keep a bounded RemoteViews chart and useful standard notification text. | Units/locales, large fonts, TalkBack, light/dark, compact/expanded and multiple sensors. No journal action, privacy preference or frame-by-frame animation in this step. |
| N5 alert presentation | Apply shared visual vocabulary to alarm cards and actions without changing alert lifetime, sound, DND, retries, alertwatch or Wear behavior. | Cold-start actions and alarm regressions pass; delivery remains independent. |

MetricStyle is not selected for this implementation. The installed API 37 SDK
contains Notification.MetricStyle (javap inspection of
$ANDROID_HOME/platforms/android-37.0/android.jar on 2026-09-27 showed
addMetric, setCriticalMetric and setMetrics). A separate API 37 Pixel preview
confirmed runtime availability, but did not retain the intended custom arrows
and chart presentation. Broader locale and supported-device behavior remain
unvalidated; the preview does not establish compatibility for this product.
See the [MetricStyle reference](https://developer.android.com/reference/android/app/Notification.MetricStyle).
Ordinary RemoteViews cannot host the Compose animation system; use System UI
transitions and avoid frame-by-frame reposting. Live Update promotion remains
an optional eligibility investigation: it prohibits custom RemoteViews and is
subject to user/OEM control. Do not assume CGM qualifies or change alert delivery
to obtain promotion. [Live Update requirements](https://developer.android.com/develop/ui/views/notifications/live-update).

## Validation and evidence gaps

For implementation PRs, add deterministic tests for no sensor, first reading,
restore unavailable/error, stale deadline without events, reconnect, history-only
startup, delayed Room write, backfill, equal-value source handover, peer/status/
unit/mode changes, out-of-order chart work, failed publication and clock
changes. Preserve mg/dL and mmol/L, provenance, gaps, smoothing and calibration;
a display refresh never writes a new reading.

N1 focused tests must cover ordinary notify versus foreground service and Wear
timeout/auto-cancel, alertwatch new-reading versus visual-only refresh,
showalways=false, stale/resume reconciliation and Doze timestamp behavior.
Keep NotificationScreenOnTests, NotificationChartSmoothingTests,
NotificationChartGapTests, NotificationPredictionBatchTests,
AlertNotificationLifetimeTests, AlertNotificationValueTests and
SilentNotificationAlertTests. Device evidence must include a current Pixel and another OEM, Android
12+, screen off, lock screen, Doze, process restart/reboot and denied
notification permission.

Proposed awake targets are hypotheses, not guarantees: p95 essential text within
500 ms of a resolved state and final chart within 1 s on the agreed reference
device. Notification rate limits and a bounded eventual latest update are part
of the contract. Measure app-state-to-notify/startForeground separately from
visible-shade latency, along with chart allocations and wakeups; age-only updates
must not redraw a chart.

Implementation checks follow project CI: full mobile and Wear JVM suites, both
debug variants, and release builds when notification/resources/bridges change.
This documentation-only revision ran no Gradle or device checks.

The modernization is complete when the notification follows the resolved state
with the activity closed, ages honestly when input stops, restores without
misleading startup content, remains readable and accessible, and retains
independent alarm behavior with measured update and allocation evidence.

[custom layouts](https://developer.android.com/develop/ui/views/notifications/custom-notification) |
[RemoteViews](https://developer.android.com/reference/android/widget/RemoteViews) |
[notification updates](https://developer.android.com/develop/ui/compose/notifications/create-notification#Updating)
