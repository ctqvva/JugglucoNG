package tk.glucodata.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "ru-w1200dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardCalibrationLayoutTests {
    @get:Rule val compose = createComposeRule()

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun TextLayoutResult.assertTextFits() {
        assertFalse("text height clipped: ${layoutInput.text}", didOverflowHeight)
        for (line in 0 until lineCount) {
            // Compose can retain a paragraph wider than the measured Text; check the glyphs.
            assertTrue(
                "text width clipped: ${layoutInput.text}",
                getLineRight(line) <= size.width + 1f && getLineLeft(line) >= -1f
            )
        }
    }

    @Test fun calibrationWordStaysWholeAndActionsStillWork() {
        var width by mutableIntStateOf(328)
        var scale by mutableFloatStateOf(1.3f)
        var enabled by mutableStateOf(true)
        var clears = 0
        var adds = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(width.dp)) {
                        DashboardCalibrationActions(enabled, { clears++ }, { adds++ })
                    }
                }
            }
        }
        for (w in listOf(240, 288, 328, 400)) {
            // At 240dp, 2x makes the word plus icon wider than a full-width button.
            for (s in if (w == 240) listOf(1f, 1.3f) else listOf(1f, 1.3f, 2f)) {
                compose.runOnIdle { width = w; scale = s }
                val label = compose.onNodeWithText("Калибровка", useUnmergedTree = true).layout()
                assertEquals("split word at ${w}dp, ${s}x", 1, label.lineCount)
                label.assertTextFits()
                compose.onNodeWithText("Очистить").performClick()
                compose.onNodeWithText("Калибровка").performClick()
            }
        }
        compose.runOnIdle {
            assertEquals(11, clears)
            assertEquals(11, adds)
            enabled = false
        }
        compose.onNodeWithText("Калибровка").assertIsNotEnabled()
        compose.onNodeWithText("Очистить").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(12, clears) }
    }

    @Test fun inactiveRowHasReadableContrastInLightAndDarkThemes() {
        var dark by mutableStateOf(true)
        var dynamic by mutableStateOf(false)
        var disabled by mutableStateOf(true)
        compose.setContent {
            val context = LocalContext.current
            val colors = when {
                dynamic && dark -> dynamicDarkColorScheme(context)
                dynamic -> dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                MaterialTheme(colorScheme = colors, typography = AppTypography) {
                    Surface(color = colors.background) {
                        Column(Modifier.width(328.dp).testTag("card")) {
                            DashboardCalibrationActions(true, {}, {})
                            DashboardCalibrationRowContent(
                                "10 нояб., 13:48", "8,7 → 7,0", disabled, Modifier.testTag("row")
                            )
                        }
                    }
                }
            }
        }
        for (d in listOf(true, false)) for (wallpaper in listOf(false, true)) {
            for (off in listOf(true, false)) {
                compose.runOnIdle { dark = d; dynamic = wallpaper; disabled = off }
                val rowImage = compose.onNodeWithTag("row").captureToImage().toPixelMap()
                val background = rowImage[0, 0].luminance()
                for (text in listOf("10 нояб., 13:48", "8,7 → 7,0")) {
                    val layout = compose.onNodeWithText(text, useUnmergedTree = true).layout()
                    layout.assertTextFits()
                    val foreground = layout.layoutInput.style.color.luminance()
                    val contrast = (maxOf(foreground, background) + 0.05f) /
                        (minOf(foreground, background) + 0.05f)
                    assertTrue("$text contrast $contrast (dark=$d, dynamic=$wallpaper, off=$off)", contrast >= 4.5f)
                    if (text.contains("→")) {
                        assertEquals(
                            if (off) TextDecoration.LineThrough else TextDecoration.None,
                            layout.layoutInput.style.textDecoration
                        )
                    }
                }
                val output = File("build/outputs/layout-regressions/calibration-$d-$wallpaper-$off.png")
                output.parentFile?.mkdirs()
                output.outputStream().use {
                    compose.onNodeWithTag("card").captureToImage().asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
