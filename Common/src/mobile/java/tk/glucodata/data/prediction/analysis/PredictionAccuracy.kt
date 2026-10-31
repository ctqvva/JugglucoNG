package tk.glucodata.data.prediction.analysis

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The glucose that actually followed, per sensor and lane: what a recorded forecast is scored
 * against. Readings of one sensor only; a forecast is never scored against another sensor's
 * line, because a sensor change is a jump in the measurement, not in glucose.
 */
class ActualGlucose(private val bySensor: Map<String, SensorLanes>) {

    /** One sensor's readings, ascending, with each lane's value (NaN where a lane has none). */
    class SensorLanes(val times: LongArray, val lanes: Map<Int, FloatArray>) {
        val latestTime: Long get() = times.lastOrNull() ?: Long.MIN_VALUE

        /** Longest gap a value is interpolated across: 2.5 readings, at least 6 and at most 20 minutes. */
        val maxGapMs: Long by lazy {
            val gaps = (1 until times.size).map { times[it] - times[it - 1] }.filter { it > 0 }.sorted()
            val cadence = if (gaps.isEmpty()) 5 * MINUTE else gaps[gaps.size / 2]
            (cadence * 5 / 2).coerceIn(6 * MINUTE, 20 * MINUTE)
        }

        fun valueAt(lane: Int, t: Long): Float {
            val values = lanes[lane] ?: return Float.NaN
            var lo = 0
            var hi = times.size - 1
            var i = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] <= t) { i = mid; lo = mid + 1 } else hi = mid - 1
            }
            if (i < 0) return Float.NaN
            if (times[i] == t) return values[i].takeIf { it.isFinite() && it > 0f } ?: Float.NaN
            if (i + 1 >= times.size) return Float.NaN
            val t0 = times[i]
            val t1 = times[i + 1]
            val v0 = values[i]
            val v1 = values[i + 1]
            if (t1 - t0 > maxGapMs || !(v0.isFinite() && v0 > 0f && v1.isFinite() && v1 > 0f)) return Float.NaN
            return v0 + (v1 - v0) * ((t - t0).toFloat() / (t1 - t0).toFloat())
        }
    }

    fun latestTime(sensor: String): Long = bySensor[sensor]?.latestTime ?: Long.MIN_VALUE

    /** The readings themselves in [fromMs, toMs], for drawing. */
    fun readings(sensor: String, lane: Int, fromMs: Long, toMs: Long): Pair<LongArray, FloatArray> {
        val s = bySensor[sensor] ?: return LongArray(0) to FloatArray(0)
        val values = s.lanes[lane] ?: return LongArray(0) to FloatArray(0)
        val idx = s.times.indices.filter { s.times[it] in fromMs..toMs && values[it].isFinite() && values[it] > 0f }
        return LongArray(idx.size) { s.times[idx[it]] } to FloatArray(idx.size) { values[idx[it]] }
    }

    fun valueAt(sensor: String, lane: Int, t: Long): Float = bySensor[sensor]?.valueAt(lane, t) ?: Float.NaN

    private companion object {
        const val MINUTE = 60_000L
    }
}

/** How a recorded forecast fared at one horizon. */
enum class Outcome { RESOLVED, PENDING, MISSING }

data class HorizonAccuracy(
    val horizonMinutes: Int,
    /** Forecasts with an outcome to compare against. */
    val count: Int,
    /** Mean absolute error, mg/dL. */
    val meanAbsoluteError: Float,
    /** Mean of forecast − actual, mg/dL: positive = forecasts ran high. */
    val bias: Float,
    val rootMeanSquareError: Float,
    /** The same, for simply assuming glucose stays where it was at the origin. */
    val noChangeMeanAbsoluteError: Float,
    /** Outcomes still in the future. */
    val pending: Int,
    /** Outcomes that cannot be scored: a gap in readings, another sensor, or no reading there. */
    val missing: Int,
)

data class ModelAccuracy(
    val model: ForecastModel,
    val forecasts: Int,
    /** Of them, how many the adaptive forecast answered with its fallback. */
    val fallbacks: Int,
    val horizons: List<HorizonAccuracy>,
)

/** Both models on the same moments, so neither gets an easier set of forecasts. */
data class PairedAccuracy(
    val horizonMinutes: Int,
    val count: Int,
    val standardMeanAbsoluteError: Float,
    val adaptiveMeanAbsoluteError: Float,
    val noChangeMeanAbsoluteError: Float,
)

data class DailyAccuracy(val dayStartMs: Long, val model: ForecastModel, val count: Int, val meanAbsoluteError: Float)

data class AccuracyReport(
    val models: List<ModelAccuracy>,
    val paired: List<PairedAccuracy>,
    /** Per day, at [dailyHorizonMinutes]. */
    val daily: List<DailyAccuracy>,
    val dailyHorizonMinutes: Int,
    /** Forecasts that already included journal entries dated after their origin. */
    val withPlannedTreatments: Int,
) {
    fun model(model: ForecastModel): ModelAccuracy? = models.firstOrNull { it.model == model }
    fun paired(horizonMinutes: Int): PairedAccuracy? = paired.firstOrNull { it.horizonMinutes == horizonMinutes }
}

/**
 * Scores recorded forecasts against the glucose that followed. Pure arithmetic: what to load
 * and from where is the repository's business.
 */
object PredictionAccuracy {
    val DEFAULT_HORIZONS = intArrayOf(15, 30, 60, 120)

    /** Below this many scored forecasts a horizon says nothing yet. */
    const val MIN_RELIABLE_COUNT = 36

    /** Below this many it is an early estimate: under a day of five-minute forecasts. */
    const val SETTLED_COUNT = 288

    /**
     * How long after its time a reading may still arrive (a backfill, a late sync). An outcome
     * past this with no reading after it is missing — the sensor stopped or its history went —
     * not pending.
     */
    const val ARRIVAL_ALLOWANCE_MS = 30 * 60_000L

    fun outcome(
        forecast: RecordedForecastEntity,
        horizonMinutes: Int,
        actual: ActualGlucose,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<Outcome, Float> {
        val predicted = forecast.valueAt(horizonMinutes)
        if (!predicted.isFinite()) return Outcome.MISSING to Float.NaN
        return actualOutcome(forecast.sensorSerial, forecast.lane, forecast.originTime + horizonMinutes * 60_000L, actual, nowMs)
    }

    /** Resolve a measured outcome even when no forecast was saved at this horizon. */
    fun actualOutcome(
        sensorSerial: String,
        lane: Int,
        target: Long,
        actual: ActualGlucose,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<Outcome, Float> {
        if (target > actual.latestTime(sensorSerial)) {
            return (if (target > nowMs - ARRIVAL_ALLOWANCE_MS) Outcome.PENDING else Outcome.MISSING) to Float.NaN
        }
        val value = actual.valueAt(sensorSerial, lane, target)
        return if (value.isFinite()) Outcome.RESOLVED to value else Outcome.MISSING to Float.NaN
    }

    fun report(
        forecasts: List<RecordedForecastEntity>,
        actual: ActualGlucose,
        horizons: IntArray = DEFAULT_HORIZONS,
        dailyHorizonMinutes: Int = 30,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis(),
    ): AccuracyReport {
        val byModel = forecasts.groupBy { ForecastModel.fromStorage(it.model) }
        val models = ForecastModel.entries.mapNotNull { model ->
            val list = byModel[model].orEmpty()
            if (list.isEmpty()) return@mapNotNull null
            ModelAccuracy(
                model = model,
                forecasts = list.size,
                fallbacks = list.count { it.fallback },
                horizons = horizons.map { h -> horizonAccuracy(list, h, actual, nowMs) },
            )
        }

        val standardByOrigin = byModel[ForecastModel.STANDARD].orEmpty().associateBy { it.originTime }
        val pairs = byModel[ForecastModel.ADAPTIVE].orEmpty().mapNotNull { adaptive ->
            standardByOrigin[adaptive.originTime]?.let { it to adaptive }
        }
        val paired = horizons.map { h ->
            var n = 0
            var standardSum = 0.0
            var adaptiveSum = 0.0
            var noChangeSum = 0.0
            for ((standard, adaptive) in pairs) {
                val (o1, actualStandard) = outcome(standard, h, actual, nowMs)
                val (o2, actualAdaptive) = outcome(adaptive, h, actual, nowMs)
                if (o1 != Outcome.RESOLVED || o2 != Outcome.RESOLVED) continue
                n++
                standardSum += abs(standard.valueAt(h) - actualStandard)
                adaptiveSum += abs(adaptive.valueAt(h) - actualAdaptive)
                noChangeSum += abs(standard.baselineMgdl - actualStandard)
            }
            PairedAccuracy(h, n, mean(standardSum, n), mean(adaptiveSum, n), mean(noChangeSum, n))
        }

        val daily = forecasts.groupBy {
            Instant.ofEpochMilli(it.originTime).atZone(zone).toLocalDate() to ForecastModel.fromStorage(it.model)
        }.mapNotNull { (key, list) ->
            val model = key.second ?: return@mapNotNull null
            var n = 0
            var sum = 0.0
            for (f in list) {
                val (o, value) = outcome(f, dailyHorizonMinutes, actual, nowMs)
                if (o != Outcome.RESOLVED) continue
                n++
                sum += abs(f.valueAt(dailyHorizonMinutes) - value)
            }
            if (n == 0) null
            else DailyAccuracy(key.first.atStartOfDay(zone).toInstant().toEpochMilli(), model, n, mean(sum, n))
        }.sortedWith(compareBy({ it.dayStartMs }, { it.model }))

        return AccuracyReport(
            models = models,
            paired = paired,
            daily = daily,
            dailyHorizonMinutes = dailyHorizonMinutes,
            withPlannedTreatments = forecasts.count { it.plannedEntries > 0 && it.model == ForecastModel.STANDARD.storageId },
        )
    }

    private fun horizonAccuracy(list: List<RecordedForecastEntity>, h: Int, actual: ActualGlucose, nowMs: Long): HorizonAccuracy {
        var n = 0
        var pending = 0
        var missing = 0
        var sumAbs = 0.0
        var sum = 0.0
        var sumSq = 0.0
        var noChange = 0.0
        for (f in list) {
            val (o, value) = outcome(f, h, actual, nowMs)
            when (o) {
                Outcome.PENDING -> pending++
                Outcome.MISSING -> missing++
                Outcome.RESOLVED -> {
                    val e = (f.valueAt(h) - value).toDouble()
                    n++
                    sumAbs += abs(e)
                    sum += e
                    sumSq += e * e
                    noChange += abs(f.baselineMgdl - value)
                }
            }
        }
        return HorizonAccuracy(
            horizonMinutes = h,
            count = n,
            meanAbsoluteError = mean(sumAbs, n),
            bias = mean(sum, n),
            rootMeanSquareError = if (n == 0) Float.NaN else sqrt(sumSq / n).toFloat(),
            noChangeMeanAbsoluteError = mean(noChange, n),
            pending = pending,
            missing = missing,
        )
    }

    private fun mean(sum: Double, n: Int): Float = if (n == 0) Float.NaN else (sum / max(1, n)).toFloat()
}
