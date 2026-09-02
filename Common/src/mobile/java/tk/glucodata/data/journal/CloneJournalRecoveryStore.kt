package tk.glucodata.data.journal

import tk.glucodata.CloneRecoveryMode
import tk.glucodata.CloneRecoveryPackageIO
import tk.glucodata.CloneRecoveryRecord
import tk.glucodata.CloneJournalRecoveryPolicy
import tk.glucodata.GlucoseReadingSource
import tk.glucodata.NightscoutUploadWake
import tk.glucodata.OutboundApiJournalSnapshot
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.calibration.JournalCalibrationSync

/** Streams the optional journal category without importing foreign database ids. */
internal class CloneJournalRecoveryStore(
    private val database: HistoryDatabase,
) {
    private val dao = database.journalDao()

    suspend fun exportRecords(sink: CloneRecoveryPackageIO.RecordSink) {
        val origin = CloneJournalIdentity.originId()
        exportTombstones(sink, origin)
        val presets = dao.getInsulinPresets().associateBy { it.id }
        val foods = dao.getFoods().associateBy { it.id }
        var afterId = 0L
        while (true) {
            val page = dao.getRecoveryEntriesPage(afterId, PAGE_SIZE)
            if (page.isEmpty()) return
            page.forEach { entry ->
                val recoveryId = entry.recoveryId
                    ?: throw IllegalStateException("Journal entry has no recovery identity")
                sink.write(
                    CloneJournalRecoveryRecords.ENTRY,
                    CloneJournalRecoveryRecords.encode(
                        CloneJournalEntryRecord(
                            recoveryId = recoveryId,
                            legacyStableId = CloneJournalIdentity.legacyStableEntryId(entry, origin),
                            entry = entry,
                            insulinPreset = entry.insulinPresetId?.let(presets::get),
                            food = entry.foodId?.let(foods::get),
                        )
                    ),
                )
            }
            afterId = page.last().id
        }
    }

    fun newImportSession(
        mode: CloneRecoveryMode,
        recoverySource: String,
    ): ImportSession {
        require(GlucoseReadingSource.cloneTransport(recoverySource) != null) {
            "Clone journal recovery requires a Clone delivery source"
        }
        return ImportSession(mode, JournalEntrySource.fromStorage(recoverySource))
    }

    private suspend fun exportTombstones(
        sink: CloneRecoveryPackageIO.RecordSink,
        origin: String,
    ) {
        exportLocalTombstones(sink, origin)
        exportRecoveredTombstones(sink)
    }

    private suspend fun exportLocalTombstones(
        sink: CloneRecoveryPackageIO.RecordSink,
        origin: String,
    ) {
        var afterDeletedAt = 0L
        var afterEntryId = 0L
        while (true) {
            val page = dao.getRecoveryCloneJournalTombstonesPage(
                afterDeletedAt = afterDeletedAt,
                afterEntryId = afterEntryId,
                limit = PAGE_SIZE,
            )
            if (page.isEmpty()) return
            page.forEach { tombstone ->
                sink.write(
                    CloneJournalRecoveryRecords.TOMBSTONE,
                    CloneJournalRecoveryRecords.encode(
                        CloneJournalTombstoneRecord(
                            recoveryId = tombstone.recoveryId,
                            legacyStableBaseId = CloneJournalIdentity.localTombstoneBaseId(
                                origin,
                                tombstone.entryId,
                            ),
                            deletedAt = tombstone.deletedAt,
                        )
                    ),
                )
            }
            val last = page.last()
            afterDeletedAt = last.deletedAt
            afterEntryId = last.entryId
        }
    }

    private suspend fun exportRecoveredTombstones(
        sink: CloneRecoveryPackageIO.RecordSink,
    ) {
        var afterDeletedAt = 0L
        var afterStableBaseId = ""
        while (true) {
            val page = dao.getRecoveryJournalTombstonesPage(
                afterDeletedAt = afterDeletedAt,
                afterStableBaseId = afterStableBaseId,
                limit = PAGE_SIZE,
            )
            if (page.isEmpty()) return
            page.forEach { tombstone ->
                sink.write(
                    CloneJournalRecoveryRecords.TOMBSTONE,
                    CloneJournalRecoveryRecords.encode(
                        CloneJournalTombstoneRecord(
                            recoveryId = tombstone.recoveryId,
                            legacyStableBaseId = tombstone.stableBaseId,
                            deletedAt = tombstone.deletedAt,
                        )
                    ),
                )
            }
            val last = page.last()
            afterDeletedAt = last.deletedAt
            afterStableBaseId = last.stableBaseId
        }
    }

    inner class ImportSession internal constructor(
        private val mode: CloneRecoveryMode,
        private val recoverySource: JournalEntrySource,
    ) {
        private val sqlite get() = database.openHelper.writableDatabase
        private val localOrigin by lazy { CloneJournalIdentity.originId() }
        private var restoredLocalContent = false
        private var changedGlucoseJournal = false
        private var imported = 0

        suspend fun beforeImport() {
            sqlite.execSQL("DROP TABLE IF EXISTS temp.clone_recovery_journal_deletions")
            sqlite.execSQL(
                """
                CREATE TEMP TABLE clone_recovery_journal_deletions (
                    recoveryId TEXT,
                    legacyStableBaseId TEXT NOT NULL PRIMARY KEY,
                    deletedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            sqlite.execSQL(
                "CREATE INDEX clone_recovery_journal_deletions_recoveryId " +
                    "ON clone_recovery_journal_deletions (recoveryId)"
            )
            if (mode == CloneRecoveryMode.FULL_HISTORY) {
                dao.deleteAllEntries()
                dao.deleteAllCloneJournalTombstones()
                dao.deleteAllRecoveryJournalTombstones()
            }
        }

        suspend fun accept(record: CloneRecoveryRecord) {
            when (record.type) {
                CloneJournalRecoveryRecords.TOMBSTONE -> stageTombstone(
                    CloneJournalRecoveryRecords.decodeTombstone(record.payload)
                )
                CloneJournalRecoveryRecords.ENTRY -> importEntry(
                    CloneJournalRecoveryRecords.decodeEntry(record.payload)
                )
                else -> throw IllegalArgumentException(
                    "Unsupported Clone journal recovery record type"
                )
            }
        }

        suspend fun finishImport() {
            sqlite.execSQL("DROP TABLE IF EXISTS temp.clone_recovery_journal_deletions")
        }

        fun notifyCommittedImport() {
            if (imported == 0 && mode != CloneRecoveryMode.FULL_HISTORY) return
            if (restoredLocalContent) {
                OutboundApiJournalSnapshot.journalChanged()
                NightscoutUploadWake.afterJournalChange()
            } else {
                OutboundApiJournalSnapshot.mirroredJournalChanged()
            }
            if (changedGlucoseJournal) JournalCalibrationSync.onJournalChanged()
            UiRefreshBus.requestDataRefresh()
        }

        private suspend fun stageTombstone(record: CloneJournalTombstoneRecord) {
            sqlite.execSQL(
                "INSERT OR REPLACE INTO temp.clone_recovery_journal_deletions " +
                    "(recoveryId, legacyStableBaseId, deletedAt) VALUES (?, ?, ?)",
                arrayOf<Any?>(
                    record.recoveryId,
                    record.legacyStableBaseId,
                    record.deletedAt,
                ),
            )
            val byStableBase = dao.getRecoveryJournalTombstoneByStableBaseId(
                record.legacyStableBaseId,
            )
            val byRecoveryId = record.recoveryId?.let {
                dao.getRecoveryJournalTombstoneByRecoveryId(it)
            }
            val newestExistingDeletion = sequenceOf(byStableBase, byRecoveryId)
                .filterNotNull()
                .maxOfOrNull(CloneJournalRecoveryTombstoneEntity::deletedAt)
            if (newestExistingDeletion == null || record.deletedAt > newestExistingDeletion) {
                dao.upsertRecoveryJournalTombstone(
                    CloneJournalRecoveryTombstoneEntity(
                        stableBaseId = record.legacyStableBaseId,
                        recoveryId = record.recoveryId,
                        deletedAt = record.deletedAt,
                    )
                )
            }
        }

        private suspend fun importEntry(record: CloneJournalEntryRecord) {
            if (isDeletedInPackage(record)) return
            if (durableDeletionBlocks(record)) return
            val incoming = record.entry
            val type = JournalEntryType.fromStorage(incoming.entryType)
            val existing = findExisting(record, type)
            if (mode == CloneRecoveryMode.ONLY_MISSING &&
                existing != null && incoming.updatedAt <= existing.updatedAt
            ) {
                return
            }
            val presetId = record.insulinPreset?.let { resolvePreset(it) }
            val foodId = record.food?.let { resolveFood(it) }
            val returningToOrigin = CloneJournalIdentity.localRowId(
                stableEntryId = record.legacyStableId,
                localOrigin = localOrigin,
                type = type,
            ) != null
            val originalSource = JournalEntrySource.fromStorage(
                incoming.originSource ?: incoming.source
            )
            val restoreOriginalSource = returningToOrigin &&
                isCloneJournalExportSource(originalSource.storageValue)
            val storedSource = existing?.source ?: if (restoreOriginalSource) {
                originalSource.storageValue
            } else {
                recoverySource.storageValue
            }
            val storedSourceRecordId = existing?.sourceRecordId ?: if (restoreOriginalSource) {
                incoming.sourceRecordId
            } else {
                record.legacyStableId
            }
            val entity = incoming.copy(
                id = existing?.id ?: 0L,
                insulinPresetId = presetId,
                foodId = foodId,
                source = storedSource,
                originSource = incoming.originSource ?: incoming.source,
                sourceRecordId = storedSourceRecordId,
                recoveryId = record.recoveryId,
                createdAt = existing?.createdAt ?: incoming.createdAt,
                updatedAt = incoming.updatedAt,
                nsUploadedAt = existing?.nsUploadedAt,
                nsRemoteId = incoming.nsRemoteId ?: existing?.nsRemoteId,
            )
            dao.upsertEntry(entity)
            imported++
            restoredLocalContent = restoredLocalContent || restoreOriginalSource
            changedGlucoseJournal = changedGlucoseJournal || entity.glucoseValueMgDl != null ||
                existing?.glucoseValueMgDl != null
        }

        private suspend fun findExisting(
            record: CloneJournalEntryRecord,
            type: JournalEntryType,
        ): JournalEntryEntity? {
            dao.getEntryByRecoveryId(record.recoveryId)?.let { return it }
            dao.getEntryBySourceRecordId(record.legacyStableId)?.let { return it }
            record.entry.sourceRecordId?.let { dao.getEntryBySourceRecordId(it) }?.let { return it }
            record.entry.nsRemoteId?.let { remoteId ->
                dao.getEntriesByNightscoutRemoteIdAndType(remoteId, type.storageValue)
                    .firstOrNull()
                    ?.let { return it }
            }
            val localId = CloneJournalIdentity.localRowId(
                stableEntryId = record.legacyStableId,
                localOrigin = localOrigin,
                type = type,
            ) ?: return null
            return dao.getEntryById(localId)?.takeIf { candidate ->
                candidate.entryType == record.entry.entryType &&
                    candidate.createdAt == record.entry.createdAt
            }
        }

        private fun isDeletedInPackage(record: CloneJournalEntryRecord): Boolean {
            val baseId = CloneJournalIdentity.tombstoneBaseForEntryId(
                record.legacyStableId,
                JournalEntryType.fromStorage(record.entry.entryType),
            ) ?: return false
            val deletedAt = sqlite.query(
                "SELECT deletedAt FROM temp.clone_recovery_journal_deletions " +
                    "WHERE recoveryId = ? OR legacyStableBaseId = ? LIMIT 1",
                arrayOf(record.recoveryId, baseId),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            } ?: return false
            return CloneJournalRecoveryPolicy.deletionBlocksIncomingEntry(
                deletedAt = deletedAt,
                entryUpdatedAt = record.entry.updatedAt,
            )
        }

        /**
         * Only Missing never deletes an existing local row. This durable marker instead
         * prevents an older package from importing that row again after it is absent.
         */
        private suspend fun durableDeletionBlocks(record: CloneJournalEntryRecord): Boolean {
            val type = JournalEntryType.fromStorage(record.entry.entryType)
            val stableBaseId = CloneJournalIdentity.tombstoneBaseForEntryId(
                record.legacyStableId,
                type,
            ) ?: return false
            val byStableBase = dao.getRecoveryJournalTombstoneByStableBaseId(stableBaseId)
            val byRecoveryId = dao.getRecoveryJournalTombstoneByRecoveryId(record.recoveryId)
            val newestDeletion = sequenceOf(byStableBase, byRecoveryId)
                .filterNotNull()
                .maxByOrNull(CloneJournalRecoveryTombstoneEntity::deletedAt)
                ?: return false
            if (CloneJournalRecoveryPolicy.deletionBlocksIncomingEntry(
                    deletedAt = newestDeletion.deletedAt,
                    entryUpdatedAt = record.entry.updatedAt,
                )
            ) {
                return true
            }
            dao.deleteRecoveryJournalTombstone(stableBaseId, record.recoveryId)
            return false
        }

        private suspend fun resolvePreset(incoming: JournalInsulinPresetEntity): Long {
            val normalized = incoming.copy(id = 0L)
            dao.getInsulinPresets().firstOrNull { it.copy(id = 0L) == normalized }
                ?.let { return it.id }
            return dao.upsertInsulinPreset(normalized)
        }

        private suspend fun resolveFood(incoming: JournalFoodEntity): Long {
            val normalized = incoming.copy(id = 0L)
            dao.getFoods().firstOrNull { it.copy(id = 0L) == normalized }
                ?.let { return it.id }
            return dao.upsertFood(normalized)
        }
    }

    private companion object {
        const val PAGE_SIZE = 256
    }
}
