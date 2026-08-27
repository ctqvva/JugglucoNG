package tk.glucodata.drivers.sibionics.adaptive2

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the estimator with a real device capture and measures how far its
 * output sits behind the observation it was handed.
 *
 * The fixture is the vendor-calibrated column from a V1.1.6A sensor, recorded
 * after the five-minute sample-and-hold was fixed, so what is left is the
 * estimator's own lag and nothing upstream of it. `stock` is the vendor's
 * output for the same minutes and is a comparison column only.
 *
 * What it establishes, and the reason it exists: the lag is real but it is
 * *not* a tuning problem. Measured against interstitial process noise —
 *
 * ```
 *   2.0e-5 (current)  lag +0.71 min   mode-discrimination failures 0
 *   2.0e-4            lag +0.69 min   failures 0
 *   1.0e-3            lag +0.62 min   failures 3
 *   2.0e-3            lag +0.54 min   failures 3
 *   2.0e-2            lag +0.23 min   failures 3
 *   2.0e-1            lag +0.11 min   interval 3.0 mmol/L wide, IMM inert
 * ```
 *
 * — every setting that meaningfully reduces the lag also stops the IMM telling
 * a real excursion from a sensor artifact. The lag inversion itself is working:
 * |B-I| runs at 0.166 against the 0.196 the model asks for. What trails is the
 * *rate* estimate that drives it, and that is set by how decisively the IMM
 * hands over to DYNAMIC, not by how much noise STEADY is given.
 */
class AdaptiveV2DeviceLagTest {

    private data class Row(
        val index: Int, val timestampMs: Long, val calibrated: Float,
        val compensation: Float, val activeSensitivity: Float,
        val factorySensitivity: Float, val qualityFlags: Int, val stock: Float,
    )

    private fun trace(): List<Row> =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("sibionics_v116a_device_trace.csv"))
            .bufferedReader().readLines().drop(1).mapNotNull { line ->
                val f = line.split(',')
                if (f.size < 8) return@mapNotNull null
                Row(
                    f[0].toIntOrNull() ?: return@mapNotNull null,
                    f[1].toLongOrNull() ?: return@mapNotNull null,
                    f[2].toFloatOrNull() ?: return@mapNotNull null,
                    f[3].toFloatOrNull() ?: return@mapNotNull null,
                    f[4].toFloatOrNull() ?: return@mapNotNull null,
                    f[5].toFloatOrNull() ?: return@mapNotNull null,
                    f[6].toIntOrNull() ?: 0,
                    f[7].toFloatOrNull() ?: Float.NaN,
                )
            }

    /** Runs the fixture through a fresh estimator and returns its output per row. */
    private val pDynamic = ArrayList<Float>()
    private val width = ArrayList<Float>()
    private val leadGap = ArrayList<Float>()
    private val expectedLead = ArrayList<Float>()

    private fun run(): Triple<List<Float>, List<Float>, List<Float>> {
        val estimator = AdaptiveV2Estimator()
        pDynamic.clear(); width.clear(); leadGap.clear(); expectedLead.clear()
        val observed = ArrayList<Float>()
        val produced = ArrayList<Float>()
        val vendor = ArrayList<Float>()
        trace().forEach { row ->
            val estimate = estimator.process(
                AdaptiveV2Sample(
                    calibratedMmol = row.calibrated,
                    factorySensitivity = row.factorySensitivity,
                    activeSensitivity = row.activeSensitivity,
                    sensorStateCompensationMmol = row.compensation,
                    temperatureC = 34f,
                    impedance = 2_900f,
                    qualityFlags = row.qualityFlags,
                    index = row.index,
                    timestampMs = row.timestampMs,
                ),
            ) ?: return@forEach
            pDynamic += estimate.dynamicProbability
            width += estimate.upper90Mmol - estimate.lower90Mmol
            estimator.latestDiagnostics?.let {
                leadGap += abs(it.glucoseMmol - it.interstitialMmol)
                expectedLead += abs(it.lagMinutes * it.rateMmolPerMin)
            }
            observed += row.calibrated
            produced += estimate.glucoseMmol
            vendor += row.stock
        }
        return Triple(observed, produced, vendor)
    }

    /**
     * Sub-sample cross-correlation lag of [target] behind [reference], on first
     * differences so a constant level offset cannot masquerade as timing.
     * Positive means target is late.
     */
    private fun lagMinutes(reference: List<Float>, target: List<Float>, span: Int = 20): Double {
        val da = reference.zipWithNext { a, b -> (b - a).toDouble() }
        val db = target.zipWithNext { a, b -> (b - a).toDouble() }
        fun correlate(shift: Int): Double {
            var num = 0.0; var na = 0.0; var nb = 0.0
            for (i in span until da.size - span) {
                val x = da[i]; val y = db[i + shift]
                num += x * y; na += x * x; nb += y * y
            }
            return if (na > 0 && nb > 0) num / sqrt(na * nb) else -1.0
        }
        var peak = 0; var best = -Double.MAX_VALUE
        for (shift in -span..span) {
            val c = correlate(shift)
            if (c > best) { best = c; peak = shift }
        }
        val before = correlate(peak - 1); val after = correlate(peak + 1)
        val denominator = before - 2 * best + after
        val offset = if (denominator != 0.0) 0.5 * (before - after) / denominator else 0.0
        return peak + offset.coerceIn(-0.5, 0.5)
    }

    @Test
    fun characteriseHowFarV2TrailsTheObservationItIsGiven() {
        val (observed, produced, vendor) = run()
        val ownInput = lagMinutes(observed, produced)
        val againstStock = lagMinutes(vendor, produced)
        val bias = produced.indices.map { (produced[it] - observed[it]).toDouble() }.average()
        val worstJump = produced.zipWithNext { a, b -> abs(b - a).toDouble() }.max()

        println(
            "DEVICE n=%d | lag vs own input=%+.2f | lag vs stock=%+.2f | bias=%+.2f | worstJump=%.2f | pDynamic=%.3f | width=%.2f | lead|B-I|=%.3f vs tau*|rate|=%.3f"
                .format(produced.size, ownInput, againstStock, bias, worstJump,
                    pDynamic.map { it.toDouble() }.average(),
                    width.map { it.toDouble() }.average(),
                    leadGap.map { it.toDouble() }.average(),
                    expectedLead.map { it.toDouble() }.average())
        )

        // Not yet a pass/fail bar. V2 currently trails its own observation by
        // about 0.7 min and stock by about 1.1, and that is not tunable away:
        // every process-noise setting that cuts it below ~0.6 also stops the
        // IMM separating a real excursion from a sensor artifact, which is the
        // one thing it must not give up. Fixing it needs a decisive IMM, not a
        // bigger Q. Bounded here only so a regression is visible.
        assertTrue("lag behind its own observation grew: $ownInput min", ownInput <= 0.85)
        assertTrue("lag behind stock grew: $againstStock min", againstStock <= 1.30)
        // Responsiveness must not be bought with a value that teleports.
        assertTrue("worst one-minute jump $worstJump", worstJump < 1.0)
        // Nor with a systematic level shift away from the observation.
        assertTrue("systematic bias $bias mmol/L", abs(bias) < 0.25)
    }
}
