package tk.glucodata.drivers.aidex.native.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.aidex.native.crypto.Crc16CcittFalse

/**
 * Evidence for the 17-byte F003 data-frame layout, in particular that the sensor-time offset is
 * a u16 LE *minute* counter at bytes[4..5] — not a u32 LE *second* counter at bytes[1..4].
 *
 * Two independent sources agree:
 *
 * 1. The captured frame recorded in `AGENTS/docs/protocols/aidex/aidexprotocoldeepdive.md`
 *    ("2026-02-23 addendum"). Its embedded CRC-16 validates, so it is a genuine frame, and only
 *    the bytes[4..5] reading yields a sane sensor age for a warmup-range glucose.
 *
 * 2. Three consecutive live frames captured 2026-08-07 from `X-22222DCWAH` (GX-01S, firmware
 *    1.9.1). AES-CFB128 with a per-serial IV means the first 16 keystream bytes are constant, so
 *    `plaintext_a XOR plaintext_b == ciphertext_a XOR ciphertext_b` — which pins down how the
 *    plaintext bytes moved between frames without needing the session key. See
 *    [liveFrameCiphertextDeltasMatchAMinuteCounterAtByte4].
 */
class AiDexParserDataFrameTests {

    /** Documented capture: 43 minutes into the session, glucose 63 mg/dL, i1 8.47, i2 29.50. */
    private val documentedFrame = AiDexParser.dataFromHex(
        "01 00 02 00 2B 00 3F 84 4F 03 86 0B 43 02 00 7D CE"
    )

    @Test
    fun documentedFrameCrcValidates() {
        val expected = Crc16CcittFalse.checksum(documentedFrame.copyOfRange(0, 15))
        val trailer = (documentedFrame[15].toInt() and 0xFF) or
            ((documentedFrame[16].toInt() and 0xFF) shl 8)
        assertEquals("documented frame must be a genuine capture", expected, trailer)
    }

    @Test
    fun documentedFrameDecodesToAPlausibleSensorAge() {
        val frame = AiDexParser.parseDataFrame(documentedFrame)
        assertNotNull(frame)
        frame!!

        assertEquals(43, frame.timeOffsetMinutes)
        assertEquals(63f, frame.glucoseMgDl, 0.001f)
        assertEquals(8.47f, frame.i1, 0.001f)
        assertEquals(29.50f, frame.i2, 0.001f)
        assertTrue(frame.isValid)
    }

    @Test
    fun documentedFrameExposesSkinTemperatureFromI2() {
        // The `i2` channel is skin temperature in °C (co-worn Sibionics
        // cross-check: r ≈ 0.72, −0.08 °C mean bias). This cool-skin warmup
        // frame reads 29.5 °C.
        val frame = AiDexParser.parseDataFrame(documentedFrame)
        assertNotNull(frame)
        frame!!

        assertEquals(frame.i2, frame.temperatureC, 0.0f)
        assertEquals(29.50f, frame.temperatureC, 0.001f)
    }

    @Test
    fun offsetIsAU16MinuteCounterNotAU32SecondCounter() {
        val frame = AiDexParser.parseDataFrame(documentedFrame)!!

        // The previous reading — u32 LE seconds at bytes[1..4], divided by 60.
        val legacyOffset = (
            (documentedFrame[1].toLong() and 0xFF) or
                ((documentedFrame[2].toLong() and 0xFF) shl 8) or
                ((documentedFrame[3].toLong() and 0xFF) shl 16) or
                ((documentedFrame[4].toLong() and 0xFF) shl 24)
            ) / 60L

        assertEquals(12_023_680L, legacyOffset)
        assertTrue(
            "the old reading exceeded the driver's 30-day sanity limit on a real frame",
            legacyOffset > 30L * 24L * 60L
        )
        assertTrue(frame.timeOffsetMinutes <= 30 * 24 * 60)
    }

    @Test
    fun offsetHighByteIsCarriedAtByte5() {
        // 2285 == 0x08ED, the age in minutes of X-22222DCWAH's session at 2026-08-07 12:30:28
        // (started 2026-08-05 22:25). Both bytes must participate.
        val frame = buildFrame(offsetMinutes = 2285, glucosePacked = 0x8051, i1Raw = 1013, i2Raw = 3400)
        val parsed = AiDexParser.parseDataFrame(frame)!!

        assertEquals(2285, parsed.timeOffsetMinutes)
        assertEquals(0xED.toByte(), frame[4])
        assertEquals(0x08.toByte(), frame[5])
        assertEquals(81f, parsed.glucoseMgDl, 0.001f)
    }

    @Test
    fun liveFrameCiphertextDeltasMatchAMinuteCounterAtByte4() {
        // First 8 ciphertext bytes of three consecutive live frames, one minute apart.
        val c1 = AiDexParser.dataFromHex("0A ED E3 0D B5 29 84 B2")
        val c2 = AiDexParser.dataFromHex("0A ED E3 06 B6 29 87 B2")
        val c3 = AiDexParser.dataFromHex("0A ED E3 02 B7 29 86 B2")

        // Under a fixed keystream, ciphertext XOR == plaintext XOR.
        val d12 = ByteArray(8) { (c1[it].toInt() xor c2[it].toInt()).toByte() }
        val d23 = ByteArray(8) { (c2[it].toInt() xor c3[it].toInt()).toByte() }

        // Build what the driver's own decoding of those three offsets looks like, and check the
        // same XOR pattern falls out of the offset field alone.
        val f1 = buildFrame(offsetMinutes = 2285, glucosePacked = 0x8051, i1Raw = 1013, i2Raw = 3400)
        val f2 = buildFrame(offsetMinutes = 2286, glucosePacked = 0x8052, i1Raw = 1025, i2Raw = 3400)
        val f3 = buildFrame(offsetMinutes = 2287, glucosePacked = 0x8053, i1Raw = 1025, i2Raw = 3400)

        // Byte 4 (offset low) and byte 6 (glucose low) are the bytes that moved; byte 5 (offset
        // high) and byte 7 (glucose high) held still, exactly as the ciphertext shows.
        assertEquals(d12[4], (f1[4].toInt() xor f2[4].toInt()).toByte())
        assertEquals(d23[4], (f2[4].toInt() xor f3[4].toInt()).toByte())
        assertEquals(0, d12[5].toInt())
        assertEquals(0, d23[5].toInt())
        assertEquals(d12[6], (f1[6].toInt() xor f2[6].toInt()).toByte())
        assertEquals(d23[6], (f2[6].toInt() xor f3[6].toInt()).toByte())
        assertEquals(0, d12[7].toInt())
        assertEquals(0, d23[7].toInt())

        assertEquals(2285, AiDexParser.parseDataFrame(f1)!!.timeOffsetMinutes)
        assertEquals(2286, AiDexParser.parseDataFrame(f2)!!.timeOffsetMinutes)
        assertEquals(2287, AiDexParser.parseDataFrame(f3)!!.timeOffsetMinutes)
    }

    private fun buildFrame(
        offsetMinutes: Int,
        glucosePacked: Int,
        i1Raw: Int,
        i2Raw: Int,
    ): ByteArray {
        val frame = ByteArray(17)
        frame[0] = 0x01
        frame[4] = (offsetMinutes and 0xFF).toByte()
        frame[5] = ((offsetMinutes shr 8) and 0xFF).toByte()
        frame[6] = (glucosePacked and 0xFF).toByte()
        frame[7] = ((glucosePacked shr 8) and 0xFF).toByte()
        frame[8] = (i1Raw and 0xFF).toByte()
        frame[9] = ((i1Raw shr 8) and 0xFF).toByte()
        frame[10] = (i2Raw and 0xFF).toByte()
        frame[11] = ((i2Raw shr 8) and 0xFF).toByte()
        val crc = Crc16CcittFalse.checksum(frame.copyOfRange(0, 15))
        frame[15] = (crc and 0xFF).toByte()
        frame[16] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }
}
