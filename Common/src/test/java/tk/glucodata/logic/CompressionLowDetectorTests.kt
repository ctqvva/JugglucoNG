package tk.glucodata.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.logic.CompressionLowDetector.Sample

/**
 * The detector's contract: mark only the V-shaped plunge that the journal's insulin
 * cannot explain, and refuse everything that could be a real hypoglycemia. The dangerous
 * failure is not a missed compression artifact (that costs a statistics correction), it
 * is a real low classified as an artifact — so every ambiguous case below must come back
 * empty. Several of these traces are adversarial constructions that defeated an earlier
 * draft: the noise blip on a physiological fall, the relapse after a treated low, the
 * one-minute baseline after an outage, carbs eaten just before onset, the W bottom.
 */
class CompressionLowDetectorTests {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    /** One sample per minute starting at t0, mg/dL. */
    private fun trace(vararg values: Float): List<Sample> =
        values.mapIndexed { i, v -> Sample(t0 + i * minute, v) }

    private fun detect(
        samples: List<Sample>,
        isf: Float = 54f,
        iob: Float = 0.3f,
        peakPassed: Boolean = true,
        carbGrams: Float = 0f,
        carbLambda: ((Long, Long) -> Float)? = null,
        sensorStart: Long? = null
    ) = CompressionLowDetector.detect(
        samples = samples,
        isfMgdlPerUnit = isf,
        iobUnitsAt = { iob },
        dosePeakPassedAt = { peakPassed },
        carbGramsBetween = carbLambda ?: { _, _ -> carbGrams },
        sensorStartMillis = sensorStart
    )

    // Twelve quiet minutes at ~120, plunge to 62 at 6-12 mg/dL/min, rebound to 110+
    // without carbs: the textbook signature. 0.3 U on board × 54 explains 16 mg/dL of a
    // 58 mg/dL fall.
    private val textbookCompression = trace(
        120f, 121f, 120f, 119f, 120f, 121f, 120f, 120f, 119f, 120f, 121f, 120f,
        114f, 104f, 92f, 80f, 70f, 62f,
        70f, 84f, 98f, 110f, 118f, 119f
    )

    @Test
    fun textbookCompressionIsDetected() {
        val episodes = detect(textbookCompression)
        assertEquals(1, episodes.size)
        val e = episodes[0]
        assertEquals(t0 + 11 * minute, e.onsetMillis)
        assertEquals(120f, e.baselineMgdl, 0.01f)
        assertEquals(62f, e.nadirMgdl, 0.01f)
        assertEquals(t0 + 17 * minute, e.nadirMillis)
        assertTrue("steepest ${e.steepestDropMgdlPerMinute}", e.steepestDropMgdlPerMinute <= -10f)
        assertEquals(0.3f * 54f, e.explainableDropMgdl, 0.01f)
        assertTrue("recovery after nadir", e.recoveryMillis > e.nadirMillis)
    }

    @Test
    fun enoughInsulinOnBoardExplainsTheDropAndBlocksDetection() {
        // 2.5 U × 54 = 135 mg/dL explainable: a real hypo is plausible, never an artifact.
        assertTrue(detect(textbookCompression, iob = 2.5f).isEmpty())
    }

    @Test
    fun brokenIobReadsAsUnknownNotAsZero() {
        // A NaN from a glitched IOB computation must abort, not impersonate "no insulin
        // on board" — that direction classifies a real insulin-driven low as an artifact.
        assertTrue(detect(textbookCompression, iob = Float.NaN).isEmpty())
        assertTrue(detect(textbookCompression, iob = -1f).isEmpty())
    }

    @Test
    fun activeDosePeakStillAheadBlocksDetection() {
        // A dose before its activity peak makes any fall physiologically plausible.
        assertTrue(detect(textbookCompression, peakPassed = false).isEmpty())
    }

    @Test
    fun fallingCurveBeforeTheDropBlocksDetection() {
        // Already descending ~1.5 mg/dL/min for twelve minutes: a steepening fall out of
        // a fall is plausible physiology, not the flat-then-plunge signature.
        val falling = trace(
            140f, 139f, 137f, 136f, 134f, 133f, 131f, 130f, 128f, 127f, 125f, 124f,
            118f, 108f, 96f, 84f, 74f, 66f,
            74f, 88f, 102f, 110f, 118f, 119f
        )
        assertTrue(detect(falling).isEmpty())
    }

    @Test
    fun physiologicalFallWithOneNoiseBlipBlocksDetection() {
        // A steady -1.5 mg/dL/min hypo (exercise, sensitivity shift) with a single noisy
        // pair at -2.3 must not qualify: the segment's MEAN rate stays physiological.
        // An earlier draft triggered on the lone pair and marked the real low.
        val descent = (1..37).map { Sample(t0 + (14 + it) * minute, 114.7f - it * 1.5f) }
        val rebound = (1..18).map { Sample(t0 + (51 + it) * minute, 59.2f + it * 3f) }
        val blipped = trace(
            120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f,
            118.5f, 117.0f, 114.7f
        ) + descent + rebound
        assertTrue(detect(blipped).isEmpty())
    }

    @Test
    fun relapseAfterATreatedLowBlocksDetection() {
        // Fell to 95, recovered on treatment, plunged again: the pre-onset window shows
        // a recent dip, so this is unstable glycemia, not a plunge out of rest.
        val decline = (1..13).map { Sample(t0 + (3 + it) * minute, 120f - it * 1.9f) }
        val rise = (1..13).map { Sample(t0 + (16 + it) * minute, 95.3f + it * 1.9f) }
        val plunge = trace(120f, 120f, 120f, 120f).take(4) + decline + rise + listOf(
            Sample(t0 + 30 * minute, 113.9f), Sample(t0 + 31 * minute, 103.9f),
            Sample(t0 + 32 * minute, 91.9f), Sample(t0 + 33 * minute, 79.9f),
            Sample(t0 + 34 * minute, 69.9f), Sample(t0 + 35 * minute, 61.9f),
            Sample(t0 + 36 * minute, 69.9f), Sample(t0 + 37 * minute, 83.9f),
            Sample(t0 + 38 * minute, 97.9f), Sample(t0 + 39 * minute, 109.9f)
        )
        assertTrue(detect(plunge).isEmpty())
    }

    @Test
    fun oneMinuteOfBaselineIsNotAQuietWindow() {
        // Recording starts two samples before the plunge (outage, restart): sixty seconds
        // of "flat" is no evidence of rest — a real hypo pausing on a stair-step passes it.
        val shortBaseline = trace(
            120f, 120f,
            114f, 104f, 92f, 80f, 70f, 62f,
            70f, 84f, 98f, 110f, 118f
        )
        assertTrue(detect(shortBaseline).isEmpty())
    }

    @Test
    fun sparselyRecordedBaselineIsNotAQuietWindow() {
        // Two samples fifteen minutes apart span the window but observe almost none of it.
        val sparse = listOf(Sample(t0, 120f), Sample(t0 + 15 * minute, 120f)) +
            (1..6).map { Sample(t0 + (15 + it) * minute, 120f - it * 10f) } +
            (1..5).map { Sample(t0 + (21 + it) * minute, 60f + it * 12f) }
        assertTrue(detect(sparse).isEmpty())
    }

    @Test
    fun noReboundMeansTheLowIsRealAndBlocksDetection() {
        val staysLow = trace(
            120f, 120f, 121f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f,
            114f, 104f, 92f, 80f, 70f, 62f,
            60f, 58f, 59f, 57f, 58f, 56f, 57f, 58f, 57f, 58f, 59f, 58f,
            57f, 58f, 59f, 58f, 57f, 58f, 59f, 58f, 57f, 58f, 59f, 58f
        )
        assertTrue(detect(staysLow).isEmpty())
    }

    @Test
    fun carbsInTheJournalExplainTheReboundAndBlockDetection() {
        // 20 g logged inside the episode: the rise is a treated real low, not pressure lifting.
        assertTrue(detect(textbookCompression, carbGrams = 20f).isEmpty())
    }

    @Test
    fun carbsEatenJustBeforeOnsetBlockDetection() {
        // Glucose tabs at onset minus eight minutes — the patient who feels it coming and
        // eats before the fall registers. An earlier draft only queried from onset onward.
        val carbTime = t0 + 3 * minute
        val episodes = detect(textbookCompression,
            carbLambda = { start, end -> if (carbTime in start..end) 20f else 0f })
        assertTrue(episodes.isEmpty())
    }

    @Test
    fun carbQueryCoversTheLookbackWindow() {
        val windows = mutableListOf<Pair<Long, Long>>()
        val episodes = detect(textbookCompression,
            carbLambda = { start, end -> windows += start to end; 0f })
        assertEquals(1, episodes.size)
        val (start, end) = windows.single()
        assertEquals(episodes[0].onsetMillis - 30 * minute, start)
        assertEquals(episodes[0].recoveryMillis, end)
    }

    @Test
    fun slowDriftDownIsNotAPlunge() {
        // -1 mg/dL/min never crosses the suspect rate.
        val drift = trace(
            160f, 159f, 158f, 157f, 156f, 155f, 154f, 153f, 152f, 151f,
            150f, 149f, 148f, 147f, 146f, 145f, 144f, 143f, 142f, 141f
        )
        assertTrue(detect(drift).isEmpty())
    }

    @Test
    fun shallowDipBelowDepthFloorIsIgnored() {
        // Steep but only 20 mg/dL deep: under MIN_DROP_DEPTH_MGDL, noise territory.
        val shallow = trace(
            120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f,
            110f, 100f, 108f, 116f, 119f, 120f
        )
        assertTrue(detect(shallow).isEmpty())
    }

    @Test
    fun aSecondDeeperDipBeforeRecoveryBlocksDetection() {
        // 62, brief lift, then 55: a W is not a V. The second dip was never vetted
        // against insulin, so the episode is refused.
        val wShape = trace(
            120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f,
            114f, 104f, 92f, 80f, 70f, 62f,
            65f, 55f, 70f, 90f, 106f, 110f
        )
        assertTrue(detect(wShape).isEmpty())
    }

    @Test
    fun steppedDescentKeepsTheTrueNadir() {
        // A plateau on the way down and at the bottom: the nadir is the real minimum,
        // not the first trough, so the episode is vetted at its full depth.
        val stepped = trace(
            120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f,
            110f, 96f, 82f, 68f, 62f, 62f, 55f, 45f, 45f,
            60f, 75f, 90f, 106f, 110f
        )
        val episodes = detect(stepped)
        assertEquals(1, episodes.size)
        assertEquals(45f, episodes[0].nadirMgdl, 0.01f)
        assertEquals(t0 + 20 * minute, episodes[0].nadirMillis)
    }

    @Test
    fun recordingGapInsideTheVBlocksDetection() {
        // 16 minutes of silence between nadir and rebound: the V shape is assumed, not
        // observed, so it must not be classified.
        val gapped = trace(
            120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f,
            114f, 104f, 92f, 80f, 70f, 62f
        ) + listOf(
            Sample(t0 + 33 * minute, 118f),
            Sample(t0 + 34 * minute, 119f),
            Sample(t0 + 35 * minute, 120f)
        )
        assertTrue(detect(gapped).isEmpty())
    }

    @Test
    fun aGapJustOverTheLimitBlocksDetection() {
        // 10 minutes 59 seconds of silence: an earlier draft truncated this to 10 whole
        // minutes and accepted the unobserved rebound.
        val gapped = trace(
            120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f,
            114f, 104f, 92f, 80f, 70f, 62f
        ) + listOf(
            Sample(t0 + 17 * minute + 659_000L, 112f),
            Sample(t0 + 17 * minute + 719_000L, 115f)
        )
        assertTrue(detect(gapped).isEmpty())
    }

    @Test
    fun placeholderZeroSamplesAreDroppedNotMeasured() {
        // Native minute slots without a reading arrive as 0 ("--" rows). A hole between
        // real readings must not read as a plunge and a recovery.
        val flatWithHoles = trace(
            120f, 121f, 0f, 120f, 119f, 0f, 121f, 120f, 0f, 120f,
            120f, 121f, 120f, 119f, 120f, 121f, 120f, 120f, 119f, 120f
        )
        assertTrue(detect(flatWithHoles).isEmpty())
    }

    @Test
    fun placeholderZerosInsideARealEpisodeDoNotHideIt() {
        val withHole = textbookCompression.toMutableList()
        withHole[14] = withHole[14].copy(mgdl = 0f)
        assertEquals(1, detect(withHole).size)
    }

    @Test
    fun missingSensitivityDisablesDetection() {
        assertTrue(detect(textbookCompression, isf = 0f).isEmpty())
        assertTrue(detect(textbookCompression, isf = Float.NaN).isEmpty())
    }

    @Test
    fun sensorAgeIsRecordedAsAWeightNotAGate() {
        val start = t0 - 14 * 60 * minute
        val episodes = detect(textbookCompression, sensorStart = start)
        assertEquals(1, episodes.size)
        assertEquals(14.2f, episodes[0].sensorAgeHoursAtOnset!!, 0.2f)
        // And without a known start the episode is still emitted, age unknown.
        assertEquals(null, detect(textbookCompression)[0].sensorAgeHoursAtOnset)
    }

    @Test
    fun newestFirstInputIsHandled() {
        assertEquals(1, detect(textbookCompression.reversed()).size)
    }

    @Test
    fun duplicateTimestampsResolveTheSameInEitherOrder() {
        // Two readings on one timestamp (calibration rewrite): the result must not
        // depend on which came first in the input.
        val withDuplicate = textbookCompression + Sample(t0 + 5 * minute, 118f)
        assertEquals(detect(withDuplicate), detect(withDuplicate.reversed()))
        assertEquals(1, detect(withDuplicate).size)
    }

    @Test
    fun twentySecondCadenceStillDetects() {
        // Dense streams: adjacent pairs are under the 30 s rate floor, so the scan reads
        // rates over a look-ahead. An earlier draft went silently blind on such input.
        val dense = textbookCompression.flatMap { s ->
            (0..2).map { Sample(s.timestampMillis + it * 20_000L, s.mgdl) }
        }
        assertEquals(1, detect(dense).size)
    }

    @Test
    fun twoSeparateEpisodesAreBothFound() {
        val second = textbookCompression.map { Sample(it.timestampMillis + 60 * minute, it.mgdl) }
        val episodes = detect(textbookCompression + second)
        assertEquals(2, episodes.size)
        assertTrue(episodes[0].onsetMillis < episodes[1].onsetMillis)
    }

    @Test
    fun reboundArrivingTooLateBlocksDetection() {
        // Recovery only after the 45-minute window: too slow for pressure lifting.
        val slowRecovery = trace(
            120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f,
            114f, 104f, 92f, 80f, 70f, 62f
        ) + (1..60).map { Sample(t0 + (17 + it) * minute, 62f + it * 0.9f) }
        assertTrue(detect(slowRecovery).isEmpty())
    }

    @Test
    fun dosePeakDerivesFromTheConfiguredCurve() {
        // Condition 2 of the spec: the peak comes from the activity curve, never from a
        // fixed minute count.
        val aspartLike = listOf(0 to 0f, 55 to 1f, 360 to 0f)
        assertEquals(55, CompressionLowDetector.peakMinuteOf(aspartLike))
        val dose = t0
        assertFalse(CompressionLowDetector.dosePeakPassed(dose, aspartLike, dose + 54 * minute))
        assertTrue(CompressionLowDetector.dosePeakPassed(dose, aspartLike, dose + 55 * minute))
        // No curve means no answer, and no answer must read as "not passed".
        assertFalse(CompressionLowDetector.dosePeakPassed(dose, emptyList(), dose + 600 * minute))
    }
}
