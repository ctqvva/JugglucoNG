// JugglucoNG — AiDex Native Kotlin Driver
// AesCfb128.kt — AES-128-CFB encrypt/decrypt
//
// Pure Kotlin using javax.crypto for AES-ECB as the building block.
// Ported from iGlucco AiDexCryptoEngine.swift.
//
// CFB-128 mode:
//   For each 16-byte block:
//     encryptedFeedback = AES-ECB-ENCRYPT(key, feedback)
//     output = input XOR encryptedFeedback
//     feedback = CIPHERTEXT block (for both encrypt and decrypt)

package tk.glucodata.drivers.aidex.native.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object AesCfb128 {

    private const val BLOCK_SIZE = 16

    /**
     * AES-128-CFB decryption.
     *
     * Used for:
     * - BOND data decryption (key = PAIR key, IV = SN-derived IV)
     * - F003 glucose data decryption (key = session key, IV = SN-derived IV)
     * - F002 response decryption (key = session key, IV = SN-derived IV)
     */
    fun decrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        if (key.size != 16 || iv.size != 16 || ciphertext.isEmpty()) return null

        val result = ByteArray(ciphertext.size)
        var feedback = iv.copyOf()
        var offset = 0

        while (offset < ciphertext.size) {
            val chunkSize = minOf(BLOCK_SIZE, ciphertext.size - offset)
            val encryptedFeedback = ecbEncrypt(feedback, key) ?: return null

            for (i in 0 until chunkSize) {
                result[offset + i] = (ciphertext[offset + i].toInt() xor encryptedFeedback[i].toInt()).toByte()
            }

            // Feedback is always the ciphertext block
            if (chunkSize == BLOCK_SIZE) {
                feedback = ciphertext.copyOfRange(offset, offset + BLOCK_SIZE)
            }

            offset += chunkSize
        }

        return result
    }

    /**
     * AES-128-CFB encryption.
     *
     * Used for:
     * - Post-BOND config encryption (key = session key, IV = SN-derived IV)
     * - F002 command encryption (key = session key, IV = SN-derived IV)
     */
    fun encrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        if (key.size != 16 || iv.size != 16 || plaintext.isEmpty()) return null

        val result = ByteArray(plaintext.size)
        var feedback = iv.copyOf()
        var offset = 0

        while (offset < plaintext.size) {
            val chunkSize = minOf(BLOCK_SIZE, plaintext.size - offset)
            val encryptedFeedback = ecbEncrypt(feedback, key) ?: return null

            for (i in 0 until chunkSize) {
                result[offset + i] = (plaintext[offset + i].toInt() xor encryptedFeedback[i].toInt()).toByte()
            }

            // Feedback is always the ciphertext block
            if (chunkSize == BLOCK_SIZE) {
                feedback = result.copyOfRange(offset, offset + BLOCK_SIZE)
            }

            offset += chunkSize
        }

        return result
    }

    /**
     * Decrypt 17-byte BOND data to extract 16-byte session key.
     *
     * 1. AES-128-CFB decrypt 17 bytes using PAIR key + SN IV
     * 2. Verify CRC-8/MAXIM: decrypted[16] == CRC8(decrypted[0..15])
     * 3. On success, decrypted[0..15] is the session key
     *
     * Returns session key (16 bytes) or null on failure.
     */
    fun decryptBondData(bondData: ByteArray, pairKey: ByteArray, iv: ByteArray): ByteArray? {
        if (bondData.size != 17 || pairKey.size != 16 || iv.size != 16) return null

        val decrypted = decrypt(bondData, pairKey, iv) ?: return null

        val sessionKey = decrypted.copyOfRange(0, 16)
        val checksumByte = decrypted[16].toInt() and 0xFF
        val computedChecksum = Crc8Maxim.checksum(sessionKey)

        if (checksumByte != computedChecksum) return null

        return sessionKey
    }

    /**
     * AES-128-ECB encrypt a single 16-byte block.
     * The fundamental building block for CFB mode.
     */
    fun ecbEncrypt(block: ByteArray, key: ByteArray): ByteArray? {
        if (block.size != 16 || key.size != 16) return null

        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(block)
        } catch (e: Exception) {
            null
        }
    }
}
