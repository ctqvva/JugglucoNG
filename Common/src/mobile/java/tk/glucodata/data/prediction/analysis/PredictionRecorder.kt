package tk.glucodata.data.prediction.analysis

import android.content.Context
import kotlinx.coroutines.runBlocking
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DataSmoothing
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.PredictionAnalysisBridge
import tk.glucodata.SensorIdentity
import tk.glucodata.data.HistoryRepository
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.data.prediction.AdaptiveGlucoseForecast
import tk.glucodata.data.prediction.GlucosePredictionSeries
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictionModelProfileStore
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.logic.TrendEngine
import tk.glucodata.settings.SettingsRegistry
import tk.glucodata.ui.buildPredictionSeriesForChart
import tk.glucodata.ui.buildSmoothedConsumerHistory
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.util.inDisplayUnit
import kotlin.math.abs

/**
 * Records forecasts while prediction analysis is on.
 *
 * Driven by the reading pipeline, not by a timer: the main sensor's live reading arrives
 * through [onRealtimeReading], and at most once per [MIN_SPACING_MS] a recording is queued on
 * [PredictionAnalysisWork]'s thread. With analysis off this returns after one preference read.
 *
 * What is recorded is what the app would draw at that moment, computed by the same functions
 * the dashboard and the notification chart use, from the history, journal and settings as they
 * are then. Both forecasts are kept: the standard one and the adaptive one. Whichever was on the
 * chart is marked [RecordedForecastEntity.shown]; the other is a shadow, recorded from the same
 * inputs so the two can be compared fairly. Nothing recorded is recomputed later.
 */
object PredictionRecorder : PredictionAnalysisBridge {
    private const val LOG_ID = "PredictionRecorder"
    private const val MINUTE_MS = 60_000L

    /** One recording per five minutes: a forecast per reading would only repeat itself on 1-minute sensors. */
    internal const val MIN_SPACING_MS = 4 * MINUTE_MS + 30_000L

    /** Give the reading time to reach the history database first. */
    private const val DELAY_MS = 20_000L

    /** Readings this far from now are history being replayed, not a live forecast moment. */
    private const val LIVE_WINDOW_MS = 5 * MINUTE_MS

    const val RETENTION_DAYS = 90
    private const val CLEANUP_EVERY_MS = 24L * 60 * MINUTE_MS
    private const val HISTORY_SPAN_MS = 3L * 60 * MINUTE_MS
    private const val JOURNAL_LOOKBACK_MS = 36L * 60 * MINUTE_MS

    /** Bumped whenever the standard forecast's arithmetic changes, so records say which one they came from. */
    const val STANDARD_MODEL_VERSION = 1

    @Volatile
    private var lastQueuedReadingMs = 0L

    @Volatile
    private var lastCleanupMs = 0L

    fun isEnabled(context: Context): Boolean =
        SettingsRegistry.PREDICTION_ANALYSIS.readBool(
            context.getSharedPreferences(SettingsRegistry.FILE, Context.MODE_PRIVATE)
        )

    override fun onRealtimeReading(sensorSerial: String?, readingTimeMs: Long) {
        val context = Applic.app ?: return
        if (!isEnabled(context)) return
        if (!shouldQueue(readingTimeMs, System.currentTimeMillis(), lastQueuedReadingMs)) return
        lastQueuedReadingMs = readingTimeMs
        PredictionAnalysisWork.schedule(DELAY_MS) {
            runCatching { recordNow(context, System.currentTimeMillis()) }
                .onFailure { Log.stack(LOG_ID, "record", it) }
        }
    }

    /** A live reading at least [MIN_SPACING_MS] after the last one that queued a recording. */
    internal fun shouldQueue(readingTimeMs: Long, nowMs: Long, lastQueuedMs: Long): Boolean =
        abs(nowMs - readingTimeMs) <= LIVE_WINDOW_MS && readingTimeMs - lastQueuedMs >= MIN_SPACING_MS

    /**
     * Records the forecasts for the newest reading, if it is fresh and not already recorded.
     * Blocking; on the worker thread only. Returns the number of forecasts stored.
     */
    internal fun recordNow(context: Context, nowMs: Long): Int {
        // Switched off between the reading and this run: record nothing.
        if (!isEnabled(context)) return 0
        val serial = SensorIdentity.resolveMainSensor() ?: return 0
        val prefs = context.getSharedPreferences(SettingsRegistry.FILE, Context.MODE_PRIVATE)
        val isMmol = GlucoseFormatter.isMmolApp()
        val unit = if (isMmol) "mmol/L" else "mg/dL"
        // The merged timeline the dashboard chart draws, not only the main sensor's rows: just
        // after a sensor switch the chart still shows the previous sensor's last readings, and
        // a recording marked as shown must be the curve that was shown.
        val history = runBlocking { HistoryRepository().loadMergedWindow(serial, nowMs - HISTORY_SPAN_MS, Long.MAX_VALUE) }
            .sortedBy { it.timestamp }
            .inDisplayUnit(isMmol)
        val newest = history.lastOrNull { it.value.isFinite() && it.value > 0.1f } ?: return 0
        if (nowMs - newest.timestamp > LIVE_WINDOW_MS + DELAY_MS) return 0

        val dao = PredictionAnalysisDatabase.getInstance(context).recordedForecastDao()
        val latest = runBlocking { dao.latestOrigin(ForecastModel.STANDARD.storageId) }
        if (latest != null && newest.timestamp - latest < MIN_SPACING_MS) return 0

        val journalEnabled = prefs.getBoolean(JOURNAL_KEY, true)
        val horizon = SettingsRegistry.PREDICTION_HORIZON.readIntClamped(prefs)
        val (entries, presets) = if (journalEnabled) {
            loadJournal(newest.timestamp - JOURNAL_LOOKBACK_MS, newest.timestamp + horizon * MINUTE_MS)
        } else {
            emptyList<JournalEntry>() to emptyMap()
        }
        val settings = PredictiveSimulationSettings(
            enabled = true,
            trendMomentumEnabled = SettingsRegistry.PREDICTION_TREND_MOMENTUM.readBool(prefs),
            horizonMinutes = horizon,
            carbAbsorptionGramsPerHour = SettingsRegistry.PREDICTION_CARB_ABSORPTION.readFloatClamped(prefs),
            foodMacrosEnabled = journalEnabled && prefs.getBoolean(FOOD_MACROS_KEY, false),
            modelProfile = PredictionModelProfileStore.load(prefs),
        )
        val consumer = buildSmoothedConsumerHistory(
            points = history,
            evaluationSmoothingMinutes = DataSmoothing.localSmoothingMinutes(context),
            evaluationCollapseMinutes = DataSmoothing.localCollapseMinutes(context),
        )
        val viewMode = CurrentDisplaySource.resolveViewModeForSensor(serial)
        val targetLow = runCatching { Natives.targetlow() }.getOrDefault(Float.NaN)
        val targetHigh = runCatching { Natives.targethigh() }.getOrDefault(Float.NaN)
        fun series(adaptive: Boolean) = buildPredictionSeriesForChart(
            points = consumer,
            viewMode = viewMode,
            journalEntries = entries,
            insulinPresetsById = presets,
            unit = unit,
            targetLow = targetLow,
            targetHigh = targetHigh,
            settings = settings.copy(adaptive = adaptive),
        )
        val standard = primarySeries(series(adaptive = false), viewMode) ?: return 0
        val adaptive = primarySeries(series(adaptive = true), viewMode)

        val predictionShown = SettingsRegistry.PREDICTION_ENABLED.readBool(prefs)
        val adaptiveShown = SettingsRegistry.PREDICTION_ADAPTIVE.readBool(prefs)
        val origin = standard.points.first().timestamp
        val sensor = SensorIdentity.resolveRoomStorageSensorId(newest.sensorSerial ?: serial) ?: serial
        val lane = laneOf(standard.kind, viewMode)
        val trend = runCatching {
            TrendEngine.calculateTrend(consumer.takeLast(30), useRaw = false, isMmol = isMmol).velocity
        }.getOrNull()
        val planned = entries.count { it.timestamp > origin }

        var stored = 0
        runBlocking {
            stored += dao.insert(
                entity(standard, ForecastModel.STANDARD, STANDARD_MODEL_VERSION, settings.copy(adaptive = false).hashCode().toLong(),
                    shown = predictionShown && !adaptiveShown, origin, nowMs, sensor, lane, isMmol, trend, planned, entries.size)
            ).let { if (it > 0) 1 else 0 }
            if (adaptive != null && adaptive.points.first().timestamp == origin) {
                val model = AdaptivePredictionStore.model(context)
                stored += dao.insert(
                    entity(adaptive, ForecastModel.ADAPTIVE, AdaptiveGlucoseForecast.MODEL_VERSION, model?.trainedAtMs ?: 0L,
                        shown = predictionShown && adaptiveShown, origin, nowMs, sensor, lane, isMmol, trend, planned, entries.size)
                ).let { if (it > 0) 1 else 0 }
            }
            if (nowMs - lastCleanupMs >= CLEANUP_EVERY_MS) {
                lastCleanupMs = nowMs
                dao.deleteOlderThan(nowMs - RETENTION_DAYS * 24L * 60 * MINUTE_MS)
            }
        }
        return stored
    }

    /** The series the chart draws as the main forecast: calibrated where calibration is on, else the view's lane. */
    internal fun primarySeries(series: List<GlucosePredictionSeries>, viewMode: Int): GlucosePredictionSeries? {
        series.firstOrNull { it.kind == GlucosePredictionSeriesKind.CALIBRATED }?.let { return it }
        val rawMode = viewMode == 1 || viewMode == 3
        return series.firstOrNull { it.kind == if (rawMode) GlucosePredictionSeriesKind.RAW else GlucosePredictionSeriesKind.AUTO }
    }

    internal fun laneOf(kind: GlucosePredictionSeriesKind, viewMode: Int): Int {
        val rawMode = viewMode == 1 || viewMode == 3
        return when (kind) {
            GlucosePredictionSeriesKind.RAW -> ForecastLane.RAW
            GlucosePredictionSeriesKind.AUTO -> ForecastLane.AUTO
            GlucosePredictionSeriesKind.CALIBRATED -> if (rawMode) ForecastLane.CALIBRATED_RAW else ForecastLane.CALIBRATED_AUTO
        }
    }

    /** The series as a record: values in mg/dL at every 5 minutes from the origin. */
    internal fun entity(
        series: GlucosePredictionSeries,
        model: ForecastModel,
        modelVersion: Int,
        configHash: Long,
        shown: Boolean,
        origin: Long,
        recordedAt: Long,
        sensor: String,
        lane: Int,
        isMmol: Boolean,
        trendMgdlPerMin: Float?,
        plannedEntries: Int,
        journalEntries: Int,
    ): RecordedForecastEntity {
        val byTime = series.points.associateBy { it.timestamp }
        val lastMinutes = ((series.points.last().timestamp - origin) / MINUTE_MS).toInt()
        val steps = (lastMinutes / STEP_MINUTES).coerceAtLeast(0)
        val values = FloatArray(steps) { k ->
            byTime[origin + (k + 1) * STEP_MINUTES * MINUTE_MS]?.value?.let { toMgdl(it, isMmol) } ?: Float.NaN
        }
        return RecordedForecastEntity(
            originTime = origin,
            recordedAt = recordedAt,
            sensorSerial = sensor,
            lane = lane,
            model = model.storageId,
            modelVersion = modelVersion,
            configHash = configHash,
            shown = shown,
            fallback = series.adaptiveFallback,
            baselineMgdl = toMgdl(series.points.first().value, isMmol),
            stepMinutes = STEP_MINUTES,
            valuesMgdl = ForecastValues.encode(values),
            trendMgdlPerMin = trendMgdlPerMin?.takeIf { it.isFinite() },
            plannedEntries = plannedEntries,
            journalEntries = journalEntries,
        )
    }

    private fun loadJournal(fromMs: Long, toMs: Long): Pair<List<JournalEntry>, Map<Long, JournalInsulinPreset>> =
        runCatching {
            runBlocking {
                val repository = JournalRepository()
                repository.getEntriesBetweenSnapshot(fromMs, toMs) to
                    repository.getInsulinPresetsSnapshot().associateBy { it.id }
            }
        }.getOrElse { emptyList<JournalEntry>() to emptyMap() }

    private fun toMgdl(value: Float, isMmol: Boolean) = if (isMmol) value * GlucoseFormatter.MGDL_PER_MMOL else value

    private const val STEP_MINUTES = 5
    private const val JOURNAL_KEY = "dashboard_journal_enabled"
    private const val FOOD_MACROS_KEY = "dashboard_journal_food_macros_enabled"
}
