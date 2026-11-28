# CareSens Air private prototype

The legacy collector is the default. The private switch selects the Kotlin collector for a newly scanned CareSens Air sensor with no existing address, sequence cursor or poll history. The selected sensor keeps its collector across process restarts and same-package debug updates. If an update drops the private engine, the managed sensor pauses until the original engine is available again. Ordinary release and CI builds use the legacy collector.

## Build locally

Check out [OpenCareSens-air at `95c816f7`](https://github.com/ErikDeBruijn/OpenCareSens-air/tree/95c816f7f9b3e0452a7f2a88f61ef18ce997b3d5) outside the JugglucoNG checkout. From that directory, build the Java engine JAR. `OracleBinaryReader` is a fixture reader and the app does not use it.

```sh
mkdir -p private-classes
find java/src/main/java -name '*.java' ! -name OracleBinaryReader.java -print > private-sources.txt
javac --release 8 -d private-classes @private-sources.txt
jar --create --file opencaresens-air-local.jar -C private-classes .
```

From JugglucoNG, build with the absolute JAR path and opt in with the second property.

```sh
./gradlew :Common:assembleMobileDebug -PcareSensAirEngineJar=/absolute/path/to/opencaresens-air-local.jar -PcareSensAirManaged=true
```

Keep the JAR and resulting APK local. The experiment binds the session to the exact JAR SHA-256, so keep the same JAR for the full sensor session. Distribution of the combined APK is pending clarification of the upstream Java engine's license terms.

### Standalone test build

Add `-PcareSensAirStandalone=true` to build the vendor-free collector under its own Android application ID (`tk.glucodata.ng.debug.caresens`). Its APK excludes `libCALCULATION.so` for every ABI, including restored vendor inputs. Use a fresh sensor with this build. If a legacy CareSens sensor is imported into its data, it stays parked. Installing the standalone app alongside JugglucoNG leaves the other app's active session and history in its own storage.

## Migration and production cutover

An existing legacy session has its native sensor ID, glucose history, packed `sensorInfo` and a proprietary `generated` filter state. Its previous raw C5 records are unavailable in the legacy store. The managed collector needs the verified factory configuration and a contiguous raw replay from sequence one through the legacy session's current edge. Previously calculated glucose values can stay visible under the same sensor ID, but they cannot reconstruct that filter state.

For a production cutover, verify that the sensor can resend the complete raw sequence on real hardware, or collect that sequence from activation in a transitional release. Compare replayed results through the existing history edge before switching collector ownership. Keep a continuation path for active legacy sensors on direct upgrades from earlier versions. Removing the vendored release inputs also requires a distributable open engine, including confirmation of the applicable license terms.

## Verify

The [upstream oracle fixture](https://github.com/ErikDeBruijn/OpenCareSens-air/releases/tag/oracle-data) archive used for this prototype has SHA-256 `5849ef4d0993f88e5bfb6643c377865188d32451a4ea0982bd55ee5a239ed6ab`. Extract it locally, then run the harness. A missing lot, missing record, or comparison failure makes the command fail.

```sh
bash tools/test-caresens-air-oracle.sh /absolute/path/to/opencaresens-air-local.jar /absolute/path/to/oracle/output
```

The JVM tests also drive the collector through simulated discovery, serial verification, registration, PIN pairing, clock/start exchange, factory framing, record retrieval, native publication and recreation from the saved raw journal. The journal keeps raw sequence, sensor and receipt times, factory bytes, and the exact packed calibration configuration in app-private storage. Restart replays from sequence one. Duplicate frames leave the engine untouched, and a gap requests the last contiguous cursor again.

For physical testing, scan a fresh sensor with the private debug build. Verify activation and the first valid reading, then restart the app, interrupt Bluetooth long enough for several readings, and check the recovered timestamps and values. The `CareSensAirManaged` trace tag reports handshake failures without printing PINs or raw data. Compare the same raw input with the legacy algorithm offline to avoid two collectors on the transmitter.

The prototype accepts a checksummed shorter C2 transfer when the packet sequence contains the full packed v2+ factory parameters. Real packet boundaries, earlier factory library versions, and the legacy historical smoothing rewrites still need hardware evidence. The oracle validates final sequence, measurement time, glucose, trend, stage and error against five synthetic runs.
