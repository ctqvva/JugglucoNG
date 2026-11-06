package tk.glucodata.alerts

import tk.glucodata.SensorIdentity

/** A data recovery edge, rather than GATT CONNECTED (which may never produce data). */
internal class SignalRestoredAlertState(private val store: SignalRestoredReadingStore? = null) {
    private var sensorId: String? = null
    private var lastReadingMs = 0L

    init {
        store?.load()?.let {
            sensorId = it.sensorId
            lastReadingMs = it.readingMs
        }
    }

    /** Upgrade fallback only: a display snapshot must not overwrite the durable baseline. */
    fun seed(id: String?, readingMs: Long) {
        if (lastReadingMs == 0L && !id.isNullOrBlank() && readingMs > 0L) {
            remember(id, readingMs)
        }
    }

    fun onReading(id: String?, readingMs: Long, nowMs: Long, lossWaitMs: Long, freshnessMs: Long): Boolean {
        if (id.isNullOrBlank() || readingMs <= 0L || readingMs > nowMs) return false
        if (sensorId == null || !SensorIdentity.matches(id, sensorId)) {
            // A first connection or a different main sensor is not a restoration.
            remember(id, readingMs)
            return false
        }
        if (readingMs <= lastReadingMs || nowMs - readingMs > freshnessMs) return false
        val restored = lossWaitMs > 0L && nowMs - lastReadingMs >= lossWaitMs
        remember(id, readingMs)
        return restored
    }

    private fun remember(id: String, readingMs: Long) {
        sensorId = id
        lastReadingMs = readingMs
        store?.save(SignalRestoredReading(id, readingMs))
    }
}
