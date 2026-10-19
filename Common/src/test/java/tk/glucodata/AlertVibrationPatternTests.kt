package tk.glucodata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertVibrationPatternTests {

    @Test
    fun finitePatternStopsAtConfiguredDuration() {
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 200, 100),
            intArrayOf(0, 255, 0),
            1
        )

        assertEquals(1_000L, pattern.totalDurationMs())
        assertTrue(pattern.timings.size <= 512)
        assertEquals(pattern.timings.size, pattern.amplitudes.size)
    }

    @Test
    fun invalidDurationFallsBackToDefaultDuration() {
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 200, 100),
            intArrayOf(0, 255, 0),
            65_535
        )

        assertEquals(5_000L, pattern.totalDurationMs())
    }

    @Test
    fun zeroDurationBasePatternBecomesSilentFinitePattern() {
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 0, 0),
            intArrayOf(0, 255, 0),
            4
        )

        assertEquals(4_000L, pattern.totalDurationMs())
        assertEquals(1, pattern.timings.size)
        assertEquals(0, pattern.amplitudes[0])
    }

    @Test
    fun leadInExtendsPatternAcrossSoundDelay() {
        // Sound delay 45s + duration 25s: the pattern must span all 70s so the
        // vibration does not run out before the delayed sound starts.
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 200, 100),
            intArrayOf(0, 255, 0),
            25,
            45
        )

        assertEquals(70_000L, pattern.totalDurationMs())
    }

    @Test
    fun zeroLeadInMatchesPlainBuild() {
        val plain = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 200, 100),
            intArrayOf(0, 255, 0),
            25
        )
        val withLeadIn = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 200, 100),
            intArrayOf(0, 255, 0),
            25,
            0
        )

        assertArrayEquals(plain.timings, withLeadIn.timings)
        assertArrayEquals(plain.amplitudes, withLeadIn.amplitudes)
    }

    @Test
    fun maxDelayPlusMaxDurationIsCoveredByDensestPattern() {
        // HIGH base pattern, the densest looped one; 300s delay + 60s duration
        // must fit within the segment budget without truncation.
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 150, 100, 150, 100, 150, 100, 150, 300),
            intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0),
            60,
            300
        )

        assertEquals(360_000L, pattern.totalDurationMs())
    }

    @Test
    fun leadInIsBoundedBySoundDelayCap() {
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 200, 100),
            intArrayOf(0, 255, 0),
            5,
            999
        )

        assertEquals(305_000L, pattern.totalDurationMs())
    }

    @Test
    fun negativeLeadInIsIgnored() {
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 200, 100),
            intArrayOf(0, 255, 0),
            25,
            -10
        )

        assertEquals(25_000L, pattern.totalDurationMs())
    }

    @Test
    fun invalidDurationWithLeadInFallsBackToDefaultPlusLeadIn() {
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 200, 100),
            intArrayOf(0, 255, 0),
            65_535,
            45
        )

        assertEquals(50_000L, pattern.totalDurationMs())
    }

    @Test
    fun negativeBaseTimingsNeverProduceNegativeSegments() {
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, -100, 200),
            intArrayOf(0, 255, 0),
            1
        )

        assertEquals(1_000L, pattern.totalDurationMs())
        assertTrue(pattern.timings.all { it >= 0L })
    }
}
