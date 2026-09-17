package tk.glucodata.drivers.aidex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.aidex.native.ble.aiDexExtractLocalName
import tk.glucodata.drivers.aidex.native.crypto.AesCfb128
import tk.glucodata.drivers.aidex.native.crypto.Crc8Maxim
import tk.glucodata.drivers.aidex.native.crypto.SerialCrypto
import tk.glucodata.drivers.aidex.native.data.BroadcastReading
import tk.glucodata.drivers.aidex.native.data.GlucoseReading
import tk.glucodata.drivers.aidex.native.protocol.AiDexDefaultParamProvisioning
import tk.glucodata.drivers.aidex.native.protocol.AiDexKeyExchange

class AiDexPureLogicTests {

    private fun key(offset: Int) = ByteArray(16) { (it + offset).toByte() }

    private fun ad(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 2)
        out[0] = (payload.size + 1).toByte()
        out[1] = type.toByte()
        payload.copyInto(out, 2)
        return out
    }

    @Test
    fun extractLocalNameHandlesCompleteAndShortenedNames() {
        val complete = ad(0x09, "AiDEX 12345678".toByteArray())
        assertEquals("AiDEX 12345678", aiDexExtractLocalName(complete))

        val shortened = ad(0x08, "AiDEX".toByteArray())
        assertEquals("AiDEX", aiDexExtractLocalName(shortened))
    }

    @Test
    fun extractLocalNameSkipsOtherAdStructures() {
        val flags = ad(0x01, byteArrayOf(0x06))
        val name = ad(0x09, "SENSOR".toByteArray())
        val record = flags + name + byteArrayOf(0)
        assertEquals("SENSOR", aiDexExtractLocalName(record))
    }

    @Test
    fun extractLocalNameClampsDeclaredLengthAndHandlesDegenerateRecords() {
        // Declared length 16 but only two payload bytes present.
        val truncated = byteArrayOf(16, 0x09, 'A'.code.toByte(), 'B'.code.toByte())
        assertEquals("AB", aiDexExtractLocalName(truncated))

        assertNull(aiDexExtractLocalName(byteArrayOf()))
        assertNull(aiDexExtractLocalName(byteArrayOf(1)))
        assertNull(aiDexExtractLocalName(byteArrayOf(0)))
    }

    @Test
    fun broadcastBestGlucoseUsesInclusiveRange() {
        fun reading(mgDl: Int, fallback: Int) = BroadcastReading(
            glucoseMgDl = mgDl,
            glucoseFallback = fallback,
            timeOffsetMinutes = 0L,
            trend = 0,
            deviceName = "dev",
        )
        assertEquals(30, reading(30, 99).bestGlucose)
        assertEquals(500, reading(500, 99).bestGlucose)
        assertEquals(99, reading(29, 99).bestGlucose)
        assertEquals(99, reading(501, 99).bestGlucose)
        assertEquals(99, reading(0, 99).bestGlucose)
    }

    @Test
    fun glucoseCompositeKeyConcatenatesTimestampAndSerial() {
        val reading = GlucoseReading(
            timestamp = 1_700_000_000_000L,
            sensorSerial = "2222267V4E",
            autoValue = null,
            rawValue = null,
            sensorGlucose = null,
            rawI1 = null,
            rawI2 = null,
            timeOffsetMinutes = 0,
        )
        assertEquals("1700000000000_2222267V4E", reading.compositeKey)
    }

    @Test
    fun aesCfbRejectsBadSizesAndEmptyInput() {
        val k = key(1)
        val iv = key(2)
        assertNull(AesCfb128.encrypt(ByteArray(0), k, iv))
        assertNull(AesCfb128.decrypt(ByteArray(0), k, iv))
        assertNull(AesCfb128.encrypt(byteArrayOf(1), ByteArray(15), iv))
        assertNull(AesCfb128.encrypt(byteArrayOf(1), k, ByteArray(15)))
        assertNull(AesCfb128.ecbEncrypt(ByteArray(15), k))
    }

    @Test
    fun decryptBondDataVerifiesChecksum() {
        val pair = key(1)
        val iv = key(2)
        val session = ByteArray(16) { (it * 5 + 2).toByte() }
        val blob = session + Crc8Maxim.checksum(session).toByte()
        val encrypted = AesCfb128.encrypt(blob, pair, iv)!!
        assertArrayEquals(session, AesCfb128.decryptBondData(encrypted, pair, iv))

        assertNull(AesCfb128.decryptBondData(encrypted, key(9), iv))
        assertNull(AesCfb128.decryptBondData(ByteArray(16), pair, iv))
    }

    @Test
    fun keyExchangeDecryptBondRequiresPairKeyAndValidChecksum() {
        val exchange = AiDexKeyExchange("2222267V4E")
        assertFalse(exchange.decryptBond(ByteArray(17)))

        val pair = key(3)
        exchange.onPairKeyReceived(pair)
        val session = ByteArray(16) { (it * 7 + 1).toByte() }
        val blob = session + Crc8Maxim.checksum(session).toByte()
        val encrypted = AesCfb128.encrypt(blob, pair, exchange.snIv)!!

        assertTrue(exchange.decryptBond(encrypted))
        assertTrue(exchange.isComplete)
        assertArrayEquals(session, exchange.sessionKey)

        exchange.reset()
        assertNull(exchange.sessionKey)
        assertFalse(exchange.decryptBond(encrypted))
    }

    @Test
    fun serialCharMappingAndPrefixStripping() {
        assertEquals(0, SerialCrypto.charToNumeric('0'))
        assertEquals(10, SerialCrypto.charToNumeric('A'))
        assertEquals(35, SerialCrypto.charToNumeric('z'))
        assertEquals(0, SerialCrypto.charToNumeric('-'))

        assertEquals("2222267V4E", SerialCrypto.stripPrefix("X-2222267V4E"))
        assertEquals("2222267V4E", SerialCrypto.stripPrefix("AiDEX X-2222267V4E"))
        assertEquals("2222267V4E", SerialCrypto.stripPrefix("2222267V4E"))
        // Too short to be a serial: main leaves sub-minimum-length inputs untouched.
        assertEquals("X-22", SerialCrypto.stripPrefix("X-22"))
    }

    @Test
    fun crc8OfEmptyIsZero() {
        assertEquals(0, Crc8Maxim.checksum(byteArrayOf()))
    }

    @Test
    fun deriveCatalogVersionKeyNormalisesFirmwareTokens() {
        assertNull(AiDexDefaultParamProvisioning.deriveCatalogVersionKey(null))
        assertNull(AiDexDefaultParamProvisioning.deriveCatalogVersionKey(""))
        assertNull(AiDexDefaultParamProvisioning.deriveCatalogVersionKey("   "))
        assertEquals("1.7.1", AiDexDefaultParamProvisioning.deriveCatalogVersionKey("1.7.1.3"))
        assertEquals("1.8.1", AiDexDefaultParamProvisioning.deriveCatalogVersionKey("1.8.1"))
        assertEquals("1.7.1", AiDexDefaultParamProvisioning.deriveCatalogVersionKey("1.7.1 (build 2)"))
        assertEquals("1.7.1", AiDexDefaultParamProvisioning.deriveCatalogVersionKey("1.7.1.3 beta"))
        assertEquals("v1.2.3.4", AiDexDefaultParamProvisioning.deriveCatalogVersionKey("v1.2.3.4"))
    }

    @Test
    fun normalizeCurrentVariantsValidatesHexShape() {
        assertTrue(AiDexDefaultParamProvisioning.normalizeCurrentVariants("").isEmpty())
        assertTrue(AiDexDefaultParamProvisioning.normalizeCurrentVariants("AABBCCDDEE").isEmpty())
        assertTrue(AiDexDefaultParamProvisioning.normalizeCurrentVariants("AABBCCDDEEF").isEmpty())
        assertTrue(AiDexDefaultParamProvisioning.normalizeCurrentVariants("ZZZZZZZZZZZZ").isEmpty())

        val variant = AiDexDefaultParamProvisioning.normalizeCurrentVariants("aabbccddeeff").single()
        assertEquals("BBCCDDEEFF", variant.hex)
        assertEquals(5, variant.byteCount)
        assertEquals("BBCCDDEE", variant.versionHex)
        assertFalse(variant.headerSwapApplied)
    }

    @Test
    fun hexToBytesAcceptsLowercaseAndRejectsMalformed() {
        assertTrue(AiDexDefaultParamProvisioning.hexToBytes("")!!.isEmpty())
        assertArrayEquals(byteArrayOf(0x0A, 0x0B), AiDexDefaultParamProvisioning.hexToBytes("0a0B")!!)
        assertNull(AiDexDefaultParamProvisioning.hexToBytes("ABC"))
        assertNull(AiDexDefaultParamProvisioning.hexToBytes("GG"))
    }

    @Test
    fun deviceListDirtyFlagRoundTrips() {
        AiDexDriver.deviceListDirty = false
        assertFalse(AiDexDriver.deviceListDirty)
        AiDexDriver.deviceListDirty = true
        assertTrue(AiDexDriver.deviceListDirty)
        AiDexDriver.deviceListDirty = false
        assertFalse(AiDexDriver.deviceListDirty)
    }
}
