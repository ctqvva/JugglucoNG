package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsProtocolExtraTests {

    private fun u16le(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun finish(p: ByteArray): ByteArray {
        p[p.size - 1] = SibionicsProtocol.checksum(p, p.size - 1)
        return p
    }

    private fun handshake(code: Int): ByteArray {
        val p = ByteArray(5)
        p[0] = 4
        p[1] = code.toByte()
        return SibionicsProtocol.encrypt(finish(p))
    }

    private fun v120Data(
        count: Int,
        baseIndex: Int,
        baseSeconds: Long,
        glucose: Int,
        temp: Int,
        flags: Int,
    ): ByteArray {
        val p = ByteArray(9 + count * 8 + 1)
        p[0] = (p.size - 1).toByte()
        p[1] = 0x08
        p[2] = count.toByte()
        p[3] = (baseIndex and 0xFF).toByte()
        p[4] = ((baseIndex ushr 8) and 0xFF).toByte()
        p[5] = (baseSeconds and 0xFF).toByte()
        p[6] = ((baseSeconds ushr 8) and 0xFF).toByte()
        p[7] = ((baseSeconds ushr 16) and 0xFF).toByte()
        p[8] = ((baseSeconds ushr 24) and 0xFF).toByte()
        for (i in 0 until count) {
            val o = 9 + i * 8
            p[o] = (temp and 0xFF).toByte()
            p[o + 1] = ((temp ushr 8) and 0xFF).toByte()
            p[o + 4] = (glucose and 0xFF).toByte()
            p[o + 5] = ((glucose ushr 8) and 0xFF).toByte()
            p[o + 6] = flags.toByte()
        }
        return SibionicsProtocol.encrypt(finish(p))
    }

    private fun chinese(count: Int, glucose: Int, numUnreceived: Int, addTime: Int): ByteArray {
        val p = ByteArray(4 + count * 14 + 1)
        p[0] = 0xAA.toByte()
        p[1] = 0x55
        p[2] = 0x09
        p[3] = count.toByte()
        for (i in 0 until count) {
            val o = 4 + i * 14
            p[o] = 0
            p[o + 1] = (i + 1).toByte()
            p[o + 6] = (glucose ushr 8).toByte()
            p[o + 7] = (glucose and 0xFF).toByte()
            p[o + 10] = (numUnreceived ushr 8).toByte()
            p[o + 11] = (numUnreceived and 0xFF).toByte()
            p[o + 12] = (addTime ushr 8).toByte()
            p[o + 13] = (addTime and 0xFF).toByte()
        }
        return finish(p)
    }

    @Test
    fun checksumOfEmptyIsZero() {
        assertEquals(0, SibionicsProtocol.checksum(byteArrayOf()).toInt())
    }

    @Test
    fun verifyChecksumRejectsShortAndBadTrailingByte() {
        assertFalse(SibionicsProtocol.verifyChecksum(byteArrayOf(1)))
        val good = finish(byteArrayOf(3, 0x10, 0x20, 0x30, 0x40, 0))
        good[2] = (good[2] + 1).toByte()
        assertFalse(SibionicsProtocol.verifyChecksum(good))
    }

    @Test
    fun encryptDecryptAreInvolutive() {
        val plain = "sibionics".toByteArray()
        val enc = SibionicsProtocol.encrypt(plain)
        assertEquals(plain.size, enc.size)
        assertNotEquals(plain.toList(), enc.toList())
        assertTrue(plain.contentEquals(SibionicsProtocol.decrypt(enc)))
    }

    @Test
    fun deriveSessionKeyIs16BytesForEveryVariant() {
        SibionicsConstants.Variant.entries.forEach { variant ->
            val key = SibionicsProtocol.deriveSessionKey(variant)
            assertEquals(variant.name, 16, key?.size)
        }
    }

    @Test
    fun buildAuthPacketReversesMacAndCarriesSessionKey() {
        val mac = byteArrayOf(1, 2, 3, 4, 5, 6)
        val sessionKey = ByteArray(16) { (it + 1).toByte() }
        val plain = SibionicsProtocol.decrypt(SibionicsProtocol.buildAuthPacket(mac, sessionKey))
        assertEquals(26, plain.size)
        assertEquals(0x19.toByte(), plain[0])
        assertTrue(SibionicsProtocol.verifyChecksum(plain))
        assertEquals(listOf<Byte>(6, 5, 4, 3, 2, 1), plain.copyOfRange(3, 9).toList())
        assertTrue(sessionKey.contentEquals(plain.copyOfRange(9, 25)))
    }

    @Test
    fun buildAuthPacketNullMacBecomesZeros() {
        val plain = SibionicsProtocol.decrypt(SibionicsProtocol.buildAuthPacket(null, ByteArray(16)))
        assertTrue(plain.copyOfRange(3, 9).all { it == 0.toByte() })
    }

    @Test
    fun activationAndTimeSyncPacketsAreDeterministic() {
        val activation = SibionicsProtocol.decrypt(SibionicsProtocol.buildActivationPacket(1_700_000_000L))
        assertEquals(0x0A.toByte(), activation[0])
        assertEquals(0x07.toByte(), activation[1])
        assertEquals(1_700_000_000L, u32le(activation, 2))
        assertEquals(1234L, u32le(activation, 6))
        assertTrue(SibionicsProtocol.verifyChecksum(activation))

        val sync = SibionicsProtocol.decrypt(SibionicsProtocol.buildTimeSyncPacket(1_600_000_000L))
        assertEquals(0x06.toByte(), sync[0])
        assertEquals(0x03.toByte(), sync[1])
        assertEquals(1_600_000_000L, u32le(sync, 2))
        assertTrue(SibionicsProtocol.verifyChecksum(sync))
    }

    @Test
    fun dataRequestClampsIndexToUnsigned16() {
        val low = SibionicsProtocol.decrypt(SibionicsProtocol.buildDataRequestPacket(-5))
        assertEquals(0, u16le(low, 2))
        val high = SibionicsProtocol.decrypt(SibionicsProtocol.buildDataRequestPacket(70_000))
        assertEquals(65535, u16le(high, 2))
    }

    @Test
    fun chineseDataRequestClampsToAtLeastOne() {
        val atZero = SibionicsProtocol.buildChineseDataRequest(0, null)
        assertEquals(1, (atZero[3].toInt() and 0xFF) or ((atZero[4].toInt() and 0xFF) shl 8))
        assertEquals(0xAA, atZero[0].toInt() and 0xFF)
        assertEquals(SibionicsProtocol.checksum(atZero, atZero.size - 1), atZero.last())
    }

    @Test
    fun parseV120HandshakeMapsKnownCodeAndRejectsUnknown() {
        val accepted = SibionicsProtocol.parseV120(handshake(0x01))
        assertEquals(SibionicsProtocol.ParseResult.Handshake(SibionicsProtocol.ResponseType.AUTH_ACCEPTED), accepted)
        val unknown = SibionicsProtocol.parseV120(handshake(0x42))
        assertTrue(unknown is SibionicsProtocol.ParseResult.Unknown)
    }

    @Test
    fun parseV120ReportsChecksumError() {
        val p = SibionicsProtocol.decrypt(handshake(0x01))
        p[1] = (p[1] + 1).toByte()
        val result = SibionicsProtocol.parseV120(SibionicsProtocol.encrypt(p))
        assertTrue(result is SibionicsProtocol.ParseResult.ChecksumError)
    }

    @Test
    fun parseV120DataDecodesEntriesAndTrend() {
        val framed = v120Data(count = 2, baseIndex = 5, baseSeconds = 1_700_000_000L, glucose = 75, temp = 325, flags = 4 shl 3)
        val result = SibionicsProtocol.parseV120(framed)
        assertTrue(result is SibionicsProtocol.ParseResult.V120Data)
        val entries = (result as SibionicsProtocol.ParseResult.V120Data).entries
        assertEquals(2, entries.size)
        val first = entries[0]
        assertEquals(5, first.index)
        assertEquals(1_700_000_000_000L, first.eventTimeMs)
        assertEquals(7.5f, first.rawMmol, 0.0001f)
        assertEquals(7.5f * SibionicsConstants.MGDL_PER_MMOLL, first.rawMgdl, 0.001f)
        assertEquals(32.5f, first.temperatureC, 0.0001f)
        assertEquals(SibionicsProtocol.Trend.STABLE, first.trend)
        assertFalse(first.isLive)
        assertEquals(0, entries[1].reindex)
        assertTrue(entries[1].isLive)
        assertEquals(1_700_000_000_000L + SibionicsConstants.READING_INTERVAL_MS, entries[1].eventTimeMs)
    }

    @Test
    fun parseV120AuthMarkerIsRecognisedBeforeDecrypt() {
        val marker = byteArrayOf(0x23, 0xF7.toByte(), 0x6F, 0xD9.toByte(), 0xF4.toByte())
        assertEquals(SibionicsProtocol.ParseResult.V120AuthRequired, SibionicsProtocol.parseV120(marker))
    }

    @Test
    fun parseChineseEmptyDataAndEchoAndUnknown() {
        val empty = SibionicsProtocol.parseChinese(chinese(0, 0, 0, 0))
        assertTrue(empty is SibionicsProtocol.ParseResult.ChineseData)
        assertTrue((empty as SibionicsProtocol.ParseResult.ChineseData).entries.isEmpty())

        val echo = SibionicsProtocol.parseChinese(byteArrayOf(0xAA.toByte(), 0x55, 0x07))
        assertEquals(SibionicsProtocol.ParseResult.ChineseEcho, echo)

        val unknown = SibionicsProtocol.parseChinese(byteArrayOf(1, 2, 3))
        assertTrue(unknown is SibionicsProtocol.ParseResult.Unknown)
    }

    @Test
    fun parseChineseDetectsChecksumError() {
        val bad = chinese(1, 80, 0, 0)
        bad[bad.size - 1] = (bad[bad.size - 1] + 1).toByte()
        assertTrue(SibionicsProtocol.parseChinese(bad) is SibionicsProtocol.ParseResult.ChecksumError)
    }

    @Test
    fun parseChineseDecodesBigEndianFieldsAndEventTime() {
        val result = SibionicsProtocol.parseChinese(chinese(1, glucose = 80, numUnreceived = 1, addTime = 30))
        val entry = (result as SibionicsProtocol.ParseResult.ChineseData).entries.single()
        assertEquals(8.0f, entry.rawMmol, 0.0001f)
        assertFalse(entry.isLive)
        assertEquals(1_000_000L - 30_000L, entry.eventTimeMs(1_000_000L))
    }

    @Test
    fun trendFromFlagsMapsAllCodesAndFallsBack() {
        SibionicsProtocol.Trend.entries.forEach { trend ->
            assertEquals(trend, SibionicsProtocol.Trend.fromFlags(trend.code shl 3))
        }
        assertEquals(SibionicsProtocol.Trend.NOT_DETERMINED, SibionicsProtocol.Trend.fromFlags(0))
    }

    @Test
    fun responseTypeLookup() {
        assertEquals(SibionicsProtocol.ResponseType.STREAMING_READY, SibionicsProtocol.ResponseType.fromCode(0x08))
        assertNull(SibionicsProtocol.ResponseType.fromCode(0x09))
    }

    @Test
    fun toHexFormatsUnsignedUppercase() {
        assertEquals("FF 00 0A", SibionicsProtocol.toHex(byteArrayOf(0xFF.toByte(), 0, 0x0A)))
    }
}
