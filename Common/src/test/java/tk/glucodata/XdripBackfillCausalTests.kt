package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reading backfilled to AAPS after a disconnect must carry the number the live path would have
 * sent for that minute — not the raw sample next to live values that were filtered.
 */
class XdripBackfillCausalTests {

    private val minute = 60_000L

    @Test
    fun backfilledValueEqualsTheLiveEstimateForTheSameMinute() {
        val history = (0 until 40).map { i -> GlucosePoint(i * minute, 100f + (i % 4) * 5f, 90f) }
        val rowTimes = listOf(35L * minute, 36L * minute, 37L * minute)
        val backfilled = XdripBackfill.causalLaneValues(history, rowTimes, rawLane = false)
        val live = DataSmoothing.smoothNativePointsCausal(history.take(37), 5, false, 1f, Long.MAX_VALUE / 2)
        assertEquals(live.last().value, backfilled.getValue(36L * minute), 0f)
    }

    @Test
    fun rowsAreMatchedWithinHalfAMinute() {
        val history = (0 until 10).map { i -> GlucosePoint(i * minute + 12_000L, 100f, 100f) }
        val values = XdripBackfill.causalLaneValues(history, listOf(5L * minute), rawLane = true)
        assertEquals(100f, values.getValue(5L * minute), 1e-3f)
    }

    @Test
    fun aRowWithNoHistoryNearItIsLeftOut() {
        val history = (0 until 10).map { i -> GlucosePoint(i * minute, 100f, 100f) }
        val values = XdripBackfill.causalLaneValues(history, listOf(60L * minute), rawLane = false)
        assertTrue(values.isEmpty())
    }

    @Test
    fun candidatesCarryTheCausalValueWithoutSmoothingItAgain() {
        val history = (0 until 40).map { i -> GlucosePoint(i * minute, 100f + (i % 4) * 5f, 90f) }
        val rows = listOf(35L, 36L, 37L).map { minuteIndex ->
            val point = history[minuteIndex.toInt()]
            XdripBackfill.Row(minuteIndex * minute, point.value, point.rawValue, null)
        }
        val causal = XdripBackfill.causalLaneValues(history, rows.map { it.timeMs }, rawLane = false)
        val candidates = XdripBackfill.candidates(
            sensorSerial = "test", rows = rows, viewMode = 0, smoothingMinutes = 5, causalHistory = history,
        )
        assertEquals(rows.size, candidates.size)
        candidates.forEach { candidate ->
            assertEquals(causal.getValue(candidate.timeMs), candidate.mgdl.toFloat(), 1e-3f)
        }
    }

    @Test
    fun rawPrimaryRowsWithoutRawUseTheFilteredAutoLane() {
        val history = (0 until 40).map { i ->
            GlucosePoint(i * minute, 100f + (i % 4) * 5f, if (i == 36) 0f else 90f)
        }
        val row = XdripBackfill.Row(36L * minute, history[36].value, 0f, null)
        val displayed = XdripBackfill.displayedRows(
            sensorSerial = "test", rows = listOf(row), viewMode = 3, smoothingMinutes = 5, causalHistory = history,
        )
        val auto = XdripBackfill.causalLaneValues(history, listOf(36L * minute), rawLane = false)

        assertEquals(1, displayed.size)
        assertEquals(auto.getValue(36L * minute), displayed.single().mgdl.toFloat(), 0.51f)
    }

    @Test
    fun displayedRowsCarryTheFilterValueEvenWhenTheTrackerNeverSawTheSensor() {
        val history = (0 until 40).map { i -> GlucosePoint(i * minute, 100f + (i % 4) * 5f, 90f) }
        val rows = listOf(35L, 36L, 37L).map { minuteIndex ->
            val point = history[minuteIndex.toInt()]
            XdripBackfill.Row(minuteIndex * minute, point.value, point.rawValue, null)
        }
        val causal = XdripBackfill.causalLaneValues(history, rows.map { it.timeMs }, rawLane = false)

        // No XdripBackfillTracker is involved: Dexcom Share must not depend on the xDrip broadcast.
        // This drives the already-resolved overload, so it cannot catch someone re-adding the
        // needsBackfill gate inside causalHistoryFor (which reads Room and has no unit seam).
        val displayed = XdripBackfill.displayedRows("test", rows, viewMode = 0, smoothingMinutes = 5, causalHistory = history)

        assertEquals(rows.size, displayed.size)
        displayed.forEach { row ->
            assertEquals(causal.getValue(row.timeMs), row.mgdl.toFloat(), 0.5f)
        }
    }
}
