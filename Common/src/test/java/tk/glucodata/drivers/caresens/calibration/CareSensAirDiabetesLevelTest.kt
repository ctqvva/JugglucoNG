package tk.glucodata.drivers.caresens.calibration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Level boundaries measured on the reference library by setting these
 * statistics in its state block just before sequence 2017 (1000 samples,
 * mean 125 mg/dL).
 */
class CareSensAirDiabetesLevelTest {
    private fun level(high: Int, low: Int, cv: Double, count: Int = 1000, mean: Double = 125.0): Int {
        val s = CareSensAirCalibrationState()
        s.diabetesCount = count; s.diabetesHigh = high; s.diabetesLow = low; s.diabetesMean = mean
        s.diabetesM2 = Math.pow(cv / 100 * mean, 2.0) * (count - 1)
        return s.diabetesLevel()
    }

    @Test
    fun matchesTheReferenceOnItsBoundaries() {
        val cases = listOf(
            // high, low, CV %, reference level
            Triple(Pair(49, 0), 19.99, 1), Triple(Pair(50, 0), 19.99, 2), Triple(Pair(0, 0), 20.01, 2),
            Triple(Pair(149, 0), 25.0, 2), Triple(Pair(150, 0), 25.0, 3), Triple(Pair(300, 0), 25.0, 3),
            Triple(Pair(301, 0), 25.0, 4), Triple(Pair(600, 0), 25.0, 4), Triple(Pair(601, 0), 25.0, 5),
            Triple(Pair(0, 0), 29.99, 2), Triple(Pair(0, 0), 30.01, 3), Triple(Pair(599, 0), 30.01, 4),
            Triple(Pair(0, 0), 35.99, 3), Triple(Pair(0, 0), 36.01, 6), Triple(Pair(600, 0), 36.01, 6),
            Triple(Pair(0, 50), 25.0, 2), Triple(Pair(700, 50), 25.0, 5), Triple(Pair(0, 51), 25.0, 6),
        )
        for ((counts, cv, want) in cases) {
            assertEquals("high ${counts.first}, low ${counts.second}, CV $cv", want, level(counts.first, counts.second, cv))
        }
        // Found by the randomised sweep: 9 % high, 3.8 % low, CV 30.25 %.
        assertEquals(3, level(97, 41, 30.25, count = 1078, mean = 121.8))
        assertEquals(6, level(0, 0, 15.0, count = 288))
    }
}
