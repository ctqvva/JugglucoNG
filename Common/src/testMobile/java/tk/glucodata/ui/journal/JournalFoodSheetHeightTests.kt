package tk.glucodata.ui.journal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.Applic
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.ThemeMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [33], application = JournalFoodSheetHeightTests.TestApplication::class,
    qualifiers = "en-w411dp-h1000dp-night-xxhdpi")
class JournalFoodSheetHeightTests {
    class TestApplication : Applic() { override fun onCreate() {} }
    @get:Rule val compose = createComposeRule()

    @Test
    fun closingTallFoodSearchReturnsTheSheetToItsShortHeight() {
        val foods = (1L..30L).map { JournalFood(it, "Meal sample $it", 10f, null, null, 90, 0, false, false, it.toInt()) }
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                JournalEntrySheet(unit = "mmol/L", selectedTimestamp = 1L, insulinPresets = emptyList(),
                    foods = foods, initialType = JournalEntryType.CARBS, onDismiss = {}, onSave = {}, sensorSerialProvider = { null })
            }
        }
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        fun height() = compose.onNodeWithTag("journal_entry_sheet", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        val original = height()
        compose.onNodeWithText(Applic.app.getString(R.string.journal_food_search)).performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Meal")
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val expanded = height()
        assertTrue("search should fill more height: $original -> $expanded", expanded > original + 100f)
        compose.onNodeWithText(Applic.app.getString(R.string.close)).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(2500)
        compose.waitForIdle()
        assertTrue("closed search should shrink: $original -> ${height()}", height() <= original + 12f)
    }

    @Test
    fun switchingFromTallFoodSearchToGlucoseRestoresItsOriginalHeight() {
        val foods = (1L..30L).map { JournalFood(it, "Meal sample $it", 10f, null, null, 90, 0, false, false, it.toInt()) }
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                JournalEntrySheet(unit = "mmol/L", selectedTimestamp = 1L, insulinPresets = emptyList(),
                    foods = foods, initialType = JournalEntryType.FINGERSTICK, onDismiss = {}, onSave = {}, sensorSerialProvider = { null })
            }
        }
        fun settle() { compose.mainClock.advanceTimeBy(2500); compose.waitForIdle() }
        fun height() = compose.onNodeWithTag("journal_entry_sheet", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        settle()
        val original = height()
        compose.onAllNodes(isToggleable())[1].performClick()
        settle()
        compose.onNodeWithText(Applic.app.getString(R.string.journal_food_search)).performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Meal")
        settle()
        assertTrue(height() > original + 100f)
        compose.onAllNodes(isToggleable())[2].performScrollTo().performClick()
        settle()
        assertTrue("Glucose should recover its compact height: $original -> ${height()}", height() <= original + 12f)
    }

    @Test
    fun formHeightChangesDoNotRebound() {
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                JournalEntrySheet(unit = "mmol/L", selectedTimestamp = 1L, insulinPresets = emptyList(),
                    initialType = JournalEntryType.FINGERSTICK, onDismiss = {}, onSave = {}, sensorSerialProvider = { null })
            }
        }
        compose.mainClock.advanceTimeBy(2500)
        compose.waitForIdle()
        fun height() = compose.onNodeWithTag("journal_entry_sheet", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.autoAdvance = false
        for (typeIndex in listOf(1, 2)) {
            val before = height()
            compose.onAllNodes(isToggleable())[typeIndex].performClick()
            val frames = (1..60).map {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                height()
            }
            val after = frames.last()
            assertTrue("type change must resize the form", kotlin.math.abs(after - before) > 100f)
            frames.zipWithNext().forEach { (a, b) ->
                assertTrue("height must approach $after without rebounding: $a -> $b",
                    if (after > before) b >= a - 1f else b <= a + 1f)
            }
        }
    }
}
