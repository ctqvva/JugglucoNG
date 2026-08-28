package tk.glucodata.ui

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
    val turn = json.optJSONObject("turn") ?: return null
    require(json.optString("ICElabel", "").isNotBlank()) {
        "TURN configuration requires an ICE label"
    }
    val host = turn.optString("host", "").trim()
    val port = turn.optInt("port", -1)
    val username = turn.optString("username", "")
    val password = turn.optString("password", "")

    require(host.isNotEmpty()) { "TURN host is missing" }
    require(port in 1..65535) { "TURN port is invalid" }
    require(host.length <= MAX_TURN_HOST_LENGTH) { "TURN host is too long" }
    require(username.length <= MAX_TURN_USERNAME_LENGTH) { "TURN username is too long" }
    require(password.length <= MAX_TURN_PASSWORD_LENGTH) { "TURN password is too long" }

    return HybridQrTurnConfig(host, port, username, password)
}

internal fun mirrorQrContainsTurnConfig(payload: String): Boolean =
    runCatching { parseMirrorQrJson(payload).has("turn") }.getOrDefault(false)
