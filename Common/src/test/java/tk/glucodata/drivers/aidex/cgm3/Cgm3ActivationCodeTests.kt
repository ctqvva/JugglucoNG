package tk.glucodata.drivers.aidex.cgm3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Cgm3ActivationCodeTests {

    @Test
    fun crc16Ccitt_matchesStandardCheckVector() {
        // CRC-16/CCITT-FALSE check value for "123456789" is 0x29B1.
        assertEquals(0x29B1, Cgm3ActivationCode.crc16Ccitt("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0xFFFF, Cgm3ActivationCode.crc16Ccitt(ByteArray(0)))
    }

    @Test
    fun byteToActivationChar_mapsVendorRanges() {
        assertEquals('0', Cgm3ActivationCode.byteToActivationChar(0))
        assertEquals('9', Cgm3ActivationCode.byteToActivationChar(9))
        assertEquals('A', Cgm3ActivationCode.byteToActivationChar(10))
        assertEquals('Z', Cgm3ActivationCode.byteToActivationChar(35))
    }

    @Test
    fun convertCrcToFourChars_isFourCharsFromCharset() {
        val encoded = Cgm3ActivationCode.convertCrcToFourChars(0x29B1)
        assertEquals(4, encoded.length)
        assertTrue(encoded.all { it in Cgm3ActivationCode.CAL_CHARSET })
        assertEquals("VVVV", Cgm3ActivationCode.convertCrcToFourChars(0))
    }

    @Test
    fun encode_produces20CharCode() {
        val code = Cgm3ActivationCode.encode(ByteArray(16))
        assertNotNull(code)
        assertEquals(20, code!!.length)
        assertTrue(code.startsWith("0000000000000000"))
        assertNull(Cgm3ActivationCode.encode(ByteArray(15)))
        assertNull(Cgm3ActivationCode.encode(ByteArray(17)))
    }

    @Test
    fun decode_invertsCharMapping() {
        assertEquals(0, Cgm3ActivationCode.decodeChar('0'))
        assertEquals(9, Cgm3ActivationCode.decodeChar('9'))
        assertEquals(10, Cgm3ActivationCode.decodeChar('A'))
        assertEquals(35, Cgm3ActivationCode.decodeChar('Z'))
        assertEquals(10, Cgm3ActivationCode.decodeChar('a'))
        assertEquals(35, Cgm3ActivationCode.decodeChar('z'))
        assertNull(Cgm3ActivationCode.decode("SHORT"))
    }

    @Test
    fun encodeDecode_roundTripsVendorRange() {
        val payload = ByteArray(16) { (it * 2).toByte() } // 0..30, inside the 0..35 range
        val code = Cgm3ActivationCode.encode(payload)!!
        assertTrue(Cgm3ActivationCode.verify(code))
        assertArrayEquals(payload, Cgm3ActivationCode.decode(code))
    }

    @Test
    fun verify_rejectsTamperedAndMalformedCodes() {
        val code = Cgm3ActivationCode.encode(ByteArray(16) { 7 })!!
        assertTrue(Cgm3ActivationCode.verify(code))
        val tampered = code.substring(0, 19) + if (code[19] == 'A') 'B' else 'A'
        assertFalse(Cgm3ActivationCode.verify(tampered))
        assertFalse(Cgm3ActivationCode.verify(code.substring(0, 16)))
        assertFalse(Cgm3ActivationCode.verify(code + "X"))
    }

    @Test
    fun provisioning_decodesHexAndBase64Material() {
        val hex = "A376886ED5ECD12868AF84F6529E91D7"
        val decoded = Cgm3Provisioning.decodeMaterial(hex)
        assertNotNull(decoded)
        assertEquals(16, decoded!!.size)
        assertEquals(0xA3.toByte(), decoded[0])
        val b64 = java.util.Base64.getEncoder().encodeToString(decoded)
        assertArrayEquals(decoded, Cgm3Provisioning.decodeMaterial(b64))
        assertNull(Cgm3Provisioning.decodeMaterial("ZZZ"))
        assertTrue(Cgm3Provisioning.validate(decoded, decoded))
        assertTrue(Cgm3Provisioning.validateSessionKey(decoded))
        assertFalse(Cgm3Provisioning.validate(ByteArray(15), decoded))
    }

    @Test
    fun provisioning_testSecretsDecodeTo16Bytes() {
        for ((sn, keys) in Cgm3Provisioning.TEST_SECRETS) {
            val (secretHex, ivHex) = keys
            assertEquals(16, Cgm3Provisioning.decodeMaterial(secretHex)!!.size)
            assertEquals(16, Cgm3Provisioning.decodeMaterial(ivHex)!!.size)
            assertTrue(sn.isNotBlank())
        }
    }

    @Test
    fun provisioning_storageKeyIsStableAndNamespaced() {
        val a = Cgm3Provisioning.storageKey("TTTT000003")
        assertEquals(a, Cgm3Provisioning.storageKey("tttt000003"))
        assertTrue(a.startsWith("cgm3_material_"))
        assertFalse(a == Cgm3Provisioning.storageKey("TTTT000004"))
    }

    @Test
    fun provisioning_dynamicKeyJsonRoundTrips() {
        val secret = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it + 16).toByte() }
        val json = Cgm3Provisioning.encodeDynamicKey("SN12345678", secret, iv)!!
        val parsed = Cgm3Provisioning.decodeDynamicKey(json)!!
        assertEquals("SN12345678", parsed.serial)
        assertArrayEquals(secret, parsed.secret)
        assertArrayEquals(iv, parsed.iv)
        assertNull(Cgm3Provisioning.encodeDynamicKey("SN", ByteArray(15), iv))
    }

    @Test
    fun provisioning_unescapeJsonString() {
        assertEquals("a/b", Cgm3Provisioning.unescapeJsonString("a\\/b"))
        assertEquals("a\\b", Cgm3Provisioning.unescapeJsonString("a\\\\b"))
        assertEquals("a\"b", Cgm3Provisioning.unescapeJsonString("a\\\"b"))
        assertEquals("AB", Cgm3Provisioning.unescapeJsonString("\\u0041B"))
        assertEquals("plain", Cgm3Provisioning.unescapeJsonString("plain"))
        assertNull(Cgm3Provisioning.unescapeJsonString("trailing\\"))
        assertNull(Cgm3Provisioning.unescapeJsonString("bad\\x"))
        assertNull(Cgm3Provisioning.unescapeJsonString("bad\\u12"))
    }

    @Test
    fun provisioning_decodeDynamicKeyReadsEscapedJson() {
        // 0xFF,0xFF,... base64-encodes with '/' characters; org.json-style
        // producers escape them as '\/'. The pre-fix decoder choked on the
        // backslash and rejected the whole document.
        val secret = ByteArray(16) { 0xFF.toByte() }
        val iv = ByteArray(16) { 0x01 }
        val enc = java.util.Base64.getEncoder()
        val slashy = enc.encodeToString(secret).replace("/", "\\/")
        val json = "{\"publicKey\":\"" + slashy + "\"," +
            "\"communicationKey\":\"" + enc.encodeToString(iv) + "\"," +
            "\"deviceSn\":\"SN-ESC\"}\n"
        val parsed = Cgm3Provisioning.decodeDynamicKey(json)
        assertNotNull(parsed)
        assertEquals("SN-ESC", parsed!!.serial)
        assertArrayEquals(secret, parsed.secret)
        assertArrayEquals(iv, parsed.iv)
    }

    @Test
    fun parser_statusSplitsFlagsBigEndian() {
        // u16 BE 0x1234, u16 BE 0xABCD -> flags (>>14)&3 = 2, value &0x3FFF = 0x2BCD.
        val frame = byteArrayOf(0x12, 0x34, 0xAB.toByte(), 0xCD.toByte(), 0x01, 0x02)
        val status = Cgm3Parser.parseStatus(frame)!!
        assertEquals(0x1234, status.word0)
        assertEquals(2, status.flags2)
        assertEquals(0x2BCD, status.value14)
        assertEquals(1, status.byte4)
        assertEquals(2, status.byte5)
        assertNull(Cgm3Parser.parseStatus(ByteArray(5)))
    }

    @Test
    fun parser_broadcastDecodesTwoEntriesBigEndian() {
        val payload = byteArrayOf(
            0x00, // skipped leading byte
            0x00, 0x02, 0x40, 0x00, 0x00, 0x00, // status: word0=2, split=0x4000, b,b
            0x05, 0x03.toByte(), 0xE8.toByte(), 0x07, // entry0: s8=5 -> 0.5, u16=0x03E8=1000, u8
            0x00, 0x64, 0x08, // entry1: u16=0x0064=100, u8 (no trend byte)
        )
        val broadcast = Cgm3Parser.parseBroadcast(payload)!!
        assertEquals(2, broadcast.entries.size)
        val first = broadcast.entries[0]
        assertEquals(0.5f, first.channel, 0.0001f)
        assertEquals(1000, first.glucoseMgDl)
        assertTrue(first.isValid)
        val second = broadcast.entries[1]
        assertEquals(0.0f, second.channel, 0.0001f)
        assertEquals(100, second.glucoseMgDl)
        assertTrue(second.isValid)
        assertNull(Cgm3Parser.parseBroadcast(ByteArray(6)))
    }

    @Test
    fun parser_deviceSpecNeeds14Bytes() {
        assertNull(Cgm3Parser.parseDeviceSpec(ByteArray(13)))
        val payload = byteArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E,
        )
        val spec = Cgm3Parser.parseDeviceSpec(payload)!!
        assertEquals(0x01, spec.byte0)
        assertEquals(0x0203, spec.word1)
        assertEquals(0x0405, spec.word2)
        assertEquals(0x0607, spec.word3)
        assertEquals(0x08, spec.byte6)
        assertEquals(0x09, spec.byte7)
        assertEquals(0x0A0B, spec.word8)
        assertEquals(0x0C, spec.byte10)
        assertEquals(0x0D, spec.byte11)
        assertEquals(0x0E and 0x01, spec.flag12)
    }

    @Test
    fun session_bringUpOrderAndDynamicNames() {
        assertEquals(10, Cgm3Session.BRING_UP_ORDER.size)
        assertEquals(Cgm3Session.BringUpStep.SET_MAC, Cgm3Session.BRING_UP_ORDER.first())
        assertEquals(Cgm3Session.BringUpStep.PAIR, Cgm3Session.BRING_UP_ORDER.last())
        assertTrue(Cgm3Session.isDynamicSecretName("AiDEX F"))
        assertTrue(Cgm3Session.isDynamicSecretName("AiDEX S"))
        assertFalse(Cgm3Session.isDynamicSecretName("AiDEX X-1234567890"))
        assertTrue(Cgm3Session.isDynamicSecretName("AiDEX X-1234567890", setOf("AiDEX X-1234567890")))
        assertEquals(15, Cgm3Session.PROBE_MAX_DAYS)
        assertEquals(2, Cgm3Session.TRANSMITTER_LIFE_YEARS)
    }

    @Test
    fun opcodes_uuidProfileMatchesAidexX() {
        assertEquals("0000181f-0000-1000-8000-00805f9b34fb", Cgm3Opcodes.SERVICE_UUID.toString())
        assertEquals("0000f001-0000-1000-8000-00805f9b34fb", Cgm3Opcodes.CHAR_PRIVATE.toString())
        assertEquals("0000f002-0000-1000-8000-00805f9b34fb", Cgm3Opcodes.CHAR_COMMAND.toString())
        assertEquals("0000f003-0000-1000-8000-00805f9b34fb", Cgm3Opcodes.CHAR_NOTIFY.toString())
        assertEquals(0x01, Cgm3Opcodes.GET_START_TIME)
        assertEquals(0x09, Cgm3Opcodes.GET_HISTORIES)
        assertEquals(0x24, Cgm3Opcodes.SET_CALIBRATION)
        assertEquals(0x29, Cgm3Opcodes.RESET)
        assertEquals(0x2B, Cgm3Opcodes.CLEAR_STORAGE)
        assertEquals(0x2E, Cgm3Opcodes.ACTIVATE)
        assertEquals(17, Cgm3Opcodes.PAIR_WRITE_LENGTH)
        assertEquals(20, Cgm3Opcodes.SEED_LENGTH)
        assertEquals(16, Cgm3Opcodes.SESSION_KEY_LENGTH)
    }
}
