// Libre3BackfillMerge.kt — how minute readings meet five-minute fill-ins already in Room.
//
// During a takeover the realtime frame's five-minute history value can reach Room before the
// clinical minute for the same life count arrives. Both map to the same timestamp, so the generic
// near-duplicate filter would drop the minute value while the native mirror still accepted it,
// leaving the chart and native exports disagreeing. A minute whose only Room neighbours are this
// driver's own fill-ins replaces the fill-in instead; a minute next to any other reading (an
// earlier clinical value or a live one) is left to the duplicate filter, so those are preserved.

package tk.glucodata.drivers.libre3

import tk.glucodata.drivers.VirtualGlucoseSensorBridge

internal object Libre3BackfillMerge {

    data class Plan(
        /** Minute readings re-stamped onto the fill-in they replace (same Room minute bucket). */
        val replacing: List<VirtualGlucoseSensorBridge.Reading>,
        /** Minute readings that go through the normal near-duplicate import. */
        val regular: List<VirtualGlucoseSensorBridge.Reading>,
        /** Fill-in timestamps that are no longer fill-ins once [replacing] is stored. */
        val consumedFillers: Set<Long>,
    )

    fun plan(
        minutes: List<VirtualGlucoseSensorBridge.Reading>,
        roomTimestamps: LongArray,
        fillers: Set<Long>,
        windowMs: Long,
    ): Plan {
        val sorted = roomTimestamps.sortedArray()
        val replacing = ArrayList<VirtualGlucoseSensorBridge.Reading>()
        val regular = ArrayList<VirtualGlucoseSensorBridge.Reading>()
        val consumed = HashSet<Long>()
        for (m in minutes.sortedBy { it.timestampMs }) {
            val neighbours = neighbours(sorted, m.timestampMs, windowMs)
            if (neighbours.isNotEmpty() && neighbours.all { it in fillers } && neighbours.none { it in consumed }) {
                val target = neighbours.minByOrNull { kotlin.math.abs(it - m.timestampMs) }!!
                replacing += m.copy(timestampMs = target)
                consumed += neighbours
            } else {
                regular += m
            }
        }
        return Plan(replacing, regular, consumed)
    }

    private fun neighbours(sorted: LongArray, t: Long, windowMs: Long): List<Long> {
        var lo = java.util.Arrays.binarySearch(sorted, t - windowMs)
        if (lo < 0) lo = -lo - 1
        val out = ArrayList<Long>(2)
        var i = lo
        while (i < sorted.size && sorted[i] <= t + windowMs) out += sorted[i++]
        return out
    }

    /** Stored form: comma-separated timestamps, newest [cap] kept. */
    fun encode(fillers: Collection<Long>, cap: Int = 600): String =
        fillers.sorted().takeLast(cap).joinToString(",")

    fun decode(text: String?): MutableSet<Long> =
        text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
}
