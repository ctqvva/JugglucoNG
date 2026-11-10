package tk.glucodata

/**
 * Text form of [XdripBackfillTracker.Coverage] for SharedPreferences:
 * `floor,sensorGen,sensorStart` followed by `|first-last` per sent stretch.
 *
 * The first release stored only `time,sensorGen,sensorStart` (the last live send); that still
 * decodes, as a floor with no stretches.
 */
object XdripBackfillCoverageFormat {
    fun encode(coverage: XdripBackfillTracker.Coverage): String = buildString {
        append(coverage.floorMs).append(',').append(coverage.sensorGen).append(',').append(coverage.sensorStartMs)
        coverage.ranges.forEach { append('|').append(it.first).append('-').append(it.last) }
    }

    fun decode(text: String?): XdripBackfillTracker.Coverage? {
        if (text.isNullOrEmpty()) return null
        val parts = text.split('|')
        val head = parts[0].split(',')
        if (head.size != 3) return null
        val floor = head[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val ranges = parts.drop(1).mapNotNull { part ->
            val bounds = part.split('-')
            val first = bounds.getOrNull(0)?.toLongOrNull()
            val last = bounds.getOrNull(1)?.toLongOrNull()
            if (bounds.size == 2 && first != null && last != null && first <= last) first..last else null
        }
        return XdripBackfillTracker.Coverage(
            floorMs = floor,
            sensorGen = head[1].toIntOrNull() ?: 0,
            sensorStartMs = head[2].toLongOrNull() ?: 0L,
            ranges = ranges,
        )
    }
}
