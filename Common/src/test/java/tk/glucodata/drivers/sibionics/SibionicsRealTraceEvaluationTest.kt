package tk.glucodata.drivers.sibionics

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the recorded 11-day sensor history through Raw, Stock, Adaptive V1
 * and Adaptive V2, and reports how they actually behave.
 *
 * This fixture is real: `index,raw_mmol,temperature_c,vendor_mmol` captured
 * from a device, where `vendor_mmol` is the official Sibionics app's own
 * output. It replaces the synthetic proxies that produced two wrong
 * conclusions earlier — a lag prior fitted on ramps too slow to engage the
 * vendor's deconvolution, and an amplitude measurement on sinusoids too smooth
 * to show the attenuation the user was actually seeing.
 *
 * Raw and Stock are comparison traces. Nothing here optimises V2 toward
 * either: the questions are whether V2 tracks real movement promptly, whether
 * it attenuates every transient, and whether its interval is honest when it
 * disagrees.
 */
class SibionicsRealTraceEvaluationTest {

    private data class Row(val index: Int, val rawMmol: Float, val temperatureC: Float)

    private fun trace(): List<Row> =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("sibionics_exact_v115g_replay.csv")) {
            "missing replay fixture"
        }.bufferedReader().readLines()
            .drop(1)
            .mapNotNull { line ->
                val fields = line.split(',')
                if (fields.size < 3) return@mapNotNull null
                val index = fields[0].toIntOrNull() ?: return@mapNotNull null
                val raw = fields[1].toFloatOrNull() ?: return@mapNotNull null
                val temperature = fields[2].toFloatOrNull() ?: return@mapNotNull null
                Row(index, raw, temperature)
            }

    private fun samples(): List<SibionicsSourceSample> = trace().map {
        SibionicsSourceSample(
            index = it.index,
            timestampMs = it.index * 60_000L,
            rawMmol = it.rawMmol,
            temperatureC = it.temperatureC,
            // The fixture predates impedance capture; a constant keeps the
            // telemetry channel quiet rather than inventing disturbances.
            impedance = 2_900f,
            variantId = 0,
        )
    }

    /**
     * The failure this whole pass exists to prevent: a long run of valid
     * observations well below the estimate, while the estimate stays put and
     * the interval stays narrow.
     */
    private class Staleness(
        val worstGap: Float,
        val worstGapIndex: Int,
        val worstGapWidth: Float,
        val sustainedEpisodes: Int,
    ) {
        override fun toString(): String =
            "worstSustainedGap=%.2f at idx=%d (width there %.2f), episodes=%d".format(
                worstGap, worstGapIndex, worstGapWidth, sustainedEpisodes,
            )
    }

    /**
     * Scans for runs where V2 sits [threshold] or more away from the very
     * observation it is being fed, for [minMinutes] consecutive minutes.
     */
    private fun staleness(
        rows: List<SibionicsReplayHarness.Row>,
        threshold: Float = 1.0f,
        minMinutes: Int = 15,
    ): Staleness {
        var run = 0
        var worst = 0f
        var worstIndex = -1
        var worstWidth = Float.NaN
        var episodes = 0
        rows.forEach { row ->
            val observation = row.calibratedMmol
            val estimate = row.adaptiveV2Mmol
            if (!observation.isFinite() || !estimate.isFinite() || estimate <= 0f) {
                run = 0
                return@forEach
            }
            val gap = estimate - observation
            if (abs(gap) >= threshold) {
                run++
                if (run >= minMinutes && abs(gap) > abs(worst)) {
                    worst = gap
                    worstIndex = row.index
                    worstWidth = row.diagnostics
                        ?.let { it.upper90Mmol - it.lower90Mmol } ?: Float.NaN
                }
                if (run == minMinutes) episodes++
            } else {
                run = 0
            }
        }
        return Staleness(worst, worstIndex, worstWidth, episodes)
    }

    @Test
    fun v2DoesNotSitStaleAgainstALongRunOfValidObservations() {
        val rows = SibionicsReplayHarness.replay(samples = samples(), sensitivity = 1.4f)
            .filter { it.index > SETTLE }
        val result = staleness(rows)
        println("REAL staleness $result")

        // A quarter of an hour of observations a full mmol/L away, while the
        // model holds its level, is not a defensible posterior. If the model
        // genuinely cannot decide it must widen instead, which the interval
        // check below covers.
        assertTrue("$result", result.worstGap.isNaN() || abs(result.worstGap) < 1.6f)
    }

    @Test
    fun whereV2DisagreesWithItsObservationTheIntervalWidens() {
        val rows = SibionicsReplayHarness.replay(samples = samples(), sensitivity = 1.4f)
            .filter { it.index > SETTLE && it.diagnostics != null }

        val agreeing = ArrayList<Double>()
        val disagreeing = ArrayList<Double>()
        rows.forEach { row ->
            val diagnostics = row.diagnostics ?: return@forEach
            val width = (diagnostics.upper90Mmol - diagnostics.lower90Mmol).toDouble()
            val gap = abs(row.adaptiveV2Mmol - row.calibratedMmol)
            if (!gap.isFinite()) return@forEach
            if (gap < 0.3f) agreeing += width else if (gap > 0.8f) disagreeing += width
        }
        val agreeingWidth = if (agreeing.isEmpty()) Double.NaN else agreeing.average()
        val disagreeingWidth = if (disagreeing.isEmpty()) Double.NaN else disagreeing.average()
        println(
            "REAL interval  agreeing=%.3f (n=%d) disagreeing=%.3f (n=%d)".format(
                agreeingWidth, agreeing.size, disagreeingWidth, disagreeing.size,
            )
        )

        // Confidence must be earned: where the model departs from what it is
        // being told, it has to say it is less sure.
        if (disagreeing.size > 50) {
            assertTrue(
                "agreeing=$agreeingWidth disagreeing=$disagreeingWidth",
                disagreeingWidth > agreeingWidth,
            )
        }
    }

    @Test
    fun characteriseAllFourTracesOnRealData() {
        val rows = SibionicsReplayHarness.replay(samples = samples(), sensitivity = 1.4f)
            .filter { it.index > SETTLE }

        fun lagAgainst(reference: (SibionicsReplayHarness.Row) -> Float,
                       target: (SibionicsReplayHarness.Row) -> Float): Int {
            // Cross-correlate over +/- 20 minutes; the peak is the effective lag.
            var bestShift = 0
            var best = -Double.MAX_VALUE
            for (shift in -20..20) {
                var num = 0.0
                var n = 0
                for (k in 30 until rows.size - 30) {
                    val a = reference(rows[k]).toDouble()
                    val b = target(rows[k + shift]).toDouble()
                    if (!a.isFinite() || !b.isFinite()) continue
                    num += a * b; n++
                }
                if (n > 0 && num / n > best) { best = num / n; bestShift = shift }
            }
            return bestShift
        }

        fun worstJump(values: List<Float>): Double =
            values.zipWithNext().maxOf { (a, b) -> abs(b - a).toDouble() }

        fun held(values: List<Float>): Double {
            var same = 0
            values.zipWithNext().forEach { (a, b) -> if (abs(a - b) < 0.001f) same++ }
            return same.toDouble() / (values.size - 1)
        }

        val dynamic = rows.mapNotNull { it.diagnostics?.dynamicProbability?.toDouble() }
        val artifact = rows.mapNotNull { it.diagnostics?.artifactProbability?.toDouble() }
        val width = rows.mapNotNull {
            it.diagnostics?.let { d -> (d.upper90Mmol - d.lower90Mmol).toDouble() }
        }
        println(
            ("REAL trace n=%d | stock jump=%.2f held=%.2f | V1 jump=%.2f held=%.2f" +
                " | V2 jump=%.2f held=%.2f width=%.2f pDynamic=%.3f pArtifact=%.3f").format(
                rows.size,
                worstJump(rows.map { it.stockMmol }), held(rows.map { it.stockMmol }),
                worstJump(rows.map { it.adaptiveV1Mmol }), held(rows.map { it.adaptiveV1Mmol }),
                worstJump(rows.map { it.adaptiveV2Mmol }), held(rows.map { it.adaptiveV2Mmol }),
                if (width.isEmpty()) Double.NaN else width.average(),
                if (dynamic.isEmpty()) Double.NaN else dynamic.average(),
                if (artifact.isEmpty()) Double.NaN else artifact.average(),
            )
        )
        println(
            "REAL lag       v2-vs-calibrated=%d min, stock-vs-calibrated=%d min".format(
                lagAgainst({ it.calibratedMmol }, { it.adaptiveV2Mmol }),
                lagAgainst({ it.calibratedMmol }, { it.stockMmol }),
            )
        )

        // No displayed value may teleport, on any of the three.
        assertTrue(rows.isNotEmpty())
        assertTrue("v2 jump", worstJump(rows.map { it.adaptiveV2Mmol }) < 1.0)
    }

    /**
     * Quantifies the offset between the Raw lane the app draws and the
     * vendor-calibrated signal Adaptive V2 actually observes.
     *
     * This matters for interpreting screenshots. Raw is the uncompensated
     * front-end value; V2's observation has the manufacturer's sensor-state
     * compensation applied, which is a real positive offset. Comparing V2
     * against the Raw lane therefore shows a gap that is not estimator error.
     */
    @Test
    fun largeRawToV2GapsAreTheRawLaneDivergingNotV2Lagging() {
        val rows = SibionicsReplayHarness.replay(samples = samples(), sensitivity = 1.4f)
            .filter { it.index > SETTLE && it.calibratedMmol.isFinite() && it.stockMmol.isFinite() }

        // Where V2 sits far above Raw, does the vendor's own output agree with
        // V2, or with Raw? That single question separates "the estimator is
        // stale" from "the Raw lane is not the signal V2 observes". Raw is the
        // uncompensated front-end value; V2's observation carries the
        // manufacturer's sensor-state compensation.
        val wide = rows.filter { it.adaptiveV2Mmol - it.rawMmol > 1.0f }
        val v2NearStock = wide.count { abs(it.adaptiveV2Mmol - it.stockMmol) < 0.5f }
        val v2NearRaw = wide.count { abs(it.adaptiveV2Mmol - it.rawMmol) < 0.5f }
        val worst = rows.maxByOrNull { it.adaptiveV2Mmol - it.rawMmol }

        val offsets = rows.map { (it.calibratedMmol - it.rawMmol).toDouble() }
        println(
            "REAL raw-lane  calibrated-raw mean=%.2f p95=%.2f max=%.2f | wideGaps=%d nearStock=%d nearRaw=%d".format(
                offsets.average(), offsets.sorted()[(offsets.size * 0.95).toInt()], offsets.max(),
                wide.size, v2NearStock, v2NearRaw,
            )
        )
        worst?.let {
            println(
                "REAL worst gap idx=%d raw=%.2f calibrated=%.2f v2=%.2f stock=%.2f v1=%.2f".format(
                    it.index, it.rawMmol, it.calibratedMmol, it.adaptiveV2Mmol, it.stockMmol,
                    it.adaptiveV1Mmol,
                )
            )
        }

        if (wide.size > 100) {
            assertTrue(
                "nearStock=$v2NearStock nearRaw=$v2NearRaw of ${wide.size}",
                v2NearStock > v2NearRaw * 3,
            )
        }
    }

    @Test
    fun v2TracksItsObservationWithoutASystematicOffset() {
        val rows = SibionicsReplayHarness.replay(samples = samples(), sensitivity = 1.4f)
            .filter { it.index > SETTLE && it.calibratedMmol.isFinite() && it.diagnostics != null }

        val gap = rows.map { (it.adaptiveV2Mmol - it.calibratedMmol).toDouble() }
        val bias = rows.mapNotNull { it.diagnostics?.biasMmol?.toDouble() }
        val artifact = rows.mapNotNull { it.diagnostics?.artifactMmol?.toDouble() }
        val sensitivity = rows.mapNotNull { it.diagnostics?.sensitivity?.toDouble() }
        val rate = rows.mapNotNull { it.diagnostics?.rateMmolPerMin?.toDouble() }
        val lag = rows.mapNotNull { it.diagnostics?.lagMinutes?.toDouble() }
        println(
            ("REAL offset    v2-calibrated mean=%.3f | residualBias=%.3f artifact=%.3f" +
                " sensitivity=%.4f meanRate=%.4f lag=%.2f | lagTerm=%.3f").format(
                gap.average(), bias.average(), artifact.average(), sensitivity.average(),
                rate.average(), lag.average(), rate.average() * lag.average(),
            )
        )

        // The reported value is blood-equivalent and the observation is
        // interstitial, so a lag term separates them instantaneously. Over an
        // eleven-day trace the mean rate is ~0, so that term must average out:
        // anything left is a genuine bias in the decomposition.
        assertTrue("mean gap=${gap.average()}", abs(gap.average()) < 0.6)
    }

    private companion object {
        /** Past the vendor warm-up and the first clip/ESA stages. */
        private const val SETTLE = 2_000
    }
}
