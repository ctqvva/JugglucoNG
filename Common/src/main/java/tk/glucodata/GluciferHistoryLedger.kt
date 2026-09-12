package tk.glucodata

import org.json.JSONArray
import org.json.JSONObject

/** Remember delivered timestamps across rescans, bounded like the receiver's history. */
internal object GluciferHistoryLedger {
    private const val LIMIT = 20160

    private fun initialize(state: JSONObject) {
        if (!state.has("acknowledged")) {
            // Older versions only retained a cursor. Do not replay that acknowledged prefix.
            state.put("acknowledged", JSONArray()).put("floor", state.optLong("cursor", 0L))
        }
    }

    private fun timestamps(state: JSONObject): Set<Long> {
        val array = state.getJSONArray("acknowledged")
        return (0 until array.length()).mapTo(mutableSetOf()) { array.getLong(it) }
    }

    fun unseen(state: JSONObject, points: List<GlucosePoint>): List<GlucosePoint> {
        initialize(state)
        val acknowledged = timestamps(state)
        val floor = state.optLong("floor", 0L)
        return points.filter { it.timestamp > floor && it.timestamp !in acknowledged }
    }

    fun acknowledge(state: JSONObject, delivered: Iterable<Long>, nowMs: Long) {
        initialize(state)
        var floor = maxOf(state.optLong("floor", 0L), nowMs - GluciferHistory.RETENTION_MS)
        val all = (timestamps(state) + delivered).filter { it > floor }.sorted()
        if (all.size > LIMIT) floor = maxOf(floor, all[all.size - LIMIT - 1])
        state.put("floor", floor)
        state.put("acknowledged", JSONArray(all.takeLast(LIMIT)))
    }
}
