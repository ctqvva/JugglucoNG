package tk.glucodata.ui

import org.json.JSONArray
import org.json.JSONObject

private const val MIRROR_QR_SUFFIX = " MirrorJuggluco"
private const val MAX_TURN_HOST_LENGTH = 191
private const val MAX_TURN_USERNAME_LENGTH = 95
private const val MAX_TURN_PASSWORD_LENGTH = 127

internal data class HybridQrTurnConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String
)

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

internal fun mirrorQrContainsTurnConfig(payload: String): Boolean =
    runCatching { parseMirrorQrJson(payload).has("turn") }.getOrDefault(false)
