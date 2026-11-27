package tk.glucodata.ui.journal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.Applic
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.prediction.StateDoseHint
import tk.glucodata.data.prediction.StateDoseHintKind
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.ThemeMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    application = JournalAmountHintSheetTests.TestApplication::class,
    qualifiers = "en-w411dp-h900dp-night-xxhdpi"
)
class JournalAmountHintSheetTests {
    class TestApplication : Applic() {
        override fun onCreate() = Unit
    }

    @get:Rule
    val compose = createComposeRule()

    private var previousApp: Applic? = null

    @Before
    fun useTestApplication() {
        previousApp = Applic.app
        Applic.app = RuntimeEnvironment.getApplication() as TestApplication
    }

    @After
    fun restoreApplication() {
        Applic.app = previousApp
    }

    private val rapid = JournalInsulinPreset(
        id = 1L,
        displayName = "Humalog",
        onsetMinutes = 20,
        durationMinutes = 300,
        accentColor = 0,
        curveJson = "0:0;60:1;300:0",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = true,
        sortOrder = 0
    )

    private var savedEntries = emptyList<JournalEntryInput>()

    private val timestamp = System.currentTimeMillis()
    private var history by mutableStateOf(emptyList<JournalEntry>())

    private fun showSheet(
        hint: StateDoseHint? = null,
        calculatorEnabled: Boolean = true,
        type: JournalEntryType = JournalEntryType.CARBS,
        existingEntry: JournalEntry? = null
    ) {
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                JournalEntrySheet(
                    unit = "mmol/L",
                    selectedTimestamp = timestamp,
                    suggestedDose = hint,
                    insulinPresets = listOf(rapid),
                    foodLibraryEnabled = false,
                    doseProfile = JournalDoseProfile(
                        enabled = calculatorEnabled,
                        carbRatioGramsPerUnit = 10f,
                        insulinSensitivityMgDlPerUnit = 40f,
                        targetMgDl = 100f
                    ),
                    initialType = type,
                    existingEntry = existingEntry,
                    doseJournalEntries = history,
                    onDismiss = {},
                    onSave = { savedEntries = listOf(it) },
                    onSaveEntries = { savedEntries = it },
                    sensorSerialProvider = { null }
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private val carbsHint = StateDoseHint(StateDoseHintKind.CARBS, 15f, 100f, minutesAhead = 20)

    @Test
    fun anOrdinaryMealKeepsTheDoseCalculator() {
        showSheet(hint = null)

        compose.onNodeWithText("Dose calculator").assertExists()
    }

    @Test
    fun hintedCarbsKeepTheCalculatorWithoutAutomaticallyPairingInsulin() {
        showSheet(carbsHint)

        compose.onNodeWithText("Suggested").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("15")).assertExists()
        compose.onNodeWithText("Dose calculator").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pair insulin").assertIsEnabled()

        save()
        assertEquals(listOf(JournalEntryType.CARBS), savedEntries.map { it.type })
        assertEquals(15f, savedEntries.single().amount)
    }

    @Test
    fun editingHintedCarbsKeepsTheCalculator() {
        showSheet(carbsHint)

        compose.onNodeWithContentDescription("Increase value").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()

        compose.onNodeWithText("Suggested").assertDoesNotExist()
        compose.onNodeWithText("Dose calculator").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pair insulin").assertIsEnabled()
    }

    @Test
    fun aCarbHintDoesNotClearAnExplicitInsulinPairing() {
        showSheet(carbsHint)

        compose.onNodeWithText("Pair insulin").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()

        compose.onNode(hasSetTextAction() and hasText("15"))
            .performScrollTo().performTextReplacement("30")
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()

        save()
        assertEquals(listOf(JournalEntryType.CARBS, JournalEntryType.INSULIN), savedEntries.map { it.type })
        assertEquals(30f, savedEntries[0].amount)
        assertEquals(3f, savedEntries[1].amount)
        assertEquals(rapid.id, savedEntries[1].insulinPresetId)
    }

    @Test
    fun aCarbHintDoesNotEnableADisabledCalculator() {
        showSheet(carbsHint, calculatorEnabled = false)

        compose.onNodeWithText("Suggested").assertIsDisplayed()
        compose.onNodeWithText("Dose calculator").assertDoesNotExist()
        compose.onNodeWithText("Pair insulin").assertDoesNotExist()
    }

    private fun usualDoses(amount: Float): List<JournalEntry> = (1..3).map { daysAgo ->
        val time = timestamp - daysAgo * 86_400_000L
        JournalEntry(
            id = daysAgo.toLong(), timestamp = time, type = JournalEntryType.INSULIN,
            title = rapid.displayName, amount = amount, insulinPresetId = rapid.id,
            sensorSerial = null, note = null, glucoseValueMgDl = null, durationMinutes = null,
            intensity = null, foodId = null, proteinGrams = null, fatGrams = null,
            source = JournalEntrySource.MANUAL, sourceRecordId = null, createdAt = time, updatedAt = time
        )
    }

    @Test
    fun savesTheDisplayedUsualDoseWithoutTouchingTheField() {
        history = usualDoses(6f)
        showSheet(type = JournalEntryType.INSULIN)

        compose.onNode(hasSetTextAction() and hasText("6")).assertIsDisplayed()
        save()
        assertEquals(6f, savedEntries.single().amount)
        assertEquals(rapid.id, savedEntries.single().insulinPresetId)
    }

    @Test
    fun oneTapSelectsTheWholeDoseAndTypingReplacesIt() {
        history = usualDoses(12.5f)
        showSheet(type = JournalEntryType.INSULIN)

        val field = compose.onNode(hasSetTextAction() and hasText("12.5"))
        field.performTouchInput { click() }
        assertEquals(TextRange(0, 4), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field.performTextInput("4,5")
        compose.runOnIdle { history = usualDoses(8f) }

        save()
        assertEquals(4.5f, savedEntries.single().amount)
    }

    @Test
    fun clearingADoseKeepsSaveDisabledWhenHistoryChanges() {
        history = usualDoses(6f)
        showSheet(type = JournalEntryType.INSULIN)

        compose.onNode(hasSetTextAction() and hasText("6")).performTextReplacement("")
        compose.runOnIdle { history = usualDoses(8f) }

        saveButton().assertIsNotEnabled()
    }

    @Test
    fun aCarbHintSuppressesTheUsualInsulinDose() {
        history = usualDoses(6f)
        showSheet(hint = carbsHint, type = JournalEntryType.INSULIN)

        saveButton().assertIsNotEnabled()
    }

    @Test
    fun withoutAnInsulinSuggestionSaveRequiresAnAmount() {
        showSheet(type = JournalEntryType.INSULIN)

        saveButton().assertIsNotEnabled()
    }

    @Test
    fun theMealSuggestionTakesPrecedenceOverHabit() {
        val doses = usualDoses(6f)
        val meal = doses.first().copy(
            id = 4L, timestamp = timestamp - 60_000L, type = JournalEntryType.CARBS,
            amount = 30f, insulinPresetId = null
        )
        history = doses + meal
        showSheet(type = JournalEntryType.INSULIN)

        save()
        assertEquals(3f, savedEntries.single().amount)
    }

    @Test
    fun theStepperIncrementsTheDisplayedDoseOnTheFirstPress() {
        history = usualDoses(6f)
        showSheet(type = JournalEntryType.INSULIN)

        compose.onNodeWithContentDescription("Increase value").performClick()

        save()
        assertEquals(6.5f, savedEntries.single().amount)
    }

    @Test
    fun anOpeningInsulinHintTakesPrecedenceOverTheUsualDose() {
        history = usualDoses(6f)
        showSheet(hint = StateDoseHint(StateDoseHintKind.INSULIN, 2.5f, 100f), type = JournalEntryType.INSULIN)

        save()
        assertEquals(2.5f, savedEntries.single().amount)
    }

    @Test
    fun editingAnExistingEntryKeepsItsRecordedDose() {
        history = usualDoses(6f)
        val recorded = history.first().copy(amount = 3f)
        showSheet(type = JournalEntryType.INSULIN, existingEntry = recorded)

        save()
        assertEquals(recorded.id, savedEntries.single().id)
        assertEquals(3f, savedEntries.single().amount)
    }

    private fun saveButton(): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("save_button")
        return compose.onNodeWithText(Applic.app.getString(R.string.save))
    }

    private fun save() {
        saveButton().assertIsEnabled().performClick()
        compose.waitForIdle()
    }
}
