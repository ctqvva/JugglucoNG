package tk.glucodata.ui.journal

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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
import tk.glucodata.data.food.*
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.ThemeMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [33], application = FoodLibraryInteractionTests.TestApplication::class,
    qualifiers = "ru-w411dp-h1000dp-night-xxhdpi")
class FoodLibraryInteractionTests {
    class TestApplication : Applic() { override fun onCreate() {} }
    @get:Rule val compose = createComposeRule()

    private val product = FoodItem("off:3017620422003", FoodSource.OPEN_FOOD_FACTS,
        "Chaussons aux pommes", referenceGrams = 100f, carbs = 24f, protein = 11f, fat = 12f,
        barcode = "3017620422003")

    @Test
    fun savingAFrenchOnlineHitDuringAnEnglishSearchShowsItInMyFoods() {
        lateinit var picker: FoodPickerState
        var saved: FoodItem? = null
        var edited: JournalFood? = null
        var library by mutableStateOf(emptyList<JournalFood>())
        compose.setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            picker = remember { FoodPickerState(scope, context, onlineSearch = { _, _ -> OpenFoodFactsResult.Found(listOf(product)) }) }
            picker.library = library
            JugglucoTheme(ThemeMode.DARK) {
                FoodLibraryContent(library, true, true, true,
                    FoodLibraryActions({}, {}, { edited = it }, {}, { food ->
                        saved = food
                        library = listOf(JournalFood(77, food.name, food.carbs, food.protein, food.fat, 90,
                            0, false, false, 1, referenceGrams = 100f, barcode = food.barcode, sourceKey = food.key))
                    }, {}, {}), picker = picker)
            }
        }
        compose.runOnIdle { picker.onQueryChange("apple"); picker.searchOnline() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText(product.name).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1000)
        val save = Applic.app.getString(R.string.journal_food_save_to_library)
        compose.onNodeWithText(save).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(product, saved)
        compose.onNodeWithText(save).assertDoesNotExist()
        compose.onNodeWithText(Applic.app.getString(R.string.food_my_foods)).assertExists()
        compose.onNodeWithText(product.name).performScrollTo().performClick()
        assertEquals(77L, edited?.id)
        assertEquals("apple", picker.query)
    }

    @Test
    fun settingsLibraryOffersScannerOnlyWhenOnlineSourceIsEnabled() {
        var online by mutableStateOf(true)
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                FoodLibraryContent(emptyList(), false, true, online, FoodLibraryActions({}, {}, {}, {}, {}, {}, {}))
            }
        }
        val scan = Applic.app.getString(R.string.food_scan_barcode)
        compose.onNodeWithContentDescription(scan).assertExists()
        compose.runOnIdle { online = false }
        compose.onNodeWithContentDescription(scan).assertDoesNotExist()
    }
}
