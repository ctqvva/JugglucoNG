package tk.glucodata

import org.json.JSONObject
import java.util.UUID

internal object GluciferBackfillStatus {
    fun needsReport(state: JSONObject, active: Boolean): Boolean =
        state.opt("backfill_active") != active || state.has("status_pending")

    fun build(sourceId: String, active: Boolean): JSONObject = JSONObject()
        .put("schema_version", 2).put("type", "backfill_status")
        .put("source_id", sourceId).put("status_id", UUID.randomUUID().toString()).put("active", active)

    fun supported(receipt: String): Boolean = runCatching {
        val capabilities = JSONObject(receipt).optJSONArray("capabilities") ?: return false
        (0 until capabilities.length()).any { capabilities.optString(it) == "backfill_status" }
    }.getOrDefault(false)

    fun acknowledged(receipt: String, payload: JSONObject): Boolean = runCatching {
        val ack = JSONObject(receipt)
        ack.opt("schema_version") == 2 && ack.optString("type") == "backfill_status" &&
            ack.optString("source_id") == payload.getString("source_id") &&
            ack.optString("status_id") == payload.getString("status_id") &&
            ack.opt("active") == payload.get("active") && ack.optString("status") == "accepted"
    }.getOrDefault(false)
}
