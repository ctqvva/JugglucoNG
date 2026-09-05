package tk.glucodata.drivers.sibionics.adaptive2

import kotlin.math.abs
import org.junit.Test

/**
 * The 14:15-14:37 event, minute by minute.
 *
 * Reconstructed from the on-device comparison: a flat stretch near 8.5, a rise
 * to 12.4 over four minutes, then a decay to 7.2 over the following quarter
 * hour. The existing device fixture cannot stand in for this — its sharpest
 * two-minute move is 0.85 mmol/L, against 3.3 here — so every responsiveness
 * result measured against that fixture was blind to this failure.
 *
 * Two questions this exists to answer numerically, before anything is tuned:
 * why the first large upward observation barely moves B, and why B later falls
 * materially below the observation.
 */
class AdaptiveV2SharpEventTest {

    /** 60 settling minutes at 8.5, then the event. */
    private fun observations(): List<Float> {
        val series = ArrayList<Float>()
        repeat(60) { series += 8.5f }
        // Rise: 8.5 -> 12.4 across four minutes, the sharp part in the middle two.
        series += listOf(8.7f, 10.1f, 11.8f, 12.4f)
        // Decay back to 7.2 over fifteen minutes.
        val peak = 12.4f
        val floor = 7.2f
        for (m in 1..15) series += floor + (peak - floor) * kotlin.math.exp(-m / 4.5f)
        return series
    }

    private fun sample(index: Int, value: Float) = AdaptiveV2Sample(
        calibratedMmol = value,
        factorySensitivity = 1.26f,
        activeSensitivity = 1.26f,
        sensorStateCompensationMmol = 0.1f,
        temperatureC = 34f,
        impedance = 2_900f,
        qualityFlags = 0,
        index = index,
        timestampMs = index * 60_000L,
    )

    @Test
    fun diagnoseTheSharpEventMinuteByMinute() {
        val estimator = AdaptiveV2Estimator()
        val series = observations()
        println(
            "min  obs    pred   innov   rawW   effR    priorV  K_B    K_I    K_V    B      I      V       lagT   median lower  upper"
        )
        series.forEachIndexed { i, value ->
            val estimate = estimator.process(sample(i + 1, value)) ?: return@forEachIndexed
            if (i < 57) return@forEachIndexed
            val t = estimator.updateTrace
            println(
                "%3d %6.2f %6.2f %+7.3f %6.3f %7.4f %7.4f %+6.3f %+6.3f %+6.3f %6.2f %6.2f %+7.4f %6.3f %6.2f %6.2f %6.2f".format(
                    i + 1, t.observation, t.predicted, t.innovation, t.rawWeight, t.effectiveR,
                    t.priorVariance, t.gainB, t.gainI, t.gainV, t.b, t.i, t.v, t.lagTerm,
                    estimate.glucoseMmol, estimate.lower90Mmol, estimate.upper90Mmol,
                )
            )
        }
        // Report the two failures as numbers rather than assertions for now.
        val estimates = ArrayList<Float>()
        val e2 = AdaptiveV2Estimator()
        series.forEachIndexed { i, v -> e2.process(sample(i + 1, v))?.let { estimates += it.glucoseMmol } }
        val riseStart = 60
        println()
        println(
            "FIRST-MINUTE RESPONSE  obs %.2f -> %.2f (%+.2f)   B %.2f -> %.2f (%+.2f)   captured %.0f%%".format(
                series[riseStart - 1], series[riseStart + 1],
                series[riseStart + 1] - series[riseStart - 1],
                estimates[riseStart - 1], estimates[riseStart + 1],
                estimates[riseStart + 1] - estimates[riseStart - 1],
                100.0 * (estimates[riseStart + 1] - estimates[riseStart - 1]) /
                    (series[riseStart + 1] - series[riseStart - 1]),
            )
        )
        val worstUnder = series.indices.maxByOrNull { estimates[it] - series[it] }!!
        val worstOver = series.indices.minByOrNull { estimates[it] - series[it] }!!
        println(
            "WORST ABOVE obs: min %d  obs %.2f  B %.2f  (%+.2f)".format(
                worstUnder + 1, series[worstUnder], estimates[worstUnder],
                estimates[worstUnder] - series[worstUnder])
        )
        println(
            "WORST BELOW obs: min %d  obs %.2f  B %.2f  (%+.2f)".format(
                worstOver + 1, series[worstOver], estimates[worstOver],
                estimates[worstOver] - series[worstOver])
        )
        println("peak obs %.2f  peak B %.2f".format(series.max(), estimates.max()))
    }
}
