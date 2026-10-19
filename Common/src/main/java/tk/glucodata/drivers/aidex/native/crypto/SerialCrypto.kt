// JugglucoNG — AiDex Native Kotlin Driver
// SerialCrypto.kt — Serial number to AES key/IV derivation
//
// Derives the AES-128 secret (F001 challenge) and IV from the sensor serial number.
// Ported from libblecomm-lib.so ARM64 disassembly: AidexXController::setSn() at 0x59b04.
//
// Transform A (secret): MD5(snToBytes(sn).map { (b * 13 + 61) & 0xFF })
// Transform B (IV):     MD5(snToBytes(sn).map { (b * 17 + 19) & 0xFF })
//
// Verified with sensor 2222267V4E:
//   Secret = 4b76169576da80e4eeacf886230873d2 (matches F001 write in HCI trace)
//   IV     = 14cb6a3a39b96c448ebc39185f70f8aa

package tk.glucodata.drivers.aidex.native.crypto

import tk.glucodata.drivers.aidex.AiDexSerialIdentity
import java.security.MessageDigest

object SerialCrypto {

    /**
     * Convert a serial number character to its numeric value.
     *
     * Port of ByteUtils::snToBytes() at 0x81cac:
     *   '0'-'9' -> 0-9
     *   'A'-'Z' -> 10-35
     *   'a'-'z' -> 10-35
     */
    fun charToNumeric(c: Char): Int {
        return when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'Z' -> c - 'A' + 10
            in 'a'..'z' -> c - 'a' + 10
            else -> 0
        }
    }

    /**
     * Convert serial number string to numeric byte array.
     * The SN should be WITHOUT the "X-" prefix (e.g., "2222267V4E").
     */
    fun snToBytes(sn: String): ByteArray {
        return ByteArray(sn.length) { charToNumeric(sn[it]).toByte() }
    }

    /**
     * Derive the 16-byte secret (F001 challenge) from the serial number.
     * Algorithm: MD5(snToBytes(sn).map { (b * 13 + 61) & 0xFF })
     */
    fun deriveSecret(sn: String): ByteArray {
        val snBytes = snToBytes(sn)
        val transformed = ByteArray(snBytes.size) {
            ((snBytes[it].toInt() and 0xFF) * 13 + 61).toByte()
        }
        return md5(transformed)
    }

    /**
     * Derive the 16-byte IV from the serial number.
     * Algorithm: MD5(snToBytes(sn).map { (b * 17 + 19) & 0xFF })
     *
     * This IV is used for BOND decrypt, F003 decrypt, and F002 command encrypt.
     */
    fun deriveIv(sn: String): ByteArray {
        val snBytes = snToBytes(sn)
        val transformed = ByteArray(snBytes.size) {
            ((snBytes[it].toInt() and 0xFF) * 17 + 19).toByte()
        }
        return md5(transformed)
    }

    /**
     * Strip the advertisement-name prefix from an AiDex serial number.
     *
     * Handles X/F advertisement names, canonical X identities, and bare serials.
     */
    fun stripPrefix(serial: String): String = AiDexSerialIdentity.bareSerial(serial)

    private fun md5(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(data)
    }
}
