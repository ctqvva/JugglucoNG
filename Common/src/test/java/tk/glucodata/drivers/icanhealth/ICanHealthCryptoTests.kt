package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthCryptoTests {

    private val key = "1234567890123456".toByteArray()
    private val iv = "abcdefghijklmnop".toByteArray()
    private val block = ByteArray(16) { (it * 7 + 1).toByte() }

    @Test
    fun selfTestPassesNistVector() {
        assertTrue(ICanHealthCrypto.runSelfTest())
    }

    @Test
    fun ecbBlockRoundTrips() {
        val encrypted = ICanHealthCrypto.encryptBlock(block, key)
        assertTrue(encrypted != null)
        assertFalse(block.contentEquals(encrypted))
        assertArrayEquals(block, ICanHealthCrypto.decryptBlock(encrypted!!, key))
    }

    @Test
    fun ecbRejectsWrongSizes() {
        assertNull(ICanHealthCrypto.encryptBlock(ByteArray(15), key))
        assertNull(ICanHealthCrypto.encryptBlock(block, ByteArray(15)))
        assertNull(ICanHealthCrypto.decryptBlock(ByteArray(15), key))
        assertNull(ICanHealthCrypto.decryptBlock(block, ByteArray(15)))
    }

    @Test
    fun cbcPkcs7RoundTripsWithPadding() {
        val plaintext = "hello ican".toByteArray()
        val encrypted = ICanHealthCrypto.encryptCbcPkcs7(plaintext, key, iv)
        assertTrue(encrypted != null)
        assertEquals(0, encrypted!!.size % 16)
        assertTrue(encrypted.size > plaintext.size)
        assertArrayEquals(plaintext, ICanHealthCrypto.decryptCbcPkcs7(encrypted, key, iv))
    }

    @Test
    fun cbcRejectsWrongKeyOrIvSize() {
        assertNull(ICanHealthCrypto.encryptCbcPkcs7(ByteArray(4), ByteArray(15), iv))
        assertNull(ICanHealthCrypto.encryptCbcPkcs7(ByteArray(4), key, ByteArray(15)))
        assertNull(ICanHealthCrypto.decryptCbcPkcs7(ByteArray(16), ByteArray(15), iv))
        assertNull(ICanHealthCrypto.decryptCbcPkcs7(ByteArray(16), key, ByteArray(15)))
    }

    @Test
    fun asciiKeyRequiresExactly16Chars() {
        assertEquals(16, ICanHealthCrypto.keyFromASCII("1234567890123456")?.size)
        assertNull(ICanHealthCrypto.keyFromASCII("short"))
        assertNull(ICanHealthCrypto.keyFromASCII("12345678901234567"))
    }
}
