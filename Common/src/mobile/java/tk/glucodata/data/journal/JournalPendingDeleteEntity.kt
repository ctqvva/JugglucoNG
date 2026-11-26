package tk.glucodata.data.journal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Nightscout document that still has to be deleted there: its entry was deleted locally, or
 * a newer document has taken its place.
 *
 * Keyed by the document, since one entry can leave several behind: a copy a token may not
 * delete, the copy a time correction superseded, the one an upload created as it was deleted.
 *
 * Room is the single source of truth for both the pending deletion and its retry metadata:
 * unresolved tombstones are never abandoned after a fixed attempt count; they back off with
 * capped exponential delays until deletion (200/204) or remote absence (404/410) is confirmed.
 *
 * [lastAttemptAt] is set while a delete is out whose outcome is not known, and 0 again once
 * the server has said no: until then the document may be gone already.
 */
@Entity(tableName = "journal_pending_deletes")
data class JournalPendingDeleteEntity(
    val entryId: Long,
    @PrimaryKey
    val nsRemoteId: String,
    val deletedAt: Long,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0L,
    /** Normalized Nightscout server origin + base path this document belongs to. */
    @ColumnInfo(defaultValue = "")
    val targetServer: String = "",
    /** Wall-clock millis before which this document should not be retried, 0 if due now. */
    @ColumnInfo(defaultValue = "0")
    val nextRetryAt: Long = 0L,
    /** [DeleteFailureKind.storageValue] of the last failure, 0 if none. */
    @ColumnInfo(defaultValue = "0")
    val failureKind: Int = 0,
    /** Last HTTP status or negative [tk.glucodata.NightPost] error code, 0 if none. */
    @ColumnInfo(defaultValue = "0")
    val lastErrorCode: Int = 0,
    /** Short server refusal message (e.g. missing permission), empty if none. */
    @ColumnInfo(defaultValue = "")
    val lastErrorMessage: String = "",
)
