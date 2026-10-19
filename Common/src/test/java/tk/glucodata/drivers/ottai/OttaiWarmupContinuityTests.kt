package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the two defects the 2026-07-29 activation exposed:
 *
 *  - nothing suppressed the post-activation settling ramp, so the sensor's first minutes were
 *    published as real glucose;
 *  - the continuity gate anchored its adjacency window on the last ACCEPTED sample, so exactly
 *    two consecutive rejections switched the gate off and let the third sample through unchecked.
 *
 * The numbers below are the readings actually recorded that evening, anchored on
 * `confirmed activeTime=1785334740` (2026-07-29 17:19:00 +0300).
 */
class OttaiWarmupContinuityTests {

    private companion object {
        const val ACTIVATION_MS = 1_785_334_740_000L

        // dataNo -> (mmol, rawCurrent), straight off the device trace.
        val OBSERVED_RAMP = listOf(
            Sample(0, 5.60f, 9_396),
            Sample(1, 2.40f, 3_064),
            Sample(2, 2.40f, 3_056),
            Sample(3, 2.80f, 3_669),
            Sample(4, 3.00f, 3_975),
            Sample(5, 3.30f, 4_635),
            Sample(6, 3.80f, 5_735),
            Sample(7, 5.00f, 7_935),
            Sample(8, 5.70f, 9_341),
            Sample(9, 5.90f, 9_726),
            Sample(10, 6.00f, 9_875),
            Sample(11, 6.10f, 10_017),
        )
    }

    private data class Sample(val dataNo: Int, val mmol: Float, val raw: Int) {
        val sampleMs: Long get() = ACTIVATION_MS + dataNo * 60_000L
    }

    @Test
    fun warmupWindow_suppressesTheWholeSettlingRamp() {
        // The false 50 mg/dL (dataNo 3) and everything before the plateau must be inside it.
        val suppressed = OBSERVED_RAMP.filter {
            OttaiConstants.isWithinWarmup(ACTIVATION_MS, it.sampleMs)
        }
        assertEquals((0..9).toList(), suppressed.map { it.dataNo })

        // dataNo 10 is the first sample at the boundary, and by then the ramp has plateaued.
        val first = OBSERVED_RAMP.first { !OttaiConstants.isWithinWarmup(ACTIVATION_MS, it.sampleMs) }
        assertEquals(10, first.dataNo)
        assertEquals(6.00f, first.mmol, 0.001f)
    }

    @Test
    fun warmupWindow_isDisabledWithoutATrustedStart() {
        // A provisional start must never reach the gate; 0 means "no trusted anchor" and has to
        // fail open, otherwise a vendor-activated sensor is blanked on every fresh connect.
        assertFalse(OttaiConstants.isWithinWarmup(0L, ACTIVATION_MS + 60_000L))
        assertFalse(OttaiConstants.isWithinWarmup(ACTIVATION_MS, 0L))
    }

    @Test
    fun adjacencyWindow_holdsAcrossTwoRecordsOrOneHundredThirtyFiveSeconds() {
        assertTrue(OttaiBleManager.isAdjacentSample(10, ACTIVATION_MS, 11, ACTIVATION_MS + 60_000L))
        assertTrue(OttaiBleManager.isAdjacentSample(10, ACTIVATION_MS, 12, ACTIVATION_MS + 120_000L))
        // Three records apart with a matching 180 s time gap is outside both tests.
        assertFalse(OttaiBleManager.isAdjacentSample(10, ACTIVATION_MS, 13, ACTIVATION_MS + 180_000L))
        // No anchor yet -> nothing to compare against.
        assertFalse(OttaiBleManager.isAdjacentSample(-1, 0L, 0, ACTIVATION_MS))
    }

    @Test
    fun continuityGate_rejectsTheThirdSampleAfterTwoRejections() {
        // The exact sequence that produced the published 50 mg/dL. Warmup is not involved here:
        // this is the gate's own mechanism, which still guards every later spike in the session.
        val gate = ContinuityGate()
        assertTrue(gate.offer(OBSERVED_RAMP[0]))   // 5.60 / 9396 -> baseline
        assertFalse(gate.offer(OBSERVED_RAMP[1]))  // 2.40 / 3064 -> excursion
        assertFalse(gate.offer(OBSERVED_RAMP[2]))  // 2.40 / 3056 -> excursion
        assertFalse(gate.offer(OBSERVED_RAMP[3]))  // 2.80 / 3669 -> was published before the fix
    }

    @Test
    fun continuityGate_anchoringOnTheAcceptedSampleIsWhatLetItThrough() {
        // Pin the defect itself: with the old anchor the third sample is not adjacent to the last
        // ACCEPTED record (dataNo gap 3, time gap 180 s), so the excursion test never ran.
        val accepted = OBSERVED_RAMP[0]
        val third = OBSERVED_RAMP[3]
        assertFalse(
            OttaiBleManager.isAdjacentSample(accepted.dataNo, accepted.sampleMs, third.dataNo, third.sampleMs)
        )
        // The excursion test would have caught it comfortably had it been reached.
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = third.mmol,
                candidateRaw = third.raw,
                baselineMmol = accepted.mmol,
                baselineRaw = accepted.raw,
            )
        )
    }

    @Test
    fun continuityGate_yieldsRatherThanLatchingForever() {
        // The comparison baseline only moves on an accepted sample, so a genuine step change must
        // not be able to blind the gate for the rest of the session.
        val gate = ContinuityGate()
        assertTrue(gate.offer(Sample(0, 12.0f, 22_000)))
        var dataNo = 1
        repeat(OttaiBleManager.MAX_CONSECUTIVE_CONTINUITY_REJECTS) {
            assertFalse(gate.offer(Sample(dataNo++, 4.0f, 6_000)))
        }
        assertTrue(gate.offer(Sample(dataNo, 4.0f, 6_000)))
        // ...and the yielded sample becomes the new baseline, so the stream continues normally.
        assertTrue(gate.offer(Sample(dataNo + 1, 4.1f, 6_100)))
    }

    @Test
    fun continuityGate_isNotPoisonedByAnOlderBackfillRecord() {
        // 2026-08-07, 6CA04230E260. A hole retry for [0,1) returned the sensor's first minute —
        // dataNo 0, 7.70 mmol, raw 19091, from two weeks earlier — on the same accept path live
        // readings use. It replaced the 5.00 mmol baseline, and the next three live samples were
        // refused as one-minute excursions until the yield valve re-baselined the gate: 20:02
        // through 20:04 lost, with the sensor streaming normally throughout.
        val gate = ContinuityGate()
        assertTrue(gate.offer(Sample(21_437, 5.00f, 13_073)))
        // Still accepted — it is a real record and belongs in history.
        assertTrue(gate.offer(Sample(0, 7.70f, 19_091)))
        assertTrue(gate.offer(Sample(21_438, 5.00f, 13_057)))
        assertTrue(gate.offer(Sample(21_439, 5.00f, 13_018)))
        assertTrue(gate.offer(Sample(21_440, 5.00f, 13_042)))
    }

    @Test
    fun continuityGate_theStaleBaselineIsWhatRefusedTheLiveStream() {
        // Pin the mechanism: against the stale dataNo=0 record the live sample really does look
        // like an excursion, so nothing but the baseline itself was wrong.
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 5.00f,
                candidateRaw = 13_057,
                baselineMmol = 7.70f,
                baselineRaw = 19_091,
            )
        )
        // And the record that should have been the baseline passes cleanly.
        assertFalse(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 5.00f,
                candidateRaw = 13_057,
                baselineMmol = 5.00f,
                baselineRaw = 13_073,
            )
        )
    }

    /**
     * Mirrors the accept/reject bookkeeping of OttaiBleManager.emitReading around the continuity
     * gate, using the same pure predicates the driver calls.
     */
    private class ContinuityGate {
        private var baselineDataNo = -1
        private var baselineMmol = Float.NaN
        private var baselineRaw = 0
        private var evaluatedDataNo = -1
        private var evaluatedSampleMs = 0L
        private var consecutiveRejects = 0

        fun offer(s: Sample): Boolean {
            if (consecutiveRejects >= OttaiBleManager.MAX_CONSECUTIVE_CONTINUITY_REJECTS) {
                accept(s)
                return true
            }
            val reject = OttaiBleManager.isAdjacentSample(
                evaluatedDataNo, evaluatedSampleMs, s.dataNo, s.sampleMs,
            ) && OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = s.mmol,
                candidateRaw = s.raw,
                baselineMmol = baselineMmol,
                baselineRaw = baselineRaw,
            )
            if (reject) {
                consecutiveRejects++
                noteEvaluated(s)
                return false
            }
            accept(s)
            return true
        }

        private fun accept(s: Sample) {
            noteEvaluated(s)
            // The baseline only moves forward: history is accepted on this same path, and an old
            // backfill record must not become the reference for the live stream.
            if (baselineDataNo >= 0 && s.dataNo < baselineDataNo) return
            baselineDataNo = s.dataNo
            baselineMmol = s.mmol
            baselineRaw = s.raw
            consecutiveRejects = 0
        }

        private fun noteEvaluated(s: Sample) {
            if (s.dataNo < evaluatedDataNo) return
            evaluatedDataNo = s.dataNo
            evaluatedSampleMs = s.sampleMs
        }
    }
}
