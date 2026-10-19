package tk.glucodata.data.journal

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A recovered journal deletion keyed by its cross-device identity. */
@Entity(
    tableName = "clone_journal_recovery_tombstones",
    indices = [Index(value = ["recoveryId"], unique = true)],
)
data class CloneJournalRecoveryTombstoneEntity(
    @PrimaryKey
    val stableBaseId: String,
    val recoveryId: String? = null,
    val deletedAt: Long,
)
