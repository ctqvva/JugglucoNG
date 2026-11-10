package tk.glucodata.ui.journal

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
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
import tk.glucodata.data.food.*
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.ThemeMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [33], application = FoodScannerLayoutTests.TestApplication::class,
    qualifiers = "en-w411dp-h1000dp-night-xxhdpi")
class FoodScannerLayoutTests {
    class TestApplication : Applic() { override fun onCreate() {} }
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int) = Applic.app.getString(id)
    private val food = FoodItem("off:1", FoodSource.OPEN_FOOD_FACTS, "Scanned", referenceGrams = 100f, carbs = 10f)

    @Test
    fun scanButtonBecomesCloseInTheSameSlotThroughoutBothTransitions() {
        var scanning by mutableStateOf(false)
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                FoodSearchField("", {}, active = false, scanning = scanning, onActivate = {},
                    onClose = { scanning = false }, onScan = { scanning = true })
            }
        }
        val scan = compose.onNodeWithContentDescription(label(R.string.food_scan_barcode))
        val close = compose.onNodeWithContentDescription(label(R.string.close))
        val bounds = scan.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        scan.performClick()
        repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals(bounds, close.fetchSemanticsNode().boundsInRoot)
            compose.onAllNodesWithContentDescription(label(R.string.close)).assertCountEquals(1)
            compose.onNodeWithText(label(R.string.close)).assertDoesNotExist()
        }
        close.performClick()
        repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals(bounds, scan.fetchSemanticsNode().boundsInRoot)
        }
        assertFalse(scanning)
    }

    @Test
    fun databaseScannerPushesExistingFoodsDownAndCloseCancelsLookup() {
        lateinit var picker: FoodPickerState
        val reply = CompletableDeferred<OpenFoodFactsResult<FoodItem>>()
        var found = 0
        val foods = listOf(
            JournalFood(1, "Saved apple", 10f, null, null, 90, 0, false, false, 0),
            JournalFood(2, "Disabled pear", 10f, null, null, 90, 0, false, true, 1)
        )
        compose.setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            picker = remember { FoodPickerState(scope, context, productLookup = { _, _ -> reply.await() }) }
            JugglucoTheme(ThemeMode.DARK) {
                FoodLibraryContent(foods, false, true, true, FoodLibraryActions({}, {}, {}, {}, {}, {}, {}), picker = picker)
            }
        }
        val apple = compose.onNodeWithText("Saved apple")
        val before = apple.fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithContentDescription(label(R.string.food_scan_barcode)).fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { picker.startScanning(); picker.lookUp("1") { found++ } }
        var moved = false
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            val now = apple.fetchSemanticsNode().boundsInRoot
            assertTrue("scanner must push, never replace or pull up the food", now.top >= before.top - 1f)
            moved = moved || now.top > before.top + 100f
            compose.onNodeWithText("Disabled pear").assertExists()
            assertEquals(button, compose.onNodeWithContentDescription(label(R.string.close)).fetchSemanticsNode().boundsInRoot)
        }
        assertTrue(moved)
        compose.onAllNodesWithContentDescription(label(R.string.close)).assertCountEquals(1)
        compose.onNodeWithText(label(R.string.close)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.close)).performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(before, apple.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { reply.complete(OpenFoodFactsResult.Found(food)) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0, found)
        assertFalse(picker.scanning)
        compose.onNodeWithContentDescription(label(R.string.food_scan_barcode)).assertIsDisplayed()
    }

    @Test
    fun journalRowsUseTheSameCloseIconDuringLookupWithoutASeparateCloseRow() {
        lateinit var picker: FoodPickerState
        val reply = CompletableDeferred<OpenFoodFactsResult<FoodItem>>()
        var added = 0
        compose.setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            picker = remember { FoodPickerState(scope, context, productLookup = { _, _ -> reply.await() }) }
            JugglucoTheme(ThemeMode.DARK) {
                LazyColumn {
                    journalFoodRows(row = { key, content -> item(key = key) { content() } }, state = picker,
                        suggestions = FoodCatalog.Suggestions(), items = emptyList(), macrosEnabled = false,
                        onlineEnabled = true, onItemsChange = {}, onAddFood = { added++ }, onSelectFood = {})
                }
            }
        }
        val before = compose.onNodeWithContentDescription(label(R.string.food_scan_barcode)).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { picker.startScanning(); picker.lookUp("1") { added++ } }
        compose.onAllNodesWithContentDescription(label(R.string.close)).assertCountEquals(1)
        compose.onNodeWithText(label(R.string.close)).assertDoesNotExist()
        val close = compose.onNodeWithContentDescription(label(R.string.close))
        assertEquals(before, close.fetchSemanticsNode().boundsInRoot)
        close.performClick()
        compose.runOnIdle { reply.complete(OpenFoodFactsResult.Found(food)) }
        assertFalse(picker.scanning)
        assertEquals(0, added)
        compose.onNodeWithContentDescription(label(R.string.food_scan_barcode)).assertIsDisplayed()
    }
}
