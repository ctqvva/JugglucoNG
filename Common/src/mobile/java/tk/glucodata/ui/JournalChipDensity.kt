package tk.glucodata.ui

import tk.glucodata.data.journal.JournalChartMarker
import tk.glucodata.data.journal.JournalEntryType

/** Doses of different insulins must never share a pile, even when their amounts match. */
internal fun journalChipKind(marker: JournalChartMarker): Any =
    if (marker.type == JournalEntryType.INSULIN) marker.title to marker.accentColor else marker.type

/**
 * Folds additive labels whose preferred rectangles overlap.
 * No time-range cutoff: sparse days keep their chips, busy hours can share a pile.
 */
internal object JournalChipDensity {
    internal data class Entry(val timestamp: Long, val width: Float, val mergeKey: Any?, val top: Float = 0f)

    /**
     * Entries are in time order. Compare the measured labels where they would actually sit,
     * before the chip solver spreads them across the glucose curve. Empty chart space above
     * a busy insulin band cannot make that band less crowded. Different insulins and labels
     * at separate heights remain independent. Each group is bounded by its first label's
     * rectangle, so a continuous run of doses cannot chain into one huge total.
     *
     * Run over the whole journal at the settled zoom. Horizontal membership does not depend
     * on the viewport's pan position. Returns each entry's pile leader.
     */
    fun leaders(
        entries: List<Entry>,
        durationMillis: Long,
        chartWidth: Float,
        chipHeight: Float,
        gap: Float
    ): IntArray {
        val leaders = IntArray(entries.size) { it }
        if (entries.isEmpty() || durationMillis <= 0 || chartWidth <= 0f || chipHeight <= 0f) return leaders
        val openByKey = HashMap<Any, MutableList<Int>>()
        entries.forEachIndexed { index, entry ->
            val key = entry.mergeKey ?: return@forEachIndexed
            val open = openByKey.getOrPut(key) { ArrayList() }
            fun distanceX(first: Int) = (entry.timestamp - entries[first].timestamp).toDouble() * chartWidth / durationMillis
            open.removeAll { distanceX(it) >= entries[it].width + gap }
            val host = open.filter { kotlin.math.abs(entry.top - entries[it].top) < chipHeight + gap }
                .minByOrNull { kotlin.math.abs(entry.top - entries[it].top) }
            if (host == null) open += index else leaders[index] = host
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
