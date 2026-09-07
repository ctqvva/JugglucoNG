package tk.glucodata

import android.content.Context
import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.data.HistoryRepository
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.data.prediction.PredictionModelProfileStore
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.ui.buildPredictionSeriesForChart
import tk.glucodata.ui.buildSmoothedConsumerHistory
import java.util.Locale
import kotlin.math.round

/** Export the dashboard's curves as display data, never as measured glucose. */
@Keep
object GluciferPredictionSnapshot {
    @JvmStatic
    fun snapshotJson(context: Context, sensorId: String?, glucoseTime: Long): String = runBlocking(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        if (sensorId.isNullOrBlank() || !prefs.getBoolean("dashboard_predictive_simulation_enabled", true) ||
            System.currentTimeMillis() - glucoseTime > 600_000L) return@runBlocking "[]"
        val history = HistoryRepository().getHistoryForSensor(sensorId, glucoseTime - 3_600_000L)
            .filter { it.timestamp <= glucoseTime }
        val minutes = DataSmoothing.localSmoothingMinutes(context)
        val points = buildSmoothedConsumerHistory(history, minutes, minutes > 0 && DataSmoothing.collapseChunks(context))
        val baseline = points.lastOrNull()?.timestamp ?: return@runBlocking "[]"
        if (glucoseTime - baseline > 120_000L) return@runBlocking "[]"
        val horizon = prefs.getInt("dashboard_prediction_horizon_minutes", 120).coerceIn(30, 360)
        val journalEnabled = prefs.getBoolean("dashboard_journal_enabled", true)
        val repository = JournalRepository()
        // Read fresh on every live export so journal saves immediately affect the curves.
        val entries = if (journalEnabled) repository.getEntriesBetweenSnapshot(
            baseline - 36 * 3_600_000L, baseline + horizon * 60_000L
        ) else emptyList()
        val presets = if (journalEnabled) repository.getInsulinPresetsSnapshot().associateBy { it.id } else emptyMap()
        val scale = if (Applic.unit == 1) 18.0182f else 1f
        val low = runCatching { Natives.targetlow() * scale }.getOrDefault(70f).takeIf { it > 0 } ?: 70f
        val high = runCatching { Natives.targethigh() * scale }.getOrDefault(180f).takeIf { it > 0 } ?: 180f
        val series = buildPredictionSeriesForChart(
            points, CurrentDisplaySource.resolveViewModeForSensor(sensorId), entries, presets, "mg/dL", low, high,
            PredictiveSimulationSettings(
                enabled = true,
                trendMomentumEnabled = prefs.getBoolean("dashboard_prediction_trend_momentum_enabled", true),
                horizonMinutes = horizon,
                foodMacrosEnabled = journalEnabled && prefs.getBoolean("dashboard_journal_food_macros_enabled", false),
                modelProfile = PredictionModelProfileStore.load(prefs)
            )
        )
        JSONArray().apply {
            series.forEach { curve ->
                put(JSONObject().put("kind", curve.kind.name.lowercase(Locale.ROOT))
                    .put("points", JSONArray().apply {
                        curve.points.forEach { point ->
                            put(JSONObject().put("time_ms", point.timestamp)
                                .put("mgdl", round(point.value.toDouble() * 10.0) / 10.0))
                        }
                    }))
            }
        }.toString()
    }
}
