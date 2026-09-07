package tk.glucodata

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Independent journal revisions: bounded edits/deletes, with exact retry bodies. */
internal object GluciferJournal {
    const val MAX_ENTRIES = 5000
    const val MAX_BATCH = 16
    const val MAX_DAYS = 90

    private fun fingerprint(entry: JSONObject): String {
        // Object key order is not part of journal content, including after a retry reload.
        val canonical = JSONObject()
        entry.keys().asSequence().sorted().forEach { canonical.put(it, entry.get(it)) }
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte -> val value = byte.toInt() and 255; append(hex[value ushr 4]); append(hex[value and 15]) }
        }
    }

    fun supported(receipt: String): Boolean = runCatching {
        val values = JSONObject(receipt).optJSONArray("capabilities") ?: return false
        (0 until values.length()).any { values.optString(it) == "journal_v1" }
    }.getOrDefault(false)

    fun next(state: JSONObject, sourceId: String, current: List<JSONObject>, enabled: Boolean,
        days: Int, notes: Boolean, now: Long): JSONObject? {
        require(days in 1..MAX_DAYS)
        val selection = "$enabled:$days:$notes"
        val pending = state.optJSONObject("pending")
        if (pending != null && state.optString("selection") == selection) return pending
        state.remove("pending")
        val known = state.optJSONObject("acknowledged") ?: JSONObject()
        if (!enabled && !state.has("settings") && known.length() == 0 && pending == null) return null
        val desired = if (enabled) current.take(MAX_ENTRIES).associateBy { it.getString("id") } else emptyMap()
        val deleted = if (enabled) known.keys().asSequence().filter { it !in desired }.sorted().take(MAX_BATCH).toList() else emptyList()
        val changed = desired.values.asSequence().filter { known.optString(it.getString("id")) != fingerprint(it) }
            .take(MAX_BATCH - deleted.size).toMutableList()
        if (deleted.isEmpty() && changed.isEmpty() && state.optString("settings") == selection) return null
        val sequence = state.optLong("sequence", 0L) + 1
        fun payload() = JSONObject().put("schema_version", 2).put("type", "journal")
            .put("source_id", sourceId).put("sequence", sequence).put("sent_at_ms", now)
            .put("enabled", enabled).put("history_days", days)
            .put("entries", JSONArray(changed)).put("deleted_ids", JSONArray(deleted))
        var result = payload()
        while (result.toString().toByteArray(Charsets.UTF_8).size > 30000 && changed.isNotEmpty()) {
            changed.removeAt(changed.lastIndex)
            result = payload()
        }
        require(result.toString().toByteArray(Charsets.UTF_8).size <= 30000)
        state.put("sequence", sequence).put("selection", selection).put("pending", result)
        return result
    }

    fun acknowledged(body: String, payload: JSONObject): Boolean = runCatching {
        val ack = JSONObject(body)
        ack.opt("schema_version") == 2 && ack.optString("type") == "journal" &&
            ack.optString("source_id") == payload.getString("source_id") &&
            ack.opt("sequence") is Number && (ack.get("sequence") as Number).toDouble() == payload.getLong("sequence").toDouble() &&
            ack.optString("status") in setOf("accepted", "duplicate")
    }.getOrDefault(false)

    fun accept(state: JSONObject, payload: JSONObject) {
        val known = if (payload.getBoolean("enabled")) state.optJSONObject("acknowledged") ?: JSONObject() else JSONObject()
        val deleted = payload.getJSONArray("deleted_ids")
        for (i in 0 until deleted.length()) known.remove(deleted.getString(i))
        val entries = payload.getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            known.put(entry.getString("id"), fingerprint(entry))
        }
        state.put("acknowledged", known).put("settings", state.getString("selection")).remove("pending")
    }
}
