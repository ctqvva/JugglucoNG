package tk.glucodata.chart

import tk.glucodata.GlucoseChartGap
import tk.glucodata.GlucoseUncertainty
import tk.glucodata.SensorIdentity

/** Geometry shared by Compose and bitmap charts; missing intervals break a band. */
object UncertaintyBands {
    const val PREFERENCE_KEY = "glucose_uncertainty_ribbon"
    /**
     * Between the forecast shading and the line: 0.26/0.42 read as a second,
     * heavier curve over the reading. The edge keeps the boundary legible where
     * the target-range fill lies on top (a 0.16 fill without one disappeared).
     */
    const val FILL_ALPHA = 0.13f
    const val EDGE_ALPHA = 0.26f
    const val FORECAST_ALPHA = 0.055f
    const val FORECAST_FADE_MS = 5 * 60_000L

    @JvmStatic
    fun isEnabled(context: android.content.Context): Boolean = runCatching {
        context.getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
            .getBoolean(PREFERENCE_KEY, true)
    }.getOrDefault(true)

    @JvmStatic
    @JvmOverloads
    fun runs(points: List<ChartPointModel>, gapThresholdMs: Long = GlucoseChartGap.THRESHOLD_MS): List<List<ChartPointModel>> {
        val runs = ArrayList<List<ChartPointModel>>()
        var run = ArrayList<ChartPointModel>()
        fun flush() {
            if (run.isEmpty()) return
            if (run.size >= 2) runs.add(run)
            run = ArrayList()
        }
        for (point in points) {
            if (point.uncertainty?.isUsable != true || !point.value.isFinite() || point.value <= 0.1f) {
                flush()
                continue
            }
            val previous = run.lastOrNull()
            if (previous != null && (point.timestamp - previous.timestamp > gapThresholdMs ||
                (previous.sensorId != null && point.sensorId != null && !SensorIdentity.matches(previous.sensorId, point.sensorId)))) flush()
            run.add(point)
        }
        flush()
        return runs
    }
}

/** Display envelope, not a new sensor estimate or a change to the simulation. */
class PredictionBand @JvmOverloads constructor(
    private val baselineValue: Float,
    private val baselineConfidence: Float,
    uncertainty: GlucoseUncertainty?,
    val startTimestamp: Long = 0L,
) {
    private val anchor = uncertainty?.takeIf { it.isUsable }
    val isAnchored: Boolean get() = anchor != null

    fun bounds(value: Float, confidence: Float, isMmol: Boolean): GlucoseUncertainty {
        val spread = halfWidth(confidence, isMmol)
        val start = anchor ?: return GlucoseUncertainty(value - spread, value + spread)
        // Inherit the actual asymmetric interval at the join. Only the additional
        // forecast spread is added, so confidence=1 cannot collapse a wide ribbon.
        val growth = (spread - halfWidth(baselineConfidence, isMmol)).coerceAtLeast(0f)
        val delta = value - baselineValue
        return start.copy(lower = start.lower + delta - growth, upper = start.upper + delta + growth)
    }

    companion object {
        @JvmStatic
        fun halfWidth(confidence: Float, isMmol: Boolean): Float {
            val uncertainty = 1f - (confidence.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 1f)
            return if (isMmol) 0.18f + uncertainty * 1.3f else 3.2f + uncertainty * 24f
        }
    }
}
