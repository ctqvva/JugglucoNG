package tk.glucodata

import androidx.health.connect.client.records.metadata.Metadata
import org.junit.Assert.*
import org.junit.Test

class HealthConnectGlucoseListTests {
    private fun packed(time: Long, glucose: Int, next: Int) =
        time or (glucose.toLong() shl 32) or (next.toLong() shl 48)

    @Test fun sparseSnapshotHasTheActualSizeAndStableIds() {
        var calls = 0
        val records = GlucoseList(Metadata.unknownRecordingMethod(), 10, 5, "sensor") { pos ->
            calls++
            when (pos) {
                10 -> packed(1_700_000_000, 100, 13)
                13 -> packed(1_700_000_180, 105, 15)
                else -> error("reader escaped its chunk: $pos")
            }
        }
        assertEquals(2, calls)
        assertEquals(2, records.size)
        assertFalse(records.isEmpty())
        assertEquals("juggluco-ng:glucose:sensor:1700000000", records[0].metadata.clientRecordId)
        assertEquals(1_700_000_180L, records[1].time.epochSecond)
        assertEquals(2, records.toList().size)
        val replay = GlucoseList(Metadata.unknownRecordingMethod(), 10, 5, "sensor") { pos ->
            if (pos == 10) packed(1_700_000_000, 100, 13) else packed(1_700_000_180, 105, 15)
        }
        assertEquals(records.map { it.metadata.clientRecordId }, replay.map { it.metadata.clientRecordId })
    }

    @Test fun emptyChunkContainsNoZeroGlucoseRecord() {
        val records = GlucoseList(Metadata.unknownRecordingMethod(), 10, 5, "sensor") {
            packed(0, 0, 15)
        }
        assertTrue(records.isEmpty())
        assertEquals(0, records.size)
    }

    @Test fun malformedReaderCannotLoopForever() {
        var calls = 0
        val records = GlucoseList(Metadata.unknownRecordingMethod(), 10, 5, "sensor") {
            calls++
            packed(0, 0, 10)
        }
        assertEquals(1, calls)
        assertTrue(records.isEmpty())
    }
}
