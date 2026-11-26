package tk.glucodata.drivers.novo

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object NovoCrypto {

    private val BASE_KEY: ByteArray = NovoConstants.AES_KEY_HEX.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    fun decimalByte2hexString(data: ByteArray): String =
        data.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    fun deviceCodeFromName(deviceName: String?): String {
        val trimmed = deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        val tail = if (trimmed.length > 3) trimmed.substring(3) else ""
        return decimalByte2hexString(tail.toByteArray(StandardCharsets.US_ASCII)).uppercase()
    }

    fun padToBlock(data: ByteArray): ByteArray {
        val block = ByteArray(NovoConstants.BLOCK_SIZE)
        val copied = minOf(NovoConstants.BLOCK_SIZE, data.size)
        System.arraycopy(data, 0, block, 0, copied)
        if (data.size < NovoConstants.BLOCK_SIZE) {
            val padLength = NovoConstants.BLOCK_SIZE - data.size
            for (index in data.size until NovoConstants.BLOCK_SIZE) {
                block[index] = padLength.toByte()
            }
        }
        return block
    }

    fun keyWithPassword(passwordLow: Byte, passwordHigh: Byte): ByteArray {
        val key = BASE_KEY.copyOf()
        key[14] = passwordHigh
        key[15] = passwordLow
        return key
    }

    fun encrypt(data: ByteArray): ByteArray = encrypt(true, 0, 0, data)

    fun encrypt(data: ByteArray, passwordLow: Byte, passwordHigh: Byte): ByteArray =
        encrypt(false, passwordLow, passwordHigh, data)

    fun encrypt(
        useDefaultPassword: Boolean,
        passwordLow: Byte,
        passwordHigh: Byte,
        data: ByteArray,
    ): ByteArray {
        val key = if (useDefaultPassword) BASE_KEY else keyWithPassword(passwordLow, passwordHigh)
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            val full = cipher.doFinal(padToBlock(data))
            full.copyOf(NovoConstants.BLOCK_SIZE)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    fun decrypt(data: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(BASE_KEY, "AES"))
        cipher.doFinal(data)
    } catch (_: Exception) {
        ByteArray(0)
    }

    fun crc16CcittPlaceholder(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }
}
