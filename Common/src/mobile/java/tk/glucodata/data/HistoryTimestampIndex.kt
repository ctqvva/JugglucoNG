package tk.glucodata.data

/**
 * Every stored reading's timestamp, per raw sensor serial, and nothing else.
 *
 * This is the part of the whole timeline the merge genuinely depends on. The
 * cross-sensor merge decides who owns a stretch from each sensor's *coverage*
 * — where it has readings and where it does not — and until now the only way
 * to know that was to read every row of every sensor, which is why every
 * screen was handed the whole store on every write. Coverage is a function of
 * timestamps alone, so the timestamps are kept here, eight bytes a reading
 * (half a year at one a minute is two megabytes), and the rows themselves are
 * read only for the window on screen.
 *
 * Pure: no Room, no threads. [HistoryTimestampIndexTracker] keeps one in step
 * with the database.
 */
class HistoryTimestampIndex : HistoryCoverage {
    private val bySerial = HashMap<String, SensorTimestamps>()
    private var segmentCacheVersion = -1
    private val segmentCache = HashMap<Set<String>, List<LongRange>>()

    /** Bumped on every change, so derived caches know when to drop what they hold. */
    var version: Int = 0
        private set

    val isEmpty: Boolean get() = bySerial.values.all { it.size == 0 }

    val readingCount: Int get() = bySerial.values.sumOf { it.size }

    /** Oldest stored timestamp, or null for an empty store. */
    val earliest: Long? get() = bySerial.values.mapNotNull { it.first() }.minOrNull()

    /** Newest stored timestamp, or null for an empty store. */
    val latest: Long? get() = bySerial.values.mapNotNull { it.last() }.maxOrNull()

    override val rawSerials: Set<String> get() = bySerial.filterValues { it.size > 0 }.keys

    fun add(sensorSerial: String, timestamp: Long) {
        if (bySerial.getOrPut(sensorSerial) { SensorTimestamps() }.add(timestamp)) touch()
    }

    fun remove(sensorSerial: String, timestamp: Long) {
        if (bySerial[sensorSerial]?.remove(timestamp) == true) touch()
    }

    fun clear() {
        if (bySerial.isEmpty()) return
        bySerial.clear()
        touch()
    }

    /** Replaces everything held for [sensorSerial]; [sortedTimestamps] must ascend. */
    fun replaceSensor(sensorSerial: String, sortedTimestamps: LongArray) {
        if (sortedTimestamps.isEmpty()) {
            if (bySerial.remove(sensorSerial) != null) touch()
            return
        }
        bySerial[sensorSerial] = SensorTimestamps.ofSorted(sortedTimestamps)
        touch()
    }

    fun timestampsOf(sensorSerial: String): SensorTimestamps? = bySerial[sensorSerial]

    override fun lastTimestampOf(serials: Collection<String>): Long? =
        serials.mapNotNull { bySerial[it]?.last() }.maxOrNull()

    override fun hasReadingInMinute(serials: Collection<String>, minuteBucket: Long): Boolean =
        serials.any { bySerial[it]?.hasReadingInMinute(minuteBucket) == true }

    /**
     * The stretches [serials] cover between them, split where the combined
     * readings are more than [gapMs] apart. Cached per set of serials until the
     * index changes, since the merge asks for the same few sets per window.
     */
    override fun segmentsOf(serials: Collection<String>, gapMs: Long): List<LongRange> {
        if (segmentCacheVersion != version) {
            segmentCache.clear()
            segmentCacheVersion = version
        }
        val key = serials.toSet()
        return segmentCache.getOrPut(key) { buildSegments(key, gapMs) }
    }

    private fun buildSegments(serials: Set<String>, gapMs: Long): List<LongRange> {
        val arrays = serials.mapNotNull { bySerial[it]?.takeIf { s -> s.size > 0 } }
        if (arrays.isEmpty()) return emptyList()
        val merged = mergeAscending(arrays)
        val segments = ArrayList<LongRange>()
        var start = merged[0]
        var end = start
        for (index in 1 until merged.size) {
            val timestamp = merged[index]
            if (timestamp - end > gapMs) {
                segments.add(start..end)
                start = timestamp
            }
            end = timestamp
        }
        segments.add(start..end)
        return segments
    }

    private fun mergeAscending(arrays: List<SensorTimestamps>): LongArray {
        if (arrays.size == 1) return arrays[0].toArray()
        val total = arrays.sumOf { it.size }
        val out = LongArray(total)
        val cursors = IntArray(arrays.size)
        for (i in 0 until total) {
            var best = -1
            var bestValue = Long.MAX_VALUE
            for (a in arrays.indices) {
                val c = cursors[a]
                if (c < arrays[a].size) {
                    val v = arrays[a][c]
                    if (v < bestValue) {
                        bestValue = v
                        best = a
                    }
                }
            }
            out[i] = bestValue
            cursors[best]++
        }
        return out
    }

    private fun touch() {
        version++
    }
}

/**
 * What [HistoryDisplayMerge] needs to know about the whole store to merge a
 * window of it correctly: which sensors exist, where each has readings, and
 * when each last read. Implemented by [HistoryTimestampIndex]; a test can
 * implement it directly.
 */
interface HistoryCoverage {
    val rawSerials: Set<String>
    fun segmentsOf(serials: Collection<String>, gapMs: Long): List<LongRange>
    fun lastTimestampOf(serials: Collection<String>): Long?
    fun hasReadingInMinute(serials: Collection<String>, minuteBucket: Long): Boolean
}

/** A sorted, growable set of timestamps for one sensor. */
class SensorTimestamps private constructor(private var values: LongArray, size: Int) {
    constructor() : this(LongArray(256), 0)

    var size: Int = size
        private set

    operator fun get(index: Int): Long = values[index]

    fun first(): Long? = if (size == 0) null else values[0]

    fun last(): Long? = if (size == 0) null else values[size - 1]

    fun toArray(): LongArray = values.copyOf(size)

    /** @return false when [timestamp] was already present. */
    fun add(timestamp: Long): Boolean {
        if (size > 0 && timestamp > values[size - 1]) {
            ensureCapacity(size + 1)
            values[size++] = timestamp
            return true
        }
        val index = lowerBound(timestamp)
        if (index < size && values[index] == timestamp) return false
        ensureCapacity(size + 1)
        System.arraycopy(values, index, values, index + 1, size - index)
        values[index] = timestamp
        size++
        return true
    }

    /** @return false when [timestamp] was not present. */
    fun remove(timestamp: Long): Boolean {
        val index = lowerBound(timestamp)
        if (index >= size || values[index] != timestamp) return false
        System.arraycopy(values, index + 1, values, index, size - index - 1)
        size--
        return true
    }

    fun hasReadingInMinute(minuteBucket: Long): Boolean {
        val index = lowerBound(minuteBucket * 60_000L)
        return index < size && values[index] / 60_000L == minuteBucket
    }

    /** How many timestamps fall in [start, end]. */
    fun countInRange(start: Long, end: Long): Int {
        if (end < start) return 0
        return (upperBound(end) - lowerBound(start)).coerceAtLeast(0)
    }

    /** First index whose timestamp is at or after [timestamp]. */
    fun lowerBound(timestamp: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < timestamp) low = mid + 1 else high = mid
        }
        return low
    }

    /** First index whose timestamp is after [timestamp]. */
    fun upperBound(timestamp: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] <= timestamp) low = mid + 1 else high = mid
        }
        return low
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= values.size) return
        values = values.copyOf(maxOf(needed, values.size * 2))
    }

    companion object {
        fun ofSorted(sorted: LongArray): SensorTimestamps {
            // Duplicates collapse; the set semantics of add() hold here too.
            var write = 0
            for (i in sorted.indices) {
                if (write == 0 || sorted[i] != sorted[write - 1]) sorted[write++] = sorted[i]
            }
            return SensorTimestamps(sorted, write)
        }
    }
}
