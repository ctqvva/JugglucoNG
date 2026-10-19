package tk.glucodata.data.journal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "journal_insulin_presets",
    indices = [Index(value = ["sortOrder"])]
)
data class JournalInsulinPresetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val displayName: String,
    val onsetMinutes: Int,
    val durationMinutes: Int,
    val accentColor: Int,
    val curveJson: String,
    val isBuiltIn: Boolean,
    val isArchived: Boolean,
    val countsTowardIob: Boolean,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "1")
    val useForCalculation: Boolean = true,
    val curveProfileId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val curveModelVersion: Int = 0,
    @ColumnInfo(defaultValue = "'unverified'")
    val curveEvidence: String = JournalCurveEvidence.UNVERIFIED.storageValue
)
