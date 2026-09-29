package tk.glucodata.drivers.aidex.native.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AiDexParser.parseBroadcastSample]. Payload = [off0 off1 off2 off3 trend lo carry].
 * No captured advert exists, so every payload here is synthetic.
 *
 * Rows named `_failsOnHead` would fail against the manager's HEAD `parseBroadcastSamplePayload`,
 * which fell back to byte 5 alone when the 10-bit value was out of range.
 */
class AiDexBroadcastParseTests {

    private fun payload(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    private fun glucosePayload(lo: Int, carry: Int) = payload(0x2B, 0x00, 0x00, 0x00, 0x00, lo, carry)

    private fun accepted(p: ByteArray): AiDexParser.BroadcastSample {
        val parsed = AiDexParser.parseBroadcastSample(p)
        assertNull(parsed.rejectionReason)
        return parsed.sample!!
    }

    private fun rejected(p: ByteArray): String {
        val parsed = AiDexParser.parseBroadcastSample(p)
        assertNull(parsed.sample)
        return parsed.rejectionReason!!
    }

    @Test
    fun decodesOffsetTrendAndGlucose() {
        val sample = accepted(payload(0x2B, 0x00, 0x00, 0x00, 0x02, 0x78, 0x00))
        assertEquals(43, sample.offsetMinutes)
        assertEquals(2, sample.trend)
        assertEquals(120, sample.glucoseMgDl)
    }

    @Test
    fun trendIsSignedAndCarryAddsBitsEightAndNine() {
        // 0xFE as i8 = -2; 0x2C | (0x01 << 8) = 300
        val sample = accepted(payload(0x2B, 0x00, 0x00, 0x00, 0xFE, 0x2C, 0x01))
        assertEquals(-2, sample.trend)
        assertEquals(300, sample.glucoseMgDl)
    }

    @Test
    fun carryBitsTwoToSevenAreIgnored() {
        // 0xFC & 0x03 = 0, so 0x78 alone = 120
        assertEquals(120, accepted(glucosePayload(0x78, 0xFC)).glucoseMgDl)
    }

    @Test
    fun glucoseRangeIsInclusive() {
        assertEquals(20, accepted(glucosePayload(0x14, 0x00)).glucoseMgDl)
        assertEquals(500, accepted(glucosePayload(0xF4, 0x01)).glucoseMgDl) // 0xF4 | 1 << 8 = 500
        assertEquals("invalid-glucose(packed=19)", rejected(glucosePayload(0x13, 0x00)))
    }

    @Test
    fun packed1023IsRejected_failsOnHead() {
        // HEAD: 1023 out of range -> byte 5 = 0xFF = 255, accepted.
        assertEquals("invalid-glucose(packed=1023)", rejected(glucosePayload(0xFF, 0x03)))
    }

    @Test
    fun packed501IsRejected_failsOnHead() {
        // HEAD: 0xF5 | 1 << 8 = 501 out of range -> byte 5 = 245, accepted.
        assertEquals("invalid-glucose(packed=501)", rejected(glucosePayload(0xF5, 0x01)))
    }

    @Test
    fun packed540IsRejectedNotAFalseLow_failsOnHead() {
        // HEAD: 0x1C | 2 << 8 = 540 out of range -> byte 5 = 28, accepted as a false low.
        assertEquals("invalid-glucose(packed=540)", rejected(glucosePayload(0x1C, 0x02)))
    }

    @Test
    fun shortPayloadAndZeroOffsetAreRejected() {
        assertEquals("too-short", rejected(payload(0x2B, 0x00, 0x00, 0x00, 0x02, 0x78)))
        assertEquals(
            "invalid-offset(u32=0,u16=0)",
            rejected(payload(0x00, 0x00, 0x00, 0x00, 0x02, 0x78, 0x00)),
        )
    }

    @Test
    fun offsetLimitIsThirtyDaysInclusive() {
        assertEquals(43_200, AiDexParser.BROADCAST_MAX_OFFSET_MINUTES)
        // 0xA8C0 = 43200, 0xA8C1 = 43201
        assertEquals(43_200, accepted(payload(0xC0, 0xA8, 0x00, 0x00, 0x00, 0x78, 0x00)).offsetMinutes)
        assertEquals(
            "invalid-offset(u32=43201,u16=43201)",
            rejected(payload(0xC1, 0xA8, 0x00, 0x00, 0x00, 0x78, 0x00)),
        )
    }

    @Test
    fun outOfRangeU32FallsBackToU16() {
        // Pin of today's behaviour: u32 0x00010010 = 65552 is over the limit, u16 0x0010 = 16.
        assertEquals(16, accepted(payload(0x10, 0x00, 0x01, 0x00, 0x00, 0x78, 0x00)).offsetMinutes)
        // u32 0xFFFFFFFF .toInt() = -1, u16 = 65535: both out of range.
        assertEquals(
            "invalid-offset(u32=-1,u16=65535)",
            rejected(payload(0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x78, 0x00)),
        )
    }

    @Test
    fun everyTenBitValueIsAcceptedIffInRange() {
        for (carry in 0..3) {
            for (lo in 0..255) {
                val packed = lo or (carry shl 8)
                val parsed = AiDexParser.parseBroadcastSample(glucosePayload(lo, carry))
                if (packed in 20..500) {
                    assertNotNull("packed=$packed", parsed.sample)
                    assertEquals("packed=$packed", packed, parsed.sample!!.glucoseMgDl)
                } else {
                    assertNull("packed=$packed", parsed.sample)
                    assertEquals("invalid-glucose(packed=$packed)", parsed.rejectionReason)
                }
            }
        }
    }

    @Test
    fun decodePackedHandlesShortArrays() {
        // Pin of the helper's own guards: no lo byte -> 0; no carry byte -> carry 0.
        assertEquals(0, AiDexParser.decodePackedBroadcastGlucoseMgDl(ByteArray(5)))
        assertEquals(0x78, AiDexParser.decodePackedBroadcastGlucoseMgDl(payload(0, 0, 0, 0, 0, 0x78)))
    }
}
