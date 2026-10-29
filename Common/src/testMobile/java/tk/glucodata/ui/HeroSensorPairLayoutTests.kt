package tk.glucodata.ui

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertTrue
import tk.glucodata.ui.theme.AppTypography
import tk.glucodata.ui.theme.displayLargeExpressive
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.logic.TrendEngine

/** Real Compose measurement: a pure layout-policy test cannot detect SubcomposeLayout crashes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w640dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeroSensorPairLayoutTests {
    @get:Rule val compose = createComposeRule()

    private fun render(width: Int, fontScale: Float, primary: String, peer: String, secondary: String? = null, peerSecondary: String? = null) {
        var selected: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                    // Same intrinsic-height request and weighted cards as the portrait header.
                    Row(Modifier.width(width.dp).height(IntrinsicSize.Min).testTag("header").background(MaterialTheme.colorScheme.surface)) {
                        Box(Modifier.weight(0.7f).fillMaxHeight().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                            DashboardHeroSensorPair(
                                primaryText = primary,
                                primaryStyle = MaterialTheme.typography.displayLargeExpressive,
                                primarySecondaryText = secondary,
                                primarySecondaryStyle = MaterialTheme.typography.headlineMedium,
                                primaryColor = MaterialTheme.colorScheme.onSurface,
                                primaryRangeText = null,
                                primaryTrend = TrendEngine.TrendResult(TrendEngine.TrendState.Unknown, 1f, 0f, 1f),
                                primaryArrowColor = MaterialTheme.colorScheme.onSurface,
                                primaryDeltaText = "+0.2",
                                peer = PeerCurrentReading("peer", peer, peerSecondary, -1f, 1L),
                                peerStyle = MaterialTheme.typography.headlineMedium,
                                selectedSensorIds = listOf("main", "peer"),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onPeerClick = { selected = it }
                            )
                        }
                        Text("Sensor", Modifier.weight(0.3f), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        compose.onNodeWithText(primary).assertIsDisplayed()
        compose.onNodeWithText(peer).assertIsDisplayed()
        val output = File("build/outputs/layout-regressions/hero-${width}-${fontScale}-${secondary != null}-${peerSecondary != null}.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag("header").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val mainBounds = compose.onNodeWithText(primary).fetchSemanticsNode().boundsInRoot
        val peerBounds = compose.onNodeWithText(peer, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("readings stay alongside each other", mainBounds.top < peerBounds.bottom && peerBounds.top < mainBounds.bottom)
        for (value in listOfNotNull(primary, peer, secondary, peerSecondary)) {
            compose.onNodeWithText(value, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val results = mutableListOf<TextLayoutResult>()
                    action(results)
                    val layout = results.single()
                    // Paragraph dimensions are floats; Text rounds its size to integer px.
                    assertTrue("$value height fits", layout.multiParagraph.height <= layout.size.height + 1f)
                    assertTrue("$value width fits", layout.getLineRight(0) <= layout.size.width + 1f)
                    if (value == primary) {
                        assertTrue("main remains a hero reading", layout.layoutInput.style.fontSize.value * fontScale >= 40f)
                    }
                    if (value == peer) {
                        assertTrue("peer remains readable", layout.layoutInput.style.fontSize.value * fontScale >= 20f)
                    }
                }
        }
        val chipBounds = compose.onNodeWithContentDescription("peer").fetchSemanticsNode().boundsInRoot
        assertTrue("peer retains a 48dp touch target", chipBounds.height >= 48f)
        compose.onNodeWithContentDescription("peer").performClick()
        compose.runOnIdle { assertEquals("peer", selected) }
    }

    @Test fun portraitHeaderMeasuresAndPeerRemainsSelectable() = render(360, 1f, "112", "85")
    @Test fun largeTextAndMmolValuesSurviveIntrinsicMeasurement() = render(360, 2f, "6.2", "4.7")
    @Test fun mainRawLaneKeepsPeerAtHeroSize() = render(360, 1f, "4,8", "4,4", secondary = "3,4")
    @Test fun peerRawLaneStaysInsideSelectableGroup() = render(360, 1f, "4,5", "4,8", peerSecondary = "3,4")
    @Test fun bothSensorsKeepTheirLanesAtLargeFont() = render(320, 2f, "112", "85", secondary = "138", peerSecondary = "90")
    @Test fun widerHeaderMeasuresWithoutSubcomposition() = render(600, 1f, "112", "85")
}
