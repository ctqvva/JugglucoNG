package tk.glucodata

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
    fun aShortStripNeedsAFadeScaledToIt() {
        // The dashboard's preview navigator squeezes a whole day into ~42.dp.
        // The shared 18px fade is tuned for the full-height chart; on the strip
        // it spans most of a mmol/L, so the low tone bleeds well above the
        // target low and the strip reads as if it ignored the target range.
        val stripHeight = 115f // ~42.dp at 2.75x
        val minValue = 2.52f
        val valueRange = 6.66f
        fun y(value: Float) = stripHeight - ((value - minValue) / valueRange) * stripHeight

        fun stripStops(fade: Float) = GlucoseChartBands.verticalStops(
            veryHigh = veryHigh, high = high, inRange = inRange, low = low, veryLow = veryLow,
            yVeryHigh = y(13.9f), yHigh = y(8.3f), yLow = y(3.5f), yVeryLow = y(3.0f),
            chartHeightPx = stripHeight, fadePx = fade,
        )

        // 4.3 sits comfortably inside a 3.5-8.3 target and must read neutral.
        val sample = y(4.3f) / stripHeight
        assertTrue(
            "the shared fade should be the thing that breaks here",
            sampleAt(stripStops(GlucoseChartBands.DEFAULT_FADE_PX), sample) != inRange
        )
        assertEquals(inRange, sampleAt(stripStops(stripHeight * 0.025f), sample))
    }

    /** The gradient's colour at a normalised position, as a shader would read it. */
    private fun sampleAt(stops: List<Pair<Float, Color>>, position: Float): Color {
        if (stops.isEmpty()) return Color.Transparent
        if (position <= stops.first().first) return stops.first().second
        if (position >= stops.last().first) return stops.last().second
        for (index in 0 until stops.size - 1) {
            val (startAt, startColor) = stops[index]
            val (endAt, endColor) = stops[index + 1]
            if (position in startAt..endAt) {
                val span = endAt - startAt
                if (span <= 0f) return endColor
                return lerp(startColor, endColor, (position - startAt) / span)
            }
        }
        return stops.last().second
    }

    @Test
    fun anUnmeasuredCanvasProducesNoStops() {
        assertTrue(stops(chartHeightPx = 0f).isEmpty())
        assertTrue(stops(chartHeightPx = -5f).isEmpty())
    }
}
