package tk.glucodata.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.ui.overlay.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayMotionTests {
    @get:Rule val compose = createComposeRule()

    @Test fun refreshAndSensorSwitchRetainACompleteFrameAndRejectCancelledResolution() {
        var revision by mutableIntStateOf(0)
        var sensor by mutableStateOf("main")
        val pending = mutableListOf<CompletableDeferred<OverlayReadings>>()
        val observed = mutableListOf<String>()
        compose.setContent {
            val state by rememberOverlayReadings(sensor, true, revision) { _, _ ->
                CompletableDeferred<OverlayReadings>().also { pending.add(it) }.await()
            }
            val shown = "${state.sensorId}:${state.peers.firstOrNull()?.primaryStr.orEmpty()}"
            SideEffect { observed.add(shown) }
            Text(shown)
        }
        fun frame(id: String, value: String) = OverlayReadings(id, null, listOf(PeerCurrentReading("peer", value, null, 0f, 1L)))
        compose.runOnIdle { pending[0].complete(frame("main", "85")) }
        compose.onNodeWithText("main:85").assertExists()
        compose.runOnIdle { revision++ }
        compose.onNodeWithText("main:85").assertExists()
        compose.runOnIdle { sensor = "next" }
        compose.runOnIdle { pending[1].complete(frame("main", "86")) }
        compose.onNodeWithText("main:85").assertExists()
        compose.runOnIdle { pending[2].complete(frame("next", "112")) }
        compose.onNodeWithText("next:112").assertExists()
        assertFalse("no empty peer frame during refresh", observed.dropWhile { it != "main:85" }.any { it.endsWith(":") })
    }

    @Test fun floatingValuesKeepOldAndNewGlyphsDuringActualMotion() = checkFloatingMotion(false)
    @Test fun outlinedFloatingValuesAnimateBothLayersTogether() = checkFloatingMotion(true)

    private fun checkFloatingMotion(outline: Boolean) {
        var value by mutableStateOf("5.0")
        compose.setContent {
            FloatingStyledText(value, 24f, FontFamily.SansSerif, FontWeight.Normal,
                Color.White, Color.Black, Shadow(), outline)
        }
        val oldTop = compose.onAllNodesWithText("5.0", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { value = "5.8" }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
        val oldLayers = compose.onAllNodesWithText("5.0", useUnmergedTree = true).fetchSemanticsNodes()
        val newLayers = compose.onAllNodesWithText("5.8", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(if (outline) 2 else 1, oldLayers.size)
        assertEquals(if (outline) 2 else 1, newLayers.size)
        assertTrue("old glyphs slide out instead of redrawing in place", oldLayers.all { it.boundsInRoot.top > oldTop })
        assertEquals("outline and fill move together", 1, oldLayers.map { it.boundsInRoot.top }.distinct().size)
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("5.0").assertDoesNotExist()
        compose.onAllNodesWithText("5.8", useUnmergedTree = true).onFirst().assertIsDisplayed()
    }
}
