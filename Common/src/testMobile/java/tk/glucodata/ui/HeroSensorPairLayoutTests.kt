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
import androidx.compose.runtime.*
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    private fun render(width: Int, fontScale: Float, primary: String, peer: String, secondary: String? = null, peerSecondary: String? = null, range: String? = null) {
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
                                primaryRangeText = range,
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
        val output = File("build/outputs/layout-regressions/hero-${width}-${fontScale}-${secondary != null}-${peerSecondary != null}-${range != null}.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag("header").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val mainBounds = compose.onNodeWithText(primary).fetchSemanticsNode().boundsInRoot
        val peerBounds = compose.onNodeWithText(peer, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("readings stay alongside each other", mainBounds.top < peerBounds.bottom && peerBounds.top < mainBounds.bottom)
        for (value in listOfNotNull(primary, peer, secondary, peerSecondary, range)) {
            compose.onNodeWithText(value, substring = true, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val results = mutableListOf<TextLayoutResult>()
                    action(results)
                    val layout = results.single()
                    // Paragraph dimensions are floats; Text rounds its size to integer px.
                    assertTrue("$value height fits: paragraph=${layout.multiParagraph.height}, box=${layout.size.height}", layout.multiParagraph.height <= layout.size.height + 1f)
                    assertTrue("$value width fits", layout.getLineRight(0) <= layout.size.width + 1f)
                    if (value == primary) {
                        assertTrue("main remains a hero reading", layout.layoutInput.style.fontSize.value * fontScale >= 24f)
                    }
                    if (value == peer) {
                        assertTrue("peer remains readable", layout.layoutInput.style.fontSize.value * fontScale >= 12f)
                    }
                }
        }
        if (range != null) {
            val rangeBounds = compose.onNodeWithText(range).fetchSemanticsNode().boundsInRoot
            val headerBounds = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
            assertTrue("range belongs just beneath the main reading", rangeBounds.top >= mainBounds.bottom)
            assertEquals("range aligns with the main reading", mainBounds.left, rangeBounds.left, 1f)
            assertTrue("range has breathing room below", headerBounds.bottom - rangeBounds.bottom >= 16f)
        }
        val chipBounds = compose.onNodeWithContentDescription("peer").fetchSemanticsNode().boundsInRoot
        assertTrue("peer retains a 48dp touch target", chipBounds.height >= 48f)
        compose.onNodeWithContentDescription("peer").performClick()
        compose.runOnIdle { assertEquals("peer", selected) }
    }

    @Test fun ribbonToggleHidesRangeImmediatelyEvenDuringAnUnfinishedSensorSwap() {
        var context: android.content.Context? = null
        var switched by mutableStateOf(false)
        compose.setContent {
            context = LocalContext.current
            val enabled = rememberUncertaintyRibbonEnabled()
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(220.dp).testTag("header")) {
                    DashboardHeroSensorPair(
                        primaryText = if (switched) "8.3" else "6.7",
                        primarySensorId = if (switched) "peer" else "main",
                        primaryStyle = MaterialTheme.typography.displayMedium,
                        primarySecondaryStyle = MaterialTheme.typography.headlineSmall,
                        primaryColor = MaterialTheme.colorScheme.onSurface,
                        // Stored intervals deliberately remain in the data after the toggle.
                        primaryRangeText = "5.1–8.3",
                        primaryTrend = TrendEngine.TrendResult(TrendEngine.TrendState.Flat, 0f, 0f, 1f),
                        primaryArrowColor = MaterialTheme.colorScheme.onSurface,
                        primaryDeltaText = null,
                        peer = PeerCurrentReading(if (switched) "main" else "peer", if (switched) "6.7" else "8.3", null, 0f, 1L),
                        peerStyle = MaterialTheme.typography.headlineSmall,
                        selectedSensorIds = listOf("main", "peer"),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        onPeerClick = {}, uncertaintyEnabled = enabled
                    )
                }
            }
        }
        compose.onNodeWithText("5.1–8.3").assertExists()
        val height = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { switched = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { GlucoseUncertaintyDisplay.setRibbonEnabled(requireNotNull(context), false) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("5.1–8.3").assertDoesNotExist()
        assertEquals(height, compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.height, 0f)
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { GlucoseUncertaintyDisplay.setRibbonEnabled(requireNotNull(context), true) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("5.1–8.3").assertExists()
    }

    @Test fun outgoingMainRemainsAvailableWhilePeerResolutionCatchesUp() {
        var main by mutableStateOf("sensor-a")
        var peers by mutableStateOf(listOf(PeerCurrentReading("sensor-b", "6.1", null, 0f, 1L)))
        compose.setContent {
            val current = PeerCurrentReading(main, if (main == "sensor-a") "5.0" else "6.1", null, 0f, 1L)
            val candidates = rememberHeroPeerReadings(current, peers, true)
            val selected = SecondarySensorDisplay.peerForHero(
                DisplayValues(primaryValue = 5f, primaryStr = current.primaryStr, fullFormatted = current.primaryStr),
                main, candidates, 1L, 330_000L)
            Text(selected?.sensorId ?: "empty")
        }
        compose.onNodeWithText("sensor-b").assertExists()
        compose.runOnIdle { main = "sensor-b" }
        compose.onNodeWithText("sensor-a").assertExists()
        compose.runOnIdle { peers = listOf(PeerCurrentReading("sensor-a", "5.1", null, 0f, 1L)) }
        compose.onNodeWithText("sensor-a").assertExists()
    }

    @Test fun switchingSensorsAnimatesOneFrameAtAConstantHeight() {
        var switched by mutableStateOf(false)
        var showRaw by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(220.dp).testTag("header")) {
                    DashboardHeroSensorPair(
                        primaryText = if (switched) "6.1" else "5.0",
                        primarySensorId = if (switched) "sensor-b" else "sensor-a",
                        primaryStyle = MaterialTheme.typography.displayMedium,
                        primarySecondaryText = "4.1".takeIf { showRaw },
                        primarySecondaryStyle = MaterialTheme.typography.headlineSmall,
                        primaryColor = MaterialTheme.colorScheme.onSurface,
                        primaryRangeText = "3.9–6.0".takeUnless { switched },
                        primaryTrend = TrendEngine.TrendResult(TrendEngine.TrendState.Flat, 0f, 0f, 1f),
                        primaryArrowColor = MaterialTheme.colorScheme.onSurface,
                        primaryDeltaText = null,
                        peer = PeerCurrentReading(if (switched) "sensor-a" else "sensor-b", if (switched) "5.0" else "6.1", null, 0f, 1L),
                        peerStyle = MaterialTheme.typography.headlineSmall,
                        selectedSensorIds = listOf("sensor-a", "sensor-b"),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        onPeerClick = {}, contentHeight = 72.dp
                    )
                }
            }
        }
        val originalHeight = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { showRaw = true }
        assertEquals(originalHeight, compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.height, 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { switched = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("3.9–6.0").assertExists() // Outgoing frame keeps its own range.
        val before = compose.onAllNodesWithText("6.1", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.left }
        compose.mainClock.advanceTimeBy(64)
        val after = compose.onAllNodesWithText("6.1", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.left }
        assertTrue("sensor swap has positional motion", before != after)
        assertEquals(originalHeight, compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.height, 0f)
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("3.9–6.0").assertDoesNotExist()
        assertEquals(originalHeight, compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.height, 0f)
    }

    @Test fun portraitHeaderMeasuresAndPeerRemainsSelectable() = render(360, 1f, "112", "85")
    @Test fun largeTextAndMmolValuesSurviveIntrinsicMeasurement() = render(360, 2f, "6.2", "4.7")
    @Test fun mainRawLaneKeepsPeerAtHeroSize() = render(360, 1f, "4,8", "4,4", secondary = "3,4")
    @Test fun peerRawLaneStaysInsideSelectableGroup() = render(360, 1f, "4,5", "4,8", peerSecondary = "3,4")
    @Test fun bothSensorsKeepTheirLanesAtLargeFont() = render(320, 2f, "112", "85", secondary = "138", peerSecondary = "90")
    @Test fun rawAndUncertaintyFitWithoutAddingHeight() = render(360, 1f, "5,0", "5,9", secondary = "4,1", range = "3,9–6,0")
    @Test fun widerHeaderMeasuresWithoutSubcomposition() = render(600, 1f, "112", "85")
}
