package tk.glucodata.data

import tk.glucodata.ui.GlucosePoint

/**
 * The rule behind [DisplayLaneHistory], without the storage: a minute with no
 * record is shown in the lane that was on at that minute, and a minute is only
 * recorded in that lane.
 */
internal object LaneAtTheTime {

    /**
     * Gives every unrecorded point from before a lane switch the value of the
     * lane that was on then, as its record. Points that already carry a record,
     * and points since the last switch, are left alone.
     *
     * @param laneValueMgdl the point's value in the given view mode's lane, or
     *   null when that lane has nothing to draw for it.
     */
    fun apply(
        points: List<GlucosePoint>,
        switchesFor: (String) -> List<DisplayLaneHistory.Switch>,
        laneValueMgdl: (GlucosePoint, Int) -> Float?,
    ): List<GlucosePoint> {
        if (points.isEmpty()) return points
        val switches = HashMap<String, List<DisplayLaneHistory.Switch>>()
        var changed = false
        val out = points.map { point ->
            if (point.sealedDisplayValue != null) return@map point
            val serial = point.sensorSerial ?: return@map point
            val log = switches.getOrPut(serial) { switchesFor(serial) }
            val viewMode = DisplayLaneHistory.viewModeAt(log, point.timestamp) ?: return@map point
            val value = laneValueMgdl(point, viewMode)
                ?.takeIf { it.isFinite() && it > 0.1f }
                ?: return@map point
            changed = true
            point.copy(sealedDisplayValue = value, sealedDisplayViewMode = viewMode)
        }
        return if (changed) out else points
    }

    /**
     * Whether a minute drawn in [viewMode] may be recorded. A minute that ended
     * before a lane switch was on the old lane; drawn in the new one, it is a
     * frame painted before the history caught up with the switch.
     */
    fun recordable(switches: List<DisplayLaneHistory.Switch>, minuteMs: Long, viewMode: Int): Boolean {
        val laneThen = DisplayLaneHistory.viewModeAt(
            switches,
            ReadingDisplay.minuteOf(minuteMs) + ReadingDisplay.MINUTE_MS - 1L,
        ) ?: return true
        return (laneThen and 1) == (viewMode and 1)
    }
}
