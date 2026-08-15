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
    val journalEntryId: Long? = null,
    /**
     * The sensor's own uncorrected value behind [sensorValue], for a driver that
     * folds the calibration into what it stores.
     *
     * [sensorValue] is what was on screen, which for such a driver is already
     * corrected — fitting the model against it is self-referential, so the model
     * needs the stock value underneath. It was recovered by matching the anchor
     * against the driver's replayed source history, which works only on the
     * device that produced the reading: a calibration taken while the watch held
     * the sensor could be rebased by neither, and landed at an x the stock
     * series never visits. Recording it here makes it survive the handover, and
     * a restart.
     *
     * 0 means unknown — anchors from before this column, and lanes nothing
     * integrates — and falls back to the history match as before.
     */
    val sensorValueStock: Float = 0f
)
