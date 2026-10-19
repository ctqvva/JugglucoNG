package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQResetCommandTests {
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private val capturedReset = bytes(0x5A, 0xA5, 0x11, 0x01, 0, 0xAA, 7)

    @Test fun sessionMarkerSelectsCapturedResetEvenWhenCloudReportsProtocolOne() {
        val marker = MQParser.parse(bytes(0x5A, 0xA5, 5, 1, 0, 0x51, 0x4F))!!
        assertTrue(MQResetCommand.isProtocol02Marker(marker))
        assertArrayEquals(capturedReset, MQResetCommand.build(1, MQResetCommand.isProtocol02Marker(marker)))
    }

    @Test fun packetDependentCrcXorDoesNotDetermineResetProtocol() {
        // Packet 14 in the 16:02 trace: computed 0x37D5, observed 0x38D5 (XOR 0x0F00).
        val bg = MQParser.parse(bytes(0x5A, 0xA5, 4, 6, 0x40, 14, 0, 172, 0, 50, 0x38, 0xD5))!!
        assertEquals(0x37D5, MQCrc16.compute(bg.raw, 0, bg.raw.size - 2))
        assertFalse(MQResetCommand.isProtocol02Marker(bg))
        assertArrayEquals(capturedReset, MQResetCommand.build(0, observedProtocol02 = true))
    }

    @Test fun beginWorkMarkerAlsoIdentifiesProtocolTwo() {
        val marker = MQParser.parse(bytes(0x5A, 0xA5, 6, 1, 0, 0x91, 0x7E))!!
        assertTrue(MQResetCommand.isProtocol02Marker(marker))
        assertArrayEquals(capturedReset, MQResetCommand.build(0, MQResetCommand.isProtocol02Marker(marker)))
    }

    @Test fun protocolOneAndUnknownFramesAreNotMistakenForCapturedMarkers() {
        val plain = MQCrc16.stamp(bytes(0x5A, 0xA5, 5, 1, 0, 0, 0))
        assertFalse(MQResetCommand.isProtocol02Marker(MQParser.parse(plain)!!))
        val corrupt = bytes(0x5A, 0xA5, 5, 1, 0, 0x52, 0x4F)
        assertFalse(MQResetCommand.isProtocol02Marker(MQParser.parse(corrupt)!!))
        assertArrayEquals(bytes(0x5A, 0xA5, 0x11, 1, 0, 0x54, 0x0F), MQResetCommand.build(1, false))
    }

    @Test fun configuredProtocolTwoDoesNotNeedAnObservedMarker() {
        assertArrayEquals(capturedReset, MQResetCommand.build(2, false))
    }
}
