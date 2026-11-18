package tk.glucodata.data.prediction.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.prediction.GlucosePredictionSeries
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictionModelParameters
import tk.glucodata.data.prediction.PredictionModelProfile
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.data.prediction.buildGlucosePrediction
import tk.glucodata.ui.GlucosePoint
import java.util.TimeZone

/**
 * Past meals (#722, plan PR 1): which meals are replayed, and the promise that a replay with
 * the saved profile is the forecast the app draws and records, so a trial value is the only
 * thing that moves it.
 */
class MealReplayTests {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val utc = TimeZone.getTimeZone("UTC")

    /** Midday UTC, so block lookups below do not depend on the machine's zone. */
    private val t0 = 1_790_000_000_000L - 1_790_000_000_000L % (24 * hour) + 12 * hour

    private val rapid = JournalInsulinPreset(
        id = 1L,
        displayName = "Rapid",
        onsetMinutes = 15,
        durationMinutes = 300,
        accentColor = 0,
        curveJson = "0:0;75:1;300:0",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = true,
        sortOrder = 0,
    )
    private val presets = mapOf(rapid.id to rapid)

    private fun entry(id: Long, at: Long, type: JournalEntryType, amount: Float) = JournalEntry(
        id = id, timestamp = at, sensorSerial = null, type = type, title = "", note = null, amount = amount,
        glucoseValueMgDl = null, durationMinutes = null, intensity = null,
        insulinPresetId = if (type == JournalEntryType.INSULIN) rapid.id else null,
        foodId = null, proteinGrams = null, fatGrams = null, source = JournalEntrySource.MANUAL,
        sourceRecordId = null, createdAt = at, updatedAt = at,
    )

    private fun carbs(id: Long, at: Long, grams: Float) = entry(id, at, JournalEntryType.CARBS, grams)
    private fun insulin(id: Long, at: Long, units: Float) = entry(id, at, JournalEntryType.INSULIN, units)

    /** Readings every 5 minutes from three hours before [mealAt] to six after, rising and falling like a meal. */
    private fun readings(mealAt: Long): List<GlucosePoint> = (-36..72).map { k ->
        val t = mealAt + k * 5 * minute
        val afterMinutes = (k * 5).coerceAtLeast(0)
        val v = 110f + 70f * kotlin.math.sin(Math.PI * afterMinutes.coerceAtMost(240) / 240.0).toFloat()
        GlucosePoint(value = v, time = "", timestamp = t, rawValue = v, sensorSerial = "S1")
    }

    private val settings = PredictiveSimulationSettings(
        enabled = true,
        trendMomentumEnabled = true,
        profileTimeZone = utc,
    )
    private val saved = PredictionModelProfile.single(carbRatioGramsPerUnit = 10f, insulinSensitivityMgDlPerUnit = 50f)

    @Test
    fun onlyMealsAloneInTheirWindowAreReplayed() {
        val entries = listOf(
            carbs(1, t0, 40f),                    // alone: kept
            carbs(2, t0 + 5 * hour, 30f),         // another meal 1.5 h after it: dropped
            carbs(3, t0 + 6 * hour + 30 * minute, 12f), // and 1.5 h after that one: dropped
            carbs(4, t0 + 12 * hour, 3f),         // under 5 g: not a meal...
            carbs(5, t0 + 13 * hour, 40f),        // ...so this one, an hour later, is still alone
            carbs(6, t0 + 20 * hour, 20f),        // three foods for one plate, each within
            carbs(7, t0 + 20 * hour + 5 * minute, 25f), // 10 minutes of the one before...
            carbs(8, t0 + 20 * hour + 14 * minute, 5f), // ...are one meal of 50 g
            carbs(9, t0 + 30 * hour, 50f),        // not yet 4 hours old
        )
        val meals = MealReplay.isolatedMeals(entries, nowMs = t0 + 32 * hour)
        assertEquals(listOf(t0, t0 + 13 * hour, t0 + 20 * hour), meals.map { it.timestamp })
        assertEquals(50f, meals[2].grams, 1e-4f)
    }

    @Test
    fun aMealShortlyAfterAnotherIsNotReplayed() {
        val entries = listOf(carbs(1, t0, 40f), carbs(2, t0 + 90 * minute, 30f))
        val meals = MealReplay.isolatedMeals(entries, nowMs = t0 + 24 * hour)
        // The first has the second inside its window, the second has the first inside its clear time.
        assertTrue(meals.isEmpty())
    }

    @Test
    fun replayWithTheSavedProfileIsTheForecastTheChartDraws() {
        val meal = MealReplay.Meal(t0, 40f)
        val history = MealReplay.slice(readings(t0), t0 - MealReplay.HISTORY_BEFORE_MS, t0)
        val journal = listOf(carbs(1, t0, 40f), insulin(2, t0 - 10 * minute, 3f), insulin(3, t0 + 2 * hour, 1f))
        val replay = MealReplay.forecast(history, 0, journal, presets, "mg/dL", 70f, 180f, settings, saved)!!

        val direct = buildGlucosePrediction(
            history, journal, presets, "mg/dL", 70f, 180f,
            settings.copy(horizonMinutes = MealReplay.DRAW_AFTER_MINUTES, modelProfile = saved),
        )
        assertEquals(direct, replay)
        assertTrue(MealReplay.canStart(meal, history))
    }

    @Test
    fun theFirstTwoHoursMatchTheRecordedForecastAtTheDashboardHorizon() {
        val history = MealReplay.slice(readings(t0), t0 - MealReplay.HISTORY_BEFORE_MS, t0)
        val journal = listOf(carbs(1, t0, 40f), insulin(2, t0 - 10 * minute, 3f))
        val replay = MealReplay.forecast(history, 0, journal, presets, "mg/dL", 70f, 180f, settings, saved)!!
        val dashboard = buildGlucosePrediction(
            history, journal, presets, "mg/dL", 70f, 180f, settings.copy(horizonMinutes = 120, modelProfile = saved),
        )
        fun recorded(points: List<tk.glucodata.data.prediction.GlucosePredictionPoint>) = PredictionRecorder.entity(
            GlucosePredictionSeries(GlucosePredictionSeriesKind.AUTO, points), ForecastModel.STANDARD, 1, 0L,
            shown = true, origin = points.first().timestamp, recordedAt = 0L, sensor = "S1", lane = ForecastLane.AUTO,
            isMmol = false, trendMgdlPerMin = null, plannedEntries = 0, journalEntries = 0,
        ).values
        val atDashboard = recorded(dashboard)
        val atReplay = recorded(replay)
        assertEquals(24, atDashboard.size)
        for (k in atDashboard.indices) assertEquals("step $k", atDashboard[k], atReplay[k], 0f)
    }

    @Test
    fun aTrialValueMovesOnlyItsOwnBlock() {
        val twoBlocks = saved.addBlock(18 * 60)
        val trial = MealReplay.trialProfile(
            twoBlocks,
            mapOf(18 * 60 to PredictionModelParameters(carbRatioGramsPerUnit = 6f, insulinSensitivityMgDlPerUnit = 70f)),
        )
        assertEquals(twoBlocks.parametersAtMinute(12 * 60), trial.parametersAtMinute(12 * 60))
        assertEquals(6f, trial.parametersAtMinute(19 * 60).carbRatioGramsPerUnit, 0f)
        assertEquals(70f, trial.parametersAtMinute(19 * 60).insulinSensitivityMgDlPerUnit, 0f)
        // The saved profile is a value: building a trial from it leaves it as it was.
        assertEquals(10f, twoBlocks.parametersAtMinute(19 * 60).carbRatioGramsPerUnit, 0f)
        assertEquals(0, MealReplay.blockStartAt(twoBlocks, t0, utc))
        assertEquals(18 * 60, MealReplay.blockStartAt(twoBlocks, t0 + 7 * hour, utc))
    }

    @Test
    fun aSmallerCarbRatioRaisesTheReplayedMeal() {
        val meal = MealReplay.Meal(t0, 40f)
        val all = readings(t0)
        val history = MealReplay.slice(all, t0 - MealReplay.HISTORY_BEFORE_MS, t0)
        val journal = listOf(carbs(1, t0, 40f), insulin(2, t0, 3f))
        fun at4h(profile: PredictionModelProfile) = MealReplay.score(
            meal, MealReplay.forecast(history, 0, journal, presets, "mg/dL", 70f, 180f, settings, profile)!!, all, isMmol = false,
        )!!
        val savedReplay = at4h(saved)
        val trialReplay = at4h(MealReplay.trialProfile(saved, mapOf(0 to PredictionModelParameters(6f, 50f))))
        assertTrue(trialReplay.forecastAtScoreMgdl > savedReplay.forecastAtScoreMgdl)
        assertNotEquals(savedReplay.missMgdl, trialReplay.missMgdl)
    }

    @Test
    fun aMealIsScoredOnTheReadingNearestFourHoursAfterIt() {
        val meal = MealReplay.Meal(t0, 40f)
        val all = readings(t0)
        val history = MealReplay.slice(all, t0 - MealReplay.HISTORY_BEFORE_MS, t0)
        val forecast = MealReplay.forecast(history, 0, listOf(carbs(1, t0, 40f)), presets, "mg/dL", 70f, 180f, settings, saved)!!
        val scored = MealReplay.score(meal, forecast, all, isMmol = false)!!
        assertEquals(110f, scored.actualAtScoreMgdl!!, 1e-3f)

        // Readings that stop before the 4-hour mark leave the meal drawn but unscored.
        val gap = all.filter { it.timestamp < t0 + 3 * hour }
        val unscored = MealReplay.score(meal, forecast, gap, isMmol = false)!!
        assertNull(unscored.actualAtScoreMgdl)
        assertNull(unscored.missMgdl)
    }

    @Test
    fun aWhatIfNeedsARecentReadingToStartFrom() {
        val meal = MealReplay.Meal(t0, 40f)
        val stale = MealReplay.slice(readings(t0), t0 - MealReplay.HISTORY_BEFORE_MS, t0 - 20 * minute)
        assertFalse(MealReplay.canStart(meal, stale))
    }

    @Test
    fun mealInsulinCountsBolusesAroundTheMealOnly() {
        val meal = MealReplay.Meal(t0, 40f)
        val entries = listOf(
            insulin(1, t0 - 15 * minute, 3f),
            insulin(2, t0 + 2 * hour, 1f),
            insulin(3, t0 - 2 * hour, 5f),
            insulin(4, t0 + 5 * hour, 2f),
        )
        assertEquals(4f, MealReplay.mealInsulinUnits(meal, entries, presets), 1e-4f)
    }
}
