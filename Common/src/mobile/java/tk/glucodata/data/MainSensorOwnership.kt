package tk.glucodata.data

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
 * - A minute **inside the grace window** follows the current primary, whether or
 *   not a record exists for it yet: it is still settling, and a record written
 *   for it is still revisable.
 * - A sealed minute with **no record** falls back to the current primary. This
 *   is a fallback, not a decision: nobody has been shown the minute, nothing is
 *   invented for it, and nothing here persists the answer. It will resolve the
 *   first time it is presented.
 *
 * Rendering, tooltips and anything else that wants to know "who is main here"
 * ask this and draw what it says. Thickness and colour are how the chart
 * happens to visualise the answer; they are not the answer.
 *
 * Pure on purpose: the record, the primary and the clock are inputs, so the
 * rule has exactly one definition and a test for each branch.
 */
class MainSensorOwnership(
    /** Recorded owner per minute, keyed by [ReadingDisplay.minuteOf]. */
    private val recorded: Map<Long, String>,
    private val currentPrimary: String?,
    private val nowMs: Long,
) {
    private val sealHorizonMs: Long = nowMs - ReadingDisplay.DISPLAY_SEAL_GRACE_MS

    /** The effective main sensor at [timestampMs], or null when nothing can be resolved. */
    fun mainSensorAt(timestampMs: Long): String? {
        val minute = ReadingDisplay.minuteOf(timestampMs)
        if (minute > sealHorizonMs) return currentPrimary
        return recorded[minute] ?: currentPrimary
    }

    /** Whether [sensorSerial] is the main sensor at [timestampMs]. */
    fun isMainAt(sensorSerial: String?, timestampMs: Long): Boolean {
        val serial = sensorSerial?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val main = mainSensorAt(timestampMs) ?: return false
        return SensorIdentity.matches(serial, main)
    }

    /** Whether the record has anything to say inside this window at all. */
    val hasRecordedOwnership: Boolean get() = recorded.isNotEmpty()

    /** The sensor that is main wherever the record is silent. */
    val primary: String? get() = currentPrimary

    companion object {
        /** No record and no primary: everything resolves to null, which callers treat as "current". */
        val NONE = MainSensorOwnership(emptyMap(), null, 0L)
    }
}
