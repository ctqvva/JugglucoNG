package tk.glucodata

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.GlucoseChartBands

/**
 * The gradient the glucose curve is stroked with. A curve is coloured by where
 * it sits on the canvas, so the stops must land on the threshold lines, stay
 * ordered, and — the case that actually broke on the watch — stay correct when
 * a threshold falls outside the drawn area.
 */
class GlucoseChartBandsTests {

    private val veryHigh = Color.Red
    private val high = Color.Yellow
    private val inRange = Color.White
    private val low = Color.Magenta
    private val veryLow = Color.Blue
    private val height = 400f

    private fun stops(
        yVeryHigh: Float = 40f,
        yHigh: Float = 100f,
        yLow: Float = 300f,
        yVeryLow: Float = 360f,
        chartHeightPx: Float = height,
        fade: Float = 18f,
    ) = GlucoseChartBands.verticalStops(
        veryHigh = veryHigh, high = high, inRange = inRange, low = low, veryLow = veryLow,
        yVeryHigh = yVeryHigh, yHigh = yHigh, yLow = yLow, yVeryLow = yVeryLow,
        chartHeightPx = chartHeightPx, fadePx = fade,
    )

    @Test
    fun stopsAreOrderedAndNormalised() {
        val positions = stops().map { it.first }
        assertEquals(positions.sorted(), positions)
        assertTrue(positions.all { it in 0f..1f })
    }

    @Test
    fun bandsLandOnTheirThresholds() {
        val byPosition = stops().toMap()
        assertEquals(veryHigh, byPosition[40f / height])
        assertEquals(high, byPosition[100f / height])
        assertEquals(low, byPosition[300f / height])
        assertEquals(veryLow, byPosition[360f / height])
    }

    @Test
    fun theInRangeBandSpansBetweenTheTargetLines() {
        val inRangeStops = stops().filter { it.second == inRange }.map { it.first }
        assertEquals(2, inRangeStops.size)
        assertEquals(118f / height, inRangeStops[0], 1e-6f)
        assertEquals(282f / height, inRangeStops[1], 1e-6f)
    }

    @Test
    fun anEntirelyInRangeViewportIsNeutralEndToEnd() {
        // The watch fits its y-range to the data, so a flat in-range curve puts
        // every threshold off-canvas. Clamping their positions used to reorder
        // the stops and paint a band colour across the whole trace.
        val result = stops(yVeryHigh = -900f, yHigh = -600f, yLow = 700f, yVeryLow = 900f)
        assertTrue("expected a neutral gradient, got $result", result.all { it.second == inRange })
    }

    @Test
    fun aViewportBelowEveryThresholdIsEntirelyVeryLow() {
        // Everything drawn sits under the very-low line: the whole trace is
        // very-low coloured, not a gradient of bands that are not on screen.
        val result = stops(yVeryHigh = -900f, yHigh = -800f, yLow = -300f, yVeryLow = -100f)
        assertTrue("expected all very-low, got $result", result.all { it.second == veryLow })
    }

    @Test
    fun aViewportAboveEveryThresholdIsEntirelyVeryHigh() {
        val result = stops(yVeryHigh = 500f, yHigh = 600f, yLow = 800f, yVeryLow = 900f)
        assertTrue("expected all very-high, got $result", result.all { it.second == veryHigh })
    }

    @Test
    fun aPartlyVisibleBandKeepsTheEdgeColourItActuallyHas() {
        // Target low is on screen but very-low is below it: the bottom edge must
        // read as low-going-to-very-low, never as the neutral tone.
        val result = stops(yVeryHigh = -900f, yHigh = -600f, yLow = 300f, yVeryLow = 500f)
        assertEquals(inRange, result.first().second)
        assertTrue("bottom edge should not be neutral, got $result", result.last().second != inRange)
    }

    @Test
    fun aFadeWiderThanTheRangeStillProducesOrderedStops() {
        val positions = stops(fade = 200f).map { it.first }
        assertEquals(positions.sorted(), positions)
        assertTrue(positions.all { it in 0f..1f })
    }

    @Test
    fun stopsCanBeReducedToAscendingPositions() {
        // A LinearGradient rejects positions that are not strictly ascending,
        // and off-canvas thresholds routinely collapse several onto the same
        // one. The complication dedupes before building its shader; if that
        // ever left fewer than two, the trace would lose its colouring.
        listOf(
            stops(),
            stops(yVeryHigh = -900f, yHigh = -600f, yLow = 700f, yVeryLow = 900f),
            stops(yVeryHigh = -900f, yHigh = -800f, yLow = -300f, yVeryLow = -100f),
            stops(yVeryHigh = 500f, yHigh = 600f, yLow = 800f, yVeryLow = 900f),
            stops(fade = 200f),
        ).forEach { list ->
            val ascending = mutableListOf<Float>()
            list.forEach { (position, _) ->
                if (ascending.lastOrNull()?.let { position > it } != false) ascending += position
            }
            assertTrue("collapsed to ${ascending.size} from $list", ascending.size >= 2)
            assertEquals(ascending.sorted(), ascending)
        }
    }

    @Test
    fun anUnmeasuredCanvasProducesNoStops() {
        assertTrue(stops(chartHeightPx = 0f).isEmpty())
        assertTrue(stops(chartHeightPx = -5f).isEmpty())
    }
}
