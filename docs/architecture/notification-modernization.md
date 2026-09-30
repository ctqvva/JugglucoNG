# Notification modernization proposal

Status: implementation in progress. The timestamp/async-startup subset merged in
[PR #485](https://github.com/ctqvva/JugglucoNG/pull/485) and received a brief maintainer
device check. The follow-up implements the contained phone lifecycle changes and
phone value presentation below. It does not move the structural track in
[direction.md](direction.md). The source evidence table records the original
review against main at 7b12a40d4, rather than claiming those defects remain unchanged.

### Current implementation slice

The maintainer explicitly requested continuing with lifecycle and visuals after the
startup device check. This authorizes a bounded N1/N4 feature slice without the
N2/N3 coordinator or shared-state extraction. No driver, storage, calibration,
widget, Floating, broadcaster contract, alarm layout or Wear redesign is included.

- Ordinary phone readings have one freshness deadline, based on the actual rendered
  snapshot, including its fallback. At expiry, retain the last value without a visible unit suffix
  with the existing translated stale label and reading-time header; remove the
  current chart/arrow. Handler timing remains best effort during Doze.
- Data/status requests keep the first one-second deadline while queued. Settings,
  screen-on and time changes reconcile the presentation on the notification worker.
  Startup, genuine publications and visual refreshes invalidate superseded work;
  service teardown cancels only the owning service's pending work.
- Remove `setTimeoutAfter` only from ongoing phone glucose content. Preserve alarm
  and Wear lifetime rules, and genuine `alertwatch` delivery. Visual-only updates
  never broadcast a new reading. Display-disabled mode retains service status only
  while the service exists; it does not implicitly stop the service.
- Phone readings use DecoratedCustomViewStyle with dedicated custom compact and
  expanded layouts. A large primary value, smaller identity-tinted peers and their
  app-rendered arrows share one horizontal strip. The chart uses FIT_CENTER and
  receives the full image without picture-template padding. Both compact and
  expanded chart preferences remain available.
- No visible glucose-unit suffix is added to live, fallback or stale phone text.
  Conversion and the configured unit remain unchanged. Stale state still retains
  the actual value and reading timestamp, while removing current arrows/chart.
- Font family, weight and size preferences apply to the phone again. IBM Plex is
  loaded in the app process; the system option resolves the OEM notification family.
  The glyph strip remains a bounded bitmap with accessible formatted-value text,
  because System UI cannot load bundled app fonts or receive Typeface objects.
  Report its render density so sizing honors SP and preference scaling; cap compact
  text at large accessibility sizes to stay inside the host height budget. System
  font weights on API 26/27 use the available regular/medium families; exact numeric
  light-weight selection requires API 28 or later.
- Android provides the notification shell and expansion affordance. Custom content
  cannot promise the standard template's internal element transitions. No app-driven
  animation timer or frame-by-frame notification publishing is introduced.

The maintainer selected this custom presentation after device comparisons showed
that native BigPictureStyle's fixed title size and text arrows did not meet the
intended hierarchy. The native presenter and its picture-padding workaround are
removed. Required validation includes the actual device shade, IBM/system fonts,
multisensor arrows, unitless live/stale text, optional chart modes and large-font
behavior, alongside existing lifecycle tests. Native-template preview evidence from
earlier iterations does not validate this renderer.

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

Optional MetricStyle work is a later experiment, not an N1 dependency. The
installed API 37 SDK contains Notification.MetricStyle (javap inspection of
$ANDROID_HOME/platforms/android-37.0/android.jar on 2026-09-27 showed
addMetric, setCriticalMetric and setMetrics). Runtime availability, layout,
locale/decimal behavior and supported-device behavior remain untested.
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
