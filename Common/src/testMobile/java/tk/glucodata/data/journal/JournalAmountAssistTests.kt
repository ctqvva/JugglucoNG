package tk.glucodata.data.journal

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JournalAmountAssistTests {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = Calendar.getInstance(utc).apply {
        set(2026, Calendar.NOVEMBER, 5, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private val rapid = preset(id = 1L, useForCalculation = true)
    private val basal = preset(id = 2L, useForCalculation = false)
    private val presets = mapOf(rapid.id to rapid, basal.id to basal)

    @Test
    fun stepStaysAtDefaultUntilTheLogCanSayOtherwise() {
        val entries = List(7) { dose(amount = (it + 2).toFloat(), daysAgo = it + 1) }

        assertEquals(0.5f, habit(entries).step, 0f)
    }

    @Test
    fun wholeUnitLoggingMovesTheStepToOneUnit() {
        val entries = List(10) { dose(amount = (it + 2).toFloat(), daysAgo = it + 1) }

        assertEquals(1f, habit(entries).step, 0f)
    }

    @Test
    fun halfUnitLoggingKeepsTheHalfUnitStep() {
        val entries = List(10) { dose(amount = 2f + it * 0.5f, daysAgo = it + 1) }

        assertEquals(0.5f, habit(entries).step, 0f)
    }

    @Test
    fun finerDosingGetsAFinerStep() {
        val entries = List(10) { dose(amount = 1.3f + it * 0.2f, daysAgo = it + 1) }

        assertEquals(0.1f, habit(entries).step, 0f)
    }

    @Test
    fun amountsOnNoGridFallBackToTheDefault() {
        val entries = List(10) { dose(amount = 1.333f + it * 0.777f, daysAgo = it + 1) }

        assertEquals(0.5f, habit(entries).step, 0f)
    }

    @Test
    fun stepIsLearnedPerInsulin() {
        val rapidHalves = List(10) { dose(amount = 2.5f + it, daysAgo = it + 1) }
        val basalWhole = List(10) { dose(amount = 20f + it, daysAgo = it + 1, presetId = basal.id) }
        val entries = rapidHalves + basalWhole

        assertEquals(0.5f, JournalAmountAssist.insulinHabit(entries, rapid.id, now, utc).step, 0f)
        assertEquals(1f, JournalAmountAssist.insulinHabit(entries, basal.id, now, utc).step, 0f)
    }

    @Test
    fun carbStepFollowsTensAndExchanges() {
        val tens = List(10) { food(amount = 10f * (it + 1), daysAgo = it + 1) }
        val exchanges = List(10) { food(amount = 15f * (it + 1), daysAgo = it + 1) }
        val mixed = List(10) { food(amount = 5f * (it + 1), daysAgo = it + 1) }

        assertEquals(10f, JournalAmountAssist.carbHabit(tens, now, utc).step, 0f)
        assertEquals(15f, JournalAmountAssist.carbHabit(exchanges, now, utc).step, 0f)
        assertEquals(5f, JournalAmountAssist.carbHabit(mixed, now, utc).step, 0f)
    }

    @Test
    fun importedDosesAreNotHabits() {
        val pump = List(10) {
            dose(amount = 6f, daysAgo = it + 1, source = JournalEntrySource.AAPS)
        }

        assertEquals(JournalAmountHabit(0.5f, null), habit(pump))
    }

    @Test
    fun aManualEntryClonedFromAnotherPhoneStillCounts() {
        val cloned = List(3) {
            dose(amount = 6f, daysAgo = it + 1).copy(
                source = JournalEntrySource.CLONE,
                originSource = JournalEntrySource.MANUAL
            )
        }

        assertEquals(6f, habit(cloned).usual)
    }

    @Test
    fun oldHabitsExpire() {
        val old = List(10) { dose(amount = 6f, daysAgo = 61 + it) }

        assertNull(habit(old).usual)
    }

    @Test
    fun entriesAfterTheEntryBeingWrittenDoNotCount() {
        val later = List(3) { dose(amount = 6f, daysAgo = -(it + 1)) }

        assertNull(habit(later).usual)
    }

    @Test
    fun usualFollowsTheTimeOfDay() {
        val breakfast = List(4) { dose(amount = 8f, daysAgo = it + 1, hourOfDay = 7) }
        val lunch = List(3) { dose(amount = 5f, daysAgo = it + 1, hourOfDay = 12) }

        assertEquals(5f, habit(breakfast + lunch).usual)
    }

    @Test
    fun timeOfDayWrapsAroundMidnight() {
        val lateNight = List(3) { dose(amount = 4f, daysAgo = it + 1, hourOfDay = 23) }
        val afternoon = List(4) { dose(amount = 9f, daysAgo = it + 1, hourOfDay = 15) }
        val shortlyAfterMidnight = now - 11 * hour

        assertEquals(
            4f,
            JournalAmountAssist.insulinHabit(lateNight + afternoon, rapid.id, shortlyAfterMidnight, utc).usual
        )
    }

    @Test
    fun usualFallsBackToTheWholeLogWhenThisTimeHasTooFew() {
        val evenings = List(5) { dose(amount = 7f, daysAgo = it + 1, hourOfDay = 19) }

        assertEquals(7f, habit(evenings).usual)
    }

    @Test
    fun usualNeedsEnoughEntries() {
        val evenings = List(4) { dose(amount = 7f, daysAgo = it + 1, hourOfDay = 19) }

        assertNull(habit(evenings).usual)
    }

    @Test
    fun aTieGoesToTheMoreRecentAmount() {
        val entries = listOf(
            dose(amount = 4f, daysAgo = 5),
            dose(amount = 4f, daysAgo = 4),
            dose(amount = 6f, daysAgo = 2),
            dose(amount = 6f, daysAgo = 1)
        )

        assertEquals(6f, habit(entries).usual)
    }

    @Test
    fun withNothingRepeatedUsualIsTheMiddleOfTheLog() {
        val entries = listOf(
            food(amount = 20f, daysAgo = 1),
            food(amount = 35f, daysAgo = 2),
            food(amount = 50f, daysAgo = 3),
            food(amount = 65f, daysAgo = 4),
            food(amount = 90f, daysAgo = 5)
        )

        assertEquals(50f, JournalAmountAssist.carbHabit(entries, now, utc).usual)
    }

    @Test
    fun nearbyAmountsCountAsOneChoice() {
        val entries = listOf(
            food(amount = 47f, daysAgo = 1),
            food(amount = 44f, daysAgo = 2),
            food(amount = 80f, daysAgo = 3)
        )

        assertEquals(45f, JournalAmountAssist.carbHabit(entries, now, utc).usual)
    }

    @Test
    fun recentFoodWithNoDoseIsUncovered() {
        val meal = JournalAmountAssist.uncoveredMeal(
            entries = listOf(food(amount = 60f, millisAgo = hour / 2)),
            presetsById = presets,
            atMillis = now
        )

        assertEquals(60f, meal?.carbsGrams)
    }

    @Test
    fun aDoseAfterTheFoodCoversIt() {
        val meal = JournalAmountAssist.uncoveredMeal(
            entries = listOf(
                food(amount = 60f, millisAgo = hour),
                dose(amount = 6f, millisAgo = hour / 2)
            ),
            presetsById = presets,
            atMillis = now
        )

        assertNull(meal)
    }

    @Test
    fun aPreBolusCoversTheFoodThatFollows() {
        val meal = JournalAmountAssist.uncoveredMeal(
            entries = listOf(
                dose(amount = 6f, millisAgo = hour),
                food(amount = 60f, millisAgo = hour * 3 / 4)
            ),
            presetsById = presets,
            atMillis = now
        )

        assertNull(meal)
    }

    @Test
    fun foodWellAfterAnEarlierDoseIsItsOwnMeal() {
        val meal = JournalAmountAssist.uncoveredMeal(
            entries = listOf(
                dose(amount = 6f, millisAgo = 2 * hour),
                food(amount = 40f, millisAgo = hour)
            ),
            presetsById = presets,
            atMillis = now
        )

        assertEquals(40f, meal?.carbsGrams)
    }

    @Test
    fun basalDoesNotCoverFood() {
        val meal = JournalAmountAssist.uncoveredMeal(
            entries = listOf(
                food(amount = 60f, millisAgo = hour),
                dose(amount = 20f, millisAgo = hour / 2, presetId = basal.id)
            ),
            presetsById = presets,
            atMillis = now
        )

        assertNotNull(meal)
    }

    @Test
    fun foodOutsideTheWindowIsNotAMeal() {
        val meal = JournalAmountAssist.uncoveredMeal(
            entries = listOf(food(amount = 60f, millisAgo = 2 * hour)),
            presetsById = presets,
            atMillis = now
        )

        assertNull(meal)
    }

    @Test
    fun uncoveredMealsAddUpWithTheirMacros() {
        val meal = JournalAmountAssist.uncoveredMeal(
            entries = listOf(
                food(amount = 30f, millisAgo = hour).copy(proteinGrams = 10f),
                food(amount = 20f, millisAgo = hour / 2)
            ),
            presetsById = presets,
            atMillis = now
        )

        assertEquals(JournalUncoveredMeal(50f, proteinGrams = 10f, fatGrams = null), meal)
    }

    @Test
    fun steppingOnTheGridMovesOneStep() {
        assertEquals(6.5f, JournalAmountAssist.stepped(6f, 1, 0.5f), 0f)
        assertEquals(5.5f, JournalAmountAssist.stepped(6f, -1, 0.5f), 0f)
        assertEquals(5.7f, JournalAmountAssist.stepped(5.6f, 1, 0.1f), 1e-4f)
    }

    @Test
    fun steppingOffTheGridLandsOnIt() {
        assertEquals(3f, JournalAmountAssist.stepped(2.5f, 1, 1f), 0f)
        assertEquals(2f, JournalAmountAssist.stepped(2.5f, -1, 1f), 0f)
        assertEquals(50f, JournalAmountAssist.stepped(47f, 1, 5f), 0f)
    }

    @Test
    fun steppingNeverGoesBelowZero() {
        assertEquals(0f, JournalAmountAssist.stepped(0f, -1, 0.5f), 0f)
        assertEquals(0f, JournalAmountAssist.stepped(0.3f, -1, 0.5f), 0f)
    }

    private fun habit(entries: List<JournalEntry>) =
        JournalAmountAssist.insulinHabit(entries, rapid.id, now, utc)

    private fun at(daysAgo: Int, hourOfDay: Int) = Calendar.getInstance(utc).apply {
        timeInMillis = now - daysAgo * day
        set(Calendar.HOUR_OF_DAY, hourOfDay)
    }.timeInMillis

    private fun dose(
        amount: Float,
        daysAgo: Int = 0,
        hourOfDay: Int = 12,
        presetId: Long = rapid.id,
        source: JournalEntrySource = JournalEntrySource.MANUAL,
        millisAgo: Long? = null
    ) = entry(
        type = JournalEntryType.INSULIN,
        amount = amount,
        timestamp = millisAgo?.let { now - it } ?: at(daysAgo, hourOfDay),
        presetId = presetId,
        source = source
    )

    private fun food(
        amount: Float,
        daysAgo: Int = 0,
        millisAgo: Long? = null
    ) = entry(
        type = JournalEntryType.CARBS,
        amount = amount,
        timestamp = millisAgo?.let { now - it } ?: at(daysAgo, 12),
        presetId = null,
        source = JournalEntrySource.MANUAL
    )

    private fun entry(
        type: JournalEntryType,
        amount: Float,
        timestamp: Long,
        presetId: Long?,
        source: JournalEntrySource
    ) = JournalEntry(
        id = timestamp,
        timestamp = timestamp,
        sensorSerial = null,
        type = type,
        title = "",
        note = null,
        amount = amount,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = presetId,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = source,
        sourceRecordId = null,
        createdAt = timestamp,
        updatedAt = timestamp
    )

    private fun preset(id: Long, useForCalculation: Boolean) = JournalInsulinPreset(
        id = id,
        displayName = "Preset $id",
        onsetMinutes = 0,
        durationMinutes = 60,
        accentColor = 0,
        curveJson = "0:0;30:1;60:0",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = true,
        sortOrder = 0,
        useForCalculation = useForCalculation
    )
}
