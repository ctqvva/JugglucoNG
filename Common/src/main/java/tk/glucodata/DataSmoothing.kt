package tk.glucodata

import android.content.Context
import tk.glucodata.settings.SettingsRegistry

object DataSmoothing {
    private const val PREFS_NAME = SettingsRegistry.FILE
    private const val LAST_ENABLED_MINUTES_KEY = "dashboard_data_smoothing_last_enabled_minutes"
    private const val MAX_CHUNK_INTERVAL_MINUTES = 5
    private const val DEFAULT_ENABLED_MINUTES = MAX_CHUNK_INTERVAL_MINUTES

    private val allowedMinutes = intArrayOf(0, 2, 3, 4, 5, 7, 10, 13)
    private val enabledMinutes = intArrayOf(2, 3, 4, 5, 7, 10, 13)

    @JvmStatic
    fun allowedMinutes(): IntArray = allowedMinutes.copyOf()

    @JvmStatic
    fun enabledMinutesOptions(): IntArray = enabledMinutes.copyOf()

    @JvmStatic
    fun sanitizeMinutes(minutes: Int): Int {
        return if (allowedMinutes.contains(minutes)) minutes else 0
    }

    @JvmStatic
    fun getMinutes(context: Context): Int {
        return sanitizeMinutes(SettingsRegistry.SMOOTHING_MINUTES.readInt(context))
    }

    @JvmStatic
    fun getLastEnabledMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // The same key, but a different question: the fallback here is the last
        // window that was on, not the setting's own default of 0.
        val current = sanitizeMinutes(
            prefs.getInt(SettingsRegistry.SMOOTHING_MINUTES.key, DEFAULT_ENABLED_MINUTES)
        )
        val fallback = current.takeIf { it > 0 } ?: DEFAULT_ENABLED_MINUTES
        return sanitizeMinutes(prefs.getInt(LAST_ENABLED_MINUTES_KEY, fallback))
            .takeIf { it > 0 }
            ?: DEFAULT_ENABLED_MINUTES
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean = getMinutes(context) > 0

    @JvmStatic
    fun isGraphOnly(context: Context): Boolean =
        SettingsRegistry.SMOOTHING_GRAPH_ONLY.readBool(context)

    @JvmStatic
    fun collapseChunks(context: Context): Boolean =
        SettingsRegistry.SMOOTHING_COLLAPSE_CHUNKS.readBool(context)

    @JvmStatic
    fun smoothOnlyExchangeOutputs(context: Context): Boolean =
        SettingsRegistry.SMOOTHING_EXCHANGE_OUTPUTS_ONLY.readBool(context)

    /**
     * Whether the newest reading — the one shown as current and the one sent out — is estimated
     * by [CausalGlucoseFilter] instead of the edge of the averaging window. Only the method
     * changes: where smoothing applies is still decided by the three switches above.
     */
    @JvmStatic
    fun useCausalFilter(context: Context): Boolean =
        SettingsRegistry.SMOOTHING_CAUSAL_LIVE.readBool(context)

    /**
     * The three places a reading is used, and the one question each of them asks.
     *
     * The settings are three independent switches, so every caller used to recombine them
     * for itself and they drifted: the same reading could be smoothed on its way to the
     * notification and measured on its way to the row beside it. Ask here instead. Each
     * destination has a `...SmoothingMinutes` accessor returning 0 when that destination
     * takes the reading as measured, so "how much smoothing" and "any at all" are one
     * question with one answer.
     *
     * Not fully independent, though: "collapse into chunks" is defined as keeping one
     * *smoothed* reading per interval, so enabling it for exchange pulls exchange smoothing
     * back on even under "graph only" — there is no smoothed reading to collapse to
     * otherwise. See [shouldSmoothExchangeOutputs]'s `collapseChunks` parameter.
     */
    @JvmStatic
    fun shouldSmoothGraph(context: Context): Boolean = graphSmoothingMinutes(context) > 0

    @JvmStatic
    fun graphSmoothingMinutes(context: Context): Int = graphSmoothingMinutes(
        smoothingMinutes = getMinutes(context),
        exchangeOutputsOnly = smoothOnlyExchangeOutputs(context)
    )

    /**
     * The window the app's own reading carries: the trend arrow, the Δ readout, the delta
     * alarms and every threshold evaluated against a value. Smoothing here is deliberate —
     * it is what keeps a single wild sample from deciding an arrow — and "smooth only
     * graph" is the way to opt out of it.
     */
    @JvmStatic
    fun localSmoothingMinutes(context: Context): Int = localSmoothingMinutes(
        smoothingMinutes = getMinutes(context),
        graphOnly = isGraphOnly(context),
        exchangeOutputsOnly = smoothOnlyExchangeOutputs(context)
    )

    @JvmStatic
    fun shouldSmoothLocalData(context: Context): Boolean = localSmoothingMinutes(context) > 0

    @JvmStatic
    fun shouldSmoothExchangeOutputs(context: Context): Boolean {
        return shouldSmoothExchangeOutputs(
            smoothingMinutes = getMinutes(context),
            graphOnly = isGraphOnly(context),
            exchangeOutputsOnly = smoothOnlyExchangeOutputs(context),
            collapseChunks = collapseChunks(context)
        )
    }

    /**
     * Averaging window, in minutes, that every outgoing reading must carry — 0 when
     * exchange outputs are configured to stay unsmoothed. Java-side outputs get this
     * applied for them by [CurrentDisplaySource.resolveCurrentForExchange]; the native
     * Nightscout uploader builds its entries straight from stored polls and has to ask
     * for the window itself (see NightscoutCalibration.getExchangeSmoothingSeconds).
     */
    @JvmStatic
    fun exchangeSmoothingMinutes(context: Context): Int {
        return exchangeSmoothingMinutes(
            smoothingMinutes = getMinutes(context),
            graphOnly = isGraphOnly(context),
            exchangeOutputsOnly = smoothOnlyExchangeOutputs(context),
            collapseChunks = collapseChunks(context)
        )
    }

    @JvmStatic
    fun shouldCollapseExchangeOutputs(context: Context): Boolean {
        return shouldCollapseExchangeOutputs(
            smoothingMinutes = getMinutes(context),
            graphOnly = isGraphOnly(context),
            exchangeOutputsOnly = smoothOnlyExchangeOutputs(context),
            collapseChunks = collapseChunks(context)
        )
    }

    /**
     * How often, in minutes, an exchange output is fed: 0 means every reading.
     *
     * "Collapse into chunks" thins the *rate* of what leaves the phone; it never changes what is
     * sent. Whatever is sent is the newest reading under its own timestamp — the payload is not
     * built from the last point of a completed chunk, which would be up to two intervals old.
     * This applies uniformly to every exchange destination, including the xDrip/xInfuus loop feed
     * AAPS doses from: it used to be exempt from collapse because the old design broke the "own
     * timestamp" guarantee for it, not because thinning it was unsafe on its own.
     */
    @JvmStatic
    fun exchangeThrottleIntervalMinutes(context: Context): Int =
        exchangeThrottleIntervalMinutes(
            smoothingMinutes = getMinutes(context),
            graphOnly = isGraphOnly(context),
            exchangeOutputsOnly = smoothOnlyExchangeOutputs(context),
            collapseChunks = collapseChunks(context)
        )

    @JvmStatic
    fun setMinutes(context: Context, minutes: Int) {
        val sanitized = sanitizeMinutes(minutes)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(SettingsRegistry.SMOOTHING_MINUTES.key, sanitized)
            .apply()
        if (sanitized > 0) {
            setLastEnabledMinutes(context, sanitized)
        }
    }

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        setMinutes(
            context,
            if (enabled) getLastEnabledMinutes(context) else 0
        )
    }

    @JvmStatic
    fun setGraphOnly(context: Context, graphOnly: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SettingsRegistry.SMOOTHING_GRAPH_ONLY.key, graphOnly)
            .apply()
    }

    @JvmStatic
    fun setCollapseChunks(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SettingsRegistry.SMOOTHING_COLLAPSE_CHUNKS.key, enabled)
            .apply()
    }

    @JvmStatic
    fun setSmoothOnlyExchangeOutputs(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SettingsRegistry.SMOOTHING_EXCHANGE_OUTPUTS_ONLY.key, enabled)
            .apply()
    }

    @JvmStatic
    fun setUseCausalFilter(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SettingsRegistry.SMOOTHING_CAUSAL_LIVE.key, enabled)
            .apply()
    }

    @JvmStatic
    fun collapseIntervalMinutes(smoothingMinutes: Int): Int {
        val sanitized = sanitizeMinutes(smoothingMinutes)
        if (sanitized <= 0) {
            return 0
        }
        return minOf(sanitized, MAX_CHUNK_INTERVAL_MINUTES)
    }

    @JvmStatic
    @JvmOverloads
    fun smoothNativePoints(
        points: List<GlucosePoint>?,
        smoothingMinutes: Int,
        collapseChunks: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): List<GlucosePoint> {
        if (points.isNullOrEmpty()) {
            return emptyList()
        }
        val sanitizedMinutes = sanitizeMinutes(smoothingMinutes)
        if (sanitizedMinutes <= 0) {
            return points
        }
        if (points.size < 3) {
            return if (collapseChunks) {
                collapsePointsForDisplay(points, collapseIntervalMinutes(sanitizedMinutes), nowMillis)
            } else {
                points
            }
        }

        val halfWindowMs = (sanitizedMinutes * 60_000L) / 2L
        if (halfWindowMs <= 0L) {
            return points
        }

        val smoothedAuto = smoothSeries(points, halfWindowMs, useRawValue = false)
        val smoothedRaw = smoothSeries(points, halfWindowMs, useRawValue = true)
        val smoothed = ArrayList<GlucosePoint>(points.size)
        points.indices.forEach { index ->
            val source = points[index]
            val point = GlucosePoint(source.timestamp, smoothedAuto[index], smoothedRaw[index])
            point.color = source.color
            point.sensorSerial = source.sensorSerial
            point.sealedDisplayValue = source.sealedDisplayValue
            point.sealedDisplayViewMode = source.sealedDisplayViewMode
            point.uncertainty = source.uncertainty?.shifted(point.value - source.value)
            smoothed.add(point)
        }

        return if (collapseChunks) {
            collapsePointsForDisplay(smoothed, collapseIntervalMinutes(sanitizedMinutes), nowMillis)
        } else {
            smoothed
        }
    }

    /**
     * Whether two display points belong to the same sensor. A point without a serial is the merged
     * live sample of the selected sensor; aliases of one sensor match through
     * [SensorIdentity.matches], while imported readings do not.
     */
    internal fun sameCausalSource(a: GlucosePoint, b: GlucosePoint): Boolean =
        a.sensorSerial.isNullOrBlank() ||
            b.sensorSerial.isNullOrBlank() ||
            SensorIdentity.matches(a.sensorSerial, b.sensorSerial)

    private fun smoothSeries(
        points: List<GlucosePoint>,
        halfWindowMs: Long,
        useRawValue: Boolean
    ): FloatArray = GlucoseSmoothing.smoothLane(
        points = points,
        halfWindowMs = halfWindowMs,
        timestamp = { it.timestamp },
        selector = { if (useRawValue) it.rawValue else it.value },
    )

    /**
     * [smoothNativePoints] with [CausalGlucoseFilter] in place of the averaging window: every
     * point is estimated from the past only, so the newest one is not an edge fit and will not be
     * redrawn differently once later readings arrive. [smoothingMinutes] still gates it (0 = off)
     * and still sets the collapse interval.
     */
    @JvmStatic
    @JvmOverloads
    fun smoothNativePointsCausal(
        points: List<GlucosePoint>?,
        smoothingMinutes: Int,
        collapseChunks: Boolean,
        mgdlPerUnit: Float,
        nowMillis: Long = System.currentTimeMillis()
    ): List<GlucosePoint> {
        if (points.isNullOrEmpty()) return emptyList()
        val sanitizedMinutes = sanitizeMinutes(smoothingMinutes)
        if (sanitizedMinutes <= 0) return points

        val autoLane = CausalGlucoseFilter.filterLane(
            points, { it.timestamp }, { it.value }, mgdlPerUnit, sameSource = ::sameCausalSource
        )
        val rawLane = CausalGlucoseFilter.filterLane(
            points, { it.timestamp }, { it.rawValue }, mgdlPerUnit, sameSource = ::sameCausalSource
        )
        val filtered = ArrayList<GlucosePoint>(points.size)
        points.indices.forEach { index ->
            val source = points[index]
            val point = GlucosePoint(source.timestamp, autoLane[index], rawLane[index])
            point.color = source.color
            point.sensorSerial = source.sensorSerial
            point.sealedDisplayValue = source.sealedDisplayValue
            point.sealedDisplayViewMode = source.sealedDisplayViewMode
            point.uncertainty = source.uncertainty?.shifted(autoLane[index] - source.value)
            filtered.add(point)
        }
        return if (collapseChunks) {
            collapsePointsForDisplay(filtered, collapseIntervalMinutes(sanitizedMinutes), nowMillis)
        } else {
            filtered
        }
    }
    private fun isValidLane(value: Float): Boolean = value.isFinite() && value >= 0.1f

    internal fun collapsePointsForDisplay(
        points: List<GlucosePoint>,
        smoothingMinutes: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): List<GlucosePoint> {
        if (points.isEmpty() || smoothingMinutes <= 0) {
            return points
        }

        val bucketDurationMs = smoothingMinutes * 60_000L
        val openBucket = nowMillis / bucketDurationMs
        val collapsed = ArrayList<GlucosePoint>()
        var activeBucket = Long.MIN_VALUE
        var pending: GlucosePoint? = null
        var bucketAuto: GlucosePoint? = null
        var bucketRaw: GlucosePoint? = null

        // The bucket's last point, and for a lane it lacks the bucket's newest
        // point that has it, at its own time: Libre's History lane is one value
        // per 15 minutes, and moving it onto the last point could stretch two
        // neighbouring values past the chart's gap limit.
        fun flush() {
            val last = pending ?: return
            val autoHolder = bucketAuto?.takeIf { !isValidLane(last.value) }
            val rawHolder = bucketRaw?.takeIf { !isValidLane(last.rawValue) }
            listOfNotNull(autoHolder, rawHolder).distinct().sortedBy { it.timestamp }.forEach(collapsed::add)
            collapsed.add(last)
        }

        for (point in points) {
            val bucket = point.timestamp / bucketDurationMs
            if (bucket != activeBucket) {
                if (activeBucket < openBucket) flush()
                activeBucket = bucket
                bucketAuto = null
                bucketRaw = null
            }
            if (isValidLane(point.value)) bucketAuto = point
            if (isValidLane(point.rawValue)) bucketRaw = point
            pending = point
        }

        if (activeBucket < openBucket) flush()
        return when {
            collapsed.isNotEmpty() -> collapsed
            points.isNotEmpty() -> listOf(points.last())
            else -> points
        }
    }

    /** Presentation. Untouched by "smooth only graph": that switch is about everything else. */
    internal fun graphSmoothingMinutes(smoothingMinutes: Int, exchangeOutputsOnly: Boolean): Int {
        val sanitized = sanitizeMinutes(smoothingMinutes)
        return if (sanitized > 0 && !exchangeOutputsOnly) sanitized else 0
    }

    /** Evaluation: off when either "only the graph" or "only what leaves the phone" is set. */
    internal fun localSmoothingMinutes(
        smoothingMinutes: Int,
        graphOnly: Boolean,
        exchangeOutputsOnly: Boolean
    ): Int {
        val sanitized = sanitizeMinutes(smoothingMinutes)
        return if (sanitized > 0 && !graphOnly && !exchangeOutputsOnly) sanitized else 0
    }

    /**
     * "Collapse" keeps one *smoothed* reading per interval (see its own description) — it
     * has nothing meaningful to keep if exchange stayed unsmoothed, so turning it on pulls
     * exchange smoothing back in even under "graph only". Default false keeps every
     * existing three-switch call site (and its tests) unaffected.
     */
    internal fun shouldSmoothExchangeOutputs(
        smoothingMinutes: Int,
        graphOnly: Boolean,
        exchangeOutputsOnly: Boolean,
        collapseChunks: Boolean = false
    ): Boolean {
        return sanitizeMinutes(smoothingMinutes) > 0 && (exchangeOutputsOnly || !graphOnly || collapseChunks)
    }

    internal fun exchangeSmoothingMinutes(
        smoothingMinutes: Int,
        graphOnly: Boolean,
        exchangeOutputsOnly: Boolean,
        collapseChunks: Boolean = false
    ): Int {
        val sanitized = sanitizeMinutes(smoothingMinutes)
        return if (shouldSmoothExchangeOutputs(sanitized, graphOnly, exchangeOutputsOnly, collapseChunks)) sanitized else 0
    }

    internal fun shouldCollapseExchangeOutputs(
        smoothingMinutes: Int,
        graphOnly: Boolean,
        exchangeOutputsOnly: Boolean,
        collapseChunks: Boolean
    ): Boolean {
        return collapseChunks && shouldSmoothExchangeOutputs(
            smoothingMinutes = smoothingMinutes,
            graphOnly = graphOnly,
            exchangeOutputsOnly = exchangeOutputsOnly,
            collapseChunks = collapseChunks
        )
    }

    internal fun exchangeThrottleIntervalMinutes(
        smoothingMinutes: Int,
        graphOnly: Boolean,
        exchangeOutputsOnly: Boolean,
        collapseChunks: Boolean
    ): Int = if (shouldCollapseExchangeOutputs(smoothingMinutes, graphOnly, exchangeOutputsOnly, collapseChunks)) {
        collapseIntervalMinutes(smoothingMinutes)
    } else {
        0
    }

    private fun setLastEnabledMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(LAST_ENABLED_MINUTES_KEY, minutes)
            .apply()
    }
}
