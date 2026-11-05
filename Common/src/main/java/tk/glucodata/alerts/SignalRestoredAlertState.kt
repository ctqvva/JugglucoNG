package tk.glucodata.alerts

import tk.glucodata.SensorIdentity

/** A data recovery edge, rather than GATT CONNECTED (which may never produce data). */
internal class SignalRestoredAlertState {
    private var sensorId: String? = null
    private var lastReadingMs = 0L

    /** Seed once from the persisted current reading so a process restart retains the gap. */
    fun seed(id: String?, readingMs: Long) {
        if (lastReadingMs == 0L && !id.isNullOrBlank() && readingMs > 0L) {
            sensorId = id
            lastReadingMs = readingMs
        }
    }

    fun onReading(id: String?, readingMs: Long, nowMs: Long, lossWaitMs: Long, freshnessMs: Long): Boolean {
        if (id.isNullOrBlank() || readingMs <= 0L || readingMs > nowMs) return false
        if (sensorId == null || !SensorIdentity.matches(id, sensorId)) {
            // A first connection or a different main sensor is not a restoration.
            sensorId = id
            lastReadingMs = readingMs
            return false
        }
        if (readingMs <= lastReadingMs || nowMs - readingMs > freshnessMs) return false
        val restored = lossWaitMs > 0L && nowMs - lastReadingMs >= lossWaitMs
        lastReadingMs = readingMs
        return restored
    }
}
