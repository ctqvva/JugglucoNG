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
        val history = loadMeasuredHistory(shown.timeMillis - 20 * 60_000L, shown.isMmol, shown.sensorId)
        return format(true, history, shown.sensorId, shown.timeMillis, shown.viewMode,
            shown.isMmol, intervalMinutes, clock(), Notify.glucosetimeout)
    }

    /**
     * Uses one measured lane throughout, before smoothing, with its display calibration
     * evaluated at each sample timestamp. Never mix a smoothed live tail with stored history.
     * An exact anchor prevents a newer stored reading (or an older history tail) from
     * supplying the delta beside a different, frozen/downsampled current value.
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
        val anchor = history.lastOrNull { it.timestamp == shownAt && belongs(it) && valid(it) } ?: return ""
        val minGap = GlucoseDelta.minGapMillis(intervalMinutes)
        val previous = history.asSequence()
            .filter { it.timestamp > 0L && shownAt - it.timestamp >= minGap && belongs(it) && valid(it) }
            .maxByOrNull { it.timestamp } ?: return ""
        fun corrected(point: GlucosePoint): Float =
            CurrentDisplaySource.resolveDisplayValuesForPoint(point, viewMode, isMmol, sensorId).primaryValue
        return GlucoseDelta.format(
            GlucoseDelta.delta(shownAt, corrected(anchor), previous.timestamp, corrected(previous), intervalMinutes), isMmol
        )
    }

    private fun isFresh(shownAt: Long, now: Long, maxAgeMillis: Long): Boolean =
        shownAt > 0L && shownAt <= now && now - shownAt <= maxAgeMillis
}
