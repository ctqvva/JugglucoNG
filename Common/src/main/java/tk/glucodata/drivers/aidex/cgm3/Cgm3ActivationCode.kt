// JugglucoNG — AiDex CGM3 (G9) driver
// Cgm3ActivationCode.kt — Probe activation-code codec.
//
// Recovered from decompiled `com.microtech.aidexx.ui.pair.CGMPairUtil`
// (v1.16.1): `encodeActivationCode`, `decodeActivationCode`,
// `byteToActivationChar`, `convertCrcToFourChars`, `crc16Ccitt`, `fCalCharset`.
// A probe activation code is 20 chars: 16 data chars + 4 CRC chars.
// Only the first 16 chars carry payload (`decodeActivationCode` is applied to
// `code.substring(0, 16)` before `newSensorOnline`).
// Full analysis: docs/aidex-g9-cgm3-protocol.md (5.5).

package tk.glucodata.drivers.aidex.cgm3

object Cgm3ActivationCode {

    /** CRC alphabet for `convertCrcToFourChars` (index 0 == 'V' == zero pad). */
    const val CAL_CHARSET: String = "VAPFWGUZYQ832JTRCMH69KNE5D4S7BX"

    /** Data payload length of a decoded activation code. */
    const val DATA_LENGTH: Int = 16

    /** CRC suffix length. */
    const val CRC_LENGTH: Int = 4

    /** Full activation-code length. */
    const val CODE_LENGTH: Int = DATA_LENGTH + CRC_LENGTH

    /**
     * Map one payload value to its code character.
     * 0–9 → '0'–'9', 10–35 → 'A'–'Z', anything else passes through raw.
     */
    fun byteToActivationChar(value: Int): Char {
        val v = value and 0xFF
        return when {
            v in 0..9 -> ('0' + v)
            v in 10..35 -> ('A' + (v - 10))
            else -> v.toChar()
        }
    }

    /**
     * CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, MSB-first, no reflection),
     * exact port of the vendor `crc16Ccitt` bit loop.
     */
    fun crc16Ccitt(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            val byte = b.toInt() and 0xFF
            for (bit in 7 downTo 0) {
                val top = (crc shr 15) and 1
                crc = crc shl 1
                if (((byte shr bit) and 1) xor top == 1) {
                    crc = crc xor 0x1021
                }
            }
        }
        return crc and 0xFFFF
    }

    /**
     * Encode a CRC value as 4 charset chars (little-endian base-32 digits,
     * reversed to big-endian, zero-padded with 'V').
     */
    fun convertCrcToFourChars(crc: Int): String {
        val base = CAL_CHARSET.length
        val sb = StringBuilder()
        var rest = crc and 0xFFFF
        while (rest > 0) {
            sb.append(CAL_CHARSET[rest % base])
            rest /= base
        }
        while (sb.length < CRC_LENGTH) {
            sb.append('V')
        }
        return sb.reverse().toString()
    }

    /**
     * Encode 16 payload bytes to a 20-char activation code. Returns null
     * unless the payload is exactly [DATA_LENGTH] bytes.
     *
     * Note: the CRC is computed over the US-ASCII bytes of the 16-char prefix,
     * mirroring `string.getBytes(US_ASCII)`.
     */
    fun encode(data: ByteArray): String? {
        if (data.size != DATA_LENGTH) return null
        val prefix = buildString {
            for (b in data) append(byteToActivationChar(b.toInt() and 0xFF))
        }
        val ascii = prefix.toByteArray(Charsets.US_ASCII)
        return prefix + convertCrcToFourChars(crc16Ccitt(ascii))
    }

    /**
     * Decode one code character to its payload value: '0'–'9' → 0–9,
     * 'A'–'Z'/'a'–'z' → 10–35 (case-insensitive), anything else → raw code.
     */
    fun decodeChar(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        in 'a'..'z' -> c - 'a' + 10
        else -> c.code
    }

    /**
     * Decode the payload of an activation code (first [DATA_LENGTH] chars).
     * Returns null when the code is too short.
     */
    fun decode(code: String): ByteArray? {
        if (code.length < DATA_LENGTH) return null
        return ByteArray(DATA_LENGTH) { i -> decodeChar(code[i]).toByte() }
    }

    /**
     * Verify the 4-char CRC suffix of a full 20-char code. Returns false for
     * malformed codes (wrong length); the payload check itself is
     * [decode] + [encode]-round-trip territory for values 0–35.
     */
    fun verify(code: String): Boolean {
        if (code.length != CODE_LENGTH) return false
        val prefix = code.substring(0, DATA_LENGTH)
        val ascii = prefix.toByteArray(Charsets.US_ASCII)
        return code.substring(DATA_LENGTH) == convertCrcToFourChars(crc16Ccitt(ascii))
    }
}
