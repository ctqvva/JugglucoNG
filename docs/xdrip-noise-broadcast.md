# Signal noise in the xDrip-compatible broadcast

JugglucoNG sends every live reading as xDrip's `com.eveningoutpost.dexdrip.BgEstimate`
broadcast. Alongside the value, trend and timestamp, it now carries its own signal-noise
estimate, so a receiver such as AAPS can tell a clean reading from a noisy one.

The estimate is xDrip's unbiased error variance, `SSE / (n - 3)`, of the same quadratic fit
the in-app signal-quality chip uses (`TrendEngine`). It is computed over the same history
plus live point list as the outgoing trend, in mg/dL² regardless of the display unit. The
chip keeps the engine's biased `SSE / n`; only the wire value is rebased, so xDrip's
thresholds mean what they mean.

## Extras

| Extra | Type | Unit | Meaning |
|---|---|---|---|
| `com.eveningoutpost.dexdrip.Extras.Noise` | `double` | mg/dL² | xDrip's own `EXTRA_NOISE`: unbiased error variance `SSE / (n - 3)` |
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

The engine needs at least four usable points. xDrip waits for six; its list is independent
of the trend window. Juggluco's 20-minute window yields five points on a 5-minute sensor,
so the estimate can have as few as two degrees of freedom. The statistic is the same and
unbiased, but it is noisier at that floor.

## When the extras are present

- Always, with every live `BgEstimate`; there is no preference or toggle.
- Never on historical backfill. Backfill describes a past reading and the estimate
  describes the live window.
- Never when fewer than four usable points exist in the window, when the quadratic
  determinant is degenerate, or when the variance overflows to a non-finite value. The
  extras are omitted rather than sent as level `0`, so a receiver cannot mistake
  "no estimate" for "clean".

## Consumer note

A receiver mapping the value into Nightscout or AAPS `GV.noise` must read
`NsNoiseLevel`, never `Noise`. `Noise` is the raw variance (typically 0–200+); treating it
as a 0–4 category would make almost every reading look noisy. AAPS reading these extras
is tracked in its own issue and is not part of this change.
