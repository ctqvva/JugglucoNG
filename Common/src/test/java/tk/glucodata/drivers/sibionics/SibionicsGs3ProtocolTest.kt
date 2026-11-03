package tk.glucodata.drivers.sibionics

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.sibionics.SibionicsGs3Protocol.FrameCipher
import tk.glucodata.drivers.sibionics.SibionicsGs3Protocol.Message

class SibionicsGs3ProtocolTest {
    private val mac = bytes("C1 02 03 A4 B5 F6")

    @Test
    fun `sysinfo frames match the vendor's hardcoded bytes`() {
        // The vendor hardcodes these; they pin the CRC rule (-sum & 0xFF) and the framing.
        assertArrayEquals(bytes("03 F0 0F FE"), SibionicsGs3Protocol.buildSysInfo(15))
        assertArrayEquals(bytes("03 F0 01 0C"), SibionicsGs3Protocol.buildSysInfo(1))
        assertArrayEquals(bytes("03 F0 07 06"), SibionicsGs3Protocol.buildSysInfo(7))
    }

    @Test
    fun `auth v3 carries mac, session key and the fixed nonce`() {
        val p = SibionicsGs3Protocol.buildAuthV3(mac)
        assertEquals(38, p.size)
        assertEquals(0x25, p[0].u())
        assertEquals(0xE2, p[1].u())
        assertEquals(0x00, p[2].u())
        assertArrayEquals(mac, p.copyOfRange(3, 9))
        assertArrayEquals("GKSHGDU0TYA456G4".toByteArray(), p.copyOfRange(9, 25))
        assertArrayEquals(bytes("1C 7C 8B DB 4E 02 00 00 00 00 00 00"), p.copyOfRange(25, 37))
        assertTrue(SibionicsGs3Protocol.isValidFrame(p))
    }

    @Test
    fun `auth v2 is the 26-byte form`() {
        val p = SibionicsGs3Protocol.buildAuthV2(mac)
        assertEquals(26, p.size)
        assertArrayEquals(bytes("19 01 00"), p.copyOfRange(0, 3))
        assertArrayEquals(mac, p.copyOfRange(3, 9))
        assertArrayEquals("GKSHGDU0TYA456G4".toByteArray(), p.copyOfRange(9, 25))
        assertTrue(SibionicsGs3Protocol.isValidFrame(p))
    }

    @Test
    fun `session key is the one the CN registration key derives`() {
        assertArrayEquals(
            SibionicsProtocol.deriveSessionKey(SibionicsConstants.Variant.CHINESE),
            SibionicsGs3Protocol.SESSION_KEY,
        )
        assertArrayEquals(
            SibionicsProtocol.deriveSessionKey(SibionicsConstants.Variant.GS3_CN),
            SibionicsGs3Protocol.SESSION_KEY,
        )
    }

    @Test
    fun `bind-in, id-bind, activate, time and get-data layouts`() {
        val time = 0x6543_2101L
        val bindIn = SibionicsGs3Protocol.buildBindIn(time)
        assertEquals(19, bindIn.size)
        assertArrayEquals(bytes("12 35 01 21 43 65"), bindIn.copyOfRange(0, 6))
        assertArrayEquals(SibionicsGs3Protocol.FIXED_NONCE, bindIn.copyOfRange(6, 18))

        val idBind = SibionicsGs3Protocol.buildIdBind(1)
        assertEquals(16, idBind.size)
        assertArrayEquals(bytes("0F 13 01"), idBind.copyOfRange(0, 3))
        assertArrayEquals(SibionicsGs3Protocol.FIXED_NONCE, idBind.copyOfRange(3, 15))
        assertEquals(2, SibionicsGs3Protocol.buildIdBind(2)[2].u())

        assertArrayEquals(bytes("06 0F 01 21 43 65"), SibionicsGs3Protocol.buildActivate(time).copyOf(6))
        assertArrayEquals(bytes("06 03 01 21 43 65"), SibionicsGs3Protocol.buildTime(time).copyOf(6))
        assertArrayEquals(bytes("06 39 34 12 00 00"), SibionicsGs3Protocol.buildGetDataV3(0x1234).copyOf(6))
        assertArrayEquals(bytes("06 14 34 12 00 00"), SibionicsGs3Protocol.buildGetDataV2(0x1234).copyOf(6))
        listOf(bindIn, idBind, SibionicsGs3Protocol.buildGetDataV3(7)).forEach {
            assertTrue(SibionicsGs3Protocol.isValidFrame(it))
        }
    }

    @Test
    fun `reply types follow cmd or 0xC000 plus one`() {
        val expected = mapOf(
            0x01 to 0xC002,
            0xE2 to 0xC0E3,
            0x03 to 0xC004,
            0x0F to 0xC010,
            0x13 to 0xC014,
            0x14 to 0xC015,
            0x35 to 0xC036,
            0x38 to 0xC039,
            0x39 to 0xC03A,
        )
        expected.forEach { (cmd, reply) -> assertEquals(reply, SibionicsGs3Protocol.replyType(cmd)) }
    }

    @Test
    fun `aes keystream is standard OFB`() {
        val data = ByteArray(53) { (it * 7 + 3).toByte() }
        val iv = SibionicsGs3Protocol.registeredIv(mac)
        val jce = Cipher.getInstance("AES/OFB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(SibionicsGs3Protocol.AUTH_KEY, "AES"), IvParameterSpec(iv))
        }.doFinal(data)
        assertArrayEquals(jce, SibionicsGs3Protocol.aesOfb(SibionicsGs3Protocol.AUTH_KEY, iv, data))
    }

    @Test
    fun `registered iv is the mac followed by ten zero bytes`() {
        assertArrayEquals(mac + ByteArray(10), SibionicsGs3Protocol.registeredIv(mac))
    }

    @Test
    fun `rc4 fallback uses the shared master key`() {
        val plain = SibionicsGs3Protocol.buildAuthV2(mac)
        assertArrayEquals(
            SibionicsProtocol.encrypt(plain),
            SibionicsGs3Protocol.applyCipher(FrameCipher.RC4, mac, plain),
        )
    }

    @Test
    fun `every cipher round-trips and is recognised on the way in`() {
        val plain = SibionicsGs3Protocol.frame(0xE3, bytes("00 00"))
        FrameCipher.entries.forEach { cipher ->
            val wire = SibionicsGs3Protocol.applyCipher(cipher, mac, plain)!!
            assertArrayEquals(cipher.name, plain, SibionicsGs3Protocol.applyCipher(cipher, mac, wire))
            val decoded = SibionicsGs3Protocol.decodeIncoming(wire, FrameCipher.REGISTERED_V3, mac)
            assertArrayEquals(cipher.name, plain, decoded?.plain)
        }
    }

    @Test
    fun `registered cipher needs an identity`() {
        assertNull(SibionicsGs3Protocol.applyCipher(FrameCipher.REGISTERED_V3, null, bytes("01 02")))
    }

    @Test
    fun `incoming order tries plain then the current cipher`() {
        assertEquals(
            listOf(FrameCipher.PLAIN, FrameCipher.RC4, FrameCipher.REGISTERED_V3, FrameCipher.AES_OFB_ZERO),
            SibionicsGs3Protocol.incomingOrder(FrameCipher.RC4),
        )
        assertEquals(
            listOf(FrameCipher.PLAIN, FrameCipher.REGISTERED_V3, FrameCipher.AES_OFB_ZERO, FrameCipher.RC4),
            SibionicsGs3Protocol.incomingOrder(FrameCipher.REGISTERED_V3),
        )
    }

    @Test
    fun `frame validation needs exact length and crc`() {
        val good = SibionicsGs3Protocol.frame(0xE3, bytes("00 00"))
        assertTrue(SibionicsGs3Protocol.isValidFrame(good))
        assertFalse(SibionicsGs3Protocol.isValidFrame(good + byteArrayOf(0)))
        assertFalse(SibionicsGs3Protocol.isValidFrame(good.copyOf(good.size - 1)))
        assertFalse(SibionicsGs3Protocol.isValidFrame(good.copyOf().also { it[2] = 1 }))
    }

    @Test
    fun `acks parse in the short and the bind-in form`() {
        val ack = SibionicsGs3Protocol.parse(SibionicsGs3Protocol.frame(0xE3, bytes("00 05"))) as Message.Ack
        assertEquals(0xC0E3, ack.replyType)
        assertEquals(0, ack.result)
        assertEquals(5, ack.arg)

        val bindIn = SibionicsGs3Protocol.frame(0x36, bytes("00 01 02 03 04 05 06"))
        assertEquals(10, bindIn.size)
        val parsed = SibionicsGs3Protocol.parse(bindIn) as Message.Ack
        assertEquals(0xC036, parsed.replyType)
        assertEquals(1, parsed.arg)
    }

    @Test
    fun `sysinfo replies parse their subtype`() {
        val reply = SibionicsGs3Protocol.parse(SibionicsGs3Protocol.frame(0xF1, bytes("0F AA BB"))) as Message.SysInfo
        assertEquals(15, reply.subtype)
        assertArrayEquals(bytes("AA BB"), reply.payload)
    }

    @Test
    fun `history page decodes glucose, trend, indices and ages`() {
        val page = page(cmd = 0x39, start = 100, trailer = 3, glucoseX10 = listOf(56, 61, 123), trendFlags = 0x20)
        assertEquals(3 * 16 + 8, page.size)
        val parsed = SibionicsGs3Protocol.parse(page) as Message.GlucosePage
        assertTrue(parsed.isHistoryPage)
        assertEquals(100, parsed.startIndex)
        assertEquals(3, parsed.trailer)
        assertEquals(listOf(100, 101, 102), parsed.records.map { it.index })
        assertEquals(listOf(56, 61, 123), parsed.records.map { it.glucoseX10 })
        // (last + count - 1) - i
        assertEquals(listOf(5, 4, 3), parsed.records.map { it.ageRecords })
        assertEquals(SibionicsProtocol.Trend.STABLE, parsed.records.first().trend)
        assertEquals(12.3f * SibionicsConstants.MGDL_PER_MMOLL, parsed.records.last().mgdl, 0.001f)
    }

    @Test
    fun `live page is current`() {
        val parsed = SibionicsGs3Protocol.parse(page(cmd = 0x32, start = 9, trailer = 0, glucoseX10 = listOf(70))) as Message.GlucosePage
        assertFalse(parsed.isHistoryPage)
        assertEquals(0, parsed.records.single().ageRecords)
        // A multi-record live page counts back from its newest record.
        assertEquals(listOf(2, 1, 0), (0 until 3).map { SibionicsGs3Protocol.ageRecords(0x32, 0, 3, it) })
    }

    @Test
    fun `empty page and wrong sizes`() {
        val empty = SibionicsGs3Protocol.parse(page(cmd = 0x39, start = 1, trailer = 0, glucoseX10 = emptyList()))
        assertTrue(empty is Message.GlucosePage && empty.records.isEmpty())
        // A 0x39 frame of ACK size is the BindIn-alt ACK, not data.
        val ack = SibionicsGs3Protocol.parse(SibionicsGs3Protocol.frame(0x39, bytes("00 00")))
        assertTrue(ack is Message.Ack)
    }

    @Test
    fun `glucose bytes carry their own encryption layer`() {
        // The record bytes on the wire are not the plain value.
        val wire = page(cmd = 0x32, start = 1, trailer = 0, glucoseX10 = listOf(0x0102))
        val stored = (wire[9].u()) or (wire[10].u() shl 8)
        assertTrue(stored != 0x0102)
        assertEquals(0x0102, (SibionicsGs3Protocol.parse(wire) as Message.GlucosePage).records.single().glucoseX10)
    }

    @Test
    fun `implausible values are flagged`() {
        val parsed = SibionicsGs3Protocol.parse(page(0x32, 1, 0, listOf(0, 501, 500))) as Message.GlucosePage
        assertEquals(listOf(false, false, true), parsed.records.map { it.isPlausible })
    }

    @Test
    fun `data request start index`() {
        assertEquals(1, SibionicsGs3Protocol.requestStartIndex(0))
        assertEquals(43, SibionicsGs3Protocol.requestStartIndex(42))
        assertEquals(0xFFFF, SibionicsGs3Protocol.requestStartIndex(0xFFFF))
    }

    @Test
    fun `a split frame is recognised as a prefix`() {
        val frame = SibionicsGs3Protocol.applyCipher(FrameCipher.REGISTERED_V3, mac, page(0x39, 1, 0, listOf(50, 51)))!!
        assertNull(SibionicsGs3Protocol.decodeIncoming(frame.copyOf(20), FrameCipher.REGISTERED_V3, mac))
        assertTrue(SibionicsGs3Protocol.couldBeFramePrefix(frame.copyOf(20), FrameCipher.REGISTERED_V3, mac))
    }

    companion object {
        fun bytes(hex: String): ByteArray =
            hex.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

        private fun Byte.u(): Int = toInt() and 0xFF

        /** A plaintext glucose page as the sensor would frame it. */
        fun page(
            cmd: Int,
            start: Int,
            trailer: Int,
            glucoseX10: List<Int>,
            trendFlags: Int = 0x20,
        ): ByteArray {
            val count = glucoseX10.size
            val payload = ByteArray(3 + count * 16 + 2)
            payload[0] = count.toByte()
            payload[1] = (start and 0xFF).toByte()
            payload[2] = (start ushr 8).toByte()
            glucoseX10.forEachIndexed { i, value ->
                val base = 3 + i * 16
                val enc = SibionicsGs3Protocol.aesOfb(
                    SibionicsGs3Protocol.GLUCOSE_KEY,
                    ByteArray(16),
                    byteArrayOf((value and 0xFF).toByte(), (value ushr 8).toByte()),
                )
                payload[base + 4] = enc[0]
                payload[base + 5] = enc[1]
                payload[base + 14] = trendFlags.toByte()
            }
            payload[3 + count * 16] = (trailer and 0xFF).toByte()
            payload[3 + count * 16 + 1] = (trailer ushr 8).toByte()
            return SibionicsGs3Protocol.frame(cmd, payload)
        }
    }
}
