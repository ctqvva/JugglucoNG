package tk.glucodata

/** Measured change beside a current value. History must be loaded for [sensorId] only. */
object DisplayDeltaSource {
    const val FLOATING_ENABLED = "floating_show_delta"
    const val WEAR_ENABLED = "wear_show_delta"
    const val AOD_ENABLED = "aod_show_delta"
    const val WIDGET_ENABLED = "widget_show_delta"
    const val NOTIFICATION_ENABLED = "notification_show_delta"
    const val INTERVAL = "delta_interval_minutes"

    @JvmStatic
    fun resolve(enabled: Boolean, shown: CurrentDisplaySource.Snapshot?, intervalMinutes: Int): String =
        resolve(enabled, shown, intervalMinutes, System::currentTimeMillis, NotificationHistorySource::getDisplayHistory)

    internal fun resolve(
        enabled: Boolean,
        shown: CurrentDisplaySource.Snapshot?,
        intervalMinutes: Int,
        clock: () -> Long,
        loadMeasuredHistory: (Long, Boolean, String) -> List<GlucosePoint>
    ): String {
        if (!enabled || shown == null || shown.sensorId.isNullOrBlank()) return ""
        if (!isFresh(shown.timeMillis, clock(), Notify.glucosetimeout)) return ""
        val history = loadMeasuredHistory(shown.timeMillis - 21 * 60_000L, shown.isMmol, shown.sensorId)
        return format(true, history, shown.sensorId, shown.timeMillis, shown.viewMode,
            shown.isMmol, intervalMinutes, clock(), Notify.glucosetimeout)
    }

    /**
     * Uses one measured lane throughout, before smoothing, with its display calibration
     * evaluated at each sample timestamp. Never mix a smoothed live tail with stored history.
     * Prefer the exact measurement. Native history may floor a live timestamp to its
     * minute; only that unambiguous minute-aligned row may match tolerantly. A nearby
     * row from another minute must never supply a frozen/downsampled reading's delta.
     * Native history has no per-point identity; its caller-supplied sensor scope is required.
     */
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
        if (!enabled || sensorId.isNullOrBlank() || !isFresh(shownAt, now, maxAgeMillis)) return ""
        val useRaw = viewMode == 1 || viewMode == 3
        fun belongs(point: GlucosePoint): Boolean =
            point.sensorSerial == null || SensorIdentity.matches(point.sensorSerial, sensorId)
        fun value(point: GlucosePoint): Float = if (useRaw) point.rawValue else point.value
        fun valid(point: GlucosePoint): Boolean = value(point).isFinite() && value(point) > 0.1f
        val scoped = history.filter { belongs(it) }
        val exact = scoped.lastOrNull { it.timestamp == shownAt }
        val anchor = if (exact != null) {
            if (!valid(exact)) return ""
            exact
        } else {
            val sameMinute = scoped.filter { it.timestamp / 60_000L == shownAt / 60_000L }
            // Multiple arrivals in this minute cannot be identified by flooring alone.
            if (sameMinute.any { it.timestamp % 60_000L != 0L }) return ""
            CurrentDisplaySource.findExactPoint(sameMinute.filter { valid(it) }, shownAt)
        } ?: return ""
        if (!isFresh(anchor.timestamp, now, maxAgeMillis)) return ""
        val minGap = GlucoseDelta.minGapMillis(intervalMinutes)
        val previous = history.asSequence()
            .filter { it.timestamp > 0L && anchor.timestamp - it.timestamp >= minGap && belongs(it) && valid(it) }
            .maxByOrNull { it.timestamp } ?: return ""
        fun corrected(point: GlucosePoint): Float =
            CurrentDisplaySource.resolveDisplayValuesForPoint(point, viewMode, isMmol, sensorId).primaryValue
        return GlucoseDelta.format(
            GlucoseDelta.delta(anchor.timestamp, corrected(anchor), previous.timestamp, corrected(previous), intervalMinutes), isMmol
        )
    }

    private fun isFresh(shownAt: Long, now: Long, maxAgeMillis: Long): Boolean =
        shownAt > 0L && shownAt <= now && now - shownAt <= maxAgeMillis
}
