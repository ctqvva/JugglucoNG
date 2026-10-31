package tk.glucodata.ui

import org.junit.Assert.*
import org.junit.Test

class ChartViewportMotionTests {
    private val now = 1_793_314_600_123L

    @Test fun epochSizedTimesKeepMillisecondPrecisionDuringAnimation() {
        assertEquals(now, chartTimeAtProgress(now, now + 60_000L, 0f))
        assertEquals(now + 60_000L, chartTimeAtProgress(now, now + 60_000L, 1f))
        assertEquals(now + 30_000L, chartTimeAtProgress(now, now + 60_000L, 0.5f))
        assertEquals(now + 1L, chartTimeAtProgress(now, now + 1000L, 0.001f))
        assertEquals(now - 30_000L, chartTimeAtProgress(now, now - 60_000L, 0.5f))
    }

    @Test fun forwardAndBackwardScrollsNeverOvershootOrReverseDirection() {
        for (delta in listOf(-43_200_000L, -60_000L, 60_000L, 43_200_000L)) {
            val frames = (0..120).map { chartTimeAtProgress(now, now + delta, it / 120f) }
            assertTrue(frames.all { it in minOf(now, now + delta)..maxOf(now, now + delta) })
            assertTrue(frames.zipWithNext().all { (a, b) -> if (delta > 0) b >= a else b <= a })
        }
        assertEquals(now, chartTimeAtProgress(now, now + 60_000L, -0.2f))
        assertEquals(now + 60_000L, chartTimeAtProgress(now, now + 60_000L, 1.2f))
    }

    @Test fun roleHandoffDoesNotRecenterForOldOrEmptyPredictionFrames() {
        val state = ChartAutoFollowState()
        assertTrue(state.observe("A", now))
        assertFalse(state.observe("B", now))
        // Predictions and peer queries may emit several times at the same timeline point.
        repeat(4) { assertFalse(state.observe("B", now)) }
        assertFalse(state.canFollow(now - 60_000L))
        assertTrue(state.observe("B", now + 60_000L))
        assertTrue(state.canFollow(now + 60_000L))
        assertFalse(state.observe("A", now + 60_000L))
        assertTrue(state.observe("A", now + 120_000L))
    }

    @Test fun initialDataAndSameSensorRefreshesStillFollowLiveReadings() {
        val state = ChartAutoFollowState()
        assertTrue(state.observe(null, 0L))
        assertTrue(state.observe("A", now))
        assertTrue(state.observe("a", now))
        assertTrue(state.observe("A", now + 60_000L))
    }
}
