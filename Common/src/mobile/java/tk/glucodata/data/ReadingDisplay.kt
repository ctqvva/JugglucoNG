package tk.glucodata.data

import androidx.room.Entity
import androidx.room.Index

/**
 * The number the dashboard showed as the main value for one minute of the
 * timeline, kept where nothing can change it.
 *
 * **Keyed by the minute, not by the sensor.** That is the whole correction over
 * the first version of this table. The dashboard draws one main value per
 * minute, chosen by [HistoryDisplayMerge] from facts that are alive at the
 * moment of the query — which sensor is currently preferred, which one read most
 * recently, how the coverage segments fall. A record keyed by
 * (sensorSerial, timestamp) stores what *each sensor* would have shown and never
 * stores *which one won*, so the main value for a past minute still moved
 * whenever the ranking changed: a sensor going quiet, a new sensor arriving, the
 * user picking a different main. In a two-sensor store that is not an edge case
 * — a 2026-09-10 export had two sensors reporting in the same minute for 29% of
 * the timeline.
 *
 * So the merge decision is part of the record. [sensorSerial] is the sensor that
 * won, kept as provenance so the chart can still break its line on a sensor
 * change, but it is not part of the key and it is not consulted to re-derive
 * anything.
 *
 * `history_readings.value` and `.rawValue` remain what the *sensor* produced.
 * They used to double as what the *user saw*, and the calibration rewrite path
 * took that literally — it read `value`, calibrated it, and wrote the result
 * back — so calibration compounded and the sensor's own number was lost. Those
 * columns are immutable facts about the sensor; the number on screen lives here.
 *
 * A separate table for the same reason [ReadingUncertainty] is one: native
 * re-sync and history rebuilds delete and re-insert `history_readings` rows
 * wholesale, so a column on that table is not durable and a row keyed by the
 * minute is.
 *
 * Values are mg/dL, matching how readings are stored.
 */
@Entity(
    tableName = "reading_display",
    primaryKeys = ["timestamp"],
    indices = [Index(value = ["sensorSerial"])],
)
data class ReadingDisplay(
    /** Start of the minute this record describes, in epoch millis. */
    val timestamp: Long,
    /** The sensor whose reading won this minute. Provenance, never a key. */
    val sensorSerial: String,
    /** The number shown, in mg/dL. */
    val displayMgdl: Float,
    /** The lane it came from: 1/3 are raw-primary, 0/2 auto-primary. */
    val viewMode: Int,
    /**
     * Identifies the calibration state that produced [displayMgdl].
     *
     * Provenance only. Nothing re-derives from it — it exists so a stored value
     * can be told apart from one today's settings would produce, which is what
     * makes "did this move?" an answerable question rather than a guess.
     */
    val calibrationFingerprint: Long,
    val recordedAt: Long,
) {
    val isUsable: Boolean
        get() = displayMgdl.isFinite() && displayMgdl > 0f

    /**
     * Whether this record is old enough to be authoritative.
     *
     * Measured from the **reading's own time**, not from when the row was
     * written. The first version measured from `recordedAt`, which made the
     * boundary depend on when a background pass happened to run: two readings a
     * minute apart could land on opposite sides of it, and re-recording a row
     * silently un-sealed a value that had been on screen for hours. Keyed to the
     * reading, "older than the grace window" is a property of the timeline that
     * every caller computes the same way and nothing can move.
     *
     * Inside the window a minute is still settling — native backfill can arrive
     * late, a second sensor can still claim the minute, a fingerstick entered
     * after the fact should still reshape the line around it. Rows are therefore
     * only ever written for minutes already past it.
     */
    fun isSealedAt(nowMs: Long): Boolean = (nowMs - timestamp) >= DISPLAY_SEAL_GRACE_MS

    companion object {
        /**
         * How long a recorded main value stays re-derivable.
         *
         * An hour, so that a calibration entered well after the fingerstick it
         * refers to still moves the line it was meant to correct.
         */
        const val DISPLAY_SEAL_GRACE_MS = 60L * 60L * 1000L

        const val MINUTE_MS = 60_000L

        /** The minute a timestamp belongs to — the table's key. */
        fun minuteOf(timestampMs: Long): Long = (timestampMs / MINUTE_MS) * MINUTE_MS
    }
}
