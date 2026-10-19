// JugglucoNG — AiDex Native Kotlin Driver
// Crc16CcittFalse.kt — CRC-16/CCITT-FALSE checksum
//
// Parameters: polynomial=0x1021, initial=0xFFFF, refIn=false, refOut=false, xorOut=0x0000.
// Used as 2-byte LE trailer on all F002 commands and responses.
//
// Verified against ALL observed F002 commands in HCI traces:
//   10 C1 F3 (STARTUP_DEVICE_INFO / post-bond 0x10): CRC-16(0x10) = 0xF3C1
//   11 E0 E3 (GET_BROADCAST_DATA): CRC-16(0x11) = 0xE3E0
//   21 B3 D5 (GET_LOCAL_START_TIME): CRC-16(0x21) = 0xD5B3
//   F2 AD 2E (DELETE_BOND):      CRC-16(0xF2) = 0x2EAD

package tk.glucodata.drivers.aidex.native.crypto

object Crc16CcittFalse {

    /**
     * Compute CRC-16/CCITT-FALSE over the given data.
     */
    fun checksum(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            for (i in 0 until 8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
            }
            crc = crc and 0xFFFF
        }
        return crc
    }

    /**
     * Build a complete F002 command with CRC-16 trailer.
     *
     * Wire format: [opcode, ...params, CRC16_lo, CRC16_hi]
     */
    fun makeCommand(opcode: Int, vararg params: Int): ByteArray {
        val payload = ByteArray(1 + params.size)
        payload[0] = opcode.toByte()
        for (i in params.indices) {
            payload[i + 1] = params[i].toByte()
        }
        val crc = checksum(payload)
        return payload + byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte()
        )
    }

    /**
     * Build a command with a u16 LE parameter.
     *
     * Wire format: [opcode, param_lo, param_hi, CRC16_lo, CRC16_hi]
     */
    fun makeCommandWithU16(opcode: Int, param: Int): ByteArray {
        return makeCommand(opcode, param and 0xFF, (param shr 8) and 0xFF)
    }

    /**
     * Validate CRC-16 trailer on an F002 response.
     *
     * Returns true if the last 2 bytes are a valid CRC-16/CCITT-FALSE
     * of all preceding bytes.
     */
    fun validateResponse(data: ByteArray): Boolean {
        if (data.size < 3) return false
        val payload = data.copyOfRange(0, data.size - 2)
        val expectedCrc = checksum(payload)
        val actualCrc = (data[data.size - 2].toInt() and 0xFF) or
                ((data[data.size - 1].toInt() and 0xFF) shl 8)
        return expectedCrc == actualCrc
    }
}
