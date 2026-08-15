package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.alerts.DeltaAlarmState
import tk.glucodata.logic.TrendEngine

class SmoothingConsumerRegressionTests {
    private val minute = 60_000L

    @Test
    fun exactFallKeepsTrendDeltaAndFallingFastCheckpoint() {
        val raw = (0..20).map { index ->
            GlucosePoint(index * minute, 150f - 2f * index, 0f)
        }
        val smoothed = DataSmoothing.smoothNativePoints(
            points = raw,
            smoothingMinutes = 13,
            collapseChunks = false,
        )

        val rawTrend = TrendEngine.calculateTrend(raw, useRaw = false, isMmol = false)
        val smoothedTrend = TrendEngine.calculateTrend(smoothed, useRaw = false, isMmol = false)
        assertEquals(rawTrend.velocity, smoothedTrend.velocity, 1e-4f)

        fun fiveMinuteDelta(points: List<GlucosePoint>): Float {
            val newest = points.last()
            val previous = points[points.lastIndex - 5]
            return GlucoseDelta.fiveMinuteDelta(
                newest.timestamp,
                newest.value,
                previous.timestamp,
                previous.value,
            )
        }
        assertEquals(fiveMinuteDelta(raw), fiveMinuteDelta(smoothed), 1e-4f)

        fun fallingFastCheckpoint(points: List<GlucosePoint>): Long {
            val state = DeltaAlarmState(falling = true)
            var firedAt = -1L
            for (point in points) {
                if (state.shouldTrigger(
                        enabled = true,
                        activeNow = true,
                        snoozed = false,
                        value = point.value,
                        readingTimeMs = point.timestamp,
                        deltaThreshold = 10f,
                        deltaCount = 3,
                        deltaBorder = 120f,
                        intervalMinutes = 5,
                        earlyTriggerEnabled = false,
                    )
                ) {
                    firedAt = point.timestamp
                    break
                }
            }
            return firedAt
        }

        assertEquals(15 * minute, fallingFastCheckpoint(raw))
        assertEquals(fallingFastCheckpoint(raw), fallingFastCheckpoint(smoothed))
    }
}
