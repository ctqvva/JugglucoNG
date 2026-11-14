package tk.glucodata.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmScreenLayoutTests {
    @get:Rule val compose = createComposeRule()

    private val prefs get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)

    @Before
    @After
    fun clearNotificationFontPreferences() {
        prefs.edit().clear().commit()
    }

    private fun showAlarm(fontScale: Float, alarmLabel: String = "Glucose low") {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                AlarmScreen(
                    primaryGlucose = "3.1",
                    secondaryGlucose = null,
                    alarmLabel = alarmLabel,
                    supportingText = "Falling for 20 minutes",
                    severity = AlarmSeverity.LOW,
                    // Unknown: no arrow; the arrow box keeps its size.
                    trendResult = TrendEngine.TrendResult(TrendEngine.TrendState.Unknown, Float.NaN, 0f, 0f),
                    timeText = "12:00",
                    onSnooze = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun assertTypography(text: String, expected: TextStyle) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val actual = layouts.single().layoutInput.style
        assertEquals("$text family", expected.fontFamily, actual.fontFamily)
        assertEquals("$text weight", expected.fontWeight, actual.fontWeight)
    }

    private fun assertAppTypographyWithNotificationFont(family: Int) {
        // Both notification families and a lighter notification weight must leave in-app text alone.
        prefs.edit().putInt("notification_font_family", family)
            .putInt("notification_font_weight", 300).commit()
        showAlarm(fontScale = 1f, alarmLabel = "Very low")
        val output = File("build/outputs/layout-regressions/alarm-notification-font-$family.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTypography("Very\nlow", AppTypography.headlineLarge)
        assertTypography("Falling for 20 minutes", AppTypography.titleMedium)
        assertTypography("3.1", AppTypography.displayLarge)
        assertTypography("Snooze", AppTypography.titleMedium)
        assertTypography("Stop", AppTypography.headlineSmall)
    }

    @Test
    fun systemNotificationFontDoesNotOverrideAlarmTypography() {
        assertAppTypographyWithNotificationFont(1)
    }

    @Test
    fun plexNotificationWeightDoesNotOverrideAlarmTypography() {
        assertAppTypographyWithNotificationFont(0)
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
