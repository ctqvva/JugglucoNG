# Compression lows (PISA): spike findings and what got built

> **Status update (2026-08-23):** the spike graduated. The branch now carries the full
> feature set — the pure retrospective detector, the live **sensor pressure hold**
> ("compression low gatekeeper": opt-in, experimental, a bounded LOW hold plus a
> six-minute confirmation wait for PRE_LOW and FALLING_FAST, VERY_LOW untouched,
> gentle SENSOR_PRESSURE cue, receipts, self-disable), the **hypo episode log** (every
> below-range episode of the last 30 days, user-togglable pressure classification), and
> the **statistics retro-fix** (confirmed pressure episodes drop out of the Compose
> stats with a visible count; flipping the toggle back restores the numbers). Every
> threshold is user-configurable past the recommended rails by deliberate product
> decision — warnings, not locks. All defaults remain conservative educated guesses
> awaiting real-trace tuning; the episode log's toggles are the calibration scorecard.
> The original spike findings below stand as written.

Outcome of the spike in `~/input/jugglucong-compression-low-spike.md`. The Phase-1
retrospective detector is feasible with pieces the codebase already has, and a working,
tested core now exists at `Common/src/main/java/tk/glucodata/logic/CompressionLowDetector.kt`
with its unit tests in `Common/src/test/java/tk/glucodata/logic/CompressionLowDetectorTests.kt`.
The first draft survived one adversarial review round badly — three constructed traces got
a plausibly real hypoglycemia emitted as an artifact — and the current version encodes
what those attacks taught; the test suite keeps the attack traces.

## What the detector does

Scans a recorded trace and reports an episode only when the full signature is observed:

- a fall whose **mean segment rate** exceeds 2 mg/dL/min (a single noisy sample pair on a
  physiological decline does not qualify), deeper than 25 mg/dL;
- out of a **quiet baseline**: at least 10 of the 15 pre-onset minutes recorded, no holes,
  no sample more than 10 mg/dL below the onset value (a recent treated low recovering into
  a second fall is instability, not rest — deliberately tighter than the spec's "flat or
  rising", noted for Phase-2 calibration);
- that the journal's insulin cannot explain (depth > IOB × ISF × 1.25), with a non-finite
  IOB aborting rather than reading as zero;
- with the last dose past its activity peak — `dosePeakPassed` derives the peak from the
  configured piecewise-linear activity curve, never from a fixed minute count;
- a rebound to within 15 mg/dL of baseline inside 45 minutes that never undercuts the
  vetted nadir (a W is not a V);
- and no carbohydrates ≥ 5 g in the journal from **30 minutes before onset** through
  recovery — carbs eaten just before the fall registers are what a treated real low
  looks like.

Sensor age is recorded on the episode as a weight, never a condition; there is no night
window. All thresholds are named constants awaiting calibration against real traces.

## Early-warning confirmation wait

PRE_LOW and FALLING_FAST are exposed to shallower compression waves than the LOW detector
can safely classify. While sensor pressure hold is enabled, either early warning waits up
to six minutes. Recovery by at least 3 mg/dL from the lowest value drops the candidate. A
continuing fall releases it at the deadline. Entering the configured LOW range releases the
wait immediately, and neither LOW nor VERY_LOW passes through this state.

This wait is deliberately separate from the retrospective detector. The field trace that
motivated it contains 15 to 32 mg/dL waves and several do not start from a quiet baseline,
so weakening the LOW detector enough to accept them would also weaken its real-low guards.
The dashboard signal-quality number was checked as a possible supporting input. It stayed
mostly green at the false-alert points and overlapped the genuine-low controls, so the wait
does not use it as a decision signal.

The detector is pure: samples plus lambdas (`iobUnitsAt`, `dosePeakPassedAt`,
`carbGramsBetween`), following the `GlucosePredictionKernel.simulate` seam pattern, so the
same code runs under JUnit against fixtures and on device against Room/native data.
Native minute-slot placeholders (value 0) are dropped before analysis — the lesson
`TrendEngineGapTests` pins — recording gaps wider than 10 minutes anywhere in the window
or the V reject the episode, duplicate timestamps resolve deterministically to the lower
reading, and rates are read over a ≥30 s look-ahead so dense (20 s) streams neither blind
the scan nor feed it noise.

**Honest limit, wider than the handoff stated it:** an unlogged journal entry in *either*
direction defeats the detector — a forgotten bolus produces the unexplained fall, and
unlogged rescue carbs produce the clean rebound. Both are real lows with the artifact's
exact signature. This is why episodes only ever mark history for user confirmation and
why the opt-in text must name both branches.

## Building blocks located (for the wiring phases)

Line numbers below cite the `local-build` branch unless marked otherwise, since that is
where the wiring will land.

- **IOB-unexplained arithmetic** — the exact `iobUnits × insulinSensitivityMgdlPerUnit`
  expression lives in `alerts/ForecastIobCoverage.kt:54` with inputs assembled in
  `AlertRuntimeManager.kt:331-341` (`JournalIobAccess.snapshot(nowMs)[0]` +
  `PredictionModelProfileStore.parametersAt(prefs, nowMs).insulinSensitivityMgDlPerUnit`).
  Both exist only on `local-build` (and `fix/forecast-rearm-hysteresis`), not on
  `upstream/main` — the detector therefore does not depend on them.
- **Remaining IOB at an arbitrary time** — `JournalIobCalculator.remainingCurveFraction`
  (`Common/src/mobile/.../journal/JournalIobCalculator.kt:74-85`, same on this branch),
  trapezoid integration over the user-editable piecewise-linear activity curve. Doses come
  from `JournalEntryEntity` rows joined to `JournalInsulinPresetEntity`.
- **Peak passed** — no production helper existed; the chart derives it ad hoc via
  `curvePoints.maxByOrNull { it.activity }` (`JournalCompose.kt:3148` on this branch,
  3227 on local-build). The detector now carries the pure derivation
  (`CompressionLowDetector.peakMinuteOf` / `dosePeakPassed`); the on-device adapter feeds
  it `preset.curvePoints.map { it.minute to it.activity }` per dose.
- **Historical glucose** — Room `history_readings` via `HistoryRepository`;
  `HistoryReading.rate` is null for native-synced rows (`HistorySync.kt` hardcodes
  `rate = null` in `doSyncSensorWindow`), so the detector computes its own rates.
- **Sensor age** — `Natives.getSensorUiSnapshot(name)[2]` (start ms), managed sensors via
  `ManagedSensorRuntime.resolveUiSnapshot`.
- **Carbs in a window** — `JournalDao.getEntriesBetween` filtered to
  `JournalEntryType.CARBS`; no carb-specific SQL exists yet.
- **Scan trigger** — `HistorySync.syncRecentSensorFromNative` runs after new readings
  land and already re-scans a recent window; the retrospective detector hooks there (or
  the 15 s `AlertRuntimeManager` scheduler for the later prospective phases).

## Where the later phases land

- **Episode storage** — `HistoryDatabase` (`glucose_history.db`, v17 on local-build, v12
  on this branch's base — renumber at the merge per the known version skew). A new
  `compression_episodes` table keyed `(sensorSerial, startMs, endMs)` with a
  confirmed/dismissed state, mirroring how meals were added; never rewrite reading rows,
  matching the `DeletedHistoryReading` tombstone precedent.
- **Statistics exclusion** — single choke point:
  `StatsViewModel.resolveRangeProjection` builds `filteredHistory`
  (`StatsViewModel.kt:886`); adding `!exclusions.covers(point.timestamp)` there
  propagates to TIR, GMI, episodes, AGP and the report exporter. Two extras: fold the
  exclusion revision into the `StatsDisplayHistoryCacheKey` construction (`:713`) and
  into `resolvePreviousPeriodScalars` (`:922`, which re-filters independently at `:946`).
  The legacy native stats path (`Stats.java` → `Natives.analysedays`) is not extensible
  without C++ work — Phase-1 exclusion applies to the Compose stats only, and the report
  must say so.
- **Marking UI** — `JournalChartMarker` (`JournalModels.kt:165` here, 167 on local-build)
  already draws active spans over the chart; the episode overlay can reuse that pattern.
- **"Turn over" alert type (Phase 4)** — `AlertType` enum has stable ids 0–13
  (`AlertConfig.kt:8`); a new type appends as id 14 and every per-type pref (tone,
  vibration, delivery mode…) derives generically from the id in `AlertRepository`.
  Only default-config construction (`AlertConfig.kt:340,361`) and severity mapping
  (`AlarmActivity.kt:185-187`) special-case per type.

## Open questions (unchanged from the handoff, now concrete)

1. **Calibration against real data.** The constants are literature-informed guesses; the
   quiet-baseline band in particular trades some genuine artifacts (those preceded by a
   steep rise) for safety and needs real-trace validation. The recorded minute-data on
   the author's device is the validation set; an export → fixture (the
   `juggluco-export-sample.tsv` TSV format already has a parser exercised by
   `HistoryImportParserTests`) would let the suite replay real episodes.
2. **False-positive rate on real fast hypos** is the one number that matters, and it is
   measurable retrospectively on that same export because the true episodes are known.
3. Nightscout announcement of confirmed episodes: undecided, not designed.

## Deliberately not done in this spike

No wiring into Room, stats, chart, or alerts; no new tables; no settings; nothing on the
alarm path. The hold-mode (handoff §4.2) and the turn-over alert (§4.3) stay behind
Phase-1 field data on the detector's error rate.
