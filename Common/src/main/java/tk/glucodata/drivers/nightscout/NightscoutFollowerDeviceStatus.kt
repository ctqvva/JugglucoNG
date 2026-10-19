package tk.glucodata.drivers.nightscout

import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote IOB/eIOB/COB read from Nightscout devicestatus documents uploaded by
 * another JugglucoNG device (classic IOB in the openaps.iob container, eIOB
 * and COB in the "jugglucong" namespace). While a fresh document exists the
 * follower shows the uploader's numbers instead of recomputing them from
 * imported treatments with its own insulin presets; once the document ages
 * out, the local computation takes over again.
 */
object NightscoutFollowerDeviceStatus {

    // Matches Nightscout's own RECENCY_THRESHOLD (lib/plugins/iob.js): the
    // site stops trusting devicestatus IOB after 30 minutes, so following the
    // same window keeps site and follower in agreement.
    const val FRESHNESS_WINDOW_MS = 30L * 60L * 1000L

    data class RemoteIob(
        val iobUnits: Float,
        /** NaN when the document carries no eiob field. */
        val eiobUnits: Float,
        /** NaN when the document carries no cob field. */
        val cobGrams: Float,
        val timestampMillis: Long,
    )

    @Volatile
    private var latest: RemoteIob? = null

    /**
     * Stores a parsed snapshot. A null result of a poll keeps the previous
     * snapshot: the freshness window retires it on its own, and dropping it
     * early would make one failed fetch flip the displayed source.
     */
    fun update(remote: RemoteIob?) {
        if (remote != null) latest = remote
    }

    fun clear() {
        latest = null
    }

    /**
     * The stored snapshot while it is inside the freshness window, else null.
     * Timestamps ahead of the local clock count as fresh — the uploader's
     * clock may run slightly ahead of the follower's.
     */
    @JvmStatic
    fun fresh(nowMillis: Long): RemoteIob? =
        latest?.takeIf { nowMillis - it.timestampMillis <= FRESHNESS_WINDOW_MS }

    /**
     * The newest document carrying a jugglucong block with a finite iob, or
     * null. Documents from foreign uploaders (plain openaps loops) have no
     * such block and are ignored.
     */
    fun parseNewest(body: String): RemoteIob? {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        var newest: RemoteIob? = null
        for (index in 0 until array.length()) {
            val parsed = parseDocument(array.optJSONObject(index) ?: continue) ?: continue
            if (newest == null || parsed.timestampMillis > newest.timestampMillis) {
                newest = parsed
            }
        }
        return newest
    }

    private fun parseDocument(doc: JSONObject): RemoteIob? {
        val block = doc.optJSONObject("jugglucong") ?: return null
        val iob = block.optDouble("iob", Double.NaN).toFiniteFloatOrNull() ?: return null
        val timestampMillis = doc.optJSONObject("openaps")?.optJSONObject("iob")
            ?.optString("timestamp").let(::parseIsoMillis)
            ?: parseIsoMillis(doc.optString("created_at"))
            ?: doc.optLong("mills", 0L).takeIf { it > 0L }
            ?: return null
        return RemoteIob(
            iobUnits = iob,
            eiobUnits = block.optDouble("eiob", Double.NaN).toFiniteFloatOrNull() ?: Float.NaN,
            cobGrams = block.optDouble("cob", Double.NaN).toFiniteFloatOrNull() ?: Float.NaN,
            timestampMillis = timestampMillis,
        )
    }

    private fun parseIsoMillis(value: String?): Long? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { Instant.parse(trimmed).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private fun Double.toFiniteFloatOrNull(): Float? =
        takeIf { it.isFinite() }?.toFloat()?.takeIf { it.isFinite() }
}
