package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleErrorHistoryTests {

    @Test
    fun `serialized history reloads without losing sensor status or timestamp`() {
        val expected = listOf(
            BleErrorEvent("TEST-SENSOR-001", "Status=147", 12_345L),
            BleErrorEvent("sensor with spaces", "Loss of signal", 12_000L),
        )

        assertEquals(expected, decodeBleErrorEvents(encodeBleErrorEvents(expected)))
    }

    @Test
    fun `malformed persisted rows do not hide valid events`() {
        val valid = encodeBleErrorEvents(listOf(BleErrorEvent("sensor", "Status=133", 9_000L)))

        assertEquals(
            listOf(BleErrorEvent("sensor", "Status=133", 9_000L)),
            decodeBleErrorEvents("broken\n$valid\n9001\t%%%\t%%%"),
        )
    }

    @Test
    fun `retention is bounded per sensor and globally`() {
        val now = 1_000_000L
        val events = buildList {
            repeat(30) { index -> add(BleErrorEvent("sensor-a", "Status=$index", now - index)) }
            repeat(30) { index -> add(BleErrorEvent("sensor-b", "Status=$index", now - 100 - index)) }
        }

        val retained = retainBleErrorEvents(
            events = events,
            nowMs = now,
            maxPerSensor = 20,
            maxTotal = 25,
            retentionMs = 10_000L,
        )

        assertEquals(25, retained.size)
        assertEquals(20, retained.count { it.sensorId == "sensor-a" })
        assertEquals(5, retained.count { it.sensorId == "sensor-b" })
        assertEquals(retained.sortedByDescending { it.atMs }, retained)
    }

    @Test
    fun `events beyond retention are removed`() {
        val now = 100_000L
        val retained = retainBleErrorEvents(
            events = listOf(
                BleErrorEvent("sensor", "old", 89_999L),
                BleErrorEvent("sensor", "boundary", 90_000L),
                BleErrorEvent("sensor", "recent", 99_000L),
            ),
            nowMs = now,
            retentionMs = 10_000L,
        )

        assertEquals(listOf("recent", "boundary"), retained.map { it.status })
        assertTrue(retained.none { it.status == "old" })
    }
}
