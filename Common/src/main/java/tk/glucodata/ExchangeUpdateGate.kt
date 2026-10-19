package tk.glucodata

import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limit for exchange outputs under "collapse into chunks": lets the first reading of each
 * interval through and holds back the rest. It decides *whether* to send, never *what*: the
 * payload is always the newest reading under its own timestamp.
 *
 * The interval is counted on the reading's own time, so it does not depend on when the
 * callback happens to run, and only moves forward: an older reading never sends.
 *
 * An interval number only means something for the interval length it was counted with, so the
 * length is stored alongside it. When the user changes the length (the smoothing minutes), the
 * old number is not compared against the new one: the next reading sends and starts the count
 * afresh. Comparing across lengths would hold every output back until the new count caught up
 * with the old one, which for 3 → 5 minutes is years.
 */
class ExchangeUpdateGate {
    private data class Last(val intervalMinutes: Int, val bucket: Long)

    private val lastBySensor = ConcurrentHashMap<String, Last>()

    /** @param intervalMinutes 0 (or an unusable [payloadTimeMs]) lets everything through. */
    fun shouldEmit(sensorId: String?, payloadTimeMs: Long, intervalMinutes: Int): Boolean {
        if (intervalMinutes <= 0 || payloadTimeMs <= 0L) {
            return true
        }
        val key = if (!sensorId.isNullOrEmpty()) sensorId else "<unknown>"
        val bucket = payloadTimeMs / (intervalMinutes * 60_000L)
        // Only a newer interval sends. A reading that arrives out of order (a backfill, an older
        // timestamp) belongs to an interval already sent, and must not make the gate forget it.
        var emit = false
        lastBySensor.compute(key) { _, previous ->
            if (previous == null || previous.intervalMinutes != intervalMinutes || bucket > previous.bucket) {
                emit = true
                Last(intervalMinutes, bucket)
            } else {
                previous
            }
        }
        return emit
    }
}
