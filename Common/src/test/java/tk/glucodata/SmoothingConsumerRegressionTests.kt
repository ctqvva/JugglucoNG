package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.alerts.DeltaAlarmState
import tk.glucodata.logic.TrendEngine

class SmoothingConsumerRegressionTests {
    private val minute = 60_000L

    @Test
    fun exactFallKeepsTrendDeltaAndFallingFastCheckpoint() {
        fun fiveMinuteDelta(points: List<GlucosePoint>): Float {
            val newest = points.last()
            val previous = points.last { it.timestamp == newest.timestamp - 5 * minute }
            return GlucoseDelta.fiveMinuteDelta(
                newest.timestamp,
                newest.value,
                previous.timestamp,
                previous.value,
            )
        }
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

        for (cadenceMinutes in listOf(1, 5)) {
            val raw = (0..20 step cadenceMinutes).map { atMinute ->
                GlucosePoint(atMinute * minute, 150f - 2f * atMinute, 0f)
            }
            val rawTrend = TrendEngine.calculateTrend(raw, useRaw = false, isMmol = false)
            val rawDelta = fiveMinuteDelta(raw)
            val rawCheckpoint = fallingFastCheckpoint(raw)
            assertEquals(15 * minute, rawCheckpoint)

            for (windowMinutes in listOf(5, 10, 13)) {
                val smoothed = DataSmoothing.smoothNativePoints(
                    points = raw,
                    smoothingMinutes = windowMinutes,
                    collapseChunks = false,
                )
                val context = "cadence=$cadenceMinutes window=$windowMinutes"
                val smoothedTrend = TrendEngine.calculateTrend(smoothed, useRaw = false, isMmol = false)

                assertEquals(context, rawTrend.velocity, smoothedTrend.velocity, 1e-4f)
                assertEquals(context, rawDelta, fiveMinuteDelta(smoothed), 1e-4f)
                assertEquals(context, rawCheckpoint, fallingFastCheckpoint(smoothed))
            }
        }
    }
}
