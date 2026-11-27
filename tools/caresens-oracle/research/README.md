# CareSens Air research scripts

These are the experiment scripts behind `docs/caresens-air-open-calibration.md`
and the task specifications in `docs/caresens-air-handoff/`. They were run one
at a time while studying the vendor library's behaviour (black-box only) and
are kept as historical experiments, with paths and shared harnesses repaired
where needed. Most print numbers that only make sense with the notes in
`checkpoint-2026-11-27.md` or the handoff findings.

## Setup

The library and the tool versions are the same as for the main oracle
(`../README.md`):

```
export CARESENS_REFERENCE_LIB=/abs/path/arm64-v8a/libCALCULATION.so   # sha256 0bc4e28b…02d4cf
export JAVA_HOME=/path/to/jdk17          # differential.engine_jar / run_engine
cd tools/caresens-oracle/research
python3 -c 'import stress, lab; print(stress.REF.sha256)'
```

You need an arm64 host (Apple silicon or arm64 Linux), Python 3 with numpy,
`cc`, and `kotlinc` for anything that runs the engine. scipy is used by a few
early scripts.

## Two harness generations

| Harness | Modules | Used by |
|---|---|---|
| current | `sc.py` (imports `../caresens_oracle.py`, `../scenarios.py`, `../differential.py`) | `e178`–`e292`, `stress*.py`, `craft.py`, `err_survey.py`, `dg*.py`, `c0*.py` |
| legacy | `lab.py` + `o.py` (uses the current oracle loader), `gen.py`, `ref.py`, `kt.py` | `e1`–`e177` (calibration chain, filling, timing error) |

`sc.REF` is `caresens_oracle.Reference()`. `REF.run(dev, recs, state=…,
snapshots=…)` returns `(count, out, dbg, state, snaps)` and **mutates the
`state` you pass** — always pass a copy. `count[i] > 1` means the library
filled missed records for input `i` (up to four outputs).

The legacy wrappers return the first slot per input. They reserve four slots
for the library so a filled record cannot overwrite the next input's output.
`e89.py` reads all slots explicitly. `oracle.c` is the original loader
archived with the experiments.

The Kotlin helpers compile against the current engine. From the repo root:

```sh
research=tools/caresens-oracle/research
engine=Common/src/main/java/tk/glucodata/drivers/caresens/calibration
kotlinc $(find "$engine" -name '*.kt' ! -name CareSensAirOpenCalibrationShadow.kt) \
    "$research/kotlin/Cli.kt" "$research/kotlin/StateDiff.kt" \
    -include-runtime -d "$research/eng.jar"
```

`CARESENS_LEGACY_JAR` can point `kt.py` to a jar elsewhere. Run the numbered
scripts from the research directory so local dataset and script paths resolve.
`t.py` is a historical cross-check that needs the separate early emulator
under `../../h`. That emulator was outside the handoff.

Task 00 syntax-checked all 307 Python scripts and matched the regenerated
state-offset map byte for byte. Both oracle wrappers agreed on a smoke input
with a filled call and a subsequent record. The relocated legacy CLI passed
engine save/restore and vendor-state takeover on that same input. Individual
historical experiments were left for the tasks that use them.

`lab.dev()` now takes the default device block from `caresens_oracle`
(originally a scratch C build of the same defaults). `ref.py` is an early
Python model of the calibration chain; the Kotlin engine supersedes it.

## Shared helpers (current harness)

| File | What it does |
|---|---|
| `sc.py` | `runspec(spec, snap)`: generate a scenario, run the vendor, return inputs, outputs, debug and snapshots |
| `stress.py` | base scenario (seed 21, 1500 records) plus 18 transforms on records 500–700; `python3 stress.py` prints first/count/last per error bit |
| `device_parameter_survey.py` | task 00's one-field-at-a-time sweep; emits the tables in `docs/caresens-air-handoff/device-parameters.md` |
| `stress2.py` | temperature, sequence wrap, clock backwards, duplicate sequence, start at seq 4000 (err8/err128 search) |
| `craft.py` | `adc_for(current)`, `run_plateau(current, K, a, n)`: flat-current plateaus at a chosen record |
| `err_survey.py` | which bits fire in all fixtures plus 250 random specs; writes `err_survey.json` |
| `err16lib.py` | the exact 12-tap err16 kernel and the shared seed-11 decay corpus generator |
| `err16_state_probe.py`, `err16_statistics.py`, `err16_rules.py` | isolate and verify error-16 histories and condition rules against vendor snapshots |
| `err16_history_probe.py` | verify sequence-window statistics and replay the missing-sequence and early-reference condition boundaries |
| `err16_isf_source.py` | reproduce the revised-current dependency and isolate it with a single-state replay |
| `err16_replay.py` | compare the isolated Kotlin research detector against the oracle; see the [error-16 evidence](../../../docs/caresens-air-handoff/error-16-evidence.md) |
| `rules.py`, `cart.py` | greedy AND-rule search and a small CART tree, used for err16, err4 D tests, err1 |
| `c0model.py`, `c0m2-4.py` | err4 condition 0 model scoring on `c0runs.pkl` (c0m4 is the final, exact one) |
| `dg.py`, `dg2.py` | `python3 dg.py "<spec dict>" <record>` prints vendor vs engine around a differing record |
| `state_map.py` | writes `state-offsets.txt`: offsets of every vendor state, debug and output field (no library needed) |
| `INDEX.txt` | auto-generated: per script, harness, topics touched, first statement |

## Datasets

Committed (small; next to the scripts, which read and write datasets in the current directory, so run them from here):

| File | sha256 (prefix) | Written by | Contents |
|---|---|---|---|
| `err_survey.json` | `2e014abd` | `err_survey.py` | per run: spec, first/count/last record of each error bit |
| `err16_tr.pkl` | `78d86a1e` | `e228.py` | 205 err16 condition transitions; all 38 err16 debug scalars before/after (plain dicts) |
| `c0specs.json` | `4f466fed` | `e274.py` | the 80 noisy-dropout specs that made err4 condition 0 exact |

Not committed because they are 0.1–65 MB. All are deterministic (seeded) and
need the reference library to regenerate. They were not re-run for this
handoff, so these sha256 values identify the copies that were analysed:

| File | Size | sha256 (prefix) | Regenerate | Contents |
|---|---|---|---|---|
| `err16_ds.npz` | 65 MB | `82d08332` | `e226.py` | X 198 560 × 38 err16 scalars, Y err16_condi[7], M (run, seq); 160 decay runs, seq ≥ 600, every 3rd record |
| `c0runs.pkl` | 7.4 MB | `20f6594b` | `e273.py` | 80 dropout runs: seq, type, raw max/mean, 5-min current, err4_condi, errcode |
| `c1runs.pkl` | 8.8 MB | `cf9063c5` | `e285.py` | 120 runs, start seq 1–3000: type, err4_condi, err4_range/min/min_diff, 5-min current (err4 condition 1 search) |
| `c0rows.pkl`, `c0rows2.pkl` | 6.8 / 11.9 MB | `2431e96d`, `658c6572` | `e271.py`, `e272.py` | per-record rows for the condition 0 search |
| `d1X.npy`, `d1Y.npy` | 1.4 MB / 96 KB | `dbf67a79`, `50e54f41` | `e280.py` | features and labels for the D1 delay-flag tree |
| `d2rows.npy` | 10.8 MB | `2c6779f5` | `e263.py` | D-test rows across phases |
| `fillB.npy`, `kern.npy` (committed) | 1 / 4 KB | | `e98.py`; `e22.py` | filling unit responses (task 08); a calibration-chain kernel from the early phase |
| `cur_rows.pkl`, `tran_rows.pkl` | 74 / 527 KB | `b608b7c5`, `febf2a08` | `e184.py`, `e186.py` | ADC-to-current rounding candidates (sample current ±4 ulp) |

Some legacy consumers also use local intermediate files. `e73.py` writes
`dt2.npy` for `e74.py`. `e119.py` and `e176.py` write `lvl.npy` and
`lvl2.npy`. `e181.py` writes the inputs for the legacy `StateDiffKt` CLI.

## Where results are written up

- `docs/caresens-air-handoff/findings.md`: confirmed rules, rejected hypotheses and unexplored avenues, citing these scripts.
- `docs/caresens-air-handoff/tasks/*.md`: one self-contained specification per open problem.
- `checkpoint-2026-11-27.md`: the raw lab notebook of the error-check phase.
