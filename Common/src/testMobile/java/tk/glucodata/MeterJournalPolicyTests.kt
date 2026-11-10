package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class MeterJournalPolicyTests {
    @Test
    fun decodedReadingKeepsNativeTimestampAndMgdlTenths() {
        val decoded = MeterJournalPolicy.decode(
            meterIndex = 3,
            timestampMillis = 1_700_000_000_000L,
            mgdlTenths = 1_237L,
            nowMillis = 1_700_000_000_000L,
        )

        assertNotNull(decoded)
        assertEquals(1_700_000_000_000L, decoded!!.timestampMillis)
        assertEquals(123.7f, decoded.glucoseMgDl, 0.001f)
        assertEquals("meter:3:1700000000000", decoded.sourceRecordId)
    }

    @Test
    fun invalidNativeValuesAreNotJournaled() {
        val now = 1_700_000_000_000L

        assertNull(MeterJournalPolicy.decode(0, 0L, 1_000L, now))
        assertNull(MeterJournalPolicy.decode(0, now, 0L, now))
        assertNull(MeterJournalPolicy.decode(0, now, 20_001L, now))
        assertNull(MeterJournalPolicy.decode(-1, now, 1_000L, now))
        assertNull(MeterJournalPolicy.decode(0, now + 24 * 60 * 60 * 1000L + 1L, 1_000L, now))
    }
}
