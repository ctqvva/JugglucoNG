# Signal noise in the xDrip-compatible broadcast

JugglucoNG sends every live reading as xDrip's `com.eveningoutpost.dexdrip.BgEstimate`
broadcast. Alongside the value, trend and timestamp, it now carries its own signal-noise
estimate, so a receiver such as AAPS can tell a clean reading from a noisy one.

The estimate is the mean squared residual of the same quadratic fit the in-app
signal-quality chip uses (`TrendEngine`). It is computed over the same history plus live
point list as the outgoing trend, in mg/dL² regardless of the display unit.

## Extras

| Extra | Type | Unit | Meaning |
|---|---|---|---|
| `com.eveningoutpost.dexdrip.Extras.Noise` | `double` | mg/dL² | Raw error variance, xDrip's own `EXTRA_NOISE` |
| `com.eveningoutpost.dexdrip.Extras.NsNoiseLevel` | `String` | none | Nightscout/Dexcom category, `"0"`–`"4"` |
| `tk.glucodata.Extras.NoiseSigmaMgdl` | `double` | mg/dL | Square root of the variance (Juggluco extension) |

`NsNoiseLevel` uses the same strict thresholds as xDrip's `BgReading.injectNoise`:

| Variance | Level |
|---|---|
| `<= 10` | `0` |
| `> 10` and `<= 60` | `2` |
| `> 60` and `<= 200` | `3` |
| `> 200` | `4` |

Level `1` is never emitted; xDrip does not emit it either.

## When the extras are present

- Always, with every live `BgEstimate`; there is no preference or toggle.
- Never on historical backfill. Backfill describes a past reading and the estimate
  describes the live window.
- Never when fewer than four usable points exist in the window. The extras are omitted
  rather than sent as level `0`, so a receiver cannot mistake "no estimate" for "clean".

## Consumer note

A receiver mapping the value into Nightscout or AAPS `GV.noise` must read
`NsNoiseLevel`, never `Noise`. `Noise` is the raw variance (typically 0–200+); treating it
as a 0–4 category would make almost every reading look noisy. AAPS reading these extras
is tracked in its own issue and is not part of this change.
