package tk.glucodata.ui.stats

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

    @Test fun inRangeHeadlineAndItsChangeStayWholeAndARowTapSelectsItsBand() {
        var scale by mutableFloatStateOf(1f)
        var percent by mutableFloatStateOf(62f)
        var delta by mutableStateOf<Float?>(23f)
        var selected by mutableStateOf<TirBand?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.coerceAtMost(1.3f))) {
                MaterialTheme(typography = AppTypography) {
                    val change = delta?.let(::comparison)
                    val summary = StatsSummary(
                        tir = TimeInRangeBreakdown(inRangePercent = percent, highPercent = 100f - percent),
                        parts = object : StatsSummaryParts by StatsSummaryParts.Empty {
                            override val comparison = change
                        }
                    )
                    Box(Modifier.width(288.dp)) {
                        GlycemicOverviewCard(summary, StatsTargets(), GlucoseUnit.MMOL,
                            StatsTimeRange.DAY_14, selected, { selected = it }, Modifier.testTag("overview"))
                    }
                }
            }
        }
        for (s in listOf(1f, 1.3f, 2f)) for (p in listOf(0.4f, 62f, 100f)) {
            for (d in listOf(null, 23f, -100f)) {
                compose.runOnIdle { scale = s; percent = p; delta = d }
                val card = compose.onNodeWithTag("overview").fetchSemanticsNode().boundsInRoot
                val headline = compose.onNodeWithText(String.format("%.1f%%", p), useUnmergedTree = true)
                    .also { it.layout().assertFits() }
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("headline outside the card at $s", headline.right <= card.right && headline.bottom <= card.bottom)
                if (d != null) {
                    compose.onNodeWithContentDescription("${formatSignedNumber(d)} vs the period before", useUnmergedTree = true)
                        .assertExists()
                }
            }
        }
        compose.runOnIdle { scale = 1f; percent = 62f; delta = 23f }
        compose.onNodeWithText("High", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(TirBand.HIGH, selected) }
        compose.onNodeWithText("High", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertNull(selected) }
        // A band with no time is left out rather than shown empty.
        compose.onNodeWithText("Very high", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun dayPartsCombineIntoOneWindowWithItsOwnTimesAndClearBackToTheWholeDay() {
        // Evening is 40% in range, the rest 95%; the night block has no readings at all.
        val hourly = (0..23).map { hour ->
            when (hour / 6) {
                0 -> HourlyGlucoseStats(hour)
                3 -> HourlyGlucoseStats(hour, TimeInRangeBreakdown(inRangePercent = 40f, highPercent = 60f), 100f, 10)
                else -> HourlyGlucoseStats(hour, TimeInRangeBreakdown(inRangePercent = 95f, highPercent = 5f), 100f, 10)
            }
        }
        val summary = StatsSummary(
            tir = TimeInRangeBreakdown(inRangePercent = 90f, highPercent = 10f),
            parts = object : StatsSummaryParts by StatsSummaryParts.Empty {
                override val comparison = comparison(23f)
                override val hourlyStats = hourly
            }
        )
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(328.dp)) {
                    GlycemicOverviewCard(summary, StatsTargets(), GlucoseUnit.MMOL, StatsTimeRange.DAY_14, null, {})
                }
            }
        }
        compose.onNodeWithText("90.0%", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("+23 vs the period before", useUnmergedTree = true).assertExists()
        // Each block shows its own time in range before any tap; one with no readings is off.
        compose.onNodeWithContentDescription("Evening, 18:00–24:00, 40% In range", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Night", substring = true).assertIsNotEnabled()

        compose.onNodeWithContentDescription("Evening", substring = true, useUnmergedTree = true).performClick()
        compose.onNodeWithText("40.0%", useUnmergedTree = true).assertExists()
        // A six-hour block: 60% high is 3 h 36 min of it, not of the day.
        compose.onNodeWithText("3 h 36 min", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("18:00–24:00", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("+23 vs the period before", useUnmergedTree = true).assertDoesNotExist()

        // A second block joins the first: (95 + 40) / 2 over twelve hours.
        compose.onNodeWithContentDescription("Afternoon", substring = true, useUnmergedTree = true).performClick()
        compose.onNodeWithText("67.5%", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("12:00–24:00", useUnmergedTree = true).assertExists()

        compose.onNodeWithContentDescription("Clear", useUnmergedTree = true).performClick()
        compose.onNodeWithText("90.0%", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("12:00–24:00", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theCountTakesTwoLinesBeforeTheDateChipLeavesItsSide() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(328.dp)) {
                        StatsRangeSelectorControl(
                            StatsTimeRange.DAY_14, range, false, true, 4132,
                            {}, {}, coveragePercent = 99f
                        )
                    }
                }
            }
        }
        val count = compose.onNodeWithText("4,132 Points", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val active = compose.onNode(hasText("Active\u00A099%"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithText(formatStatsDateRange(range)!!, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("coverage should wrap under the count", active.top >= count.bottom - 1f)
        assertTrue("date chip should stay beside the count", chip.top < active.bottom && chip.left > count.right)
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
