# Native Sibionics alias history: evidence and migration proposal

This is a diagnostic follow-up to #897. It does not combine, rewrite, rebase,
recalibrate or render history, and does not change sensor ownership.

## What is established

The source contains a concrete route to distinct physical records:

1. `SibionicsLegacyMigration.migrateActiveSensors()` creates a managed record,
   saves `legacyNativeName`, and finishes the old native sensor entry. Its comment
   explicitly preserves the old native directory for history access.
2. The managed identity is namespaced, for example `SIBI:0111801JGS9`.
   `SibionicsBleManager.mirrorReadingsIntoNativeLocked()` subsequently writes
   that name through `ensureSensorShell` and the native batch writer.
3. `Sensoren.ensureDirectStreamShell()` first checks full and short names. An
   existing legacy name such as `240600111801JGS9` does not match the full
   managed name, so the managed shell can be created separately. Both records
   can retain readings. The production lookup regression demonstrates that
   the short alias `0111801JGS9` matches both records, including the retired one.

This establishes a reachable storage configuration, **not** that the reporter's
watch has it. The supplied screenshots and a phone's history-served status do
not establish which native directories exist or which hold data. No watch
trace/database dump confirming split records was available in this investigation.

## Obtain the missing device evidence

Export a trace on the affected device using the existing debug-log export.
Phone save/share prepends a diagnostic report; legacy/Wear trace export logs the
report before saving when logging is enabled. Collection/export runs off the UI
thread. The report is collected only at
export, outside normal history reads and acquisition/synchronization paths.

Only persisted Sibionics records with an explicitly registered legacy name are
inspected. The registry-only JNI lookup enumerates **all** matching full native
names, including retired records and short-name collisions. It never constructs
a `SensorGlucoseData`: doing so could initialize or repair native mappings.

The inspector opens existing `polls.dat` and optional `rawpolls.dat` files in
read-only mode. It does not call the native glucose-history getter, because that
can open native mappings and its `ScanData.valid()` helper can repair timestamps.
The diagnostic decoder's 20-byte poll layout and 2-byte raw layout are checked
against production native structs. It scans at most 100,000 slots per record
and reports the latest 14 days. It does not create missing directories/files.

Classification:

- `SPLIT_POLL_HISTORY`: distinct physical directories both contain usable poll
  rows in the diagnostic window. This is device evidence for split poll history.
- `SAME_NATIVE_RECORD`: both lookups identify the same physical record.
- `ONE_OR_BOTH_EMPTY`: distinct readable stores exist, but both are not populated
  in this window. It does not claim that older history is absent.
- `CANONICAL_MISSING`, `CANONICAL_AMBIGUOUS`, `ALIAS_MISSING`,
  `LOOKUP_UNAVAILABLE`, `INCOMPLETE_SNAPSHOT`: insufficient evidence.

The report includes record names, allocated slots, window row/minute counts,
first/last timestamps, overlap and exclusive coverage, stored-Auto/raw mismatch
counts, and resolved-value representation differences. It includes no glucose
values or pairing keys. Comparisons use the latest stored row in each clock
minute; Auto is the stored glucose word before native/software calibration.
Counts are a best-effort live snapshot, not an atomic migration manifest.

Matching overlap values are **not** algorithm proof. Empty, failed or truncated
reads do not authorize recovery. Re-export retries from scratch; no recovery
cursor, completion flag or new stored glucose value is written.

## The specific Auto-provenance blocker

`ScanData` stores timestamp, sensor index, glucose, trend and change, plus a
resolved-value representation bit. It does not store the algorithm model,
implementation revision, parameter set or historical calibration recipe that
produced each value. `SibionicsRegistry` stores the current selection, and
rebuilds can replace historical values in batches. Today's selection therefore
cannot attest to every old row, including a partially rebuilt record.

The native history getter also applies each record's current native calibration
when exporting Auto. Equal serials, view modes, current selections, calibration
counts or overlap values cannot establish compatible historical outputs.
Consequently this PR always reports `auto_algorithm=UNKNOWN`,
`historical_calibration=UNKNOWN` and `merge_allowed=false`.

## Concrete recovery proposal

1. **Gate on device evidence.** Require a split-history report and immutable,
   user-exported copies of both physical records, their `info.dat`, raw/temperature
   files, managed settings and any source journal. Preserve the originals and
   hash the inputs. Verify explicit identity binding, variant and sensor session;
   a shared short suffix is insufficient.
2. **Establish a replayable recipe or stop.** For future values, persist provenance
   per output generation/range: algorithm/model and implementation revision,
   parameter/factory-input fingerprint, session epoch, calibration inputs and
   revision, and whether the value is source or already resolved. A mutable
   per-sensor "current algorithm" field is insufficient. Old unknown Auto must
   remain unknown unless independent evidence establishes its recipe.
3. **Prefer source replay over importing unknown Auto.** A managed
   `SibionicsSampleJournal` retains index, timestamp, raw input, temperature,
   impedance and variant. If it covers the required session continuously from
   its start, or from a validated algorithm checkpoint, a read-only offline replay
   can reconstruct candidate values under a pinned canonical recipe. Verify
   required factory parameters and calibration inputs as well. Native Auto/raw
   values alone do not supply all of these inputs. Unknown Auto is never replay
   input. Incomplete source/checkpoint evidence means no automatic Auto recovery.
4. **Validate a history-layer projection.** With compatible provenance or validated
   replay, construct an immutable proposed history snapshot. Canonical clock
   minutes always win, including existing raw/Auto lane semantics. Add only
   missing historical minutes; do not change current readings, live cursors,
   native time origins, sensor selection, ownership, alarms or export semantics.
   Do not build a second chart-specific history pipeline.
5. **Publish atomically only after validation.** Prefer a retryable read-only
   projection first. Recheck source generation/hashes before publishing it. If a
   physical migration is later justified, write a separate staging store with a
   durable manifest, fsync and atomically publish it only after validating every
   sample; preserve originals and rollback. Never rebase or overwrite an active
   native poll file in place, and never mark recovery complete before commit.

Required regressions before enabling recovery: partial and minute-offset overlap;
matching values with different model/provenance; historical calibration changes;
mixed resolved/source rows; short-name collisions and retired records; old unknown
records; source-journal holes; session reset; interrupted read/replay/staging and
retry; concurrent local readings; byte-for-byte preservation of originals; and
unchanged canonical current-reading, synchronization and export behavior.

Until the evidence and recipe gates pass, the existing history behavior remains
unchanged and diagnostics explain why automatic reconciliation is not enabled.
