package tk.glucodata.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w1200dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartHeaderBaselineTests {
    @get:Rule val compose = createComposeRule()

    @Test
    fun stateChipFollowsDateAcrossChartExpansionFontScaleDensityAndContent() {
        var density by mutableFloatStateOf(1f)
        var fontScale by mutableFloatStateOf(1f)
        var backdrop by mutableFloatStateOf(1f)
        var content by mutableIntStateOf(0)
        val date = "Friday, 30 October"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                    val baseline = chartHeaderTextBaseline(date, backdrop)
                    Box(Modifier.width(440.dp).height(180.dp).background(MaterialTheme.colorScheme.surface).testTag("header")) {
                        // The date's existing fixed-height container is the independent reference.
                        Box(
                            Modifier.align(Alignment.TopEnd)
                                .padding(top = androidx.compose.ui.unit.lerp(16.dp, 0.dp, backdrop))
                                .height(32.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(date, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                        Surface(
                            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp)
                                .chartHeaderBaseline(baseline.roundToInt()).widthIn(max = 190.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            // Match the state chip's padding and size animation. Its first line
                            // changes typography between hint-only, IOB/COB and expanded content.
                            Column(
                                Modifier.animateContentSize().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (content == 1) {
                                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("IOB: 0.5 U", Modifier.testTag("first"), style = MaterialTheme.typography.labelLargeEmphasized, maxLines = 1)
                                        Text("0.4 U", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    }
                                } else {
                                    Text(
                                        when (content) {
                                            0 -> "Insulin: 0,5 U"
                                            2 -> "COB: 12 g"
                                            else -> "Insulin on board: 0.5 U"
                                        },
                                        Modifier.testTag("first"),
                                        style = if (content == 0) MaterialTheme.typography.labelMedium
                                            else MaterialTheme.typography.labelLargeEmphasized,
                                        maxLines = if (content == 3) 2 else 1
                                    )
                                }
                                if (content == 3) {
                                    Text("Effective insulin: 0.4 U", style = MaterialTheme.typography.labelMedium)
                                    Text("Active until 16:30", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        for (d in listOf(1f, 2.75f)) for (scale in listOf(1f, 1.5f, 2f)) {
            for (progress in listOf(1f, 0.5f, 0f)) for (state in 0..3) {
                compose.runOnIdle {
                    density = d
                    fontScale = scale
                    backdrop = progress
                    content = state
                }
                val actual = baseline(compose.onNodeWithTag("first"))
                val expected = baseline(compose.onNodeWithText(date))
                assertEquals("density=$d font=$scale backdrop=$progress state=$state", expected, actual, 1f)
                assertTrue("First text must stay on the plot", compose.onNodeWithTag("first").fetchSemanticsNode().boundsInRoot.top >= 0f)
            }
        }
        // Save the screenshot's hint-only case at normal font size for visual inspection.
        compose.runOnIdle { density = 1f; fontScale = 1f; backdrop = 1f; content = 0 }
        val output = File("build/outputs/layout-regressions/chart-header-insulin.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag("header").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun baseline(node: SemanticsNodeInteraction): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return node.fetchSemanticsNode().boundsInRoot.top + layouts.single().firstBaseline
    }
}
