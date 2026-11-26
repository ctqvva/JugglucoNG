package tk.glucodata.data.journal

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Nightscout document this device may have created and has not been told about yet.
 *
 * Written before the request goes out and removed once the answer is known, so it outlives
 * the process: a request that was cut off, timed out, or answered after its entry was deleted
 * still leaves behind the name of the document to look for. [NightscoutUploadLedger] owns the
 * rows.
 */
@Entity(tableName = "journal_nightscout_uploads")
data class JournalNightscoutUploadEntity(
    /** [JournalTreatmentUploader.datedIdentifier], on v1 as well, where the server assigns the `_id`. */
    @PrimaryKey
    val remoteId: String,
    val entryId: Long,
    /** With [entryId], which entry this was: an id can be taken again by a restored or re-saved row. */
    val entryCreatedAt: Long,
    val startedAt: Long
)
