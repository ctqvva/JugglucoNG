package tk.glucodata.drivers.libre3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Libre3LingoTest {
    private fun hex(value: String) = Libre3Bytes.parseHex(value)!!

    // Decrypted Lingo v3 capture, Juggluco lingo_realtime_test.c, GPL-3.0-or-later.
    private val live = hex(
        "3f002d00008000800000ffff00800800800080c94000800000ffff008008d0000080100000b90c70000109e14ced0e00000000"
    )

    @Test
    fun capturedFrameAuthenticatesAndDecodesThroughLingoFraming() {
        val session = Libre3DataPlaneSession(ByteArray(16), ByteArray(8))
        val frame = session.encryptInbound(Libre3Channel.GLUCOSE_DATA, live, 8)
        assertEquals(Libre3Lingo.LIVE_WIRE_SIZE, frame.size)
        val assembler = Libre3Framing.GlucoseAssembler(Libre3Lingo.LIVE_WIRE_SIZE)
        assertNull(assembler.feed(frame.copyOfRange(0, 20)))
        assertNull(assembler.feed(frame.copyOfRange(20, 40)))
        val assembled = assembler.feed(frame.copyOfRange(40, frame.size))!!
        val plain = session.decrypt(Libre3Channel.GLUCOSE_DATA, assembled)!!.plaintext
        val reading = Libre3Lingo.parseRealtime(plain)!!
        assertEquals(63, reading.lifeCount)
        // Lingo shows 201 mg/dL for this capture; upstream Juggluco uses its uncapped 208 mg/dL.
        assertEquals(208, reading.currentMgdl)
        val cappedOnly = plain.copyOf().apply { Libre3Bytes.putU16le(this, 30, 0) }
        assertEquals(201, Libre3Lingo.parseRealtime(cappedOnly)!!.currentMgdl)
        assertEquals(45, reading.historicalLifeCount)
        assertNull(reading.historicalMgdl)
        assertTrue(reading.rateMgdlPerMinute.isNaN())
        assertNull(Libre3RealtimeReading.parse(plain))
    }

    @Test
    fun invalidGlucoseAndOtherAnalyteNeverBecomeReadings() {
        val invalid = live.copyOf().apply {
            Libre3Bytes.putU16le(this, 19, 0x80c9)
        }
        val rejected = Libre3Lingo.parseRealtime(invalid)!!
        assertTrue(rejected.dataQualityError)
        assertNull(rejected.currentMgdl)
        val otherAnalyte = live.copyOf().apply { this[34] = 0x20 }
        assertNull(Libre3Lingo.parseRealtime(otherAnalyte)!!.currentMgdl)
        assertNull(Libre3Lingo.parseRealtime(live.copyOf(live.size - 1)))
    }

    @Test
    fun backfillStripsGlucoseChannelBitAndKeepsQualityFlags() {
        val clinical = hex("3f800000000000000000c940d080")
        val reading = Libre3Lingo.parseClinical(clinical)!!
        assertEquals(63, reading.lifeCount)
        assertEquals(201, reading.currentMgdl)
        assertNull(reading.historicalMgdl)
        assertNull(Libre3Lingo.parseClinical(clinical.copyOf().apply { this[1] = 0 }))

        val page = Libre3Lingo.parseHistory(hex("2d80c940d080c8000000c700"))!!
        assertEquals(listOf(45 to 201, 55 to 200), page.samples())
        assertEquals(55, page.lastLifeCount)
        assertNull(Libre3Lingo.parseHistory(hex("2d00c940")))
        assertNull(Libre3Lingo.parseHistory(hex("2d800000")))
        assertArrayEquals(hex("0102012d000000"), Libre3ControlCommand.historicalFrom(45, isLingo = true))
        assertArrayEquals(hex("0103013f000000"), Libre3ControlCommand.clinicalFrom(63, isLingo = true))
        assertArrayEquals(hex("0101013f000000"), Libre3ControlCommand.clinicalFrom(63))
    }

    @Test
    fun extendedPatchStatusRequiresTheLingoProduct() {
        val status = hex("3f000000000000043f0000001000")
        assertNull(Libre3PatchStatus.parse(status))
        assertEquals(63, Libre3PatchStatus.parse(status, isLingo = true)!!.currentLifeCount)
    }
}
