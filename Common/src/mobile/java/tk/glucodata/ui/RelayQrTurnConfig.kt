package tk.glucodata.ui

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import org.json.JSONObject

private const val MIRROR_QR_SUFFIX = " MirrorJuggluco"
private const val MAX_TURN_HOST_LENGTH = 191
private const val MAX_TURN_USERNAME_LENGTH = 95
private const val MAX_TURN_PASSWORD_LENGTH = 127
private const val MAX_ICE_HOST_LENGTH = 191
private const val DEFAULT_RENDEZVOUS_PORT = 6789

internal val MIRROR_QR_ENCODE_HINTS = mapOf(
    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
)

internal data class HybridQrTurnConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String
)

internal data class HybridQrIceConfig(
    val useTurnForStun: Boolean,
    val rendezvousHost: String,
    val rendezvousPort: Int,
    val verifyRendezvousCertificate: Boolean,
    val useLocalDiscovery: Boolean,
)

private fun exactInteger(value: Any?, error: String): Long {
    require(value is Number) { error }
    val integer = value.toLong()
    require(integer.toDouble() == value.toDouble()) { error }
    return integer
}

internal fun parseMirrorQrJson(payload: String): JSONObject {
    val json = if (payload.endsWith(MIRROR_QR_SUFFIX)) {
        payload.dropLast(MIRROR_QR_SUFFIX.length)
    } else {
        payload
    }
    return JSONObject(json)
}

internal fun parseHybridQrTurnConfig(json: JSONObject): HybridQrTurnConfig? {
    if (!json.has("turn")) return null
    require(json.optString("ICElabel", "").isNotBlank()) {
        "TURN configuration requires an ICE label"
    }
    val config = when (val turn = json.get("turn")) {
        is JSONObject -> HybridQrTurnConfig(
            turn.optString("host", "").trim(),
            turn.optInt("port", -1),
            turn.optString("username", ""),
            turn.optString("password", "")
        )
        is JSONArray -> {
            require(turn.length() == 4) { "TURN configuration is invalid" }
            HybridQrTurnConfig(
                turn.optString(0, "").trim(),
                turn.optInt(1, -1),
                turn.optString(2, ""),
                turn.optString(3, "")
            )
        }
        else -> throw IllegalArgumentException("TURN configuration is invalid")
    }
    val (host, port, username, password) = config

    require(host.isNotEmpty()) { "TURN host is missing" }
    require(port in 1..65535) { "TURN port is invalid" }
    require(host.length <= MAX_TURN_HOST_LENGTH) { "TURN host is too long" }
    require(username.length <= MAX_TURN_USERNAME_LENGTH) { "TURN username is too long" }
    require(password.length <= MAX_TURN_PASSWORD_LENGTH) { "TURN password is too long" }

    return config
}

internal fun parseHybridQrIceConfig(
    json: JSONObject,
    turnConfig: HybridQrTurnConfig?,
): HybridQrIceConfig? {
    val hasStun = json.has("stun")
    val hasRendezvous = json.has("rv")
    val hasCertificateVerification = json.has("cv")
    val hasLocalDiscovery = json.has("ld")
    if (!hasStun && !hasRendezvous && !hasCertificateVerification &&
        !hasLocalDiscovery) return null
    require(hasStun && hasRendezvous) { "ICE network configuration is incomplete" }
    require(json.optString("ICElabel", "").isNotBlank()) {
        "ICE network configuration requires an ICE label"
    }

    val useTurnForStun = json.get("stun") as? Boolean
        ?: throw IllegalArgumentException("STUN configuration is invalid")
    val verifyRendezvousCertificate = if (hasCertificateVerification) {
        json.get("cv") as? Boolean
            ?: throw IllegalArgumentException("Certificate verification configuration is invalid")
    } else {
        true
    }
    val useLocalDiscovery = if (hasLocalDiscovery) {
        json.get("ld") as? Boolean
            ?: throw IllegalArgumentException("Local discovery configuration is invalid")
    } else {
        true
    }
    require(!useTurnForStun || turnConfig != null) {
        "TURN-for-STUN requires TURN configuration"
    }

    val (host, port) = when (val rendezvous = json.get("rv")) {
        is Number -> {
            val numericPort = exactInteger(rendezvous, "Rendezvous port is invalid")
            when (numericPort) {
                0L -> "" to DEFAULT_RENDEZVOUS_PORT
                in 1L..65535L -> {
                    require(turnConfig != null) {
                        "Compact rendezvous configuration requires TURN"
                    }
                    turnConfig.host to numericPort.toInt()
                }
                else -> throw IllegalArgumentException("Rendezvous port is invalid")
            }
        }
        is JSONArray -> {
            require(rendezvous.length() == 2) { "Rendezvous configuration is invalid" }
            val explicitHost = rendezvous.optString(0, "").trim()
            require(explicitHost.isNotEmpty()) { "Rendezvous host is missing" }
            val explicitPort = exactInteger(
                rendezvous.get(1),
                "Rendezvous port is invalid",
            )
            require(explicitPort in 1L..65535L) { "Rendezvous port is invalid" }
            explicitHost to explicitPort.toInt()
        }
        else -> throw IllegalArgumentException("Rendezvous configuration is invalid")
    }

    require(host.length <= MAX_ICE_HOST_LENGTH) { "Rendezvous host is too long" }
    require(port in 1..65535) { "Rendezvous port is invalid" }
    if (host.isEmpty()) {
        require(port == DEFAULT_RENDEZVOUS_PORT) {
            "Default rendezvous configuration is invalid"
        }
    }

    return HybridQrIceConfig(
        useTurnForStun,
        host,
        port,
        verifyRendezvousCertificate,
        useLocalDiscovery,
    )
}

internal fun mirrorQrContainsTurnConfig(payload: String): Boolean =
    runCatching { parseMirrorQrJson(payload).has("turn") }.getOrDefault(false)
