package tk.glucodata.drivers.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * Latest insulin/carb state received through the HTTP API follower path: another
 * JugglucoNG instance POSTs its computed IOB/COB (OutboundApi's normalized glucose
 * JSON, or GlucoWatch text) to a relay, and the API follower polls it back.
 *
 * This mirrors [tk.glucodata.drivers.nightscout.NightscoutFollowerDeviceStatus] for
 * Nightscout and [tk.glucodata.CloneIobSnapshot] for Clone: while a fresh snapshot
 * exists the follower shows the sender's numbers instead of recomputing them from
 * imported treatments with its own insulin presets and absorption profile. The
 * recompute cannot substitute for the snapshot — the follower picks its own preset
 * curve, contributing entries may sit outside the snapshot's event window
 * (long-acting insulin), and eIOB has no event-level representation at all.
 */
object ApiIobSnapshot {

    // Matches the Nightscout follower window: the sender POSTs at most every few
    // minutes (OutboundApi min interval, 5 by default; 15 when idle), so the same
    // window keeps the API follower in agreement with the sender.
    const val FRESHNESS_WINDOW_MS = 30L * 60L * 1000L

    data class RemoteIob(
        val iobUnits: Float,
        /** NaN when the payload carries no eiob field (text format, IOB-only senders). */
        val eiobUnits: Float,
        /** NaN when the payload carries no cob field. */
        val cobGrams: Float,
        val timestampMillis: Long,
    )

    @Volatile
    private var latest: RemoteIob? = null

    /**
     * Stores a parsed snapshot. A null result of a poll keeps the previous
     * snapshot: the freshness window retires it on its own, and dropping it
     * early would make one failed fetch flip the displayed source. A delayed
     * retransmission never replaces a newer packet.
     */
    fun update(remote: RemoteIob?): Boolean {
        if (remote == null) return false
        val current = latest
        if (current != null && remote.timestampMillis < current.timestampMillis) return false
        latest = remote
        return true
    }

    fun clear() {
        latest = null
    }

    /**
     * The stored snapshot while it is inside the freshness window, else null.
     * Timestamps ahead of the local clock count as fresh — the sender's
     * clock may run slightly ahead of the follower's.
     */
    @JvmStatic
    fun fresh(nowMillis: Long): RemoteIob? =
        latest?.takeIf { nowMillis - it.timestampMillis <= FRESHNESS_WINDOW_MS }

    /**
     * The sender's snapshot from an OutboundApi normalized-glucose body, in any of
     * the shapes the follower accepts for readings: a single object, a top-level
     * array, or a `readings`/`entries` wrapper object. The nested `journal`
     * snapshot is preferred — it is the only level carrying eIOB and its own
     * timestamp; the top-level `journal_iob`/`iob` + `journal_cob`/`cob` fields
     * are the fallback. IOB-only and COB-only payloads are kept: the broadcast
     * layer falls back to the local computation per missing field. When several
     * readings are present the newest valid snapshot wins.
     */
    fun parse(body: String): RemoteIob? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) {
            return newestFromArray(runCatching { JSONArray(trimmed) }.getOrNull() ?: return null)
        }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        var newest = parseSingle(root)
        for (key in arrayOf("readings", "entries")) {
            root.optJSONArray(key)?.let { newest = newerOf(newest, newestFromArray(it)) }
        }
        return newest
    }

    private fun newestFromArray(array: JSONArray): RemoteIob? {
        var newest: RemoteIob? = null
        for (index in 0 until array.length()) {
            val parsed = array.optJSONObject(index)?.let(::parseSingle) ?: continue
            newest = newerOf(newest, parsed)
        }
        return newest
    }

    private fun newerOf(current: RemoteIob?, candidate: RemoteIob?): RemoteIob? {
        if (candidate == null) return current
        if (current == null) return candidate
        return if (candidate.timestampMillis > current.timestampMillis) candidate else current
    }

    private fun parseSingle(root: JSONObject): RemoteIob? {
        root.optJSONObject("journal")?.let { journal ->
            snapshot(
                iob = journal.finiteFloat("journal_iob", "iob"),
                eiob = journal.finiteFloat("journal_eiob", "eiob") ?: Float.NaN,
                cob = journal.finiteFloat("journal_cob", "cob"),
                timestampMillis = journal.optLong("timestamp", 0L),
            )?.let { return it }
        }
        return snapshot(
            iob = root.finiteFloat("journal_iob", "iob"),
            eiob = Float.NaN,
            cob = root.finiteFloat("journal_cob", "cob"),
            timestampMillis = root.optLong("timestamp", 0L),
        )
    }

    /**
     * The IOB/COB carried by one GlucoWatch text message, whose fields were split
     * on '|' with uppercased keys (see ApiGlucoseSourceManager): IOB/COB/TS.
     * The text template renders a missing value as 0, which is indistinguishable
     * from a real zero — both display as zero, so finite values are accepted.
     */
    fun fromTextFields(fields: Map<String, String>): RemoteIob? {
        val timestamp = fields["TS"]?.toLongOrNull()
            ?: fields["TIMESTAMP"]?.toLongOrNull()
            ?: return null
        return snapshot(
            iob = fields["IOB"]?.toDoubleOrNull()?.takeIf(Double::isFinite)
                ?.toFloat()?.takeIf(Float::isFinite),
            eiob = Float.NaN,
            cob = fields["COB"]?.toDoubleOrNull()?.takeIf(Double::isFinite)
                ?.toFloat()?.takeIf(Float::isFinite),
            timestampMillis = timestamp,
        )
    }

    private fun snapshot(
        iob: Float?,
        eiob: Float,
        cob: Float?,
        timestampMillis: Long,
    ): RemoteIob? {
        if (timestampMillis <= 0L) return null
        val iobUnits = iob?.takeIf(Float::isFinite)
        val cobGrams = cob?.takeIf(Float::isFinite)
        if (iobUnits == null && cobGrams == null) return null
        return RemoteIob(
            iobUnits = iobUnits ?: Float.NaN,
            eiobUnits = eiob.takeIf(Float::isFinite) ?: Float.NaN,
            cobGrams = cobGrams ?: Float.NaN,
            timestampMillis = timestampMillis,
        )
    }

    private fun JSONObject.finiteFloat(vararg keys: String): Float? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val parsed = when (val value = opt(key)) {
                is Number -> value.toDouble().takeIf(Double::isFinite)
                is String -> value.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
                else -> null
            }?.toFloat()?.takeIf(Float::isFinite)
            if (parsed != null) return parsed
        }
        return null
    }
}
