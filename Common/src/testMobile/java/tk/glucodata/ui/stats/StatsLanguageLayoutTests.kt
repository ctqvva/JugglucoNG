package tk.glucodata.ui.stats

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.R
import tk.glucodata.ui.FontScaleCap
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w1400dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsLanguageLayoutTests {
    @get:Rule val compose = createComposeRule()
    private val originalLocale = Locale.getDefault()
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }

    private val tags = listOf("en", "be", "de", "fr", "hu", "it", "mn", "nl", "pl", "pt", "ru", "so", "sv", "tr", "uk", "zh")
    private val percentages = listOf(0.7f, 1.9f, 75.2f, 8.2f, 14.6f)
    private val labels = listOf(R.string.very_high, R.string.high_range, R.string.in_range, R.string.low_range, R.string.very_low)
    private val range = StatsDateRange(
        Instant.parse("2026-10-27T12:00:00Z").toEpochMilli(),
        Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
    )
    private val targets = StatsTargets(64f, 162f, 58f, 252f)
    private val summary = StatsSummary(
        readingCount = 19621, avgMgDl = 88f, medianMgDl = 83f, p25MgDl = 67f, p75MgDl = 104f,
        stdDevMgDl = 31f, cvPercent = 35.2f, gmiPercent = 5.4f,
        tir = TimeInRangeBreakdown(14.6f, 8.2f, 75.2f, 1.9f, 0.7f),
        parts = object : StatsSummaryParts by StatsSummaryParts.Empty {
            override val comparison = StatsComparison(23f, 0f, 0f, 0f, PeriodScalars())
        }
    )

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    private fun SemanticsNodeInteraction.assertFits() {
        val result = layout()
        assertTrue("height clipped: ${result.layoutInput.text}, size=${result.size}, paragraph=${result.multiParagraph.height}", result.multiParagraph.height <= result.size.height + 1f)
        for (line in 0 until result.lineCount) {
            assertFalse("ellipsized: ${result.layoutInput.text}", result.isLineEllipsized(line))
            assertTrue("width clipped: ${result.layoutInput.text}, size=${result.size}, left=${result.getLineLeft(line)}, right=${result.getLineRight(line)}", result.getLineRight(line) - result.getLineLeft(line) <= result.size.width + 1f)
            // No lone final letter from a squeezed translated word.
            if (result.lineCount > 1) assertTrue("single-letter line: ${result.layoutInput.text}", result.getLineEnd(line, visibleEnd = true) - result.getLineStart(line) > 1)
        }
    }

    @Test fun shippingLanguagesKeepTheOverviewCompactAndTheWholeScreenAligned() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        var language by mutableStateOf("en")
        var fontScale by mutableFloatStateOf(1f)
        var unit by mutableStateOf(GlucoseUnit.MMOL)
        var width by mutableIntStateOf(328)
        var observedSpecs = emptyList<MetricSpec>()
        compose.setContent {
            val configuration = Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
            }
            val context = base.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(1f, fontScale)) {
                FontScaleCap {
                    MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                        Surface {
                            Column(Modifier.width(width.dp).testTag("screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                StatsRangeSelectorControl(StatsTimeRange.DAY_14, range, false, true, 19621,
                                    {}, {}, coveragePercent = 97f)
                                GlycemicOverviewCard(summary, targets, unit,
                                    StatsTimeRange.DAY_14, null, {}, Modifier.testTag("overview"))
                                val metrics = StatsLayoutState().visibleMetrics.take(8)
                                val specs = metrics.map { metricSpec(it, summary, targets, unit) }
                                SideEffect { observedSpecs = specs }
                                MetricsGrid(StatsLayoutState().visibleMetrics.take(8), emptySet(), summary,
                                    targets, unit, emptySet(), {}, null, Modifier.testTag("metrics"))
                                PatternsHeader(context.getString(R.string.hourly_patterns),
                                    "12:00 · ${context.getString(R.string.median)} ${formatMgDl(128f, unit)}",
                                    "IQR ${formatMgDl(94f, unit)}–${formatMgDl(158f, unit)}")
                            }
                        }
                    }
                }
            }
        }
        for (tag in tags) for (w in if (tag in listOf("en", "ru")) listOf(328, 416) else listOf(328))
            for (scale in listOf(1f, 1.3f)) for (u in GlucoseUnit.entries) {
            compose.runOnIdle {
                Locale.setDefault(Locale.forLanguageTag(tag)); language = tag; fontScale = scale; unit = u; width = w
            }
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(tag)))
            })
            val output = File("build/outputs/layout-regressions/languages/stats-$tag-$w-$scale-$u.png")
            output.parentFile?.mkdirs()
            output.outputStream().use {
                compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val overview = compose.onNodeWithTag("overview").fetchSemanticsNode().boundsInRoot
            val ring = compose.onNodeWithContentDescription("${formatLocalePercent(75f)} ${context.getString(R.string.tir)}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("oversized ring in $tag $scale $u", ring.width <= 160.5f)
            assertTrue("stacked normal-phone overview in $tag $scale $u", ring.right < overview.right - 96f)
            assertTrue("overview too tall in $tag $scale $u: ${overview.height}", overview.height <= 360f)
            var right: Float? = null
            for (p in percentages) {
                val node = compose.onNode(hasText(formatLocalePercent(p, 1)) and hasAnyAncestor(hasTestTag("overview")), useUnmergedTree = true)
                node.assertFits()
                val end = node.fetchSemanticsNode().boundsInRoot.right
                right?.let { assertEquals("percent column drift in $tag", it, end, 0.5f) }
                right = end
            }
            for (id in labels) {
                compose.onNode(hasText(context.getString(id)) and hasAnyAncestor(hasTestTag("overview")), useUnmergedTree = true).assertFits()
            }
            val patterns = compose.onNodeWithText(context.getString(R.string.stats_patterns), useUnmergedTree = true)
            patterns.assertFits()
            assertEquals(1, patterns.layout().lineCount)
            for (choice in StatsTimeRange.entries) compose.onNodeWithText(context.getString(choice.labelResId), useUnmergedTree = true).assertFits()
            val inMetrics = hasAnyAncestor(hasTestTag("metrics"))
            for (spec in observedSpecs) {
                for (text in listOf(spec.title, spec.value, spec.status, spec.meta).filter { it.isNotBlank() }) {
                    val matches = compose.onAllNodes(hasText(text) and inMetrics, useUnmergedTree = true)
                    for (index in matches.fetchSemanticsNodes().indices) matches[index].assertFits()
                }
            }
            // Paired cards reserve the same title/status/meta lines: text baselines must agree.
            for ((left, rightSpec) in observedSpecs.chunked(2).map { it[0] to it[1] }) {
                fun bounds(text: String, below: Float = 0f) = compose.onAllNodes(hasText(text) and inMetrics, useUnmergedTree = true)
                    .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.top >= below - 0.5f }.minBy { it.top }
                val leftTitle = bounds(left.title)
                val rightTitle = bounds(rightSpec.title)
                // At the minimum readable width, this pair intentionally becomes two full-width cards.
                if (kotlin.math.abs(leftTitle.left - rightTitle.left) < 1f && rightTitle.top > leftTitle.bottom) continue
                val top = maxOf(leftTitle.bottom, rightTitle.bottom)
                if (left.status.isNotBlank() && rightSpec.status.isNotBlank()) {
                    assertEquals("status rows drift in $tag", bounds(left.status, top).top, bounds(rightSpec.status, top).top, 0.5f)
                }
                if (left.meta.isNotBlank() && rightSpec.meta.isNotBlank()) {
                    assertEquals("footnotes drift in $tag", bounds(left.meta, top).bottom, bounds(rightSpec.meta, top).bottom, 0.5f)
                }
            }
            compose.onNodeWithText(formatStatsDateRange(range, Locale.forLanguageTag(tag))!!, useUnmergedTree = true).assertFits()
            val active = context.getString(R.string.stats_coverage_active, 97).replace(' ', '\u00A0')
            compose.onNode(hasText(active, substring = true), useUnmergedTree = true).assertFits()

        }
    }

    @Test fun longMetricTitleAndDetailsRemainReadableAndExpandable() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                MaterialTheme(typography = AppTypography) {
                    ScoreTile(
                        title = "Длительность периода", value = "123,4", status = "Умеренная вариабельность",
                        meta = "Диапазон 3,6–9,0", tone = MaterialTheme.colorScheme.primary,
                        expanded = expanded, onToggleExpanded = { expanded = !expanded },
                        modifier = Modifier.width(160.dp), infoText = "Подробное объяснение показателя",
                        forceTitleOwnRow = true, titleLines = 2, statusLines = 2, metaLines = 2
                    )
                }
            }
        }
        for (text in listOf("Длительность периода", "123,4", "Умеренная вариабельность", "Диапазон 3,6–9,0")) {
            compose.onNodeWithText(text, useUnmergedTree = true).assertFits()
        }
        compose.onNodeWithText("Длительность периода").performClick()
        compose.onNodeWithText("Подробное объяснение показателя").assertIsDisplayed()
        compose.onNodeWithText("Длительность периода").performClick()
        compose.onNodeWithText("Подробное объяснение показателя").assertDoesNotExist()
    }

    @Test fun ringAndNumbersStayInsideTheirBoundsAcrossWindowsAndDensities() {
        var width by mutableIntStateOf(328)
        var density by mutableFloatStateOf(1f)
        var scale by mutableFloatStateOf(1.3f)
        var selected by mutableStateOf<TirBand?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                FontScaleCap {
                    MaterialTheme(typography = AppTypography) {
                        Box(Modifier.width(width.dp)) {
                            GlycemicOverviewCard(summary, targets, GlucoseUnit.MMOL,
                                StatsTimeRange.DAY_14, selected, { selected = it }, Modifier.testTag("overview"))
                        }
                    }
                }
            }
        }
        for (w in listOf(240, 288, 328, 400, 600)) for (d in listOf(1f, 2f)) for (s in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { width = w; density = d; scale = s }
            val card = compose.onNodeWithTag("overview").fetchSemanticsNode().boundsInRoot
            val ring = compose.onNodeWithContentDescription("75% TIR", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(ring.left >= card.left && ring.right <= card.right && ring.top >= card.top && ring.bottom <= card.bottom)
            val innerRadius = ring.width / 2f - 13f * d
            for (text in listOf("75%", "TIR")) {
                val node = compose.onNodeWithText(text, useUnmergedTree = true)
                node.assertFits()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                for (corner in listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight)) {
                    assertTrue("$text crosses ring at $w/$d/$s", (corner - ring.center).getDistance() < innerRadius)
                }
            }
            for (p in percentages) compose.onNodeWithText(formatLocalePercent(p, 1), useUnmergedTree = true).assertFits()
        }
        compose.onNodeWithText("High", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(TirBand.HIGH, selected) }
    }
}
