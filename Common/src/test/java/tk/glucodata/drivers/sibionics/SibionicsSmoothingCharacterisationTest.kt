package tk.glucodata.drivers.sibionics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterises how much Adaptive V2 smooths, and how much excursion amplitude
 * it keeps, on content sharp enough to excite the vendor's deconvolution.
 *
 * This is a **characterisation**, not a target. Stock amplitude is a diagnostic
 * trace: the vendor's deconvolution is an inverse filter with roughly 1.4x
 * high-frequency gain, and matching it is explicitly not the objective. The
 * assertions are sanity bounds; the numbers are printed so a change in
 * behaviour is visible in the build log rather than only on a phone.
 *
 * The reason this needs sharp content: an earlier version of this measurement
 * used smooth sinusoids, reported v2/stock at 0.94, and completely missed the
 * 0.77 the user was actually seeing. Slow content does not exercise the stage
 * that differs.
 */
class SibionicsSmoothingCharacterisationTest {

    private fun samples(minutes: Int, seed: Int = 4): List<SibionicsSourceSample> {
        val random = Random(seed)
        return (0 until minutes).map { m ->
            val i = m + 1
            val phase = i % 240
            // Fast ramps and reversals, as a real night produces.
            val ramp = when {
                phase < 45 -> phase * 0.055f
                phase < 75 -> 45 * 0.055f - (phase - 45) * 0.02f
                phase < 130 -> 45 * 0.055f - 30 * 0.02f - (phase - 75) * 0.045f
                else -> 45 * 0.055f - 30 * 0.02f - 55 * 0.045f + (phase - 130) * 0.004f
            }
            val raw = (5.6f + ramp
                + 1.6f * sin(2 * PI * (i % 900) / 900.0).toFloat()
                + (random.nextFloat() - 0.5f) * 0.30f).coerceIn(3.0f, 14f)
            SibionicsSourceSample(i, i * 60_000L, raw, 34f, 2_900f, 0)
        }
    }

    @Test
    fun characteriseAmplitudeAndSmoothing() {
        listOf(
            SibionicsConstants.Variant.SIBIONICS2 to "V116A",
            SibionicsConstants.Variant.CHINESE to "V115G",
        ).forEach { (variant, family) ->
            val rows = SibionicsReplayHarness.replay(
                samples = samples(TRACE_MINUTES),
                variant = variant,
                shortCode = "46HU804EBJ4",
                sensitivity = 1.4f,
            ).filter {
                it.index > SETTLE && it.stockMmol.isFinite() && it.adaptiveV2Mmol.isFinite()
            }

            // Peak-to-trough over two-hour windows.
            var stockAmplitude = 0.0
            var calibratedAmplitude = 0.0
            var v2Amplitude = 0.0
            var windows = 0
            rows.chunked(120).forEach { window ->
                if (window.size < 100) return@forEach
                stockAmplitude += window.maxOf { it.stockMmol } - window.minOf { it.stockMmol }
                v2Amplitude += window.maxOf { it.adaptiveV2Mmol } - window.minOf { it.adaptiveV2Mmol }
                val calibrated = window.filter { it.calibratedMmol.isFinite() }
                if (calibrated.isNotEmpty()) {
                    calibratedAmplitude +=
                        calibrated.maxOf { it.calibratedMmol } - calibrated.minOf { it.calibratedMmol }
                }
                windows++
            }

            /** Fraction of consecutive samples that display an identical value. */
            fun held(values: List<Float>): Double {
                var same = 0
                values.zipWithNext().forEach { (a, b) -> if (abs(a - b) < 0.001f) same++ }
                return same.toDouble() / (values.size - 1)
            }

            /** Worst one-minute change in the displayed value. */
            fun worstJump(values: List<Float>): Double =
                values.zipWithNext().maxOf { (a, b) -> abs(b - a).toDouble() }

            val dynamic = rows.mapNotNull { it.diagnostics?.dynamicProbability?.toDouble() }
            val width = rows.mapNotNull {
                it.diagnostics?.let { d -> (d.upper90Mmol - d.lower90Mmol).toDouble() }
            }
            val v2Jump = worstJump(rows.map { it.adaptiveV2Mmol })
            // The fair smoothing comparison is against V2's own input, not
            // against stock: stock is deconvolved and therefore intrinsically
            // noisier, so measuring V2 against it conflates "V2 over-smooths"
            // with "the calibrated observation is smoother than stock". Rounded
            // to display resolution so the three are measured alike.
            fun rounded(value: Float): Float = kotlin.math.round(value * 10f) / 10f
            val calibratedHeld = held(rows.map { rounded(it.calibratedMmol) })

            println(
                ("SMOOTH %s | amplitude stock=%.2f calibrated=%.2f v2=%.2f (v2/cal=%.2f v2/stock=%.2f)" +
                    " | held stock=%.2f calibrated=%.2f v2=%.2f | worstJump v2=%.2f | pDynamic=%.3f | width=%.2f").format(
                    family,
                    stockAmplitude / windows, calibratedAmplitude / windows, v2Amplitude / windows,
                    v2Amplitude / calibratedAmplitude, v2Amplitude / stockAmplitude,
                    held(rows.map { it.stockMmol }), calibratedHeld, held(rows.map { it.adaptiveV2Mmol }),
                    v2Jump,
                    if (dynamic.isEmpty()) Double.NaN else dynamic.average(),
                    if (width.isEmpty()) Double.NaN else width.average(),
                )
            )

            // V2 must at least keep the amplitude present in its own input;
            // falling below that means the estimator is destroying signal
            // rather than reconstructing it.
            assertTrue(
                "$family v2=${v2Amplitude / windows} cal=${calibratedAmplitude / windows}",
                v2Amplitude >= calibratedAmplitude * 0.97,
            )
            // Hard ceiling from the failed loose-tuning experiment: a displayed
            // value that teleports is never an acceptable price for amplitude.
            assertTrue("$family worstJump=$v2Jump", v2Jump < 1.0)
        }
    }

    private companion object {
        private const val TRACE_MINUTES = 9_000
        private const val SETTLE = 3_000
    }
}
