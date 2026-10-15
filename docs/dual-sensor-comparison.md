# Combine sensors (experimental)

On the **Sensors** screen, select the physical sensors to combine with their display controls,
then enable **Combine sensors**. The contributor set is pinned: changing display visibility
never silently replaces an input. Turn Combine off to change the contributors. Selecting
an individual sensor as primary also exits Combine mode.

Combine creates a separate derived sensor (`COMB-…`) and initially displays only that
source. Physical contributors can still be explicitly displayed alongside it; their
display visibility does not change the pinned contributor set. Turning Combine off
restores the contributors to the display selection.
Its own Room/native history goes through the existing virtual-sensor publication
pipeline: dashboard/chart, notifications, standard/custom alarms, speech, widgets,
live broadcasts, loop feeds, outbound API, and native history integrations consume
the derived source just as they consume a physical primary. The physical sensors
continue collecting and retain their own original history and calibration. Native
exports that contain multiple sensor histories also retain the individual sources;
the combined source is identifiable by its separate ID.

The combined reading has one final display value, without a second calibration or
an identical raw value beside it. It has no physical sensor start or expiry date;
the native history storage window is not a sensor lifetime. The dashboard uses the
same resolved combined rate as alerts and integrations, leaving its arrow unavailable
when continuity is unknown.

The chart follows the display selection too: the default has one combined trace
and one scrubber value taken from the line under the cursor, including after
smoothing. An explicitly selected physical peer adds its own trace and scrubber
row. Combined points never create raw, uncalibrated or calibration-preview lanes,
including when they appear in a timeline whose current sensor uses a raw view mode.

The Sensors screen contains the switch with one short active/waiting/fallback
status. There is no separate dashboard-only estimate or combination switch in Alerts.
The **Sensor disagreement** warning remains independently configurable under Alerts.
Both controls default off.

## Model

Each input is resolved through its own normal display/calibration pipeline exactly
once, then converted to mg/dL internally. Never calibrate the already combined
reading again. For each pair, three completed observations spanning at least two minutes are required
before blending. Up to 12 recent pairs within 20 minutes determine short-term
stability, measured as median absolute residual against each stream's previous
two-point linear trend, with a 5 mg/dL noise floor.

Recent user calibration anchors also influence the contribution. An anchor younger
than 24 hours gives up to twice the stability weight, fading with age and distance
from its reference glucose (40 mg/dL distance scale). Inverse variance determines
the pair blend, and either sensor’s contribution is limited to 25–75%.
For three or more selected sensors, accept a complete group observation before
advancing any pair. This gives every pair the same observation baseline, including
when one contributor reports every five minutes and the others every minute.
Compare every pair from that group and average the pair estimates only when every
pair has learned and agrees. Every selected input
participates; each total contribution is bounded between `0.5/N` and `1.5/N`.
A disagreeing sensor withholds the whole blend; a majority cannot establish truth. This is an
experimental heuristic, not an accuracy score: a quiet sensor can be wrong and two
sensors can share a bias. It does not invent a reference from sensor agreement.

## Pairing, waiting, and fallback

Comparison requires at least two distinct logical sensors, no future timestamps,
values in 20–600 mg/dL, readings no older than six minutes, and completed-group
timestamps within one minute. These comparison bounds do not exclude a valid low
calibrated reading from individual fallback: fallback accepts the physical display
pipeline’s finite positive reading, including values below 20 mg/dL.
The freshness limit supports five-minute CGMs. Separate callbacks are paired before
comparison: while inputs advance separately and peers still have their last accepted
samples, preserve completed evidence and the last primary output within the freshness
limit. Waiting never holds an expired primary when a fresh individual fallback is
available. Repeated timer ticks cannot manufacture paired evidence.

Withhold blending while learning, after a gap, on calibration/lane changes, or when
the current symmetric difference `200 × abs(a − b) / (a + b)` exceeds the configured
threshold (20% by default). In these cases use a fresh reading from the first pinned
sensor, or the next fresh contributor in selection order. No fresh contributor means no new output;
normal signal-loss handling remains active. No output is backdated or republished
under a newer timestamp. The combined rate is derived from its own consecutive
outputs, and is unavailable across composition/fallback transitions. Each transition
persists a composition boundary before publication. Historical trend and delta
queries select the segment containing the row’s timestamp; later transitions do
not erase movement within older segments. Smoothing and trend estimation never
cross a known composition boundary. Restart restores the native source’s timestamp (including
its short aliases), preventing same-timestamp replacement, and starts a new trend
segment at the first new output.

The boundary timeline and its confirmed-through timestamp travel with the mirrored
Wear preferences. Every output advances that coverage, and receivers merge delayed
snapshots without losing newer boundaries. A combined reading arriving before its
metadata keeps its glucose value, but its trend is unavailable until continuity is
known. Native second precision and phone millisecond timestamps share the same
segment boundaries.

With Health Connect enabled, the combined native record is exported. Physical
exports are withheld while Combine is active and resume when it is disabled.

The independent disagreement warning defaults to five minutes of completed paired
evidence. Invalid data resets evidence; a bounded wait for the peer does not clear
acknowledgements or retries. Stopped managers and assessments older than six minutes
are unavailable even when Combine remains enabled. Disagreement recommends a meter check without identifying
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
The dashboard header was also rendered in
[English](images/combined-dashboard-en.png) and
[Russian](images/combined-dashboard-ru.png), including a transition with an unavailable
trend and stale physical-lifetime fields.
The [combined chart scrubber](images/combined-chart-scrub.png) was also rendered
from Compose with one trace and its matching cursor value.
