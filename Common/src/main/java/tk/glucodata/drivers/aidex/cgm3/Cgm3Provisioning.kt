// JugglucoNG — AiDex CGM3 (G9) driver
// Cgm3Provisioning.kt — Transmitter-scoped pairing material handling.
//
// The vendor keeps TWO identities per pairing row (`TransmitterEntity`):
// the transmitter SN (BLE bond, 2-year life, `setSn` challenge + `setKey`
// session key) and the probe SN (15-day session, activation code).
// Material shapes (decompiled `PairDynamicKeyInfo`, `HistoryDeviceInfo`):
// secret/IV = 16 B each (hex-or-base64), session key = 16 B (Base64
// `deviceKey`). Resolution order in the vendor app: MMKV dynamic secret →
// entity `publicKey`/`communicationKey` fallback.
// This file is pure JVM (no Android dependency) so it stays unit-testable;
// persistence remains with the caller (cf. `AiDexProvisioningStore`).
// Full analysis: docs/aidex-g9-cgm3-protocol.md (5.5).

package tk.glucodata.drivers.aidex.cgm3

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

object Cgm3Provisioning {

    /** Required size of secret, IV and session-key material. */
    const val MATERIAL_SIZE: Int = 16

    /** Transmitter-side pairing material (F001 challenge + protocol IV). */
    data class TransmitterMaterial(
        val serial: String,
        val secret: ByteArray,
        val iv: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TransmitterMaterial) return false
            return serial == other.serial &&
                secret.contentEquals(other.secret) &&
                iv.contentEquals(other.iv)
        }

        override fun hashCode(): Int {
            var result = serial.hashCode()
            result = 31 * result + secret.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            return result
        }
    }

    /** Decode one 16-B material field (32-hex-chars or Base64). */
    fun decodeMaterial(encoded: String): ByteArray? = runCatching {
        val trimmed = encoded.trim()
        val decoded = if (
            trimmed.length == 32 &&
            trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        ) {
            trimmed.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            Base64.getDecoder().decode(trimmed)
        }
        decoded.takeIf { it.size == MATERIAL_SIZE }
    }.getOrNull()

    /** Validate already-decoded transmitter material. */
    fun validate(secret: ByteArray, iv: ByteArray): Boolean =
        secret.size == MATERIAL_SIZE && iv.size == MATERIAL_SIZE

    /** Validate a session key (`deviceKey`, 16 B). */
    fun validateSessionKey(sessionKey: ByteArray): Boolean =
        sessionKey.size == MATERIAL_SIZE

    /**
     * Storage key for one transmitter's material: `cgm3_material_` +
     * SHA-256 hex of the canonical serial (mirrors the X-family scheme in
     * `AiDexProvisioningStore`, with a distinct prefix so the two
     * generations can never collide).
     */
    fun storageKey(serial: String): String {
        val canonical = serial.trim().uppercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return "cgm3_material_" + digest.joinToString("") {
            "%02x".format(Locale.ROOT, it.toInt() and 0xFF)
        }
    }

    /**
     * Minimal `PairDynamicKeyInfo{publicKey, communicationKey, deviceSn}`
     * JSON encoding (hand-rolled to stay dependency-free).
     */
    fun encodeDynamicKey(serial: String, secret: ByteArray, iv: ByteArray): String? {
        if (!validate(secret, iv)) return null
        val enc = Base64.getEncoder()
        return "{\"${Cgm3Cloud.DynamicKeyJson.PUBLIC_KEY}\":\"${enc.encodeToString(secret)}\"," +
            "\"${Cgm3Cloud.DynamicKeyJson.COMMUNICATION_KEY}\":\"${enc.encodeToString(iv)}\"," +
            "\"${Cgm3Cloud.DynamicKeyJson.DEVICE_SN}\":\"${serial.trim()}\"}"
    }

    /**
     * Unescape a JSON string literal body (no surrounding quotes).
     * Handles the escapes real producers emit (`org.json` writes `/` as
     * `\/`, Gson writes `\uXXXX` for HTML-sensitive chars); anything
     * malformed yields null instead of silently corrupting key bytes.
     */
    fun unescapeJsonString(body: String): String? {
        if ('\\' !in body) return body
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= body.length) return null
            when (val e = body[i + 1]) {
                '"', '\\', '/' -> {
                    out.append(e)
                    i += 2
                }
                'b' -> {
                    out.append('\b')
                    i += 2
                }
                'f' -> {
                    out.append('\u000C')
                    i += 2
                }
                'n' -> {
                    out.append('\n')
                    i += 2
                }
                'r' -> {
                    out.append('\r')
                    i += 2
                }
                't' -> {
                    out.append('\t')
                    i += 2
                }
                'u' -> {
                    if (i + 5 >= body.length) return null
                    val hex = body.substring(i + 2, i + 6)
                    val code = hex.toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    i += 6
                }
                else -> return null
            }
        }
        return out.toString()
    }

    /** Decode a `PairDynamicKeyInfo` JSON blob (tolerates missing serial). */
    fun decodeDynamicKey(json: String): TransmitterMaterial? = runCatching {
        fun field(name: String): String? {
            val key = "\"$name\""
            val start = json.indexOf(key)
            if (start < 0) return null
            val colon = json.indexOf(':', start + key.length)
            if (colon < 0) return null
            // Walk the quoted body honouring backslash escapes so an
            // escaped quote cannot terminate the scan early.
            var i = json.indexOf('"', colon + 1)
            if (i < 0) return null
            i++
            val body = StringBuilder()
            var closed = false
            while (i < json.length) {
                val c = json[i]
                if (c == '\\') {
                    if (i + 1 >= json.length) return null
                    body.append(c).append(json[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') {
                    closed = true
                    break
                }
                body.append(c)
                i++
            }
            if (!closed) return null
            return unescapeJsonString(body.toString())
        }
        val secret = decodeMaterial(
            field(Cgm3Cloud.DynamicKeyJson.PUBLIC_KEY).orEmpty(),
        ) ?: return@runCatching null
        val iv = decodeMaterial(
            field(Cgm3Cloud.DynamicKeyJson.COMMUNICATION_KEY).orEmpty(),
        ) ?: return@runCatching null
        val serial = field(Cgm3Cloud.DynamicKeyJson.DEVICE_SN).orEmpty()
        TransmitterMaterial(serial, secret, iv)
    }.getOrNull()

    /**
     * Factory test-transmitter secrets baked into the vendor app
     * (`DynamicSecretManager.secretMap`; harmless test units).
     */
    val TEST_SECRETS: Map<String, Pair<String, String>> = mapOf(
        "TTTT000003" to (
            "A376886ED5ECD12868AF84F6529E91D7" to
                "D46D73F7118482AEB4D545E22A679BF3"
            ),
        "TTTT000004" to (
            "9757478DDA210F9CF93F2680B1429392" to
                "65CB5E891D344F5EA788016AF67C5C35"
            ),
        "TEST000005" to (
            "18001B094C75DD8AFF7C1910EE3E5574" to
                "C7465377D336AB6EC197152DA6E951F6"
            ),
    )

    /** Default dynamic-secret BLE-name prefixes (no cloud list needed). */
    val DEFAULT_DYNAMIC_NAMES: List<String> = listOf("AiDEX F", "AiDEX S")
}
