package tk.glucodata.data.prediction.analysis

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.data.HistoryRepository
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.PredictionHistorySource
import tk.glucodata.ui.buildDisplayHistoryForPrediction
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.util.inDisplayUnit

/** Everything the accuracy screen shows, loaded together. */
data class PredictionAnalysisSnapshot(
    val report: AccuracyReport,
    val storedForecasts: Int,
    val storedBytes: Long,
)

/** One recorded moment: its forecasts and the readings around it. */
data class ForecastMoment(
    val originTime: Long,
    val sensorSerial: String,
    val lane: Int,
    val standard: RecordedForecastEntity?,
    val adaptive: RecordedForecastEntity?,
    /** Readings of the forecast's sensor and lane, mg/dL, from an hour before to the end of the curve. */
    val actualTimes: LongArray,
    val actualValues: FloatArray,
    val actual: ActualGlucose,
)

/**
 * Reads recorded forecasts and the glucose that followed them.
 *
 * The forecasts come back exactly as stored. The outcomes are read from the history as it is
 * now — the readings of the forecast's own sensor, in its own lane — because an outcome is a
 * measurement, and the best record of it is the latest one.
 */
class PredictionAnalysisRepository(private val context: Context) {
    private val dao get() = PredictionAnalysisDatabase.getInstance(context).recordedForecastDao()

    suspend fun snapshot(periodDays: Int, nowMs: Long = System.currentTimeMillis()): PredictionAnalysisSnapshot =
        withContext(Dispatchers.IO) {
            // Retention holds even when nothing is being recorded any more.
            dao.deleteOlderThan(nowMs - PredictionRecorder.RETENTION_DAYS * DAY_MS)
            val from = nowMs - periodDays * DAY_MS
            val forecasts = dao.between(from, nowMs)
            val actual = actualFor(forecasts, from)
            PredictionAnalysisSnapshot(
                report = PredictionAccuracy.report(forecasts, actual, nowMs = nowMs),
                storedForecasts = dao.count(),
                storedBytes = PredictionAnalysisDatabase.sizeOnDisk(context),
            )
        }

    /** Every recorded moment in the period, oldest first: the inspector's timeline. */
    suspend fun origins(periodDays: Int, nowMs: Long = System.currentTimeMillis()): List<Long> =
        withContext(Dispatchers.IO) {
            dao.origins(nowMs - periodDays * DAY_MS, nowMs)
        }

    suspend fun moment(originTime: Long): ForecastMoment? = withContext(Dispatchers.IO) {
        val forecasts = dao.between(originTime, originTime)
        val standard = forecasts.firstOrNull { it.model == ForecastModel.STANDARD.storageId }
        val adaptive = forecasts.firstOrNull { it.model == ForecastModel.ADAPTIVE.storageId }
        val any = standard ?: adaptive ?: return@withContext null
        val longest = forecasts.maxOf { it.values.size * it.stepMinutes }
        val from = originTime - HOUR_MS
        // The table always shows all standard horizons, even for a shorter saved curve.
        val lastOutcome = originTime + maxOf(longest, PredictionAccuracy.DEFAULT_HORIZONS.max()) * MINUTE_MS
        val actual = actualFor(forecasts, from, lastOutcome + 20 * MINUTE_MS)
        val (times, values) = actual.readings(any.sensorSerial, any.lane, from, originTime + longest * MINUTE_MS)
        ForecastMoment(
            originTime = originTime,
            sensorSerial = any.sensorSerial,
            lane = any.lane,
            standard = standard,
            adaptive = adaptive,
            actualTimes = times,
            actualValues = values,
            actual = actual,
        )
    }

    suspend fun clear(): Int = withContext(Dispatchers.IO) { dao.deleteAll() }

    /** The readings each forecast is scored against, per sensor and per lane it was recorded in. */
    private suspend fun actualFor(forecasts: List<RecordedForecastEntity>, fromMs: Long, toMs: Long = Long.MAX_VALUE): ActualGlucose {
        val history = HistoryRepository()
        val isMmol = GlucoseFormatter.isMmolApp()
        val bySensor = HashMap<String, ActualGlucose.SensorLanes>()
        for ((sensor, list) in forecasts.groupBy { it.sensorSerial }) {
            val points = history.getHistoryForSensor(sensor, fromMs - HOUR_MS, toMs)
                .filter { it.timestamp > 0L }
                .sortedBy { it.timestamp }
            if (points.isEmpty()) continue
            val lanes = HashMap<Int, FloatArray>()
            for (lane in list.map { it.lane }.distinct()) {
                lanes[lane] = laneValues(points, lane, isMmol)
            }
            bySensor[sensor] = ActualGlucose.SensorLanes(LongArray(points.size) { points[it].timestamp }, lanes)
        }
        return ActualGlucose(bySensor)
    }

    /**
     * One lane's values in mg/dL. The calibrated lanes go through the chart's own function, so
     * an outcome is the number the chart draws for that minute.
     */
    private fun laneValues(points: List<GlucosePoint>, lane: Int, isMmol: Boolean): FloatArray = when (lane) {
        ForecastLane.AUTO -> FloatArray(points.size) { points[it].value }
        ForecastLane.RAW -> FloatArray(points.size) { points[it].rawValue }
        else -> {
            val source = if (lane == ForecastLane.CALIBRATED_RAW) PredictionHistorySource.CALIBRATED_RAW
            else PredictionHistorySource.CALIBRATED_AUTO
            val drawn = buildDisplayHistoryForPrediction(points.inDisplayUnit(isMmol), source)
            FloatArray(drawn.size) { if (isMmol) drawn[it].value * GlucoseFormatter.MGDL_PER_MMOL else drawn[it].value }
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS
    }
}
