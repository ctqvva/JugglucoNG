# Storage ownership

Status: **first pass against `main` at `05f9a9fb1` (#478), then one pass per facet (#480, #482,
#483, #484) and a correction pass against `da788a73f`.** P4 ("one owner per kind of data") needs a
document to point at, and this is that document written down before anyone needs it to be true.
Every facet is now traced; what remains are questions within facets, marked **not established**
or stated as open. Those gaps are deliberate: an invented answer here would be worse than an open
question, because Q3 and any storage contract are supposed to be built on this file.

## What "owner" means

One authoritative writer per kind of data (P4). Everyone else reads, or reconciles. A second
writer is not a bug to be tolerated, it is a change that needs a line added to this file saying
why the single owner no longer suffices. "Reads" includes writing to its own cache; the test is
whether a reader can leave the datum in a state the owner would not have produced.

A variant that lacks a capability does not get to own a copy of the datum (P2, and the table in
`direction.md` §2: sensor state and secrets are "never exported, never mirrored to the watch").
This is the line the watch rows below sit on, but it is not a blanket "the watch never writes":
in direct-sensor mode the watch *is* the writer for the live reading, and for journal and
calibration it holds copies and issues commands. What the watch never becomes is the authority
on another device's state.

## Ownership

| Datum | Authoritative writer | On the watch |
| --- | --- | --- |
| Live reading (current value) | **not a single owner** -- see below: whichever device holds the sensor, decided at runtime | the watch is the writer in direct-sensor mode |
| History (readings over time) | `HistoryRepository` (Room, `src/mobile`) | **no `HistoryRepositoryAccess` registered** — reads only |
| Calibration (parameters, profile) | the phone, `CalibrationManager` (Room, `src/mobile`) | `SyncedWearCalibrationProvider` — holds a copy, commands over `/sync2/calcmd` |
| Journal (treatments, notes, entries) | the phone, via the journal accessors | holds a synced copy and issues commands over `/sync2/journal/cmd` |
| Sensor metadata + driver state + credentials | the sensor's own store: native mmap, and a prefs file per driver | never mirrored |
| Setting | the native settings block (`settings.hpp:315`, the bitfield struct) | its own local block; see the caveat |

Both `HistoryRepository` and `CalibrationManager` are in `src/mobile`, so the watch cannot reach
them even if it wanted to. `Specific.registerBridges()` makes that explicit: the phone registers
`MobileHistoryRepositoryBridge`, `MobileCalibrationProvider` and `MobileCalibrationProfileBridge`;
the watch registers only `SyncedWearCalibrationProvider`, and registers no history repository and
no calibration profile at all. That asymmetry is the enforcement for history: the watch registers no writer
at all, so there is nothing to drift. Calibration is weaker, because the watch
registers a synced provider -- the invariant there is not "the watch does not write" but "the
watch is never the authority", which is a rule rather than a compiler-enforced absence.

## The seven facets

### Identity

A history row is identified by **(`sensorSerial`, `timestamp`)**, enforced as a unique index on
`history_readings` (`HistoryReading.kt:19`); the `@PrimaryKey` is an auto-generated `id`, so the
identity is the pair, not the row. `HistoryReading` also carries `value`, `rawValue`, nullable
`rate`, `source` and `firstStoredAt` -- the last being insertion time, which records when the app
learned of a reading, not when the sensor saw it.

**For sensor data the effective identity is (`sensorSerial`, minute), not the exact
timestamp.** The index collides only on exact equality, but both entry points the sensor producers
use delete the whole minute before they insert:

- **Live readings** (`storeCurrentReading*Async` -> `HistoryRepository.storeReading`) delete every
  row of that sensor in the reading's 60-second bucket (`deleteSensorRowsInBucketRanges`), then
  insert the one row.
- **Batches** (`storeSensorHistoryBatch*`, and the native sync in `HistorySync`) go through
  `storeReadingsReplacingSensorBuckets`, which first collapses the batch with
  `HistoryBucketReplacement.collapseReadings(..., SENSOR_MINUTE_BUCKET_MS = 60_000)` -- keyed on
  `timestamp / bucketDurationMs`, one row per bucket -- and then deletes the covered buckets
  before inserting.

So a sensor row at 12:00:10 is replaced by a later sensor write at 12:00:40; the stored row keeps
the exact millisecond of whichever reading represents the minute, but two sensor rows never share a
minute.

**The exact-millisecond paths are the imports.** `storeReadings` neither collapses nor deletes
buckets, and its only caller is the file import (`HistoryExporter`, `source = IMPORT`); clone
recovery writes with `insertAllIgnoring`. An imported row at 12:00:30 therefore coexists with a
sensor row at 12:00:10 -- until the next sensor write into that minute deletes it. Which of the two
happens is decided by the entry point, not by anything the datum asserts.

Readings with `timestamp <= 0` are dropped by the collapse rather than stored.

### Authoritative writer

Per the table above. Two rows carry more structure than the others:

- **Live reading has no single owner, and that is the important row.** Which device reads the
  sensor is decided at runtime: `WearSensorClaim` and `SensorOwnershipRuntime` own the claim, and
  the `/sensorhandoff` and `/sync2/own` messages move it between devices. In direct-sensor mode
  the **watch** writes the live reading and the native stream, and `WearSync2` carries those
  readings to the phone, which stores them in Room through `HistorySyncAccess.storeSensorHistoryBatchAsync`
  (`WearSync2.kt:513`). So ownership of this datum is per-device and conditional, and a
  document that names one writer for it would be wrong. The native mmap store is where the
  owning device keeps it, with `VirtualGlucoseSensorBridge` and `VirtualSensorNativeMirror`
  (both `src/main`) between it and the drivers; which of the three writes for a given driver is
  **not established**.

  History has **one writer with many producers**, and that is worth stating precisely because I
  first got it wrong in the other direction. `HistorySyncAccess.store*` is called from
  `SuperGattCallback` (native-backed sensors), from every managed driver -- `OttaiBleManager`,
  `SibionicsBleManager`, `AiDexBleManager`, `ICanHealthBleManager` -- from
  `VirtualGlucoseSensorBridge`, and from `WearSync2` for readings the watch took over. All of
  them land in `HistoryRepository`, so the datum has an owner and the producers are not writers.

  The open question here is not "how many producers" but **which device's producer is
  live for a given sensor at a given moment**, which is what `SensorOwnershipRuntime` decides. A
  take-over is the case worth naming, because two producers can briefly overlap, and what a double
  write does is settled by the schema rather than by policy: `history_readings` carries a unique
  index on `(timestamp, sensorSerial)` (`HistoryReading.kt:19`), and `HistoryDao` offers both
  `OnConflictStrategy.REPLACE` (`insert`, `insertAll`) and `OnConflictStrategy.IGNORE`
  (`insertAllIgnoring`, `insertDeletedReadings`).

  **Traced, so the take-over case does not have to be guessed at.** The seven sensor producers
  enter through `HistorySyncAccess.store*` and reach `HistoryRepository.storeReading` (live) or
  `storeReadingsReplacingSensorBuckets` (batches); `storeReadings` is the file-import path, not a
  sensor one. Both sensor paths delete the minute bucket before `dao.insert` / `dao.insertAll`, so
  on those paths it is the bucket delete, not `REPLACE`, that makes the later write win -- and it
  wins even when the two timestamps differ inside the minute. On `history_readings`, `IGNORE` is
  used in exactly one place, `CloneGlucoseRecoveryStore` (`historyDao.insertAllIgnoring`, and
  `uncertaintyDao.insertAllIgnoring` on its own table), so clone recovery is first-write-wins while
  the sensor path is last-write-wins. (`insertDeletedReadings` is also `IGNORE`, but on the
  tombstone table.)

  Provenance is folded in before the write, via `HistorySourceProvenance.stableSource` and
  `stableFirstStoredAt`, **but only for a row with the exact same timestamp**: `storeReading`
  matches `it.timestamp == timestamp` and the batch path keys its lookup on the timestamp. So if
  the two devices stamp the overlapping reading to the same millisecond, `source` and
  `firstStoredAt` survive the take-over and only the measurement columns (`value`, `rawValue`,
  `rate`) are replaced. If they stamp it differently within the minute, the earlier row is deleted
  and the incoming row's provenance stands. Whether phone and watch produce identical timestamps
  for the same sensor reading is **not established**, so "provenance survives a take-over" is
  conditional, not a property of the code.

- **Journal.** The plan says "the journal accessors, one per kind". The registry carries several
  (`JournalAccess`, `JournalTreatmentUploadAccess`, `NightscoutTreatmentImportAccess`,
  `LibreviewJournalEntriesAccess`, `JournalSnapshotAccess`) and the phone registers each. Whether
  one of them is the writer or all of them are is **not established** -- `OutboundApiJournalSnapshot`
  was looked up in three places before Q1, so "one writer" is a claim to check rather than assume.

### What counts as a duplicate

Recorded, not guessed:

- A second **registration** of the same implementation in `registerBridges()`. The guards exist
  because `registerBridges()` runs twice per process (from `onCreate` and again from the
  variant's `start()` as a safety net), so a second `register` is a real event and not a
  theoretical one.
- A **Room write that bypasses the owner** -- an insert into the history or calibration tables
  from anything other than `HistoryRepository` / `CalibrationManager`.
- **Clone recovery is the known second writer**, and the plan calls it a fourth writer after the
  two Room owners and the native store. It is a whole subsystem in its own right: staging, an
  import ledger, a package IO layer, a wake path, and mobile-side coordinators and bridges. The
  ledger and staging classes are the design's answer to "how does a second writer stay
  reconcilable", and **how that reconciliation works is not established** -- it should be the
  next thing written here, because it is the only sanctioned second writer.
- A **native-side write** to a datum a Kotlin owner also writes. `settings.hpp:315` is the
  clearest case: `dontuseclose` is a bitfield in the persisted block, written by
  `javasettings.cpp:2272` and read straight off the struct at 2276 with no cache. That is why the
  `useclose` cache was removed in #477 -- the cache was a second writer with no owner.

### Ordering

Rows are ordered by `timestamp`, and the secondary index on `(sensorSerial, timestamp)` exists to
serve that. That much is settled.

**Within a minute there is no order for sensor data**: the batch path's `collapseReadings`
discards sub-minute positions, and the live path deletes the minute before inserting, so a bucket
holds exactly one sensor row. Across writes, the later write simply wins. Within one batch, the
choice of which reading represents a
bucket is a real policy, not an accident -- `choosePreferred` scores each candidate, +10 for a
finite positive `value` and +5 for a finite positive `rawValue`, and only when the scores tie does
it fall back to the **later** timestamp. So quality beats recency, and recency is only the
tiebreak. A minute whose two readings both look valid is represented by the later one; a minute
where only the raw value is usable keeps the reading that at least has a value.

`firstStoredAt` does not help here: it is when the app stored the row, so on a re-sync it moves for
a reading whose sensor time did not.

Note that the display layer merges on the same 60-second granularity
(`HistoryDisplayMerge`, with its own `SENSOR_MINUTE_BUCKET_MS`), which is consistent with the write
path but for a different reason: display grouping, not storage identity.

### Clock rollback

There is a **forward** guard, and it is duplicated. `MIN_REASONABLE_TIMESTAMP_MS` and
`MAX_FUTURE_TIMESTAMP_DRIFT_MS` are each declared twice with identical values --
`946684800000` (2000-01-01T00:00:00Z) and 10 minutes -- once in `VirtualGlucoseSensorBridge`
(lines 29 and 30) and once in `ApiGlucoseSourceManager` (lines 44 and 45). Each file then applies
both bounds to its own acceptance check. So the timestamp-sanity policy is two copies of one rule,
and changing one does not change the other. Worth recording as a policy fact rather than a code
change: the rule itself is coherent, its placement is not.

Past the bounds, there is no central handling. A reading is accepted when its timestamp falls
inside the window, and a wall-clock rollback is not detected anywhere in shared code: the searches
for rollback, clock-change and backward-time handling find nothing, and the only rollback logic in
the tree is `AnytimeBleManager`'s `GLUCOSE_ID_ROLLBACK_RESET_THRESHOLD`, which is a **sensor
glucose-id** counter rolling back, not the clock. #424 is the tracked instance of the general
problem, for one driver.

The consequences follow from the guards rather than from a policy. A clock rolled **back** leaves
timestamps inside the window and inside the past, so they are accepted and sort *before* existing
rows, and `DisplayDataState` reports them as stale because `ageMillis` is large. A reading dated
**ahead** of the phone's clock is caught only on the two paths that carry the bounds: the virtual
sensor bridge and the API sources reject it beyond ten minutes, and `pruneFutureHistory` (virtual
bridge only) trims rows past that point. Everywhere else it is stored: the Room layer's
`reportIfFutureTimestamp` logs a warning, rate-limited per sensor, and does not reject. So the
handling is *forward-drift-aware on two paths, logged on the rest, and backward-drift-blind*, and
the residual questions are whether the forward bound belongs in `HistoryRepository` for every
producer, and what a rolled-back clock should do to readings taken during the affected window --
reject them, or accept and re-anchor them.

### Reconciliation

Two mechanisms, and only one of them is a merge.

**Tombstones are how history reconciles against re-imports.** `history_deleted_readings` holds
(`timestamp`, `sensorSerial`, `deletedAt`) rows, every batch write runs `filterDeletedReadings`
before it writes and `storeReading` checks `isReadingDeleted`, so a re-sync or a clone import cannot
resurrect a reading
that was deliberately deleted. `CloneGlucoseRecoveryStore` advances tombstones by comparing
`afterDeletedAt` against the last one, tombstones travel in clone records and in the outbound
journal snapshot, and `ExportPackageExporter` emits them. The point of the table is that deletion
is itself data: without it, any source that still has the reading would put it back on the next
import.

**Provenance is reconciled before the write, not after.** On both sensor paths an existing row
with the same (`sensorSerial`, `timestamp`) is read and folded in through
`HistorySourceProvenance.stableSource` and `stableFirstStoredAt`, so `source` and `firstStoredAt`
survive a rewrite of the same reading while the measurement columns are overwritten. A different
timestamp in the same minute gets no fold. This is the take-over behaviour described under the
live-reading row.

**A live-versus-history reconciliation exists but is a freshness rule, not a merge.**
`DisplayDataState` takes `maxOf(currentTimestampMillis, latestHistoryTimestampMillis)` and classifies
the result as no-sensor / awaiting / fresh / stale against `Notify.glucosetimeout`. The tie-break
is "newest wins", which is a policy statement and not an averaging or a preference.

Which deletions leave a tombstone:

- **Deleting one reading** (the dashboard, `HistoryRepository.deleteReading`) writes a tombstone
  for the serial and each of its query aliases, then deletes the rows, in one transaction. A later
  import or re-sync cannot bring it back.
- **`HistoryRepository.deleteForSensor`** is a hard `DELETE` with no tombstone, and that is correct
  for what it is: the wipe `HistorySync` does before a full re-sync (`HistorySync.kt:475`), after
  which the rows are expected to come back with recalibrated values.
- **"Delete glucose history" on sensor removal** (`SensorViewModel` ->
  `deleteAllHistoryForSensor`) also writes no tombstone. Nothing re-syncs it locally, because the
  sensor is no longer in `Natives.activeSensors`, but nothing stops a clone import or a file
  import of that sensor's rows from putting them back. Whether a sensor-wide delete should
  tombstone is the open question here.

### Deletion

Traced per driver, and it is **not uniform**. Two different lifetimes for two kinds of
credential, which is the finding here.

**iCan ties its secret to the sensor.** `ICanHealthRegistry.removeSensor` drops the record set
and then removes, in one commit, `PREF_AES_KEY_PREFIX`, `PREF_DEVICE_SN_PREFIX`,
`PREF_AUTH_USER_ID_PREFIX`, `PREF_RECOVERED_USER_ID_PREFIX`, `PREF_AUTH_BYPASS_UNTIL_PREFIX` and
the two history-edge keys. The AES keys also have hardcoded defaults in `ICanHealthConstants`
(`DEFAULT_OLD_GLUCOSE_AES_KEY_ASCII` and friends), which are source constants and no deletion
touches them -- so deleting a sensor removes that sensor's stored key, not the vendor default
that ships in the binary.

**AiDex holds two secrets, and removal treats them differently.**

- The **PAIR key** lives in `AiDexPairKeyVault`. It is cleared after the sensor confirms an unpair
  (`AiDexBleManager`, `clearAfterConfirmedUnpair`), or by the manual "delete pairing key" action
  in `SensorCard` (`SensorViewModel.forgetAiDexPairKey`), which exists for a key that an unpair
  elsewhere invalidated. `AiDexNativeSensorManager.removeSensor` itself does not touch it. The
  removal dialog in `SensorCard` has an unbind checkbox, on by default for a vendor-paired sensor:
  checked, `disconnectAiDexSensor` sends the unpair, waits up to eight seconds for it to settle,
  then removes the sensor, so the key goes with the confirmed unpair; unchecked, the key is kept on
  purpose so the sensor can be re-added. That is a choice the user makes, not a leak -- though an
  unpair that does not confirm inside the eight seconds leaves the key behind.
- The **F-generation provisioned material** lives in `AiDexProvisioningStore`, one encrypted prefs
  entry per serial. **Nothing removes it**: the store has `saveAndInstall`, `importJson` and
  `exportJson` but no per-serial delete, and `AiDexKeyManagementScreen` only clears the account
  session (`clearSession`). `AiDexPairingMaterialFile` is just the JSON codec for that material's
  export and import. So provisioned material outlives sensor removal in every case.

**History is deletable, and it is reachable.** One reading: `HistoryRepository.deleteReading`, with
a tombstone. A whole sensor: `deleteAllHistoryForSensor`, from "Delete glucose history" on removal,
which deletes `history_readings` under every id the rows may have been stored with and also clears
`reading_uncertainty` and the display table. `HistoryRepository.deleteForSensor` (called from
`HistorySync.kt:475` for the serial and each legacy alias) is not a user deletion but the wipe
before a full re-sync. See Reconciliation for which of these leave tombstones. The
`deleteReadingsForSensorAfter` calls in `VirtualGlucoseSensorBridge.pruneFutureHistory` and
`ICanHealthBleManager` are **not** deletion -- they trim rows past a cutoff (future-dated rows, a
history boundary), and reading them as deletion would overstate what the code does.

What is still open, and is the reason this section is not finished: no path found removes the
native mmap bytes for a removed sensor, and no path found coordinates the driver-level removal
with the Room purge or the watch's copy. The six `removeSensor` implementations (AiDex, Anytime,
ICan, MQ, Ottai, Sibionics) are per driver, with no shared contract, so "what removal guarantees"
currently has no single answer -- and the credentials row above shows the two drivers that manage
a secret already answer it differently.

## Credentials and per-driver state, deliberately outside the settings registry

The plan is explicit that the settings registry **excludes** per-sensor driver state and
credentials, and today's code agrees: these live in their own files next to the drivers rather
than in the settings block.

- **AiDex pairing keys** — the PAIR key in `AiDexPairKeyVault` and the F-generation provisioned
  material in `AiDexProvisioningStore` (with `AiDexPairingMaterialFile` as its export/import
  codec), not settings keys. See Deletion for how each is removed.
- **iCan AES keys** — under `drivers/icanhealth/` (`ICanHealthConstants`, `ICanHealthParser`,
  `ICanHealthBleManager`).
- **MQ** state lives in `MQRegistry` in `tk.glucodata_preferences`. Out of scope for this
  document and not covered here.

The reason for the exclusion is the one worth keeping: these are per-sensor secrets, not user
preferences. Their lifetimes differ by driver -- see Deletion. Mirroring one to the watch would mean a second
copy of a secret on a second device, which is the `secret: true` case Q3 has to ask about anyway.

## Consequence for the other queue items

- **Q2** (typed phone↔watch protocol) does not collide with anything here, because the watch is
  never the authority on any row -- but it is not simply a reader either. In direct-sensor mode it
  writes the live reading natively and `WearSync2` ships it in; for journal and calibration it
  holds copies and sends commands. A codec has to carry all three shapes, and the version field
  has to distinguish "I am reporting my own reading" from "I am applying your decision".
- **Q3** (D1 watch features: IOB/COB, standalone Nightscout, journal and meal entry) is the item
  that **needs this file**. "Journal/meal entry on the watch" and "standalone Nightscout" are both
  second-writer questions, and the secrets question is the one to settle first: if the watch holds
  a Nightscout API secret, that secret has an owner and a deletion rule, and neither exists yet.
- **Q5 is this file.** The remaining gaps above are the work, and they should be closed before
  Q3 starts rather than during it.

## Validation

Everything in the ownership and credentials tables was read out of the tree at `05f9a9fb1`, not
recalled:

- `HistoryRepository` and `CalibrationManager` are in `src/mobile`; `VirtualGlucoseSensorBridge`
  and `VirtualSensorNativeMirror` are in `src/main`.
- The phone's `registerBridges()` registers `MobileHistoryRepositoryBridge`,
  `MobileCalibrationProvider`, `MobileCalibrationProfileBridge`; the watch's registers
  `SyncedWearCalibrationProvider` and no history repository.
- `dontuseclose` is `settings.hpp:315`; `javasettings.cpp:2272` writes it and 2276 reads it.
- `DisplayDataState` takes `maxOf(current, latestHistory)` and clamps `ageMillis` at 0.
- `history_readings` has a unique index on `(timestamp, sensorSerial)`, and `HistoryDao` mixes
  `REPLACE` and `IGNORE` inserts; on the sensor paths the minute-bucket delete runs first, so it
  decides a take-over -- see the live-reading row.
- Clone recovery's file list is the `CloneRecovery*` / `CloneOutgoingRecovery*` set in `src/main`
  plus the mobile coordinators and bridges.
- iCan AES keys are per-sensor prefs keys that `ICanHealthRegistry.removeSensor` does remove,
  plus hardcoded vendor defaults in `ICanHealthConstants` that no deletion reaches.
- `HistoryDao.deleteForSensor` is reachable from `HistorySync.kt:475`; the
  `deleteReadingsForSensorAfter` calls in `VirtualGlucoseSensorBridge` and `ICanHealthBleManager`
  are cutoff trims, not deletion.

Facets marked **not established** were not verified and are not guesses -- they are the open
questions above.

Verification for the per-facet passes:

- `HistoryReading` declares `@PrimaryKey(autoGenerate = true) id` plus the unique index on
  `(timestamp, sensorSerial)`, and its own comment gives the reason: the same timestamp from
  different sensors must coexist.
- `HistoryBucketReplacement.collapseReadings` keys on `timestamp / bucketDurationMs`, keeps one row
  per bucket, drops `timestamp <= 0`, and returns them sorted by timestamp.
- `choosePreferred` scores +10 for a finite positive `value`, +5 for a finite positive `rawValue`,
  and breaks a tie on the later timestamp.
- `SENSOR_MINUTE_BUCKET_MS = 60_000` is declared in `HistoryDisplayMerge.kt`, i.e. the display
  layer's own copy of the same granularity; `HistoryRepository` declares its own private copy.

Verification for the correction pass (against `da788a73f`):

- `HistoryRepository.storeReading` reads the existing row with `it.timestamp == timestamp`, calls
  `deleteSensorRowsInBucketRanges` for the reading's minute, then `dao.insert`.
  `storeReadingsReplacingSensorBuckets` collapses, keys its provenance lookup on the exact
  timestamp, deletes the planned bucket ranges, then `dao.insertAll`.
- `storeReadings` has one caller, `HistoryExporter`'s import, with `source = IMPORT`.
- `reportIfFutureTimestamp` only logs; the ten-minute bound exists only in
  `VirtualGlucoseSensorBridge` and `ApiGlucoseSourceManager`.
- `HistoryRepository.deleteReading` inserts `DeletedHistoryReading` rows before
  `deleteReadingsAtTimestamp`, and is called from `DashboardViewModel`;
  `deleteAllHistoryForSensor` is called from `SensorViewModel` and writes no tombstone.
- The AiDex PAIR key is cleared by `AiDexPairKeyVault.clearAfterConfirmedUnpair` (from
  `AiDexBleManager`) and `clearStoredKey` (from `forgetSavedPairKey` / `forgetAiDexPairKey`);
  `SensorCard`'s removal dialog passes its unbind checkbox to `disconnectAiDexSensor`.
  `AiDexProvisioningStore` has no per-serial delete.

Documentation only: no app code was changed, and no build or device run was performed for this
file.
