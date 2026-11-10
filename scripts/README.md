# Repository scripts

These build helpers locate the repository themselves and accept Gradle options:

- `scripts/debug.sh [Gradle options]` builds the phone debug APK (`assembleMobileDebug`).
- `scripts/makeapk.sh [Gradle options]` builds the Wear release APK (`assembleWearRelease`), preserving the original helper's target.
- `scripts/build-dist.sh {phone|phone-all|wear|wear-all|all} [Gradle options]` builds and verifies production-signed distribution APKs. See [distribution.md](../docs/distribution.md) for private inputs and release procedures.

You can invoke these helpers by their path from any working directory. Gradle's build files and wrappers stay at the repository root. CI continues to use its existing Gradle and distribution entry points.

Developer probes and test runners live in `tools/`. The former root staging lists, obsolete build backup, and one-off translation scripts are retained as [historical files](../tools/archive/root-cleanup/README.md).
