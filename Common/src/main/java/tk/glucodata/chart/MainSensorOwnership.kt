package tk.glucodata.chart

import tk.glucodata.SensorIdentity

/**
 * Which sensor is the main sensor at a given minute.
 *
 * This is the guarantee, stated once: the identity of the main line is a fact
 * about the minute, not about the current selection.
 *
 * - A **sealed** minute that has a recorded presentation is owned by the sensor
 *   the record names — whichever sensor was main when the minute was first put
 *   in front of the user. Swapping main sensors today does not move it.
 * - Every other minute — inside the grace window, or sealed with no record —
 *   gets **no opinion**. The live merge already embodies the normal
 *   current-primary logic for those, and whatever it chose as the main line is
 *   the main line. Answering "current primary" here instead was a mistake: a
 *   retired sensor's history, with no record and no competitor, was demoted
 *   everywhere because its serial was not today's primary. Nothing is invented,
 *   nothing is persisted; a minute resolves the first time it is presented.
 *
 * Rendering, tooltips and anything else that wants to know "who is main here"
 * ask this and draw what it says — and where it says nothing, they draw what
 * they already were. Thickness and colour are how the chart happens to
 * visualise the answer; they are not the answer.
 *
 * Pure on purpose: the record and the clock are inputs, so the rule has exactly
 * one definition and a test for each branch.
 */
class MainSensorOwnership(
    /** Recorded owner per minute, keyed by [minuteOf]. */
    private val recorded: Map<Long, String>,
    private val nowMs: Long,
) {
    private val sealHorizonMs: Long = nowMs - SEAL_GRACE_MS

    /**
     * The recorded main sensor at [timestampMs], or null where the record has
     * no opinion — inside the grace window, or with nothing recorded.
     */
    fun mainSensorAt(timestampMs: Long): String? {
        val minute = minuteOf(timestampMs)
        if (minute > sealHorizonMs) return null
        return recorded[minute]
    }

    /**
     * Whether [sensorSerial] is the recorded main sensor at [timestampMs].
     * Null where the record has no opinion; callers leave things as they are.
     */
    fun isMainAt(sensorSerial: String?, timestampMs: Long): Boolean? {
        val main = mainSensorAt(timestampMs) ?: return null
        val serial = sensorSerial?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return SensorIdentity.matches(serial, main)
    }

    /** Whether the record has anything to say inside this window at all. */
    val hasRecordedOwnership: Boolean get() = recorded.isNotEmpty()

    companion object {
        const val MINUTE_MS = 60_000L

        /**
         * How long a presented minute stays revisable before it is history.
         *
         * An hour, so that a calibration entered well after the fingerstick it
         * refers to still moves the line it was meant to correct. The single
         * definition; the record's own constant points here.
         */
        const val SEAL_GRACE_MS = 60L * 60L * 1000L

        /** The minute a timestamp belongs to. */
        fun minuteOf(timestampMs: Long): Long = (timestampMs / MINUTE_MS) * MINUTE_MS

        /** Nothing recorded: no opinion anywhere. */
        val NONE = MainSensorOwnership(emptyMap(), 0L)
    }
}
