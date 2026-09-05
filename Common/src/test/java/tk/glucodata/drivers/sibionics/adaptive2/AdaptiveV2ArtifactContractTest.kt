package tk.glucodata.drivers.sibionics.adaptive2

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Artifact handling under the current contract.
 *
 * The contract changed, and this file exists because the old one encoded a
 * requirement that cannot be met. At the first minute, with no independent
 * evidence, a genuine sharp move and a single bad sample are the same
 * observation; a test that demands the estimator suppress one and follow the
 * other is asking for clairvoyance, and the only way to pass it is to add the
 * lag we were told to remove.
 *
 * So the responsibilities are split by what is actually knowable when:
 *
 *  - **Minute one, no independent evidence.** Follow it, and widen. A valid
 *    surprising observation is still an observation.
 *  - **Minute two, if it reversed.** That is positive evidence. Come back at
 *    once, raise artifact probability, and leave no velocity behind — a
 *    one-minute spike must not become a multi-minute lead.
 *  - **Minute one, with independent evidence** — impedance disturbance, vendor
 *    quality flags, invalid telemetry. Suppress immediately; the evidence did
 *    not need confirming.
 *
 * Large innovation on its own is never positive artifact evidence.
 */
class AdaptiveV2ArtifactContractTest {

    private fun sample(
        index: Int, value: Float, impedance: Float = 2_900f, flags: Int = 0,
    ) = AdaptiveV2Sample(
        calibratedMmol = value, factorySensitivity = 1.26f, activeSensitivity = 1.26f,
        sensorStateCompensationMmol = 0.1f, temperatureC = 34f, impedance = impedance,
        qualityFlags = flags, index = index, timestampMs = index * 60_000L,
    )

    private fun settled(level: Float = 6f, minutes: Int = 90): Pair<AdaptiveV2Estimator, Int> {
        val estimator = AdaptiveV2Estimator()
        repeat(minutes) { estimator.process(sample(it + 1, level)) }
        return estimator to minutes
    }

    @Test
    fun anIsolatedFalseLowIsFollowedThenCorrectedOnTheVeryNextSample() {
        val (estimator, n) = settled()
        val widthBefore = estimator.latestEstimate!!.let { it.upper90Mmol - it.lower90Mmol }

        val spike = estimator.process(sample(n + 1, 2.6f))!!
        val spikeWidth = spike.upper90Mmol - spike.lower90Mmol

        // Minute one: it moved, and it said it was unsure.
        assertTrue("spike did not move the median: ${spike.glucoseMmol}", spike.glucoseMmol < 5.6f)
        assertTrue(
            "uncertainty did not widen: $widthBefore -> $spikeWidth",
            spikeWidth > widthBefore * 1.5f,
        )

        // Minute two reverses it: back at once, and no lead left behind.
        val back = estimator.process(sample(n + 2, 6f))!!
        assertTrue("did not recover on the reversing sample: ${back.glucoseMmol}",
            back.glucoseMmol > 5.5f)
        assertTrue("a one-minute spike left velocity behind: ${back.rateMmolPerMin}",
            abs(back.rateMmolPerMin) < 0.15f)
        assertTrue("reversal did not raise artifact probability: ${back.artifactProbability}",
            back.artifactProbability > spike.artifactProbability)

        // And it does not linger.
        val after = (3..8).map { estimator.process(sample(n + it, 6f))!!.glucoseMmol }
        assertTrue("did not settle back: $after", after.all { abs(it - 6f) < 0.4f })
    }

    /**
     * The paired case, and the reason a sign flip cannot be the test.
     *
     * A genuine sharp peak produces the same innovation signature as a spike:
     * large positive, then large negative. The difference is only visible in
     * where the signal ends up — a spike returns to where it started, a turn
     * does not. These two tests differ solely in that, and must come out
     * opposite.
     */
    @Test
    fun aGenuineSharpPeakIsNotAnArtifactAndTurnsImmediately() {
        val (estimator, n) = settled()
        // Up hard, turn, and keep going down: never returns to 6.
        val up = listOf(7.1f, 8.3f, 9.2f)
        val down = listOf(8.6f, 7.9f, 7.2f, 6.6f)
        var minute = n
        val rising = up.map { estimator.process(sample(++minute, it))!! }
        val falling = down.map { estimator.process(sample(++minute, it))!! }
        println("PEAK obs->est " + (up + down).zip(rising + falling)
            .joinToString(" ") { "%.1f/%.2f".format(it.first, it.second.glucoseMmol) })

        val turn = falling.first()
        assertTrue("a real peak was called an artifact: ${turn.artifactProbability}",
            turn.artifactProbability < 0.3f)
        // The turn is followed on the minute it happens, not after confirmation.
        assertTrue(
            "the turn was not followed: peak=${rising.last().glucoseMmol} turn=${turn.glucoseMmol}",
            turn.glucoseMmol < rising.last().glucoseMmol,
        )
        // And the continued fall keeps its velocity rather than being damped out.
        assertTrue("velocity was stripped from a real turn: ${falling.last().rateMmolPerMin}",
            falling.last().rateMmolPerMin < -0.1f)
        assertTrue("the fall was not tracked: ${falling.last().glucoseMmol}",
            falling.last().glucoseMmol < 7.4f)
    }

    @Test
    fun aSustainedMoveIsNotTreatedAsAReversalArtifact() {
        val (estimator, n) = settled()
        val walk = (1..8).map { estimator.process(sample(n + it, 6f + it * 0.45f))!! }
        val last = walk.last()
        assertTrue("a sustained rise was called an artifact: ${last.artifactProbability}",
            last.artifactProbability < 0.35f)
        assertTrue("a sustained rise was not followed: ${last.glucoseMmol}",
            last.glucoseMmol > 8.4f)
    }

    @Test
    fun independentEvidenceOnMinuteOneSuppressesImmediately() {
        val (clean, n) = settled()
        val unflagged = clean.process(sample(n + 1, 2.6f))!!.glucoseMmol

        val (disturbed, m) = settled()
        // Impedance disturbance and vendor quality flags are evidence that did
        // not need a later sample to confirm it.
        val flagged = disturbed.process(
            sample(m + 1, 2.6f, impedance = 9_000f, flags = 0x30),
        )!!.glucoseMmol

        assertTrue(
            "independent evidence did not suppress the move: unflagged=$unflagged flagged=$flagged",
            flagged > unflagged,
        )
    }
}
