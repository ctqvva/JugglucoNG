package tk.glucodata.ui

import tk.glucodata.data.journal.JournalChartMarker
import tk.glucodata.data.journal.JournalEntryType

/** Doses of different insulins must never share a pile, even when their amounts match. */
internal fun journalChipKind(marker: JournalChartMarker): Any =
    if (marker.type == JournalEntryType.INSULIN) marker.title to marker.accentColor else marker.type

/**
 * Folds additive labels only where their measured footprint would crowd the chart.
 * No time-range cutoff: sparse days keep their chips, busy hours can share a pile.
 */
internal object JournalChipDensity {
    internal data class Entry(val timestamp: Long, val width: Float, val mergeKey: Any?)

    /**
     * Entries are in time order. Each local neighbourhood spans at most one label's width
     * from its first entry, so a continuous run of doses cannot chain into one huge total.
     * Labels may occupy 30% of that neighbourhood's chart area before additive entries
     * share a pile. Notes and fingersticks contribute to crowding but never fold here.
     *
     * Run over the whole journal at the settled zoom: neither membership nor the density
     * calculation depends on the viewport's pan position. Returns each entry's pile leader.
     */
    fun leaders(
        entries: List<Entry>,
        durationMillis: Long,
        chartWidth: Float,
        chartHeight: Float,
        chipHeight: Float
    ): IntArray {
        val leaders = IntArray(entries.size) { it }
        if (entries.isEmpty() || durationMillis <= 0 || chartWidth <= 0f ||
            chartHeight <= 0f || chipHeight <= 0f) return leaders
        val maxWidth = entries.maxOf { it.width }.coerceAtLeast(1f)
        val reachMillis = (maxWidth * durationMillis.toDouble() / chartWidth).toLong().coerceAtLeast(1L)
        var first = 0
        while (first < entries.size) {
            var end = first + 1
            var widthSum = entries[first].width
            while (end < entries.size && entries[end].timestamp - entries[first].timestamp <= reachMillis) {
                widthSum += entries[end].width
                end++
            }
            val span = (entries[end - 1].timestamp - entries[first].timestamp).toDouble() * chartWidth / durationMillis
            val footprint = widthSum * chipHeight
            val budget = (span + maxWidth) * chartHeight * 0.30
            if (footprint > budget) {
                val firstByKey = HashMap<Any, Int>()
                for (index in first until end) {
                    val key = entries[index].mergeKey ?: continue
                    leaders[index] = firstByKey.getOrPut(key) { index }
                }
            }
            first = end
        }
        return leaders
    }

    /** Keep the first visible member independent when the original leader has scrolled away. */
    fun foldInViewport(pileKeys: List<Long>, anchorXs: List<Float>, foldInto: Array<Int?>) {
        pileKeys.indices.groupBy { pileKeys[it] }.values.forEach { group ->
            if (group.size < 2) return@forEach
            val front = group.firstOrNull { anchorXs[it] >= 0f } ?: group.first()
            foldInto[front] = null
            // Folded entries must follow their host in JournalChipLayout's request order.
            group.forEach { index -> if (index > front) foldInto[index] = front }
        }
    }
}

/** How far a carb entry may be from a reading and still be pinned to the curve there. */
internal const val JOURNAL_TRACE_MAX_GAP_MILLIS = 15L * 60_000L
