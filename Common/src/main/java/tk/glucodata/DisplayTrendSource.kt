package tk.glucodata

object DisplayTrendSource {
    /**
     * How much history the trend consumers keep on hand. This is a supply ceiling, not
     * the estimator's window: TrendEngine picks its own window from the sensor's cadence
     * and narrows this list further. It only has to be at least the estimator's longest
     * possible window (TrendEngine.MAX_WINDOW_MIN, 25 min) so a slow sensor is never
     * quietly served less history than it asked for. The two live in different source
     * sets, so they cannot share the constant — keep them in step by hand.
     */
    const val TREND_WINDOW_MS = 25L * 60L * 1000L

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

        val candidate = GlucosePoint(current.timeMillis, autoValue, rawValue)
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

    /**
     * The one point list every trend arrow regresses over: recent history plus
     * the live reading, cut to [TREND_WINDOW_MS] anchored at the newest point.
     *
     * Anchoring at the newest point instead of the wall clock matters: the
     * newest reading lags "now" by up to a full period, so a now-anchored load
     * window keeps or drops the reading sitting at the very edge of the trend
     * window depending on each consumer's own load timing. Two honest
     * consumers then regress over lists that differ by one tail point — a
     * nuance invisible everywhere except at the flat dead zone, where it flips
     * the glyph categorically for a full period.
     */
    @JvmStatic
    fun resolveTrendPoints(
        historyPoints: List<GlucosePoint>?,
        current: CurrentDisplaySource.Snapshot?,
        activeSensorSerial: String?
    ): List<GlucosePoint> {
        val augmented = augmentHistory(historyPoints, current, activeSensorSerial, 0L)
        val newestTimestamp = augmented.lastOrNull()?.timestamp ?: return augmented
        val cutoff = newestTimestamp - TREND_WINDOW_MS
        if (augmented.first().timestamp >= cutoff) {
            return augmented
        }
        return augmented.filter { it.timestamp >= cutoff }
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
        current?.let { snapshot ->
            VendorTrendRate.resolve(snapshot.sensorId, snapshot.timeMillis)?.let { return it }
        }
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
