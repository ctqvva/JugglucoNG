package tk.glucodata.data.journal

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A locally authored journal row whose deletion still has to reach Clone receivers. */
@Entity(tableName = "clone_journal_tombstones")
data class CloneJournalTombstoneEntity(
    @PrimaryKey
    val entryId: Long,
    val deletedAt: Long,
    val recoveryId: String? = null,
)
