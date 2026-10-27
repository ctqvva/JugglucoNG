# iCan SDK boundary oracle

`provisioning-boundaries.json` contains ten outputs captured from the official SDK
in an offline probe on 2026-10-22. All inputs are synthetic: a 15-value list of
1.0f, with x1 replaced by each `value`; full F3 `06 F3 01 11 22 33 44`; SDK version
flag true and init/F7 flag false. Each `packets` array is the actual SDK output.

The fractional and sub-unit cases distinguish float32 multiplication followed by
truncation from rounding or double arithmetic. These are encoder inputs after
list construction; box-derived x13 replacement is tested separately.

No sensor identities, captured sensor tables, live challenges or credentials are
included. A private genuine-table/captured-F3 comparison additionally verifies
the complete per-box builder against the three official CE capture packets.
