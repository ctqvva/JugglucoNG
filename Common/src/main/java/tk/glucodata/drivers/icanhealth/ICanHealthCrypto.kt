// JugglucoNG — iCanHealth (Sinocare iCan i3/i6/i7) Driver
// ICanHealthCrypto.kt — firmware-selected AES helpers for iCan glucose and auth.

package tk.glucodata.drivers.icanhealth

import tk.glucodata.Log
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object ICanHealthCrypto {

    private const val TAG = "ICanHealthCrypto"

    /**
     * Decrypt a single 16-byte AES-128-ECB block.
     *
     * @param ciphertext 16 bytes of encrypted data
     * @param key 16 bytes AES key
     * @return 16 bytes of plaintext, or null on failure
     */
    fun decryptBlock(ciphertext: ByteArray, key: ByteArray): ByteArray? {
        if (ciphertext.size != 16 || key.size != 16) {
            Log.e(TAG, "decryptBlock: invalid sizes ciphertext=${ciphertext.size} key=${key.size}")
            return null
        }
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "decryptBlock failed: ${e.message}")
            null
        }
    }

    /**
     * Encrypt a single 16-byte AES-128-ECB block.
     *
     * @param plaintext 16 bytes of data
     * @param key 16 bytes AES key
     * @return 16 bytes of ciphertext, or null on failure
     */
    fun encryptBlock(plaintext: ByteArray, key: ByteArray): ByteArray? {
        if (plaintext.size != 16 || key.size != 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "encryptBlock failed: ${e.message}")
            null
        }
    }

    /**
     * Encrypt a payload with AES-128-CBC and PKCS padding.
     *
     * Android exposes PKCS7-compatible AES/CBC padding via PKCS5Padding.
     */
    fun encryptCbcPkcs7(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        if (key.size != 16 || iv.size != 16) {
            Log.e(TAG, "encryptCbcPkcs7: invalid key/iv sizes key=${key.size} iv=${iv.size}")
            return null
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "encryptCbcPkcs7 failed: ${e.message}")
            null
        }
    }

    fun decryptCbcPkcs7(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        if (key.size != 16 || iv.size != 16) {
            Log.e(TAG, "decryptCbcPkcs7: invalid key/iv sizes key=${key.size} iv=${iv.size}")
            return null
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "decryptCbcPkcs7 failed: ${e.message}")
            null
        }
    }

    /**
     * Convert a 16-character ASCII string to a 16-byte AES key.
     *
     * @param asciiKey 16-character ASCII string
     * @return 16-byte key, or null if length mismatch
     */
    fun keyFromASCII(asciiKey: String): ByteArray? {
        if (asciiKey.length != 16) {
            Log.e(TAG, "keyFromASCII: expected 16 chars, got ${asciiKey.length}")
            return null
        }
        return asciiKey.toByteArray(Charsets.US_ASCII)
    }

    /**
     * Run a self-test to verify AES-128-ECB implementation.
     * Uses NIST AES-128 test vector (FIPS 197, Appendix B).
     *
     * @return true if self-test passes
     */
    fun runSelfTest(): Boolean {
        // NIST test vector
        val key = byteArrayOf(
            0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae.toByte(), 0xd2.toByte(), 0xa6.toByte(),
            0xab.toByte(), 0xf7.toByte(), 0x15, 0x88.toByte(), 0x09, 0xcf.toByte(), 0x4f, 0x3c
        )
        val plaintext = byteArrayOf(
            0x32, 0x43, 0xf6.toByte(), 0xa8.toByte(), 0x88.toByte(), 0x5a, 0x30, 0x8d.toByte(),
            0x31, 0x31, 0x98.toByte(), 0xa2.toByte(), 0xe0.toByte(), 0x37, 0x07, 0x34
        )
        val expectedCiphertext = byteArrayOf(
            0x39, 0x25, 0x84.toByte(), 0x1d, 0x02, 0xdc.toByte(), 0x09, 0xfb.toByte(),
            0xdc.toByte(), 0x11, 0x85.toByte(), 0x97.toByte(), 0x19, 0x6a, 0x0b, 0x32
        )

        val encrypted = encryptBlock(plaintext, key)
        if (encrypted == null || !encrypted.contentEquals(expectedCiphertext)) {
            Log.e(TAG, "Self-test FAILED: encryption mismatch")
            return false
        }

        val decrypted = decryptBlock(encrypted, key)
        if (decrypted == null || !decrypted.contentEquals(plaintext)) {
            Log.e(TAG, "Self-test FAILED: decryption mismatch")
            return false
        }

        Log.i(TAG, "Self-test PASSED")
        return true
    }
}
