package tk.glucodata

/** Measured change beside a current value. History must be loaded for [sensorId] only. */
object DisplayDeltaSource {
    const val FLOATING_ENABLED = "floating_show_delta"
    const val WEAR_ENABLED = "wear_show_delta"
    const val AOD_ENABLED = "aod_show_delta"
    const val WIDGET_ENABLED = "widget_show_delta"
    const val NOTIFICATION_ENABLED = "notification_show_delta"
    const val INTERVAL = "delta_interval_minutes"

    internal data class Result(val text: String, val validUntilMillis: Long)

    @JvmStatic
    fun resolve(enabled: Boolean, shown: CurrentDisplaySource.Snapshot?, intervalMinutes: Int): String =
        if (!enabled || shown == null) "" else resolveResult(shown, intervalMinutes)?.text.orEmpty()

    internal fun resolveResult(
        shown: CurrentDisplaySource.Snapshot,
        intervalMinutes: Int,
        clock: () -> Long = System::currentTimeMillis,
        loadMeasuredHistory: (Long, Boolean, String) -> List<GlucosePoint> = NotificationHistorySource::getDisplayHistory
    ): Result? {
        val sensorId = shown.sensorId?.takeIf { it.isNotBlank() } ?: return null
        if (!isFresh(shown.timeMillis, clock(), Notify.glucosetimeout)) return null
        val history = loadMeasuredHistory(shown.timeMillis - 21 * 60_000L, shown.isMmol, sensorId)
        return formatResult(history, sensorId, shown.timeMillis, shown.viewMode,
            shown.isMmol, intervalMinutes, clock(), Notify.glucosetimeout)
    }

    @JvmStatic
    fun format(
        enabled: Boolean,
        history: List<GlucosePoint>,
        sensorId: String?,
        shownAt: Long,
        viewMode: Int,
        isMmol: Boolean,
        intervalMinutes: Int,
        now: Long,
        maxAgeMillis: Long
    ): String {
        if (!enabled || sensorId.isNullOrBlank()) return ""
        return formatResult(history, sensorId, shownAt, viewMode, isMmol, intervalMinutes, now, maxAgeMillis)
            ?.text.orEmpty()
    }

    /**
     * Uses one measured lane throughout, before smoothing, with its display calibration
     * evaluated at each sample timestamp. Never mix a smoothed live tail with stored history.
     * Prefer the exact measurement. Native history may floor a live timestamp to its
     * minute; only that unambiguous minute-aligned row may match tolerantly. A nearby
     * row from another minute must never supply a frozen/downsampled reading's delta.
     * Native history has no per-point identity; its caller-supplied sensor scope is required.
     */
    private fun formatResult(
        history: List<GlucosePoint>,
        sensorId: String,
        shownAt: Long,
        viewMode: Int,
        isMmol: Boolean,
        intervalMinutes: Int,
        now: Long,
        maxAgeMillis: Long
    ): Result? {
        if (!isFresh(shownAt, now, maxAgeMillis)) return null
        val useRaw = viewMode == 1 || viewMode == 3
        fun belongs(point: GlucosePoint): Boolean =
            point.sensorSerial == null || SensorIdentity.matches(point.sensorSerial, sensorId)
        fun value(point: GlucosePoint): Float = if (useRaw) point.rawValue else point.value
        fun valid(point: GlucosePoint): Boolean = value(point).isFinite() && value(point) > 0.1f
        val scoped = history.filter { belongs(it) }
        val exact = scoped.lastOrNull { it.timestamp == shownAt }
        val anchor = if (exact != null) {
            if (!valid(exact)) return null
            exact
        } else {
            val sameMinute = scoped.filter { it.timestamp / 60_000L == shownAt / 60_000L }
            // Multiple arrivals in this minute cannot be identified by flooring alone.
            if (sameMinute.any { it.timestamp % 60_000L != 0L }) return null
            CurrentDisplaySource.findExactPoint(sameMinute.filter { valid(it) }, shownAt)
        } ?: return null
        if (!isFresh(anchor.timestamp, now, maxAgeMillis)) return null
        val minGap = GlucoseDelta.minGapMillis(intervalMinutes)
        val previous = history.asSequence()
            .filter { it.timestamp > 0L && anchor.timestamp - it.timestamp >= minGap && belongs(it) && valid(it) }
            .maxByOrNull { it.timestamp } ?: return null
        fun corrected(point: GlucosePoint): Float =
            CurrentDisplaySource.resolveDisplayValuesForPoint(point, viewMode, isMmol, sensorId).primaryValue
        val text = GlucoseDelta.format(
            GlucoseDelta.delta(anchor.timestamp, corrected(anchor), previous.timestamp, corrected(previous), intervalMinutes), isMmol
        )
        return text.takeIf { it.isNotEmpty() }?.let { Result(it, anchor.timestamp + maxAgeMillis) }
    }

    private fun isFresh(shownAt: Long, now: Long, maxAgeMillis: Long): Boolean =
        shownAt > 0L && shownAt <= now && now - shownAt <= maxAgeMillis
}
