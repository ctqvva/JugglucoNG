package tk.glucodata

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Historical glucose has its own receipt and never carries alert episodes. */
internal object GluciferHistory {
    const val RETENTION_MS = 7 * 86400000L
    const val BATCH_SIZE = 256

    fun build(sourceId: String, points: List<GlucosePoint>, afterMs: Long, throughMs: Long, nowMs: Long, raw: Boolean): JSONObject? {
        val readings = points.asSequence()
            .filter { it.timestamp > afterMs && it.timestamp >= nowMs - RETENTION_MS && it.timestamp <= throughMs }
            .map { it.timestamp to if (raw && it.rawValue > 0) it.rawValue else it.value }
            .filter { (_, value) -> value.isFinite() && value > 0 && value <= 1000 }
            .sortedBy { it.first }.distinctBy { it.first }.take(BATCH_SIZE).toList()
        if (readings.isEmpty()) return null
        return JSONObject().put("schema_version", 2).put("type", "history")
            .put("source_id", sourceId).put("batch_id", UUID.randomUUID().toString())
            .put("readings", JSONArray().apply { readings.forEach { (time, value) -> put(JSONObject().put("time_ms", time).put("mgdl", value)) } })
    }

    fun through(payload: JSONObject): Long = payload.getJSONArray("readings").let { it.getJSONObject(it.length() - 1).getLong("time_ms") }

    fun acknowledged(body: String, payload: JSONObject): Boolean = runCatching {
        val ack = JSONObject(body)
        val receiptTime = ack.opt("through_ms")
        ack.opt("schema_version") == 2 && ack.optString("type") == "history" &&
            ack.optString("source_id") == payload.getString("source_id") &&
            ack.optString("batch_id") == payload.getString("batch_id") &&
            ack.optString("status") == "accepted" && receiptTime is Number &&
            receiptTime.toDouble() == through(payload).toDouble()
    }.getOrDefault(false)
}
