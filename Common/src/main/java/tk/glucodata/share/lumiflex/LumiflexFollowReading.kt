package tk.glucodata.share.lumiflex

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Lumiflex glucose is already mg/dL; appTime is local to appTimeZone. */
data class LumiflexFollowReading(val timestampMs: Long, val mgdl: Float) {
    companion object {
        private val timeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
        fun fromJson(row: JSONObject?, providerId: String): LumiflexFollowReading? {
            row ?: return null
            if (row.optString("userId") != providerId || row.optInt("glucoseIsValid", 0) != 1) return null
            val glucose = row.optDouble("glucose", Double.NaN).toFloat()
            if (!glucose.isFinite() || glucose <= 0f || glucose > 1000f) return null
            val timestamp = runCatching {
                val local = LocalDateTime.parse(row.optString("appTime"), timeFormat)
                val rules = ZoneId.of(row.optString("appTimeZone")).rules
                val offsets = rules.getValidOffsets(local)
                // The vendor records daylight-saving seconds separately. During the repeated
                // autumn hour, zone + wall time alone cannot identify which instant was read.
                val daylightSeconds = row.optString("dstOffset").toLongOrNull()
                val candidates = offsets.map { local.toInstant(it) }.filter {
                    daylightSeconds == null || rules.getDaylightSavings(it).seconds == daylightSeconds
                }
                candidates.singleOrNull()?.toEpochMilli()
            }.getOrNull() ?: return null
            if (timestamp < 946_684_800_000L) return null
            return LumiflexFollowReading(timestamp, glucose)
        }
    }
}
