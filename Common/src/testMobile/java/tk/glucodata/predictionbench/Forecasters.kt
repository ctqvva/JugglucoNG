package tk.glucodata.predictionbench

import kotlin.math.exp
import tk.glucodata.CausalGlucoseFilter
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.data.prediction.AdaptiveForecastModel
import tk.glucodata.data.prediction.AdaptiveGlucoseForecast
import tk.glucodata.data.prediction.GlucosePredictionPoint
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.data.prediction.buildGlucosePrediction
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.DASHBOARD_CONSUMER_HISTORY_MAX_POINTS
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.buildSmoothedConsumerHistory
import tk.glucodata.ui.trimHistoryForPrediction

/**
 * A candidate forecast. Returns mg/dL at `originTime + horizons[k]` minutes, NaN to abstain.
 *
 * Only [view] may be read. A forecaster may keep state between calls (a learner's model),
 * but everything in that state has to have come through an earlier view, which is what keeps
 * it causal; [PredictionBenchHarnessTests] checks this by rewriting the future and requiring
 * identical forecasts.
 */
interface Forecaster {
    val name: String
    fun forecast(view: OriginView, horizons: IntArray): FloatArray
}

/** The phone's settings as shipped. Prediction is on by default with these values. */
val PRODUCTION_DEFAULT_SETTINGS = PredictiveSimulationSettings(enabled = true, trendMomentumEnabled = true)

/**
 * The dashboard's prediction input at one moment, built by the production functions:
 * the newest [DASHBOARD_CONSUMER_HISTORY_MAX_POINTS] readings, smoothed as the user set it
 * (0 = off, the default), trimmed to the prediction window. No calibration is applied here:
 * a calibrated lane is benchmarked by feeding its calibrated values as the lane.
 */
internal fun productionHistory(view: OriginView, smoothingMinutes: Int): List<GlucosePoint> =
    view.memo("history:$smoothingMinutes") {
        val from = maxOf(0, view.size - DASHBOARD_CONSUMER_HISTORY_MAX_POINTS)
        val points = (from until view.size).map { i ->
            val v = view.display(i)
            GlucosePoint(value = v, time = "", timestamp = view.time(i), rawValue = v, sensorSerial = view.sensor)
        }
        trimHistoryForPrediction(buildSmoothedConsumerHistory(points, smoothingMinutes, collapseChunks = false))
    }

internal fun settingsFor(view: OriginView, base: PredictiveSimulationSettings, useProfile: Boolean = true) =
    if (useProfile && view.profile != null) base.copy(modelProfile = view.profile) else base

internal fun targetLow(view: OriginView): Float = GlucoseRangeColors.defaultLow(view.isMmol)
internal fun targetHigh(view: OriginView): Float = GlucoseRangeColors.defaultHigh(view.isMmol)

internal fun curveAtHorizons(curve: List<GlucosePredictionPoint>, isMmol: Boolean, horizons: IntArray, unclamped: Boolean = false): FloatArray {
    val out = FloatArray(horizons.size) { Float.NaN }
    if (curve.isEmpty()) return out
    val base = curve.first().timestamp
    for ((k, h) in horizons.withIndex()) {
        val point = curve.firstOrNull { it.timestamp == base + h * 60_000L } ?: continue
        out[k] = toMgdl(if (unclamped) point.unclampedValue else point.value, isMmol)
    }
    return out
}

/** The production chart forecast, end to end, as the dashboard computes it. */
class ProductionKernel(
    override val name: String,
    private val settings: PredictiveSimulationSettings = PRODUCTION_DEFAULT_SETTINGS,
    private val smoothingMinutes: Int = 0,
    /** Use the person's profile where the dataset has one; false = the app's defaults regardless. */
    private val useProfile: Boolean = true,
) : Forecaster {
    override fun forecast(view: OriginView, horizons: IntArray): FloatArray {
        val curve = buildGlucosePrediction(
            history = productionHistory(view, smoothingMinutes),
            journalEntries = view.journal,
            insulinPresetsById = view.presets,
            unit = view.unit,
            targetLow = targetLow(view),
            targetHigh = targetHigh(view),
            settings = settingsFor(view, settings, useProfile),
        )
        return curveAtHorizons(curve, view.isMmol, horizons)
    }
}

/** The person's own average over the last day: what is left when the recent past says nothing. */
class TrailingMean(override val name: String, private val windowMs: Long = 24L * 60 * 60_000) : Forecaster {
    override fun forecast(view: OriginView, horizons: IntArray): FloatArray {
        val mean = view.meanMgdl(view.originTime - windowMs, view.originTime)
        return FloatArray(horizons.size) { mean }
    }
}

class Persistence(override val name: String = "persistence (last reading)") : Forecaster {
    override fun forecast(view: OriginView, horizons: IntArray): FloatArray {
        val v = view.mgdl(view.end)
        return FloatArray(horizons.size) { v }
    }
}

enum class SlopeSource {
    /** The kernel's own: unweighted least squares over ≤10 readings within 45 min. */
    KERNEL_OLS,
    /** The trend arrow's: cadence-scaled window, recency-weighted. */
    TREND_ENGINE,
    /** The causal Kalman filter's rate state. */
    KALMAN,
}

enum class MomentumShape {
    NONE,
    /** `s·m·e^(−m/τ)`: production. Velocity reverses at m = τ and the curve returns to baseline. */
    REBOUND,
    /** `s·τ·(1 − e^(−m/τ))`: velocity decays, the curve levels off at s·τ. */
    PLATEAU,
    /** `s·m`: the forecast alerts' projection. */
    LINEAR,
}

enum class LevelSource { LAST, KALMAN }

/**
 * The production kernel taken apart so each assumption can be switched on its own.
 *
 * With its defaults this is the kernel: same input pipeline, same Float arithmetic, same
 * clamps — [PredictionBenchHarnessTests] holds it to the production output. Treatments are
 * taken from the production kernel run without momentum, so they stay the production
 * treatment model. One known difference: production fits its slope to journal-residualised
 * readings and this does not, so the two agree exactly only where no treatment is active.
 */
class KernelVariant(
    override val name: String,
    private val slope: SlopeSource = SlopeSource.KERNEL_OLS,
    private val shape: MomentumShape = MomentumShape.REBOUND,
    private val tauMinutes: Float = 70f,
    private val settling: Boolean = true,
    private val level: LevelSource = LevelSource.LAST,
    private val slopeScale: Float = 1f,
    private val clampSlope: Boolean = true,
    /** Above 0, a pull toward the trailing 24 h mean with this time constant. */
    private val reversionTauMinutes: Float = 0f,
    private val smoothingMinutes: Int = 0,
    private val settings: PredictiveSimulationSettings = PRODUCTION_DEFAULT_SETTINGS,
) : Forecaster {

    override fun forecast(view: OriginView, horizons: IntArray): FloatArray {
        val out = FloatArray(horizons.size) { Float.NaN }
        val history = productionHistory(view, smoothingMinutes)
        if (history.size < 2) return out
        val baseline = history.asReversed().firstOrNull { it.value.isFinite() && it.value > 0.1f } ?: return out
        val isMmol = view.isMmol
        val conversion = if (isMmol) MGDL_PER_MMOL else 1f
        val targetCenter = ((targetLow(view) + targetHigh(view)) * 0.5f).takeIf { it.isFinite() && it > 0f }
            ?: baseline.value

        val rawSlope = when (slope) {
            SlopeSource.KERNEL_OLS -> kernelOlsSlope(history, baseline.timestamp)
            SlopeSource.TREND_ENGINE -> trendEngineVelocity(view, smoothingMinutes) / conversion
            SlopeSource.KALMAN -> (kalman(view)?.rateMgdlPerMin?.toFloat() ?: 0f) / conversion
        } * slopeScale
        val maxSlope = if (isMmol) 0.16f else 3f
        val s = if (clampSlope) rawSlope.coerceIn(-maxSlope, maxSlope) else rawSlope
        val start = when (level) {
            LevelSource.LAST -> baseline.value
            LevelSource.KALMAN -> kalman(view)?.mgdl?.toFloat()?.div(conversion) ?: baseline.value
        }
        val mean24 = if (reversionTauMinutes > 0f) {
            view.meanMgdl(view.originTime - 24L * 60 * 60_000, view.originTime) / conversion
        } else Float.NaN
        val treatments = if (view.journal.isEmpty()) null else treatmentCurve(view, horizons)

        val lowClamp = if (isMmol) 1.0f else 18f
        val highClamp = if (isMmol) 30f else 540f
        for ((k, h) in horizons.withIndex()) {
            if (h > settings.horizonMinutes.coerceIn(30, 360)) continue
            val m = h.toFloat()
            val trend = when (shape) {
                MomentumShape.NONE -> 0f
                MomentumShape.REBOUND -> s * m * exp(-m / tauMinutes)
                MomentumShape.PLATEAU -> s * tauMinutes * (1f - exp(-m / tauMinutes))
                MomentumShape.LINEAR -> s * m
            }
            val settle = if (settling) (targetCenter - baseline.value) * (1f - exp(-m / 240f)) * 0.18f else 0f
            val revert = if (mean24.isFinite()) (mean24 - start) * (1f - exp(-m / reversionTauMinutes)) else 0f
            val treatment = treatments?.get(k) ?: 0f
            val projected = start + (trend + settle + revert + treatment)
            out[k] = toMgdl(projected.coerceIn(lowClamp, highClamp), isMmol)
        }
        return out
    }

    /** Production treatment effect at each horizon, in display units: the momentum-free curve minus its pull. */
    private fun treatmentCurve(view: OriginView, horizons: IntArray): FloatArray =
        treatmentEffects(view, horizons, smoothingMinutes, settings)

    companion object {
        /** GlucosePredictionKernel.residualSlopePerMinute with no treatments, in its own Float arithmetic. */
        internal fun kernelOlsSlope(history: List<GlucosePoint>, baselineTime: Long): Float {
            val recent = history.asReversed().asSequence()
                .filter { it.timestamp <= baselineTime && baselineTime - it.timestamp <= 45L * 60_000L }
                .filter { it.value.isFinite() && it.value > 0.1f }
                .take(10).toList().asReversed()
            if (recent.size < 2) return 0f
            val firstTime = recent.first().timestamp
            val xs = recent.map { (it.timestamp - firstTime) / 60_000f }
            val ys = recent.map { it.value - 0f }
            val xMean = xs.average().toFloat()
            val yMean = ys.average().toFloat()
            var numerator = 0f
            var denominator = 0f
            for (index in recent.indices) {
                val dx = xs[index] - xMean
                numerator += dx * (ys[index] - yMean)
                denominator += dx * dx
            }
            return if (denominator > 0.001f) numerator / denominator else 0f
        }
    }
}

/**
 * The production treatment model's effect at each horizon, in display units: the
 * momentum-free curve minus its pull toward the target. Shared by every candidate that adds
 * logged treatments on top of its own CGM forecast.
 */
internal fun treatmentEffects(
    view: OriginView,
    horizons: IntArray,
    smoothingMinutes: Int = 0,
    settings: PredictiveSimulationSettings = PRODUCTION_DEFAULT_SETTINGS,
): FloatArray = view.memo("treatments:$smoothingMinutes:${settings.hashCode()}:${horizons.contentHashCode()}") {
    val history = productionHistory(view, smoothingMinutes)
    val curve = buildGlucosePrediction(history, view.journal, view.presets, view.unit,
        targetLow(view), targetHigh(view), settingsFor(view, settings).copy(trendMomentumEnabled = false))
    val unclamped = curveAtHorizons(curve, false, horizons, unclamped = true)
    val baseline = curve.firstOrNull()?.value ?: return@memo FloatArray(horizons.size)
    val center = (targetLow(view) + targetHigh(view)) * 0.5f
    FloatArray(horizons.size) { k ->
        val m = horizons[k].toFloat()
        val pull = (center - baseline) * (1f - exp(-m / 240f)) * 0.18f
        (unclamped[k] - baseline - pull).takeIf { it.isFinite() } ?: 0f
    }
}

/**
 * The production adaptive forecast ([AdaptiveGlucoseForecast]) as a phone would run it:
 * refitted once a day on the visible past, and falling back to the production forecast
 * without momentum whenever there is no model yet, the model lost to "no change" on its
 * holdout, or the last hour does not support the features (new sensor, gap).
 */
class AdaptiveProduction(
    override val name: String,
    /** Add the production treatment model's effect of logged insulin and carbs. */
    private val withTreatments: Boolean = false,
    private val refitEveryMs: Long = 24L * 60 * 60_000,
) : Forecaster {
    private val fallback = ProductionKernel("fallback", PRODUCTION_DEFAULT_SETTINGS.copy(trendMomentumEnabled = false))
    private class State(val fittedAt: Long, val model: AdaptiveForecastModel?)
    private val states = HashMap<String, State>()
    var fallbacks = 0
        private set

    override fun forecast(view: OriginView, horizons: IntArray): FloatArray {
        var state = states[view.laneId]
        if (state == null || view.originTime - state.fittedAt >= refitEveryMs) {
            val window = (AdaptiveGlucoseForecast.TRAINING_WINDOW_DAYS + 1) * 24L * 60 * 60_000
            state = State(view.originTime, AdaptiveGlucoseForecast.fit(view.adaptiveHistory(view.originTime - window), view.originTime))
            states[view.laneId] = state
        }
        val model = state.model?.takeIf { it.reliable }
        val deltas = model?.let {
            AdaptiveGlucoseForecast.forecastDeltas(it, view.adaptiveHistory(view.originTime - 25L * 60 * 60_000), view.originTime)
        }
        if (deltas == null) {
            fallbacks++
            return fallback.forecast(view, horizons)
        }
        val history = productionHistory(view, 0)
        val baseline = history.lastOrNull()?.value?.let { toMgdl(it, view.isMmol) } ?: return FloatArray(horizons.size) { Float.NaN }
        val treatments = if (withTreatments && view.journal.isNotEmpty()) treatmentEffects(view, horizons) else null
        return FloatArray(horizons.size) { k ->
            val idx = model.horizons.indexOf(horizons[k])
            if (idx < 0 || !deltas[idx].isFinite()) Float.NaN
            else baseline + deltas[idx] + (treatments?.get(k)?.let { toMgdl(it, view.isMmol) } ?: 0f)
        }
    }
}

/** The arrow's velocity, mg/dL/min, from the same prediction history. */
internal fun trendEngineVelocity(view: OriginView, smoothingMinutes: Int = 0): Float =
    view.memo("trend:$smoothingMinutes") {
        TrendEngine.calculateTrend(productionHistory(view, smoothingMinutes), useRaw = false, isMmol = view.isMmol).velocity
    }

internal class KalmanState(val mgdl: Double, val rateMgdlPerMin: Double)

/** The production causal filter over the last hour, at the origin. */
internal fun kalman(view: OriginView): KalmanState? = view.memo("kalman") {
    val from = maxOf(0, view.indexAtOrBefore(view.originTime - CausalGlucoseFilter.WINDOW_MS))
    val n = view.size - from
    val times = LongArray(n) { view.time(from + it) }
    val values = DoubleArray(n) { view.mgdl(from + it).toDouble() }
    CausalGlucoseFilter.estimateAt(times, values, n - 1)?.let { KalmanState(it.mgdl, it.rateMgdlPerMin) }
}
