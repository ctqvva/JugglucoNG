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

internal fun buildNotificationPredictionHistory(
    source: List<NativeGlucosePoint>,
    calibrationSensorId: String?
): List<UiGlucosePoint> {
    // This formatter belongs to one calculation; SimpleDateFormat is not thread-safe.
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    return source.map { point ->
        UiGlucosePoint(
            value = point.value,
            rawValue = point.rawValue,
            timestamp = point.timestamp,
            time = timeFormat.format(Date(point.timestamp)),
            sensorSerial = point.sensorSerial?.takeIf { it.isNotBlank() } ?: calibrationSensorId,
            sealedDisplayValue = point.sealedDisplayValue.takeIf { it.isFinite() && it > 0.1f },
            sealedDisplayViewMode = point.sealedDisplayViewMode.takeIf { it >= 0 }
        )
    }
}

@Keep
object NotificationPredictionOverlay : NotificationPredictionBridge {
    private const val PREFS_NAME = tk.glucodata.settings.SettingsRegistry.FILE
    private const val NOTIFICATION_CHART_KEY = "dashboard_prediction_notification_chart_enabled"
    private const val JOURNAL_KEY = "dashboard_journal_enabled"
    private const val FOOD_MACROS_KEY = "dashboard_journal_food_macros_enabled"
    private const val JOURNAL_LOOKBACK_MS = 36L * 60L * 60L * 1000L
    private const val CACHE_MAX_AGE_MS = 30_000L

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
    override fun buildPredictionSeries(
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
        if (!tk.glucodata.settings.SettingsRegistry.PREDICTION_ENABLED.readBool(prefs) ||
            !prefs.getBoolean(NOTIFICATION_CHART_KEY, true)
        ) {
            return emptyList()
        }
        val source = data?.filter { point ->
            point.timestamp > 0L && ((point.value.isFinite() && point.value > 0.1f) || (point.rawValue.isFinite() && point.rawValue > 0.1f))
        }.orEmpty()
        if (source.size < 2) return emptyList()

        val unit = if (isMmol) "mmol/L" else "mg/dL"
        val uiHistory = buildNotificationPredictionHistory(source, calibrationSensorId)
        val baselineTime = uiHistory.lastOrNull { point ->
            point.timestamp > 0L && ((point.value.isFinite() && point.value > 0.1f) ||
                (point.rawValue.isFinite() && point.rawValue > 0.1f))
        }?.timestamp ?: return emptyList()
        val horizonMinutes = tk.glucodata.settings.SettingsRegistry.PREDICTION_HORIZON.readIntClamped(prefs)
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
                trendMomentumEnabled = tk.glucodata.settings.SettingsRegistry.PREDICTION_TREND_MOMENTUM.readBool(prefs),
                horizonMinutes = horizonMinutes,
                carbAbsorptionGramsPerHour =
                    tk.glucodata.settings.SettingsRegistry.PREDICTION_CARB_ABSORPTION.readFloatClamped(prefs),
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
