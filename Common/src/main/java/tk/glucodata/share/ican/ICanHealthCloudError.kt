package tk.glucodata.share.ican

import org.json.JSONObject

sealed class ICanHealthCloudError {

    data class Unauthorized(val message: String = "") : ICanHealthCloudError()

    data class ActivateAccount(val message: String = "") : ICanHealthCloudError()

    data class Transport(val code: Int, val message: String = "") : ICanHealthCloudError()

    data class Decrypt(val message: String = "") : ICanHealthCloudError()

    enum class Kind {
        BAD_CREDENTIALS,
        LOCKED,
        UNAUTHORIZED,
        ACTIVATE_ACCOUNT,
        TRANSIENT,
        OTHER,
    }

    companion object {

        private const val MESSAGE_LIMIT = 160

        private const val TRANSPORT_FAILURE_CODE = -1

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_LOCKED = 423
        private const val HTTP_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500

        private const val BODY_CODE_ACTIVATE_ACCOUNT = 30

        private const val BODY_CODE_BAD_CREDENTIALS = 13

        private const val BODY_CODE_UNAUTHORIZED = 401

        private val LOCK_PATTERNS = listOf(
            Regex("\\block(ed|out)?\\b", RegexOption.IGNORE_CASE),
            Regex("too many attempts?", RegexOption.IGNORE_CASE),
            Regex("max attempts?", RegexOption.IGNORE_CASE),
            Regex("заблокир", RegexOption.IGNORE_CASE),
            Regex("блокиров", RegexOption.IGNORE_CASE),
            Regex("锁定"),
            Regex("被锁"),
        )

        fun classify(code: Int, body: String?): Kind {
            val parsed = jsonOf(body)
            val bodyCode = codeOf(parsed)
            return when {
                code == HTTP_UNAUTHORIZED || bodyCode == BODY_CODE_UNAUTHORIZED -> Kind.UNAUTHORIZED
                bodyCode == BODY_CODE_ACTIVATE_ACCOUNT -> Kind.ACTIVATE_ACCOUNT
                bodyCode == BODY_CODE_BAD_CREDENTIALS -> Kind.BAD_CREDENTIALS
                code == TRANSPORT_FAILURE_CODE -> Kind.TRANSIENT
                code in HTTP_SERVER_ERROR..599 -> Kind.TRANSIENT
                code == HTTP_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS -> Kind.TRANSIENT
                code == HTTP_LOCKED -> Kind.LOCKED
                isLocked(parsed, body) -> Kind.LOCKED
                else -> Kind.OTHER
            }
        }

        fun message(body: String?): String {
            val text = messageOf(jsonOf(body))
            if (text.isNotEmpty()) return text
            return body.orEmpty().trim().take(MESSAGE_LIMIT)
        }

        private fun isLocked(parsed: JSONObject?, body: String?): Boolean {
            val text = messageOf(parsed).ifEmpty { body.orEmpty() }
            return LOCK_PATTERNS.any { it.containsMatchIn(text) }
        }

        private fun codeOf(parsed: JSONObject?): Int? {
            if (parsed == null) return null
            val raw = parsed.opt("code")
            if (raw is Number) return raw.toInt()
            if (raw is String) return raw.trim().toIntOrNull()
            return null
        }

        private fun messageOf(parsed: JSONObject?): String {
            if (parsed == null) return ""
            val raw = parsed.opt("msg") ?: parsed.opt("message")
            if (raw == null || raw == JSONObject.NULL) return ""
            return raw.toString().trim()
        }

        private fun jsonOf(body: String?): JSONObject? {
            val trimmed = body?.trim().orEmpty()
            if (!trimmed.startsWith("{")) return null
            return runCatching { JSONObject(trimmed) }.getOrNull()
        }
    }
}

fun classify(code: Int, body: String?): ICanHealthCloudError.Kind =
    ICanHealthCloudError.classify(code, body)

fun message(body: String?): String = ICanHealthCloudError.message(body)
