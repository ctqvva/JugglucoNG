# Task 08: filling missed records

Setup and conventions: `00-shared-infrastructure.md`. Background:
`../findings.md`, section "Record filling". This task is independent of the
error tasks. Every detector processes filled records through the normal
per-record path, so implement filling by calling that path and keep
detector code free of special cases.

## Trigger

The vendor fills when the sequence number is consecutive (seq = previous + 1)
and the time step is 480–1379 s. For one input, `air1_opcal4_algorithm`
then writes up to four outputs and debug records, and returns the count. The
newest output is the input record itself.

This happens only when the transmitter's clock is broken, since a
consecutive sequence normally means 300 s. It is rare in real data but
reachable.

## Current engine behaviour

- The engine processes the record normally and sets `exact = false` on it (`CareSensAirCalibration.process`, `dt in 480 until 1380`).
- It writes no filled records, so its Kalman, history and error buffers diverge from the vendor's afterwards.
- `differential.py` stops comparing at the first filled record. The shadow comparison (`CareSensAirOpenCalibrationShadow`) skips such records.
- Native side: `airstream` holds four outputs and uses the newest (commit 99eb5936d). That part is done.

## Known

These experiments use the legacy harness: `lab.py` and `o.py` with
`oracle_step`, which builds `liboracle.dylib` from `oracle.c`. They are in
`tools/caresens-oracle/research`:

| Script | What it does |
|---|---|
| e89.py | `multi(W, w, T, t, tg)`: runs 40 records, then one record at gap `tg`; returns the count and the filled outputs |
| e90.py | step and impulse responses of the filled ADC samples to single previous and current samples |
| e98.py | unit responses `fillB.npy` to the three 10-sample block means of the previous record and the first block of the current one; compared against SciPy cubic splines |
| e91–e97, e99–e103 | node-position, boundary-condition, end-slope and secant-weight fits against the measured responses (`fillB.npy`, committed) |
| e104 | the same fits per gap: 480, 500, 600, 700, 900, 1200, 1379 |

- **Inputs.** The filled 10 s samples are linear in the inputs. They depend on the previous record's three 10-sample block means and the current record's first block mean. Current samples 10–29 have no effect.
- **Shape.** Zero curvature at the right end. The slope at the last previous node is a gap-dependent combination of the three secants, with weights of about −0.163, 0.879 and 0.284 at one gap.
- **Rejected:** natural, not-a-knot and clamped cubic splines through nodes at −250, −150, −50 and 350 s (±5 s); a Lagrange cubic; PCHIP- and Akima-type slopes.
- The unit responses are measured to about 1e-5. A table interpolated over the gap is a fallback if the closed form stays unknown; it must be exact to be useful for parity.

## Open

1. The number of filled records as a function of the gap, and their sequence numbers and times (`seq_number_final`, `measurement_time_standard` of each output).
2. The closed form of the interpolant. Try:
   - a cubic Hermite with a finite-difference slope over unequal node spacing
   - a spline that includes the current record's first block at its true time `tg − 290 + 5`
3. Whether filled records go through every stage: contact rejection, spike limiter, loess, errors and level counts. Expect yes; verify per stage with the differential intermediates.
4. The engine state after a fill. Compare a run with a fill against the vendor from the fill onward, and remove the cut-off in `differential.py`.

## Suggested next steps

1. Port `multi` to the current harness. `REF.run` already returns `count` and all four slots.
2. Tabulate the count against the gap (480–1379 in 10 s steps) and the filled times.
3. Recover the interpolant, or tabulate it if the closed form resists. A table interpolated over the gap is acceptable only if it is exact to the ADC integer, because the filled samples are rounded to `uint16`.
4. Implement it in the engine as synthesised input records fed through `process`. The `Result` API then needs to return up to four results per input. Coordinate with whoever owns the driver wiring; the shadow class already expects `count`.

## Tests

- Fixture `clock-jump` exists, but its jump is 2000 or 40 000 s, outside the filling window.
- Add a fixture with several 480–1379 s steps at a consecutive sequence, development and holdout.
- `differential.py`: remove the `filled` cut-off and compare all outputs of a filled input.
