package tk.glucodata.ui

/**
 * Shared pieces of the History and Journal timelines that have to stay cheap
 * as the store grows: what a row's arrow regresses over, and how rows are keyed.
 */

/**
 * The history, ascending by timestamp, without copying it when it already is.
 *
 * The repository emits ascending order and the screens re-sorted it anyway,
 * which is a copy of the whole store on every emission, on the main thread,
 * to produce a list identical to the one it was given.
 */
internal fun List<GlucosePoint>.ascendingByTimestamp(): List<GlucosePoint> {
    for (index in 1 until size) {
        if (this[index - 1].timestamp > this[index].timestamp) return sortedBy { it.timestamp }
    }
    return this
}

/**
 * The most points a row's arrow can be handed. TrendEngine regresses over at
 * most 25 minutes and 30 readings behind the row, but it also measures the
 * sensor's cadence and drops readings that are not readings, so it is given
 * a comfortable tail rather than exactly the window — twelve hours at one
 * reading a minute — as a view, not a copy.
 */
internal const val ROW_TREND_TAIL_LIMIT = 720

/**
 * The points a row's arrow regresses over: the row's own reading and what
 * precedes it in the full history, newest first.
 *
 * Read from the whole ascending history rather than from the rows on screen.
 * The screens used to hand each row the other rows of its day section, which
 * is the right series on a day of readings and the wrong one everywhere else:
 * in a journal ledger the rows are the readings that happen to carry an entry,
 * hours apart, and an arrow regressed over those describes nothing. It was
 * also rebuilt per row per recomposition, which is what made a long ledger
 * crawl.
 *
 * Returns a view; nothing is copied.
 */
internal fun rowTrendHistory(ascending: List<GlucosePoint>, timestamp: Long): List<GlucosePoint> {
    if (ascending.isEmpty()) return emptyList()
    // Last index whose timestamp is at or before the row's.
    var low = 0
    var high = ascending.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (ascending[mid].timestamp <= timestamp) low = mid + 1 else high = mid
    }
    val endExclusive = low
    if (endExclusive == 0) return emptyList()
    val start = (endExclusive - ROW_TREND_TAIL_LIMIT).coerceAtLeast(0)
    return ascending.subList(start, endExclusive).asReversed()
}

/**
 * Makes row keys unique without an index in them.
 *
 * A LazyColumn key must be unique, and the screens made theirs so by appending
 * the row's index. A new reading lands at the top of the newest section, so
 * every row below it moves by one, every key changes, and the list treats the
 * whole visible page as new rows — recomposed, re-animated, and no longer
 * anchored to the row the user was reading. Keys are unique by content; only a
 * genuine duplicate gets a suffix.
 */
internal fun uniqueRowKeys(keys: List<String>): List<String> {
    val seen = HashMap<String, Int>(keys.size)
    return keys.map { key ->
        val count = seen[key] ?: 0
        seen[key] = count + 1
        if (count == 0) key else "$key#$count"
    }
}
