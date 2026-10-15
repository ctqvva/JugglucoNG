# Combine sensors (experimental)

On the **Sensors** screen, select two physical sensors with their display controls,
then enable **Combine sensors**. The pair is pinned: changing display visibility
never silently replaces an input. Turn Combine off to change the pair. Selecting
an individual sensor as primary also exits Combine mode.

Combine creates a separate derived sensor (`COMB-…`) and selects it as the primary.
Its own Room/native history goes through the existing virtual-sensor publication
pipeline: dashboard/chart, notifications, standard/custom alarms, speech, widgets,
live broadcasts, loop feeds, outbound API, and native history integrations consume
the derived source just as they consume a physical primary. Both physical sensors
continue collecting and retain their own original history and calibration. Native
exports that contain multiple sensor histories also retain the individual sources;
the combined source is identifiable by its separate ID.

The Sensors screen contains the switch, contributor IDs, and active/waiting/fallback
status. There is no separate dashboard-only estimate or combination switch in Alerts.
The **Sensor disagreement** warning remains independently configurable under Alerts.
Both controls default off.

## Model

Each input is resolved through its own normal display/calibration pipeline exactly
once, then converted to mg/dL internally. Never calibrate the already combined
reading again. Three completed pairs spanning at least two minutes are required
before blending. Up to 12 recent pairs within 20 minutes determine short-term
stability, measured as median absolute residual against each stream's previous
two-point linear trend, with a 5 mg/dL noise floor.

Recent user calibration anchors also influence the contribution. An anchor younger
than 24 hours gives up to twice the stability weight, fading with age and distance
from its reference glucose (40 mg/dL distance scale). Inverse variance determines
the blend, and either sensor's final contribution is limited to 25–75%. This is an
experimental heuristic, not an accuracy score: a quiet sensor can be wrong and two
sensors can share a bias. It does not invent a reference from sensor agreement.

## Pairing, waiting, and fallback

Require two distinct logical sensors, no future timestamps, values in 20–600 mg/dL,
readings no older than six minutes, and completed-pair timestamps within one minute.
The freshness limit supports five-minute CGMs. Separate callbacks are paired before
comparison: when one input advances while the other still has its last accepted
sample, preserve completed evidence and the last primary output within the freshness
limit. Repeated timer ticks cannot manufacture paired evidence.

Withhold blending while learning, after a gap, on calibration/lane changes, or when
the current symmetric difference `200 × abs(a − b) / (a + b)` exceeds the configured
threshold (20% by default). In these cases use a fresh reading from the first pinned
sensor, or the second if only it is fresh. No fresh contributor means no new output;
normal signal-loss handling remains active. No output is backdated or republished
under a newer timestamp. The combined rate is derived from its own consecutive
outputs, and is unavailable across composition/fallback transitions.

The independent disagreement warning defaults to five minutes of completed paired
evidence. Invalid data resets evidence; a bounded wait for the peer does not clear
acknowledgements or retries. Disagreement recommends a meter check without identifying
either sensor as correct. This follows the general principle in
[Dexcom's guidance when readings conflict with symptoms or expectations](https://provider.dexcom.com/what-continuous-glucose-monitoring-cgm-0),
which does not validate this model.

## Validation boundaries

Tests distinguish pairing, fallback and publication behavior from clinical accuracy.
The model is experimental; real simultaneous sensor/meter traces, physical alarm and
integration delivery, and device rendering require separate verification.

The control was also rendered from Compose at 360 dp in
[English (off)](images/combine-sensors-en.png) and
[Russian (fallback example)](images/combine-sensors-ru.png) using Robolectric.
These captures verify the control layout, not physical device behavior.
