package tk.glucodata.data

import androidx.room.Entity
import androidx.room.Index

/**
 * The glucose value a reading was actually shown as, kept where nothing can
 * overwrite it.
 *
 * `history_readings.value` and `.rawValue` are what the *sensor* produced. They
 * used to double as what the *user saw*, and the calibration rewrite path took
 * that literally: it read `value`, calibrated it, and wrote the result back into
 * `value` — then did the same again on the next toggle, over its own output. So
 * calibration compounded, raw mode wrote calibrated numbers into the raw column,
 * and turning calibration off could not undo any of it because the sensor's own
 * number was gone. Those two columns are now immutable facts about the sensor,
 * and the number on screen lives here.
 *
 * A separate table for the same reason [ReadingUncertainty] is one: native
 * re-sync and history rebuilds delete and re-insert `history_readings` rows
 * wholesale (see `storeReadingsReplacingSensorBuckets`), so a column on that
 * table is not durable, and a row keyed by (sensorSerial, timestamp) is. A
 * reading with no row here simply has no recorded display value — which is the
 * correct answer for every reading written before this existed.
 *
 * Values are mg/dL, matching how readings are stored.
 */
@Entity(
    tableName = "reading_display",
    primaryKeys = ["sensorSerial", "timestamp"],
    indices = [Index(value = ["timestamp"])],
)
data class ReadingDisplay(
    val sensorSerial: String,
    val timestamp: Long,
    /** The number shown, in mg/dL. */
    val displayMgdl: Float,
    /** The lane it came from: 1/3 are raw-primary, 0/2 auto-primary. */
    val viewMode: Int,
    /**
     * Identifies the calibration state that produced [displayMgdl].
     *
     * Not used to re-derive anything — it is provenance, so a stored value can
     * be told apart from one the current settings would produce, which is what
     * makes "recompute" an answerable question rather than a guess.
     */
    val calibrationFingerprint: Long,
    val recordedAt: Long,
) {
    val isUsable: Boolean
        get() = displayMgdl.isFinite() && displayMgdl > 0f

    /**
     * Whether this record is old enough to be authoritative.
     *
     * Inside the grace window a reading is still settling — native backfill can
     * arrive late, a fingerstick entered after the fact should still reshape the
     * line around it, smoothing has not converged. Past it, the number stood on
     * screen long enough to be history, and changing a setting should not
     * silently rewrite it.
     */
    fun isSealedAt(nowMs: Long): Boolean = (nowMs - recordedAt) >= DISPLAY_SEAL_GRACE_MS

    companion object {
        /**
         * How long a recorded display value stays re-derivable.
         *
         * An hour, so that a calibration entered well after the fingerstick it
         * refers to still moves the line it was meant to correct.
         */
        const val DISPLAY_SEAL_GRACE_MS = 60L * 60L * 1000L
    }
}
