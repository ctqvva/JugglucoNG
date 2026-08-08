package tk.glucodata

/**
 * Briefly retains a sensor-supplied trend for the exact sample that produced it.
 * This prevents a display refresh from replacing an authoritative device arrow
 * with a regression over older local history.
 */
object VendorTrendRate {
    private const val MATCH_WINDOW_MS = 5_000L
    private const val MAX_ENTRIES = 8

    private data class Entry(
        val sensorId: String,
        val timestampMillis: Long,
        val rate: Float,
        val exchangeTrendIndex: Int,
    )

    @Volatile
    private var entries: List<Entry> = emptyList()

    @Synchronized
    fun publish(
        sensorId: String?,
        timestampMillis: Long,
        rate: Float,
        exchangeTrendIndex: Int = ExchangeTrend.UNKNOWN,
    ) {
        val id = sensorId?.trim().orEmpty()
        if (id.isEmpty() || timestampMillis <= 0L || !rate.isFinite()) return
        val retained = entries.filterNot {
            SensorIdentity.matches(it.sensorId, id) &&
                kotlin.math.abs(it.timestampMillis - timestampMillis) <= MATCH_WINDOW_MS
        }
        entries = (retained + Entry(id, timestampMillis, rate, exchangeTrendIndex)).takeLast(MAX_ENTRIES)
    }

    @JvmStatic
    fun resolve(sensorId: String?, timestampMillis: Long): Float? {
        val id = sensorId?.trim().orEmpty()
        if (id.isEmpty() || timestampMillis <= 0L) return null
        return entries.asReversed().firstOrNull {
            SensorIdentity.matches(it.sensorId, id) &&
                kotlin.math.abs(it.timestampMillis - timestampMillis) <= MATCH_WINDOW_MS
        }?.rate
    }

    @JvmStatic
    fun resolveExchangeTrendIndex(sensorId: String?, timestampMillis: Long): Int {
        val id = sensorId?.trim().orEmpty()
        if (id.isEmpty() || timestampMillis <= 0L) return ExchangeTrend.UNKNOWN
        return entries.asReversed().firstOrNull {
            SensorIdentity.matches(it.sensorId, id) &&
                kotlin.math.abs(it.timestampMillis - timestampMillis) <= MATCH_WINDOW_MS
        }?.exchangeTrendIndex ?: ExchangeTrend.UNKNOWN
    }

    @Synchronized
    internal fun clearForTests() {
        entries = emptyList()
    }
}
