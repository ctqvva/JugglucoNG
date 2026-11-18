package tk.glucodata.ui.stats

import androidx.compose.ui.geometry.Offset
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
                "width clipped: ${layoutInput.text}, ${getLineLeft(line)}..${getLineRight(line)} in ${size.width}",
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

    @Test fun selectingALocalTimeBlockChangesTheDistributionAndReturningToAllRestoresIt() {
        val summary = StatsSummary(
            readingCount = 96,
            tir = TimeInRangeBreakdown(inRangePercent = 75f, highPercent = 25f),
            parts = object : StatsSummaryParts by StatsSummaryParts.Empty {
                override val dayParts = listOf(
                    DayPartStats(DayPart.NIGHT, 100f, TimeInRangeBreakdown(inRangePercent = 50f, lowPercent = 50f), 24)
                )
                override val comparison = comparison(10f).copy(previous = PeriodScalars(dayParts = listOf(
                    DayPartStats(DayPart.NIGHT, 100f, TimeInRangeBreakdown(inRangePercent = 75f), 24)
                )))
            }
        )
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(288.dp).testTag("card")) { GlycemicOverviewCard(summary, StatsTargets(), GlucoseUnit.MGDL) }
            }
        }
        val all = compose.onNode(hasText("All day") and hasText("Time in range"))
        val high = compose.onNodeWithText("High")
        high.assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        high.assertIsSelected()
        compose.onNodeWithText("High · ≈ 6 h / 24 h").assertExists()
        high.performClick().assertIsNotSelected()

        // The proportional bar uses the same selection as the full-height legend rows.
        val top = compose.onNodeWithText("Very high").fetchSemanticsNode().boundsInRoot.top
        val bottom = compose.onNodeWithText("Very low").fetchSemanticsNode().boundsInRoot.bottom
        val card = compose.onNodeWithTag("card")
        val origin = card.fetchSemanticsNode().boundsInRoot.topLeft
        val highPoint = Offset(origin.x + 32f, top + (bottom - top) * 0.12f) - origin
        val middle = Offset(highPoint.x, (top + bottom) / 2f - origin.y)
        card.performTouchInput { click(highPoint) }
        high.assertIsSelected()
        card.performTouchInput { swipe(highPoint, middle) }
        compose.onNodeWithText("In range").assertIsSelected().performClick()
        compose.onNodeWithText("≈ 18 h / 24 h").assertExists()
        compose.onNodeWithContentDescription("+10.0 percentage points", substring = true).assertExists()

        // Keep the original pointer down across expansion, then release over Night.
        val start = all.fetchSemanticsNode().boundsInRoot.center
        all.performTouchInput { down(center); moveBy(Offset(0f, 32.dp.toPx())) }
        compose.waitForIdle()
        compose.onNodeWithText("Morning").assertIsNotEnabled()
        val night = compose.onNodeWithText("Night").fetchSemanticsNode().boundsInRoot.center
        all.performTouchInput { moveTo(center + night - start); up() }
        compose.onNodeWithText("≈ 3 h / 6 h").assertExists()
        compose.onNodeWithContentDescription("-25.0 percentage points", substring = true).assertExists()
        compose.onNodeWithContentDescription("+10.0 percentage points", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Night").performClick()
        compose.onNodeWithText("All day").performClick()
        compose.onNodeWithText("75.0%").assertExists()
        compose.onNodeWithContentDescription("+10.0 percentage points", substring = true).assertExists()
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
                            GlycemicOverviewCard(summary, StatsTargets(), GlucoseUnit.MMOL)
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
            compose.onNodeWithText("61.7%", useUnmergedTree = true).layout().assertFits()
            val output = File("build/outputs/layout-regressions/statistics-$w-$s.png")
            output.parentFile?.mkdirs()
            output.outputStream().use {
                compose.onNodeWithTag("portrait").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
