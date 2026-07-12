package tk.glucodata

object DisplayTrendSource {
    const val TREND_WINDOW_MS = 20L * 60L * 1000L

    @JvmStatic
    fun augmentHistory(
        historyPoints: List<GlucosePoint>?,
        current: CurrentDisplaySource.Snapshot?,
        activeSensorSerial: String?,
        startTimeMs: Long
    ): List<GlucosePoint> {
        val history = historyPoints ?: emptyList()
        if (current == null || current.timeMillis < startTimeMs) {
            return history
        }
        if (activeSensorSerial != null && current.sensorId != null &&
            !SensorIdentity.matches(current.sensorId, activeSensorSerial)
        ) {
            return history
        }

        var autoValue = current.autoValue
        var rawValue = current.rawValue
        if (!autoValue.isFinite() || autoValue <= 0.1f) {
            autoValue = 0f
        }
        if (!rawValue.isFinite() || rawValue <= 0.1f) {
            rawValue = 0f
        }
        if (autoValue <= 0f && rawValue <= 0f) {
            return history
        }

        val candidate = GlucosePoint(current.timeMillis, autoValue, rawValue, current.sensorId)
        if (history.isEmpty()) {
            return listOf(candidate)
        }

        val merged = ArrayList<GlucosePoint>(history.size + 1)
        var inserted = false
        history.forEach { point ->
            if (!inserted && candidate.timestamp <= point.timestamp) {
                if (candidate.timestamp == point.timestamp) {
                    merged.add(if (pointScore(candidate) >= pointScore(point)) candidate else point)
                    inserted = true
                    return@forEach
                }
                merged.add(candidate)
                inserted = true
            }
            merged.add(point)
        }
        if (!inserted) {
            merged.add(candidate)
        }
        return merged
    }

    @JvmStatic
    @JvmOverloads
    fun resolveArrowRate(
        recentPoints: List<GlucosePoint>?,
        current: CurrentDisplaySource.Snapshot?,
        viewMode: Int,
        isMmol: Boolean,
        fallbackRate: Float = 0f
    ): Float {
        val points = recentPoints ?: emptyList()
        val useRaw = viewMode == 1 || viewMode == 3
        if (hasUsableTrendHistory(points, useRaw)) {
            val historyRate = runCatching {
                TrendAccess.calculateVelocity(points, useRaw = useRaw, isMmol = isMmol)
            }.getOrNull()
            if (historyRate != null && historyRate.isFinite()) {
                return historyRate
            }
        }
        return current?.rate?.takeIf { it.isFinite() } ?: fallbackRate
    }

    private fun hasUsableTrendHistory(points: List<GlucosePoint>, useRaw: Boolean): Boolean {
        var usablePoints = 0
        var previousTimestamp = Long.MIN_VALUE
        points.asReversed().forEach { point ->
            val value = if (useRaw) point.rawValue else point.value
            if (!value.isFinite() || value <= 0.1f) {
                return@forEach
            }
            if (usablePoints > 0 && point.timestamp != previousTimestamp) {
                return true
            }
            usablePoints++
            previousTimestamp = point.timestamp
        }
        return false
    }

    private fun pointScore(point: GlucosePoint): Int {
        var score = 0
        if (point.value > 0f) {
            score += 1
        }
        if (point.rawValue > 0f) {
            score += 2
        }
        return score
    }
}
