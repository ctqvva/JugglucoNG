package tk.glucodata.drivers.ottai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests for the Ottai cloud secret chain. These prove the AES/ECB/PKCS5
 * + key-derivation + keyA-split mechanics round-trip correctly. They do NOT
 * assert correctness against live Ottai data (that is Phase 1/3/4).
 */
class OttaiCryptoTests {

    @Test
    fun takeLast_matchesDecompiledSemantics() {
        assertEquals("4567890123", OttaiCrypto.takeLast(10, "1234567890123"))
        assertEquals("ABCDEF", OttaiCrypto.takeLast(6, "00:11:22:AABBCCDDEEFF".replace(":", "").let { "112233AABCDEF" }))
        // n >= length returns whole string
        assertEquals("abc", OttaiCrypto.takeLast(10, "abc"))
        assertEquals("", OttaiCrypto.takeLast(0, "abc"))
    }

    @Test
    fun deriveFieldKey_concatenatesSecretTimestampTailMacTail() {
        // 16-char secret + last10(timestamp) + last6(mac) = 32 chars => AES-256 key.
        val secret = "0123456789ABCDEF"
        val produceTime = "1700000000123" // 13-digit epoch ms
        val mac = "C0FFEE112233"
        val key = OttaiCrypto.deriveFieldKey(secret, produceTime, mac)
        assertEquals(secret + "0000000123" + "112233", key)
        assertEquals(32, key.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun base64_roundTrips() {
        val samples = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(0, 1, 2),
            byteArrayOf(0, 1, 2, 3),
            "hello ottai".toByteArray(Charsets.UTF_8),
            ByteArray(96) { it.toByte() },
        )
        for (s in samples) {
            val enc = OttaiCrypto.base64Encode(s)
            val dec = OttaiCrypto.base64Decode(enc)
            assertNotNull(enc)
            assertArrayEquals(s, dec)
        }
        // Known vector.
        assertEquals("aGVsbG8=", OttaiCrypto.base64Encode("hello".toByteArray()))
        assertArrayEquals("hello".toByteArray(), OttaiCrypto.base64Decode("aGVsbG8="))
    }

    @Test
    fun hex_roundTrips() {
        val bytes = ByteArray(64) { (it * 7).toByte() }
        val hex = OttaiCrypto.bytesToHex(bytes)
        assertEquals(128, hex.length)
        assertArrayEquals(bytes, OttaiCrypto.hexToBytes(hex))
    }

    @Test
    fun cloudField_encryptThenDecrypt_roundTrips() {
        val secret = "0123456789ABCDEF"
        val mac = "C0FFEE112233"
        val ts = "1699999999000"
        val key = OttaiCrypto.deriveFieldKey(secret, ts, mac)
        val plaintext = "1.5;V0 C0 ML R0 AD" // method-like payload
        val cipher = OttaiCrypto.encryptCloudField(plaintext, key)
        assertNotNull(cipher)
        val back = OttaiCrypto.decryptCloudField(cipher!!, key)
        assertEquals(plaintext, back)
    }

    @Test
    fun cloudField_wrongKey_returnsNullOrGarbageNotCrash() {
        val key = OttaiCrypto.deriveFieldKey("0123456789ABCDEF", "1699999999000", "C0FFEE112233")
        val cipher = OttaiCrypto.encryptCloudField("payload-123", key)!!
        val wrong = OttaiCrypto.deriveFieldKey("FEDCBA9876543210", "1699999999000", "C0FFEE112233")
        // PKCS5 padding will almost always reject a wrong key -> null (no throw).
        val back = OttaiCrypto.decryptCloudField(cipher, wrong)
        assertTrue(back == null || back != "payload-123")
    }

    @Test
    fun keyA_sixKeys_decryptAndSplit() {
        // Build six known 16-byte auth keys, hex-join to 192 chars, encrypt as keyA,
        // then decrypt + split and compare.
        val keys = (0 until 6).map { k -> ByteArray(16) { (k * 16 + it).toByte() } }
        val joined = keys.joinToString("") { OttaiCrypto.bytesToHex(it) }
        assertEquals(OttaiCrypto.AUTH_KEY_HEX_LEN, joined.length)

        val secret = "0123456789ABCDEF"
        val mac = "C0FFEE112233"
        val produceTime = "1700000000999"
        val fieldKey = OttaiCrypto.deriveFieldKey(secret, produceTime, mac)
        val keyACipher = OttaiCrypto.encryptCloudField(joined, fieldKey)!!

        val plain = OttaiCrypto.decryptKeyA(keyACipher, secret, produceTime, mac)
        assertEquals(joined, plain)

        val split = OttaiCrypto.parseAuthKeys(plain!!)
        assertNotNull(split)
        assertEquals(6, split!!.size)
        for (i in 0 until 6) assertArrayEquals(keys[i], split[i])
    }

    @Test
    fun parseAuthKeys_rejectsWrongLength() {
        assertNull(OttaiCrypto.parseAuthKeys("abcd"))
        assertNull(OttaiCrypto.parseAuthKeys("zz".repeat(96))) // 192 chars but non-hex
    }
}
