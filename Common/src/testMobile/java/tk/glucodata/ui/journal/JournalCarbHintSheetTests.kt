package tk.glucodata.ui.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.prediction.StateDoseHint
import tk.glucodata.data.prediction.StateDoseHintKind
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.ThemeMode

/** A dashboard carb hint must not override the person's calculator setting or pairing choice. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    application = JournalCarbHintSheetTests.TestApplication::class,
    qualifiers = "en-w411dp-h900dp-night-xxhdpi"
)
class JournalCarbHintSheetTests {
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

    private fun showFoodSheet(hint: StateDoseHint?, calculatorEnabled: Boolean = true) {
        val timestamp = System.currentTimeMillis()
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
                    initialType = JournalEntryType.CARBS,
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
        showFoodSheet(hint = null)

        compose.onNodeWithText("Dose calculator").assertExists()
    }

    @Test
    fun hintedCarbsKeepTheCalculatorWithoutAutomaticallyPairingInsulin() {
        showFoodSheet(carbsHint)

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
        showFoodSheet(carbsHint)

        compose.onNodeWithContentDescription("Increase value").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()

        compose.onNodeWithText("Suggested").assertDoesNotExist()
        compose.onNodeWithText("Dose calculator").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pair insulin").assertIsEnabled()
    }

    @Test
    fun aCarbHintDoesNotClearAnExplicitInsulinPairing() {
        showFoodSheet(carbsHint)

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
        showFoodSheet(carbsHint, calculatorEnabled = false)

        compose.onNodeWithText("Suggested").assertIsDisplayed()
        compose.onNodeWithText("Dose calculator").assertDoesNotExist()
        compose.onNodeWithText("Pair insulin").assertDoesNotExist()
    }

    private fun save() {
        compose.onNodeWithText(Applic.app.getString(R.string.save)).performScrollTo().performClick()
        compose.waitForIdle()
    }
}
