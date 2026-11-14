# Signal noise in the xDrip-compatible broadcast

JugglucoNG sends every live reading as xDrip's `com.eveningoutpost.dexdrip.BgEstimate`
broadcast. Alongside the value, trend and timestamp, it now carries its own signal-noise
estimate, so a receiver such as AAPS can tell a clean reading from a noisy one.

The estimate is the quadratic fit's residual variance, `SSE / (n - 3)`, in mg/dL²
regardless of display units. The payload captures history and the live reading once. Its
computed trend and noise use the transmitted primary series: Auto in modes 0/2, Raw in
modes 1/3 (with the same missing-Raw fallback as the payload), exchange smoothing and
software calibration. Local-only smoothing and “Fewer readings” do not alter this series.
The outgoing reading always ends the fit at its own timestamp, including while its history
write is pending; later history cannot leak into it. The in-app chip keeps `SSE / n`, so
its number can differ from the wire value and from an independently smoothed exchange.

## Extras

| Extra | Type | Unit | Meaning |
|---|---|---|---|
| `com.eveningoutpost.dexdrip.Extras.Noise` | `double` | mg/dL² | xDrip's own `EXTRA_NOISE`: unbiased error variance `SSE / (n - 3)` |
| `com.eveningoutpost.dexdrip.Extras.NsNoiseLevel` | `String` | none | Nightscout/Dexcom category, `"1"`–`"4"` |
| `tk.glucodata.Extras.NoiseSigmaMgdl` | `double` | mg/dL | Square root of the variance (Juggluco extension) |

`NsNoiseLevel` uses the same strict thresholds as xDrip's `BgReading.injectNoise`:

| Variance | Level |
|---|---|
| `>= 0` and `<= 10` | `1` (clean) |
| `> 10` and `<= 60` | `2` |
| `> 60` and `<= 200` | `3` |
| `> 200` | `4` |

Level `0` means unknown/no data and is not emitted for a valid estimate. xDrip's
[`noiseValue()` and `injectNoise()`](https://github.com/NightscoutFoundation/xDrip/blob/master/app/src/main/java/com/eveningoutpost/dexdrip/models/BgReading.java)
use an effective default of `1` for clean live readings.

The engine needs at least four usable points. Its window scales with the measured cadence:
`20 × cbrt(cadenceMinutes / 5)` minutes, clamped to 8–25 minutes. A 5-minute sensor
normally supplies five points over 20 minutes; a 1-minute sensor supplies about twelve
points over 12 minutes. The thresholds remain 10/60/200 mg/dL², so the shorter horizon
and different sample count can change the category for the same underlying trace.
`n - 3` can be as small as one. This is the usual unbiased residual-variance estimator
under the quadratic, independent-error model, but it is less stable at that floor and
should not be treated as a clinical confidence score. xDrip itself waits for six points.

## When the extras are present

- Always, with every live `BgEstimate`; there is no preference or toggle.
- Never on historical backfill. Backfill describes a past reading and the estimate
  describes the live window.
- Never when fewer than four usable points exist in the window, when the quadratic
  determinant is degenerate, or when the variance overflows to a non-finite value. The
  extras are omitted rather than sent as level `0`, so a receiver cannot mistake
  "no estimate" for "clean".
- Never when the payload timestamp is outside the app's 5.5-minute freshness limit
  on either side of the wall clock. A history fallback still has a trend ending at the
  payload's timestamp, but stale/far-future data has no noise estimate.
- Only the segment newer than a >20 mg/dL/min discontinuity participates. If fewer
  than four points remain after that boundary, the estimate is omitted.
- No estimate if resolving the exchange reading failed: fallback glucose and its native
  rate can still be sent, without attaching noise from an unrelated snapshot.

## Consumer note

A receiver mapping the value into Nightscout or AAPS `GV.noise` must read
`NsNoiseLevel`, never `Noise`. `Noise` is the raw variance (typically 0–200+); treating it
as a 0–4 category would make almost every reading look noisy. AAPS reading these extras
is tracked in its own issue and is not part of this change.
