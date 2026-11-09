package tk.glucodata.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DataSmoothing
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorIdentity
import tk.glucodata.data.HistoryRepository
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.data.prediction.PredictionModelParameters
import tk.glucodata.data.prediction.PredictionModelProfile
import tk.glucodata.data.prediction.PredictionModelProfileStore
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.data.prediction.analysis.MealReplay
import tk.glucodata.settings.SettingsRegistry
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.buildSmoothedConsumerHistory
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.util.inDisplayUnit

/** One replayable meal and what was replayed for it. Glucose in mg/dL. */
data class ReplayedMeal(
    val meal: MealReplay.Meal,
    val blockStart: Int,
    val insulinUnits: Float,
    val readingsMgdl: List<Pair<Long, Float>>,
    val saved: MealReplay.Replay,
    /** Null while no trial value differs from the saved one in this meal's block. */
    val trial: MealReplay.Replay?,
)

/** A profile block's meals, summarised. Misses are mean absolute differences 4 hours after the meal, in mg/dL. */
data class ReplayBlock(
    val startMinute: Int,
    val endMinute: Int,
    val saved: PredictionModelParameters,
    val trial: PredictionModelParameters,
    val scoredMeals: Int,
    val savedMissMgdl: Float?,
    val trialMissMgdl: Float?,
    val flatMissMgdl: Float?,
)

data class MealReplayState(
    val meals: List<ReplayedMeal>,
    val blocks: List<ReplayBlock>,
)

/**
 * State for Past meals: the journal's isolated meals, each replayed with the saved profile and
 * with the screen's trial values. Reads history, journal and profile; writes nothing.
 */
class MealReplayViewModel : ViewModel() {

    /** Null while loading. */
    private val _state = MutableStateFlow<MealReplayState?>(null)
    val state = _state.asStateFlow()

    private val _selected = MutableStateFlow(-1)
    val selected = _selected.asStateFlow()

    private var inputs: Inputs? = null
    private var trials: Map<Int, PredictionModelParameters> = emptyMap()
    private var trialJob: Job? = null

    private class Case(
        val meal: MealReplay.Meal,
        val blockStart: Int,
        val history: List<GlucosePoint>,
        val readings: List<GlucosePoint>,
        val journal: List<JournalEntry>,
        val insulinUnits: Float,
        val saved: MealReplay.Replay,
    )

    private class Inputs(
        val saved: PredictionModelProfile,
        val isMmol: Boolean,
        val cases: List<Case>,
        val forecast: (Case, PredictionModelProfile) -> MealReplay.Replay?,
    )

    fun load() {
        if (_state.value != null || inputs != null) return
        viewModelScope.launch {
            val loaded = runCatching { withContext(Dispatchers.Default) { loadInputs() } }
                .onFailure { Log.stack(LOG_ID, "load", it) }
                .getOrNull()
            inputs = loaded
            publish()
            val meals = _state.value?.meals.orEmpty()
            _selected.value = meals.lastIndex
        }
    }

    fun select(index: Int) {
        val meals = _state.value?.meals ?: return
        if (index in meals.indices) _selected.value = index
    }

    fun setTrial(blockStart: Int, carbRatio: Float? = null, sensitivityMgdl: Float? = null) {
        val current = inputs ?: return
        val base = trials[blockStart] ?: current.saved.parametersAtMinute(blockStart)
        trials = trials + (blockStart to base.copy(
            carbRatioGramsPerUnit = carbRatio ?: base.carbRatioGramsPerUnit,
            insulinSensitivityMgDlPerUnit = sensitivityMgdl ?: base.insulinSensitivityMgDlPerUnit,
        ))
        recomputeSoon()
    }

    fun resetTrial(blockStart: Int) {
        if (blockStart !in trials) return
        trials = trials - blockStart
        recomputeSoon()
    }

    private fun recomputeSoon() {
        trialJob?.cancel()
        trialJob = viewModelScope.launch {
            delay(TRIAL_DEBOUNCE_MS)
            withContext(Dispatchers.Default) { publish() }
        }
    }

    private fun publish() {
        val current = inputs
        if (current == null) {
            _state.value = MealReplayState(emptyList(), emptyList())
            return
        }
        val trialProfile = MealReplay.trialProfile(current.saved, trials)
        val changed = current.saved.blocks.map { it.startMinuteOfDay }.filterTo(mutableSetOf()) { start ->
            trialProfile.parametersAtMinute(start) != current.saved.parametersAtMinute(start)
        }
        val meals = current.cases.map { case ->
            ReplayedMeal(
                meal = case.meal,
                blockStart = case.blockStart,
                insulinUnits = case.insulinUnits,
                readingsMgdl = case.readings.map {
                    it.timestamp to if (current.isMmol) it.value * GlucoseFormatter.MGDL_PER_MMOL else it.value
                },
                saved = case.saved,
                trial = if (case.blockStart in changed) current.forecast(case, trialProfile) else null,
            )
        }
        val blocks = current.saved.blocks.mapIndexedNotNull { index, block ->
            val inBlock = meals.filter { it.blockStart == block.startMinuteOfDay }
            if (inBlock.isEmpty()) return@mapIndexedNotNull null
            val scored = inBlock.filter { it.saved.actualAtScoreMgdl != null }
            fun mean(values: List<Float>) = values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            ReplayBlock(
                startMinute = block.startMinuteOfDay,
                endMinute = current.saved.endMinuteFor(index),
                saved = block.parameters,
                trial = trialProfile.parametersAtMinute(block.startMinuteOfDay),
                scoredMeals = scored.size,
                savedMissMgdl = mean(scored.mapNotNull { it.saved.missMgdl }),
                trialMissMgdl = mean(scored.mapNotNull { (it.trial ?: it.saved).missMgdl }),
                flatMissMgdl = mean(scored.mapNotNull { meal ->
                    meal.saved.actualAtScoreMgdl?.let { kotlin.math.abs(it - meal.saved.points.first().value) }
                }),
            )
        }
        _state.value = MealReplayState(meals, blocks)
    }

    /** Blocking: reads the database. Off the main thread only. */
    private fun loadInputs(): Inputs? {
        val context = Applic.app ?: return null
        val prefs = context.getSharedPreferences(SettingsRegistry.FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(JOURNAL_KEY, true)) return null
        val serial = SensorIdentity.resolveMainSensor() ?: return null
        val now = System.currentTimeMillis()
        val from = now - PERIOD_DAYS * DAY_MS

        val (entries, presets) = kotlinx.coroutines.runBlocking {
            val repository = JournalRepository()
            repository.getEntriesBetweenSnapshot(from - MealReplay.JOURNAL_LOOKBACK_MS, now) to
                repository.getInsulinPresetsSnapshot().associateBy(JournalInsulinPreset::id)
        }
        val meals = MealReplay.isolatedMeals(entries, now).filter { it.timestamp >= from }
        val isMmol = GlucoseFormatter.isMmolApp()
        if (meals.isEmpty()) return Inputs(PredictionModelProfileStore.load(prefs), isMmol, emptyList()) { _, _ -> null }

        val unit = if (isMmol) "mmol/L" else "mg/dL"
        val history = kotlinx.coroutines.runBlocking {
            HistoryRepository().loadMergedWindow(serial, meals.first().timestamp - MealReplay.HISTORY_BEFORE_MS, now)
        }.sortedBy { it.timestamp }.inDisplayUnit(isMmol)
        val smoothing = DataSmoothing.localSmoothingMinutes(context)
        val collapse = DataSmoothing.localCollapseMinutes(context)
        fun consumer(points: List<GlucosePoint>) = buildSmoothedConsumerHistory(points, smoothing, collapse)

        val saved = PredictionModelProfileStore.load(prefs)
        val settings = PredictiveSimulationSettings(
            enabled = true,
            trendMomentumEnabled = SettingsRegistry.PREDICTION_TREND_MOMENTUM.readBool(prefs),
            carbAbsorptionGramsPerHour = SettingsRegistry.PREDICTION_CARB_ABSORPTION.readFloatClamped(prefs),
            foodMacrosEnabled = prefs.getBoolean(FOOD_MACROS_KEY, false),
            modelProfile = saved,
        )
        val viewMode = CurrentDisplaySource.resolveViewModeForSensor(serial)
        val targetLow = runCatching { Natives.targetlow() }.getOrDefault(Float.NaN)
        val targetHigh = runCatching { Natives.targethigh() }.getOrDefault(Float.NaN)

        fun replay(meal: MealReplay.Meal, history: List<GlucosePoint>, readings: List<GlucosePoint>, journal: List<JournalEntry>, profile: PredictionModelProfile) =
            MealReplay.forecast(history, viewMode, journal, presets, unit, targetLow, targetHigh, settings, profile)
                ?.let { MealReplay.score(meal, it, readings, isMmol) }

        val cases = meals.mapNotNull { meal ->
            val before = consumer(MealReplay.slice(history, meal.timestamp - MealReplay.HISTORY_BEFORE_MS, meal.timestamp))
            if (!MealReplay.canStart(meal, before)) return@mapNotNull null
            val readings = consumer(
                MealReplay.slice(
                    history,
                    meal.timestamp - MealReplay.DRAW_BEFORE_MS,
                    meal.timestamp + MealReplay.DRAW_AFTER_MINUTES * MINUTE_MS,
                )
            )
            val journal = MealReplay.journalFor(meal, entries)
            val replayed = replay(meal, before, readings, journal, saved) ?: return@mapNotNull null
            Case(
                meal = meal,
                blockStart = MealReplay.blockStartAt(saved, meal.timestamp),
                history = before,
                readings = readings,
                journal = journal,
                insulinUnits = MealReplay.mealInsulinUnits(meal, entries, presets),
                saved = replayed,
            )
        }
        return Inputs(saved, isMmol, cases) { case, profile ->
            replay(case.meal, case.history, case.readings, case.journal, profile)
        }
    }

    private companion object {
        const val LOG_ID = "MealReplay"
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * MINUTE_MS
        /** The journal's meals from this far back are replayed. */
        const val PERIOD_DAYS = 90L
        const val TRIAL_DEBOUNCE_MS = 150L
        const val JOURNAL_KEY = "dashboard_journal_enabled"
        const val FOOD_MACROS_KEY = "dashboard_journal_food_macros_enabled"
    }
}
