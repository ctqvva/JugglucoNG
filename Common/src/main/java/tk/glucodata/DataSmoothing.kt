package tk.glucodata

import android.content.Context

object DataSmoothing {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val MINUTES_KEY = "dashboard_chart_smoothing_minutes"
    private const val LAST_ENABLED_MINUTES_KEY = "dashboard_data_smoothing_last_enabled_minutes"
    private const val GRAPH_ONLY_KEY = "dashboard_data_smoothing_graph_only"
    private const val COLLAPSE_CHUNKS_KEY = "dashboard_data_smoothing_collapse_chunks"
    private const val EXCHANGE_OUTPUTS_ONLY_KEY = "dashboard_data_smoothing_exchange_outputs_only"
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sanitizeMinutes(prefs.getInt(MINUTES_KEY, 0))
    }

    @JvmStatic
    fun getLastEnabledMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = sanitizeMinutes(prefs.getInt(MINUTES_KEY, DEFAULT_ENABLED_MINUTES))
        val fallback = current.takeIf { it > 0 } ?: DEFAULT_ENABLED_MINUTES
        return sanitizeMinutes(prefs.getInt(LAST_ENABLED_MINUTES_KEY, fallback))
            .takeIf { it > 0 }
            ?: DEFAULT_ENABLED_MINUTES
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean = getMinutes(context) > 0

    @JvmStatic
    fun isGraphOnly(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(GRAPH_ONLY_KEY, false)
    }

    @JvmStatic
    fun collapseChunks(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(COLLAPSE_CHUNKS_KEY, false)
    }

    @JvmStatic
    fun smoothOnlyExchangeOutputs(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(EXCHANGE_OUTPUTS_ONLY_KEY, false)
    }

    @JvmStatic
    fun shouldSmoothGraph(context: Context): Boolean {
        return getMinutes(context) > 0 && !smoothOnlyExchangeOutputs(context)
    }

    @JvmStatic
    fun graphSmoothingMinutes(context: Context): Int {
        return if (shouldSmoothGraph(context)) getMinutes(context) else 0
    }

    @JvmStatic
    fun shouldSmoothLocalData(context: Context): Boolean {
        return getMinutes(context) > 0 &&
            !isGraphOnly(context) &&
            !smoothOnlyExchangeOutputs(context)
    }

    @JvmStatic
    fun shouldSmoothExchangeOutputs(context: Context): Boolean {
        return shouldSmoothExchangeOutputs(
            smoothingMinutes = getMinutes(context),
            graphOnly = isGraphOnly(context),
            exchangeOutputsOnly = smoothOnlyExchangeOutputs(context)
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
            exchangeOutputsOnly = smoothOnlyExchangeOutputs(context)
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

    @JvmStatic
    fun setMinutes(context: Context, minutes: Int) {
        val sanitized = sanitizeMinutes(minutes)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(MINUTES_KEY, sanitized)
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
            .putBoolean(GRAPH_ONLY_KEY, graphOnly)
            .apply()
    }

    @JvmStatic
    fun setCollapseChunks(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(COLLAPSE_CHUNKS_KEY, enabled)
            .apply()
    }

    @JvmStatic
    fun setSmoothOnlyExchangeOutputs(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(EXCHANGE_OUTPUTS_ONLY_KEY, enabled)
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
    fun smoothNativePoints(
        points: List<GlucosePoint>?,
        smoothingMinutes: Int,
        collapseChunks: Boolean
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
                collapsePointsForDisplay(points, collapseIntervalMinutes(sanitizedMinutes))
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
            smoothed.add(point)
        }

        return if (collapseChunks) {
            collapsePointsForDisplay(smoothed, collapseIntervalMinutes(sanitizedMinutes))
        } else {
            smoothed
        }
    }

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

        for (point in points) {
            val bucket = point.timestamp / bucketDurationMs
            if (bucket != activeBucket) {
                if (activeBucket < openBucket) {
                    pending?.let(collapsed::add)
                }
                activeBucket = bucket
            }
            pending = point
        }

        if (activeBucket < openBucket) {
            pending?.let(collapsed::add)
        }
        return when {
            collapsed.isNotEmpty() -> collapsed
            points.isNotEmpty() -> listOf(points.last())
            else -> points
        }
    }

    internal fun shouldSmoothExchangeOutputs(
        smoothingMinutes: Int,
        graphOnly: Boolean,
        exchangeOutputsOnly: Boolean
    ): Boolean {
        return sanitizeMinutes(smoothingMinutes) > 0 && (exchangeOutputsOnly || !graphOnly)
    }

    internal fun exchangeSmoothingMinutes(
        smoothingMinutes: Int,
        graphOnly: Boolean,
        exchangeOutputsOnly: Boolean
    ): Int {
        val sanitized = sanitizeMinutes(smoothingMinutes)
        return if (shouldSmoothExchangeOutputs(sanitized, graphOnly, exchangeOutputsOnly)) sanitized else 0
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
            exchangeOutputsOnly = exchangeOutputsOnly
        )
    }

    private fun setLastEnabledMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(LAST_ENABLED_MINUTES_KEY, minutes)
            .apply()
    }
}
