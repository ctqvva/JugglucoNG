package tk.glucodata.ui.journal

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.ThemeMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [33], application = FoodLibraryMotionTests.TestApplication::class,
    qualifiers = "ru-w411dp-h1000dp-night-xxhdpi")
class FoodLibraryMotionTests {
    class TestApplication : Applic() { override fun onCreate() {} }
    @get:Rule val compose = createComposeRule()
    private fun foods(count: Int) = (1L..count).map { id ->
        JournalFood(id, "Saved food $id with a long name that wraps", 10f, null, null, 90, 0,
            false, false, id.toInt())
    }
    private fun label(id: Int) = Applic.app.getString(id)

    private fun swipeRow(name: String, start: Float = 0.98f, end: Float = 0.02f, duration: Long = 200, settleMillis: Long = 1500) {
        val row = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("food_library_list")
        val viewport = list.fetchSemanticsNode().boundsInRoot
        // Inject through the stable viewport: a successful deletion may remove its row before
        // the final input event has been dispatched.
        list.performTouchInput {
            val startPoint = Offset(row.left - viewport.left + row.width * start, row.center.y - viewport.top)
            val endPoint = Offset(row.left - viewport.left + row.width * end, row.center.y - viewport.top)
            if (duration > 1000) {
                down(startPoint)
                repeat(30) { step -> moveTo(startPoint + (endPoint - startPoint) * ((step + 1) / 30f), delayMillis = 20) }
                // A stationary release tests distance alone, not the velocity-based fling path.
                moveTo(endPoint, delayMillis = 400)
                up()
            } else swipe(startPoint, endPoint, durationMillis = duration)
        }
        compose.mainClock.advanceTimeBy(settleMillis)
        compose.waitForIdle()
    }

    @Test
    fun selectingAndCancellingPreserveScrolledRowAndViewportOnEveryFrame() {
        val foods = foods(100)
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                FoodLibraryLayout(foods, true, true, true, {}, FoodLibraryActions({}, {}, {}, {}, {}, {}, {}))
            }
        }
        val row = compose.onNodeWithText(foods[40].displayName)
        compose.onNodeWithTag("food_library_list").performScrollToNode(hasText(foods[40].displayName))
        val text = compose.onNodeWithText(foods[40].displayName, useUnmergedTree = true)
        val before = text.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("food_library_list").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        row.performTouchInput { longClick() }
        fun assertStableFrames() {
            repeat(40) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val now = text.fetchSemanticsNode().boundsInRoot
                assertEquals("row top", before.top, now.top, 1f)
                assertEquals("row height", before.height, now.height, 1f)
                assertEquals("text left edge", before.left, now.left, 1f)
                assertEquals("list viewport", viewport,
                    compose.onNodeWithTag("food_library_list").fetchSemanticsNode().boundsInRoot)
            }
        }
        assertStableFrames()
        compose.onNodeWithContentDescription(label(R.string.cancel)).performClick()
        assertStableFrames()
        compose.onNodeWithContentDescription(label(R.string.journal_add_food)).assertIsDisplayed()
    }

    @Test
    fun swipeFiresOnceHasHapticsAndFailedDeleteCanBeRetried() {
        val events = mutableListOf<HapticFeedbackType>()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { events += hapticFeedbackType }
        }
        val foods = foods(3)
        var revision by mutableIntStateOf(0)
        var calls = 0
        var completion: ((Boolean) -> Unit)? = null
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                JugglucoTheme(ThemeMode.DARK) {
                    FoodLibraryLayout(foods, revision % 2 == 0, true, true, {},
                        FoodLibraryActions({}, {}, {}, {}, {}, {}, {}, onDelete = { _, done -> calls++; completion = done }))
                }
            }
        }
        val row = compose.onNodeWithText(foods.first().displayName)
        row.performScrollTo()
        swipeRow(foods.first().displayName)
        assertEquals(1, calls)
        assertTrue(events.contains(HapticFeedbackType.LongPress))
        assertEquals(1, events.count { it == HapticFeedbackType.Confirm })
        repeat(3) { compose.runOnIdle { revision++ } }
        assertEquals("recomposition must not repeat deletion", 1, calls)
        compose.runOnIdle { completion!!(false) }
        compose.mainClock.advanceTimeBy(1500)
        row.assertIsDisplayed()
        swipeRow(foods.first().displayName)
        assertEquals(2, calls)
        assertEquals(2, events.count { it == HapticFeedbackType.Confirm })
    }

    @Test
    fun shortSwipeDoesNotMutateAndUndoDoesNotRestoreADismissedRow() {
        val original = foods(3)
        var current by mutableStateOf(original)
        var calls = 0
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                FoodLibraryLayout(current, false, true, true, {},
                    FoodLibraryActions({}, {}, {}, {}, {}, {}, {}, onDelete = { ids, done ->
                        calls++; current = current.filter { it.id !in ids }; done(true)
                    }))
            }
        }
        val row = compose.onNodeWithText(original.first().displayName)
        row.performScrollTo()
        swipeRow(original.first().displayName, start = 0.8f, end = 0.6f, duration = 1200)
        assertEquals(0, calls)
        swipeRow(original.first().displayName)
        assertEquals(1, calls)
        compose.onNodeWithText(original.first().displayName).assertDoesNotExist()
        compose.runOnIdle { current = original }
        compose.mainClock.advanceTimeBy(1500)
        row.performScrollTo().assertIsDisplayed()
        swipeRow(original.first().displayName)
        assertEquals("restored row must accept a new swipe", 2, calls)
    }

    @Test
    fun undoWhileTheRemovalIsStillAnimatingRestoresAUsableRow() {
        val original = foods(3)
        var current by mutableStateOf(original)
        var calls = 0
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                FoodLibraryLayout(current, false, true, true, {},
                    FoodLibraryActions({}, {}, {}, {}, {}, {}, {}, onDelete = { ids, done ->
                        calls++; current = current.filter { it.id !in ids }; done(true)
                    }))
            }
        }
        compose.onNodeWithText(original.first().displayName).performScrollTo()
        compose.mainClock.autoAdvance = false
        swipeRow(original.first().displayName, settleMillis = 16)
        compose.mainClock.advanceTimeUntil(timeoutMillis = 2000) { calls == 1 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { current = original }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(original.first().displayName).performScrollTo().assertIsDisplayed()
        swipeRow(original.first().displayName)
        assertEquals(2, calls)
    }
}
