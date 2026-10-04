# Experimental Libre 3 source security engine

## Decision

JugglucoNG keeps its established Libre 3 BLE security engine as the default. A
separate `l3experimental` build type compiles and selects the source engine from
Juggluco 10.10.0. It uses the same application ID so testers can install it over
their existing app and return to a standard APK without losing app data.

This is deliberately an experimental build, not a silent replacement. Removing
the closed-source runtime dependency would make the Libre 3 path inspectable,
maintainable, and portable, but a successful compile is not evidence that a
newly activated or already-running sensor will accept every recovered handshake
path.

## Source provenance and remaining uncertainty

The native implementation and generated tables are imported from Juggluco
commit [`4f8d94b4`](https://github.com/j-kaltes/Juggluco/commit/4f8d94b4)
(`10.10.0`) plus follow-up commit
[`11d016eb`](https://github.com/j-kaltes/Juggluco/commit/11d016eb3aeffe77e86d9522f5192e83790b5a21),
which adds the required SHA-512 binding. NG adds only the JNI boundary, build
isolation, strict result validation, and rollback-safe authorization lifecycle.

The imported engine itself says that the generated-object key derivation remains
the live-device gap. It also contains recovered white-box tables and captured
challenge material, with no upstream live-sensor regression suite. Consequently,
host tests can verify record encoding, bounds, build isolation, linking, and
known control flow, but only hardware testing can establish sensor compatibility.

## Safety boundaries

| Standard build | Experimental build |
| --- | --- |
| Compiles and calls the established `processint` / `processbar` engine | Also retains the established symbols, but routes the Java handshake through the source JNI API |
| Uses the existing native authorization record | Uses a separate `libre3_source_auth_experimental` preferences file |
| Existing disconnect cleanup is unchanged | Never writes or clears the established authorization record |
| Normal app name and version | Distinct launcher name, version suffix, startup toast, and About warning |

Every native result is checked for the exact protocol length or success code.
A fresh authorization record is persisted first as a **candidate**, before data
notifications are enabled. It is promoted to **verified** only after the existing
CCM stream layer authenticates and decrypts a complete one-minute sensor frame.
Neither a handshake error nor status 19 removes the candidate, verified record,
or the established engine's authorization. This preserves retry evidence and
keeps reinstalling the standard APK as the rollback path.

R8 minification and resource shrinking are disabled only for the experimental
build. This reduces peak build memory and keeps early hardware-test stack traces
readable. The standard release build remains minified and unchanged.

## Test protocol before considering default use

1. Keep the matching standard APK on hand and confirm it can read the active
   sensor before installing the experimental APK.
2. Test reconnection to an already-authorized Libre 3 sensor first. Confirm a
   complete authenticated minute packet arrives, then force Bluetooth and app
   restarts and confirm reconnection.
3. Test a new sensor activation only after the reconnection path is repeatable.
   Record app logs, Android version, sensor generation, and sensor firmware.
4. On any repeated failure, reinstall the standard APK. Do not clear app data,
   remove the sensor, or retry activation with another reader until the preserved
   established authorization path has been checked.

Promoting this engine to the normal build requires successful testing across
multiple sensor states and devices. This PR creates a controlled way to collect
that evidence without making current users the experiment.
