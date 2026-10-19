package tk.glucodata.drivers.sibionics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stock, Adaptive V1 and Adaptive V2 on identical input.
 *
 * All three are driven through the real [SibionicsAlgorithmContext] with the
 * same raw one-minute samples, so every model sees the same vendor front end.
 *
 * The comparison is deliberately **differential**, not absolute. The vendor
 * core applies its own sensitivity, correction, clipping and deconvolution to a
 * raw sample, so its output does not live in the same calibration domain as a
 * synthetic "blood truth" a test can invent — an earlier version of this file
 * asserted against one and measured nothing but that mismatch, reporting ~29%
 * MARD for stock on its own preferred input. What *is* well posed is running
 * each model twice, once on a clean trace and once on the same trace with a
 * sensor event injected, and asking how far each one moves. The truth does not
 * change between the two runs, so any movement is the model reacting to the
 * sensor rather than to glucose.
 *
 * Stock is a benchmark trace here, never a target.
 */
class SibionicsModelComparisonTest {

    private class Scenario(
        val rawMmol: FloatArray,
        val temperatureC: FloatArray,
        val impedance: FloatArray,
    )

    /**
     * A raw one-minute trace with a returning meal excursion and a genuine
     * sustained fall, plus an optional compression-like sensor event.
     *
     * The dip moves the *sample*, never the underlying trajectory, which is
     * what makes the clean run a usable reference for the disturbed one.
     */
    private fun buildScenario(
        minutes: Int,
        seed: Int,
        withCompressionDip: Boolean,
    ): Scenario {
        val random = Random(seed)
        val raw = FloatArray(minutes)
        val temperature = FloatArray(minutes)
        val impedance = FloatArray(minutes)
        var level = 6.5f
        var wander = 0f

        for (minute in 0 until minutes) {
            var rate = 0f
            // A full sine period rises and returns, rather than ramping away.
            if (minute in MEAL) {
                rate += 0.050f * sin(2.0 * PI * (minute - MEAL.first) / MEAL.count()).toFloat()
            }
            if (minute in HYPO_FALL) rate -= 0.032f
            if (minute in HYPO_RECOVERY) rate += 0.030f

            level = (level + rate).coerceIn(2.5f, 18f)
            wander += (random.nextFloat() - 0.5f) * 0.05f
            wander *= 0.92f

            var sample = level + wander + (random.nextFloat() - 0.5f) * 0.20f
            var resistance = 2_900f + (random.nextFloat() - 0.5f) * 60f
            if (withCompressionDip && minute in COMPRESSION_DIP) {
                val depth = sin(PI * (minute - COMPRESSION_DIP.first) / COMPRESSION_DIP.count()).toFloat()
                sample -= 0.34f * sample * depth
                resistance += 2_400f * depth
            }

            raw[minute] = max(sample, 0.6f)
            temperature[minute] = 33.9f + (random.nextFloat() - 0.5f) * 0.4f
            impedance[minute] = resistance
        }
        return Scenario(raw, temperature, impedance)
    }

    private fun run(scenario: Scenario, selection: SibionicsAlgorithmSelection): FloatArray {
        val context = SibionicsAlgorithmContext("compare-${selection.storageId}").apply {
            configure("46HU804EBJ4", 1.4f, SibionicsConstants.Variant.CHINESE, selection)
        }
        return FloatArray(scenario.rawMmol.size) { minute ->
            val index = minute + 1
            context.process(
                rawMmol = scenario.rawMmol[minute],
                temperatureC = scenario.temperatureC[minute],
                index = index,
                mode = SibionicsAlgorithmMode.REPLAY,
                impedance = scenario.impedance[minute],
                eventTimeMs = index * 60_000L,
            )
        }
    }

    private class V2Run(
        val values: FloatArray,
        val widths: FloatArray,
        val artifactProbability: FloatArray,
    )

    /** Adaptive V2 with its posterior captured per sample. */
    private fun runV2(scenario: Scenario): V2Run {
        val context = SibionicsAlgorithmContext("compare-v2-detail").apply {
            configure(
                "46HU804EBJ4", 1.4f, SibionicsConstants.Variant.CHINESE,
                SibionicsAlgorithmSelection.ADAPTIVE_V2,
            )
        }
        val size = scenario.rawMmol.size
        val values = FloatArray(size)
        val widths = FloatArray(size) { Float.NaN }
        val artifact = FloatArray(size) { Float.NaN }
        for (minute in 0 until size) {
            val index = minute + 1
            values[minute] = context.process(
                rawMmol = scenario.rawMmol[minute],
                temperatureC = scenario.temperatureC[minute],
                index = index,
                mode = SibionicsAlgorithmMode.REPLAY,
                impedance = scenario.impedance[minute],
                eventTimeMs = index * 60_000L,
            )
            context.latestProbabilisticEstimate()?.let {
                widths[minute] = it.upper90Mmol - it.lower90Mmol
                artifact[minute] = it.artifactProbability
            }
        }
        return V2Run(values, widths, artifact)
    }

    private fun meanOver(values: FloatArray, range: IntRange): Double {
        val usable = range.mapNotNull { values.getOrNull(it)?.takeIf { value -> value.isFinite() } }
        return if (usable.isEmpty()) Double.NaN else usable.map { it.toDouble() }.average()
    }

    /** Mean absolute displacement of a model's disturbed run from its own clean run. */
    private fun meanDisplacement(clean: FloatArray, disturbed: FloatArray, range: IntRange): Double {
        var sum = 0.0
        var count = 0
        range.forEach { minute ->
            val a = clean.getOrNull(minute) ?: return@forEach
            val b = disturbed.getOrNull(minute) ?: return@forEach
            if (!a.isFinite() || !b.isFinite() || a <= 0f || b <= 0f) return@forEach
            sum += abs(b - a)
            count++
        }
        return if (count == 0) Double.NaN else sum / count
    }

    /** Largest downward displacement, which is the direction that matters for a false low. */
    private fun worstDrop(clean: FloatArray, disturbed: FloatArray, range: IntRange): Float {
        var worst = 0f
        range.forEach { minute ->
            val a = clean.getOrNull(minute) ?: return@forEach
            val b = disturbed.getOrNull(minute) ?: return@forEach
            if (!a.isFinite() || !b.isFinite() || a <= 0f || b <= 0f) return@forEach
            worst = maxOf(worst, a - b)
        }
        return worst
    }

    private fun minimumOver(output: FloatArray, range: IntRange): Float {
        var minimum = Float.MAX_VALUE
        range.forEach { minute ->
            val value = output.getOrNull(minute) ?: return@forEach
            if (value.isFinite() && value > 0f) minimum = minOf(minimum, value)
        }
        return minimum
    }

    private fun maximumOver(output: FloatArray, range: IntRange): Float {
        var maximum = 0f
        range.forEach { minute ->
            val value = output.getOrNull(minute) ?: return@forEach
            if (value.isFinite() && value > 0f) maximum = maxOf(maximum, value)
        }
        return maximum
    }

    @Test
    fun everyModelProducesAUsableTrajectoryOnACleanTrace() {
        val scenario = buildScenario(MINUTES, seed = 31, withCompressionDip = false)
        val outputs = mapOf(
            "stock" to run(scenario, SibionicsAlgorithmSelection.STOCK),
            "v1" to run(scenario, SibionicsAlgorithmSelection.STATE_MODEL),
            "v2" to run(scenario, SibionicsAlgorithmSelection.ADAPTIVE_V2),
        )
        outputs.forEach { (name, output) ->
            println(
                "clean trace  %s: min=%.2f max=%.2f".format(
                    name, minimumOver(output, SETTLE until MINUTES),
                    maximumOver(output, SETTLE until MINUTES),
                )
            )
        }

        outputs.forEach { (name, output) ->
            val minimum = minimumOver(output, SETTLE until MINUTES)
            val maximum = maximumOver(output, SETTLE until MINUTES)
            // The vendor core compresses the raw range into its own calibration
            // domain, so the assertion is about movement rather than absolute
            // levels: each model has to follow the meal up and the fall down,
            // and stay inside physiological range while doing it.
            assertTrue("$name min=$minimum max=$maximum", maximum - minimum > 2.5f)
            assertTrue("$name min=$minimum", minimum > 1.5f)
            assertTrue("$name max=$maximum", maximum < 20f)
        }
    }

    @Test
    fun v2WidensAndFlagsArtifactRiskThroughASustainedCompressionDip() {
        val clean = buildScenario(MINUTES, seed = 31, withCompressionDip = false)
        val disturbed = buildScenario(MINUTES, seed = 31, withCompressionDip = true)

        val cleanV2 = runV2(clean)
        val disturbedV2 = runV2(disturbed)
        val stockDrop = worstDrop(
            run(clean, SibionicsAlgorithmSelection.STOCK),
            run(disturbed, SibionicsAlgorithmSelection.STOCK),
            COMPRESSION_DIP,
        )
        val v2Drop = worstDrop(cleanV2.values, disturbedV2.values, COMPRESSION_DIP)
        val quietWidth = meanOver(disturbedV2.widths, QUIET_WINDOW)
        val dipWidth = meanOver(disturbedV2.widths, COMPRESSION_DIP)
        val quietArtifact = meanOver(disturbedV2.artifactProbability, QUIET_WINDOW)
        val dipArtifact = meanOver(disturbedV2.artifactProbability, COMPRESSION_DIP)
        println(
            "compression dip  drop stock=%.2f V2=%.2f | width quiet=%.2f dip=%.2f | pArtifact quiet=%.2f dip=%.2f"
                .format(stockDrop, v2Drop, quietWidth, dipWidth, quietArtifact, dipArtifact)
        )

        // This dip lasts 40 minutes and falls at about 0.1 mmol/L/min, which is
        // an entirely plausible real fall. Refusing to follow it would be the
        // wrong answer, and V2 does follow it — roughly as far as stock does.
        // What V2 adds is that it says so: the band opens and the artifact
        // hypothesis gains ground, instead of reporting the low as a certainty.
        assertTrue("stock=$stockDrop v2=$v2Drop", v2Drop < stockDrop + 0.5f)
        assertTrue("quiet=$quietWidth dip=$dipWidth", dipWidth > quietWidth)
        assertTrue("quiet=$quietArtifact dip=$dipArtifact", dipArtifact > quietArtifact)
    }

    @Test
    fun hardenedV1DoesNotAmplifyAnUnsupportedExcursion() {
        val clean = buildScenario(MINUTES, seed = 31, withCompressionDip = false)
        val disturbed = buildScenario(MINUTES, seed = 31, withCompressionDip = true)

        val stockOutputs = run(disturbed, SibionicsAlgorithmSelection.STOCK)
        val v1Outputs = run(disturbed, SibionicsAlgorithmSelection.STATE_MODEL)
        val stockDrop = worstDrop(
            run(clean, SibionicsAlgorithmSelection.STOCK), stockOutputs, COMPRESSION_DIP,
        )
        val v1Drop = worstDrop(
            run(clean, SibionicsAlgorithmSelection.STATE_MODEL), v1Outputs, COMPRESSION_DIP,
        )
        var worstBelowStock = 0f
        COMPRESSION_DIP.forEach { minute ->
            val stockValue = stockOutputs.getOrNull(minute) ?: return@forEach
            val v1Value = v1Outputs.getOrNull(minute) ?: return@forEach
            if (stockValue.isFinite() && v1Value.isFinite()) {
                worstBelowStock = maxOf(worstBelowStock, stockValue - v1Value)
            }
        }
        println(
            "compression dip  V1 drop=%.2f (stock %.2f), worst below stock=%.2f".format(
                v1Drop, stockDrop, worstBelowStock,
            )
        )

        // V1 stays the conservative, stock-aware model. It is allowed to move
        // with stock; what it must not do is run away below it on an excursion
        // the chemical signal does not support.
        assertTrue("v1=$v1Drop stock=$stockDrop", v1Drop <= stockDrop + 0.3f)
        assertTrue("worstBelowStock=$worstBelowStock", worstBelowStock < 1.0f)
    }

    @Test
    fun everyModelStillFollowsTheGenuineFall() {
        val scenario = buildScenario(MINUTES, seed = 31, withCompressionDip = false)
        val stock = run(scenario, SibionicsAlgorithmSelection.STOCK)
        val v1 = run(scenario, SibionicsAlgorithmSelection.STATE_MODEL)
        val v2 = run(scenario, SibionicsAlgorithmSelection.ADAPTIVE_V2)

        val preFall = maximumOver(stock, PRE_FALL)
        val stockMinimum = minimumOver(stock, HYPO_WINDOW)
        val v1Minimum = minimumOver(v1, HYPO_WINDOW)
        val v2Minimum = minimumOver(v2, HYPO_WINDOW)
        println(
            "genuine fall  pre-fall=%.2f | stock=%.2f | V1=%.2f | V2=%.2f".format(
                preFall, stockMinimum, v1Minimum, v2Minimum,
            )
        )

        // Artifact protection in V2 and plausibility gating in V1 must not turn
        // into a floor: both have to follow a real sustained fall as far as the
        // vendor model does, give or take the lead each one applies.
        assertTrue("stock=$stockMinimum preFall=$preFall", stockMinimum < preFall - 2f)
        assertTrue("v1=$v1Minimum stock=$stockMinimum", v1Minimum < stockMinimum + 0.6f)
        assertTrue("v2=$v2Minimum stock=$stockMinimum", v2Minimum < stockMinimum + 0.6f)
    }

    private companion object {
        private const val MINUTES = 900
        /** Past the vendor warm-up and the filters' own settling. */
        private const val SETTLE = 200
        private val MEAL = 210..390
        private val COMPRESSION_DIP = 440..480
        private val HYPO_FALL = 600..700
        private val HYPO_RECOVERY = 760..860
        private val PRE_FALL = 560..600
        private val QUIET_WINDOW = 500..560
        private val HYPO_WINDOW = 600..760
    }
}
