# Prediction benchmark

Replays recorded CGM history, makes a forecast every 5 minutes with each candidate, and
scores it against what the sensor read 15–120 minutes later. Production forecasting is not
touched: the shipped forecast is measured by calling the production functions
(`buildSmoothedConsumerHistory`, `trimHistoryForPrediction`, `buildGlucosePrediction`).

## Running

Data is never committed. Point the run at it:

```
JAVA_HOME=<JDK 17> \
JUGGLUCO_PREDBENCH_DATA='fixture:sibionics_v116a_real_events.csv:calibrated,stock;/path/Juggluco_Export.csv;azt1d:/path/AZT1D 2025/CGM Records' \
JUGGLUCO_PREDBENCH_OUT=/tmp/predbench \
JUGGLUCO_PREDBENCH_SCENARIOS=native,5min \
JUGGLUCO_PREDBENCH_JOURNAL=none,operational \
  ./gradlew :Common:testMobileDebugUnitTest --tests 'tk.glucodata.predictionbench.PredictionBenchmarkRun' --rerun
```

`--rerun` is required: environment variables are not task inputs, so Gradle otherwise treats
the task as up to date and silently writes nothing. Without `JUGGLUCO_PREDBENCH_DATA` the run
skips itself, which is how CI sees it.

Sources: the app's CSV export, `fixture:<test resource>:<columns>` (mmol/L column CSVs),
`azt1d:<folder>` and `hupa:<folder>`. Both public sets are CC BY 4.0 on Mendeley Data; download
them yourself:

- **AZT1D** (doi:10.17632/gk9m674wcx.1): 25 people with type 1 diabetes on Control-IQ pumps,
  Dexcom G6 every 5 minutes, boluses and carbs.
- **HUPA-UCM** (doi:10.17632/3hbcscwz44.1): 25 people with type 1 diabetes on FreeStyle Libre 2,
  injections or pumps. Point the loader at the dataset folder; it reads `Raw_Data` only. The
  `Preprocessed` files interpolate the 15-minute Libre history to 5 minutes, so every point between
  two readings leans on the later one, which is future information for a forecast made between
  them.

Scenarios: `native` (as recorded), `5min` (input thinned, truth at full resolution), `rounded`
(input rounded as the CSV export rounds it), `person` (a person's successive sensors as one lane,
scored per sensor period, so learners keep what they learned across a sensor change).

## What keeps it honest

- **Production forecast measured as shipped.** `ProductionKernel` runs the dashboard's own
  pipeline. `KernelVariant` with default arguments is held to it by
  `kernelVariantAtDefaultsIsTheProductionForecast`, so its ablations start from the real thing.
- **No look-ahead.** A forecaster only receives an `OriginView`, which refuses indices past the
  forecast moment. Learners build their training sets through the view, so a training target is
  never later than the forecast moment. `noForecasterCanSeeTheFuture` rewrites the future
  (readings, late-logged entries, future entries) and requires bit-identical forecasts.
- **Smoothing as it was live.** The app's smoothing is centred, so a stored smoothed series
  holds future data. The bench re-smooths the history ending at each forecast moment, which
  reproduces the live edge.
- **Prediction vs what-if.** `JournalMode.OPERATIONAL` sees only entries created, and not edited
  since, by the forecast moment. Planned entries for later count. `ORACLE` sees everything up to
  the horizon, which makes it a what-if test of the treatment model, not a forecast anyone saw.
- **Profiles as of the moment.** `OriginView.profile` carries the profile a dataset says was in
  force. Absent means app defaults.
- **Uncertainty of the comparison.** Differences come with 95 % intervals from resampling whole
  lane-days, because readings within a day are not independent.
- **Sensors differ.** Pooled tables weight lanes by length. The per-lane section and its
  trend-continuation figure come first when deciding whether a result generalises.

## Known limits

- The CSV export rounds to 0.1 mmol/L. It also holds history as of export time, so drivers that
  rewrite history (replays, Adaptive V2) are not seen as they were live.
- The export does not carry `firstStoredAt` or the recorded display values, so late-arriving
  backfill is treated as if it had been on time.
- `KernelVariant` fits its slope to raw readings. Production fits it to journal-residualised
  readings, so the two agree exactly only where no treatment is active.
- The `5min` scenario thins a 1-minute stream. It is not a Dexcom or Libre 2 signal.

## What the bench decided (October 2026)

RMSE change against "no change", median over people, 30 / 60 min:

| | AZT1D (25, Dexcom, pumps) | HUPA-UCM (23, Libre 2, independent) |
|---|---|---|
| production kernel | +5 % / +5 % (better for 4 of 25) | −2 % / 0 % (better for 14 of 23) |
| Kalman level + rate, plateau τ15 | −5 % / −2 % | −8 % / −4 % |
| plateau + pull to own 24 h mean | −9 % / −11 % | −17 % / −11 % |
| **adaptive (production)** | **−12 % / −14 % (better for 25 of 25 at both)** | **−16 % / −12 % (better for 23 of 23 at 30 min, 22 at 60)** |
| analog k-NN | −13 % / −15 % | −15 % / −9 % |

Adding the production treatment model to the adaptive forecast made it worse on HUPA's
injection users (operational journal); a ridge with insulin and carbs on board gained a few
tenths. HUPA was not used to choose the model: it was loaded after the choice, as a check. The
adaptive forecast is `AdaptiveGlucoseForecast` in `src/main`, the same code the phone runs;
`AdaptiveProduction` here only adds the daily refit and the fallback the phone uses.

Lower error is not better low detection: the adaptive forecast rarely crosses 70 mg/dL on its own,
but ranks upcoming lows best at an equal alarm count (49–60 % caught against 42–46 % for the
kernel). Alerts do not read it.

## Adding a candidate

Implement `Forecaster` (mg/dL out, NaN to abstain) and add it to `Rosters.standard()`. Read
only through the view. The causality test then covers it automatically.
