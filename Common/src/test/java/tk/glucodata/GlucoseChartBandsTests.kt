package tk.glucodata

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.GlucoseChartBands

/**
 * The gradient the glucose curve is stroked with. A curve is coloured by where
 * it sits on the canvas, so what matters is that the stops land on the
 * threshold lines, stay ordered, and stay inside the canvas.
 */
class GlucoseChartBandsTests {

    private val veryHigh = Color.Red
    private val high = Color.Yellow
    private val inRange = Color.White
    private val low = Color.Magenta
    private val veryLow = Color.Blue

    // A 400px canvas: veryHigh line at 40, high at 100, low at 300, veryLow at 360.
    private fun stops(height: Float = 400f, fade: Float = 18f) = GlucoseChartBands.verticalStops(
        veryHigh = veryHigh, high = high, inRange = inRange, low = low, veryLow = veryLow,
        yVeryHigh = 40f, yHigh = 100f, yLow = 300f, yVeryLow = 360f,
        chartHeightPx = height, fadePx = fade,
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
        assertEquals(veryHigh, byPosition[0f])
        assertEquals(veryHigh, byPosition[40f / 400f])
        assertEquals(high, byPosition[100f / 400f])
        assertEquals(low, byPosition[300f / 400f])
        assertEquals(veryLow, byPosition[360f / 400f])
        assertEquals(veryLow, byPosition[1f])
    }

    @Test
    fun theInRangeBandSpansBetweenTheTargetLines() {
        val inRangeStops = stops().filter { it.second == inRange }.map { it.first }
        assertEquals(2, inRangeStops.size)
        // Just inside the target lines, offset by the fade.
        assertEquals(118f / 400f, inRangeStops[0], 1e-6f)
        assertEquals(282f / 400f, inRangeStops[1], 1e-6f)
    }

    @Test
    fun aFadeWiderThanTheRangeStillProducesOrderedStops() {
        // A tall fade on a short canvas crosses the two in-range stops over;
        // sorting has to keep the gradient legal rather than throw.
        val positions = stops(fade = 200f).map { it.first }
        assertEquals(positions.sorted(), positions)
        assertTrue(positions.all { it in 0f..1f })
    }

    @Test
    fun anUnmeasuredCanvasProducesNoStops() {
        assertTrue(stops(height = 0f).isEmpty())
        assertTrue(stops(height = -5f).isEmpty())
    }
}
