package tk.glucodata.data.journal

import android.content.Context

/** Local-only human metadata used when an official curve is indexed by U/kg. */
object JournalHumanProfile {
    const val BODY_WEIGHT_KG_KEY = "journal_body_weight_kg"
    const val MIN_BODY_WEIGHT_KG = 10f
    const val MAX_BODY_WEIGHT_KG = 400f

    fun bodyWeightKg(context: Context): Float? {
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        if (!prefs.contains(BODY_WEIGHT_KG_KEY)) return null
        return prefs.getFloat(BODY_WEIGHT_KG_KEY, Float.NaN)
            .takeIf { it.isFinite() && it in MIN_BODY_WEIGHT_KG..MAX_BODY_WEIGHT_KG }
    }

    fun setBodyWeightKg(context: Context, value: Float?) {
        val editor = context.getSharedPreferences(
            "tk.glucodata_preferences",
            Context.MODE_PRIVATE
        ).edit()
        val normalized = value?.takeIf { it.isFinite() }
            ?.coerceIn(MIN_BODY_WEIGHT_KG, MAX_BODY_WEIGHT_KG)
        if (normalized == null) {
            editor.remove(BODY_WEIGHT_KG_KEY)
        } else {
            editor.putFloat(BODY_WEIGHT_KG_KEY, normalized)
        }
        editor.apply()
    }
}
