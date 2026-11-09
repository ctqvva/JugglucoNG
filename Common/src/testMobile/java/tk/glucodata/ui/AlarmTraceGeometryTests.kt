package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTraceGeometryTests {
    private val start = 0L
    private val end = 60 * 60_000L

    /** Five-minute readings from [from] to [to] minutes, rising linearly from [low] to [high]. */
    private fun ramp(from: Int, to: Int, low: Float, high: Float) =
        (from..to step 5).map { it to low + (high - low) * (it - from) / (to - from).coerceAtLeast(1) }.toTypedArray()

    private fun trace(vararg points: Pair<Int, Float>, minSpan: Float = 2f) = AlarmTrace(
        points = points.map { (minute, value) -> AlarmTracePoint(minute * 60_000L, value) },
        startMs = start,
        endMs = end,
        minSpan = minSpan,
    )

    @Test
    fun theHourSpansTheWidthUpToTheEndInset() {
        val segments = alarmTraceSegments(trace(*ramp(0, 60, 5f, 9f)), 400f, 100f, 0f, 0f, endInset = 40f)
        val only = segments.single()
        assertEquals(0f, only.first().x, 0.01f)
        assertEquals(360f, only.last().x, 0.01f)
        // Highest value at the top, lowest at the bottom.
        assertEquals(100f, only.first().y, 0.01f)
        assertEquals(0f, only.last().y, 0.01f)
    }

    @Test
    fun aGapInTheRecordBreaksTheLine() {
        val segments = alarmTraceSegments(
            trace(*ramp(0, 10, 5f, 5.2f), *ramp(30, 40, 6f, 6.1f)), 360f, 100f, 0f, 0f
        )
        assertEquals(2, segments.size)
    }

    @Test
    fun aSteadyHourStaysNearTheMiddleInsteadOfFillingTheHeight() {
        val segments = alarmTraceSegments(trace(*ramp(0, 60, 5.8f, 5.9f)), 360f, 100f, 0f, 0f)
        segments.single().forEach { assertTrue("${it.y}", it.y in 45f..55f) }
    }

    @Test
    fun fewerThanTwoReadingsDrawNothing() {
        assertTrue(alarmTraceSegments(trace(30 to 6f), 360f, 100f, 0f, 0f).isEmpty())
    }
}
