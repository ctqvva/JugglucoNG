package tk.glucodata.drivers.aidex

import java.util.Base64
import java.util.Locale
import org.json.JSONObject

/** Portable, explicit export format for one sensor's pairing material. */
internal object AiDexPairingMaterialFile {
    private const val FORMAT = "juggluco-aidex-pairing-material"
    private const val VERSION = 1

    data class Parsed(
        val serial: String,
        val secret: ByteArray,
        val iv: ByteArray,
    )

    fun normalizeSerial(raw: String): String? {
        val bare = AiDexSerialIdentity.bareSerial(raw).trim().uppercase(Locale.ROOT)
        return bare.takeIf { it.length in 8..14 && it.all(Char::isLetterOrDigit) }
    }

    fun encode(serial: String, secret: ByteArray, iv: ByteArray): String? {
        val normalized = normalizeSerial(serial) ?: return null
        if (secret.size != 16 || iv.size != 16) return null
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("sensorSerial", normalized)
            .put("publicKey", Base64.getEncoder().encodeToString(secret))
            .put("communicationKey", Base64.getEncoder().encodeToString(iv))
            .toString(2)
    }

    fun decode(jsonText: String): Parsed? = runCatching {
        val json = JSONObject(jsonText)
        val format = json.optString("format")
        if (format.isNotBlank() && format != FORMAT) return@runCatching null
        val version = json.optInt("version", VERSION)
        if (version != VERSION) return@runCatching null
        val serial = normalizeSerial(
            json.optString("sensorSerial")
                .ifBlank { json.optString("deviceSn") }
                .ifBlank { json.optString("serial") },
        ) ?: return@runCatching null
        val secret = AiDexCnProtocol.decodeMaterial(
            json.optString("publicKey").ifBlank { json.optString("secret") },
        ) ?: return@runCatching null
        val iv = AiDexCnProtocol.decodeMaterial(
            json.optString("communicationKey").ifBlank { json.optString("iv") },
        ) ?: return@runCatching null
        Parsed(serial, secret, iv)
    }.getOrNull()
}
