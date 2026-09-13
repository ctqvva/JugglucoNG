package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.ui.filterRecentSensorTraceLines
import tk.glucodata.ui.isUsefulSensorTraceLine

/**
 * The ring exists so the connection log has a source when trace.log is off, and it is only
 * useful if the one reader can parse both. These pin the line shape against that reader
 * rather than against a literal.
 */
class SensorTraceRingTests {
    @Before
    fun clear() = SensorTraceRing.reset()

    @Test
    fun aCapturedLineParsesTheWayATraceLogLineDoes() {
        SensorTraceRing.add('E', "Anytime", "CT5 end-cycle failed (no response)")
        val line = SensorTraceRing.snapshot().single()

        // <epoch seconds> <pid> <level>/<tag> <message>
        val fields = line.split(" ", limit = 3)
        assertTrue("expected epoch seconds, got ${fields[0]}", fields[0].toLongOrNull() != null)
        assertTrue("expected a pid, got ${fields[1]}", fields[1].toIntOrNull() != null)
        assertTrue(fields[2].startsWith("E/Anytime "))

        assertEquals(
            listOf(line),
            filterRecentSensorTraceLines(lines = listOf(line), identifiers = emptyList(), driverTags = listOf("Anytime")),
        )
    }

    @Test
    fun anUntaggedLineKeepsItsMessageWhole() {
        SensorTraceRing.add(' ', null, "mirror: marking D76C7BB368A3 as Clone")
        val line = SensorTraceRing.snapshot().single()

        assertTrue(line.endsWith(" mirror: marking D76C7BB368A3 as Clone"))
        assertEquals(
            listOf(line),
            filterRecentSensorTraceLines(lines = listOf(line), identifiers = listOf("D76C7BB368A3")),
        )
    }

    @Test
    fun theNoiseFilterStillAppliesToCapturedLines() {
        SensorTraceRing.add('I', "SensorBluetooth", "getdataptr(D76C7BB368A3) Libre2")
        SensorTraceRing.add('W', "Anytime", "No sensor data for 300s - forcing reconnect")
        val lines = SensorTraceRing.snapshot().toList()

        assertEquals(2, lines.size)
        assertEquals(listOf(lines[1]), lines.filter(::isUsefulSensorTraceLine))
    }

    @Test
    fun theRingKeepsTheNewestLinesOldestFirst() {
        repeat(450) { SensorTraceRing.add('I', "Anytime", "reading $it") }
        val lines = SensorTraceRing.snapshot()

        assertEquals(400, lines.size)
        assertTrue(lines.first().endsWith("reading 50"))
        assertTrue(lines.last().endsWith("reading 449"))
    }

    @Test
    fun anEmptyRingReadsAsNothingRatherThanBlankLines() {
        assertEquals(0, SensorTraceRing.snapshot().size)
    }

    @Test
    fun aRunawayLineIsTruncatedRatherThanPinnedWhole() {
        SensorTraceRing.add('D', "Anytime", "payload " + "AB".repeat(4_000))
        val line = SensorTraceRing.snapshot().single()

        assertTrue("expected an ellipsis marking the cut, got ${line.takeLast(8)}", line.endsWith("…"))
        // prefix + the 256 kept characters + the marker, nothing like the 8k that went in.
        assertTrue("line was ${line.length} chars", line.length < 320)
        assertTrue(line.contains("D/Anytime payload AB"))
    }

    @Test
    fun whatTheRingHoldsDoesNotGrowWithUptime() {
        repeat(400) { SensorTraceRing.add('I', "Anytime", "reading $it") }
        val afterOnePass = SensorTraceRing.snapshot().sumOf { it.length }

        repeat(40_000) { SensorTraceRing.add('I', "Anytime", "reading $it") }
        val afterManyMore = SensorTraceRing.snapshot().sumOf { it.length }

        assertEquals(400, SensorTraceRing.snapshot().size)
        // Same shape of line either way, so a hundred times the traffic is the same footprint.
        assertTrue(
            "held $afterOnePass chars then $afterManyMore",
            afterManyMore < afterOnePass * 2,
        )
    }
}
