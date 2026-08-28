// JugglucoNG — AiDex Native Kotlin Driver
// AiDexKeyExchange.kt — F001 challenge, PAIR key, BOND decryption, session key
//
// Implements the complete vendor key exchange protocol.

package tk.glucodata.drivers.aidex.native.protocol

import tk.glucodata.drivers.aidex.native.crypto.AesCfb128
import tk.glucodata.drivers.aidex.native.crypto.SerialCrypto

/**
 * Manages the AiDex vendor key exchange for a single sensor connection.
 *
 * Protocol:
 *   1. Write snSecret to F001 (authentication challenge)
 *   2. Receive PAIR key from F001 notification (16 bytes, changes per connection)
 *   3. Read F002 to get 17-byte BOND data
 *   4. Decrypt BOND with PAIR key + SN IV -> session key
 *   5. Send post-BOND config (plaintext 10 C1 F3, encrypted with session key + SN IV)
 *   6. F003 data decrypted with session key + SN IV
 *   7. F002 commands encrypted with session key + SN IV
 */
class AiDexKeyExchange(val serial: String) {

    /** Bare serial number (without "X-" or "AiDEX X-" prefix).
     *  snToBytes() expects the bare serial (e.g., "2222267V4E", not "X-2222267V4E"). */
    val bareSerial: String = SerialCrypto.stripPrefix(serial)

    /** SN-derived secret (F001 challenge). Stable per serial. */
    val snSecret: ByteArray = SerialCrypto.deriveSecret(bareSerial)

    /** SN-derived IV. Used for BOND, F003, and F002. Stable per serial. */
    val snIv: ByteArray = SerialCrypto.deriveIv(bareSerial)

    /** PAIR key from F001 notification. Changes per connection. */
    var pairKey: ByteArray? = null
        private set

    /** Session key from BOND decryption. Changes per connection. */
    var sessionKey: ByteArray? = null
        private set

    /** Whether the key exchange completed successfully. */
    val isComplete: Boolean get() = sessionKey != null

    /** Step 1: Get the challenge to write to F001. */
    fun getChallenge(): ByteArray = snSecret

    /** Step 2: Store the PAIR key received from F001 notification. */
    fun onPairKeyReceived(data: ByteArray) {
        pairKey = data.copyOf()
    }

    /**
     * Step 3: Decrypt BOND data read from F002 (17 bytes).
     * Returns true if decryption and CRC-8 verification succeed.
     */
    fun decryptBond(bondData: ByteArray): Boolean {
        val pk = pairKey ?: return false
        val sk = AesCfb128.decryptBondData(bondData, pk, snIv) ?: return false
        sessionKey = sk
        return true
    }

    /**
     * Step 4: Get the post-BOND config command (encrypted).
     * Plaintext is always [0x10, 0xC1, 0xF3].
     */
    fun getPostBondConfig(): ByteArray? {
        val sk = sessionKey ?: return null
        return AesCfb128.encrypt(
            byteArrayOf(0x10, 0xC1.toByte(), 0xF3.toByte()),
            sk, snIv
        )
    }

    /**
     * Encrypt data for F002 write (command encryption).
     */
    fun encrypt(plaintext: ByteArray): ByteArray? {
        val sk = sessionKey ?: return null
        return AesCfb128.encrypt(plaintext, sk, snIv)
    }

    /**
     * Decrypt data from F002 notification or F003 (response/glucose decryption).
     */
    fun decrypt(ciphertext: ByteArray): ByteArray? {
        val sk = sessionKey ?: return null
        return AesCfb128.decrypt(ciphertext, sk, snIv)
    }

    /** Reset state for a new connection. */
    fun reset() {
        pairKey = null
        sessionKey = null
    }
}
