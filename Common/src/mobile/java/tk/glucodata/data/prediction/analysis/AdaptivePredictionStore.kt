package tk.glucodata.data.prediction.analysis

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import tk.glucodata.Log
import tk.glucodata.SensorIdentity
import tk.glucodata.data.HistoryRepository
import tk.glucodata.data.prediction.AdaptiveForecastModel
import tk.glucodata.data.prediction.AdaptiveGlucoseForecast
import tk.glucodata.data.prediction.AdaptiveHistory
import tk.glucodata.data.prediction.GlucosePredictionPoint
import tk.glucodata.settings.SettingsRegistry
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.util.GlucoseFormatter
import kotlin.math.sqrt

/**
 * The phone's adaptive forecast: the fitted model, the recent history it reads, and when to
 * refresh either.
 *
 * Nothing here runs on its own schedule. Training happens on [PredictionAnalysisWork]'s thread
 * when something asks for the model and it is missing or a day old — a new reading while
 * analysis is on, or the chart while adaptive prediction is shown. With both off, nothing
 * trains and nothing is loaded.
 *
 * One model per person, not per sensor: it learns from every sensor in the last four weeks,
 * and its features never read across a sensor change (see [AdaptiveHistory]), so a new sensor
 * starts with what the old ones taught instead of two days of nothing.
 */
object AdaptivePredictionStore {
    private const val LOG_ID = "AdaptivePrediction"
    private const val MODEL_KEY = "prediction_adaptive_model"
    private const val MODEL_LANE_KEY = "prediction_adaptive_model_raw_lane"
    private const val DAY_MS = 24L * 60 * 60_000
    private const val REFIT_AFTER_MS = DAY_MS
    /** A failed or empty fit is not retried on every reading. */
    private const val RETRY_AFTER_MS = 6L * 60 * 60_000
    private const val CACHE_REFRESH_MS = 2L * 60_000
    /** Enough history for the 24 h mean and the hour of features behind the newest reading. */
    private const val CACHE_SPAN_MS = 25L * 60 * 60_000

    private class Cached(val model: AdaptiveForecastModel?, val rawLane: Boolean, val attemptedAt: Long)
    private class RecentHistory(
        val loadedAt: Long,
        val times: LongArray,
        val auto: FloatArray,
        val raw: FloatArray,
        val sensors: List<String?>,
    )

    @Volatile
    private var cached: Cached? = null

    @Volatile
    private var recent: RecentHistory? = null

    @Volatile
    private var refreshQueued = false

    /** The adaptive model's state, for the settings row. */
    sealed interface Status {
        data object Learning : Status
        data class Ready(val trainingDays: Float, val trainedAtMs: Long) : Status
        /** It lost to "no change" on its own recent holdout, so the fallback is shown instead. */
        data class NotBetter(val trainingDays: Float) : Status
    }

    fun status(context: Context): Status {
        val model = model(context) ?: return Status.Learning
        return if (model.reliable) Status.Ready(model.trainingDays, model.trainedAtMs) else Status.NotBetter(model.trainingDays)
    }

    fun isDisplayEnabled(prefs: SharedPreferences): Boolean = SettingsRegistry.PREDICTION_ADAPTIVE.readBool(prefs)

    /**
     * The adaptive curve for the line [history] belongs to, in its display unit, or null when
     * the adaptive forecast cannot answer — no reliable model for this lane yet, or the last
     * hour does not support the features. The caller then draws the fallback.
     *
     * [history] is the prediction history of one lane, as `buildDisplayHistoryForPrediction`
     * makes it: `value` is the drawn value (calibrated where calibration is on) and `rawValue`
     * the lane's own reading. The model reads the lane's own readings and its answer, a change,
     * is added to the drawn value, so a calibrated line gets a calibrated forecast.
     */
    fun forecastPoints(
        context: Context,
        history: List<GlucosePoint>,
        isMmol: Boolean,
        rawLane: Boolean,
        horizonMinutes: Int,
    ): List<GlucosePredictionPoint>? {
        requestRefresh(context)
        val model = model(context)?.takeIf { it.reliable } ?: return null
        // The model learns the main line's lane; a second line drawn beside it gets the fallback.
        if (cached?.rawLane != rawLane) return null
        val baseline = history.lastOrNull { it.value.isFinite() && it.value > 0.1f } ?: return null
        val adaptiveHistory = mergedHistory(history, isMmol, rawLane) ?: return null
        val deltas = AdaptiveGlucoseForecast.forecastDeltas(model, adaptiveHistory, baseline.timestamp) ?: return null
        return pointsFrom(baseline, deltas, model.horizons, isMmol, horizonMinutes)
    }

    /** Training and cache refresh, when due. Never blocks the caller. */
    fun requestRefresh(context: Context) {
        if (refreshQueued) return
        val now = System.currentTimeMillis()
        val current = cached ?: run { model(context); cached }
        val modelDue = current == null ||
            (current.model == null && now - current.attemptedAt >= RETRY_AFTER_MS) ||
            (current.model != null && now - current.model.trainedAtMs >= REFIT_AFTER_MS)
        val historyDue = recent?.let { now - it.loadedAt >= CACHE_REFRESH_MS } ?: true
        if (!modelDue && !historyDue) return
        refreshQueued = true
        PredictionAnalysisWork.execute {
            try {
                refreshNow(context.applicationContext, now, refit = modelDue)
            } finally {
                refreshQueued = false
            }
        }
    }

    /** Blocking; on the worker thread only. */
    internal fun refreshNow(context: Context, now: Long = System.currentTimeMillis(), refit: Boolean) {
        val serial = SensorIdentity.resolveMainSensor()
        // The main line's lane: what the user reads, so what the model learns.
        val viewMode = runCatching { tk.glucodata.CurrentDisplaySource.resolveViewModeForSensor(serial) }.getOrDefault(0)
        val rawLane = viewMode == 1 || viewMode == 3
        // A switch of lane makes the current model the wrong one.
        val refitNow = refit || cached?.let { it.rawLane != rawLane } == true
        val span = if (refitNow) (AdaptiveGlucoseForecast.TRAINING_WINDOW_DAYS + 1) * DAY_MS else CACHE_SPAN_MS
        val points = runCatching {
            runBlocking { HistoryRepository().getDisplayHistory(serial, now - span) }
        }.onFailure { Log.stack(LOG_ID, "history", it) }.getOrNull() ?: return
        val sorted = points.filter { it.timestamp in 1..now }.sortedBy { it.timestamp }
        recent = sorted.filter { it.timestamp >= now - CACHE_SPAN_MS }.let { window ->
            RecentHistory(
                loadedAt = now,
                times = LongArray(window.size) { window[it].timestamp },
                auto = FloatArray(window.size) { window[it].value },
                raw = FloatArray(window.size) { window[it].rawValue },
                sensors = window.map { it.sensorSerial },
            )
        }
        if (!refitNow) return
        val history = AdaptiveHistory.of(
            LongArray(sorted.size) { sorted[it].timestamp },
            FloatArray(sorted.size) { if (rawLane) sorted[it].rawValue else sorted[it].value },
            sorted.map { it.sensorSerial },
        )
        val model = runCatching { AdaptiveGlucoseForecast.fit(history, now) }
            .onFailure { Log.stack(LOG_ID, "fit", it) }.getOrNull()
        cached = Cached(model, rawLane, now)
        prefs(context).edit()
            .putString(MODEL_KEY, model?.encode())
            .putBoolean(MODEL_LANE_KEY, rawLane)
            .apply()
    }

    fun model(context: Context): AdaptiveForecastModel? {
        cached?.let { return it.model }
        val prefs = prefs(context)
        val decoded = AdaptiveForecastModel.decode(prefs.getString(MODEL_KEY, null))
        cached = Cached(decoded, prefs.getBoolean(MODEL_LANE_KEY, false), decoded?.trainedAtMs ?: 0L)
        return decoded
    }

    /** Forgets the model; the next request trains a new one. Recorded forecasts are untouched. */
    fun reset(context: Context) {
        cached = null
        recent = null
        prefs(context).edit().remove(MODEL_KEY).remove(MODEL_LANE_KEY).apply()
    }

    /**
     * The cached recent history older than [history], then [history]'s own lane readings, in
     * mg/dL with sensor segments. Null until the cache has loaded once.
     */
    private fun mergedHistory(history: List<GlucosePoint>, isMmol: Boolean, rawLane: Boolean): AdaptiveHistory? {
        val cache = recent ?: return null
        val first = history.firstOrNull()?.timestamp ?: return null
        val times = ArrayList<Long>(cache.times.size + history.size)
        val values = ArrayList<Float>(cache.times.size + history.size)
        val sensors = ArrayList<String?>(cache.times.size + history.size)
        for (i in cache.times.indices) {
            if (cache.times[i] >= first) break
            times += cache.times[i]
            values += if (rawLane) cache.raw[i] else cache.auto[i]
            sensors += SensorIdentity.resolveRoomStorageSensorId(cache.sensors[i]) ?: cache.sensors[i]
        }
        for (point in history) {
            times += point.timestamp
            values += if (isMmol) point.rawValue * GlucoseFormatter.MGDL_PER_MMOL else point.rawValue
            sensors += SensorIdentity.resolveRoomStorageSensorId(point.sensorSerial) ?: point.sensorSerial
        }
        return AdaptiveHistory.of(times.toLongArray(), values.toFloatArray(), sensors)
    }

    private fun pointsFrom(
        baseline: GlucosePoint,
        deltasMgdl: FloatArray,
        horizons: IntArray,
        isMmol: Boolean,
        horizonMinutes: Int,
    ): List<GlucosePredictionPoint>? {
        val limit = horizonMinutes.coerceIn(30, horizons.last())
        val lowClamp = if (isMmol) 1.0f else 18f
        val highClamp = if (isMmol) 30f else 540f
        val points = ArrayList<GlucosePredictionPoint>(horizons.size + 1)
        points += GlucosePredictionPoint(baseline.timestamp, baseline.value, confidence = 1f)
        for ((k, minutes) in horizons.withIndex()) {
            if (minutes > limit) break
            val delta = deltasMgdl[k].takeIf { it.isFinite() } ?: continue
            val projected = baseline.value + if (isMmol) delta / GlucoseFormatter.MGDL_PER_MMOL else delta
            // The standard forecast's confidence ramp, so the chart's band reads the same.
            val confidence = (0.88f - 0.62f * sqrt(minutes.toFloat() / limit)).coerceIn(0.18f, 0.88f)
            points += GlucosePredictionPoint(
                timestamp = baseline.timestamp + minutes * 60_000L,
                value = projected.coerceIn(lowClamp, highClamp),
                confidence = confidence,
                unclampedValue = projected,
            )
        }
        return points.takeIf { it.size >= 2 }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(SettingsRegistry.FILE, Context.MODE_PRIVATE)
}
