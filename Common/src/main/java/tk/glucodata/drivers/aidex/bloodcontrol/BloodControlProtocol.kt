package tk.glucodata.drivers.aidex.bloodcontrol

import org.json.JSONObject
import tk.glucodata.drivers.aidex.AiDexCnProtocol
import tk.glucodata.drivers.aidex.AiDexProvisionedKeys
import tk.glucodata.drivers.aidex.AiDexSerialIdentity
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class BloodControlCredentials(
    val bearer: String,
    val aes: ByteArray,
    val signingKey: PrivateKey,
    val serverKey: PublicKey,
) {
    companion object {
        fun decode(bearer: String, aes: String, signing: String, server: String): BloodControlCredentials? =
            runCatching {
                require(bearer.isNotBlank() && !bearer.contains('\n') && !bearer.contains('\r'))
                val decoder = Base64.getDecoder()
                val aesBytes = decoder.decode(aes).also { require(it.size == 32) }
                val rsa = KeyFactory.getInstance("RSA")
                BloodControlCredentials(bearer, aesBytes,
                    rsa.generatePrivate(PKCS8EncodedKeySpec(decoder.decode(signing))),
                    rsa.generatePublic(X509EncodedKeySpec(decoder.decode(server))))
            }.getOrNull()
    }
}

/** All signatures cover the exact bytes on the wire; no credentials or bodies are logged. */
internal object BloodControlProtocol {
    private fun pss() = Signature.getInstance("RSASSA-PSS").apply {
        setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
    }

    fun timestamp(now: Long): String = Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.SECONDS).toString()

    fun normalizeSerial(value: String): String? {
        val bare = AiDexSerialIdentity.bareSerial(value).uppercase(Locale.ROOT)
        return bare.takeIf { it.matches(Regex("[A-Z0-9]{8,14}")) }
    }

    fun normalizeAllowlistEntry(value: String): String {
        val upper = value.uppercase(Locale.ROOT).trim()
        val prefix = listOf("SIBIONICS2:", "SIBIONICS3:", "SIBIONICS:", "AIDEX:", "ICAN:", "LIBRE2:")
            .firstOrNull(upper::startsWith)
        return (prefix?.let(upper::removePrefix) ?: upper).replace(Regex("[-: ]"), "")
    }

    fun plaintext(deviceId: String, timestamp: String, serial: String? = null): ByteArray =
        JSONObject().apply {
            if (serial == null) put("deviceID", deviceId)
            else { put("serialNumber", serial); put("deviceId", deviceId) }
            put("requestedAt", timestamp)
        }.toString().toByteArray(Charsets.UTF_8)

    fun wrap(plaintext: ByteArray, timestamp: String, credentials: BloodControlCredentials): ByteArray {
        val signature = pss().apply { initSign(credentials.signingKey); update(plaintext) }.sign()
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(credentials.aes, "AES"), GCMParameterSpec(128, iv))
        }
        val encrypted = cipher.doFinal(plaintext)
        val base64 = Base64.getEncoder()
        val envelope = JSONObject()
            .put("ivBase64", base64.encodeToString(iv))
            .put("ciphertextBase64", base64.encodeToString(encrypted.copyOfRange(0, encrypted.size - 16)))
            .put("tagBase64", base64.encodeToString(encrypted.takeLast(16).toByteArray()))
        return JSONObject()
            .put("clientId", "android-client")
            .put("signatureBase64", base64.encodeToString(signature))
            .put("envelopeBase64", base64.encodeToString(envelope.toString().toByteArray(Charsets.UTF_8)))
            .put("requestId", UUID.randomUUID().toString())
            .put("timestampUtc", timestamp)
            .put("nonce", UUID.randomUUID().toString().replace("-", ""))
            .toString().toByteArray(Charsets.UTF_8)
    }

    fun verify(body: ByteArray, header: String?, key: PublicKey): Boolean = runCatching {
        if (header.isNullOrBlank()) return false
        pss().apply { initVerify(key); update(body) }.verify(Base64.getDecoder().decode(header))
    }.getOrDefault(false)

    fun parseKeys(json: JSONObject, serial: String): AiDexProvisionedKeys? {
        if (json.has("serialNumber") && normalizeSerial(json.optString("serialNumber")) != serial) return null
        val secret = AiDexCnProtocol.decodeMaterial(json.optString("publicKey")) ?: return null
        val iv = AiDexCnProtocol.decodeMaterial(json.optString("communicationKey")) ?: return null
        return AiDexProvisionedKeys(secret, iv)
    }
}
