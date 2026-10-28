package tk.glucodata.data.journal

/**
 * When each AAPS temp basal actually stopped running. A new temp basal replaces the running
 * one, and the replaced record does not always come back shortened, so a segment ends at its
 * own duration or at the next temp basal's start, whichever is first.
 *
 * Works over every temp basal, not just the ones in view: a replacement scrolled out of the
 * chart window still cuts the segment it replaced. Keyed by entry id; temp basals without a
 * duration are left out.
 *
 * The same basal can arrive twice: from the local AAPS status line, kept as one row per run,
 * and from Nightscout, as one row per treatment. Where the status line has a segment, it
 * wins: a Nightscout copy starting inside it is left out, so it can neither cut the status
 * line segment short nor draw a second bar over it. One that started earlier keeps its
 * history and is cut where the status line segment starts, like any replaced temp basal.
 */
fun tempBasalEndsMillis(entries: List<JournalEntry>): Map<Long, Long> {
    val all = entries.filter { it.type == JournalEntryType.TEMP_BASAL }
    val statusLine = all
        .filter { AapsStatusLine.isStatusLineRecord(it.sourceRecordId) }
        .sortedBy { it.timestamp }
    // Both sources keep their whole history, so the coverage test is a binary search, not a
    // scan of every status line segment per Nightscout row. statusLineReach[i] is the latest
    // end among the first i + 1 segments: a time is covered when the last segment starting at
    // or before it, or any earlier one, still runs.
    val statusLineStarts = LongArray(statusLine.size) { statusLine[it].timestamp }
    val statusLineReach = LongArray(statusLine.size)
    statusLine.forEachIndexed { index, segment ->
        val end = segment.ownEndMillis()
        statusLineReach[index] = if (index == 0) end else maxOf(statusLineReach[index - 1], end)
    }
    fun coveredByStatusLine(timestamp: Long): Boolean {
        var low = 0
        var high = statusLineStarts.size - 1
        var last = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (statusLineStarts[mid] <= timestamp) {
                last = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return last >= 0 && timestamp < statusLineReach[last]
    }
    val tempBasals = all
        .filter { entry ->
            AapsStatusLine.isStatusLineRecord(entry.sourceRecordId) || !coveredByStatusLine(entry.timestamp)
        }
        .sortedWith(compareBy({ it.timestamp }, { it.id }))
    return buildMap {
        tempBasals.forEachIndexed { index, entry ->
            if (entry.durationMinutes == null) return@forEachIndexed
            val nextStart = tempBasals.getOrNull(index + 1)?.timestamp ?: Long.MAX_VALUE
            put(entry.id, minOf(entry.ownEndMillis(), nextStart))
        }
    }
}

private fun JournalEntry.ownEndMillis(): Long =
    timestamp + (durationMinutes ?: 0).coerceAtLeast(1) * 60_000L
