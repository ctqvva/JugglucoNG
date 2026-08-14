package tk.glucodata.data.calibration

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calibrations")
data class CalibrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val sensorId: String,
    val sensorValue: Float,      // Auto value at calibration time
    val sensorValueRaw: Float,   // Raw value at calibration time
    val userValue: Float,
    val isEnabled: Boolean = true,
    val isRawMode: Boolean = false, // Which mode was used to create the calibration
    /**
     * The journal blood-glucose entry this calibration was derived from, when it
     * was not entered by hand. Journal-derived rows are kept in step with the
     * journal — re-paired when the entry moves, removed when it disappears — so
     * they must stay identifiable. A hand-entered calibration has no entry.
     */
    val journalEntryId: Long? = null
)
