# Automatic Sibionics probe calibration

The split labels in PR #408 carry a 14-character AI (21) probe code. The old
setup retained only the 8-character identity prefix for sensitivity decoding,
so both observed labels used the SIBIONICS2 fallback of 1.44.

## Official binary evidence

Source: `GS3_01.01.00.18/lib/arm64-v8a/libnative-sensitivity-v110.so`, also
byte-identical in the locally available `SIBIONICSGS3` split APK.
SHA-256: `9b176c18d4346c04cc1e55fec29c5e892b72ec136f5a8bbf4bfe1c43a84109fe`.
Exported `connect_decode(char*, char*, int&, char*)` at ELF address `0x15bd4`;
`SensitivityEncrypt::sens_decode` at `0x19084`, `batchnum_decode` at `0x17d54`,
and `serialnum_decode` at `0x1899c`.

The original ARM64 instructions were executed with Unicorn, resolving ELF
relocations and providing guest memory, allocation, TLS, and libc operations.
The calibration functions themselves were neither replaced nor mocked.

| Probe | Native batch | Native sensitivity | Native serial suffix | Application sensitivity |
| --- | --- | ---: | --- | ---: |
| EU2VCZUQPSHD5Q | 260201 | 173 | KW78 | 1.73 |
| 145TUMXYK4S46V | 250651 | 175 | CU24 | 1.75 |
| XPT1EEX2NRU16U | 251212 | 126 | HF20 | 1.26 |

The first two batch/suffix outputs match the printed serials
`P2260201675KKW78` and `P2250651231GCU24`. Their native sensitivities divided
by 100 agree with the independently established P-format calibration decoder.
These are dimensionless calibration coefficients; no mg/dL/mmol conversion or
native storage format changes.

The Kotlin port preserves the native version discriminator, outer checksum,
sensitivity bit permutation and inner checksum. It also requires valid batch
and serial checksums. The native outer wrapper ignores component failures:
`J45TUMXYK4S46V` returns success with sensitivity 239 but an empty batch. Kotlin
rejects that incomplete decode. Of 868 single-character substitutions across
the two observed probes, native rejects 865 and reports success for three with
invalid batch output; Kotlin rejects all 868.

`Common/src/test/resources/sibionics/probe-sensitivity-native.tsv` contains
73 valid outputs from the original binary: the three observed codes and 70 generated
code vectors spanning sensitivity 0.80–2.50. Synthetic vectors test conformance;
they are not additional physical sensor observations.

To reproduce a row with a locally obtained copy of that exact library:

```sh
python3 -m venv /path/to/oracle-venv
/path/to/oracle-venv/bin/pip install unicorn==2.1.4 pyelftools==0.32
/path/to/oracle-venv/bin/python tools/sibionics-probe-oracle.py \
  --library /path/to/libnative-sensitivity-v110.so \
  EU2VCZUQPSHD5Q 145TUMXYK4S46V XPT1EEX2NRU16U J45TUMXYK4S46V
```

The binary is not redistributed. The harness is an analysis tool, not shipped
application code. Its outputs verify factory-code decoding, not end-to-end
BLE or glucose-display agreement with a live official app.

## Setup and continuation

Only validated SIBIONICS2 QR labels use this decoder. The full probe is stored
separately from sensor identity and the legacy short code; sensor aliases and
BLE matching retain their existing meanings. Existing explicit user sensitivity
overrides still win. Unrecognized codes retain the previous fallback behavior.

Old installations did not retain the full code, so those records need a rescan.
A rescan updates an active callback, including one retained under a BLE alias.
When the effective sensitivity changes, the old stream is closed and the existing
startup recovery path rebuilds exact continuation from the local journal, or
requests contiguous replay from index 1 if inputs are missing. It cannot continue
at a mid-life index with freshly reset algorithm state. No printed-serial prompt
or hardcoded probe-to-value lookup is involved.
