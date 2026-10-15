package tk.glucodata.combined

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.GlucosePoint

/** Persist the start of a new composition before publication, including across process death. */
object CombinedTrendHistory {
    private fun prefs() = Applic.app.getSharedPreferences("combined_trend_boundaries", Context.MODE_PRIVATE)

    @Synchronized internal fun markBoundary(id: String, timeMs: Long) {
        val canonical = CombinedSensorRegistry.canonicalId(id) ?: return
        // A restart deliberately starts a new segment at the next output: the old
        // model's weights/composition are unknown. No older history is deleted.
        prefs().edit().putLong(canonical, timeMs).commit()
    }

    fun startTime(id: String?): Long {
        val canonical = CombinedSensorRegistry.canonicalId(id) ?: return 0L
        return prefs().getLong(canonical, Long.MAX_VALUE)
    }

    fun filterUi(points: List<tk.glucodata.ui.GlucosePoint>, id: String?): List<tk.glucodata.ui.GlucosePoint> {
        val start = startTime(id)
        return if (start == 0L) points else points.filter { it.timestamp >= start }
    }

    fun filter(points: List<GlucosePoint>, id: String?): List<GlucosePoint> {
        val start = startTime(id)
        return if (start == 0L) points else points.filter { it.timestamp >= start }
    }
}
