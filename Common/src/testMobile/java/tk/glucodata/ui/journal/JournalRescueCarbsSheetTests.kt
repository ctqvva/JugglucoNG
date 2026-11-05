package tk.glucodata.ui.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.Applic
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.prediction.StateDoseHint
import tk.glucodata.data.prediction.StateDoseHintKind
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.ThemeMode

/** Food the dashboard asked for is there to stop a low, so it gets no dose offered with it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    application = JournalRescueCarbsSheetTests.TestApplication::class,
    qualifiers = "en-w411dp-h900dp-night-xxhdpi"
)
class JournalRescueCarbsSheetTests {
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

    private fun showFoodSheet(hint: StateDoseHint?) {
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                JournalEntrySheet(
                    unit = "mmol/L",
                    selectedTimestamp = System.currentTimeMillis(),
                    suggestedDose = hint,
                    insulinPresets = listOf(rapid),
                    doseProfile = JournalDoseProfile(
                        enabled = true,
                        carbRatioGramsPerUnit = 10f,
                        insulinSensitivityMgDlPerUnit = 40f,
                        targetMgDl = 100f
                    ),
                    initialType = JournalEntryType.CARBS,
                    onDismiss = {},
                    onSave = {},
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
    fun theHintedCarbsAreFilledInWithNoDoseOffered() {
        showFoodSheet(carbsHint)

        compose.onNodeWithText("Suggested").assertIsDisplayed()
        compose.onNodeWithText("Dose calculator").assertDoesNotExist()
        compose.onNodeWithText("Pair insulin").assertDoesNotExist()
    }

    @Test
    fun steppingTheHintedCarbsStillOffersNoDose() {
        showFoodSheet(carbsHint)

        compose.onNodeWithContentDescription("Increase value").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()

        compose.onNodeWithText("Suggested").assertDoesNotExist()
        compose.onNodeWithText("Dose calculator").assertDoesNotExist()
    }
}
