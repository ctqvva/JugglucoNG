package tk.glucodata.share.ican

import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.json.JSONTokener

object ICanHealthCloudJson {

    private const val AES_TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
    private const val PEM_HEADER = "-----BEGIN PUBLIC KEY-----"
    private const val PEM_FOOTER = "-----END PUBLIC KEY-----"
    private const val RANDOM_STRING_LENGTH = 32
    private const val IV_LENGTH = 16
    private const val NULL_LITERAL = "null"

    private val random = SecureRandom()

    fun getRandomString(): String {
        val alphabet = ICanHealthCloudConstants.GET_RANDOM_STRING_ALPHABET
        val builder = StringBuilder(RANDOM_STRING_LENGTH)
        repeat(RANDOM_STRING_LENGTH) {
            builder.append(alphabet[random.nextInt(alphabet.length)])
        }
        return builder.toString()
    }

    fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun base64UrlDecode(encoded: String): ByteArray {
        val normalized = encoded
            .filter { !it.isWhitespace() }
            .trimEnd('=')
            .replace('+', '-')
            .replace('/', '_')
        return Base64.getUrlDecoder().decode(normalized)
    }

    fun aesEncrypt(key: String, plaintext: String): String {
        val keyBytes = aesKeyBytes(key)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivFor(keyBytes)),
        )
        return base64UrlEncode(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun aesDecrypt(key: String, ciphertext: String): String {
        val keyBytes = aesKeyBytes(key)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivFor(keyBytes)),
        )
        return String(cipher.doFinal(base64UrlDecode(ciphertext)), Charsets.UTF_8)
    }

    fun rsaEncrypt(
        key: String,
        publicKeyPem: String = ICanHealthCloudConstants.RSA_PUBLIC_KEY,
    ): String {
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey(publicKeyPem))
        return base64UrlEncode(cipher.doFinal(key.toByteArray(Charsets.UTF_8)))
    }

    fun createEncryptedRequest(
        payload: Map<String, Any?>,
        key: String = getRandomString(),
    ): Map<String, String> {
        val data = aesEncrypt(key, JSONObject(payload).toString())
        return linkedMapOf("data" to data, "key" to rsaEncrypt(key))
    }

    fun authBody(account: String, password: String): String = JSONObject()
        .put("grant_type", "password")
        .put("scope", "all")
        .put("type", "")
        .put("thirdAuth", "")
        .put("country", ICanHealthCloudConstants.COUNTRY)
        .put("ids", "")
        .put("tenantId", "")
        .put("phone_num", account)
        .put("password", password)
        .put("thirdAuthKey", "")
        .toString()

    fun parseJsonString(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        val parsed = runCatching {
            when (val value = JSONTokener(trimmed).nextValue()) {
                null -> ""
                JSONObject.NULL -> ""
                is String -> value
                else -> value.toString()
            }
        }.getOrDefault(trimmed.removeSurrounding("\""))
        return if (parsed.equals(NULL_LITERAL, ignoreCase = true)) "" else parsed
    }

    private fun aesKeyBytes(key: String): ByteArray {
        val bytes = key.toByteArray(Charsets.UTF_8)
        require(bytes.size == 16 || bytes.size == 24 || bytes.size == 32) {
            "AES key must be 16, 24 or 32 bytes, was ${bytes.size}"
        }
        return bytes
    }

    private fun ivFor(keyBytes: ByteArray): ByteArray = keyBytes.copyOfRange(0, IV_LENGTH)

    private fun rsaPublicKey(pem: String): PublicKey {
        val body = pem
            .replace(PEM_HEADER, "")
            .replace(PEM_FOOTER, "")
            .filter { !it.isWhitespace() }
        val der = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }
}
