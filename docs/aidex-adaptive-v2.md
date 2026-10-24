# AiDex Adaptive V2 experiment

Status: selectable on the phone as an **experimental** AiDex Auto algorithm.
Stock calibration remains the default. The initial evaluation reduced the reported
factory excursion but did not establish better agreement on the aging sensor.

## Use in the app

Open the AiDex sensor card in an Auto-containing display mode, then **Sensor
algorithm**. Choose **Stock calibration** or **Adaptive V2**. Adaptive V2 also
shows the **Calibration** switch for reference correction inside the estimator,
and the existing uncertainty-ribbon switch. The pure Raw display has no Auto
algorithm selector. The choice is saved per sensor on the device.

Uncertainty is rendered from the same resolved Auto-lane bounds on the dashboard,
notification and AOD charts, for both AiDex and Sibionics. Bounds follow display
unit conversion, smoothing and calibration; Raw does not inherit Auto uncertainty.
Missing intervals and sensor/time gaps break the ribbon. When predictive simulation
meets a ribbon, its displayed envelope starts at those asymmetric bounds and adds
forecast spread, fading into the lighter forecast shading over five minutes. This
changes drawing only; the sensor estimate and predicted glucose values are unchanged.

With internal calibration enabled, the same enabled references eligible for the
sensor's Auto calibration controls are supplied to the estimator in canonical
units, using only references at or before the sample timestamp. Manual Raw
calibrations stay in Raw; journal fingersticks shared with Auto remain eligible.
The Auto enable switch and each point's enabled state apply inside the algorithm
too. With no eligible references, toggling internal calibration does not change
the curve. The generic Auto calibration layer is then bypassed
to avoid applying the same correction twice. With it disabled, the usual external
calibration behavior remains available, as with Sibionics. Raw values and their
separate calibration path remain independent.

Changing the algorithm or its active references rebuilds retained history from
original factory/raw samples. While rebuilding, the last applied selection owns
live output. The rebuilt selection then supplies Auto history, current readings
and downstream display/export/alert paths through the existing reading lanes.
Returning to Stock restores original factory values before the usual external
calibration. Status reports rebuilding or failure rather than silently claiming
that an unfinished selection has been applied.

Original samples are saved to a CRC-protected, per-sensor source journal before
derived output is written. Full export/import includes this journal. History
backfill upgrades missing raw inputs and triggers replay; a restart reconstructs
the estimator from the journal. Selection/reference/source revisions invalidate
stale workers before storage, and removal drains writers before deleting the
native shell. Imported historical sessions have an explicit age proxy where
their activation time is unknown. The current native window uses its reported
start, and a sensor reset starts a separate estimator session.

Returning from a stock release can leave holes in the original journal even
though that release advanced both sensor-download cursors. Before replay, the
runtime checks stored minutes against the journal and requests sensor history
from the earliest missing minute. Both factory (0x23) and raw (0x24) cursors are
rewound for that repair, including on reconnect. Stored Auto values are never
substituted for missing factory inputs. Replay and any pending algorithm choice
resume after authentic originals arrive. Repeated live/UI triggers do not
repeat a repair for the same hole or compute the full estimator while waiting.
An incomplete/expired repair stops until reconnect, restart or new originals;
history outside the sensor's available range still requires the original journal.
That failure does not stop fresh readings: until the estimator is reconstructed,
live Auto uses the sensor's factory value, with the applied mode's internal
correction when enabled. It publishes without an invented uncertainty interval.
Available original factory minutes seed that correction's reference anchors
before an unrelated history hole stops replay. Imported references with unknown
stock values therefore fit against their available factory minute rather than
their saved Adaptive value; full historical repair remains blocked.
If a backfill page cannot be journaled while Adaptive owns history, the download
stops without advancing that page. Both cursors are persisted at or before its
start so reconnect refetches the consumed factory cache as well as the raw page,
even when the failed minutes never reached Room. Stock keeps its direct factory
history storage when journaling is unavailable.
The applied mode remains unchanged while a Stock restoration is pending, so
already-corrected retained history cannot become eligible for a second correction.

Live source, Room and publication work runs on a serial worker, under the same
ownership lock as replay commits, so a
previous Adaptive write cannot overwrite a later Stock restoration. The new
managed snapshot is installed before exchange publication; rate stays in mg/dL
per minute regardless of glucose display units. GATT notifications/read values
are copied and dispatched off the platform callback; protocol responses stay on
the control handler so replay cannot delay authentication ACK handling. Queued
live work rechecks the GATT identity and cannot write after removal closes
ownership. Publication carries the reported sensor start to the exchange feeds.
AiDex disables generic native-to-Room history sync in every mode, including after
removal. Stock startup bootstrap and failed direct-history writes use the same
owned journal/replay writer. Algorithm choices enter a per-sensor FIFO before
switching to IO; choices blocked by a write preserve tap order.
Failed live Room writes mark the journal for owned replay, including broadcast-only
Stock operation. A repair makes at most three attempts, then waits for another
live/history event. It uses the current algorithm request rather than a queued
old output. A redundant live write after a successful replay batch cannot start
another repair loop.
Calibration revision callbacks
compare actual references and fit fingerprints, so completion/cache-only
notifications do not schedule another replay. A rejected reset releases the
algorithm wait and retries any pending selection.

Keeping history on removal also keeps its applied calibration ownership in the
retained native preferences. Without a driver, single-value and series
calibration queries consult that applied mode; requested but unfinished choices
cannot change ownership. Stock-restored history remains eligible for generic
calibration, while internally corrected Auto history bypasses it. Raw remains
independent. Settings-only imports preserve local applied mode and original-input
provenance rather than replacing them with the backup's requested configuration.
Backup import counts streamed readings per sensor. A sensor with retained history,
provenance or a live driver keeps its local applied mode; only a new sensor adopts
the backup's history ownership. Conflicting incoming rows are rebuilt under the
local mode from local/backup factory/raw originals and local references before
merging. Existing rows outside the imported range remain unchanged. Missing
original minutes reject the conflict before settings or glucose writes. History-only
import uses the same policy. Requested settings may change, while applied ownership
changes only through a completed driver rebuild. Import commits share active AiDex
writer locks and recheck applied modes and original samples; concurrent imports
are serialized. Originals are restored before new ownership becomes observable.

With Stock requested and applied, a replay does not rewrite history: Room and
native already hold the factory values through the direct writers. It writes
only journal minutes Room lacks or holds a different number for (a failed live
or history write, a native-only minute), with the same three-attempt bound.
A rewrite of Auto history the driver does not calibrate voids a recorded
display value only at minutes whose stored number actually changed; replaying
the same factory numbers keeps every record. The original journal is an input
to rebuilds, never a gate on a reading: a journal I/O failure publishes and
stores the factory value under the applied mode, and an unreadable journal is
moved aside (`.corrupt-<time>`) rather than failing every later load. Missing
originals are then refetched or fail the rebuild closed, as before.
A live rate is the difference between neighbouring readings of the same kind
(model or factory fallback) at most five minutes apart, else unknown, which
publishes as 0 as the trendless native result did; a Stock trend byte wins.

Live and replay use the same causal one-minute reference window. An anchor on a
rawless or unusable minute remains available to the next usable sample within
that window; the estimator consumes accepted references once. Restart and the
standalone replay retain the same behavior.

Brief/broadcast readings without usable raw retain factory output without an
invented uncertainty interval. With internal calibration enabled, those fallback
points use the existing integrated calibration series. Actual live samples
remain publishable during reconstruction. Replay evaluates calibration against
the full factory series, including usable-raw minutes, and replaces only fallback
outputs with that correction. Startup replay alone does not count as a new sensor
packet.

The menu is a phone UI. Wear sources compile with the shared driver integration;
standalone watch selection, handover and physical-sensor behavior have not been
verified. An export without original journals cannot reliably reconstruct factory
inputs after Adaptive values have replaced Auto history. Missing originals are
refetched where the sensor still has them; unavailable originals cause replay
to fail rather than being treated as factory data.

## Model and isolation

The model intentionally follows raw changes while learning a slow factory scale.
When that scale is near one and no artifact/reference correction is needed,
Auto can closely track Raw. A similar-looking curve does not establish an
accuracy gain or mean that calibration/drift handling was bypassed.

`AiDexAdaptiveV2` is a separate Kotlin implementation in
`tk.glucodata.drivers.aidex.adaptive2`. The five estimator/math files originate
from the existing Sibionics Adaptive V2 implementation; changes are confined to
the AiDex package. No Sibionics source, configuration, native code or selection
logic changed. The model itself has no Android/JNI dependency; a separate AiDex
runtime adapter handles selection, source persistence and history/publication.

It retains the four-mode interacting multiple model, seven-state EKFs,
Student-t observation likelihood, adapted measurement noise, dynamics/artifact/
drift separation, reversal recovery, persistent reference-derived sensor priors,
mixture median and nominal 90% quantiles, trend uncertainty, probability queries,
and full continuation snapshots. CRC-protected AiDex snapshots have their own
magic/version and include normalization history and output-mixture state.

AiDex adaptations:

- The app's raw lane is `i1 * 10`, not a decoded factory glucose calibration.
  `AiDexParser` already stores that same convention for direct and raw-history
  samples. The model accepts this proxy and factory glucose as separate inputs
  in mg/dL; internal glucose arithmetic uses mmol/L with `18.0182` mg/dL per
  mmol/L. It does not treat `i1` itself as mmol/L.
- **Factory-assisted** normalization maps raw to glucose as
  `scale * raw + offset`. The scale is the robust median ratio of stable
  factory/raw pairs in a 60-minute window, with a six-hour adaptation half-life.
  It starts from the median of the first 15 stable pairs, never from a single
  pair: KT3T's first pair (15.3 / 11.5, a warm-up artifact) put the model 3–4
  mmol/L high for hours. Until then the factory value is published.
  The offset is fitted (Huber-weighted, zero-mean prior with a 1 mmol/L SD,
  ±4 mmol/L bound) only over at least a day of pairs from a 48-hour window,
  with a 12-hour half-life. A pure ratio cannot represent the offset an aging
  sensor develops: on KT3T days 9–13 factory fits `a·raw + c` with c ≈ +2.1 to
  +2.6 mmol/L at half the error of `k·raw`, and the ratio model turned low raw
  values into exaggerated lows. A six-hour affine fit was tried and rejected:
  on KUFY the offset swung between −2.4 and +4.0 mmol/L.
  Current factory spikes do not directly change the glucose observation. This
  remains a hybrid with a manufacturer-derived curve; it cannot correct
  arbitrary manufacturer bias without independent references.
- In factory-assisted mode, **references correct the output**, not the
  estimator's residual sensitivity/bias states. Each reference measures error
  against the **pre-correction filtered median**, so a fingerstick can correct
  a displayed curve that is still far from its slowly learned factory target.
  At raw `r_i`, filtered median `M_i` and applied mapping `f_i`, store the anchor
  `a_i = reference_i - M_i + f_i(r_i)`. Its later error is
  `a_i - f_t(r_i)`: mapping catch-up subtracts its share of the correction.
  Evaluate at the reference's original raw, not today's raw, so physiological
  glucose changes do not masquerade as calibration drift. The filter's residual
  at the reference stays part of its anchor. Errors retain a 1.5-day age decay,
  a prior weight of a quarter reference (one fresh, uncapped reference corrects
  80% of its error), and the ±2.5 mmol/L cap. Later references measure the
  unshifted median rather than incorporating earlier corrections again.
  Before bootstrap produces a filtered output, a reference retains its factory
  observation as its provisional baseline; it cannot bootstrap the mapping.
  Snapshot version 4 stores these raw-level anchors. Older snapshots reject
  cleanly; the app reconstructs model state by replaying original samples and
  references rather than reinterpreting old correction errors as anchors.
  Earlier target-based correction prevented double application after catch-up,
  but could barely move the output when factory already agreed with a meter
  (issue #681). In that issue's synthetic reproduction, the two reference-minute
  outputs change from 118/132 to 156/165 mg/dL for meter values 167/174; at +570
  minutes the corrected output is 166 mg/dL. These are regression results, not
  clinical validation. The normalizer's speed and estimator states are unchanged.
- **Reference-only** normalization (replay only) waits for a timely reference to
  establish the scale and thereafter does not use factory glucose for
  estimation. Further references update the residual sensitivity/bias states. Without a reference,
  raw alone cannot establish absolute glucose. KUFY has none in this export.
- Sibionics factory compensation, sensitivity constants, impedance semantics,
  vendor quality bits and deterministic lag correction are absent. Unknown
  temperature does not invent artifact evidence. The zero lead and lag variance
  of 12 min² are explicit modelling assumptions, not recovered factory values.
- Calibration uncertainty is added to each output-mixture component. It is
  not one minute's factory/raw disagreement: the factory leans with the trend
  and amplifies moves away from its recent level, and treating that as
  uncertainty in the slow scale made the 90% band about 40% of the value
  (median 2.1 mmol/L wide on KUFY, up to 8.5). Three parts instead:
  how far the whole mapping (scale and offset) sits from its target, smoothed
  with the scale's 6-hour half-life and divided by the ~17 independent hours
  that half-life averages (target correlation about one hour); a 4% floor; and
  half of a 30-minute signed average of the factory's log gap from the mapping,
  because where the two estimators persistently disagree the level is
  genuinely in doubt. On the KUFY stock export the median band is 1.03 mmol/L
  (90th percentile 1.78); Oct 14 19:55 went from 6.3–10.4 to 7.5–9.2.
  Reference corrections add their own variance. None of this is clinically
  validated, and it was not fitted to references: the no-reference band
  contains 11 of 29 KT3T references, because that aging sensor's errors are
  larger than its model uncertainty.
- Real lows and sharp raw rises remain reachable; there is no 3–4 mmol/L floor.
  Missing/invalid raw yields no estimate instead of an invented raw observation.
  The inherited physical state bounds remain 0.6–35 mmol/L. Six KT3T samples
  outside the usable normalized range and 755 zero-raw samples are unavailable.
- Activation/reset identity is explicit in `sessionStartMs`. Old-session,
  duplicate and stale packets cannot reset or contaminate newer state. A long
  transport gap within the same session retains reference-derived calibration;
  an explicit new session clears it. Instances are caller-thread-confined.

The inherited residual sensitivity bounds (roughly ±25%) and bias bounds
(±2.5 mmol/L) are regularizers, not proven AiDex aging limits. Reference-only
operation cannot replace continuous independent references on a deteriorating
sensor simply by estimating more latent states. These constraints, normalization
adaptation speed and lack of sensor-state telemetry are remaining research issues.

## Reference visibility repair, 2026-10-20

AiDex previously combined manual Raw and Auto references for its Auto estimator,
although the application's manual-calibration controls and lists are lane-specific.
A Raw reference could therefore move Auto history when internal calibration was
enabled, even with the Auto calibration switch off. Adding an Auto point then
averaged it with that hidden Raw point and unexpectedly reshaped the curve.
Live processing, history replay, backup conflict rebuilds and JSON replay input
now use the same Auto-eligible references as those controls. Raw-only edits do
not request an Auto rebuild. Journal fingersticks already eligible for Auto
remain supported. Real-database regressions cover an enabled Raw reference with
Auto disabled, an empty Auto lane, point enable/disable, another sensor's points,
shared journal points, restart and history merge.

## Calibration response revision, 2026-10-19 (#681)

In the issue's synthetic test, raw and factory settle at 105 mg/dL for one day,
then factory rises to 160 while raw remains 105. Fingersticks arrive at +120 and
+260 minutes. The output-anchored correction gives:

| Minute after factory change | Meter | Before | After |
| --- | ---: | ---: | ---: |
| +120 | 167 | 117.95 | 156.07 |
| +240 | — | 126.39 | 157.35 |
| +260 | 174 | 131.54 | 165.07 |
| +570 | — | 147.14 | 166.37 |

All values in this table are mg/dL. The new regression fails on the previous
model. Other tests check downward correction, the filtered rather than raw
baseline, scale/offset drift at fixed reference raw, multiple and duplicate
references, unchanged cap/decay, bootstrap and exact snapshot continuation.

Replaying the same 48,534 archived samples before and after this change gives
identical outputs for both the uncalibrated factory-assisted and reference-only
models. The calibrated model's pre-reference MAE on the 29 saved KT3T reference
minutes rises from 0.853 to 0.935 mmol/L. The change fixes the weak immediate
response; it does not establish better future prediction or clinical accuracy.
No parameter was fitted to these reference scores. The older evaluation tables
below describe their dated revisions, not the output-anchored model.

## Revision, 2026-10-15

Re-scored with the same replay on the same private export after the
normaliser and reference changes above. "False low" counts minutes where the
displayed value (model, else factory fallback) is below 3.0 mmol/L while
factory reads at least 3.9. No constant was optimised against the reference
scores; the offset window was chosen against KUFY/KT3T factory agreement.

| KT3T | Factory | Before | After |
| --- | ---: | ---: | ---: |
| Calibrated: false-low minutes | — | 3,062 | 510 |
| Calibrated: minutes < 2.2 | 1,572 | 5,179 | 1,640 |
| Calibrated: pre-reference MAE (29) | 0.686 | 0.912 | 0.853 |
| Calibrated: worst pre-reference error | | −3.81 | −2.58 |
| No references: MAE (29) | 0.686 | 1.273 | 1.117 |
| No references: minutes < 2.2 | 1,572 | 2,100 | 2,366 |
| First 12 h, median abs(model − factory) | | 2.47 | 0.65 |

KUFY: 7 false-low minutes (before 0) and a first-12-hour median difference
from factory of 1.08 (before 1.14). The calibrated model now reads low at
higher glucose: mean pre-reference error −0.80 mmol/L at references of 5.5 or
more (before −0.13). It is still less accurate than factory on these
references; the conclusion below is unchanged.

## Private export evaluation, 2026-10-14

The supplied Everything export contains 5,653 KUFY readings and 42,881 KT3T
readings: 48,534 total. KUFY spans October 10–14 and contains no enabled reference
calibrations. KT3T spans September 10–October 10 and contains 29 enabled reference
minutes. Its first 15 days and subsequent reset wear cannot be separated with a
verified millisecond activation boundary from the export. Default replay treats
the first exported reading as an age proxy, not a verified activation time.
Raw/auto changes or changes in timestamp phase are not proof of a reset.

KUFY's daily median factory/raw ratios are approximately 0.93, 0.95, 1.04 and
1.02. KT3T reaches roughly 2.3–2.6 during the first wear period and about 9.8
on its final partial day. A fixed cross-sensor or cross-age raw conversion is
not supported by these histories.

The screenshot event is present in both exports. At October 13, 10:34 UTC+5:

| Series | mmol/L |
| --- | ---: |
| Factory auto | 9.49 |
| Stored raw proxy | 7.48 |
| Factory-assisted AiDex V2 | 7.90 |
| Model nominal 90% interval | 6.38–9.43 |

This demonstrates reduced factory overshoot, not improved glucose accuracy.
There is no independent reference for that event. The interval is a model
quantile range, not a validated clinical error bound.

KT3T predictions are scored **before assimilating the current reference**;
only earlier references can affect that prediction. Reference-only's initial
anchor is excluded because it cannot predict an absolute value beforehand.
Comparators use the same scored minutes for each row.

| Candidate | N | Candidate MAE, mmol/L | Factory MAE on same minutes | Raw-proxy MAE on same minutes |
| --- | ---: | ---: | ---: | ---: |
| Factory-assisted, no references | 29 | 1.273 | 0.686 | 2.978 |
| Factory-assisted, earlier references | 29 | 0.912 | 0.686 | 2.978 |
| Reference-only, earlier references | 28 | 0.770 | 0.702 | 3.052 |

These are sparse saved user calibration values, with unverified meter/laboratory
provenance. They are not clinical validation or MARD. The comparisons do not
include a factory baseline additionally corrected using earlier manual references,
and would need that baseline for a complete calibration-method comparison.
Reference ordering uses the saved measurement timestamps; original entry and
revision timestamps are absent, so this does not recreate prospective availability
of the references on the phone.
No constants were optimized against these reference scores. The current result
does not justify presenting the candidate as a more accurate aging-sensor model.

A reset sensitivity run used September 25, 18:11:42 UTC+5
(`1790341902000`) as a **hypothesized**, unverified boundary suggested by the
timestamp-phase change near the described 15-day reset. It also failed to improve
agreement: factory-assisted with earlier references MAE was 0.927 versus factory
0.686 on 29 minutes; reference-only was 0.784 versus factory 0.684 on 27 minutes.
The phase change itself does not establish a hardware reset. A first post-reset
reference cannot be scored as an independent prediction in reference-only mode.

## Reproduce

The source export and generated per-minute traces are private and excluded from
Git under `.local/`. `replay.py` extracts only selected readings, enabled user
reference values and the matching temperature history. It does not copy the
settings dump, credentials, pairing keys or the original export.

Calibration records are stored in display units while history is stored in
mg/dL, and the export does not label calibration units. Supply the established
reference unit explicitly; do not infer it from value magnitude. This export's
saved reference values are in mmol/L. Mixed historical calibration units require
an explicitly corrected input rather than guessing.

```sh
python3 scripts/aidex/replay.py /path/to/private/Everything.json \
  --sensor X-222227KUFY --sensor X-222227KT3T \
  --reference-unit mmol/L

python3 scripts/aidex/report.py .local/aidex-evaluation/readings.csv \
  --sensor X-222227KUFY --date 2026-10-13 --offset-hours 5 \
  --start-hour 9 --end-hour 18 \
  --output .local/aidex-evaluation/report.html
```

Requirements: `kotlinc` and Java 17+; Java 21 was used for verification. Replay
compiles and executes the actual Kotlin model, not a second Python estimator.
The interactive report shows every minute in the selected event window,
supports a cursor and an optional model-interval band, and includes pre-reference
scores from its adjacent `summary.json`. It does not interpolate across gaps.
An independently established reset may be supplied as
`--reset X-serial=EPOCH_MILLISECONDS`; no automatic reset inference is performed.

## Verification

The full Gradle/JVM suites pass: 4,290 mobile and 3,271 Wear tests, zero
failures/errors/skips. Phone and Wear Kotlin/Java compilation pass. Eight Python replay/report
contract tests also pass. Model tests cover explicit units,
factory overshoot isolation, raw responsiveness, lows, references, unknown data,
reset/stale input, gaps and exact/corrupt snapshot restoration. Python replay
contract tests cover unit conversion, no future-reference leakage, private-field
exclusion, explicit resets and pre-reference scoring.

Integration tests cover source-journal CRC/recovery/backup, Stock restoration,
calibration toggling, causal reference ordering, restart continuation, late and
revised minutes, stale-worker rejection, failed commits and removal ownership.
Android runtime/manager regressions exercise release-switch and empty-journal
recovery, interrupted repair/restart, completion notification
termination, real reference/fit changes, delayed live-write ordering, current
managed-minute publication, rate units, rejected-reset recovery, retained
ownership, nonblocking callbacks and queued removal/transport checks. Runtime
tests exercise a journal failure during Adaptive backfill, persisted cursor
rewind, driver recreation, factory/raw refetch and successful replay, plus
continued direct Stock history storage when the journal is unavailable. Runtime
tests also cover disabled generic sync, settings-only import over
retained calibrated history, forward factory publication with irrecoverable
originals, Stock startup repair, full-series fallback fits and blocked tap ordering.
The real streamed importer is exercised for a clean-device full-history restore
and history-free packages. Merge regressions cover both Stock/Adaptive directions,
untouched newer rows and intervals, missing-original rejection, unrelated/new sensors,
history-only restore and a mode change during preflight. A real integrated calibration fit with unknown stock
references verifies available-anchor seeding during blocked repair. Live Room
failure tests cover Stock recovery, the three-attempt limit, a later Stock choice
and termination after a failed redundant write following replay commit.
Chart regressions cover both algorithm families, converted/recorded bounds, live
tail metadata, Auto/Raw lane ownership and forecast widening. Robolectric native
graphics tests check actual Compose and bitmap ribbon pixels, missing-minute gaps,
forecast-join opacity and the shared toggle at notification/AOD bitmap sizes.
Report tests verify separately closed interval bands for each continuous run,
using the same gap threshold as the glucose lines. Controller
tests cover rawless/unusable reference minutes, expiry and restart continuation.
The retained-history tests also exercise the real calibration manager's
single-value/series ownership gates. Other adapter tests replace history Room,
JNI, crypto and final publication side effects; they do not constitute
device or native-storage validation. UI guardrails pass without a baseline change.
The actual Compose sheet was checked in a JNI-free emulator harness, including
selection/toggle changes and portrait/landscape layouts. This harness does not
exercise BLE, native history storage or physical-sensor accuracy.

For reproducible UI inspection on an explicitly named disposable emulator:

```sh
ANDROID_HOME=/path/to/android/sdk python3 scripts/aidex/preview-ui.py \
  --emulator emulator-PORT
```

The script temporarily adds a debug-only Activity, removes its sources on exit,
and installs a UI-only APK on that emulator. Do not distribute that harness APK
as the application. Clinical accuracy, real-sensor reset behavior and phone/Wear
handover remain unverified; the historical reference comparison above remains
the available accuracy evidence.
