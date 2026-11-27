# Task 06: error 8

Setup and conventions: `00-shared-infrastructure.md`. Background:
`../findings.md`, section "Errors 8 and 128".

## Goal

Find any input that sets bit 8. Then decode it, or show it is unreachable
for the inputs JugglucoNG passes: a device block from the transmitter, no
calibration list, and records from BLE.

## Known

- **Never seen.** It did not fire in 268 survey runs, 18 signal transforms, or `stress2.py`:
  - temperatures 10–50 °C, 0 °C, −5 °C and NaN
  - alternating 30/38 °C
  - a sequence wrap at 65535
  - the clock going backwards
  - a duplicate sequence number
  - a start at seq 4000
- **Debug.** `err8_condi[2]` (debug 1249) was always 0.
- **State.** Only `err8_result_prev` (48825). There is no history of its own, so the conditions use other detectors' state or the current record. `err_glu_arr`, the `err4_*` minimum and range histories, or the ISF are candidates.
- **Device block.** The `err345_*` block covers errors "3, 4, 5". If those are bits 4, 8 and 16, error 8 reads `err345_*`:
  - `filtered` {0.2, 1.0}
  - `min` {0.5, 0.3}
  - `range` −1
  - `n_range` 2
  - `md` 0
  - `n_md` 6
  - seq tables `err345_seq1..5`, with entries 3, 576, 5, 864, 24, 11, 23, 12, 288, 36

  This is a hypothesis from the names. `err345_range = −1` with `md = 0` could be "disabled" sentinels.

## Suggested next steps

1. **Read task 00 A's table.** If an `err345_*` field changes bit 8 or `err8_condi`, the sentinel defaults may disable it. Set `err345_range` ≥ 0 or `err345_md` > 0 and look for firing.
2. **Perturb the state.** Set `err8_result_prev = 1` and see whether the latch is permanent or shared, which shows its family. Also set the `err4_*` histories to extreme values.
3. **Try signal shapes not yet tried:**
   - a frozen ADC (an identical value for hours)
   - a record whose 30 samples are constant but the records step
   - a range collapse over `n_range` records
   - very long gaps with a consecutive sequence
4. **Check `errcode_version`** (default 2) at 0, 1 and 3.

If nothing fires after these, document it as unreachable with the defaults
and the transmitter-sent blocks seen in testers' logs. The engine then
leaves bit 8 at 0, and the device block values in real logs decide whether
that is safe.
