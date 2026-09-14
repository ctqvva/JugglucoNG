package tk.glucodata

import android.content.Context
import android.os.Looper
import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictionModelProfileStore
import tk.glucodata.ui.buildPredictionSeriesForChart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import tk.glucodata.GlucosePoint as NativeGlucosePoint
import tk.glucodata.ui.GlucosePoint as UiGlucosePoint

@Keep
object NotificationPredictionOverlay {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val MASTER_KEY = "dashboard_predictive_simulation_enabled"
    private const val NOTIFICATION_CHART_KEY = "dashboard_prediction_notification_chart_enabled"
    private const val TREND_MOMENTUM_KEY = "dashboard_prediction_trend_momentum_enabled"
    private const val JOURNAL_KEY = "dashboard_journal_enabled"
    private const val FOOD_MACROS_KEY = "dashboard_journal_food_macros_enabled"
    private const val CARB_ABSORPTION_KEY = "dashboard_prediction_carb_absorption_g_per_h"
    private const val HORIZON_MINUTES_KEY = "dashboard_prediction_horizon_minutes"
    private const val JOURNAL_LOOKBACK_MS = 36L * 60L * 60L * 1000L
    private const val CACHE_MAX_AGE_MS = 30_000L
    private const val PREDICTION_CARB_ABSORPTION_DEFAULT = 35f
    private const val PREDICTION_HORIZON_MINUTES_DEFAULT = 120

    private data class JournalSnapshot(
        val loadedAt: Long,
        val startMillis: Long,
        val endMillis: Long,
        val entries: List<JournalEntry>,
        val presetsById: Map<Long, JournalInsulinPreset>
    )

    @Volatile
    private var cachedJournalSnapshot: JournalSnapshot? = null

    @Keep
    @JvmStatic
    fun buildPredictionSeries(
        context: Context,
        data: List<NativeGlucosePoint>?,
        isMmol: Boolean,
        viewMode: Int,
        hasCalibration: Boolean,
        calibrationSensorId: String?,
        targetLow: Float,
        targetHigh: Float
    ): List<NotificationPredictionSeries> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MASTER_KEY, true) || !prefs.getBoolean(NOTIFICATION_CHART_KEY, true)) {
            return emptyList()
        }
        val source = data?.filter { point ->
            point.timestamp > 0L && ((point.value.isFinite() && point.value > 0.1f) || (point.rawValue.isFinite() && point.rawValue > 0.1f))
        }.orEmpty()
        if (source.size < 2) return emptyList()

        val unit = if (isMmol) "mmol/L" else "mg/dL"
        // This formatter belongs to one calculation; SimpleDateFormat is not thread-safe.
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val uiHistory = source.map { point ->
            UiGlucosePoint(
                value = point.value,
                rawValue = point.rawValue,
                timestamp = point.timestamp,
                time = timeFormat.format(Date(point.timestamp)),
                sensorSerial = calibrationSensorId
            )
        }
        val baselineTime = uiHistory.lastOrNull { point ->
            point.timestamp > 0L && ((point.value.isFinite() && point.value > 0.1f) ||
                (point.rawValue.isFinite() && point.rawValue > 0.1f))
        }?.timestamp ?: return emptyList()
        val horizonMinutes = prefs
            .getInt(HORIZON_MINUTES_KEY, PREDICTION_HORIZON_MINUTES_DEFAULT)
            .coerceIn(30, 360)
        val journalSnapshot = if (prefs.getBoolean(JOURNAL_KEY, true)) {
            loadJournalSnapshot(baselineTime, horizonMinutes)
        } else {
            JournalSnapshot(
                loadedAt = System.currentTimeMillis(),
                startMillis = 0L,
                endMillis = 0L,
                entries = emptyList(),
                presetsById = emptyMap()
            )
        }

        val predictionSeries = buildPredictionSeriesForChart(
            points = uiHistory,
            viewMode = viewMode,
            journalEntries = journalSnapshot.entries,
            insulinPresetsById = journalSnapshot.presetsById,
            unit = unit,
            targetLow = targetLow,
            targetHigh = targetHigh,
            settings = PredictiveSimulationSettings(
                enabled = true,
                trendMomentumEnabled = prefs.getBoolean(TREND_MOMENTUM_KEY, true),
                horizonMinutes = horizonMinutes,
                carbAbsorptionGramsPerHour = prefs
                    .getFloat(CARB_ABSORPTION_KEY, PREDICTION_CARB_ABSORPTION_DEFAULT)
                    .coerceIn(10f, 90f),
                foodMacrosEnabled = prefs.getBoolean(JOURNAL_KEY, true) && prefs.getBoolean(FOOD_MACROS_KEY, false),
                modelProfile = PredictionModelProfileStore.load(prefs)
            )
        )
        return predictionSeries.mapNotNull { series ->
            if (series.kind == GlucosePredictionSeriesKind.CALIBRATED && !hasCalibration) {
                return@mapNotNull null
            }
            val kind = when (series.kind) {
                GlucosePredictionSeriesKind.RAW -> NotificationPredictionSeries.KIND_RAW
                GlucosePredictionSeriesKind.AUTO -> NotificationPredictionSeries.KIND_AUTO
                GlucosePredictionSeriesKind.CALIBRATED -> NotificationPredictionSeries.KIND_CALIBRATED
            }
            val points = series.points.map { point ->
                NotificationPredictionPoint(
                    point.timestamp,
                    point.value,
                    point.confidence
                )
            }
            points.takeIf { it.size >= 2 }?.let { NotificationPredictionSeries(kind, it) }
        }
    }

    private fun loadJournalSnapshot(baselineTime: Long, horizonMinutes: Int): JournalSnapshot {
        val start = baselineTime - JOURNAL_LOOKBACK_MS
        val end = baselineTime + horizonMinutes * 60_000L
        val now = System.currentTimeMillis()
        cachedJournalSnapshot?.let { snapshot ->
            if (now - snapshot.loadedAt <= CACHE_MAX_AGE_MS && snapshot.startMillis <= start && snapshot.endMillis >= end) {
                return snapshot
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            BatteryTrace.bump("notify.prediction.main_thread_journal_skip", 20L)
            return emptyJournalSnapshot(now, start, end)
        }

        return runCatching {
            runBlocking(Dispatchers.IO) {
                val repository = JournalRepository()
                val entries = repository.getEntriesBetweenSnapshot(start, end)
                val presets = repository.getInsulinPresetsSnapshot().associateBy { it.id }
                JournalSnapshot(
                    loadedAt = now,
                    startMillis = start,
                    endMillis = end,
                    entries = entries,
                    presetsById = presets
                )
            }
        }.getOrElse {
            emptyJournalSnapshot(now, start, end)
        }.also { snapshot ->
            cachedJournalSnapshot = snapshot
        }
    }

    private fun emptyJournalSnapshot(now: Long, start: Long, end: Long): JournalSnapshot {
        return JournalSnapshot(
            loadedAt = now,
            startMillis = start,
            endMillis = end,
            entries = emptyList(),
            presetsById = emptyMap()
        )
    }
}
