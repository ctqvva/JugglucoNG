package tk.glucodata.ui.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.data.food.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class FoodPickerRegressionTests {
    private val food = FoodItem("off:1", FoodSource.OPEN_FOOD_FACTS, "Scanned", referenceGrams = 100f, carbs = 10f)

    @Test
    fun lookupAppendsToTheCurrentDraftAfterEditsAndRemovals() = runBlocking {
        val reply = CompletableDeferred<OpenFoodFactsResult<FoodItem>>()
        val scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = FoodPickerState(scope, context) { _, _ -> reply.await() }
        var draft = JournalEntryDraft(type = tk.glucodata.data.journal.JournalEntryType.CARBS, timestamp = 0L)
            .withFoodItems(listOf(JournalDraftFoodItem(food.copy(name = "Removed"), FoodAmount(50f))), true)
        state.startScanning()
        state.lookUp("1") { found -> draft = draft.withFoodItems(draft.foodItems + JournalDraftFoodItem(found, FoodAmount(100f)), true) }
        draft = draft.withFoodItems(listOf(JournalDraftFoodItem(food.copy(name = "Edited"), FoodAmount(25f))), true)
        reply.complete(OpenFoodFactsResult.Found(food))
        assertEquals(listOf("Edited", "Scanned"), draft.foodItems.map { it.food.name })
        assertEquals(25f, draft.foodItems.first().amount.quantity, 0f)
    }

    @Test
    fun leavingTheScannerDiscardsAnOutstandingLookup() = runBlocking {
        val reply = CompletableDeferred<OpenFoodFactsResult<FoodItem>>()
        val scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined)
        val state = FoodPickerState(scope, ApplicationProvider.getApplicationContext()) { _, _ -> reply.await() }
        var calls = 0
        state.startScanning()
        state.lookUp("1") { calls++ }
        state.close()
        reply.complete(OpenFoodFactsResult.Found(food))
        assertEquals(0, calls)
        assertFalse(state.scanning)
        assertEquals(FoodBarcodeLookup.Idle, state.barcode)
    }
    @Test
    fun refocusingSearchRetainsTheQueryAndCancelsOnlyBarcodeWork() = runBlocking {
        val reply = CompletableDeferred<OpenFoodFactsResult<FoodItem>>()
        val scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined)
        val state = FoodPickerState(scope, ApplicationProvider.getApplicationContext()) { _, _ -> reply.await() }
        state.startScanning()
        var calls = 0
        state.lookUp("1") { calls++ }
        state.onQueryChange("apple")
        state.activateSearch()
        assertEquals("apple", state.query)
        assertTrue(state.searching)
        assertFalse(state.scanning)
        reply.complete(OpenFoodFactsResult.Found(food))
        assertEquals(0, calls)
        // Let the already scheduled local search finish, then keep its result on refocus.
        delay(200)
        val shelves = state.shelves
        state.activateSearch()
        assertSame(shelves, state.shelves)
        assertEquals("apple", state.query)
        state.close()
    }

}
