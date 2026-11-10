package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** When an AAPS temp basal stopped running, for the chart's icicles. */
class TempBasalSegmentsTests {

    private val t0 = 1_700_000_000_000L
    private fun minutes(m: Int) = t0 + m * 60_000L

    private fun entry(
        id: Long,
        atMinute: Int,
        durationMinutes: Int?,
        type: JournalEntryType = JournalEntryType.TEMP_BASAL,
        sourceRecordId: String? = null,
    ) = JournalEntry(
        id = id,
        timestamp = minutes(atMinute),
        sensorSerial = null,
        type = type,
        title = type.name,
        note = null,
        amount = 0.8f,
        glucoseValueMgDl = null,
        durationMinutes = durationMinutes,
        intensity = null,
        insulinPresetId = null,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = JournalEntrySource.AAPS,
        sourceRecordId = sourceRecordId,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun aSegmentRunsItsOwnDurationWhenNothingReplacesIt() {
        val ends = tempBasalEndsMillis(listOf(entry(1, atMinute = 0, durationMinutes = 30)))
        assertEquals(minutes(30), ends[1])
    }

    /**
     * 10:00 for 30 min, replaced at 10:05 by a 5 min one. The first ends at 10:05, so a
     * 10:15–10:25 chart window, which shows neither start, draws nothing for it.
     */
    @Test
    fun aReplacedSegmentEndsWhereItsReplacementStarts() {
        val ends = tempBasalEndsMillis(
            listOf(
                entry(2, atMinute = 5, durationMinutes = 5),
                entry(1, atMinute = 0, durationMinutes = 30),
            )
        )
        assertEquals(minutes(5), ends[1])
        assertEquals(minutes(10), ends[2])
    }

    @Test
    fun otherTypesAndSegmentsWithoutADurationAreLeftOut() {
        val ends = tempBasalEndsMillis(
            listOf(
                entry(1, atMinute = 0, durationMinutes = null),
                entry(2, atMinute = 1, durationMinutes = 30, type = JournalEntryType.CARBS),
                entry(3, atMinute = 2, durationMinutes = 30),
            )
        )
        assertFalse(1L in ends)
        assertFalse(2L in ends)
        assertEquals(minutes(32), ends[3])
    }

    private fun statusLine(id: Long, atMinute: Int, durationMinutes: Int) =
        entry(id, atMinute, durationMinutes, sourceRecordId = AapsStatusLine.SOURCE_RECORD_PREFIX + minutes(atMinute))

    /**
     * The status line runs 10:00–11:00 as one row; Nightscout has the same basal as a
     * 10:05–10:35 treatment. The copy must not cut the status line row at 10:05.
     */
    @Test
    fun aNightscoutCopyOverlappingAStatusLineSegmentIsLeftOut() {
        val ends = tempBasalEndsMillis(
            listOf(
                statusLine(1, atMinute = 0, durationMinutes = 60),
                entry(2, atMinute = 5, durationMinutes = 30),
            )
        )
        assertEquals(minutes(60), ends[1])
        assertFalse(2L in ends)
    }

    /**
     * AAPS import switched on during a basal Nightscout already has: 10:00–10:30 from
     * Nightscout, the first status line segment at 10:10. The 10:00–10:10 history stays.
     */
    @Test
    fun aNightscoutBasalStartedEarlierKeepsItsHistory() {
        val ends = tempBasalEndsMillis(
            listOf(
                entry(1, atMinute = 0, durationMinutes = 30),
                statusLine(2, atMinute = 10, durationMinutes = 1),
            )
        )
        assertEquals(minutes(10), ends[1])
        assertEquals(minutes(11), ends[2])
    }

    /**
     * A status line row 10:00–11:00 and a stray one 10:10–10:15 inside it. A Nightscout copy
     * at 10:20 is past the later row but still inside the earlier, longer one, so it is out.
     */
    @Test
    fun aNightscoutCopyInsideAnEarlierLongerStatusLineSegmentIsLeftOut() {
        val ends = tempBasalEndsMillis(
            listOf(
                statusLine(1, atMinute = 0, durationMinutes = 60),
                statusLine(2, atMinute = 10, durationMinutes = 5),
                entry(3, atMinute = 20, durationMinutes = 30),
            )
        )
        assertFalse(3L in ends)
    }

    /** Before the status line started, Nightscout is all there is; it ends where that starts. */
    @Test
    fun nightscoutStillFillsTimeTheStatusLineDoesNotCover() {
        val ends = tempBasalEndsMillis(
            listOf(
                entry(1, atMinute = 0, durationMinutes = 30),
                statusLine(2, atMinute = 30, durationMinutes = 20),
                entry(3, atMinute = 60, durationMinutes = 30),
            )
        )
        assertEquals(minutes(30), ends[1])
        assertEquals(minutes(50), ends[2])
        assertEquals(minutes(90), ends[3])
    }
}
