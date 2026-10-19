package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stock value behind a reading is only knowable to the device that computed
 * it. A calibration taken while the watch held the sensor could be rebased by
 * neither device, and pinned the fit at an x the stock series never visits.
 */
class IntegratedStockBaselineTests {
    private val sensor = "SIBI:0683013AQT9"
    private val base = 1_786_748_400_000L

    @Test
    fun answersForTheReadingItRecorded() {
        IntegratedStockBaseline.record(sensor, base, 6.4f)
        assertEquals(6.4f, IntegratedStockBaseline.stockAt(sensor, base), 0.0001f)
    }

    @Test
    fun answersForANearbyReadingButNotADistantOne() {
        IntegratedStockBaseline.record(sensor, base, 6.4f)
        // A calibration is entered against a reading a moment old.
        assertEquals(6.4f, IntegratedStockBaseline.stockAt(sensor, base + 60_000L), 0.0001f)
        // Half an hour away is a different reading; unknown beats a wrong number,
        // because the phone can still fall back to its own history match.
        assertTrue(IntegratedStockBaseline.stockAt(sensor, base + 30 * 60_000L).isNaN())
    }

    @Test
    fun picksTheNearerOfTwoRecordedReadings() {
        IntegratedStockBaseline.record(sensor, base, 6.4f)
        IntegratedStockBaseline.record(sensor, base + 120_000L, 6.9f)
        assertEquals(6.4f, IntegratedStockBaseline.stockAt(sensor, base + 30_000L), 0.0001f)
        assertEquals(6.9f, IntegratedStockBaseline.stockAt(sensor, base + 90_000L), 0.0001f)
    }

    @Test
    fun aSensorItNeverSawIsUnknownRatherThanZero() {
        IntegratedStockBaseline.record(sensor, base, 6.4f)
        assertTrue(IntegratedStockBaseline.stockAt("SIBI:SOMETHINGELSE", base).isNaN())
        assertTrue(IntegratedStockBaseline.stockAt(null, base).isNaN())
    }

    @Test
    fun refusesValuesThatAreNotReadings() {
        val quiet = "SIBI:QUIETONE"
        IntegratedStockBaseline.record(quiet, base, 0f)
        IntegratedStockBaseline.record(quiet, base, Float.NaN)
        IntegratedStockBaseline.record(quiet, 0L, 6.4f)
        assertTrue(IntegratedStockBaseline.stockAt(quiet, base).isNaN())
    }
}
