package tk.glucodata.ui

/**
 * Journal entries on a zoomed-out chart, where a labelled chip per entry would bury the curve.
 *
 * Each entry goes to a rail along the chart's top or bottom edge instead of floating beside its
 * dot. Entries of one kind close together in time merge into one mark that shows their total,
 * and marks that would still touch on a rail move to its next row. Everything is worked out in
 * time rather than pixels, from the whole journal, so panning neither regroups the marks nor
 * moves them between rows; only a new zoom does.
 */
internal object JournalOverviewLayout {

    /** One entry: when it was, which rail it rides, and which others it may merge with. */
    internal data class Entry(val timestamp: Long, val rail: Int, val mergeKey: Any)

    /**
     * One mark on a rail: the [members] it stands for, indices into the entries ascending by
     * time; the time it is centred on; and its [row], 0 being the one nearest the chart's edge.
     */
    internal class Mark(val members: IntArray, val centerMillis: Long, val rail: Int, val row: Int) {
        val first: Int get() = members[0]
    }

    /**
     * Lays out [entries], which must be ascending by time.
     *
     * An entry merges into the open mark of its [Entry.mergeKey] while it is within [mergeMillis]
     * of that mark's first entry; measuring from the first rather than the last keeps a long run
     * of doses from chaining into one mark. Each mark is [widthMillis] wide; on its rail it takes
     * the first of [rows] rows where it clears the mark before it by [gapMillis], or, when none
     * has room, the row whose last mark ends earliest.
     */
    fun layout(
        entries: List<Entry>,
        mergeMillis: Long,
        gapMillis: Long,
        rows: Int,
        widthMillis: (IntArray) -> Long
    ): List<Mark> {
        if (entries.isEmpty()) return emptyList()
        class Open(val start: Long, val members: ArrayList<Int>)
        val open = HashMap<Any, Open>()
        val groups = ArrayList<IntArray>()
        entries.forEachIndexed { index, entry ->
            val current = open[entry.mergeKey]
            if (current != null && entry.timestamp - current.start <= mergeMillis) {
                current.members += index
            } else {
                current?.let { groups += it.members.toIntArray() }
                open[entry.mergeKey] = Open(entry.timestamp, arrayListOf(index))
            }
        }
        open.values.forEach { groups += it.members.toIntArray() }

        val marks = ArrayList<Mark>(groups.size)
        groups
            .map { members ->
                val center = (entries[members.first()].timestamp + entries[members.last()].timestamp) / 2L
                Triple(members, center, entries[members.first()].rail)
            }
            .groupBy { it.third }
            .forEach { (rail, onRail) ->
                val rowEnds = LongArray(rows.coerceAtLeast(1)) { Long.MIN_VALUE }
                onRail
                    .sortedWith(compareBy({ it.second }, { it.first[0] }))
                    .forEach { (members, center) ->
                        val half = widthMillis(members) / 2L
                        val left = center - half
                        var row = rowEnds.indices.firstOrNull { rowEnds[it] == Long.MIN_VALUE || left >= rowEnds[it] + gapMillis }
                        if (row == null) row = rowEnds.indices.minBy { rowEnds[it] }
                        rowEnds[row] = maxOf(rowEnds[row], center + half)
                        marks += Mark(members, center, rail, row)
                    }
            }
        marks.sortBy { it.first }
        return marks
    }
}

/** How far a carb entry may be from a reading and still be pinned to the curve there. */
internal const val JOURNAL_TRACE_MAX_GAP_MILLIS = 15L * 60_000L

/**
 * Below this many dp per hour the chart shows the journal as rail marks rather than chips:
 * a phone's 12-hour view and wider, a tablet's 24-hour view and wider.
 */
internal const val JOURNAL_OVERVIEW_MAX_DP_PER_HOUR = 40f

internal fun isJournalOverviewZoom(visibleDurationMillis: Long, chartWidthDp: Float): Boolean {
    if (visibleDurationMillis <= 0L || chartWidthDp <= 0f) return false
    val hours = visibleDurationMillis / 3_600_000f
    return chartWidthDp / hours < JOURNAL_OVERVIEW_MAX_DP_PER_HOUR
}
