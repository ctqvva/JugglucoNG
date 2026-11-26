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
| `make_fixtures.py` | writes the reference fixtures the unit tests use |
| `differential.py` | randomised sweep; prints the first stage that differs |
| `engine-cli/EngineCli.kt` | command-line driver for the engine, used by `differential.py` |

Regenerating fixtures with a different library build changes their expected
values; `make_fixtures.py` warns when the library's sha256 is not the known one.
