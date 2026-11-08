package tk.glucodata.share.lumiflex

import org.json.JSONObject

object LumiflexCloudError {

    enum class Kind {
        BAD_CREDENTIALS,
        UNAUTHORIZED,
        TRANSIENT,
        OTHER,
    }

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_TIMEOUT = 408
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR = 500
    private const val TRANSPORT_FAILURE_CODE = -1
    private const val MESSAGE_LIMIT = 160

    /**
     * Vendor application code for "this account has been logged in from other equipment":
     * another login displaced the session, so the stored token is dead.
     */
    private const val VENDOR_SESSION_REPLACED = 802

    fun classify(code: Int, body: String?): Kind = when {
        code in setOf(HTTP_UNAUTHORIZED, 800, 801, VENDOR_SESSION_REPLACED) -> Kind.UNAUTHORIZED
        bodyCode(body) in setOf(HTTP_UNAUTHORIZED, 800, 801, VENDOR_SESSION_REPLACED) ->
            Kind.UNAUTHORIZED
        code == TRANSPORT_FAILURE_CODE -> Kind.TRANSIENT
        code in HTTP_SERVER_ERROR..599 -> Kind.TRANSIENT
        code == HTTP_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS -> Kind.TRANSIENT
        else -> Kind.OTHER
    }

    fun message(body: String?): String {
        val parsed = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
        val text = parsed?.optString("msg", "").orEmpty()
            .ifBlank { parsed?.optString("message", "").orEmpty() }
        if (parsed != null) return text.take(MESSAGE_LIMIT)
        return body.orEmpty().trim().take(MESSAGE_LIMIT)
    }

    private fun bodyCode(body: String?): Int? {
        val parsed = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return null
        val raw = parsed.opt("code")
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }
}
