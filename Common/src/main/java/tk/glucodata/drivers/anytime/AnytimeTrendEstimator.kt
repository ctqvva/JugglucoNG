// AnytimeTrendEstimator.kt — trend slope + noise estimate for the manual
// calibration gate (AnytimeCalibrationPolicy) and the trend classifier.
//
// Reference App gates calibration on a stable trend and low noise (see
// docs/anytime-reference-extract.md, "Гейты допуска" / "Классификатор
// тренда") and MK4_FINAL_SUMMARY.md does not cover this layer at all — it is
// vendor-native, not Reference App. Only the acceptance thresholds are RE'd
// (±0.06 / ±0.11 mmol/L/min, noise < 0.2 mmol/L; see
// docs/anytime-reference-extract.md). The regression itself (ordinary
// least squares over a short window) and the window length are this file's
// own, undocumented choice — see docs/ct-driver-plan.md open questions.

package tk.glucodata.drivers.anytime

import kotlin.math.sqrt

internal object AnytimeTrendEstimator {

    data class Point(val timestampMs: Long, val mmol: Float)

    data class Estimate(val slopeMmolPerMin: Float, val noiseMmol: Float)

    /**
     * Ordinary least-squares slope (mmol/L per minute) and RMS residual noise
     * (mmol/L) over [points]. Returns null with fewer than 3 points — not
     * enough to distinguish a real trend from sensor jitter.
     */
    fun estimate(points: List<Point>): Estimate? {
        if (points.size < 3) return null
        val t0 = points.first().timestampMs
        val xs = points.map { (it.timestampMs - t0) / 60_000.0 }
        val ys = points.map { it.mmol.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        var sxx = 0.0
        var sxy = 0.0
        for (i in points.indices) {
            val dx = xs[i] - meanX
            sxx += dx * dx
            sxy += dx * (ys[i] - meanY)
        }
        if (sxx <= 1e-9) return Estimate(slopeMmolPerMin = 0f, noiseMmol = 0f)
        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        var sse = 0.0
        for (i in points.indices) {
            val residual = ys[i] - (intercept + slope * xs[i])
            sse += residual * residual
        }
        val noise = sqrt(sse / points.size)
        return Estimate(slopeMmolPerMin = slope.toFloat(), noiseMmol = noise.toFloat())
    }
}
