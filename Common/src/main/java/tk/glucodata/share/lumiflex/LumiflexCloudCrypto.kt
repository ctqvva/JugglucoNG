package tk.glucodata.share.lumiflex

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import org.json.JSONObject

object LumiflexCloudCrypto {

    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    fun encryptEnvelope(
        plainJson: String,
        publicKeyBase64: String = LumiflexCloudConstants.RSA_PUBLIC_KEY,
    ): String {
        val key = publicKey(publicKeyBase64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val blockSize = key.modulus.bitLength() / 8 - 11
        val input = plainJson.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        input.asList().chunked(blockSize).forEach { chunk ->
            output.write(cipher.doFinal(chunk.toByteArray()))
        }
        val encoded = Base64.getEncoder().encodeToString(output.toByteArray())
        return JSONObject().put("encryptData", encoded).toString()
    }

    fun decryptEnvelope(
        body: String,
        privateKeyBase64: String = LumiflexCloudConstants.RSA_PRIVATE_KEY,
    ): String? = runCatching {
        val encrypted = JSONObject(body).optString("encryptData", "")
        if (encrypted.isEmpty()) return@runCatching null
        val key = privateKey(privateKeyBase64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key)
        val blockSize = key.modulus.bitLength() / 8
        val input = Base64.getDecoder().decode(encrypted)
        require(input.size % blockSize == 0)
        val output = ByteArrayOutputStream()
        input.asList().chunked(blockSize).forEach { chunk ->
            output.write(cipher.doFinal(chunk.toByteArray()))
        }
        output.toString(Charsets.UTF_8.name())
    }.getOrNull()

    fun passwordHash(password: String): String = MessageDigest.getInstance("MD5")
        .digest(password.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }

    fun versionCheckJson(userId: String, nowMs: Long): String = JSONObject()
        .put("version", LumiflexCloudConstants.VENDOR_VERSION)
        .put("timestamp", nowMs)
        .put("userId", userId)
        .toString()

    fun versionCheckHeader(userId: String, nowMs: Long): String =
        encryptEnvelope(versionCheckJson(userId, nowMs))

    private fun publicKey(encoded: String): RSAPublicKey {
        val bytes = Base64.getDecoder().decode(encoded.filterNot { it.isWhitespace() })
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes)) as RSAPublicKey
    }

    private fun privateKey(encoded: String): RSAPrivateKey {
        val bytes = Base64.getDecoder().decode(encoded.filterNot { it.isWhitespace() })
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes)) as RSAPrivateKey
    }
}
