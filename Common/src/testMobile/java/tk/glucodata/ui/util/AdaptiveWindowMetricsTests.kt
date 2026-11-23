package tk.glucodata.ui.util

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AdaptiveWindowMetricsTests {
    @get:Rule val compose = createComposeRule()

    private fun metrics(
        widthDp: Int,
        heightDp: Int,
        screenDensity: Float = 2.75f,
        density: Float = screenDensity,
        nativeDensity: Float = 2.75f,
        windowHeightPx: Int = 0,
        isLandscape: Boolean = false
    ) = adaptiveWindowMetrics(
        widthDp, heightDp, screenDensity, density, nativeDensity, windowHeightPx, isLandscape
    )

    @Test
    fun defaultDisplaySizeClassifiesByTheWindowAlone() {
        assertEquals(AdaptiveLayoutDensity.Regular, metrics(392, 803).layoutDensity)
        assertEquals(AdaptiveLayoutDensity.Regular, metrics(360, 700).layoutDensity)
        assertEquals(AdaptiveLayoutDensity.Compact, metrics(360, 699).layoutDensity)
        assertEquals(AdaptiveLayoutDensity.Compact, metrics(359, 800).layoutDensity)
        assertEquals(AdaptiveLayoutDensity.Compact, metrics(803, 392, isLandscape = true).layoutDensity)
        assertEquals(AdaptiveLayoutDensity.Comfortable, metrics(1280, 800, isLandscape = true).layoutDensity)
        assertEquals(392, metrics(392, 803).widthDp)
        assertEquals(803, metrics(392, 803).heightDp)
    }

    @Test
    fun enlargedDisplaySizeStaysRegularAndReportsTheDpItLaysOutIn() {
        // A 1080 x 2208 px window at 540 dpi on a 440 dpi phone, held at 1.1x by the theme.
        val enlarged = metrics(320, 654, screenDensity = 3.375f, density = 3.025f)
        assertEquals(AdaptiveLayoutDensity.Regular, enlarged.layoutDensity)
        assertEquals(357, enlarged.widthDp)
        assertEquals(730, enlarged.heightDp)
        assertTrue(enlarged.hasCompactRoom)
        assertFalse(metrics(392, 803).hasCompactRoom)

        // The same enlargement where nothing limits it, as in a dialog.
        val unlimited = metrics(320, 654, screenDensity = 3.375f)
        assertEquals(AdaptiveLayoutDensity.Regular, unlimited.layoutDensity)
        assertEquals(654, unlimited.heightDp)
    }

    @Test
    fun systemBarsGrowingWithTheDisplaySizeDoNotSelectCompact() {
        // 360 x 780 dp at 480 dpi with 72 dp of bars outside the configuration's height.
        val window = 2340
        assertEquals(
            AdaptiveLayoutDensity.Regular,
            metrics(360, 708, screenDensity = 3f, nativeDensity = 3f, windowHeightPx = window).layoutDensity
        )
        // Enlarged 1.36x the bars are 98 of those dp.
        assertEquals(
            AdaptiveLayoutDensity.Regular,
            metrics(
                264, 501, screenDensity = 4.08f, density = 3.3f, nativeDensity = 3f,
                windowHeightPx = window
            ).layoutDensity
        )
    }

    @Test
    fun enlargingNeverTurnsASmallWindowRegular() {
        // A split-screen half is short at any display size.
        assertEquals(
            AdaptiveLayoutDensity.Compact,
            metrics(320, 320, screenDensity = 3.375f, density = 3.025f).layoutDensity
        )
    }

    @Test
    fun reducedDisplaySizeCountsTheRoomItMakes() {
        // A 360 x 640 dp phone is Compact; at a smaller display size it has room.
        assertEquals(
            AdaptiveLayoutDensity.Compact,
            metrics(360, 640, screenDensity = 2f, nativeDensity = 2f).layoutDensity
        )
        assertEquals(
            AdaptiveLayoutDensity.Regular,
            metrics(423, 752, screenDensity = 1.7f, nativeDensity = 2f).layoutDensity
        )
    }

    @Test
    fun textSizeNeverSelectsCompact() {
        var textSize by mutableFloatStateOf(1f)
        val seen = mutableListOf<AdaptiveLayoutDensity>()
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 411
                screenHeightDp = 900
                densityDpi = 160
                fontScale = textSize
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(1f, textSize)
            ) {
                seen += rememberAdaptiveWindowMetrics().layoutDensity
            }
        }
        for (scale in listOf(1.15f, 1.3f, 2f)) {
            compose.runOnIdle { textSize = scale }
            compose.waitForIdle()
        }
        assertEquals(setOf(AdaptiveLayoutDensity.Regular), seen.toSet())
        assertEquals(4, seen.size)
    }
}
