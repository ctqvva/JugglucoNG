package tk.glucodata.data.journal

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "journal_entries",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["entryType"]),
        Index(value = ["insulinPresetId"]),
        Index(value = ["foodId"]),
        Index(value = ["sourceRecordId"], unique = true)
    ]
)
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val sensorSerial: String?,
    val entryType: String,
    val title: String,
    val note: String?,
    val amount: Float?,
    val glucoseValueMgDl: Float?,
    val durationMinutes: Int?,
    val intensity: String?,
    val insulinPresetId: Long?,
    val foodId: Long? = null,
    val proteinGrams: Float? = null,
    val fatGrams: Float? = null,
    val source: String,
    val sourceRecordId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val nsUploadedAt: Long? = null,
    val nsRemoteId: String? = null,
    /**
     * When this row last went out to LibreView, so an unchanged entry is not resent on
     * every upload pass. Tracked separately from [nsUploadedAt] because the two
     * destinations succeed and fail independently.
     */
    val lvUploadedAt: Long? = null
)
