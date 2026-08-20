package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorTraceLogTests {
    @Test
    fun filterRecentSensorTraceLines_matchesIdentifiersAndKeepsNewestLines() {
        val lines = listOf(
            "1 10 unrelated",
            "2 10 sensor ABC123 connecting",
            "3 10 address aa:bb:cc:dd:ee:ff",
            "4 10 ABC123 connected",
        )

        assertEquals(
            listOf(lines[2], lines[3]),
            filterRecentSensorTraceLines(
                lines = lines,
                identifiers = listOf("ABC123", "AA:BB:CC:DD:EE:FF"),
                limit = 2,
            ),
        )
    }
}
