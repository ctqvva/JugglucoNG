package tk.glucodata

import org.json.JSONObject
import kotlin.math.round

/** Versioned, complete snapshots. Optional field selection happens before serialization. */
object GluciferPayload {
    val fields = linkedSetOf(
        "trend", "delta_mgdl", "rate_mgdl_min", "raw_mgdl", "auto_mgdl",
        "iob_u", "eiob_u", "cob_g", "battery_percent", "sensor_id", "sensor_generation",
        "sensor_started_ms", "sensor_expires_ms", "sensor_warmup"
    )
    val alerts = linkedSetOf(
        "low", "high", "very_low", "very_high", "pre_low", "pre_high",
        "missed_reading", "persistent_high", "loss", "sensor_expiry",
        "falling_fast", "rising_fast", "sensor_pressure"
    )
    val defaults: Set<String> = setOf("trend", "delta_mgdl") + alerts.map { "alert:$it" }
    val supported: Set<String> = fields + alerts.map { "alert:$it" } + "predictions"

    fun build(
        sourceId: String,
        sequence: Long,
        nowMs: Long,
        glucoseTimeMs: Long,
        mgdl: Int,
        selected: Set<String>,
        values: Map<String, Any?>,
        alertStates: Map<String, Boolean?>,
        schemaVersion: Int = if (selected.any { it in setOf("sensor_started_ms", "sensor_expires_ms", "sensor_warmup") }) 2 else 1
    ): JSONObject {
        require(schemaVersion in 1..2)
        require(sourceId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(sequence in 1..9007199254740991L)
        require(mgdl in 1..1000 && glucoseTimeMs > 0 && nowMs > 0)
        val optional = JSONObject()
        fields.filter { it in selected }.forEach { key ->
            val value = values[key]
            optional.put(key, when {
                value is Float && !value.isFinite() -> JSONObject.NULL
                value is Double && !value.isFinite() -> JSONObject.NULL
                value is Float || value is Double -> round((value as Number).toDouble() * 10.0) / 10.0
                else -> value ?: JSONObject.NULL
            })
        }
        val alarms = JSONObject()
        alerts.filter { "alert:$it" in selected }.forEach {
            alarms.put(it, alertStates[it] ?: JSONObject.NULL)
        }
        return JSONObject()
            .put("schema_version", schemaVersion)
            .put("source_id", sourceId)
            .put("sequence", sequence)
            .put("sent_at_ms", nowMs)
            .put("glucose", JSONObject().put("time_ms", glucoseTimeMs).put("mgdl", mgdl))
            .put("fields", optional)
            .put("alerts", alarms)
            .apply { if ("predictions" in selected) put("predictions", values["predictions"] ?: org.json.JSONArray()) }
    }

    fun acknowledged(body: String, sourceId: String, sequence: Long, schemaVersion: Int = 1): Boolean = runCatching {
        val json = JSONObject(body)
        json.opt("schema_version") == schemaVersion && json.optString("source_id") == sourceId &&
            json.opt("sequence") is Number && json.getLong("sequence") == sequence &&
            (json.get("sequence") as Number).toDouble() == sequence.toDouble() &&
            json.optString("status") in setOf("accepted", "duplicate", "superseded")
    }.getOrDefault(false)

    /** Compare exported data, excluding delivery sequence and send time. */
    fun sameData(a: JSONObject, b: JSONObject): Boolean =
        a.getInt("schema_version") == b.getInt("schema_version") &&
        equalObject(a.getJSONObject("glucose"), b.getJSONObject("glucose")) &&
            equalObject(a.getJSONObject("fields"), b.getJSONObject("fields")) &&
            equalObject(a.getJSONObject("alerts"), b.getJSONObject("alerts")) &&
            (a.optJSONObject("alert_details") ?: JSONObject()).toString() ==
                (b.optJSONObject("alert_details") ?: JSONObject()).toString() &&
            (a.optJSONArray("alert_events") ?: org.json.JSONArray()).toString() ==
                (b.optJSONArray("alert_events") ?: org.json.JSONArray()).toString() &&
            (a.optJSONObject("reporting") ?: JSONObject()).toString() ==
                (b.optJSONObject("reporting") ?: JSONObject()).toString() &&
            (a.optJSONArray("predictions") ?: org.json.JSONArray()).toString() ==
                (b.optJSONArray("predictions") ?: org.json.JSONArray()).toString()

    internal fun nextDelivery(
        previous: JSONObject?, candidate: JSONObject, pending: Boolean, force: Boolean
    ): JSONObject? {
        if (previous == null || force || !sameData(previous, candidate)) return candidate
        if (pending) return previous
        return null
    }

    private fun equalObject(a: JSONObject, b: JSONObject): Boolean {
        if (a.length() != b.length()) return false
        return a.keys().asSequence().all { key ->
            if (!b.has(key)) false else {
                val x = a.get(key)
                val y = b.get(key)
                if (x is Number && y is Number) x.toDouble() == y.toDouble() else x == y
            }
        }
    }
}
