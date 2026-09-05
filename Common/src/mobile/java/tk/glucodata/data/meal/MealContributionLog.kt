package tk.glucodata.data.meal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One attempt to send a product to Open Food Facts, kept so the outcome can be looked at later. */
data class ContributionLogEntry(
    val at: Long,
    val barcode: String,
    val name: String,
    val ok: Boolean,
    val message: String,
    val photosSent: Int,
    val photosFailed: Int
)

/**
 * The last [MAX_ENTRIES] upload attempts, newest first, in the contribution preferences file.
 * A toast is easy to miss; this is where "did it work?" gets its answer.
 */
object MealContributionLog {
    const val MAX_ENTRIES = 30
    private const val PREFS = "meal_off_contribution"
    private const val KEY = "log_json"

    fun entries(context: Context): List<ContributionLogEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(
                    ContributionLogEntry(
                        at = o.optLong("at"),
                        barcode = o.optString("barcode"),
                        name = o.optString("name"),
                        ok = o.optBoolean("ok"),
                        message = o.optString("message"),
                        photosSent = o.optInt("photosSent"),
                        photosFailed = o.optInt("photosFailed")
                    )
                )
            }
        }
    }

    fun lastFor(context: Context, barcode: String): ContributionLogEntry? =
        entries(context).firstOrNull { it.barcode == barcode }

    fun append(context: Context, entry: ContributionLogEntry) {
        val next = (listOf(entry) + entries(context)).take(MAX_ENTRIES)
        val array = JSONArray()
        next.forEach { e ->
            array.put(
                JSONObject()
                    .put("at", e.at).put("barcode", e.barcode).put("name", e.name).put("ok", e.ok)
                    .put("message", e.message).put("photosSent", e.photosSent).put("photosFailed", e.photosFailed)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun describe(result: ContributionResult): Pair<Boolean, String> = when (result) {
        is ContributionResult.Sent -> true to "photos ${result.photosSent}/${result.photosSent + result.photosFailed}"
        is ContributionResult.Rejected -> false to (result.message ?: "rejected")
        is ContributionResult.Failed -> false to (result.message ?: "failed")
    }
}
