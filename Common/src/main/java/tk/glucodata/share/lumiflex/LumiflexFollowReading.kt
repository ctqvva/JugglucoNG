package tk.glucodata.share.lumiflex

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Lumiflex glucose is already mg/dL; appTime is local to appTimeZone. */
data class LumiflexFollowReading(val timestampMs: Long, val mgdl: Float) {
    companion object {
        fun fromJson(row: JSONObject?, providerId: String): LumiflexFollowReading? {
            row ?: return null
            if (row.optString("userId") != providerId || row.optInt("glucoseIsValid", 0) != 1) return null
            val glucose = row.optDouble("glucose", Double.NaN).toFloat()
            if (!glucose.isFinite() || glucose <= 0f || glucose > 1000f) return null
            val zone = row.optString("appTimeZone")
            if (zone !in TimeZone.getAvailableIDs()) return null
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone(zone)
                isLenient = false
            }
            val text = row.optString("appTime")
            val position = java.text.ParsePosition(0)
            val timestamp = format.parse(text, position)?.time ?: return null
            if (position.index != text.length || timestamp < 946_684_800_000L) return null
            return LumiflexFollowReading(timestamp, glucose)
        }
    }
}
