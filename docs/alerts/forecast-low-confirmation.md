# Forecast-low evidence

Enable **Consider IOB and recent glucose** in Forecast Low advanced settings to
use this policy. It defaults off; disabling it preserves existing forecast-low
behavior and clears the collected shape history.

PRE_LOW projects the current trend line over its configured forecast horizon.
After a brief rise, a ten-minute descent back to the previous baseline can
therefore predict a low even though it only retraces the rise. Zero IOB makes
that interpretation relevant, but it cannot establish that glucose will stop
falling at the baseline.

The existing direction, COB coverage and trusted-trend checks still run. For a
new candidate, these conditions allow immediate eligibility:

- Measured glucose is at most 15 mg/dL above the forecast threshold.
- The current rate projects reaching that threshold within ten minutes.
- Next-30-minute insulin activity, multiplied by configured insulin sensitivity,
  represents at least half the distance to the threshold and at least 10 mg/dL.
  A saved sensitivity or model profile is required for this insulin shortcut.

For candidates without those urgency signals, a recent rise can explain a
return toward baseline. The runtime keeps up to 45 minutes of observed display
values from the same sensor and display mode. The shape requires a quiet
five-minute baseline (within 6 mg/dL, with timestamp tolerance), followed by a
rise of at least 15 mg/dL over 3 to 15 minutes. Both classic IOB and next-30-minute
IOB must explicitly be zero, allowing only numerical residue up to 0.0001 U.
Missing data, an older short snapshot, and insulin waiting to act do not qualify.

That candidate waits while glucose is at or above baseline minus 3 mg/dL.
Falling farther below baseline releases it immediately. The shape evidence
expires 15 minutes after the observed peak, and any urgency signal above takes
precedence. This is a bounded interpretation of the preceding rise, not a
claim that the baseline is a physiological floor. A monotonic fall has no
preceding rise to justify this deferral.

Other distant candidates require at least two distinct readings spanning one
minute. For a sensor reporting once per minute, the next candidate reading can
alert. A five-minute sensor can confirm on its next reading. This short
confirmation alone cannot reject a ten-minute return to baseline. Confirmation
also accumulates during shape deferral, so expiry does not start another wait.

A measured rise, loss of the forecast crossing, non-falling arrow, COB coverage,
unavailable input, or a gap longer than six minutes resets confirmation.
Sensor identity, generation, threshold, unit and horizon changes also reset it.
Calibration changes clear the observed shape and confirmation, using the
existing wait-for-new-reading barrier. Sensor or display-mode changes and
invalid readings discard the shape history. Restarting loses that history and
uses the ordinary confirmation path until a new baseline and rise are observed.
New episodes require a reading no more than six minutes old. Scheduler ticks
and duplicate timestamps cannot advance confirmation.

Existing episodes keep their rearm, dismissal, snooze and retry rules. LOW,
VERY_LOW, PRE_HIGH and FALLING_FAST keep their existing behavior. Sensor-pressure
handling remains a separate optional gate. COB coverage still uses total
remaining carbs; absorption timing is outside this change.

These constants are policy heuristics, not calibrated probabilities. Synthetic
replays exercise the real trend estimator, forecast evaluation and eligibility
policy. They do not prove the cause of a particular excursion or clinical
performance. Field benefit and device delivery still need validation.
