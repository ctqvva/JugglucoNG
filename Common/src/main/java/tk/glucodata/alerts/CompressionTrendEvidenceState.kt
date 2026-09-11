package tk.glucodata.alerts

import tk.glucodata.logic.CompressionLowDetector

/**
 * Carries a detector-backed pressure suspicion from the falling edge into its rebound.
 *
 * The detector can only establish its prospective evidence while the trace is falling.
 * A later PRE_HIGH, RISING_FAST, HIGH, or VERY_HIGH candidate therefore needs this small
 * bridge to know that it belongs to a recent suspicious fall. Process death deliberately
 * loses the bridge and fails open: an alarm is never delayed on reconstructed guesswork.
 */
internal class CompressionTrendEvidenceState {
    private data class Evidence(
        val sensorId: String?,
        val onsetMillis: Long,
        var lastSuspiciousReadingTimeMs: Long,
        var baselineMgdl: Float,
        var nadirMgdl: Float
    )

    private var evidence: Evidence? = null

    fun record(
        sensorId: String?,
        readingTimeMs: Long,
        suspect: CompressionLowDetector.OngoingSuspect
    ) {
        if (readingTimeMs <= 0L) return
        val running = evidence
        val knownSensorChanged = running?.sensorId != null && sensorId != null &&
            running.sensorId != sensorId
        if (running == null || knownSensorChanged || running.onsetMillis != suspect.onsetMillis) {
            evidence = Evidence(
                sensorId = sensorId,
                onsetMillis = suspect.onsetMillis,
                lastSuspiciousReadingTimeMs = readingTimeMs,
                baselineMgdl = suspect.baselineMgdl,
                nadirMgdl = suspect.currentMgdl
            )
            return
        }
        if (readingTimeMs < running.lastSuspiciousReadingTimeMs) return
        running.lastSuspiciousReadingTimeMs = readingTimeMs
        running.baselineMgdl = maxOf(running.baselineMgdl, suspect.baselineMgdl)
        running.nadirMgdl = minOf(running.nadirMgdl, suspect.currentMgdl)
    }

    fun qualifies(
        type: AlertType,
        sensorId: String?,
        readingTimeMs: Long,
        valueMgdl: Float,
        recoveryWindowMs: Long
    ): Boolean {
        val running = evidence ?: return false
        val knownSensorChanged = running.sensorId != null && sensorId != null &&
            running.sensorId != sensorId
        if (knownSensorChanged) {
            clear()
            return false
        }
        if (!valueMgdl.isFinite() || readingTimeMs < running.lastSuspiciousReadingTimeMs) {
            return false
        }
        if (readingTimeMs - running.lastSuspiciousReadingTimeMs > recoveryWindowMs) {
            clear()
            return false
        }
        return when (type) {
            AlertType.PRE_LOW,
            AlertType.FALLING_FAST -> valueMgdl <= running.baselineMgdl

            AlertType.PRE_HIGH,
            AlertType.RISING_FAST,
            AlertType.HIGH,
            AlertType.VERY_HIGH -> valueMgdl >= running.nadirMgdl + REBOUND_MGDL

            else -> false
        }
    }

    fun clear() {
        evidence = null
    }

    companion object {
        private const val REBOUND_MGDL = 3f
    }
}
