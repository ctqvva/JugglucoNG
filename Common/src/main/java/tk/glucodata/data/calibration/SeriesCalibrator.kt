package tk.glucodata.data.calibration

/**
 * Calibrates a whole series against one resolved calibration context.
 *
 * `CalibrationManager.getCalibratedValue` answers one value at a time: it
 * re-resolves the sensor, the enablement, the algorithm and the point set, then
 * filters the calibration points for the target timestamp, before it does any
 * arithmetic — and remembers the answer in a 4096-entry cache. The chart calls
 * it for every point of the whole stored timeline on every rebuild. Past 4096
 * points the cache is walked end to end and evicts each entry before it is
 * asked for again, so every rebuild paid the full resolution for every point,
 * on the main thread, once a minute, and linearly longer as the store grew.
 *
 * This resolves the context once and keeps two things across points:
 *
 *  - [CalibrationMath.resolvePointsForTimestamp] depends on the target only
 *    through "which calibration points precede it", so its result is constant
 *    between consecutive calibration timestamps. It is computed once per such
 *    stretch, found by a binary search over the sorted timestamps.
 *  - The calibrated value of each (timestamp, value) pair, so the next rebuild
 *    of the same series recomputes only the points that changed.
 *
 * The arithmetic is [CalibrationMath], unchanged, so a value produced here is
 * the value `getCalibratedValue` produces for the same inputs. The instance is
 * only valid for the context it was built from; the manager hands out a fresh
 * one whenever a calibration, a setting or the sensor changes.
 */
internal class SeriesCalibrator(
    private val allPoints: List<CalPoint>,
    private val earliestPoint: CalPoint?,
    private val algorithm: String,
    private val tuning: CalibrationTuning,
    private val applyToPast: Boolean,
) {
    private val sortedTimestamps: LongArray =
        LongArray(allPoints.size) { allPoints[it].timestamp }.also { it.sort() }
    private val resolvedByRank = HashMap<Int, List<CalPoint>>()
    private val results = LongFloatResultCache()

    /** Same contract as `CalibrationManager.getCalibratedValue`: a value that is not a reading comes back untouched. */
    fun calibrate(value: Float, timestamp: Long): Float {
        if (!value.isFinite() || value <= 0f) return value
        val valueBits = java.lang.Float.floatToRawIntBits(value)
        results.get(timestamp, valueBits)?.let { return it }
        val calibrated = compute(value, timestamp)
        results.put(timestamp, valueBits, calibrated)
        return calibrated
    }

    private fun compute(value: Float, timestamp: Long): Float {
        val points = resolvedByRank.getOrPut(rankOf(timestamp)) {
            CalibrationMath.resolvePointsForTimestamp(allPoints, timestamp, earliestPoint, tuning)
        }
        if (points.isEmpty()) return value
        val computation = CalibrationMath.computeAlgorithm(
            algorithm = algorithm,
            targetValue = value.toDouble(),
            targetTimestamp = timestamp,
            points = points,
            tuning = tuning
        )
        val calibrated = CalibrationMath.sanitizeCalibratedValue(computation.prediction, value)
        return if (applyToPast) {
            calibrated
        } else {
            CalibrationMath.applyPastPolicy(
                originalValue = value,
                calibratedValue = calibrated,
                targetTimestamp = timestamp,
                points = points
            )
        }
    }

    /** How many calibration points are at or before [timestamp]. */
    private fun rankOf(timestamp: Long): Int {
        var low = 0
        var high = sortedTimestamps.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedTimestamps[mid] <= timestamp) low = mid + 1 else high = mid
        }
        return low
    }

    /** Test hook: how many distinct point sets were resolved. */
    internal val resolvedStretchCount: Int get() = resolvedByRank.size

    /** Test hook: how many results are held. */
    internal val cachedResultCount: Int get() = results.size
}

/**
 * An open-addressed `(timestamp, valueBits) -> Float` table with primitive
 * storage, so a hundred thousand cached points cost a few megabytes rather
 * than a boxed entry each.
 *
 * Keyed by timestamp; the value bits are checked on a hit, so a point whose
 * value moved (smoothing shifts the last few values when a reading lands) is
 * recomputed rather than served stale. A timestamp is held once: two values at
 * the same instant simply replace each other, which is correct and rare.
 */
internal class LongFloatResultCache(initialCapacity: Int = 1024) {
    private var keys = LongArray(capacityFor(initialCapacity))
    private var used = BooleanArray(keys.size)
    private var valueBits = IntArray(keys.size)
    private var values = FloatArray(keys.size)
    var size: Int = 0
        private set

    fun get(key: Long, bits: Int): Float? {
        var index = indexFor(key, keys.size)
        while (used[index]) {
            if (keys[index] == key) {
                return if (valueBits[index] == bits) values[index] else null
            }
            index = (index + 1) and (keys.size - 1)
        }
        return null
    }

    fun put(key: Long, bits: Int, value: Float) {
        if ((size + 1) * 2 > keys.size) grow()
        var index = indexFor(key, keys.size)
        while (used[index]) {
            if (keys[index] == key) {
                valueBits[index] = bits
                values[index] = value
                return
            }
            index = (index + 1) and (keys.size - 1)
        }
        used[index] = true
        keys[index] = key
        valueBits[index] = bits
        values[index] = value
        size++
    }

    private fun grow() {
        val oldKeys = keys
        val oldUsed = used
        val oldBits = valueBits
        val oldValues = values
        keys = LongArray(oldKeys.size * 2)
        used = BooleanArray(keys.size)
        valueBits = IntArray(keys.size)
        values = FloatArray(keys.size)
        size = 0
        for (i in oldKeys.indices) {
            if (oldUsed[i]) put(oldKeys[i], oldBits[i], oldValues[i])
        }
    }

    private fun indexFor(key: Long, capacity: Int): Int {
        // Timestamps are millisecond multiples of a minute apart; mix the bits
        // so they do not all land in the same few slots.
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return (h.toInt()) and (capacity - 1)
    }

    private fun capacityFor(requested: Int): Int {
        var capacity = 16
        while (capacity < requested) capacity = capacity shl 1
        return capacity
    }
}
