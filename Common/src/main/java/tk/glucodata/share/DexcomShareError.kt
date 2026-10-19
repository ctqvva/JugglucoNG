package tk.glucodata.share

import org.json.JSONObject

/**
 * Classifies a failed Share response so the uploader knows whether retrying can help.
 *
 * The distinction that matters most is [Kind.BAD_CREDENTIALS] / [Kind.LOCKED]: Dexcom locks an
 * account after repeated failed logins, and that lock also breaks the user's real Dexcom apps.
 * Those two kinds are therefore never retried automatically — not once, not with backoff.
 */
object DexcomShareError {

    enum class Kind {
        /** The session is gone; log in once and retry the request. */
        SESSION_EXPIRED,

        /** The account or password is wrong. Stop until the user edits them or presses Test. */
        BAD_CREDENTIALS,

        /** Dexcom locked the account after too many failed logins. Same handling as bad credentials. */
        LOCKED,

        /** A server or transport problem. Keep the queue and try again later. */
        TRANSIENT,

        /** The request itself will never succeed. Drop the batch and record it. */
        REJECTED
    }

    private const val MESSAGE_LIMIT = 160

    /** 408 Request Timeout and 429 Too Many Requests. */
    private val TEMPORARY_CLIENT_CODES = setOf(408, 429)
    private const val SSO_INTERNAL_ERROR = "SSO_InternalError"

    private val SESSION_CODES = setOf("SessionIdNotFound", "SessionNotValid")

    private val CREDENTIAL_CODES = setOf(
        "AccountPasswordInvalid",
        "SSO_AuthenticatePasswordInvalid",
        "SSO_AuthenticateAccountNotFound"
    )

    /** Codes this table knows about; an unrecognised one on HTTP 500 means "log in again". */
    private val KNOWN_CODES = SESSION_CODES + CREDENTIAL_CODES + SSO_INTERNAL_ERROR

    fun classify(httpCode: Int, body: String?): Kind {
        val code = codeOf(body)
        if (code != null) {
            when {
                code in SESSION_CODES -> return Kind.SESSION_EXPIRED
                code in CREDENTIAL_CODES -> return Kind.BAD_CREDENTIALS
                // Dexcom reports some password problems through its SSO layer instead.
                code == SSO_INTERNAL_ERROR && messageOf(body).contains("password", ignoreCase = true) ->
                    return Kind.BAD_CREDENTIALS
                // "Exceeed" is Dexcom's own spelling; match the meaning, not the typo.
                code.contains("Locked", ignoreCase = true) ||
                    code.contains("MaxAttempts", ignoreCase = true) -> return Kind.LOCKED
                // A recognised code that means none of the above: only the HTTP class is left.
                code in KNOWN_CODES -> return if (httpCode in 400..499) Kind.REJECTED else Kind.TRANSIENT
            }
        }
        return when {
            // xDrip treats any 500 as "the session died"; the uploader allows exactly one
            // re-login per flush, so an endless 500 cannot turn into a login loop.
            httpCode == 500 -> Kind.SESSION_EXPIRED
            // A timeout or a rate limit says "later", not "never": rejecting would drop the batch
            // and remember it as delivered, so history could not bring it back.
            httpCode in TEMPORARY_CLIENT_CODES -> Kind.TRANSIENT
            httpCode in 400..499 -> Kind.REJECTED
            else -> Kind.TRANSIENT
        }
    }

    /** Dexcom's own sentence when it has one, else its code, else the head of the body. */
    fun message(body: String?): String {
        val parsed = jsonOf(body)
        val message = parsed?.optString("Message").orEmpty().trim()
        if (message.isNotEmpty()) return message
        val code = parsed?.optString("Code").orEmpty().trim()
        if (code.isNotEmpty()) return code
        return body.orEmpty().trim().take(MESSAGE_LIMIT)
    }

    private fun codeOf(body: String?): String? =
        jsonOf(body)?.optString("Code").orEmpty().trim().takeIf { it.isNotEmpty() }

    private fun messageOf(body: String?): String = jsonOf(body)?.optString("Message").orEmpty()

    private fun jsonOf(body: String?): JSONObject? {
        val trimmed = body?.trim().orEmpty()
        if (!trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }
}
