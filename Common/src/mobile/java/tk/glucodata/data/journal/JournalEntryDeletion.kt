package tk.glucodata.data.journal

import androidx.room.withTransaction
import tk.glucodata.data.HistoryDatabase

/**
 * What deleting a journal entry has to leave behind besides the missing row, so the same
 * rules hold whether one entry goes or all of them do.
 *
 *  - An entry a Clone peer would otherwise keep gets a tombstone, so the peer drops it too.
 *  - An entry already sent to Nightscout is queued for deletion there; the uploader carries
 *    that out. Entries that came from another system are never queued, since nothing of ours
 *    is stored remotely.
 *
 * Call inside the transaction that deletes the row.
 *
 * @return whether a Clone tombstone was written
 */
internal suspend fun JournalDao.recordEntryDeletion(existing: JournalEntryEntity, now: Long): Boolean {
    var cloneTombstoned = false
    if (isCloneJournalExportSource(existing.source)) {
        upsertCloneJournalTombstone(
            CloneJournalTombstoneEntity(
                entryId = existing.id,
                deletedAt = now,
                recoveryId = existing.recoveryId,
            )
        )
        cloneTombstoned = true
    }
    existing.nightscoutDeleteRemoteId()?.let { remoteId ->
        enqueuePendingNightscoutDelete(
            JournalPendingDeleteEntity(
                entryId = existing.id,
                nsRemoteId = remoteId,
                deletedAt = now,
            )
        )
    }
    return cloneTombstoned
}

/** What a bulk delete removed, for the callers that have to tell other parts of the app. */
internal data class DeletedJournalEntries(
    val count: Int,
    val affectsIob: Boolean,
    val hadGlucose: Boolean,
    val cloneTombstoned: Boolean,
)

/**
 * Deletes every journal entry in one transaction, with the per-entry bookkeeping of
 * [recordEntryDeletion]. The insulin presets and the food library are setup, not entries,
 * and stay.
 */
internal suspend fun HistoryDatabase.deleteAllJournalEntries(
    now: Long = System.currentTimeMillis(),
): DeletedJournalEntries = withTransaction {
    val dao = journalDao()
    val rows = dao.getEntries()
    var cloneTombstoned = false
    rows.forEach { row -> if (dao.recordEntryDeletion(row, now)) cloneTombstoned = true }
    if (rows.isNotEmpty()) dao.deleteAllEntries()
    DeletedJournalEntries(
        count = rows.size,
        affectsIob = rows.any { affectsIob(it.entryType) },
        hadGlucose = rows.any { it.glucoseValueMgDl != null },
        cloneTombstoned = cloneTombstoned,
    )
}

internal fun affectsIob(entryType: String?): Boolean =
    entryType == JournalEntryType.INSULIN.storageValue ||
        entryType == JournalEntryType.CARBS.storageValue
