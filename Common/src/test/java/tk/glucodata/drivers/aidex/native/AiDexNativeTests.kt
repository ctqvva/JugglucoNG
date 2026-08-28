// JugglucoNG — AiDex Native Kotlin Driver
// AiDexNativeTests.kt — Unit tests for CRC, crypto, parsing, key derivation,
// history merge, and storage filtering
// Test vectors from NIST SP 800-38A, captured HCI traces, and live sensor data
//
// Ported from iGlucco Tests/AiDexProtocolTests.swift (68 tests)

package tk.glucodata.drivers.aidex.native

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.drivers.aidex.native.crypto.AesCfb128
import tk.glucodata.drivers.aidex.native.crypto.Crc16CcittFalse
import tk.glucodata.drivers.aidex.native.crypto.Crc8Maxim
import tk.glucodata.drivers.aidex.native.crypto.SerialCrypto
import tk.glucodata.drivers.aidex.native.ble.aiDexActivationTimeZone
import tk.glucodata.drivers.aidex.native.ble.aiDexDeviceNameMatchesSerial
import tk.glucodata.drivers.aidex.native.data.*
import tk.glucodata.drivers.aidex.native.protocol.AiDexCommandBuilder
import tk.glucodata.drivers.aidex.native.protocol.AiDexDpCatalogProvider
import tk.glucodata.drivers.aidex.native.protocol.AiDexDefaultParamProvisioning
import tk.glucodata.drivers.aidex.native.protocol.AiDexKeyExchange
import tk.glucodata.drivers.aidex.native.protocol.AiDexOpcodes
import tk.glucodata.drivers.aidex.native.protocol.AiDexParser
import java.util.Calendar
import java.util.TimeZone

// ============================================================================
// MARK: - CRC-8/MAXIM Tests
// ============================================================================

class Crc8MaximTests {

    @Test
    fun testEmptyInput() {
        assertEquals(0x00, Crc8Maxim.checksum(byteArrayOf()))
    }

    @Test
    fun testSingleByte() {
        // CRC-8/MAXIM of [0x00] should be 0x00 (identity)
        assertEquals(0x00, Crc8Maxim.checksum(byteArrayOf(0x00)))
    }

    @Test
    fun testKnownValues() {
        // CRC-8/MAXIM("123456789") = 0xA1 (standard check value)
        val check = byteArrayOf(0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39)
        assertEquals(0xA1, Crc8Maxim.checksum(check))
    }

    @Test
    fun testMultipleBytes() {
        val bytes = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        // Just verify it produces a consistent result
        val result = Crc8Maxim.checksum(bytes)
        assertEquals(result, Crc8Maxim.checksum(bytes))
    }
}

// ============================================================================
// MARK: - CRC-16/CCITT-FALSE Tests
// ============================================================================

class Crc16CcittFalseTests {

    // Known test vectors from HCI trace
    @Test
    fun testPostBondConfig() {
        // CRC-16(0x10) = 0xF3C1
        assertEquals(0xF3C1, Crc16CcittFalse.checksum(byteArrayOf(0x10)))
    }

    @Test
    fun testGetBroadcastData() {
        // CRC-16(0x11) = 0xE3E0
        assertEquals(0xE3E0, Crc16CcittFalse.checksum(byteArrayOf(0x11)))
    }

    @Test
    fun testGetDeviceInfo() {
        // CRC-16(0x21) = 0xD5B3
        assertEquals(0xD5B3, Crc16CcittFalse.checksum(byteArrayOf(0x21)))
    }

    @Test
    fun testDeleteBond() {
        // CRC-16(0xF2) = 0x2EAD
        assertEquals(0x2EAD, Crc16CcittFalse.checksum(byteArrayOf(0xF2.toByte())))
    }

    @Test
    fun testMultipleBytes() {
        val bytes = byteArrayOf(0x23, 0x40, 0x38)
        val result = Crc16CcittFalse.checksum(bytes)
        assertEquals(result, Crc16CcittFalse.checksum(bytes))
    }

    @Test
    fun testMakeCommand_NoParams() {
        // makeCommand(0x11) should produce [0x11, 0xE0, 0xE3]
        val cmd = Crc16CcittFalse.makeCommand(0x11)
        assertArrayEquals(byteArrayOf(0x11, 0xE0.toByte(), 0xE3.toByte()), cmd)
    }

    @Test
    fun testMakeCommand_PostBondConfig() {
        // makeCommand(0x10) should produce [0x10, 0xC1, 0xF3]
        val cmd = Crc16CcittFalse.makeCommand(0x10)
        assertArrayEquals(byteArrayOf(0x10, 0xC1.toByte(), 0xF3.toByte()), cmd)
    }

    @Test
    fun testMakeCommand_DeleteBond() {
        // makeCommand(0xF2) should produce [0xF2, 0xAD, 0x2E]
        val cmd = Crc16CcittFalse.makeCommand(0xF2)
        assertArrayEquals(byteArrayOf(0xF2.toByte(), 0xAD.toByte(), 0x2E), cmd)
    }

    @Test
    fun testMakeCommand_WithParams() {
        // From live log: plaintext=23 40 38 9D A9
        // opcode=0x23, params=[0x40, 0x38], CRC=[0x9D, 0xA9]
        val cmd = Crc16CcittFalse.makeCommand(0x23, 0x40, 0x38)
        assertArrayEquals(byteArrayOf(0x23, 0x40, 0x38, 0x9D.toByte(), 0xA9.toByte()), cmd)
    }

    @Test
    fun testMakeCommandWithU16() {
        // From live log: plaintext=23 40 38 9D A9
        // offset = 0x3840 = 14400
        val cmd = Crc16CcittFalse.makeCommandWithU16(0x23, 14400)
        assertArrayEquals(byteArrayOf(0x23, 0x40, 0x38, 0x9D.toByte(), 0xA9.toByte()), cmd)
    }

    @Test
    fun testValidateResponse() {
        // makeCommand already validates CRC round-trip
        val cmd = Crc16CcittFalse.makeCommand(0x21)
        assertTrue(Crc16CcittFalse.validateResponse(cmd))
    }

    @Test
    fun testValidateResponse_CorruptData() {
        // Corrupt: flip a bit
        val cmd = Crc16CcittFalse.makeCommand(0x21)
        cmd[0] = (cmd[0].toInt() xor 0x01).toByte()
        assertFalse(Crc16CcittFalse.validateResponse(cmd))
    }

    @Test
    fun testValidateResponse_TooShort() {
        assertFalse(Crc16CcittFalse.validateResponse(byteArrayOf(0x11)))
        assertFalse(Crc16CcittFalse.validateResponse(byteArrayOf()))
    }

    // Verify CRC-16 on multiple live log command plaintexts
    @Test
    fun testLiveLogPlaintextCRCs() {
        data class CrcVector(val plaintext: ByteArray, val label: String)

        val vectors = listOf(
            CrcVector(byteArrayOf(0x23, 0x40, 0x38, 0x9D.toByte(), 0xA9.toByte()), "GET_HISTORIES_RAW(14400)"),
            CrcVector(byteArrayOf(0x23, 0xB7.toByte(), 0x38, 0xCB.toByte(), 0x23), "GET_HISTORIES_RAW(14519)"),
            CrcVector(byteArrayOf(0x23, 0x2E, 0x39, 0x99.toByte(), 0x91.toByte()), "GET_HISTORIES_RAW(14638)"),
            CrcVector(byteArrayOf(0x23, 0xA5.toByte(), 0x39, 0xFB.toByte(), 0x56), "GET_HISTORIES_RAW(14757)"),
            CrcVector(byteArrayOf(0x23, 0x1C, 0x3A, 0x0D, 0xC2.toByte()), "GET_HISTORIES_RAW(14876)"),
            CrcVector(byteArrayOf(0x23, 0x93.toByte(), 0x3A, 0xAB.toByte(), 0xC9.toByte()), "GET_HISTORIES_RAW(14995)"),
            CrcVector(byteArrayOf(0x23, 0x0A, 0x3B, 0xF9.toByte(), 0x7B), "GET_HISTORIES_RAW(15114)"),
            CrcVector(byteArrayOf(0x23, 0x81.toByte(), 0x3B, 0x9B.toByte(), 0xBC.toByte()), "GET_HISTORIES_RAW(15233)"),
            CrcVector(byteArrayOf(0x23, 0xF8.toByte(), 0x3B, 0x5A, 0x0E), "GET_HISTORIES_RAW(15352)"),
        )

        for (v in vectors) {
            assertTrue("CRC failed for ${v.label}", Crc16CcittFalse.validateResponse(v.plaintext))
        }
    }
}

// ============================================================================
// MARK: - Serial Crypto Tests
// ============================================================================

class SerialCryptoTests {

    @Test
    fun testCharToNumeric_Digits() {
        assertEquals(0, SerialCrypto.charToNumeric('0'))
        assertEquals(5, SerialCrypto.charToNumeric('5'))
        assertEquals(9, SerialCrypto.charToNumeric('9'))
    }

    @Test
    fun testCharToNumeric_Letters() {
        assertEquals(10, SerialCrypto.charToNumeric('A'))
        assertEquals(15, SerialCrypto.charToNumeric('F'))
        assertEquals(31, SerialCrypto.charToNumeric('V'))
        assertEquals(35, SerialCrypto.charToNumeric('Z'))
    }

    @Test
    fun testSnToBytes() {
        // "2222267V4E" -> [2, 2, 2, 2, 2, 6, 7, 31, 4, 14]
        val bytes = SerialCrypto.snToBytes("2222267V4E")
        assertArrayEquals(byteArrayOf(2, 2, 2, 2, 2, 6, 7, 31, 4, 14), bytes)
    }

    @Test
    fun testDeriveSecret() {
        // Known vector: SN "2222267V4E" -> secret = 4b76169576da80e4eeacf886230873d2
        val secret = SerialCrypto.deriveSecret("2222267V4E")
        val expected = byteArrayOf(
            0x4b, 0x76, 0x16, 0x95.toByte(), 0x76, 0xda.toByte(), 0x80.toByte(), 0xe4.toByte(),
            0xee.toByte(), 0xac.toByte(), 0xf8.toByte(), 0x86.toByte(), 0x23, 0x08, 0x73, 0xd2.toByte()
        )
        assertArrayEquals(expected, secret)
    }

    @Test
    fun testDeriveIV() {
        // Known vector: SN "2222267V4E" -> IV = 14cb6a3a39b96c448ebc39185f70f8aa
        val iv = SerialCrypto.deriveIv("2222267V4E")
        val expected = byteArrayOf(
            0x14, 0xcb.toByte(), 0x6a, 0x3a, 0x39, 0xb9.toByte(), 0x6c, 0x44,
            0x8e.toByte(), 0xbc.toByte(), 0x39, 0x18, 0x5f, 0x70, 0xf8.toByte(), 0xaa.toByte()
        )
        assertArrayEquals(expected, iv)
    }

    @Test
    fun testStripPrefix() {
        assertEquals("2222267V4E", SerialCrypto.stripPrefix("AiDEX X-2222267V4E"))
        assertEquals("2222267V4E", SerialCrypto.stripPrefix("X-2222267V4E"))
        assertEquals("2222293NWA", SerialCrypto.stripPrefix("AiDEX x-2222293NWA"))
        assertEquals("22222FZXKT", SerialCrypto.stripPrefix("AiDEX F-22222FZXKT"))
        assertEquals("22222FZXKT", SerialCrypto.stripPrefix("F-22222FZXKT"))
        assertEquals("2222267V4E", SerialCrypto.stripPrefix("2222267V4E"))
    }

    @Test
    fun testFGenerationSerialDerivesCapturedChallenge() {
        val secret = SerialCrypto.deriveSecret(SerialCrypto.stripPrefix("AiDEX F-22222FZXKT"))
        assertEquals("F0740DC2FA51F24A884F61FC4C14885B", AiDexParser.compactHex(secret))
    }

    @Test
    fun testDeriveSecretAndIV_Length() {
        val secret = SerialCrypto.deriveSecret("22222689WH")
        val iv = SerialCrypto.deriveIv("22222689WH")
        assertEquals(16, secret.size)
        assertEquals(16, iv.size)
    }

    @Test
    fun testDeriveSecretDeterministic() {
        val s1 = SerialCrypto.deriveSecret("2222267V4E")
        val s2 = SerialCrypto.deriveSecret("2222267V4E")
        assertArrayEquals(s1, s2)
    }

    @Test
    fun testDifferentSNsDifferentKeys() {
        val s1 = SerialCrypto.deriveSecret("2222267V4E")
        val s2 = SerialCrypto.deriveSecret("22222689WH")
        assertFalse(s1.contentEquals(s2))
    }
}

class DeviceNameMatchingTests {

    @Test
    fun testAiDexDeviceNameMatchesSerialIgnoringPrefixCase() {
        assertTrue(aiDexDeviceNameMatchesSerial("AiDEX x-2222293NWA", "X-2222293NWA"))
    }

    @Test
    fun testAiDexDeviceNameMatchesSerialIgnoringSerialCase() {
        assertTrue(aiDexDeviceNameMatchesSerial("aidex x-2222293nwa", "X-2222293NWA"))
    }

    @Test
    fun testAiDexDeviceNameRejectsDifferentSerial() {
        assertFalse(aiDexDeviceNameMatchesSerial("AiDEX x-2222293NWA", "X-222228AWH2"))
    }
}

// ============================================================================
// MARK: - AES-128-CFB Crypto Tests
// ============================================================================

class CryptoEngineTests {

    // NIST SP 800-38A F.3.13 AES-128-CFB test vector
    private val nistKey = byteArrayOf(
        0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae.toByte(), 0xd2.toByte(), 0xa6.toByte(),
        0xab.toByte(), 0xf7.toByte(), 0x15, 0x88.toByte(), 0x09, 0xcf.toByte(), 0x4f, 0x3c
    )
    private val nistIV = byteArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
    )
    private val nistPlaintext = byteArrayOf(
        0x6b, 0xc1.toByte(), 0xbe.toByte(), 0xe2.toByte(), 0x2e, 0x40, 0x9f.toByte(), 0x96.toByte(),
        0xe9.toByte(), 0x3d, 0x7e, 0x11, 0x73, 0x93.toByte(), 0x17, 0x2a
    )
    private val nistCiphertext = byteArrayOf(
        0x3b, 0x3f, 0xd9.toByte(), 0x2e, 0xb7.toByte(), 0x2d, 0xad.toByte(), 0x20,
        0x33, 0x34, 0x49, 0xf8.toByte(), 0xe8.toByte(), 0x3c, 0xfb.toByte(), 0x4a
    )

    @Test
    fun testNISTEncrypt() {
        val encrypted = AesCfb128.encrypt(nistPlaintext, nistKey, nistIV)
        assertNotNull(encrypted)
        assertArrayEquals(nistCiphertext, encrypted)
    }

    @Test
    fun testNISTDecrypt() {
        val decrypted = AesCfb128.decrypt(nistCiphertext, nistKey, nistIV)
        assertNotNull(decrypted)
        assertArrayEquals(nistPlaintext, decrypted)
    }

    @Test
    fun testNISTRoundTrip() {
        val encrypted = AesCfb128.encrypt(nistPlaintext, nistKey, nistIV)!!
        val decrypted = AesCfb128.decrypt(encrypted, nistKey, nistIV)
        assertArrayEquals(nistPlaintext, decrypted)
    }

    @Test
    fun testECBEncrypt_NIST() {
        // ECB(key, IV) XOR plaintext == ciphertext
        val ecb = AesCfb128.ecbEncrypt(nistIV, nistKey)
        assertNotNull(ecb)
        assertEquals(16, ecb!!.size)
        val xored = ByteArray(16) { i -> (ecb[i].toInt() xor nistPlaintext[i].toInt()).toByte() }
        assertArrayEquals(nistCiphertext, xored)
    }

    @Test
    fun testCustomKeyRoundTrip() {
        // Custom key from runSelfTest()
        val testKey = byteArrayOf(
            0xAC.toByte(), 0x4C, 0x8E.toByte(), 0xCD.toByte(), 0xD8.toByte(), 0x76, 0x1B, 0x51,
            0x2E, 0xEB.toByte(), 0x95.toByte(), 0xD7.toByte(), 0x07, 0x94.toByte(), 0x29, 0x12
        )
        val testIV = byteArrayOf(
            0x48, 0x1B, 0x33, 0x85.toByte(), 0x5D, 0xD7.toByte(), 0xB8.toByte(), 0xB8.toByte(),
            0xFB.toByte(), 0xDE.toByte(), 0x15, 0xDC.toByte(), 0xE1.toByte(), 0x11, 0x00, 0x0B
        )
        val testPlain = byteArrayOf(
            0x01, 0x00, 0x02, 0x00, 0x2B, 0x00, 0x3F, 0x84.toByte(),
            0x4F, 0x03, 0x86.toByte(), 0x0B, 0x43, 0x02, 0x00, 0x7D
        )

        val encrypted = AesCfb128.encrypt(testPlain, testKey, testIV)
        assertNotNull(encrypted)
        val decrypted = AesCfb128.decrypt(encrypted!!, testKey, testIV)
        assertArrayEquals(testPlain, decrypted)
    }

    @Test
    fun testDecryptWithWrongKey() {
        val wrongKey = ByteArray(16) { 0xFF.toByte() }
        val decrypted = AesCfb128.decrypt(nistCiphertext, wrongKey, nistIV)
        assertNotNull(decrypted) // decryption succeeds but produces wrong data
        assertFalse(nistPlaintext.contentEquals(decrypted))
    }

    @Test
    fun testMultiBlockRoundTrip() {
        // Test with 48 bytes (3 blocks)
        val multiBlock = ByteArray(48) { 0xAB.toByte() }
        val encrypted = AesCfb128.encrypt(multiBlock, nistKey, nistIV)
        assertNotNull(encrypted)
        assertEquals(48, encrypted!!.size)
        val decrypted = AesCfb128.decrypt(encrypted, nistKey, nistIV)
        assertArrayEquals(multiBlock, decrypted)
    }
}

// ============================================================================
// MARK: - F003 Data Frame Parsing Tests
// ============================================================================

class DataFrameParsingTests {

    @Test
    fun testParseDataFrame_RawrExample() {
        // From rawr.txt: 01 00 02 00 2B 00 3F 84 4F 03 86 0B 43 02 00 7D CE
        // bytes[4..5] = 2B 00 → u16LE = 43 minutes since sensor start
        // glucose = bytes[6..7] LE with mask 0x03FF = 0x843F & 0x03FF = 63 mg/dL
        // i1 = bytes[8..9] LE = 0x034F / 100 = 8.47
        // i2 = bytes[10..11] LE = 0x0B86 / 100 = 29.50
        // CRC = bytes[15..16] LE = 0xCE7D
        val frame = byteArrayOf(
            0x01, 0x00, 0x02, 0x00, 0x2B, 0x00, 0x3F, 0x84.toByte(),
            0x4F, 0x03, 0x86.toByte(), 0x0B, 0x43, 0x02, 0x00, 0x7D, 0xCE.toByte()
        )

        val parsed = AiDexParser.parseDataFrame(frame)
        assertNotNull(parsed)

        assertEquals(0x01, parsed!!.opcode)
        assertEquals(43, parsed.timeOffsetMinutes) // u16LE(bytes[4..5]) = 0x002B
        // rawGlucosePacked = 0x843F, glucose = 0x843F & 0x03FF = 0x003F = 63
        assertEquals(0x843F, parsed.rawGlucosePacked)
        assertEquals(63.0f, parsed.glucoseMgDl, 0.01f)
        assertEquals(8.47f, parsed.i1, 0.01f)
        assertEquals(29.50f, parsed.i2, 0.01f)
        assertEquals(0xCE7D, parsed.crc16)
        assertTrue(parsed.isValid) // 63 is in range [20, 500]
    }

    @Test
    fun testParseDataFrame_WrongLength() {
        assertNull(AiDexParser.parseDataFrame(byteArrayOf(0x01, 0x02, 0x03)))
        assertNull(AiDexParser.parseDataFrame(ByteArray(16)))
        assertNull(AiDexParser.parseDataFrame(ByteArray(18)))
    }

    @Test
    fun testParseDataFrame_SentinelValue() {
        // Sentinel: rawGlucose = 1023 (0x03FF)
        val frame = ByteArray(17)
        frame[6] = 0xFF.toByte() // glucosePacked lo = 0xFF
        frame[7] = 0x03          // glucosePacked hi = 0x03 -> 0x03FF & 0x03FF = 1023
        val parsed = AiDexParser.parseDataFrame(frame)
        assertNotNull(parsed)
        assertFalse(parsed!!.isValid)
        assertEquals(0, parsed.timeOffsetMinutes) // all zeros → 0
        assertEquals(1023.0f, parsed.glucoseMgDl, 0.01f) // sentinel, but isValid=false
    }

    @Test
    fun testParseDataFrame_HalfScaleOpcode() {
        // opcode 0xD2 -> scaling = 0.5
        val frame = ByteArray(17)
        frame[0] = 0xD2.toByte() // half-scale opcode
        frame[6] = 100            // rawGlucose = 100
        frame[7] = 0x00
        val parsed = AiDexParser.parseDataFrame(frame)
        assertNotNull(parsed)
        assertEquals(50.0f, parsed!!.glucoseMgDl, 0.01f)
        assertTrue(parsed.isValid)
    }

    @Test
    fun testParseDataFrame_DirectOpcode() {
        // opcode 0xA1 -> scaling = 1.0
        val frame = ByteArray(17)
        frame[0] = 0xA1.toByte()
        frame[6] = 80  // rawGlucose = 80
        frame[7] = 0x00
        val parsed = AiDexParser.parseDataFrame(frame)
        assertNotNull(parsed)
        assertEquals(80.0f, parsed!!.glucoseMgDl, 0.01f)
    }

    @Test
    fun testParseDataFrame_TimeOffsetMinutes() {
        val frame = ByteArray(17)
        frame[0] = 0xA1.toByte() // valid direct opcode
        // bytes[4..5]: 15 minutes = 0x000F LE = 0F 00
        frame[4] = 0x0F
        frame[5] = 0x00
        // Bytes 1..3 are not the offset; fill them to prove they are ignored.
        frame[1] = 0x84.toByte()
        frame[2] = 0x03
        frame[3] = 0xFF.toByte()
        frame[6] = 80  // rawGlucose = 80 (valid)
        frame[7] = 0x00
        val parsed = AiDexParser.parseDataFrame(frame)
        assertNotNull(parsed)
        assertEquals(15, parsed!!.timeOffsetMinutes)
        assertEquals(80.0f, parsed.glucoseMgDl, 0.01f)
        assertTrue(parsed.isValid)
    }

    @Test
    fun testParseDataFrame_TimeOffsetUsesBothBytes() {
        val frame = ByteArray(17)
        frame[0] = 0xA1.toByte()
        frame[4] = 0xED.toByte() // 2285 = 0x08ED
        frame[5] = 0x08
        frame[6] = 80
        frame[7] = 0x00
        val parsed = AiDexParser.parseDataFrame(frame)
        assertNotNull(parsed)
        assertEquals(2285, parsed!!.timeOffsetMinutes)
    }

    @Test
    fun testClassifyFrame() {
        assertEquals(AiDexParser.FrameType.DATA, AiDexParser.classifyFrame(ByteArray(17)))
        assertEquals(AiDexParser.FrameType.STATUS, AiDexParser.classifyFrame(ByteArray(5)))
        assertEquals(AiDexParser.FrameType.UNKNOWN, AiDexParser.classifyFrame(ByteArray(10)))
        assertEquals(AiDexParser.FrameType.UNKNOWN, AiDexParser.classifyFrame(byteArrayOf()))
    }
}

// ============================================================================
// MARK: - History Response Parsing Tests
// ============================================================================

class HistoryParsingTests {

    @Test
    fun testParseHistoryResponse_LiveData() {
        // From live log: decrypted response for GET_HISTORIES_RAW(14400)
        val payload = byteArrayOf(
            0x40, 0x38,             // startOffset = 14400
            0x42, 0x80.toByte(),    // entry 0: glucose=66
            0x42, 0x40,             // entry 1: glucose=66
            0x41, 0x80.toByte(),    // entry 2: glucose=65
            0x3F, 0x80.toByte(),    // entry 3: glucose=63
            0x3E, 0x80.toByte(),    // entry 4: glucose=62
            0x3D, 0x40,             // entry 5: glucose=61
            0x3E, 0x80.toByte(),    // entry 6: glucose=62
        )

        val records = AiDexParser.parseHistoryResponse(payload)
        assertEquals(7, records.size)

        // Verify first few entries match live log
        assertEquals(14400, records[0].timeOffsetMinutes)
        assertEquals(66, records[0].glucoseMgDl)
        assertFalse(records[0].isSentinel)

        assertEquals(14401, records[1].timeOffsetMinutes)
        assertEquals(66, records[1].glucoseMgDl)

        assertEquals(14402, records[2].timeOffsetMinutes)
        assertEquals(65, records[2].glucoseMgDl)

        assertEquals(14403, records[3].timeOffsetMinutes)
        assertEquals(63, records[3].glucoseMgDl)

        assertEquals(14404, records[4].timeOffsetMinutes)
        assertEquals(62, records[4].glucoseMgDl)

        assertEquals(14405, records[5].timeOffsetMinutes)
        assertEquals(61, records[5].glucoseMgDl)

        assertEquals(14406, records[6].timeOffsetMinutes)
        assertEquals(62, records[6].glucoseMgDl)
    }

    @Test
    fun testParseHistoryResponse_Sentinel() {
        // Entry with glucose = 1023 (sentinel)
        val payload = byteArrayOf(
            0x00, 0x00,         // startOffset = 0
            0xFF.toByte(), 0x03 // glucose = 0xFF | ((0x03 & 0x03) << 8) = 0x3FF = 1023
        )
        val records = AiDexParser.parseHistoryResponse(payload)
        assertEquals(1, records.size)
        assertTrue(records[0].isSentinel)
        assertEquals(1023, records[0].glucoseMgDl)
    }

    @Test
    fun testParseHistoryResponse_StatusBit() {
        // Test statusBit extraction: (b1 & 0x04) != 0
        val payload = byteArrayOf(
            0x00, 0x00,
            0x42, 0x84.toByte(), // 0x84 & 0x04 = 0x04 -> statusBit = true
            0x42, 0x80.toByte(), // 0x80 & 0x04 = 0x00 -> statusBit = false
        )
        val records = AiDexParser.parseHistoryResponse(payload)
        assertEquals(2, records.size)
        assertTrue(records[0].statusBit)
        assertFalse(records[1].statusBit)
    }

    @Test
    fun testParseHistoryResponse_TooShort() {
        assertEquals(0, AiDexParser.parseHistoryResponse(byteArrayOf()).size)
        assertEquals(0, AiDexParser.parseHistoryResponse(byteArrayOf(0x00)).size)
        assertEquals(0, AiDexParser.parseHistoryResponse(byteArrayOf(0x00, 0x00, 0x00)).size)
    }

    @Test
    fun testParseHistoryResponse_OddBodyBytes() {
        // Body of 3 bytes -> 1 full row (2 bytes) + 1 leftover (discarded)
        val payload = byteArrayOf(0x00, 0x00, 0x42, 0x80.toByte(), 0xFF.toByte())
        val records = AiDexParser.parseHistoryResponse(payload)
        assertEquals(1, records.size)
    }
}

// ============================================================================
// MARK: - Calibration Response Parsing Tests
// ============================================================================

class CalibrationParsingTests {

    @Test
    fun testParseCalibrationResponse_Valid() {
        // Synthetic calibration record:
        // startIndex = 1 (u16 LE)
        // Row: timeOffset=100, refGlucose=120, cf=150(=1.50), offset=50(=0.50)
        val payload = byteArrayOf(
            0x01, 0x00,     // startIndex = 1
            0x64, 0x00,     // timeOffset = 100
            0x78, 0x00,     // refGlucose = 120
            0x96.toByte(), 0x00, // cf = 150
            0x32, 0x00,     // offset = 50
        )
        val records = AiDexParser.parseCalibrationResponse(payload)
        assertEquals(1, records.size)
        assertEquals(100, records[0].timeOffsetMinutes)
        assertEquals(120, records[0].referenceGlucoseMgDl)
        assertEquals(1.50f, records[0].calibrationFactor, 0.01f)
        assertEquals(0.50f, records[0].calibrationOffset, 0.01f)
    }

    @Test
    fun testParseCalibrationResponse_TooShort() {
        assertEquals(0, AiDexParser.parseCalibrationResponse(byteArrayOf()).size)
        assertEquals(0, AiDexParser.parseCalibrationResponse(ByteArray(9)).size)
    }

    @Test
    fun testParseCalibrationResponse_ImplausibleStartIndex() {
        // startIndex > 10000 should be rejected
        val payload = ByteArray(10)
        payload[0] = 0x12           // 0x2712 = 10002
        payload[1] = 0x27
        assertEquals(0, AiDexParser.parseCalibrationResponse(payload).size)
    }

    @Test
    fun testParseCalibrationResponse_BodyNotMultipleOf8() {
        // Body of 7 bytes (not multiple of 8) should be rejected
        val payload = ByteArray(9) // 2 header + 7 body
        assertEquals(0, AiDexParser.parseCalibrationResponse(payload).size)
    }
}

// ============================================================================
// MARK: - Opcode Scaling Tests
// ============================================================================

class OpcodeScalingTests {

    @Test
    fun testDirectOpcodes() {
        for (opcode in listOf(0xA1, 0xA4, 0x5B, 0xD7)) {
            assertEquals(
                "Direct opcode 0x${opcode.toString(16)} should have scaling 1.0",
                1.0f, AiDexOpcodes.scalingFactor(opcode)!!
            )
        }
    }

    @Test
    fun testHalfScaleOpcodes() {
        assertEquals(0.5f, AiDexOpcodes.scalingFactor(0xD2)!!)
    }

    @Test
    fun testUnknownOpcodes() {
        assertNull(AiDexOpcodes.scalingFactor(0x00))
        assertNull(AiDexOpcodes.scalingFactor(0x01))
        assertNull(AiDexOpcodes.scalingFactor(0xFF))
    }
}

// ============================================================================
// MARK: - Hex Conversion Utility Tests
// ============================================================================

class HexUtilityTests {

    @Test
    fun testHexString() {
        val data = byteArrayOf(0x01, 0xAB.toByte(), 0xFF.toByte(), 0x00)
        assertEquals("01 AB FF 00", AiDexParser.hexString(data))
    }

    @Test
    fun testCompactHex() {
        val data = byteArrayOf(0x01, 0xAB.toByte(), 0xFF.toByte(), 0x00)
        assertEquals("01ABFF00", AiDexParser.compactHex(data))
    }

    @Test
    fun testDataFromHex() {
        val hex = "01 AB FF 00"
        val data = AiDexParser.dataFromHex(hex)
        assertArrayEquals(byteArrayOf(0x01, 0xAB.toByte(), 0xFF.toByte(), 0x00), data)
    }

    @Test
    fun testDataFromHex_Compact() {
        val hex = "01ABFF00"
        val data = AiDexParser.dataFromHex(hex)
        assertArrayEquals(byteArrayOf(0x01, 0xAB.toByte(), 0xFF.toByte(), 0x00), data)
    }

    @Test
    fun testHexRoundTrip() {
        val original = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val hex = AiDexParser.compactHex(original)
        val restored = AiDexParser.dataFromHex(hex)
        assertArrayEquals(original, restored)
    }
}

// ============================================================================
// MARK: - Frame Classification Tests
// ============================================================================

class FrameClassificationTests {

    @Test
    fun testHandshakeEcho() {
        val sent = byteArrayOf(0x6F, 0x01, 0x02)
        val received = byteArrayOf(0x6F, 0xAA.toByte(), 0xBB.toByte())
        assertTrue(AiDexParser.isHandshakeEcho(sent, received))
    }

    @Test
    fun testHandshakeEcho_Mismatch() {
        val sent = byteArrayOf(0x6F, 0x01, 0x02)
        val received = byteArrayOf(0x70, 0xAA.toByte(), 0xBB.toByte())
        assertFalse(AiDexParser.isHandshakeEcho(sent, received))
    }

    @Test
    fun testHandshakeEcho_Empty() {
        assertFalse(AiDexParser.isHandshakeEcho(byteArrayOf(), byteArrayOf()))
    }
}

// ============================================================================
// MARK: - Advertisement Parsing Tests
// ============================================================================

class AdvertisementTests {

    @Test
    fun testIsAiDexDevice_ValidNames() {
        assertTrue(AiDexOpcodes.isAiDexDevice("AiDEX X-2222267V4E"))
        assertTrue(AiDexOpcodes.isAiDexDevice("AiDEX X-22222689WH"))
    }

    @Test
    fun testIsAiDexDevice_InvalidNames() {
        assertFalse(AiDexOpcodes.isAiDexDevice("Dexcom G7"))
        assertFalse(AiDexOpcodes.isAiDexDevice(null))
        assertFalse(AiDexOpcodes.isAiDexDevice(""))
    }
}

// ============================================================================
// MARK: - Status Frame Parsing Tests
// ============================================================================

class StatusFrameTests {

    @Test
    fun testParseStatusFrame_Valid() {
        val data = byteArrayOf(0xAA.toByte(), 0x01, 0x02, 0x03, 0x04)
        val frame = AiDexParser.parseStatusFrame(data)
        assertNotNull(frame)
        assertEquals(0xAA, frame!!.header)
    }

    @Test
    fun testParseStatusFrame_WrongLength() {
        assertNull(AiDexParser.parseStatusFrame(byteArrayOf(0x01, 0x02)))
        assertNull(AiDexParser.parseStatusFrame(ByteArray(17)))
    }
}

// ============================================================================
// MARK: - Live Sensor Encrypt/Decrypt Integration Tests
// ============================================================================

class LiveSensorCryptoTests {

    @Test
    fun testCRC16_OnDecryptedResponse_14400() {
        val decryptedHex = "23 01 40 38 42 80 42 40 41 80 3F 80 3E 80 3D 40 3E 80 43 80 46 80 48 40 49 80 49 80 49 80 48 40 46 80 44 80 43 80 42 40 41 80 40 80 3F 80 3D 40 3D 80 3C 80 3C 80 3D 40 3D 80 3E 80 3E 80 3E 40 3E 80 3E 80 3E 80 3E 40 3D 80 3D 80 3C 80 3C 40 3C 80 3D 80 3E 80 3F 40 3F 80 3E 80 3D 80 3C 40 3B 80 3B 80 3B 80 3B 40 3B 80 3B 80 3B 80 3C 40 3C 80 3C 80 3C 80 3C 40 3C 80 3C 80 3C 80 3B 40 3B 80 3B 80 3C 80 3C 40 3D 80 3D 80 3D 80 3D 00 3D 80 3E 80 41 80 45 40 47 80 46 80 44 80 45 40 45 80 45 80 44 80 43 40 43 80 41 80 3F 80 3E 40 3E 80 3E 80 3E 80 3E 40 3F 80 3F 80 3F 80 3F 40 3F 80 3F 80 3F 80 3F 40 3F 80 3F 80 3E 80 3E 40 3E 80 3D 80 3C 80 3B 40 3B 80 3B 80 3B 80 3B 40 3B 80 3B 80 3C 80 3C 40 3C 80 3C 80 3C 80 3D 40 3F 80 8B 7D"
        val data = AiDexParser.dataFromHex(decryptedHex)
        assertEquals(244, data.size)
        assertTrue(Crc16CcittFalse.validateResponse(data))
    }

    @Test
    fun testCRC16_OnDecryptedResponse_14519() {
        val decryptedHex = "23 01 B7 38 41 80 41 80 41 40 41 80 40 80 3E 80 3D 40 3D 80 3D 80 3E 80 3E 40 3F 80 3E 80 3F 80 3F 00 3F 80 40 80 40 80 41 00 41 80 41 80 40 80 3F 00 3F 80 3E 80 3D 80 3D 00 3C 80 3C 80 3D 80 3D 00 3C 80 3B 80 3B 80 3B 40 3D 80 3D 80 3F 80 3F 40 3E 80 3D 80 3C 80 3B 40 3B 80 3A 80 3A 80 3A 40 39 80 3A 80 3A 80 3A 40 3A 80 3A 80 3A 80 39 40 39 80 39 80 39 80 39 40 3A 80 3C 80 3E 80 43 40 45 80 45 80 44 80 43 40 42 80 41 80 41 80 41 40 43 80 44 80 44 80 44 40 44 80 44 80 43 80 43 40 46 80 4A 80 4C 80 4D 40 4C 80 50 80 56 80 5C 40 60 80 61 80 62 80 62 40 61 80 60 80 5E 80 5B 40 59 80 57 80 55 80 54 40 54 80 53 80 53 80 53 40 52 80 52 80 50 80 4E 40 4C 80 4B 80 4B 80 4B 40 4C 80 4B 80 4A 80 49 40 48 80 47 80 46 80 45 40 F6 CE"
        val data = AiDexParser.dataFromHex(decryptedHex)
        assertEquals(244, data.size)
        assertTrue(Crc16CcittFalse.validateResponse(data))
    }

    @Test
    fun testCRC16_OnDecryptedResponse_14638() {
        val decryptedHex = "23 01 2E 39 42 80 40 80 40 80 41 40 42 80 43 80 42 80 41 40 40 80 40 80 40 80 41 40 42 80 42 80 42 80 44 40 45 80 44 80 44 80 42 40 40 80 3E 80 3E 80 3E 40 3E 80 3F 80 3F 80 3F 40 3F 80 40 80 40 80 40 40 41 80 41 80 41 80 41 40 42 80 44 80 46 80 47 40 47 80 46 80 45 80 45 40 44 80 43 80 43 80 42 40 43 80 43 80 44 80 44 40 45 80 44 80 45 80 47 40 48 80 48 80 47 80 45 40 42 80 3F 80 3D 80 3C 40 3C 80 3D 80 3E 80 3F 40 40 80 41 80 43 80 45 40 48 80 4D 80 52 80 54 40 58 80 5A 80 59 80 57 40 55 80 56 80 5A 80 5E 40 60 80 60 80 5F 80 5F 40 5E 80 5D 80 57 80 4E 40 47 80 45 80 44 80 45 40 45 80 46 80 47 80 49 40 4B 80 4E 80 4F 80 51 40 51 80 52 80 50 80 4E 40 4B 80 48 80 44 80 42 40 3F 80 3E 80 3E 80 3E 40 3F 80 40 80 42 80 C0 95"
        val data = AiDexParser.dataFromHex(decryptedHex)
        assertEquals(244, data.size)
        assertTrue(Crc16CcittFalse.validateResponse(data))
    }

    @Test
    fun testHistoryParsing_FromDecryptedResponse_14400() {
        // Parse the payload portion (skip opcode+status at start, CRC at end)
        val decryptedHex = "23 01 40 38 42 80 42 40 41 80 3F 80 3E 80 3D 40 3E 80 43 80 46 80 48 40 49 80 49 80 49 80 48 40 46 80 44 80 43 80 42 40 41 80 40 80 3F 80 3D 40 3D 80 3C 80 3C 80 3D 40 3D 80 3E 80 3E 80 3E 40 3E 80 3E 80 3E 80 3E 40 3D 80 3D 80 3C 80 3C 40 3C 80 3D 80 3E 80 3F 40 3F 80 3E 80 3D 80 3C 40 3B 80 3B 80 3B 80 3B 40 3B 80 3B 80 3B 80 3C 40 3C 80 3C 80 3C 80 3C 40 3C 80 3C 80 3C 80 3B 40 3B 80 3B 80 3C 80 3C 40 3D 80 3D 80 3D 80 3D 00 3D 80 3E 80 41 80 45 40 47 80 46 80 44 80 45 40 45 80 45 80 44 80 43 40 43 80 41 80 3F 80 3E 40 3E 80 3E 80 3E 80 3E 40 3F 80 3F 80 3F 80 3F 40 3F 80 3F 80 3F 80 3F 40 3F 80 3F 80 3E 80 3E 40 3E 80 3D 80 3C 80 3B 40 3B 80 3B 80 3B 80 3B 40 3B 80 3B 80 3C 80 3C 40 3C 80 3C 80 3C 80 3D 40 3F 80 8B 7D"
        val fullData = AiDexParser.dataFromHex(decryptedHex)

        // Payload = bytes[2..<242] (skip opcode+status at start, CRC at end)
        val payload = fullData.copyOfRange(2, fullData.size - 2)
        val records = AiDexParser.parseHistoryResponse(payload)

        // 238 body bytes / 2 = 119 entries (minus 2 byte header = 240-2=238)
        // Actually: payload is 240 bytes (244-2-2=240), first 2 bytes header, body is 238 / 2 = 119
        assertEquals(119, records.size)

        // Verify against logged values
        assertEquals(66, records[0].glucoseMgDl) // 0x42
        assertEquals(14400, records[0].timeOffsetMinutes)

        assertEquals(66, records[1].glucoseMgDl) // 0x42
        assertEquals(65, records[2].glucoseMgDl) // 0x41
        assertEquals(63, records[3].glucoseMgDl) // 0x3F
        assertEquals(62, records[4].glucoseMgDl) // 0x3E

        // Last entry: 0x3F = 63
        assertEquals(63, records[118].glucoseMgDl)
        assertEquals(14518, records[118].timeOffsetMinutes)
    }
}

// ============================================================================
// MARK: - Key Exchange Tests
// ============================================================================

class KeyExchangeTests {

    @Test
    fun testKeyExchangeInit() {
        val ke = AiDexKeyExchange("2222267V4E")

        // Secret should match known vector
        val expectedSecret = byteArrayOf(
            0x4b, 0x76, 0x16, 0x95.toByte(), 0x76, 0xda.toByte(), 0x80.toByte(), 0xe4.toByte(),
            0xee.toByte(), 0xac.toByte(), 0xf8.toByte(), 0x86.toByte(), 0x23, 0x08, 0x73, 0xd2.toByte()
        )
        assertArrayEquals(expectedSecret, ke.snSecret)

        // IV should match known vector
        val expectedIv = byteArrayOf(
            0x14, 0xcb.toByte(), 0x6a, 0x3a, 0x39, 0xb9.toByte(), 0x6c, 0x44,
            0x8e.toByte(), 0xbc.toByte(), 0x39, 0x18, 0x5f, 0x70, 0xf8.toByte(), 0xaa.toByte()
        )
        assertArrayEquals(expectedIv, ke.snIv)

        // Initial state
        assertFalse(ke.isComplete)
        assertNull(ke.pairKey)
        assertNull(ke.sessionKey)
    }

    @Test
    fun testKeyExchangeChallenge() {
        val ke = AiDexKeyExchange("2222267V4E")
        val challenge = ke.getChallenge()
        assertArrayEquals(ke.snSecret, challenge)
    }

    @Test
    fun testKeyExchangePrefixStripping() {
        // AiDexKeyExchange must produce the same secret/IV regardless of prefix.
        // SuperGattCallback.SerialNumber includes the "X-" prefix (e.g., "X-2222267V4E").
        val bare = AiDexKeyExchange("2222267V4E")
        val withPrefix = AiDexKeyExchange("X-2222267V4E")
        val withFullPrefix = AiDexKeyExchange("AiDEX X-2222267V4E")

        assertArrayEquals(bare.snSecret, withPrefix.snSecret)
        assertArrayEquals(bare.snIv, withPrefix.snIv)
        assertArrayEquals(bare.snSecret, withFullPrefix.snSecret)
        assertArrayEquals(bare.snIv, withFullPrefix.snIv)
        assertEquals(bare.bareSerial, withPrefix.bareSerial)
        assertEquals("2222267V4E", withPrefix.bareSerial)
    }

    @Test
    fun testKeyExchangeReset() {
        val ke = AiDexKeyExchange("2222267V4E")
        ke.onPairKeyReceived(ByteArray(16) { 0x42 })
        assertNotNull(ke.pairKey)
        ke.reset()
        assertNull(ke.pairKey)
        assertNull(ke.sessionKey)
        assertFalse(ke.isComplete)
    }

    @Test
    fun testKeyExchangeEncryptDecryptFailsWithoutSessionKey() {
        val ke = AiDexKeyExchange("2222267V4E")
        assertNull(ke.encrypt(byteArrayOf(0x10, 0xC1.toByte(), 0xF3.toByte())))
        assertNull(ke.decrypt(byteArrayOf(0x10, 0xC1.toByte(), 0xF3.toByte())))
    }

    @Test
    fun testPostBondConfigFailsWithoutSessionKey() {
        val ke = AiDexKeyExchange("2222267V4E")
        assertNull(ke.getPostBondConfig())
    }
}

// ============================================================================
// MARK: - Command Builder Tests
// ============================================================================

class CommandBuilderTests {

    @Test
    fun testCommandBuilderFailsWithoutSessionKey() {
        val ke = AiDexKeyExchange("2222267V4E")
        val builder = AiDexCommandBuilder(ke)

        assertNull(builder.getStartupDeviceInfo())
        assertNull(builder.getBroadcastData())
        assertNull(builder.getHistoryRange())
        assertNull(builder.getHistoriesRaw(14400))
        assertNull(builder.getHistories(14400))
        assertNull(builder.deleteBond())
        assertNull(builder.reset())
    }

    @Test
    fun testGetDefaultParamUsesRequestedStartIndex() {
        val ke = AiDexKeyExchange("2222267V4E")
        val sessionKeyField = ke.javaClass.getDeclaredField("sessionKey")
        sessionKeyField.isAccessible = true
        sessionKeyField.set(ke, ByteArray(16) { (it + 1).toByte() })

        val builder = AiDexCommandBuilder(ke)
        val encrypted = builder.getDefaultParam(startIndex = 0x0A)
        assertNotNull(encrypted)

        val plaintext = ke.decrypt(encrypted!!)
        assertNotNull(plaintext)
        assertEquals(AiDexOpcodes.GET_DEFAULT_PARAM, plaintext!![0].toInt() and 0xFF)
        assertEquals(0x0A, plaintext[1].toInt() and 0xFF)
        assertTrue(Crc16CcittFalse.validateResponse(plaintext))
    }
}

class StartupMetadataParsingTests {

    @Test
    fun testParseStartupDeviceInfoPayload_vendorShape() {
        val payload = AiDexParser.dataFromHex("0000010701030F0047582D3031530000")

        val parsed = AiDexParser.parseStartupDeviceInfoPayload(payload)

        assertNotNull(parsed)
        assertEquals("1.7", parsed!!.firmwareVersion)
        assertEquals("1.3", parsed.hardwareVersion)
        assertEquals(15, parsed.wearDays)
        assertEquals("GX-01S", parsed.modelName)
    }

    @Test
    fun testParseStartupDeviceInfoFrame_stripsOpcodeOnly() {
        val frame = AiDexParser.dataFromHex("100000010701030F0047582D3031530000")

        val parsed = AiDexParser.parseStartupDeviceInfoFrame(frame)

        assertNotNull(parsed)
        assertEquals("1.7", parsed!!.firmwareVersion)
        assertEquals("1.3", parsed.hardwareVersion)
        assertEquals(15, parsed.wearDays)
        assertEquals("GX-01S", parsed.modelName)
    }

    @Test
    fun testParseStartupDeviceInfoPayload_lumiflex16DayShape() {
        val payload = AiDexParser.dataFromHex("000001080201100047582D3031530000")

        val parsed = AiDexParser.parseStartupDeviceInfoPayload(payload)

        assertNotNull(parsed)
        assertEquals("1.8", parsed!!.firmwareVersion)
        assertEquals("2.1", parsed.hardwareVersion)
        assertEquals(16, parsed.wearDays)
        assertEquals("GX-01S", parsed.modelName)
    }

    @Test
    fun testParseStartupDeviceInfoFrame_lumiflex16DayShape() {
        val frame = AiDexParser.dataFromHex("10000001080201100047582D3031530000")

        val parsed = AiDexParser.parseStartupDeviceInfoFrame(frame)

        assertNotNull(parsed)
        assertEquals("1.8", parsed!!.firmwareVersion)
        assertEquals("2.1", parsed.hardwareVersion)
        assertEquals(16, parsed.wearDays)
        assertEquals("GX-01S", parsed.modelName)
    }

    @Test
    fun testParseLocalStartTimePayload_acceptsPlausibleDate() {
        val payload = byteArrayOf(
            0xEA.toByte(), 0x07,
            0x02,
            0x1C,
            0x13,
            0x25,
            0x05,
            0x14,
            0x00,
        )

        val parsed = AiDexParser.parseLocalStartTimePayload(payload)

        assertNotNull(parsed)
        assertEquals(2026, parsed!!.year)
        assertEquals(2, parsed.month)
        assertEquals(28, parsed.day)
        assertEquals(19, parsed.hour)
        assertEquals(37, parsed.minute)
        assertEquals(5, parsed.second)
        assertEquals(20, parsed.tzQuarters)
        assertEquals(0, parsed.dstQuarters)
        assertFalse(parsed.isAllZeros)
    }

    @Test
    fun testParseLocalStartTimePayload_acceptsAllZeros() {
        val payload = ByteArray(9)

        val parsed = AiDexParser.parseLocalStartTimePayload(payload)

        assertNotNull(parsed)
        assertTrue(parsed!!.isAllZeros)
    }

    @Test
    fun testParseLocalStartTimePayload_rejectsLegacyMetadataHead() {
        val payload = AiDexParser.dataFromHex("0000010701030F0047582D3031530000")

        val parsed = AiDexParser.parseLocalStartTimePayload(payload)

        assertNull(parsed)
    }
}

class ActivationTimeZoneTests {

    @Test
    fun testActivationTimeZoneSeparatesRawOffsetAndDst() {
        val tz = TimeZone.getTimeZone("Europe/Berlin")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.JULY, 1, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val encoded = aiDexActivationTimeZone(cal, tz)
        val quarterHourMs = 15 * 60 * 1000

        assertEquals(tz.rawOffset / quarterHourMs, encoded.tzQuarters)
        assertEquals(tz.dstSavings / quarterHourMs, encoded.dstQuarters)
        assertEquals(tz.getOffset(cal.timeInMillis) / quarterHourMs, encoded.tzQuarters + encoded.dstQuarters)
    }
}

// ============================================================================
// MARK: - Default Param (0x31) Parsing Tests
// ============================================================================

class DefaultParamParsingTests {

    @Test
    fun testParseDefaultParamChunk() {
        val payload = byteArrayOf(
            0x01,
            0x04,
            0x01,
            0x11,
            0x22,
            0x33,
            0x44,
        )

        val chunk = AiDexParser.parseDefaultParamChunk(payload)
        assertNotNull(chunk)
        assertEquals(0x01, chunk!!.leadByte)
        assertEquals(4, chunk.totalWords)
        assertEquals(1, chunk.startIndex)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), chunk.rawChunk)
        assertEquals(3, chunk.nextStartIndex)
        assertFalse(chunk.isComplete)
    }

    @Test
    fun testAssembleDefaultParamChunks() {
        val first = AiDexParser.parseDefaultParamChunk(
            byteArrayOf(
                0x01,
                0x04,
                0x01,
                0x01,
                0x06,
                0x00,
                0x03,
            )
        )!!
        val second = AiDexParser.parseDefaultParamChunk(
            byteArrayOf(
                0x01,
                0x04,
                0x03,
                0x80.toByte(),
                0xC6.toByte(),
                0x13,
                0x00,
            )
        )!!

        val buffer1 = AiDexParser.appendDefaultParamChunk(null, first)
        assertEquals("010106000300000000", AiDexParser.defaultParamRawHex(buffer1, first.totalWords))

        val buffer2 = AiDexParser.appendDefaultParamChunk(buffer1, second)
        assertEquals("010106000380C61300", AiDexParser.defaultParamRawHex(buffer2, second.totalWords))
        assertTrue(second.isComplete)
    }
}

// ============================================================================
// MARK: - Default Param Catalog Compare Tests
// ============================================================================

class DefaultParamCatalogCompareTests {

    @Test
    fun testNormalizeCatalogModelName() {
        assertEquals("1034_GX01S", AiDexDefaultParamProvisioning.normalizeCatalogModelName("GX-01S"))
        assertEquals("1034_GX02S", AiDexDefaultParamProvisioning.normalizeCatalogModelName("gx02s"))
        assertEquals("1034_GX03S", AiDexDefaultParamProvisioning.normalizeCatalogModelName("1034_GX03S"))
        assertEquals("1034_GXXXS_7", AiDexDefaultParamProvisioning.normalizeCatalogModelName("1034_GXXXS_7"))
        assertEquals("1034_GXXXS_14", AiDexDefaultParamProvisioning.normalizeCatalogModelName("GXXXS14"))
        assertEquals("1034_GXXXS_16", AiDexDefaultParamProvisioning.normalizeCatalogModelName("1034GXXXS16"))
        assertNull(AiDexDefaultParamProvisioning.normalizeCatalogModelName("mystery"))
    }

    @Test
    fun testWorking171UsesOfficialTrimmedFirmwareKey() {
        val currentRawHex = "010105000080C613008303BFFE68006700650068006B00100E302AD06BB0FFC4FFECFF00000000100E302AD06B0A0000000000C4092800FA00740E2003EE020A000A000800FA0019007D000802AA009CFF640000001100E803B80B32005500D501280046001E0032006400020014002003B004050014001E005A005A00F401F4019033B04F000000000000000000000000000000000000000000000000000000000000000000000000"

        val comparisons = AiDexDefaultParamProvisioning.compareKnownCatalog(currentRawHex, "GX-01S", "1.7.1.3")
        assertFalse(comparisons.isEmpty())

        val best = comparisons.first()
        assertEquals("1034_GX01S", best.entry.settingType)
        assertEquals("1.7.1", best.entry.version)
        assertEquals("parameters_x_1.5.0.0.ini", best.entry.settingVersion)
        assertFalse(best.current.headerSwapApplied)
        assertEquals(168, best.current.byteCount)
        assertEquals("01050000", best.current.versionHex)
        assertFalse(best.exactMatch)
    }

    @Test
    fun testWorking181MatchesCapturedOfficial1610Candidate() {
        val currentRawHex = "010106010080C61300D103D1FE6E006D006B006E007100100E302AD06BB0FFC4FFECFF00000000100E302AD06B0A0000000000C4092800FA00740E2003EE020A000A000800FA0019007D000802AA009CFF640000001100E803B80B32005500D601280046001E0032006400020014002003B004050014005A003200EE021E005A005A00F401F4019033B04F000000000000000000000000000000000000000000000000000000000000"

        val comparisons = AiDexDefaultParamProvisioning.compareKnownCatalog(currentRawHex, "GX-01S", "1.8.1")
        assertFalse(comparisons.isEmpty())

        val best = comparisons.first()
        assertEquals("1.8.1", best.entry.version)
        assertEquals("parameters_x_1.6.1.0.ini", best.entry.settingVersion)
        assertEquals(168, best.current.byteCount)
        assertFalse(best.current.headerSwapApplied)
        assertEquals("01060100", best.current.versionHex)
        assertTrue(best.exactMatch)
        assertEquals(0, best.diffByteCount)
    }

    @Test
    fun testLongDpShapeUsesElsePreserveRangeLikeOfficialOtaManager() {
        val currentRawHex = "010105000080C613008303BFFE68006700650068006B00100E302AD06BB0FFC4FFECFF00000000100E302AD06B0A0000000000C4092800FA00740E2003EE020A000A000800FA0019007D000802AA009CFF640000001100E803B80B32005500D501280046001E0032006400020014002003B004050014001E005A005A00F401F4019033B04F000000000000000000000000000000000000000000000000000000000000000000000000"

        val comparisons = AiDexDefaultParamProvisioning.compareKnownCatalog(currentRawHex, "GX-01S", "1.7.1")
        val candidate120 = comparisons.first { it.entry.version == "1.2.0" }

        assertEquals(336, candidate120.current.hex.length)
        assertEquals(
            candidate120.current.hex.substring(16, 24),
            candidate120.candidate.candidateHex.substring(16, 24)
        )
    }

    @Test
    fun testImportedCatalogOverlaysSnapshotEntry() {
        val storage = AiDexDpCatalogProvider.InMemoryStorage()
        AiDexDpCatalogProvider.setStorageForTests(storage)
        try {
            val importJson = """
                {
                  "otaFileId": 25,
                  "fileType": 1,
                  "settingType": "1034_GX01S",
                  "version": "1.5.1",
                  "aidexVersion": "X",
                  "settingVersion": "parameters_x_contact.ini",
                  "settingContent": "0102020080C61300840338FF65006400640064006400100EF03CD06B00000000000000000000100EF03CD06B0A0000000000DC052800FA00740E20032E002800F4016E0078000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000BD"
                }
            """.trimIndent()

            val result = AiDexDpCatalogProvider.importCatalogJson(importJson)
            assertEquals(1, result.importedCount)
            assertEquals(1, result.replacedKeys)
            assertEquals(1, result.catalogState.importedEntryCount)

            val comparisons = AiDexDefaultParamProvisioning.compareKnownCatalog(
                currentRawHex = "010102020080C61300840338FF65006400640064006400100EF03CD06B00000000000000000000100EF03CD06B0A0000000000DC052800FA00740E20032E002800F4016E0078000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000BD",
                modelName = "GX-01S",
                firmwareVersion = "1.5.1",
            )

            val best = comparisons.first()
            assertEquals(
                AiDexDefaultParamProvisioning.CatalogSource.IMPORTED,
                best.entry.source
            )
            assertEquals("parameters_x_contact.ini", best.entry.settingVersion)


        } finally {
            AiDexDpCatalogProvider.setStorageForTests(null)
        }
    }

    @Test
    fun testPlanGuardedApplySplitsChunksUsingRequestedPayloadLimit() {
        val currentRawHex = "010105000080C613008303BFFE68006700650068006B00100E302AD06BB0FFC4FFECFF00000000100E302AD06B0A0000000000C4092800FA00740E2003EE020A000A000800FA0019007D000802AA009CFF640000001100E803B80B32005500D501280046001E0032006400020014002003B004050014001E005A005A00F401F4019033B04F000000000000000000000000000000000000000000000000000000000000000000000000"

        val plan = AiDexDefaultParamProvisioning.planGuardedApply(
            currentRawHex = currentRawHex,
            modelName = "GX-01S",
            firmwareVersion = "1.8.1",
            maxChunkPayloadBytes = 32,
        )

        assertNotNull(plan)
        assertEquals(6, plan!!.chunks.size)
        assertEquals(84, plan.totalWords)
        assertEquals(1, plan.chunks[0].startIndex)
        assertEquals(17, plan.chunks[1].startIndex)
        assertEquals(81, plan.chunks.last().startIndex)
        assertEquals(32, plan.chunks.first().payloadByteCount)
        assertEquals(8, plan.chunks.last().payloadByteCount)
        assertFalse(plan.comparison.exactMatch)
    }
}

// ============================================================================
// MARK: - Brief History (0x24) Parsing Tests
// ============================================================================

class BriefHistoryParsingTests {

    @Test
    fun testParseBriefHistory_Synthetic() {
        // 2 bytes header + 1 x 5-byte entry
        // i1_raw = 847 (8.47 -> rawValue=84.7, sensorGlucose=8.47*18.0182=152.6)
        // i2_raw = 2950 (29.50)
        // vc_raw = 50 (0.50)
        val payload = byteArrayOf(
            0x00, 0x00, // startOffset = 0
            0x4F, 0x03, // i1 = 0x034F = 847
            0x86.toByte(), 0x0B, // i2 = 0x0B86 = 2950
            0x32,       // vc = 50
        )
        val records = AiDexParser.parseBriefHistoryResponse(payload)
        assertEquals(1, records.size)

        val r = records[0]
        assertEquals(0, r.timeOffsetMinutes)
        assertEquals(8.47f, r.i1, 0.01f)
        assertEquals(29.50f, r.i2, 0.01f)
        assertEquals(0.50f, r.vc, 0.01f)
        assertEquals(84.7f, r.rawValue, 0.1f)
        assertEquals(8.47f * 18.0182f, r.sensorGlucose, 0.1f)
    }

    @Test
    fun testParseBriefHistory_InvalidEntry() {
        // All-zero entry should be skipped
        val payload = byteArrayOf(
            0x00, 0x00, // startOffset = 0
            0x00, 0x00, // i1 = 0
            0x00, 0x00, // i2 = 0
            0x00,       // vc = 0
        )
        val records = AiDexParser.parseBriefHistoryResponse(payload)
        assertEquals(0, records.size) // invalid entry filtered out
    }

    @Test
    fun testParseBriefHistory_TooShort() {
        assertEquals(0, AiDexParser.parseBriefHistoryResponse(byteArrayOf()).size)
        assertEquals(0, AiDexParser.parseBriefHistoryResponse(byteArrayOf(0x00)).size)
        assertEquals(0, AiDexParser.parseBriefHistoryResponse(ByteArray(6)).size) // 2+4 < 2+5
    }
}

// ============================================================================
// MARK: - History Cache (0x23) Tests
// ============================================================================

class CacheCalibratedEntriesTests {

    private fun entry(offset: Int, glucose: Int, sentinel: Boolean = false) =
        CalibratedHistoryEntry(offset, glucose, statusBit = false, isSentinel = sentinel)

    @Test
    fun testBasicCaching() {
        val cache = mutableMapOf<Int, Int>()
        val entries = listOf(entry(100, 80), entry(101, 85), entry(102, 90))
        val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(entries, cache)
        assertEquals(3, cached)
        assertEquals(0, skipped)
        assertEquals(80, cache[100])
        assertEquals(85, cache[101])
        assertEquals(90, cache[102])
    }

    @Test
    fun testSkipsSentinels() {
        val cache = mutableMapOf<Int, Int>()
        val entries = listOf(entry(100, 80), entry(101, 1023, sentinel = true), entry(102, 90))
        val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(entries, cache)
        assertEquals(2, cached)
        assertEquals(1, skipped)
        assertNull(cache[101])
    }

    @Test
    fun testSkipsControlValue_FullPage() {
        // Simulate a full 120-entry page where the last entry is a control value
        val cache = mutableMapOf<Int, Int>()
        val entries = (0 until 120).map { i ->
            if (i < 119) entry(1000 + i, 80) else entry(1000 + i, 366)  // spike at end
        }
        val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(entries, cache)
        assertEquals(119, cached)
        assertEquals(1, skipped)
        assertNull(cache[1119])  // control value skipped
    }

    @Test
    fun testKeepsLastEntry_SmallDeviation() {
        // Full 120-entry page, last entry is close to neighbors — keep it
        val cache = mutableMapOf<Int, Int>()
        val entries = (0 until 120).map { i ->
            entry(1000 + i, 80 + (i % 5))  // small variation
        }
        val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(entries, cache)
        assertEquals(120, cached)
        assertEquals(0, skipped)
    }

    @Test
    fun testKeepsLastEntry_PartialPage() {
        // Partial page (<120 entries) — last entry is NOT treated as control value
        val cache = mutableMapOf<Int, Int>()
        val entries = (0 until 50).map { i ->
            if (i < 49) entry(1000 + i, 80) else entry(1000 + i, 366)  // spike at end
        }
        val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(entries, cache)
        assertEquals(50, cached)  // all kept — partial page
        assertEquals(0, skipped)
    }

    @Test
    fun testEmptyEntries() {
        val cache = mutableMapOf<Int, Int>()
        val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(emptyList(), cache)
        assertEquals(0, cached)
        assertEquals(0, skipped)
        assertTrue(cache.isEmpty())
    }

    @Test
    fun testMultipleSentinels() {
        val cache = mutableMapOf<Int, Int>()
        val entries = listOf(
            entry(100, 1023, sentinel = true),
            entry(101, 1023, sentinel = true),
            entry(102, 80),
        )
        val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(entries, cache)
        assertEquals(1, cached)
        assertEquals(2, skipped)
    }

    @Test
    fun testControlValueDeviation_ExactThreshold() {
        // Deviation of exactly 20 should NOT be skipped (> 20 required)
        val cache = mutableMapOf<Int, Int>()
        val entries = (0 until 120).map { i ->
            if (i < 119) entry(1000 + i, 80) else entry(1000 + i, 100)  // deviation = 20
        }
        val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(entries, cache)
        assertEquals(120, cached)
        assertEquals(0, skipped)
    }

    @Test
    fun testControlValueDeviation_JustAboveThreshold() {
        // Deviation of 21 should be skipped
        val cache = mutableMapOf<Int, Int>()
        val entries = (0 until 120).map { i ->
            if (i < 119) entry(1000 + i, 80) else entry(1000 + i, 101)  // deviation = 21
        }
        val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(entries, cache)
        assertEquals(119, cached)
        assertEquals(1, skipped)
    }

    @Test
    fun testSkipsObservedAiDexPageTailBump() {
        val cache = mutableMapOf<Int, Int>()
        val entries = (0 until 120).map { i ->
            when (i) {
                118 -> entry(6989 + i, 70)
                119 -> entry(6989 + i, 97)
                else -> entry(6989 + i, 70)
            }
        }

        val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(entries, cache)

        assertEquals(119, cached)
        assertEquals(1, skipped)
        assertEquals(70, cache[7107])
        assertNull(cache[7108])
    }
}

// ============================================================================
// MARK: - History Merge (0x23 + 0x24) Tests
// ============================================================================

class HistoryMergeEntryTests {

    private fun adcEntry(offset: Int, i1: Float = 5.0f, i2: Float = 10.0f, vc: Float = 0.5f) =
        AdcHistoryEntry(
            timeOffsetMinutes = offset,
            i1 = i1,
            i2 = i2,
            vc = vc,
            rawValue = i1 * 10f,
            sensorGlucose = i1 * 18.0182f,
        )

    @Test
    fun testExactMatch() {
        val cache = mutableMapOf(100 to 80, 101 to 85, 102 to 90)
        val adcEntries = listOf(adcEntry(100), adcEntry(101), adcEntry(102))
        val result = HistoryMerge.mergeHistoryEntries(adcEntries, cache, null)

        assertEquals(3, result.mergedCount)
        assertEquals(0, result.fallbackCount)
        assertEquals(0, result.noGlucoseCount)
        assertEquals(3, result.entries.size)
        assertEquals(80f, result.entries[0].glucoseMgDl)
        assertEquals(85f, result.entries[1].glucoseMgDl)
        assertEquals(90f, result.entries[2].glucoseMgDl)
        // Cache should be emptied
        assertTrue(cache.isEmpty())
    }

    @Test
    fun testFallbackWhenNoMatch() {
        // 0x24 has entries at offsets 100-104, but 0x23 only has 100-101
        val cache = mutableMapOf(100 to 80, 101 to 85)
        val adcEntries = (100..104).map { adcEntry(it) }
        val result = HistoryMerge.mergeHistoryEntries(adcEntries, cache, null)

        assertEquals(2, result.mergedCount)      // 100 and 101
        assertEquals(3, result.fallbackCount)     // 102, 103, 104 use fallback
        assertEquals(0, result.noGlucoseCount)
        // Fallback uses last matched value (85 from offset 101)
        assertEquals(85f, result.entries[2].glucoseMgDl)
        assertEquals(85f, result.entries[3].glucoseMgDl)
        assertEquals(85f, result.entries[4].glucoseMgDl)
        assertEquals(85, result.lastKnownGlucose)
    }

    @Test
    fun testNoGlucose_NoFallbackAvailable() {
        // 0x24 entries with no 0x23 match and no initial fallback
        val cache = mutableMapOf<Int, Int>()
        val adcEntries = listOf(adcEntry(100), adcEntry(101))
        val result = HistoryMerge.mergeHistoryEntries(adcEntries, cache, null)

        assertEquals(0, result.mergedCount)
        assertEquals(0, result.fallbackCount)
        assertEquals(2, result.noGlucoseCount)
        // Glucose should be 0 (will be filtered by filterForStorage)
        assertEquals(0f, result.entries[0].glucoseMgDl)
        assertEquals(0f, result.entries[1].glucoseMgDl)
        assertNull(result.lastKnownGlucose)
    }

    @Test
    fun testInitialFallbackFromPreviousPage() {
        // No 0x23 cache, but initial fallback from previous page
        val cache = mutableMapOf<Int, Int>()
        val adcEntries = listOf(adcEntry(200), adcEntry(201))
        val result = HistoryMerge.mergeHistoryEntries(adcEntries, cache, initialFallback = 75)

        assertEquals(0, result.mergedCount)
        assertEquals(2, result.fallbackCount)
        assertEquals(0, result.noGlucoseCount)
        assertEquals(75f, result.entries[0].glucoseMgDl)
        assertEquals(75f, result.entries[1].glucoseMgDl)
        assertEquals(75, result.lastKnownGlucose)
    }

    @Test
    fun testMixedMatchAndFallback() {
        // First entries have no match (use initial fallback), middle has match, rest use updated fallback
        val cache = mutableMapOf(102 to 90)
        val adcEntries = (100..104).map { adcEntry(it) }
        val result = HistoryMerge.mergeHistoryEntries(adcEntries, cache, initialFallback = 70)

        assertEquals(1, result.mergedCount)       // offset 102
        assertEquals(4, result.fallbackCount)     // 100, 101, 103, 104
        assertEquals(0, result.noGlucoseCount)
        assertEquals(70f, result.entries[0].glucoseMgDl)   // initial fallback
        assertEquals(70f, result.entries[1].glucoseMgDl)   // initial fallback
        assertEquals(90f, result.entries[2].glucoseMgDl)   // exact match
        assertEquals(90f, result.entries[3].glucoseMgDl)   // updated fallback
        assertEquals(90f, result.entries[4].glucoseMgDl)   // updated fallback
        assertEquals(90, result.lastKnownGlucose)
    }

    @Test
    fun testFallbackUpdatesPerMatch() {
        // Multiple matches update the fallback progressively
        val cache = mutableMapOf(100 to 80, 103 to 95)
        val adcEntries = (100..105).map { adcEntry(it) }
        val result = HistoryMerge.mergeHistoryEntries(adcEntries, cache, null)

        // 100 matched (80), 101 fallback (80), 102 fallback (80),
        // 103 matched (95), 104 fallback (95), 105 fallback (95)
        assertEquals(2, result.mergedCount)
        assertEquals(4, result.fallbackCount)
        assertEquals(0, result.noGlucoseCount)
        assertEquals(80f, result.entries[0].glucoseMgDl)   // match
        assertEquals(80f, result.entries[1].glucoseMgDl)   // fallback from 100
        assertEquals(80f, result.entries[2].glucoseMgDl)   // fallback from 100
        assertEquals(95f, result.entries[3].glucoseMgDl)   // match
        assertEquals(95f, result.entries[4].glucoseMgDl)   // fallback from 103
        assertEquals(95f, result.entries[5].glucoseMgDl)   // fallback from 103
        assertEquals(95, result.lastKnownGlucose)
    }

    @Test
    fun testRawValuePassedThrough() {
        val cache = mutableMapOf(100 to 80)
        val adcEntries = listOf(adcEntry(100, i1 = 8.47f))
        val result = HistoryMerge.mergeHistoryEntries(adcEntries, cache, null)

        assertEquals(84.7f, result.entries[0].rawMgDl, 0.01f)
    }

    @Test
    fun testImplausibleRawValueDropped() {
        val cache = mutableMapOf(100 to 80)
        val adcEntries = listOf(adcEntry(100, i1 = 135f)) // raw=1350 mg/dL (~75 mmol/L)
        val result = HistoryMerge.mergeHistoryEntries(adcEntries, cache, null)

        assertEquals(0f, result.entries[0].rawMgDl, 0.01f)
        assertEquals(80f, result.entries[0].glucoseMgDl, 0.01f)
    }

    @Test
    fun testInvalidAdcEntry() {
        // All-zero ADC values produce isValid=false
        val cache = mutableMapOf(100 to 80)
        val adcEntries = listOf(adcEntry(100, i1 = 0f, i2 = 0f, vc = 0f))
        val result = HistoryMerge.mergeHistoryEntries(adcEntries, cache, null)

        assertFalse(result.entries[0].isValid)
    }

    @Test
    fun testEmptyAdcEntries() {
        val cache = mutableMapOf(100 to 80)
        val result = HistoryMerge.mergeHistoryEntries(emptyList(), cache, null)

        assertEquals(0, result.entries.size)
        assertEquals(0, result.mergedCount)
        assertNull(result.lastKnownGlucose)
        // Cache should not be modified
        assertEquals(1, cache.size)
    }

    @Test
    fun testCacheNotModifiedForUnmatchedOffsets() {
        // 0x23 cache has offsets 200-210, 0x24 has offsets 100-105 — no overlap
        val cache = mutableMapOf(200 to 80, 201 to 85, 202 to 90)
        val adcEntries = (100..102).map { adcEntry(it) }
        val result = HistoryMerge.mergeHistoryEntries(adcEntries, cache, null)

        assertEquals(0, result.mergedCount)
        assertEquals(3, result.noGlucoseCount)
        // Unmatched cache entries should remain
        assertEquals(3, cache.size)
        assertEquals(80, cache[200])
    }
}

// ============================================================================
// MARK: - History Filter Tests
// ============================================================================

class HistoryFilterTests {

    private val sensorStart = 1_700_000_000_000L  // arbitrary sensor start time
    private val now = sensorStart + (15L * 24 * 60 * 60_000L)  // 15 days after start

    private fun storeEntry(
        offset: Int = 1000,
        glucose: Float = 100f,
        raw: Float = 50f,
        valid: Boolean = true,
    ) = HistoryStoreEntry(offset, glucose, raw, valid)

    @Test
    fun testValidEntryPasses() {
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry()), sensorStart, now
        )
        assertEquals(1, result.passed.size)
        assertEquals(0, result.filteredCount)
    }

    @Test
    fun testFilterInvalid() {
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(valid = false)), sensorStart, now
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterOffsetZero() {
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = 0)), sensorStart, now
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterNegativeOffset() {
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = -1)), sensorStart, now
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterOffsetTooLarge() {
        // MAX_OFFSET_DAYS = 30, so max offset = 30 * 24 * 60 = 43200 minutes
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = 43201)), sensorStart, now
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterOffsetAtMax() {
        // Exactly at max should pass (43200 minutes = 30 days)
        val maxOffset = (30 * 24 * 60)
        val laterNow = sensorStart + (31L * 24 * 60 * 60_000L) // 31 days after start
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = maxOffset)), sensorStart, laterNow
        )
        assertEquals(1, result.passed.size)
    }

    @Test
    fun testFilterBeyondNewestOffset() {
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = 1001)), sensorStart, now,
            historyNewestOffset = 1000,
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterAtNewestOffset() {
        // At newest offset exactly should pass
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = 1000)), sensorStart, now,
            historyNewestOffset = 1000,
        )
        assertEquals(1, result.passed.size)
    }

    @Test
    fun testFilterAtLiveOffsetCutoff() {
        // At liveOffsetCutoff should be filtered (>= check)
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = 1000)), sensorStart, now,
            liveOffsetCutoff = 1000,
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterBelowLiveOffsetCutoff() {
        // Below liveOffsetCutoff should pass
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = 999)), sensorStart, now,
            liveOffsetCutoff = 1000,
        )
        assertEquals(1, result.passed.size)
    }

    @Test
    fun testFilterGlucoseZero() {
        // glucose=0 is below MIN_VALID (20), so should be filtered
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(glucose = 0f)), sensorStart, now
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterGlucoseBelowMin() {
        // glucose=19 is below MIN_VALID (20)
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(glucose = 19f)), sensorStart, now
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterGlucoseAtMin() {
        // glucose=20 is at MIN_VALID, should pass
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(glucose = 20f)), sensorStart, now
        )
        assertEquals(1, result.passed.size)
    }

    @Test
    fun testFilterGlucoseAboveMax() {
        // glucose=501 is above MAX_VALID (500)
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(glucose = 501f)), sensorStart, now
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterGlucoseAtMax() {
        // glucose=500 is at MAX_VALID, should pass
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(glucose = 500f)), sensorStart, now
        )
        assertEquals(1, result.passed.size)
    }

    @Test
    fun testFilterAdcSaturation() {
        // glucose=1023 (ADC sentinel)
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(glucose = 1023f)), sensorStart, now
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterAdcSaturationHigh() {
        // glucose > 1023 also filtered
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(glucose = 2000f)), sensorStart, now
        )
        assertEquals(0, result.passed.size)
    }

    @Test
    fun testFilterWarmup() {
        val warmupMinutes = (HistoryMerge.WARMUP_DURATION_MS / 60_000L).toInt()
        // Readings within the configured warmup window should be filtered.
        // offset=warmupMinutes-1 -> timestamp is still inside WARMUP_DURATION_MS.
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = warmupMinutes - 1)), sensorStart, now
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterWarmup_AtBoundary() {
        val warmupMinutes = (HistoryMerge.WARMUP_DURATION_MS / 60_000L).toInt()
        // offset=warmupMinutes lands exactly on the warmup boundary and should pass.
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = warmupMinutes)), sensorStart, now
        )
        assertEquals(1, result.passed.size)
    }

    @Test
    fun testFilterFutureTimestamp() {
        // Entry with timestamp > now + 2 minutes
        // offset so large that sensorStart + offset*60000 > now + 120000
        val farFutureOffset = ((now - sensorStart) / 60_000L).toInt() + 3  // 3 minutes beyond "now"
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = farFutureOffset)), sensorStart, now
        )
        assertEquals(0, result.passed.size)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun testFilterFutureTimestamp_Within2MinTolerance() {
        // Entry 1 minute in the future — within 2-minute tolerance
        val nearFutureOffset = ((now - sensorStart) / 60_000L).toInt() + 1
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = nearFutureOffset)), sensorStart, now
        )
        assertEquals(1, result.passed.size)
    }

    @Test
    fun testMultipleFilters() {
        val warmupMinutes = (HistoryMerge.WARMUP_DURATION_MS / 60_000L).toInt()
        val entries = listOf(
            storeEntry(offset = 1000, glucose = 100f),   // valid
            storeEntry(offset = 0, glucose = 100f),       // offset zero
            storeEntry(offset = 1001, glucose = 0f),      // glucose zero
            storeEntry(offset = 1002, glucose = 100f, valid = false),  // invalid
            storeEntry(offset = 1003, glucose = 1023f),   // ADC saturation
            storeEntry(offset = warmupMinutes - 1, glucose = 100f),     // warmup
            storeEntry(offset = 1004, glucose = 80f),     // valid
        )
        val result = HistoryMerge.filterForStorage(entries, sensorStart, now)
        assertEquals(2, result.passed.size)
        assertEquals(5, result.filteredCount)
        assertEquals(1000, result.passed[0].offsetMinutes)
        assertEquals(1004, result.passed[1].offsetMinutes)
    }

    @Test
    fun testNewestOffsetZero_NoLimit() {
        // historyNewestOffset=0 means no limit applied
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = 20000)), sensorStart, now,
            historyNewestOffset = 0,
        )
        assertEquals(1, result.passed.size)
    }

    @Test
    fun testLiveOffsetCutoffZero_NoLimit() {
        // liveOffsetCutoff=0 means no limit applied
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = 20000)), sensorStart, now,
            liveOffsetCutoff = 0,
        )
        assertEquals(1, result.passed.size)
    }

    @Test
    fun testEmptyInput() {
        val result = HistoryMerge.filterForStorage(emptyList(), sensorStart, now)
        assertEquals(0, result.passed.size)
        assertEquals(0, result.filteredCount)
    }

    @Test
    fun testRawValuePreserved() {
        val result = HistoryMerge.filterForStorage(
            listOf(storeEntry(offset = 1000, glucose = 100f, raw = 84.7f)),
            sensorStart, now
        )
        assertEquals(84.7f, result.passed[0].rawMgDl, 0.01f)
    }

    @Test
    fun testNormalizeRawValueRejectsImplausibleSpike() {
        val rawMgDl = 75f * HistoryMerge.MGDL_PER_MMOL
        assertNull(HistoryMerge.normalizeRawMgDl(rawMgDl))
    }

    @Test
    fun testNormalizeRawValueKeepsPlausibleReading() {
        val rawMgDl = 12.5f * HistoryMerge.MGDL_PER_MMOL
        assertEquals(rawMgDl, HistoryMerge.normalizeRawMgDl(rawMgDl)!!, 0.01f)
    }
}

// ============================================================================
// MARK: - Integration: Full History Pipeline Tests
// ============================================================================

class HistoryPipelineTests {

    /**
     * Simulates a realistic history download scenario:
     * 0x23 pages arrive, get cached, then 0x24 pages arrive and merge.
     */
    @Test
    fun testFullPipeline_TwoPages() {
        val cache = mutableMapOf<Int, Int>()

        // Page 1 of 0x23: offsets 100-104 with glucose values
        val page1_0x23 = (100..104).map {
            CalibratedHistoryEntry(it, 60 + it - 100, statusBit = false, isSentinel = false)
        }
        HistoryMerge.cacheCalibratedEntries(page1_0x23, cache)
        assertEquals(5, cache.size)

        // Page 1 of 0x24: same offsets — should merge exactly
        val page1_0x24 = (100..104).map {
            AdcHistoryEntry(it, 5.0f, 10.0f, 0.5f, 50f, 90.1f)
        }
        val result1 = HistoryMerge.mergeHistoryEntries(page1_0x24, cache, null)
        assertEquals(5, result1.mergedCount)
        assertEquals(0, result1.fallbackCount)
        assertEquals(0, result1.noGlucoseCount)
        assertTrue(cache.isEmpty())

        // Verify glucose values match the 0x23 cached values
        assertEquals(60f, result1.entries[0].glucoseMgDl)
        assertEquals(64f, result1.entries[4].glucoseMgDl)
    }

    @Test
    fun testFullPipeline_MismatchedCounts() {
        // Realistic scenario: 0x23 has 119 entries (full page minus control), 0x24 has 47 entries
        val cache = mutableMapOf<Int, Int>()

        // 0x23: 120 entries, last is control value (glucose=366, prev=85)
        val entries_0x23 = (0 until 120).map { i ->
            CalibratedHistoryEntry(
                1000 + i,
                if (i < 119) 80 + (i % 10) else 366,
                statusBit = false, isSentinel = false
            )
        }
        val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(entries_0x23, cache)
        assertEquals(119, cached)  // control value skipped
        assertEquals(1, skipped)

        // 0x24: 47 entries starting at 1000 — all should find matches
        val entries_0x24 = (0 until 47).map { i ->
            AdcHistoryEntry(1000 + i, 5.0f, 10.0f, 0.5f, 50f, 90.1f)
        }
        val result = HistoryMerge.mergeHistoryEntries(entries_0x24, cache, null)
        assertEquals(47, result.mergedCount)
        assertEquals(0, result.fallbackCount)
        // Remaining cache should have 119 - 47 = 72 entries
        assertEquals(72, cache.size)
    }

    @Test
    fun testFullPipeline_CrossPageFallback() {
        val cache = mutableMapOf<Int, Int>()

        // Page 1: 0x23 offsets 100-104, 0x24 offsets 100-104
        val page1_0x23 = (100..104).map {
            CalibratedHistoryEntry(it, 80, statusBit = false, isSentinel = false)
        }
        HistoryMerge.cacheCalibratedEntries(page1_0x23, cache)
        val page1_0x24 = (100..104).map {
            AdcHistoryEntry(it, 5.0f, 10.0f, 0.5f, 50f, 90f)
        }
        val result1 = HistoryMerge.mergeHistoryEntries(page1_0x24, cache, null)
        assertEquals(80, result1.lastKnownGlucose)

        // Page 2: 0x24 has entries 200-202 with NO 0x23 cache
        // The fallback from page 1 should carry over
        val page2_0x24 = (200..202).map {
            AdcHistoryEntry(it, 5.0f, 10.0f, 0.5f, 50f, 90f)
        }
        val result2 = HistoryMerge.mergeHistoryEntries(page2_0x24, cache, result1.lastKnownGlucose)
        assertEquals(0, result2.mergedCount)
        assertEquals(3, result2.fallbackCount)
        assertEquals(80f, result2.entries[0].glucoseMgDl)  // fallback from page 1
    }

    @Test
    fun testFullPipeline_GlucoseZeroFiltered() {
        // Scenario that caused the original bug:
        // 0x24 entries with no 0x23 match and no fallback → glucose=0
        // filterForStorage should catch these
        val cache = mutableMapOf<Int, Int>()
        val adcEntries = (100..104).map {
            AdcHistoryEntry(it, 5.0f, 10.0f, 0.5f, 50f, 90f)
        }
        val mergeResult = HistoryMerge.mergeHistoryEntries(adcEntries, cache, null)
        assertEquals(5, mergeResult.noGlucoseCount)

        // All should be filtered out — glucose=0 is below MIN_VALID (20)
        val sensorStart = 1_700_000_000_000L
        val now = sensorStart + (15L * 24 * 60 * 60_000L)
        val filterResult = HistoryMerge.filterForStorage(mergeResult.entries, sensorStart, now)
        assertEquals(0, filterResult.passed.size)
        assertEquals(5, filterResult.filteredCount)
    }

    @Test
    fun testLiveOffsetCutoff_DedupsHistoryVsLive() {
        // History entries at or above the live cutoff should be filtered
        // to prevent duplicates with live F003 pipeline
        val entries = (998..1002).map {
            HistoryStoreEntry(it, 100f, 50f, true)
        }
        val sensorStart = 1_700_000_000_000L
        val now = sensorStart + (15L * 24 * 60 * 60_000L)
        val result = HistoryMerge.filterForStorage(
            entries, sensorStart, now, liveOffsetCutoff = 1000
        )
        // Only offsets 998, 999 should pass (< 1000)
        assertEquals(2, result.passed.size)
        assertEquals(998, result.passed[0].offsetMinutes)
        assertEquals(999, result.passed[1].offsetMinutes)
    }
}
