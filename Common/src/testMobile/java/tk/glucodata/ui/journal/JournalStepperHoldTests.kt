package tk.glucodata.ui.journal

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class JournalStepperHoldTests {
    @get:Rule
    val compose = createComposeRule()

    private val steps = mutableListOf<Int>()

    private fun showStepper() {
        compose.setContent {
            JournalStepperField(
                value = "",
                onValueChange = {},
                onStep = { steps += it },
                label = "Insulin",
                suffix = "U"
            )
        }
    }

    private fun plus() = compose.onNodeWithContentDescription("Increase value")
    private fun minus() = compose.onNodeWithContentDescription("Decrease value")

    private fun hold(millis: Long) {
        compose.mainClock.autoAdvance = false
        plus().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(millis)
    }

    private fun release() {
        plus().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun aTapStepsOnce() {
        showStepper()

        plus().performClick()
        compose.waitForIdle()
        assertEquals(listOf(1), steps)

        minus().performClick()
        compose.waitForIdle()
        assertEquals(listOf(1, -1), steps)
    }

    @Test
    fun holdingStepsOnItsOwnAndGetsQuicker() {
        showStepper()

        hold(1_500)
        val firstSecondAndAHalf = steps.size
        compose.mainClock.advanceTimeBy(1_500)
        val nextSecondAndAHalf = steps.size - firstSecondAndAHalf
        release()

        assertTrue("held for 1.5 s: $firstSecondAndAHalf steps", firstSecondAndAHalf >= 6)
        assertTrue(
            "$nextSecondAndAHalf steps should beat $firstSecondAndAHalf",
            nextSecondAndAHalf > firstSecondAndAHalf
        )
        assertTrue(steps.all { it == 1 })
    }

    @Test
    fun liftingTheFingerAddsNothingToAHold() {
        showStepper()

        hold(1_000)
        val held = steps.size
        release()

        assertTrue(held > 0)
        assertEquals(held, steps.size)
    }

    @Test
    fun aTapAfterAHoldStillSteps() {
        showStepper()
        hold(1_000)
        release()
        val held = steps.size

        plus().performClick()
        compose.waitForIdle()

        assertEquals(held + 1, steps.size)
    }

    @Test
    fun aShortPressIsATapNotAHold() {
        showStepper()

        hold(200)
        release()

        assertEquals(listOf(1), steps)
    }
}
