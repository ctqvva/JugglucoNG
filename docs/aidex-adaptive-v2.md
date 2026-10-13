# AiDex Adaptive V2 experiment

Status: implemented and replayable, **not connected to live display, alerts,
exports or therapy integrations**. The initial evaluation reduced the reported
factory excursion but did not establish better agreement on the aging sensor.
There is no new selectable phone/Wear algorithm in this change.

## Model and isolation

`AiDexAdaptiveV2` is a separate Kotlin implementation in
`tk.glucodata.drivers.aidex.adaptive2`. The five estimator/math files originate
from the existing Sibionics Adaptive V2 implementation; changes are confined to
the AiDex package. No Sibionics source, configuration, native code or selection
logic changed. The prototype has no Android/JNI dependency and does not mutate
sensor history or settings.

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
- **Factory-assisted** normalization bootstraps an explicit scale from a valid
  factory/raw pair. Subsequent learning uses stable pairs in a 60-minute window,
  their robust median log ratio, and a six-hour adaptation half-life. Current
  factory spikes do not directly change the glucose observation. This remains
  a hybrid with a manufacturer-derived scale; it cannot correct arbitrary
  manufacturer bias without independent references.
- **Reference-only** normalization waits for a timely reference to establish
  the scale and thereafter does not use factory glucose for estimation. Further
  references update the residual sensitivity/bias states. Without a reference,
  raw alone cannot establish absolute glucose. KUFY has none in this export.
- Sibionics factory compensation, sensitivity constants, impedance semantics,
  vendor quality bits and deterministic lag correction are absent. Unknown
  temperature does not invent artifact evidence. The zero lead and lag variance
  of 12 min² are explicit modelling assumptions, not recovered factory values.
- Absolute scale uncertainty is added to each output-mixture component. Its
  assumed log-scale SD floor is 8%, rising to 15% without enough stable pairs;
  these bounds and the nominal interval coverage are not clinically validated.
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

## Verification and next integration boundary

The focused AiDex package, existing Sibionics V2 suites and selection suite pass
under mobile Gradle/JVM verification: 512 tests, zero failures/errors/skips,
including 25 new AiDex model tests and 61 existing Sibionics V2/selection tests.
Four Python replay contract tests also pass. New model tests cover explicit units,
factory overshoot isolation, raw responsiveness, lows, references, unknown data,
reset/stale input, gaps and exact/corrupt snapshot restoration. Python replay
contract tests cover unit conversion, no future-reference leakage, private-field
exclusion, explicit resets and pre-reference scoring.

Before a live selector is implemented, establish improved chronological results
with independently verified reference readings and reset boundaries. An eventual
integration must preserve original factory/raw source history, replay consistently
after selection/reference changes, and resolve the same value for live display,
history, alerts, phone/Wear handover and exports without double calibration.
That behavior is not implemented or claimed by this experiment.
