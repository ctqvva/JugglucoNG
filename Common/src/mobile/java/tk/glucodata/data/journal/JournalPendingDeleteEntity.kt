package tk.glucodata.data.journal

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A journal entry that was deleted locally and still has to be deleted on Nightscout.
 *
 * [attempts] counts the deletes the server refused. A document the server will never
 * let us delete (a token without `api:treatments:delete`, say) would otherwise be
 * retried on every upload cycle forever, so the tombstone is dropped past
 * [JournalTreatmentUploader.MAX_DELETE_ATTEMPTS].
 */
@Entity(tableName = "journal_pending_deletes")
data class JournalPendingDeleteEntity(
    @PrimaryKey
    val entryId: Long,
    val nsRemoteId: String,
    val deletedAt: Long,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0L
)
