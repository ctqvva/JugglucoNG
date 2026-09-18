package tk.glucodata.alerts

import tk.glucodata.logic.CompressionLowDetector

/** Keeps the shallow falling evidence separate from conservative rebound evidence. */
internal class CompressionTrendEvidence {
    private val early = CompressionTrendEvidenceState()
    private val full = CompressionTrendEvidenceState()

    fun observe(
        samples: List<CompressionLowDetector.Sample>,
        nowMs: Long,
        readingTimeMs: Long,
        sensorId: String?,
        isfMgdlPerUnit: Float,
        iobUnits: Float,
        dosePeakPassed: Boolean,
        tuning: CompressionLowDetector.Tuning
    ) {
        CompressionLowDetector.assessEarlyWarningSuspicion(
            samples, nowMs, isfMgdlPerUnit, iobUnits, dosePeakPassed, tuning
        )?.let { early.record(sensorId, readingTimeMs, it) }
        CompressionLowDetector.assessOngoing(
            samples, nowMs, isfMgdlPerUnit, iobUnits, dosePeakPassed, tuning
        )?.let { full.record(sensorId, readingTimeMs, it) }
    }

    fun recordLow(sensorId: String?, readingTimeMs: Long, suspect: CompressionLowDetector.OngoingSuspect) {
        full.record(sensorId, readingTimeMs, suspect)
        early.record(sensorId, readingTimeMs, suspect)
    }

    fun qualifies(type: AlertType, sensorId: String?, readingTimeMs: Long, valueMgdl: Float,
                  recoveryWindowMs: Long): Boolean =
        (if (type == AlertType.PRE_LOW || type == AlertType.FALLING_FAST) early else full)
            .qualifies(type, sensorId, readingTimeMs, valueMgdl, recoveryWindowMs)

    fun clear() {
        early.clear()
        full.clear()
    }
}
