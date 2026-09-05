package tk.glucodata

import org.json.JSONObject

/** Latest authoritative insulin and carb state received through Clone. */
object CloneIobSnapshot {
    const val FRESHNESS_WINDOW_MS = 6L * 60L * 1000L
    private const val SCHEMA = "tk.glucodata.clone.iob.v1"

    data class RemoteIob(
        val iobUnits: Float,
        val eiobUnits: Float,
        val cobGrams: Float,
        val iobNext30Units: Float,
        val cobNext30Grams: Float,
        val timestampMillis: Long,
    ) {
        fun values(): FloatArray = floatArrayOf(
            iobUnits,
            eiobUnits,
            cobGrams,
            iobNext30Units,
            cobNext30Grams,
        )
    }

    @Volatile
    private var latest: RemoteIob? = null

    /** Keeps a newer packet from being replaced by a delayed retransmission. */
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

    @JvmStatic
    fun fresh(nowMillis: Long): RemoteIob? = latest?.takeIf {
        nowMillis - it.timestampMillis <= FRESHNESS_WINDOW_MS
    }

    fun encode(values: FloatArray, timestampMillis: Long): String {
        if (timestampMillis <= 0L || values.size < 3) return ""
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("timestamp", timestampMillis)
        root.putFinite("iob", values.getOrNull(0))
        root.putFinite("eiob", values.getOrNull(1))
        root.putFinite("cob", values.getOrNull(2))
        root.putFinite("iobNext30", values.getOrNull(3))
        root.putFinite("cobNext30", values.getOrNull(4))
        return if (root.length() > 2) root.toString() else ""
    }

    fun parse(raw: String): RemoteIob? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optString("schema") != SCHEMA) return null
        val timestamp = root.optLong("timestamp", 0L).takeIf { it > 0L } ?: return null
        val iob = root.finiteFloatOrNaN("iob")
        val eiob = root.finiteFloatOrNaN("eiob")
        val cob = root.finiteFloatOrNaN("cob")
        val iobNext30 = root.finiteFloatOrNaN("iobNext30")
        val cobNext30 = root.finiteFloatOrNaN("cobNext30")
        if (listOf(iob, eiob, cob, iobNext30, cobNext30).none(Float::isFinite)) return null
        return RemoteIob(iob, eiob, cob, iobNext30, cobNext30, timestamp)
    }

    private fun JSONObject.putFinite(key: String, value: Float?) {
        value?.takeIf(Float::isFinite)?.let { put(key, it.toDouble()) }
    }

    private fun JSONObject.finiteFloatOrNaN(key: String): Float =
        optDouble(key, Double.NaN)
            .takeIf(Double::isFinite)
            ?.toFloat()
            ?.takeIf(Float::isFinite)
            ?: Float.NaN
}
