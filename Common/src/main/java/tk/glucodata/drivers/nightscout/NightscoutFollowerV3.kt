package tk.glucodata.drivers.nightscout

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * v3 support for follower mode: endpoint shapes, the response envelope, and the bearer token.
 *
 * The uploader's token cache in NightPost cannot be reused here. It mints its token from the
 * uploader's own URL and secret and keeps it in a single field with no record of which host it
 * came from, so a follower pointing at a different server would be handed a JWT signed by the
 * wrong one. This keeps its own cache, keyed by the credentials it was minted from.
 */
internal object NightscoutFollowerV3 {
    /** v3 pages with `limit` and has no `.json` suffix; `sort$desc=date` gives newest first. */
    private const val SORT_NEWEST = "sort%24desc=date"

    fun entriesUrl(baseUrl: String, count: Int, type: String, gteMillis: Long?, ltMillis: Long?): String {
        val parameters = mutableListOf("limit=$count", SORT_NEWEST, "type%24eq=$type")
        // Verified against a live instance: type$eq filters rather than being ignored.
        if (gteMillis != null) parameters += "date%24gte=$gteMillis"
        if (ltMillis != null) parameters += "date%24lt=$ltMillis"
        return "$baseUrl/api/v3/entries?${parameters.joinToString("&")}"
    }

    fun treatmentsUrl(baseUrl: String, count: Int): String =
        "$baseUrl/api/v3/treatments?limit=$count&$SORT_NEWEST&fields=_all"

    fun deviceStatusUrl(baseUrl: String, count: Int): String =
        "$baseUrl/api/v3/devicestatus?limit=$count&$SORT_NEWEST&fields=_all"

    /**
     * v1 answers with the document array itself, v3 wraps it in {status, result}. Every parser
     * downstream expects an array, so the envelope comes off here. A body that is already an
     * array passes through, so a host answering the v1 shape still works.
     */
    fun arrayBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("[")) return trimmed
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching { JSONObject(trimmed).optJSONArray("result")?.toString() }
            .getOrNull()
            ?: "[]"
    }

    /**
     * The server's own words when it has any. A refused read names the permission it wanted
     * ("Missing permission api:entries:read"), which is the difference between an actionable
     * failure and a bare status code.
     */
    fun serverMessage(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed.take(160)
        return runCatching {
            JSONObject(trimmed).let { json ->
                sequenceOf("message", "description")
                    .map { json.optString(it).trim() }
                    .firstOrNull { it.isNotEmpty() }
            }
        }.getOrNull() ?: trimmed.take(160)
    }

    /** A cached bearer token, remembering which credentials produced it. */
    private data class CachedToken(val url: String, val secret: String, val header: String, val expiresAt: Long)

    @Volatile
    private var cached: CachedToken? = null

    /** Clock skew guard: stop trusting the token slightly before the server does. */
    private const val EXPIRY_MARGIN_MILLIS = 60_000L

    @Synchronized
    fun clearToken() {
        cached = null
    }

    /**
     * Authorization header for a v3 read, exchanging the access token for a bearer JWT and
     * caching it until it expires. Returns null when the secret is already a header value or
     * no token could be had, so the caller falls back to [NightscoutFollowerRegistry.applyAuth]
     * rather than sending something it knows is wrong.
     */
    @Synchronized
    fun authorizationHeader(baseUrl: String, secret: String, nowMillis: Long): String? {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return null
        // Already a header value: applyAuth handles these, and there is nothing to exchange.
        if (trimmed.startsWith("Bearer ", ignoreCase = true) ||
            trimmed.startsWith("token=", ignoreCase = true) ||
            isSha1Hex(trimmed)
        ) {
            return null
        }
        cached?.let { hit ->
            if (hit.url == baseUrl && hit.secret == trimmed && nowMillis < hit.expiresAt) {
                return hit.header
            }
        }
        val fetched = requestToken(baseUrl, trimmed, nowMillis) ?: return null
        cached = fetched
        return fetched.header
    }

    private fun requestToken(baseUrl: String, secret: String, nowMillis: Long): CachedToken? {
        val encoded = URLEncoder.encode(secret, Charsets.UTF_8.name())
        val connection = (URL("$baseUrl/api/v2/authorization/request/$encoded").openConnection()
            as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JugglucoNG Nightscout follower")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val token = json.optString("token").trim()
            if (token.isEmpty()) return null
            // exp is seconds since the epoch, as in the uploader's own exchange.
            val expSeconds = json.optLong("exp", 0L)
            val expiresAt = if (expSeconds > 0L) {
                expSeconds * 1000L - EXPIRY_MARGIN_MILLIS
            } else {
                nowMillis + 30 * 60_000L
            }
            return CachedToken(baseUrl, secret, "Bearer $token", expiresAt)
        } catch (_: Throwable) {
            return null
        } finally {
            connection.disconnect()
        }
    }

    // Character loop rather than Regex: this runs on the follower HandlerThread, where the ICU
    // matcher has been implicated in a native heap corruption (see NightscoutFollowerRegistry).
    private fun isSha1Hex(s: String): Boolean {
        if (s.length != 40) return false
        return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
