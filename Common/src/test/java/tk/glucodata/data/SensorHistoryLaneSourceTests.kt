package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorHistoryLaneSourceTests {

    @Test
    fun parsesPairsIntoMgdlPointsInTimeOrder() {
        val points = SensorHistoryLaneSource.parse(
            longArrayOf(1_800_000_900L, 1234L, 1_800_000_000L, 1080L),
            "3MH0012345",
        )
        assertEquals(listOf(1_800_000_000_000L, 1_800_000_900_000L), points.map { it.timestamp })
        assertEquals(listOf(108.0f, 123.4f), points.map { it.value })
        assertTrue(points.all { it.sensorSerial == "3MH0012345" && it.rawValue == 0f })
    }

    @Test
    fun dropsEmptyValuesAndATrailingHalfPair() {
        val points = SensorHistoryLaneSource.parse(
            longArrayOf(1_800_000_000L, 0L, 1_800_000_900L, 1000L, 1_800_001_800L),
            "3MH0012345",
        )
        assertEquals(listOf(100.0f), points.map { it.value })
    }
}
