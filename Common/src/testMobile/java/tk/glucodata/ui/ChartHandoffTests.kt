package tk.glucodata.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.chart.HistoryChartModel
import tk.glucodata.chart.MainSensorOwnership

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ChartHandoffTests {
    @get:Rule val compose = createComposeRule()
    private fun inputs(primary: String, peer: String, mode: Int = 0) = ChartResolutionInputs(
        listOf(GlucosePoint(100f, "", 1_793_314_600_123L, sensorSerial = primary)),
        MultiSensorDisplayData(listOf(PeerSensorSeries(peer, 0, 0,
            listOf(GlucosePoint(110f, "", 1_793_314_600_123L, sensorSerial = peer)))), emptyMap()),
        0, 0, MainSensorOwnership.NONE, 0L, mode, false, false, primary, 0)

    @Test fun actualScrollAnimationMovesMonotonicallyWithinTheRequestedMinute() {
        val start = 1_793_314_600_123L
        var target by mutableLongStateOf(start)
        var shown by mutableLongStateOf(start)
        val frames = mutableListOf<Long>()
        compose.setContent {
            LaunchedEffect(target) {
                animateChartTime(shown, target) { shown = it; frames.add(it) }
            }
            Text(shown.toString())
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { frames.clear(); target = start + 60_000L }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        repeat(90) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(start + 60_000L, shown)
            assertTrue("there are intermediate frames, not quantized epoch jumps", frames.any { it > start && it < target })
            assertTrue("automatic scroll does not overshoot", frames.all { it in start..target })
            assertTrue("forward scroll never jerks right", frames.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test fun bothEmissionOrdersKeepTheCompleteChartUntilPrimaryAndPeersAgree() {
        val a = inputs("A", "B")
        val b = inputs("B", "A", mode = 2)
        var current by mutableStateOf(a)
        val built = mutableListOf<ChartResolutionInputs>()
        val visible = mutableListOf<String>()
        compose.setContent {
            val result = rememberChartResolution(current) { input ->
                synchronized(built) { built.add(input) }
                ChartResolution(input.safeData, emptyList(), HistoryChartModel.EMPTY, input)
            }
            val shown = result.inputs
            val label = "${shown?.primarySerial}:${shown?.multiSensorDisplay?.series?.firstOrNull()?.sensorId}:${shown?.viewMode}"
            SideEffect { visible.add(label) }
            Box(Modifier.size(200.dp)) { Text(label) }
        }
        compose.onNodeWithText("A:B:0").assertExists()
        // The primary query wins the race: new primary with old peers would drop a sensor.
        compose.runOnIdle { current = inputs("B", "B", mode = 2) }
        compose.onNodeWithText("A:B:0").assertExists()
        // The peer query wins the race on another refresh.
        compose.runOnIdle { current = inputs("A", "A", mode = 2) }
        compose.onNodeWithText("A:B:0").assertExists()
        compose.runOnIdle { current = b }
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("B:A:2").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            throw AssertionError("visible=$visible built=${synchronized(built) { built.map { it.primarySerial } }} coherent=${b.isCoherent()}", failure)
        }
        assertTrue(visible.all { it == "A:B:0" || it == "B:A:2" })
        assertEquals("incoherent intermediate frames are never built", 2, synchronized(built) { built.size })
        compose.onNodeWithText("B:A:2").assertExists()
    }
}
