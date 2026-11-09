package tk.glucodata.data.prediction.analysis

import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.prediction.GlucosePredictionPoint
import tk.glucodata.data.prediction.PredictionModelParameters
import tk.glucodata.data.prediction.PredictionModelProfile
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.buildPredictionSeriesForChart
import tk.glucodata.ui.util.GlucoseFormatter
import kotlin.math.abs

/**
 * Past meals replayed with any coefficients (#722, plan PR 1).
 *
 * A what-if can be recomputed from history and the journal, so this answers "would other
 * values have fitted my meals better" without recording anything or waiting. Every forecast
 * here comes from [forecast], which is the recorder's path — [buildPredictionSeriesForChart]
 * and [PredictionRecorder.primarySeries] — with only the profile swapped.
 *
 * Meals are isolated the way the feasibility study isolated them, so a miss belongs to one
 * meal: at least [MIN_CARBS_GRAMS], no other meal in the [CLEAR_BEFORE_MS] before it, and none
 * within [SCORE_AFTER_MINUTES] after it. Every treatment logged in the window counts, as the
 * study scored it, so a dose taken two hours in is part of the replay: this tests the
 * coefficients, not what the app could have known at the time.
 *
 * Nothing here writes anywhere. Trial values live in the caller's state and reach no other
 * reader of the profile.
 */
object MealReplay {
    private const val MINUTE_MS = 60_000L

    const val MIN_CARBS_GRAMS = 5f
    const val CLEAR_BEFORE_MS = 120 * MINUTE_MS

    /** Where a meal is scored: the study's window, and where the meal check (PR 3) will look. */
    const val SCORE_AFTER_MINUTES = 240

    /** How far each what-if is drawn: the longest forecast the app makes. */
    const val DRAW_AFTER_MINUTES = 360

    /** Readings fed to each what-if: the same span the recorder feeds it. */
    const val HISTORY_BEFORE_MS = 180 * MINUTE_MS

    /** Readings drawn before the meal, for context. */
    const val DRAW_BEFORE_MS = 60 * MINUTE_MS

    /** Treatments this old still reach the forecast, as on the dashboard. */
    const val JOURNAL_LOOKBACK_MS = 36 * 60 * MINUTE_MS

    /** Carb entries this close together are one meal: two foods logged for the same plate. */
    const val SAME_MEAL_MS = 10 * MINUTE_MS

    /** A what-if starts from a reading at most this long before the meal. */
    const val MAX_START_GAP_MS = 10 * MINUTE_MS

    /** A reading at most this far from the 4-hour mark scores the meal. */
    const val MAX_OUTCOME_GAP_MS = 10 * MINUTE_MS

    data class Meal(val timestamp: Long, val grams: Float)

    /** A replayed what-if, in mg/dL, and how it compared with the reading 4 hours after the meal. */
    data class Replay(
        val points: List<GlucosePredictionPoint>,
        val forecastAtScoreMgdl: Float,
        val actualAtScoreMgdl: Float?,
    ) {
        val missMgdl: Float? get() = actualAtScoreMgdl?.let { abs(forecastAtScoreMgdl - it) }
    }

    /**
     * Carb entries grouped into meals, oldest first. An entry within [SAME_MEAL_MS] of the
     * previous entry joins its meal, so a meal logged food by food stays one meal however
     * long the run; the meal keeps the time of its first entry.
     */
    fun meals(entries: List<JournalEntry>): List<Meal> {
        val carbs = entries
            .filter { it.type == JournalEntryType.CARBS && (it.amount ?: 0f) > 0f }
            .sortedBy { it.timestamp }
        val out = ArrayList<Meal>()
        var previousEntryAt = Long.MIN_VALUE
        for (entry in carbs) {
            val last = out.lastOrNull()
            if (last != null && entry.timestamp - previousEntryAt <= SAME_MEAL_MS) {
                out[out.lastIndex] = last.copy(grams = last.grams + entry.amount!!)
            } else {
                out += Meal(entry.timestamp, entry.amount!!)
            }
            previousEntryAt = entry.timestamp
        }
        return out
    }

    /**
     * Meals that can be scored alone by [nowMs]. Only meals of [MIN_CARBS_GRAMS] or more count
     * as neighbours, as in the study: a smaller snack nearby does not drop a meal, and the
     * what-if still includes it, since it reads every logged treatment.
     */
    fun isolatedMeals(entries: List<JournalEntry>, nowMs: Long): List<Meal> {
        val all = meals(entries).filter { it.grams >= MIN_CARBS_GRAMS }
        val window = SCORE_AFTER_MINUTES * MINUTE_MS
        return all.filterIndexed { index, meal ->
            meal.timestamp + window <= nowMs &&
                (index == 0 || meal.timestamp - all[index - 1].timestamp >= CLEAR_BEFORE_MS) &&
                (index == all.lastIndex || all[index + 1].timestamp - meal.timestamp > window)
        }
    }

    /** The journal a meal's what-if reads: what the dashboard would read, plus the rest of the window. */
    fun journalFor(meal: Meal, entries: List<JournalEntry>): List<JournalEntry> {
        val from = meal.timestamp - JOURNAL_LOOKBACK_MS
        val to = meal.timestamp + DRAW_AFTER_MINUTES * MINUTE_MS
        return entries.filter { it.timestamp in from..to }
    }

    /** Units of mealtime insulin logged from half an hour before the meal to the scoring mark. */
    fun mealInsulinUnits(meal: Meal, entries: List<JournalEntry>, presets: Map<Long, JournalInsulinPreset>): Float {
        val from = meal.timestamp - 30 * MINUTE_MS
        val to = meal.timestamp + SCORE_AFTER_MINUTES * MINUTE_MS
        return entries
            .filter { it.type == JournalEntryType.INSULIN && it.timestamp in from..to }
            .filter { entry -> entry.insulinPresetId?.let(presets::get)?.countsTowardIob == true }
            .sumOf { (it.amount ?: 0f).toDouble() }
            .toFloat()
    }

    /** Readings from [fromMs] to [toMs] of a timestamp-sorted list. */
    fun slice(history: List<GlucosePoint>, fromMs: Long, toMs: Long): List<GlucosePoint> {
        var low = 0
        var high = history.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (history[mid].timestamp < fromMs) low = mid + 1 else high = mid
        }
        val out = ArrayList<GlucosePoint>()
        var index = low
        while (index < history.size && history[index].timestamp <= toMs) {
            out += history[index]
            index++
        }
        return out
    }

    /** True when [history] ends close enough before the meal for a what-if to start there. */
    fun canStart(meal: Meal, history: List<GlucosePoint>): Boolean {
        val last = history.lastOrNull { it.timestamp <= meal.timestamp && it.value.isFinite() && it.value > 0.1f }
            ?: return false
        return history.size >= 2 && meal.timestamp - last.timestamp <= MAX_START_GAP_MS
    }

    /**
     * The what-if the recorder would have kept for this history, journal and settings, with
     * [profile] in place of the saved one. Display unit in, display unit out; null when the
     * chart would draw no forecast.
     */
    fun forecast(
        history: List<GlucosePoint>,
        viewMode: Int,
        journal: List<JournalEntry>,
        presets: Map<Long, JournalInsulinPreset>,
        unit: String,
        targetLow: Float,
        targetHigh: Float,
        settings: PredictiveSimulationSettings,
        profile: PredictionModelProfile,
    ): List<GlucosePredictionPoint>? {
        val series = buildPredictionSeriesForChart(
            points = history,
            viewMode = viewMode,
            journalEntries = journal,
            insulinPresetsById = presets,
            unit = unit,
            targetLow = targetLow,
            targetHigh = targetHigh,
            settings = settings.copy(
                enabled = true,
                adaptive = false,
                horizonMinutes = DRAW_AFTER_MINUTES,
                modelProfile = profile,
            ),
        )
        return PredictionRecorder.primarySeries(series, viewMode)?.points?.takeIf { it.size >= 2 }
    }

    /** A what-if in display units, scored against [readings] (display units) 4 hours after the meal. */
    fun score(meal: Meal, forecast: List<GlucosePredictionPoint>, readings: List<GlucosePoint>, isMmol: Boolean): Replay? {
        val mark = meal.timestamp + SCORE_AFTER_MINUTES * MINUTE_MS
        val atMark = interpolate(forecast, mark) ?: return null
        val actual = readings
            .filter { it.value.isFinite() && it.value > 0.1f && abs(it.timestamp - mark) <= MAX_OUTCOME_GAP_MS }
            .minByOrNull { abs(it.timestamp - mark) }
        return Replay(
            points = forecast.map {
                it.copy(value = toMgdl(it.value, isMmol), unclampedValue = toMgdl(it.unclampedValue, isMmol))
            },
            forecastAtScoreMgdl = toMgdl(atMark, isMmol),
            actualAtScoreMgdl = actual?.let { toMgdl(it.value, isMmol) },
        )
    }

    /** The saved profile with trial values in the given blocks, keyed by block start minute. */
    fun trialProfile(saved: PredictionModelProfile, trials: Map<Int, PredictionModelParameters>): PredictionModelProfile =
        trials.entries.fold(saved) { profile, (start, values) ->
            profile.updateBlock(
                startMinuteOfDay = start,
                carbRatioGramsPerUnit = values.carbRatioGramsPerUnit,
                insulinSensitivityMgDlPerUnit = values.insulinSensitivityMgDlPerUnit,
            )
        }

    /** Start minute of the profile block that holds [timestamp]. */
    fun blockStartAt(profile: PredictionModelProfile, timestamp: Long, timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int {
        val calendar = java.util.Calendar.getInstance(timeZone).apply { timeInMillis = timestamp }
        val minute = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        return profile.blocks.lastOrNull { it.startMinuteOfDay <= minute }?.startMinuteOfDay ?: 0
    }

    private fun interpolate(points: List<GlucosePredictionPoint>, at: Long): Float? {
        if (points.isEmpty() || at < points.first().timestamp || at > points.last().timestamp) return null
        for (index in 1 until points.size) {
            val a = points[index - 1]
            val b = points[index]
            if (at <= b.timestamp) {
                val span = (b.timestamp - a.timestamp).toFloat()
                if (span <= 0f) return b.value
                return a.value + (b.value - a.value) * ((at - a.timestamp) / span)
            }
        }
        return points.last().value
    }

    private fun toMgdl(value: Float, isMmol: Boolean) = if (isMmol) value * GlucoseFormatter.MGDL_PER_MMOL else value
}
