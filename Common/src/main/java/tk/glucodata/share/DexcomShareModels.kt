package tk.glucodata.share

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Where Dexcom's Share service lives. The publisher API is regional, and an account only
 * exists on the region it was created in — there is no way to discover which one.
 */
enum class DexcomShareRegion(val baseUrl: String) {
    US("https://share2.dexcom.com/ShareWebServices/Services/"),
    OUS("https://shareous1.dexcom.com/ShareWebServices/Services/")
}

/**
 * One reading as Dexcom wants it: a millisecond timestamp (floored to whole seconds on the
 * wire), a whole mg/dL value, and the Dexcom trend ordinal.
 */
data class ShareEgv(val timeMs: Long, val mgdl: Int, val trend: Int)

/**
 * The wire format of the Share publisher API, mirrored from xDrip+ `sharemodels/`.
 *
 * This is an unofficial interface, so the shape here is copied rather than designed: the
 * placeholder trend, the `/Date(…)/` spelling and the request headers are what Dexcom accepts.
 * Anything that looks redundant (the identical `ST`/`DT`, the `TA` of -5) is deliberate.
 */
object DexcomShareJson {

    /** The public application id every third-party Share client uses. */
    const val APPLICATION_ID = "d89443d2-327c-4a6f-89e5-496bbb0317db"

    /** xDrip's stand-in serial for an account that has no receiver to monitor. */
    const val DEFAULT_RECEIVER_SERIAL = "SM00000000"

    /** Dexcom rejects requests from unknown clients; this is the string xDrip sends. */
    const val USER_AGENT = "CGM-Store-1.2/22 CFNetwork/711.5.6 Darwin/14.0.0"

    /** Share is a 5-minute stream; batches larger than this are split by the flush loop. */
    const val MAX_EGVS_PER_REQUEST = 60

    /** The `TA` field of every upload body: a fixed -5, copied from xDrip. */
    private const val TIME_ADJUST = -5

    private const val MIN_MGDL = 40
    private const val MAX_MGDL = 400

    /** `"/Date(" + milliseconds floored to whole seconds + ")/"`, as xDrip spells it. */
    fun dateString(timeMs: Long): String = "/Date(${timeMs / 1000 * 1000})/"

    /** Dexcom's accepted range. Values outside it would be rejected for the whole batch. */
    fun clampMgdl(mgdl: Int): Int = mgdl.coerceIn(MIN_MGDL, MAX_MGDL)

    /** Dexcom's ordinal is 0..7; anything else (unknown, a driver's own scale) reads as "none". */
    fun normalizeTrend(index: Int): Int = if (index in 0..7) index else 0

    fun authBody(account: String, password: String): String = JSONObject()
        .put("accountName", account)
        .put("password", password)
        .put("applicationId", APPLICATION_ID)
        .toString()

    /** Oldest first: the caller passes the batch in the order Dexcom should store it. */
    fun uploadBody(serial: String, egvs: List<ShareEgv>): String {
        val array = JSONArray()
        egvs.forEach { egv ->
            val stamp = dateString(egv.timeMs)
            array.put(
                JSONObject()
                    .put("Trend", normalizeTrend(egv.trend))
                    .put("Value", clampMgdl(egv.mgdl))
                    .put("ST", stamp)
                    .put("DT", stamp)
            )
        }
        return JSONObject()
            .put("SN", serial)
            .put("Egvs", array)
            .put("TA", TIME_ADJUST)
            .toString()
    }

    /**
     * Dexcom answers the session and status calls with a bare JSON *string* (`"8f2c…"`), and
     * the check call with an unquoted one (`AssignedToYou`). Accept whichever arrives.
     *
     * A JSON `null` (Dexcom's answer for an account that does not exist in this region) is not a
     * session: returning the literal text "null" would make the uploader re-login on every flush.
     */
    fun parseJsonString(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        val parsed = runCatching {
            when (val value = JSONTokener(trimmed).nextValue()) {
                null -> ""
                JSONObject.NULL -> ""
                is String -> value
                else -> value.toString()
            }
        }.getOrDefault(trimmed.removeSurrounding("\""))
        return if (parsed.equals(NULL_LITERAL, ignoreCase = true)) "" else parsed
    }

    private const val NULL_LITERAL = "null"
}
