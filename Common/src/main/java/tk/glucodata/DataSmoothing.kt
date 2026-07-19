package tk.glucodata

import android.content.Context
import kotlin.math.roundToInt

object DataSmoothing {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val LEGACY_MINUTES_KEY = "dashboard_chart_smoothing_minutes"
    private const val LEGACY_GRAPH_ONLY_KEY = "dashboard_data_smoothing_graph_only"
    private const val LEGACY_EXCHANGE_ONLY_KEY = "dashboard_data_smoothing_exchange_outputs_only"
    private const val GRAPH_LEVEL_KEY = "dashboard_graph_smoothing_level"
    private const val EXCHANGE_MINUTES_KEY = "dashboard_exchange_smoothing_minutes"
    private const val COLLAPSE_CHUNKS_KEY = "dashboard_data_smoothing_collapse_chunks"
    private const val SPLIT_MIGRATED_KEY = "dashboard_split_smoothing_migrated"
    private const val MAX_CHUNK_INTERVAL_MINUTES = 5

    private val graphLevels = intArrayOf(0, 1, 2, 3)
    private val graphWindowMinutes = intArrayOf(1, 2, 3, 4, 5, 7, 10, 13)
    private val exchangeMinutes = intArrayOf(0, 2, 3, 4, 5, 7, 10, 13)

    @JvmStatic
    fun graphLevelOptions(): IntArray = graphLevels.copyOf()

    @JvmStatic
    fun exchangeMinutesOptions(): IntArray = exchangeMinutes.copyOf()

    @JvmStatic
    fun sanitizeGraphLevel(level: Int): Int = level.coerceIn(graphLevels.first(), graphLevels.last())

    @JvmStatic
    fun sanitizeExchangeMinutes(minutes: Int): Int {
        return if (exchangeMinutes.contains(minutes)) minutes else 0
    }

    @JvmStatic
    fun graphSmoothingLevel(context: Context): Int {
        migrateSplitSettings(context)
        return sanitizeGraphLevel(context.preferences().getInt(GRAPH_LEVEL_KEY, 0))
    }

    @JvmStatic
    fun exchangeSmoothingMinutes(context: Context): Int {
        migrateSplitSettings(context)
        return sanitizeExchangeMinutes(context.preferences().getInt(EXCHANGE_MINUTES_KEY, 0))
    }

    @JvmStatic
    fun setGraphSmoothingLevel(context: Context, level: Int) {
        migrateSplitSettings(context)
        context.preferences().edit().putInt(GRAPH_LEVEL_KEY, sanitizeGraphLevel(level)).apply()
    }

    @JvmStatic
    fun setExchangeSmoothingMinutes(context: Context, minutes: Int) {
        migrateSplitSettings(context)
        context.preferences().edit().putInt(EXCHANGE_MINUTES_KEY, sanitizeExchangeMinutes(minutes)).apply()
    }

    @JvmStatic
    fun graphSmoothingMinutes(context: Context, visibleDurationMillis: Long): Int {
        return graphWindowMinutes(graphSmoothingLevel(context), visibleDurationMillis)
    }

    @JvmStatic
    fun graphSmoothingMinutes(context: Context): Int {
        return graphSmoothingMinutes(context, 3L * 60L * 60L * 1000L)
    }

    @JvmStatic
    fun graphWindowMinutes(level: Int, visibleDurationMillis: Long): Int {
        val safeLevel = sanitizeGraphLevel(level)
        if (safeLevel == 0 || visibleDurationMillis <= 0L) return 0

        // Roughly one smoothing minute per 2.4 visible hours. The user-selected
        // level changes intensity while zooming remains the dominant input.
        val visibleMinutes = visibleDurationMillis / 60_000f
        val baseWindow = (visibleMinutes / 144f).coerceIn(1f, 13f)
        val factor = when (safeLevel) {
            1 -> 0.72f
            2 -> 1f
            else -> 1.35f
        }
        val requested = (baseWindow * factor).roundToInt().coerceIn(1, 13)
        return graphWindowMinutes.minBy { kotlin.math.abs(it - requested) }
    }

    @JvmStatic
    fun collapseChunks(context: Context): Boolean {
        return context.preferences().getBoolean(COLLAPSE_CHUNKS_KEY, false)
    }

    @JvmStatic
    fun setCollapseChunks(context: Context, enabled: Boolean) {
        context.preferences().edit().putBoolean(COLLAPSE_CHUNKS_KEY, enabled).apply()
    }

    @JvmStatic
    fun shouldSmoothExchangeOutputs(context: Context): Boolean = exchangeSmoothingMinutes(context) > 0

    @JvmStatic
    fun shouldCollapseExchangeOutputs(context: Context): Boolean {
        return shouldSmoothExchangeOutputs(context) && collapseChunks(context)
    }

    @JvmStatic
    fun collapseIntervalMinutes(smoothingMinutes: Int): Int {
        if (smoothingMinutes <= 0) return 0
        return minOf(smoothingMinutes.coerceAtMost(graphWindowMinutes.last()), MAX_CHUNK_INTERVAL_MINUTES)
    }

    @JvmStatic
    @JvmOverloads
    fun smoothNativePoints(
        points: List<GlucosePoint>?,
        smoothingMinutes: Int,
        collapseChunks: Boolean,
        preserveLatestEndpoint: Boolean = false
    ): List<GlucosePoint> {
        if (points.isNullOrEmpty()) return emptyList()
        val sanitizedMinutes = graphWindowMinutes.firstOrNull { it == smoothingMinutes }
            ?: sanitizeExchangeMinutes(smoothingMinutes)
        if (sanitizedMinutes <= 0) return points
        if (points.size < 3) {
            return if (collapseChunks) {
                collapsePointsForDisplay(points, collapseIntervalMinutes(sanitizedMinutes))
            } else {
                points
            }
        }

        val halfWindowMs = (sanitizedMinutes * 60_000L) / 2L
        val smoothedAuto = smoothSeries(points, halfWindowMs, useRawValue = false, preserveLatestEndpoint)
        val smoothedRaw = smoothSeries(points, halfWindowMs, useRawValue = true, preserveLatestEndpoint)
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
        useRawValue: Boolean,
        preserveLatestEndpoint: Boolean
    ): FloatArray {
        val size = points.size
        val prefixSums = DoubleArray(size + 1)
        val prefixCounts = IntArray(size + 1)
        val latestTimestamp = points.maxOf { it.timestamp }

        for (index in 0 until size) {
            val point = points[index]
            val value = if (useRawValue) point.rawValue else point.value
            val valid = value.isFinite() && value >= 0.1f
            prefixSums[index + 1] = prefixSums[index] + if (valid) value.toDouble() else 0.0
            prefixCounts[index + 1] = prefixCounts[index] + if (valid) 1 else 0
        }

        val result = FloatArray(size)
        var windowStart = 0
        var windowEndExclusive = 0

        for (index in 0 until size) {
            val point = points[index]
            val original = if (useRawValue) point.rawValue else point.value
            if (!original.isFinite() || original < 0.1f) {
                result[index] = original
                continue
            }

            val minTime = point.timestamp - halfWindowMs
            val maxTime = point.timestamp + halfWindowMs
            while (windowStart < size && points[windowStart].timestamp < minTime) windowStart++
            while (windowEndExclusive < size && points[windowEndExclusive].timestamp <= maxTime) {
                windowEndExclusive++
            }

            val count = prefixCounts[windowEndExclusive] - prefixCounts[windowStart]
            val average = if (count > 0) {
                ((prefixSums[windowEndExclusive] - prefixSums[windowStart]) / count).toFloat()
            } else {
                original
            }
            val smoothingWeight = if (preserveLatestEndpoint) {
                ((latestTimestamp - point.timestamp).toFloat() / halfWindowMs.toFloat()).coerceIn(0f, 1f)
            } else {
                1f
            }
            result[index] = original + ((average - original) * smoothingWeight)
        }

        return result
    }

    internal fun collapsePointsForDisplay(
        points: List<GlucosePoint>,
        smoothingMinutes: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): List<GlucosePoint> {
        if (points.isEmpty() || smoothingMinutes <= 0) return points
        val bucketDurationMs = smoothingMinutes * 60_000L
        val openBucket = nowMillis / bucketDurationMs
        val collapsed = ArrayList<GlucosePoint>()
        var activeBucket = Long.MIN_VALUE
        var pending: GlucosePoint? = null

        for (point in points) {
            val bucket = point.timestamp / bucketDurationMs
            if (bucket != activeBucket) {
                if (activeBucket < openBucket) pending?.let(collapsed::add)
                activeBucket = bucket
            }
            pending = point
        }

        if (activeBucket < openBucket) pending?.let(collapsed::add)
        return when {
            collapsed.isNotEmpty() -> collapsed
            points.isNotEmpty() -> listOf(points.last())
            else -> points
        }
    }

    private fun migrateSplitSettings(context: Context) {
        val prefs = context.preferences()
        if (prefs.getBoolean(SPLIT_MIGRATED_KEY, false)) return

        val legacyMinutes = sanitizeExchangeMinutes(prefs.getInt(LEGACY_MINUTES_KEY, 0))
        val legacyGraphOnly = prefs.getBoolean(LEGACY_GRAPH_ONLY_KEY, false)
        val legacyExchangeOnly = prefs.getBoolean(LEGACY_EXCHANGE_ONLY_KEY, false)
        val graphLevel = if (legacyMinutes > 0 && !legacyExchangeOnly) {
            when {
                legacyMinutes <= 3 -> 1
                legacyMinutes <= 7 -> 2
                else -> 3
            }
        } else {
            0
        }
        val exchangeWindow = if (legacyMinutes > 0 && !legacyGraphOnly) legacyMinutes else 0
        prefs.edit()
            .putInt(GRAPH_LEVEL_KEY, graphLevel)
            .putInt(EXCHANGE_MINUTES_KEY, exchangeWindow)
            .putBoolean(SPLIT_MIGRATED_KEY, true)
            .apply()
    }

    private fun Context.preferences() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
