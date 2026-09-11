package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tk.glucodata.GlucoseReadingSource

/**
 * The guarantee: once a minute has been shown, swapping the main sensor does
 * not change which sensor's reading is the main line for it.
 */
class RecordedOwnerMergeTests {

    private companion object {
        const val MINUTE = ReadingDisplay.MINUTE_MS
        const val NOW = 1_800_000_000_000L
        const val SEALED_AT = NOW - 3L * 60L * MINUTE      // three hours ago
        const val SETTLING_AT = NOW - 10L * MINUTE          // ten minutes ago
    }

    private fun reading(serial: String, timestamp: Long, value: Float) = HistoryReading(
        timestamp = timestamp,
        sensorSerial = serial,
        value = value,
        rawValue = value,
        rate = null,
        source = GlucoseReadingSource.SENSOR,
        firstStoredAt = timestamp,
    )

    private fun record(timestamp: Long, owner: String) = ReadingDisplay(
        timestamp = ReadingDisplay.minuteOf(timestamp),
        sensorSerial = owner,
        displayMgdl = 100f,
        viewMode = 0,
        calibrationFingerprint = 1L,
        recordedAt = NOW,
    )

    @Test
    fun aSealedMinuteIsOwnedByTheRecordedSensorWhateverTheMergeChose() {
        val a = reading("A", SEALED_AT + 5_000L, 90f)
        val b = reading("B", SEALED_AT + 20_000L, 130f)
        // The user swapped main to B, so today's merge picks B for this minute.
        val merged = listOf(b)
        // But A was on screen when the minute was first presented.
        val display = mapOf(ReadingDisplay.minuteOf(SEALED_AT) to record(SEALED_AT, "A"))

        val owned = RecordedOwnerMerge.apply(merged, raw = listOf(a, b), display = display, nowMs = NOW)

        assertEquals(1, owned.size)
        assertSame("A's own reading replaces the merge's choice", a, owned[0])
    }

    @Test
    fun aMinuteStillSettlingFollowsTheMergeNotTheRecord() {
        val a = reading("A", SETTLING_AT + 5_000L, 90f)
        val b = reading("B", SETTLING_AT + 20_000L, 130f)
        val merged = listOf(b)
        val display = mapOf(ReadingDisplay.minuteOf(SETTLING_AT) to record(SETTLING_AT, "A"))

        val owned = RecordedOwnerMerge.apply(merged, raw = listOf(a, b), display = display, nowMs = NOW)

        assertSame("inside the grace window the live decision stands", b, owned[0])
    }

    @Test
    fun theMergesChoiceStandsWhereTheRecordedOwnerHasNoReading() {
        val b = reading("B", SEALED_AT + 20_000L, 130f)
        val display = mapOf(ReadingDisplay.minuteOf(SEALED_AT) to record(SEALED_AT, "A"))

        // A has no reading for this minute at all — better B's line with the
        // recorded value on it than a hole.
        val owned = RecordedOwnerMerge.apply(listOf(b), raw = listOf(b), display = display, nowMs = NOW)

        assertSame(b, owned[0])
    }

    @Test
    fun aMinuteWithNoRecordIsUntouched() {
        val b = reading("B", SEALED_AT + 20_000L, 130f)
        val owned = RecordedOwnerMerge.apply(listOf(b), raw = listOf(b), display = emptyMap(), nowMs = NOW)
        assertSame(b, owned[0])
    }

    @Test
    fun anAlreadyCorrectOwnerIsLeftAlone() {
        val a = reading("A", SEALED_AT + 5_000L, 90f)
        val display = mapOf(ReadingDisplay.minuteOf(SEALED_AT) to record(SEALED_AT, "A"))
        val owned = RecordedOwnerMerge.apply(listOf(a), raw = listOf(a), display = display, nowMs = NOW)
        assertSame(a, owned[0])
    }
}
