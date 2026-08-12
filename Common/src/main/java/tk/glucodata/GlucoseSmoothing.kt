package tk.glucodata

/**
 * The chart smoothing pipeline, shared by the phone and the watch.
 *
 * This lived in the phone's chart file, so the watch drew whatever the store
 * held and the user's smoothing setting simply did not exist there — the same
 * sensor produced a smooth curve on the phone and a noisy one on the wrist.
 *
 * Three steps, in order, matching what [DataSmoothing]'s settings describe:
 *  1. split the series wherever the data has a real hole or changes sensor, so
 *     nothing is averaged across a gap;
 *  2. run a centred moving average over each segment, both lanes independently;
 *  3. optionally collapse each segment into one reading per interval.
 *
 * It is generic over the point type because the phone's chart carries a richer
 * one (a formatted time, a sensor serial) than the watch's, and neither wants
 * to allocate a conversion of the whole horizon to call this.
 */
object GlucoseSmoothing {

    /** Below this many points a window cannot say anything useful. */
    private const val MIN_POINTS = 3

    /** A value at or under this is a hole, not a reading, and never averaged. */
    private const val MIN_VALID_VALUE = 0.1f

    /**
     * Smooths [points] (ascending by timestamp) per [DataSmoothing]'s settings.
     *
     * @param withValues rebuilds a point carrying new auto and raw values.
     * @param nowMillis  the boundary for the still-open collapse bucket, which
     *                   is left uncollapsed so the newest reading keeps its own
     *                   timestamp instead of jumping to a bucket edge.
     */
    fun <T> smooth(
        points: List<T>,
        smoothingMinutes: Int,
        collapseIntoChunks: Boolean,
        timestamp: (T) -> Long,
        value: (T) -> Float,
        rawValue: (T) -> Float,
        sensorSerial: (T) -> String? = { null },
        withValues: (T, Float, Float) -> T,
        nowMillis: Long = System.currentTimeMillis(),
        gapThresholdMs: Long = GlucoseChartGap.THRESHOLD_MS,
    ): List<T> {
        if (smoothingMinutes <= 0 || points.size < MIN_POINTS) return points
        val halfWindowMs = (smoothingMinutes * 60_000L) / 2L
        if (halfWindowMs <= 0L) return points

        val collapsedInterval = DataSmoothing.collapseIntervalMinutes(smoothingMinutes)
        val result = ArrayList<T>(points.size)

        splitSegments(points, timestamp, sensorSerial, gapThresholdMs).forEach { segment ->
            val smoothed = if (segment.size < MIN_POINTS) {
                segment
            } else {
                val autoLane = movingAverage(segment, halfWindowMs, timestamp, value)
                val rawLane = movingAverage(segment, halfWindowMs, timestamp, rawValue)
                segment.mapIndexed { index, point ->
                    withValues(point, autoLane[index], rawLane[index])
                }
            }
            if (collapseIntoChunks) {
                result.addAll(collapse(smoothed, collapsedInterval, timestamp, nowMillis))
            } else {
                result.addAll(smoothed)
            }
        }
        return result
    }

    /**
     * Breaks a series wherever a real hole or a sensor change means the points
     * on either side do not describe one continuous trace.
     */
    fun <T> splitSegments(
        points: List<T>,
        timestamp: (T) -> Long,
        sensorSerial: (T) -> String? = { null },
        gapThresholdMs: Long = GlucoseChartGap.THRESHOLD_MS,
    ): List<List<T>> {
        if (points.isEmpty()) return emptyList()
        val segments = ArrayList<List<T>>()
        var current = ArrayList<T>()
        var lastTimestamp = Long.MIN_VALUE
        var lastSerial: String? = null

        points.forEach { point ->
            val serial = sensorSerial(point)
            val sensorChanged = current.isNotEmpty() && sensorChanged(lastSerial, serial)
            val gapExceeded = current.isNotEmpty() &&
                lastTimestamp != Long.MIN_VALUE &&
                (timestamp(point) - lastTimestamp) > gapThresholdMs
            if (sensorChanged || gapExceeded) {
                segments.add(current)
                current = ArrayList()
            }
            current.add(point)
            lastTimestamp = timestamp(point)
            lastSerial = serial
        }
        if (current.isNotEmpty()) segments.add(current)
        return segments
    }

    /**
     * Centred moving average over a time window, carrying holes through
     * untouched so a missing lane does not get invented from its neighbours.
     */
    private fun <T> movingAverage(
        points: List<T>,
        halfWindowMs: Long,
        timestamp: (T) -> Long,
        selector: (T) -> Float,
    ): FloatArray {
        val size = points.size
        val prefixSums = DoubleArray(size + 1)
        val prefixCounts = IntArray(size + 1)
        for (index in 0 until size) {
            val v = selector(points[index])
            val valid = v.isFinite() && v >= MIN_VALID_VALUE
            prefixSums[index + 1] = prefixSums[index] + if (valid) v.toDouble() else 0.0
            prefixCounts[index + 1] = prefixCounts[index] + if (valid) 1 else 0
        }

        val result = FloatArray(size)
        var windowStart = 0
        var windowEndExclusive = 0
        for (index in 0 until size) {
            val original = selector(points[index])
            if (!original.isFinite() || original < MIN_VALID_VALUE) {
                result[index] = original
                continue
            }
            val at = timestamp(points[index])
            val minTime = at - halfWindowMs
            val maxTime = at + halfWindowMs
            while (windowStart < size && timestamp(points[windowStart]) < minTime) windowStart++
            while (windowEndExclusive < size && timestamp(points[windowEndExclusive]) <= maxTime) {
                windowEndExclusive++
            }
            val count = prefixCounts[windowEndExclusive] - prefixCounts[windowStart]
            result[index] = if (count > 0) {
                ((prefixSums[windowEndExclusive] - prefixSums[windowStart]) / count).toFloat()
            } else {
                original
            }
        }
        return result
    }

    /**
     * Keeps the last reading of each completed interval. The bucket [nowMillis]
     * falls in is still filling, so it is left alone rather than represented by
     * a reading that will be superseded a minute later.
     */
    private fun <T> collapse(
        points: List<T>,
        intervalMinutes: Int,
        timestamp: (T) -> Long,
        nowMillis: Long,
    ): List<T> {
        if (points.isEmpty() || intervalMinutes <= 0) return points
        val bucketDurationMs = intervalMinutes * 60_000L
        val openBucket = nowMillis / bucketDurationMs
        val collapsed = ArrayList<T>()
        var activeBucket = Long.MIN_VALUE
        var pending: T? = null

        points.forEach { point ->
            val bucket = timestamp(point) / bucketDurationMs
            if (bucket != activeBucket) {
                if (activeBucket < openBucket) pending?.let(collapsed::add)
                activeBucket = bucket
            }
            pending = point
        }
        if (activeBucket < openBucket) pending?.let(collapsed::add)

        return when {
            collapsed.isNotEmpty() -> collapsed
            points.isNotEmpty() -> listOf(points.last())
            else -> points
        }
    }

    private fun sensorChanged(previous: String?, current: String?): Boolean {
        val a = previous?.trim()?.takeIf { it.isNotEmpty() }
        val b = current?.trim()?.takeIf { it.isNotEmpty() }
        if (a == null && b == null) return false
        if (a == null || b == null) return true
        return !SensorIdentity.matches(a, b)
    }
}
