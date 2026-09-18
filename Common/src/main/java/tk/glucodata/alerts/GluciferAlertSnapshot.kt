package tk.glucodata.alerts

import org.json.JSONArray
import org.json.JSONObject

/** Stable identities retain quick fire/acknowledge pairs across coalesced sends and retries. */
data class GluciferAlertChange(
    val id: String, val alert: String, val reason: String, val timeMs: Long,
    val snoozedUntilMs: Long? = null
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("alert", alert)
        .put("reason", reason).put("time_ms", timeMs)
        .apply { snoozedUntilMs?.let { put("snoozed_until_ms", it) } }
}

data class GluciferAlertSnapshot(
    val states: Map<String, Boolean?>,
    val details: List<GluciferAlertChange>,
    val events: List<GluciferAlertChange>
) {
    fun detailsJson(selected: Set<String>): JSONObject = JSONObject().apply {
        details.filter { "alert:${it.alert}" in selected }.sortedBy { it.alert }
            .forEach { put(it.alert, it.json()) }
    }
    fun eventsJson(selected: Set<String>): JSONArray = JSONArray().apply {
        events.filter { "alert:${it.alert}" in selected }.forEach { put(it.json()) }
    }
}
