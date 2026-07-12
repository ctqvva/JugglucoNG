package tk.glucodata.drivers.ottai

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object OttaiCrypto {

    private const val AES_ECB_PKCS5 = "AES/ECB/PKCS5Padding"
    private const val AES_ECB_NOPADDING = "AES/ECB/NoPadding"
    private const val AES_ALG = "AES"

    // ---- Cloud secret chain (AES/ECB/PKCS5) ----

    fun decryptKeyA(keyABase64: String, glucoseSecretKey: String, produceTimeMs: Long, mac: String): String {
        val key = OttaiConstants.deriveFieldKey(glucoseSecretKey, produceTimeMs, mac)
        return aesEcbPkcs5DecryptBase64(keyABase64, key)
    }

    fun decryptMethod(methodBase64: String, glucoseSecretKey: String, methodUpdateTimeMs: Long, mac: String): String {
        val key = OttaiConstants.deriveMethodKey(glucoseSecretKey, methodUpdateTimeMs, mac)
        return aesEcbPkcs5DecryptBase64(methodBase64, key)
    }

    fun decryptCoefficient(coefficientBase64: String, glucoseSecretKey: String, coeffUpdateTimeMs: Long, mac: String): String {
        val key = OttaiConstants.deriveCoefficientKey(glucoseSecretKey, coeffUpdateTimeMs, mac)
        return aesEcbPkcs5DecryptBase64(coefficientBase64, key)
    }

    fun splitKeyA(keyAPlaintext: String): List<ByteArray> {
        require(keyAPlaintext.length == OttaiConstants.AUTH_KEY_HEX_LEN) {
            "keyA plaintext must be ${OttaiConstants.AUTH_KEY_HEX_LEN} hex chars, got ${keyAPlaintext.length}"
        }
        return keyAPlaintext.chunked(OttaiConstants.AUTH_KEY_BYTES * 2).map { hexToBytes(it) }
    }

    // ---- BLE payload (AES/ECB/ZeroBytePadding) ----

    fun decryptPayload(ciphertext: ByteArray, sessionKeyHex: String): ByteArray {
        val keyBytes = hexToBytes(sessionKeyHex)
        val cipher = Cipher.getInstance(AES_ECB_NOPADDING)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, AES_ALG))
        val decrypted = cipher.doFinal(ciphertext)
        return stripTrailingZeros(decrypted)
    }

    fun encryptActivateCmd(data: ByteArray, sessionKeyHex: String): ByteArray {
        val keyBytes = hexToBytes(sessionKeyHex)
        val padded = zeroPad16(data)
        val cipher = Cipher.getInstance(AES_ECB_NOPADDING)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, AES_ALG))
        return cipher.doFinal(padded)
    }

    fun zeroPad16(data: ByteArray): ByteArray {
        if (data.size >= 16) return data.copyOf(16)
        return data + ByteArray(16 - data.size)
    }

    private fun stripTrailingZeros(data: ByteArray): ByteArray {
        var end = data.size
        while (end >= 8 && data[end - 8] == 0.toByte() &&
            (end - 8 until end).all { data[it] == 0.toByte() }
        ) {
            end -= 8
        }
        return data.copyOf(end)
    }

    // ---- MD5 signature ----

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun signApiToken(deviceId: String, timestampMs: Long): String {
        val input = "${OttaiConstants.SIGN_APP_PREFIX}${OttaiConstants.SIGN_APP_PREFIX}:a:$deviceId$timestampMs${OttaiConstants.SIGN_SEED}"
        return md5Hex(input)
    }

    fun signSmsCode(deviceId: String, timestampMs: Long, phone: String, apiToken: String): String {
        val input = "${OttaiConstants.SIGN_APP_PREFIX}${OttaiConstants.SIGN_APP_PREFIX}:a:$deviceId$timestampMs${phone}${apiToken}${OttaiConstants.SIGN_SEED}"
        return md5Hex(input)
    }

    fun signSmsLogin(deviceId: String, timestampMs: Long, requestId: String, phone: String, code: String): String {
        val input = "${OttaiConstants.SIGN_APP_PREFIX}${OttaiConstants.SIGN_APP_PREFIX}:a:$deviceId$timestampMs${requestId}${phone}${code}${OttaiConstants.SIGN_SEED}"
        return md5Hex(input)
    }

    fun signValidateDevice(deviceId: String, timestampMs: Long, mac: String): String {
        val input = "${OttaiConstants.SIGN_APP_PREFIX}${OttaiConstants.SIGN_APP_PREFIX}:a:$deviceId$timestampMs${mac}${OttaiConstants.SIGN_SEED}"
        return md5Hex(input)
    }

    fun signAccountLogin(deviceId: String, timestampMs: Long, apiToken: String, account: String, password: String): String {
        val input = "${OttaiConstants.SIGN_APP_PREFIX}${OttaiConstants.SIGN_APP_PREFIX}:a:$deviceId$timestampMs${apiToken}${account}${password}${OttaiConstants.SIGN_SEED}"
        return md5Hex(input)
    }

    // ---- SHA-256 ----

    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    fun sha256Hex(data: ByteArray): String {
        return sha256(data).joinToString("") { "%02x".format(it) }
    }

    // ---- Util ----

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val result = ByteArray(len / 2)
        for (i in result.indices) {
            result[i] = ((hex[i * 2].digitToIntOrNull(16) ?: 0) shl 4 or
                (hex[i * 2 + 1].digitToIntOrNull(16) ?: 0)).toByte()
        }
        return result
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun aesEcbPkcs5DecryptBase64(base64: String, keyBytes: ByteArray): String {
        val ciphertext = Base64.decode(base64, Base64.DEFAULT)
        val cipher = Cipher.getInstance(AES_ECB_PKCS5)
        val secretKey = SecretKeySpec(keyBytes.copyOf(32.coerceAtMost(keyBytes.size)), AES_ALG)
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
