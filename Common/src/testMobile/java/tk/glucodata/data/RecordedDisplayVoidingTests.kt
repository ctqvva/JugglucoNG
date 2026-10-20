package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invalidation requires a change of source, not a difference from the displayed value. */
class RecordedDisplayVoidingTests {
    private companion object {
        const val MINUTE = ReadingDisplay.MINUTE_MS
        const val T0 = 1_800_000_000_000L
        const val SERIAL = "SIBI:0683013AQT9"
    }

    private fun record(minute: Int, shown: Float, mode: Int = 0, serial: String = SERIAL) = ReadingDisplay(
        ReadingDisplay.minuteOf(T0 + minute * MINUTE), serial, shown, mode, 1L, T0,
    )

    private fun reading(minute: Int, value: Float, raw: Float = value, serial: String = SERIAL) = HistoryReading(
        timestamp = T0 + minute * MINUTE + 17_000L, sensorSerial = serial,
        value = value, rawValue = raw, rate = null,
    )

    @Test fun changedSourcesInvalidateTheirOwnRecords() {
        val records = listOf(record(0, 99f), record(1, 106f), record(2, 119f))
        val before = listOf(reading(0, 100f), reading(1, 104f), reading(2, 108f))
        val after = listOf(reading(0, 112f), reading(1, 116f), reading(2, 120f))
        assertEquals(records.map { it.timestamp },
            RecordedDisplayVoiding.changedMinutes(records, SERIAL, before, after, true, false))
    }

    @Test fun unchangedReplayKeepsSmoothedAndCalibratedRecordsAndOwnership() {
        val records = listOf(record(0, 99f), record(1, 106f), record(2, 119f))
        val before = listOf(reading(0, 100f), reading(1, 104f), reading(2, 108f))
        val after = listOf(reading(0, 100f), reading(1, 104f), reading(2, 108.004f))
        assertTrue(RecordedDisplayVoiding.changedMinutes(records, SERIAL, before, after, true, false).isEmpty())
    }

    @Test fun onlyTheReplacedMinutesAreConsidered() {
        val records = listOf(record(0, 100f), record(5, 120f), record(9, 140f))
        val before = listOf(reading(0, 100f), reading(5, 120f), reading(9, 140f))
        val after = listOf(reading(4, 200f), reading(5, 200f), reading(6, 200f))
        assertEquals(listOf(records[1].timestamp),
            RecordedDisplayVoiding.changedMinutes(records, SERIAL, before, after, true, false))
    }

    @Test fun aGapBetweenReplacementBucketsDoesNotDeleteItsRecords() {
        val records = listOf(record(0, 100f), record(1, 104f), record(2, 108f))
        val before = listOf(reading(0, 100f), reading(1, 104f), reading(2, 108f))
        val after = listOf(reading(0, 100f), reading(2, 108f))
        assertTrue(RecordedDisplayVoiding.changedMinutes(records, SERIAL, before, after, true, false).isEmpty())
    }

    @Test fun anotherSensorsRecordsAreNotTouched() {
        val records = listOf(record(0, 100f, serial = "other"), record(1, 104f))
        val before = listOf(reading(0, 100f), reading(1, 104f), reading(0, 300f, serial = "other"))
        val after = listOf(reading(0, 150f), reading(1, 150f), reading(0, 400f, serial = "other"))
        assertEquals(listOf(records[1].timestamp),
            RecordedDisplayVoiding.changedMinutes(records, SERIAL, before, after, true, false))
    }

    @Test fun eachLaneIsComparedWithItsOwnPreviousSource() {
        val records = listOf(record(0, 110f, mode = 0), record(1, 99f, mode = 1))
        val before = listOf(reading(0, 100f, raw = 90f), reading(1, 104f, raw = 94f))
        val after = listOf(reading(0, 112f, raw = 95f), reading(1, 116f, raw = 99f))
        assertEquals(listOf(records[0].timestamp),
            RecordedDisplayVoiding.changedMinutes(records, SERIAL, before, after, true, false))
        assertEquals(listOf(records[1].timestamp),
            RecordedDisplayVoiding.changedMinutes(records, SERIAL, before, after, false, true))
        assertEquals(records.map { it.timestamp },
            RecordedDisplayVoiding.changedMinutes(records, SERIAL, before, after, true, true))
    }

    @Test fun aDriverThatRewritesNeitherLaneVoidsNothing() {
        val records = listOf(record(0, 100f))
        assertTrue(RecordedDisplayVoiding.changedMinutes(records, SERIAL,
            listOf(reading(0, 100f)), listOf(reading(0, 80f)), false, false).isEmpty())
    }

    @Test fun noPreviousSourceIsNotEvidenceForDeletingASavedValue() {
        assertTrue(RecordedDisplayVoiding.changedMinutes(listOf(record(0, 112f)), SERIAL,
            emptyList(), listOf(reading(0, 100f)), true, false).isEmpty())
    }

    @Test fun restoringStockVoidsOnlyAutoSourcesThatActuallyChanged() {
        val records = listOf(record(0, 112f), record(1, 90f), record(2, 95f), record(3, 50f, mode = 1))
        val before = listOf(reading(0, 100f), reading(1, 90f), reading(2, 95f), reading(3, 99f))
        val after = listOf(reading(0, 100f), reading(1, 104f), reading(2, 108f), reading(3, 109f))
        assertEquals(listOf(records[1].timestamp, records[2].timestamp),
            RecordedDisplayVoiding.changedMinutes(records, SERIAL, before, after, true, false))
    }

    @Test fun aTimestampCorrectionWithinTheSameMinuteKeepsTheRecordWhenItsSourceIsUnchanged() {
        val before = reading(0, 100f).copy(timestamp = T0 + 1_000L)
        val after = reading(0, 100f)
        assertTrue(RecordedDisplayVoiding.changedMinutes(listOf(record(0, 105f)), SERIAL,
            listOf(before), listOf(after), true, false).isEmpty())
    }

    // Libre 1/2: its 15-minute history used to share the Auto lane with the
    // stream. Records drawn from a history row must not keep drawing it there.

    private fun row(minute: Int, second: Int, value: Float, raw: Float = 0f) = HistoryReading(
        timestamp = T0 + minute * MINUTE + second * 1_000L,
        sensorSerial = SERIAL,
        value = value,
        rawValue = raw,
        rate = null,
    )

    @Test
    fun aRecordFromAHistoryRowTheStreamNowHoldsIsVoid() {
        val history = row(0, 3, 108f)
        val stream = row(0, 41, 115f, raw = 108f)
        val voided = RecordedDisplayVoiding.displacedHistoryMinutes(
            records = listOf(record(0, 108f)),
            sensorSerial = SERIAL,
            rewritten = listOf(stream),
            previousByMinute = mapOf(record(0, 0f).timestamp to history),
            historyTimestamps = setOf(history.timestamp),
        )
        assertEquals(listOf(record(0, 0f).timestamp), voided)
    }

    @Test
    fun aRecordFromAHistoryRowThatLostItsAutoValueIsVoid() {
        val history = row(0, 3, 108f)
        val voided = RecordedDisplayVoiding.displacedHistoryMinutes(
            records = listOf(record(0, 108f)),
            sensorSerial = SERIAL,
            rewritten = listOf(history.copy(value = 0f, rawValue = 108f)),
            previousByMinute = mapOf(record(0, 0f).timestamp to history),
            historyTimestamps = setOf(history.timestamp),
        )
        assertEquals(listOf(record(0, 0f).timestamp), voided)
    }

    @Test
    fun streamRecordsAndHistoryStillOnTheAutoLineKeepTheirRecords() {
        val stream = row(0, 41, 115f)
        val gapFill = row(1, 3, 108f)
        val records = listOf(record(0, 115f), record(1, 108f), record(2, 99f, mode = 1))
        val voided = RecordedDisplayVoiding.displacedHistoryMinutes(
            records = records,
            sensorSerial = SERIAL,
            // The stream row gains its History; the gap fill keeps its Auto value.
            rewritten = listOf(stream.copy(rawValue = 108f), gapFill.copy(rawValue = 108f), row(2, 3, 0f, 99f)),
            previousByMinute = mapOf(
                records[0].timestamp to stream,
                records[1].timestamp to gapFill,
                records[2].timestamp to row(2, 3, 99f),
            ),
            historyTimestamps = setOf(gapFill.timestamp, row(2, 3, 0f).timestamp),
        )
        assertTrue(voided.isEmpty())
    }
}
