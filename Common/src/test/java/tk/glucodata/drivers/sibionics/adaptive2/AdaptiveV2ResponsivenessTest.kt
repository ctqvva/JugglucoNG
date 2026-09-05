package tk.glucodata.drivers.sibionics.adaptive2

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-minute responsiveness against the real device trace.
 *
 * The invariant: Adaptive V2 must add no low-pass delay to the calibrated
 * observation it is given. A valid new minute must move the posterior on that
 * same minute. Correcting drift, rejecting independently evidenced artifacts,
 * estimating uncertainty and leading the dynamics are all allowed; making a
 * valid current observation arrive later is not.
 *
 * Measured as step-response ratio: for every minute where the observation
 * genuinely moved, what fraction of that move appeared in the output on the
 * same minute. A first-order lag in front of the output shows up here directly
 * — 0.5 means half of every minute's movement is deferred, which is the
 * temporal smearing seen on device (14:18 the observation moved +0.46 and the
 * estimate +0.24).
 */
class AdaptiveV2ResponsivenessTest {

    private data class Row(
        val index: Int, val timestampMs: Long, val calibrated: Float, val compensation: Float,
        val activeSensitivity: Float, val factorySensitivity: Float, val qualityFlags: Int,
        val stock: Float,
    )

    private fun trace(): List<Row> =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("sibionics_v116a_real_events.csv"))
            .bufferedReader().readLines().drop(1).mapNotNull { line ->
                val f = line.split(',')
                if (f.size < 8) return@mapNotNull null
                Row(
                    f[0].toIntOrNull() ?: return@mapNotNull null,
                    f[1].toLongOrNull() ?: return@mapNotNull null,
                    f[2].toFloatOrNull() ?: return@mapNotNull null,
                    f[3].toFloatOrNull() ?: 0f,
                    f[4].toFloatOrNull() ?: 1.2f,
                    f[5].toFloatOrNull() ?: 1.2f,
                    f[6].toIntOrNull() ?: 0,
                    f[7].toFloatOrNull() ?: Float.NaN,
                )
            }

    private fun run(): Triple<List<Float>, List<Float>, List<Float>> {
        val estimator = AdaptiveV2Estimator()
        val obs = ArrayList<Float>(); val out = ArrayList<Float>(); val stock = ArrayList<Float>()
        trace().forEach { row ->
            val e = estimator.process(
                AdaptiveV2Sample(
                    calibratedMmol = row.calibrated,
                    factorySensitivity = row.factorySensitivity,
                    activeSensitivity = row.activeSensitivity,
                    sensorStateCompensationMmol = row.compensation,
                    temperatureC = 34f, impedance = 2_900f,
                    qualityFlags = row.qualityFlags,
                    index = row.index, timestampMs = row.timestampMs,
                ),
            ) ?: return@forEach
            obs += row.calibrated; out += e.glucoseMmol; stock += row.stock
        }
        return Triple(obs, out, stock)
    }

    /** Fraction of each minute's observation move that appears in the output that minute. */
    private fun stepResponse(obs: List<Float>, out: List<Float>, threshold: Float = 0.10f): Double {
        val ratios = ArrayList<Double>()
        for (i in 1 until obs.size) {
            val dz = obs[i] - obs[i - 1]
            if (abs(dz) < threshold) continue
            ratios.add(((out[i] - out[i - 1]) / dz).toDouble())
        }
        return ratios.sorted()[ratios.size / 2]
    }

    @Test
    fun v2AddsNoLowPassDelayToItsInput() {
        val (obs, out, stock) = run()
        val ratio = stepResponse(obs, out)
        val stockRatio = stepResponse(obs, stock)
        val lagged = (1 until obs.size).count { i ->
            val dz = obs[i] - obs[i - 1]
            abs(dz) >= 0.10f && (out[i] - out[i - 1]) / dz < 0.5f
        }
        val moved = (1 until obs.size).count { abs(obs[it] - obs[it - 1]) >= 0.10f }
        println(
            "RESPONSE n=%d moved=%d | same-minute step response: v2=%.2f stock=%.2f | minutes under 50%% captured: %.0f%%"
                .format(obs.size, moved, ratio, stockRatio, 100.0 * lagged / moved)
        )
        assertTrue("v2 same-minute step response $ratio", ratio >= 0.85)
    }
}
