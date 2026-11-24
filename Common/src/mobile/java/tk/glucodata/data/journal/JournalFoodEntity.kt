package tk.glucodata.data.journal

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "journal_foods",
    indices = [
        Index(value = ["isArchived", "sortOrder"]),
        Index(value = ["displayName"])
    ]
)
data class JournalFoodEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val displayName: String,
    val carbsGrams: Float,
    val proteinGrams: Float?,
    val fatGrams: Float?,
    val absorptionMinutes: Int,
    val accentColor: Int,
    val isBuiltIn: Boolean,
    val isArchived: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/** Updated atomically with the journal rows so the food database can recover after a crash. */
@Entity(tableName = "journal_food_restore_commit")
data class JournalFoodRestoreCommit(@PrimaryKey val id: Int = 1, val token: String)
