# Two-sensor comparison (experimental)

In **Alerts → Sensor disagreement**, enable a warning, an experimental combined
estimate, or both. Select exactly two sensors with the existing sensor selector.
Both features default to off. The phone owns the comparison; the Wear alert
switch controls the phone warning through the existing toggle sync.

The dashboard shows both source sensor IDs and their individual values. The
combined value is an additional display only. It never enters the glucose store,
primary selection, glucose alarms, notifications' glucose values, forecasts,
exports, or integrations. Existing per-sensor calibrations are resolved by
`CurrentDisplaySource` before comparison; the experiment does not fit another
calibration or treat one sensor as a reference.

## Comparison and warning

- Use each sensor's primary display lane, converted to mg/dL for all calculations.
- Require two different logical identities, readings no older than six minutes,
  timestamps no more than one minute apart, no future readings, and values in
  20–600 mg/dL. Outside this range the diagnostic is unavailable; normal alarms
  still operate independently. The six-minute age limit accommodates five-minute
  sensors; sensors whose sampling phases stay more than one minute apart remain
  unavailable rather than being extrapolated.
- Difference is `200 × abs(a − b) / (a + b)`. The default threshold is **20%**,
  adjustable from 10% to 50%. This symmetric definition is unchanged by swapping
  the primary. It is an engineering default, not a clinically validated threshold
  or a CGM-versus-meter accuracy criterion.
- Warn after five minutes of disagreeing paired evidence by default; the wait is
  adjustable using the standard duration control. Both timestamps must advance
  to accumulate evidence. Scheduler ticks and one repeatedly reused sensor
  sample do not count as additional observations. When one sensor advances before
  its peer, the incomplete pair hides the estimate while retaining
  completed-pair evidence within the six-minute freshness limit. Waiting neither
  advances the disagreement timer nor clears an existing warning episode or its
  acknowledgement/retry state. Recovery or expiry still cancels pending retries.
- Agreement, missing/stale/invalid data, sensor or display-lane changes, clock
  reversal, and recalibration reset the relevant evidence. Configuration changes
  restart learning. An outage longer than six minutes cannot bridge a run.
- Use the existing notification/alarm, sound, time-window, snooze, dismissal, and
  retry controls. Recovery cancels pending retries; failed delivery remains
  eligible through the standard alert tracker. The warning recommends a meter
  check and never identifies a sensor as correct.

## Experimental combination

At least three paired observations spanning two minutes are required. Keep up
to 12 pairs over 20 minutes. Estimate each stream's short-term variability from
the median absolute residual against its previous two-point linear trend. Apply
a 5 mg/dL noise floor, square this measure, and blend the **current** readings
using inverse-variability weights limited to 25–75%. This avoids adding a moving
average's lag to the current estimate. It does not extrapolate either sensor.

Withhold the estimate immediately while the current difference exceeds the
configured threshold, without waiting for the warning delay. Invalid timing,
values, or identity also withhold it and discard learned history, except for the
bounded wait for an unchanged peer described above. A stable bias
cannot be detected or corrected by this heuristic: two sensors can agree and
both be wrong, and a quiet sensor can be less accurate than a noisy one. No
confidence interval or claim of improved accuracy is shown.

The estimate is explicitly labelled experimental and unsuitable for treatment
decisions. The meter-check copy follows the general principle in
[Dexcom's instructions when readings conflict with symptoms or expectations](https://provider.dexcom.com/what-continuous-glucose-monitoring-cgm-0).
That guidance does **not** validate this two-sensor algorithm.

## Validation boundaries

Deterministic tests cover pairing, persistence, expiry, timestamp changes,
calibration resets, weighting, bounds, percentage symmetry, configuration
persistence, and notification text. Phone/Wear compilation and localized resource
checks cover integration. Real simultaneous sensor traces, meter-reference
accuracy, physical alarm delivery, and on-device layout remain to be evaluated.
