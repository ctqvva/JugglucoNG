package tk.glucodata.ui.stats

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w1200dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsPortraitLayoutTests {
    @get:Rule val compose = createComposeRule()

    private val range = StatsDateRange(
        Instant.parse("2026-11-09T12:00:00Z").toEpochMilli(),
        Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
    )

    private fun comparison(delta: Float) = StatsComparison(delta, 0f, 0f, 0f, PeriodScalars())

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun TextLayoutResult.assertFits() {
        assertFalse("height clipped: ${layoutInput.text}", didOverflowHeight)
        for (line in 0 until lineCount) {
            assertFalse("ellipsized: ${layoutInput.text}", isLineEllipsized(line))
            assertTrue(
                "width clipped: ${layoutInput.text}",
                getLineLeft(line) >= -1f && getLineRight(line) <= size.width + 1f
            )
        }
    }

    @Test fun coverageKeepsItsPercentageAndDateRangeActionAtPhoneWidths() {
        var width by mutableIntStateOf(328)
        var scale by mutableFloatStateOf(1.3f)
        var count by mutableIntStateOf(282)
        var coverage by mutableFloatStateOf(98f)
        var dateClicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.coerceAtMost(1.3f))) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(width.dp)) {
                        StatsRangeSelectorControl(
                            StatsTimeRange.DAY_1, range, false, true, count,
                            {}, { dateClicks++ }, coveragePercent = coverage
                        )
                    }
                }
            }
        }
        for (w in listOf(288, 328, 400, 600)) for (s in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { width = w; scale = s }
            for (n in listOf(282, 1234567)) {
                compose.runOnIdle { count = n; coverage = if (n == 282) 98f else 100f }
                val active = if (n == 282) "Active\u00A098%" else "Active\u00A0100%"
                compose.onNode(hasText(active, substring = true), useUnmergedTree = true).layout().assertFits()
                compose.onNodeWithText(formatStatsDateRange(range)!!).performClick()
            }
        }
        compose.runOnIdle { assertEquals(24, dateClicks) }
    }

    @Test fun wholeTirLabelStaysInsideTheCircleWithAReadableComparisonBelow() {
        var scale by mutableFloatStateOf(1f)
        var percent by mutableFloatStateOf(62f)
        var delta by mutableStateOf<Float?>(23f)
        var selected by mutableStateOf<TirBand?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.coerceAtMost(1.3f))) {
                MaterialTheme(typography = AppTypography) {
                    val change = delta?.let(::comparison)
                    OverviewRing(
                        tir = TimeInRangeBreakdown(inRangePercent = percent, highPercent = 100f - percent),
                        comparison = change,
                        selectedBand = selected,
                        onBandSelected = { selected = it },
                        modifier = Modifier.width(128.dp).testTag("ring")
                    )
                }
            }
        }
        for (s in listOf(1f, 1.3f, 2f)) for (p in listOf(0f, 62f, 100f)) {
            for (d in listOf(null, 23f, -100f)) {
                compose.runOnIdle { scale = s; percent = p; delta = d }
                val ring = compose.onNodeWithContentDescription("${p.toInt()}% TIR", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                // A selected arc is 17dp thick, extending 1dp farther into the opening.
                val innerRadius = ring.width / 2f - 16f
                for (text in listOf("${p.toInt()}%", "TIR")) {
                    val node = compose.onNodeWithText(text, useUnmergedTree = true)
                    node.layout().assertFits()
                    val b = node.fetchSemanticsNode().boundsInRoot
                    for (corner in listOf(b.topLeft, b.topRight, b.bottomLeft, b.bottomRight)) {
                        assertTrue("$text touches ring at scale $s", (corner - ring.center).getDistance() < innerRadius)
                    }
                }
            }
        }
        // A band tap and a center tap retain the existing selection behavior.
        compose.runOnIdle { percent = 100f; delta = 23f; selected = null }
        compose.onNodeWithContentDescription("100% TIR", useUnmergedTree = true).performTouchInput { click(Offset(center.x, 4f)) }
        compose.runOnIdle { assertEquals(TirBand.IN_RANGE, selected) }
        compose.onNodeWithContentDescription("100% TIR", useUnmergedTree = true).performTouchInput { click(center) }
        compose.runOnIdle { assertNull(selected) }
    }

    @Test fun patternsHeadingStaysWholeAndPortraitOverviewRendersWithoutCrowding() {
        var width by mutableIntStateOf(328)
        var scale by mutableFloatStateOf(1.3f)
        val summary = StatsSummary(
            tir = TimeInRangeBreakdown(inRangePercent = 61.7f, highPercent = 26.6f, veryHighPercent = 11.7f),
            parts = object : StatsSummaryParts by StatsSummaryParts.Empty {
                override val comparison = comparison(23f)
            }
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.coerceAtMost(1.3f))) {
                MaterialTheme(typography = AppTypography) {
                    Surface {
                        Column(Modifier.width(width.dp).testTag("portrait")) {
                            StatsRangeSelectorControl(
                                StatsTimeRange.DAY_1, range, false, true, 282,
                                {}, {}, coveragePercent = 98f
                            )
                            Spacer(Modifier.height(16.dp))
                            GlycemicOverviewCard(summary, StatsTargets(), GlucoseUnit.MMOL, StatsTimeRange.DAY_1, null, {})
                            PatternsHeader("Hourly patterns", "12:00 · Median 7.1", "IQR 5.2–8.8")
                        }
                    }
                }
            }
        }
        for (w in listOf(288, 328, 400, 600)) for (s in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { width = w; scale = s }
            val title = compose.onNodeWithText("Patterns", useUnmergedTree = true).layout()
            title.assertFits()
            assertEquals("heading broken at ${w}dp, ${s}x", 1, title.lineCount)
            compose.onNodeWithText("62%", useUnmergedTree = true).layout().assertFits()
            val output = File("build/outputs/layout-regressions/statistics-$w-$s.png")
            output.parentFile?.mkdirs()
            output.outputStream().use {
                compose.onNodeWithTag("portrait").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
