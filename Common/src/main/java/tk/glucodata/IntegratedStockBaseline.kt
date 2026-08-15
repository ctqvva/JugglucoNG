package tk.glucodata

/**
 * The sensor's own uncorrected value behind each reading, for a driver that
 * folds the user's calibration into what it stores.
 *
 * The model has to be fitted against stock values, but a calibration records
 * what was on screen — already corrected. The phone recovers the stock value by
 * matching the anchor against its driver's replayed source history, which only
 * works on the device that produced the reading. Once the watch holds the
 * sensor the phone's source history stops growing (it sat frozen at 15354
 * samples for the 81 minutes of one trace), so an anchor taken in that window
 * could be rebased by neither device and pinned the fit at an x the stock series
 * never visits.
 *
 * So the device computing a reading keeps its stock value here, and hands it
 * over with the calibration it is asked to record. Bounded and in memory only:
 * it exists to answer for a reading the user is calibrating against, which is
 * minutes old, and the value it produces is persisted on the anchor itself.
 */
object IntegratedStockBaseline {
    /** Roughly a day at one reading a minute, per sensor. */
    private const val MAX_ENTRIES = 1500

    /** How far from the asked-for time a stock value may still be its own. */
    private const val MATCH_WINDOW_MS = 5L * 60L * 1000L

    private val byKey = java.util.concurrent.ConcurrentHashMap<String, java.util.TreeMap<Long, Float>>()

    private fun keyOf(sensorId: String?): String? =
        sensorId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            (runCatching { SensorIdentity.canonicalSensorId(it) }.getOrNull() ?: it).lowercase()
        }

    /** Called by a managed driver as it computes each reading. */
    @JvmStatic
    fun record(sensorId: String?, timestampMs: Long, stockDisplayValue: Float) {
        val key = keyOf(sensorId) ?: return
        if (timestampMs <= 0L || !stockDisplayValue.isFinite() || stockDisplayValue <= 0f) return
        val series = byKey.getOrPut(key) { java.util.TreeMap() }
        synchronized(series) {
            series[timestampMs] = stockDisplayValue
            while (series.size > MAX_ENTRIES) series.pollFirstEntry() ?: break
        }
    }

    /**
     * The stock value at [timestampMs], or NaN when this device did not produce
     * that reading. Callers must treat NaN as "unknown", never as a value.
     */
    @JvmStatic
    fun stockAt(sensorId: String?, timestampMs: Long): Float {
        val key = keyOf(sensorId) ?: return Float.NaN
        val series = byKey[key] ?: return Float.NaN
        val nearest = synchronized(series) {
            val floor = series.floorEntry(timestampMs)
            val ceiling = series.ceilingEntry(timestampMs)
            when {
                floor == null -> ceiling
                ceiling == null -> floor
                timestampMs - floor.key <= ceiling.key - timestampMs -> floor
                else -> ceiling
            }
        } ?: return Float.NaN
        if (kotlin.math.abs(nearest.key - timestampMs) > MATCH_WINDOW_MS) return Float.NaN
        return nearest.value
    }
}
