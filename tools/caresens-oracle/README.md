# CareSens Air differential oracle

Compares the open calibration engine
(`Common/src/main/java/tk/glucodata/drivers/caresens/calibration`) with i-SENS'
`libCALCULATION.so`. See `docs/caresens-air-open-calibration.md`.

The vendor library is not part of the repository. Point
`CARESENS_REFERENCE_LIB` at a local arm64-v8a copy (the one
`scripts/restore-build-inputs.sh` installs). `oracle_host.c` loads it into an
arm64 macOS or Linux process; no Android device or emulator is needed.

| File | Purpose |
|---|---|
| `oracle_host.c` | minimal loader for the arm64 Android library |
| `caresens_oracle.py` | struct layouts from `air/air.hpp`, ctypes wrapper |
| `scenarios.py` | deterministic record generator, mirrored by `CareSensAirScenarios.kt` |
| `make_fixtures.py` | writes reference fixtures; `--fixture NAME` writes just that fixture and updates the index |
| `differential.py` | randomised or named fixture sweep with per-bit mismatches, publication-gate differences and first failing stage |
| `engine-cli/EngineCli.kt` | command-line driver for the engine, used by `differential.py` |
| `research/` | experiment scripts, small datasets and the vendor state offset map behind the error-check findings; see `research/README.md` and `docs/caresens-air-handoff/` |

Regenerating fixtures with a different library build changes their expected
values; `make_fixtures.py` warns when the library's sha256 is not the known one.

Use Python 3.9 or later with NumPy. The sweep needs `kotlinc` and Java on
`PATH`, or `KOTLINC` and either `JAVA` or `JAVA_HOME`. The default scored
error mask is 4, 32 and 64. For example, `--modelled 0x74` also scores bit
16. The [task 00 notes](../../docs/caresens-air-handoff/tasks/00-shared-infrastructure.md)
describe the comparison boundaries and how to add a detector.
