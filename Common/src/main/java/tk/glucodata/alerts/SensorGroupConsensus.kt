package tk.glucodata.alerts

/** All selected contributors must be comparable; no silent majority/outlier selection. */
internal class SensorGroupConsensus {
    private val pairs = mutableMapOf<List<String>, DualSensorConsensus>()
    private var identities = emptyList<String>()
    private var observation: List<DualSensorConsensus.Reading>? = null

    fun reset() { pairs.clear(); identities = emptyList(); observation = null }

    fun evaluate(readings: List<DualSensorConsensus.Reading>, nowMs: Long, percent: Int, duration: Int): DualSensorConsensus.Result {
        val sorted = readings.sortedBy { it.sensorId }
        val ids = sorted.map { it.sensorId }
        if (sorted.size < 2 || ids.distinct().size != ids.size || sorted.any {
            !it.mgdl.isFinite() || it.mgdl !in 20f..600f || it.timeMs <= 0L ||
                it.timeMs > nowMs || nowMs - it.timeMs > DualSensorConsensus.MAX_AGE_MS
        }) {
            reset()
            return DualSensorConsensus.Result()
        }
        if (identities != ids) { reset(); identities = ids }
        if (sorted.size > 2) {
            val previous = observation
            if (previous != null && (nowMs - previous.minOf { it.timeMs } > DualSensorConsensus.MAX_AGE_MS ||
                    sorted.indices.any { i ->
                        val current = sorted[i]
                        val old = previous[i]
                        current.lane != old.lane || current.timeMs < old.timeMs ||
                            (current.timeMs == old.timeMs && current.mgdl != old.mgdl)
                    })) {
                reset()
                identities = ids
            }
            val accepted = observation
            // Commit all pairs from the same complete group. Advancing individual
            // pair engines on partial callbacks gives each pair a different baseline
            // and can leave mixed one/five-minute sensors waiting permanently.
            if (accepted != null && sorted.indices.any { sorted[it].timeMs > accepted[it].timeMs } &&
                sorted.indices.any { sorted[it].timeMs == accepted[it].timeMs }) {
                return DualSensorConsensus.Result(status = DualSensorConsensus.Status.WAITING_FOR_PEER)
            }
        }
        val assessments = sorted.indices.flatMap { i ->
            (i + 1 until sorted.size).map { j ->
                val key = listOf(ids[i], ids[j])
                pairs.getOrPut(key) { DualSensorConsensus() }.evaluate(listOf(sorted[i], sorted[j]), nowMs, percent, duration)
            }
        }
        if (assessments.size == 1) return assessments.single()
        if (assessments.any { it.status == DualSensorConsensus.Status.UNAVAILABLE }) {
            reset()
            return DualSensorConsensus.Result()
        }
        if (assessments.any { it.status == DualSensorConsensus.Status.WAITING_FOR_PEER })
            return DualSensorConsensus.Result(status = DualSensorConsensus.Status.WAITING_FOR_PEER)
        observation = sorted
        val worst = assessments.maxBy { it.differencePercent ?: 0f }
        val estimates = assessments.mapNotNull { it.estimateMgdl }
        // Each input appears in N-1 pairs. Averaging the bounded pair estimates
        // keeps contributions between 0.5/N and 1.5/N without crowning a "correct" sensor.
        return worst.copy(
            status = when {
                assessments.any { it.status == DualSensorConsensus.Status.DISAGREEING } -> DualSensorConsensus.Status.DISAGREEING
                estimates.size == assessments.size -> DualSensorConsensus.Status.COMPARABLE
                else -> DualSensorConsensus.Status.LEARNING
            },
            estimateMgdl = if (estimates.size == assessments.size) estimates.average().toFloat() else null,
            warningDue = assessments.any { it.warningDue }
        )
    }
}
