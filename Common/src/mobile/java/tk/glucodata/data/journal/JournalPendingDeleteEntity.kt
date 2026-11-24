package tk.glucodata.data.journal

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Nightscout document that still has to be deleted there: its entry was deleted locally, or
 * a newer document has taken its place.
 *
 * Keyed by the document, since one entry can leave several behind: a copy a token may not
 * delete, the copy a time correction superseded, the one an upload created as it was deleted.
 *
 * [attempts] counts the deletes the server refused. A document the server will never
 * let us delete (a token without `api:treatments:delete`, say) would otherwise be
 * retried on every upload cycle forever, so the tombstone is dropped past
 * [JournalTreatmentUploader.MAX_DELETE_ATTEMPTS].
 */
@Entity(tableName = "journal_pending_deletes")
data class JournalPendingDeleteEntity(
    val entryId: Long,
    @PrimaryKey
    val nsRemoteId: String,
    val deletedAt: Long,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0L
)
