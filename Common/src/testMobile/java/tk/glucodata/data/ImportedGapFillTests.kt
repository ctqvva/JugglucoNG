package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedGapFillTests {

    private val minute = 60_000L

    private fun minutes(vararg values: Long): List<Long> = values.map { it * minute }

    @Test
    fun inferCadence_takesTheMedianSoAMissedReadingDoesNotMoveIt() {
        // iCan/i6 cadence: 5 min, one missed reading makes a 15-min gap.
        val native = minutes(0, 5, 10, 25, 30, 35)
        assertEquals(5 * minute, ImportedGapFill.inferCadenceMs(native))
    }

    @Test
    fun inferCadence_needsEnoughNativeRows() {
        assertNull(ImportedGapFill.inferCadenceMs(minutes(0, 5)))
        assertNull(ImportedGapFill.inferCadenceMs(emptyList()))
    }

    @Test
    fun normalGapsOnAFiveMinuteSensorGetNoFillers() {
        // Every minute that is not a native minute looks "empty"; four of every five
        // are normal coverage, not holes. None of them may be retagged.
        val native = minutes(0, 5, 10, 15, 20)
        val candidates = minutes(1, 2, 3, 4, 6, 7, 8, 9, 11, 12, 13, 14, 16, 17, 18, 19)
        val kept = ImportedGapFill.selectFillerTimestamps(native, candidates, 5 * minute)
        assertTrue("normal coverage must not be filled, got $kept", kept.isEmpty())
    }

    @Test
    fun oneMissedFiveMinuteReadingGetsExactlyOneFiller() {
        // 5 -> 15 is a genuine missing native reading at 10.
        val native = minutes(0, 5, 15, 20)
        val candidates = minutes(6, 7, 8, 9, 11, 12, 13, 14)
        val kept = ImportedGapFill.selectFillerTimestamps(native, candidates, 5 * minute)
        assertEquals(1, kept.size)
        // The slot is minute 10; the nearest imported rows are 9 and 11, first wins.
        assertEquals(9 * minute, kept.first())
    }

    @Test
    fun aWholeOutageGetsOneFillerPerMissingSlot() {
        // 0 -> 20 with cadence 5 is three missing slots (5, 10, 15).
        val native = minutes(0, 20)
        val candidates = (1L..19L).toList() * minute
        val kept = ImportedGapFill.selectFillerTimestamps(native, candidates, 5 * minute)
        assertEquals(3, kept.size)
        assertEquals(setOf(5 * minute, 10 * minute, 15 * minute), kept)
    }

    @Test
    fun candidatesOutsideAnyNativeGapAreIgnored() {
        val native = minutes(100, 105, 110)
        val candidates = minutes(1, 2, 3)
        assertTrue(ImportedGapFill.selectFillerTimestamps(native, candidates, 5 * minute).isEmpty())
    }

    @Test
    fun noCadenceMeansNoFillers() {
        assertTrue(ImportedGapFill.selectFillerTimestamps(minutes(0, 5), minutes(1, 2), 0L).isEmpty())
    }

    private operator fun List<Long>.times(factor: Long): List<Long> = map { it * factor }
}
