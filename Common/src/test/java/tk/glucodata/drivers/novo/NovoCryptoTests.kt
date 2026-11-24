package tk.glucodata.drivers.novo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NovoCryptoTests {
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun baseKeyReproducesVendorVectors() {
        assertEquals(
            "aef53869b88ed2df40b1b1cff385c4a5",
            NovoCrypto.encrypt("010000000c0c0c0c0c0c0c0c0c0c0c0c".hexBytes()).hex(),
        )
        assertEquals(
            "2a5e7c2a52c27fdd9a34e1e5c74c8993",
            NovoCrypto.encrypt("500000000c0c0c0c0c0c0c0c0c0c0c0c".hexBytes()).hex(),
        )
        assertEquals(
            "1227d97b38bd6672f07a1a315c560f80",
            NovoCrypto.encrypt("000102030405060708090a0b0c0d0e0f".hexBytes()).hex(),
        )
    }

    @Test
    fun passwordKeyVectorMatchesObservedCiphertext() {
        assertEquals(
            "aeb3a22cfa2fd17529eb9cab86680be9",
            NovoCrypto.encrypt("010000000c0c0c0c0c0c0c0c0c0c0c0c".hexBytes(), 0x31, 0xAC.toByte()).hex(),
        )
    }

    @Test
    fun passwordBytesArePlacedAtKeyTailInVendorOrder() {
        val key = NovoCrypto.keyWithPassword(0x31, 0x30)
        assertEquals(NovoConstants.AES_KEY_HEX.take(28), key.copyOf(14).hex())
        assertEquals(0x30.toByte(), key[14])
        assertEquals(0x31.toByte(), key[15])
    }

    @Test
    fun decryptWithBaseKeyStripsManualPadding() {
        assertArrayEquals(
            "01000000".hexBytes(),
            NovoCrypto.decrypt("aef53869b88ed2df40b1b1cff385c4a5".hexBytes()),
        )
    }

    @Test
    fun padToBlockFollowsVendorManualPadding() {
        assertEquals("010000000c0c0c0c0c0c0c0c0c0c0c0c", NovoCrypto.padToBlock("01000000".hexBytes()).hex())
        assertEquals("000102030405060708090a0b0c0d0e0f", NovoCrypto.padToBlock("000102030405060708090a0b0c0d0e0f".hexBytes()).hex())
        assertEquals("7a000041424331323334060606060606", NovoCrypto.padToBlock("7a000041424331323334".hexBytes()).hex())
    }

    @Test
    fun deviceCodeIsAsciiHexOfNameWithoutPrefix() {
        assertEquals("4142434431323334", NovoCrypto.deviceCodeFromName("DN-ABCD1234"))
        assertEquals("4142", NovoCrypto.deviceCodeFromName("DN-AB"))
        assertEquals("", NovoCrypto.deviceCodeFromName("DN-"))
        assertEquals("", NovoCrypto.deviceCodeFromName(null))
        assertEquals("", NovoCrypto.deviceCodeFromName("   "))
    }

    @Test
    fun crcPlaceholderIsStable() {
        assertEquals(0x29B1, NovoCrypto.crc16CcittPlaceholder("123456789".toByteArray()))
        assertEquals(0xF274, NovoCrypto.crc16CcittPlaceholder("01000000".hexBytes()))
    }
}
