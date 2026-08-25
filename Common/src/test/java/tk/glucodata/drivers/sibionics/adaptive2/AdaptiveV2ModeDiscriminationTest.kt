package tk.glucodata.drivers.sibionics.adaptive2

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.sibionics.SibionicsSensorObservation

/**
 * Proof that the IMM actually switches.
 *
 * This exists because it once did not, and nothing caught it. The glucose
 * block's covariance was derived from the jerk density alone, so the configured
 * DYNAMIC rate freedom was never read; DYNAMIC's predicted variance stayed far
 * below the measurement noise, its likelihood never separated from STEADY's,
 * and the mode probabilities tracked the transition prior exactly. Measured on
 * a moving trace against a flat one, the numbers were 0.10 and 0.09 — a
 * four-mode filter computing one answer, and every downstream symptom
 * (over-smoothing, attenuated peaks) followed from it.
 *
 * Output amplitude cannot detect that, so these tests assert on the mode
 * probabilities themselves and print them.
 */
class AdaptiveV2ModeDiscriminationTest {

    private fun context(): SibionicsAdaptiveV2Context =
        SibionicsAdaptiveV2Context().apply { configure(1.4f) }

    private fun SibionicsAdaptiveV2Context.feed(
        index: Int,
        calibrated: Float,
        impedance: Float = 2_900f,
    ): ProbabilisticGlucoseEstimate? = process(
        observation = SibionicsSensorObservation(
            calibratedMmol = calibrated,
            chemicalMmol = calibrated,
            sensorStateCompensationMmol = 0f,
            qualityFlags = 0,
            factorySensitivity = 1.4f,
            activeSensitivity = 1.4f,
            sensorAgeMinutes = index,
            family = 115,
        ),
        temperatureC = 34f,
        impedance = impedance,
        eventTimeMs = index * 60_000L,
    )

    private class Modes(val steady: Double, val dynamic: Double, val artifact: Double, val drift: Double) {
        override fun toString(): String =
            "pSteady=%.3f pDynamic=%.3f pArtifact=%.3f pDrift=%.3f".format(steady, dynamic, artifact, drift)
    }

    private fun averageModes(estimates: List<ProbabilisticGlucoseEstimate>): Modes = Modes(
        estimates.map { it.steadyProbability.toDouble() }.average(),
        estimates.map { it.dynamicProbability.toDouble() }.average(),
        estimates.map { it.artifactProbability.toDouble() }.average(),
        estimates.map { it.driftProbability.toDouble() }.average(),
    )

    /** Settles on a flat trace with realistic minute noise. */
    private fun settled(level: Float = 6f, seed: Int = 5): SibionicsAdaptiveV2Context {
        val random = Random(seed)
        return context().apply {
            repeat(240) { offset ->
                feed(START + offset, level + (random.nextFloat() - 0.5f) * 0.16f)
            }
        }
    }

    @Test
    fun aFlatTraceKeepsTheSteadyHypothesisDominant() {
        val random = Random(11)
        val context = settled()
        val estimates = (0 until 90).mapNotNull { offset ->
            context.feed(START + 240 + offset, 6f + (random.nextFloat() - 0.5f) * 0.16f)
        }
        val modes = averageModes(estimates)
        println("IMM flat            $modes")

        assertTrue("$modes", modes.steady > 0.55)
        assertTrue("$modes", modes.dynamic < 0.25)
    }

    @Test
    fun aRealExcursionMakesTheDynamicHypothesisDominant() {
        val random = Random(11)
        val context = settled()
        // A real excursion has curvature: it starts, turns and reverses. A
        // *constant* ramp is not the discriminating case, because a
        // constant-velocity model predicts one perfectly — STEADY holds v and
        // a states too, so once its velocity converges it explains a straight
        // ramp with strictly less noise than DYNAMIC and correctly wins. What
        // separates the two hypotheses is change of rate, not rate itself.
        var level = 6f
        var rate = 0f
        val estimates = (0 until 24).mapNotNull { offset ->
            rate = when {
                offset < 8 -> rate - 0.022f
                offset < 16 -> rate + 0.022f
                else -> rate - 0.018f
            }
            level += rate
            context.feed(START + 240 + offset, level + (random.nextFloat() - 0.5f) * 0.16f)
        }
        val modes = averageModes(estimates.drop(4))
        val peak = estimates.maxOf { it.dynamicProbability.toDouble() }
        println("IMM excursion       $modes peakDynamic=%.3f".format(peak))

        assertTrue("$modes", modes.dynamic > modes.steady)
        assertTrue("peak=$peak", peak > 0.7)
    }

    @Test
    fun theDynamicHypothesisIsMateriallyStrongerOnMotionThanOnAFlatTrace() {
        val random = Random(11)
        val flat = settled().let { context ->
            (0 until 40).mapNotNull { offset ->
                context.feed(START + 240 + offset, 6f + (random.nextFloat() - 0.5f) * 0.16f)
            }
        }
        val ramping = settled().let { context ->
            var level = 6f
            (0 until 40).mapNotNull { offset ->
                level -= 0.09f
                context.feed(START + 240 + offset, level + (random.nextFloat() - 0.5f) * 0.16f)
            }
        }
        val flatModes = averageModes(flat)
        val rampModes = averageModes(ramping)
        println("IMM flat vs ramp    flat[$flatModes] ramp[$rampModes]")

        // The failing version scored 0.09 against 0.10 here.
        assertTrue(
            "flat=${flatModes.dynamic} ramp=${rampModes.dynamic}",
            rampModes.dynamic > flatModes.dynamic * 2.5,
        )
    }

    @Test
    fun anIsolatedExcursionIsWonByTheArtifactHypothesis() {
        val context = settled()
        val during = context.feed(START + 240, 2.9f)!!
        val modes = Modes(
            during.steadyProbability.toDouble(), during.dynamicProbability.toDouble(),
            during.artifactProbability.toDouble(), during.driftProbability.toDouble(),
        )
        println("IMM single excursion $modes glucose=%.2f".format(during.glucoseMmol))

        // One sample cannot be a 3 mmol/L glucose move; the sensor is the only
        // explanation with any prior mass at all.
        assertTrue("$modes", modes.artifact > modes.dynamic)
        assertTrue("$modes", modes.artifact > 0.15)
    }

    @Test
    fun anImpedanceDisturbedExcursionFavoursArtifactMoreStronglyStill() {
        val quiet = settled().feed(START + 240, 4.4f)!!
        val disturbed = settled().feed(START + 240, 4.4f, impedance = 5_400f)!!
        println(
            "IMM impedance       quiet pArtifact=%.3f disturbed pArtifact=%.3f".format(
                quiet.artifactProbability, disturbed.artifactProbability,
            )
        )

        assertTrue(
            "quiet=${quiet.artifactProbability} disturbed=${disturbed.artifactProbability}",
            disturbed.artifactProbability > quiet.artifactProbability,
        )
    }

    @Test
    fun aCoherentRampOutgrowsTheArtifactHypothesisAsItPersists() {
        val random = Random(7)
        val context = settled()
        var level = 6f
        val artifactTrace = ArrayList<Double>()
        val dynamicTrace = ArrayList<Double>()
        repeat(18) { offset ->
            level -= 0.12f
            context.feed(START + 240 + offset, level + (random.nextFloat() - 0.5f) * 0.16f)?.let {
                artifactTrace += it.artifactProbability.toDouble()
                dynamicTrace += it.dynamicProbability.toDouble()
            }
        }
        println(
            "IMM persistence     early pArtifact=%.3f pDynamic=%.3f | late pArtifact=%.3f pDynamic=%.3f".format(
                artifactTrace[2], dynamicTrace[2], artifactTrace.last(), dynamicTrace.last(),
            )
        )

        // Onset is genuinely ambiguous; persistence is what resolves it.
        assertTrue(
            "early=${artifactTrace[2]} late=${artifactTrace.last()}",
            artifactTrace.last() < artifactTrace[2],
        )
        assertTrue(
            "dynamic late=${dynamicTrace.last()} artifact late=${artifactTrace.last()}",
            dynamicTrace.last() > artifactTrace.last(),
        )
    }

    private companion object {
        private const val START = 130
    }
}
