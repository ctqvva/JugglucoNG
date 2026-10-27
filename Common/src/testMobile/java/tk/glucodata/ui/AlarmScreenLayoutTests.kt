package tk.glucodata.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.logic.TrendEngine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmScreenLayoutTests {
    @get:Rule val compose = createComposeRule()

    private fun showAlarm(fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                AlarmScreen(
                    primaryGlucose = "3.1",
                    secondaryGlucose = null,
                    alarmLabel = "Glucose low",
                    supportingText = "Falling for 20 minutes",
                    severity = AlarmSeverity.LOW,
                    // Unknown keeps the trend name (a native call) out; the arrow box keeps its size.
                    trendResult = TrendEngine.TrendResult(TrendEngine.TrendState.Unknown, Float.NaN, 0f, 0f),
                    timeText = "12:00",
                    onSnooze = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun bounds(text: String): Rect = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun assertDockWhole(screenHeight: Float) {
        val stop = bounds("Stop")
        val snooze = bounds("Snooze")
        assertTrue("Stop $stop", stop.bottom <= screenHeight && stop.height >= 82f)
        assertTrue("Snooze $snooze", snooze.bottom <= stop.top && snooze.height >= 58f)
    }

    // Before the dock was measured first, Stop was squeezed to 13dp here.
    @Test
    @Config(qualifiers = "en-w360dp-h520dp-mdpi")
    fun stopAndSnoozeStayWholeOnAShortScreenAtLargeText() {
        showAlarm(fontScale = 2f)
        assertDockWhole(520f)
        compose.onNodeWithText("3.1").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-w640dp-h360dp-mdpi")
    fun stopAndSnoozeStayWholeInLandscape() {
        showAlarm(fontScale = 1f)
        assertDockWhole(360f)
        compose.onNodeWithText("3.1").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theDockSitsAtTheBottomWhenEverythingFits() {
        showAlarm(fontScale = 2f)
        // 16dp under the dock, 16dp inside it.
        assertEquals(608f, bounds("Stop").bottom, 0.5f)
    }
}
