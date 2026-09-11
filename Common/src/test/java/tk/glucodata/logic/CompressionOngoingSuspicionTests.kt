package tk.glucodata.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.logic.CompressionLowDetector.Sample
import tk.glucodata.logic.CompressionLowDetector.Tuning

/**
 * The live-alarm variant: at hold-decision time only the falling edge exists, so
 * [CompressionLowDetector.assessOngoing] must judge conditions 1-4 on a trace that ENDS
 * mid-fall. Anything doubtful returns null — at this call site null means "the alarm
 * fires normally", which is the safe direction.
 */
class CompressionOngoingSuspicionTests {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun trace(vararg values: Float): List<Sample> =
        values.mapIndexed { i, v -> Sample(t0 + i * minute, v) }

    // Twelve quiet minutes, then a plunge caught mid-fall at 62: the moment a LOW alarm
    // for threshold 80 would be firing.
    private val ongoingPlunge = trace(
        120f, 121f, 120f, 119f, 120f, 121f, 120f, 120f, 119f, 120f, 121f, 120f,
        114f, 104f, 92f, 80f, 70f, 62f
    )

    private val nowAtLastSample = t0 + 17 * minute

    private fun assess(
        samples: List<Sample>,
        nowMs: Long = nowAtLastSample,
        isf: Float = 54f,
        iob: Float = 0.3f,
        peakPassed: Boolean = true,
        tuning: Tuning = Tuning.DEFAULT
    ) = CompressionLowDetector.assessOngoing(samples, nowMs, isf, iob, peakPassed, tuning)

    @Test
    fun ongoingPlungeFromQuietBaselineIsSuspect() {
        // The onset walk-back lands on the last sample of the non-increasing run's
        // shoulder — here the 121 one minute before the noisy prefix's final 120.
        val suspect = assess(ongoingPlunge)
        assertNotNull(suspect)
        assertEquals(t0 + 10 * minute, suspect!!.onsetMillis)
        assertEquals(121f, suspect.baselineMgdl, 0.01f)
        assertEquals(62f, suspect.currentMgdl, 0.01f)
        assertEquals(59f, suspect.depthMgdl, 0.01f)
        assertTrue("mean ${suspect.meanDropMgdlPerMinute}", suspect.meanDropMgdlPerMinute <= -8f)
        assertEquals(0.3f * 54f, suspect.explainableDropMgdl, 0.01f)
    }

    @Test
    fun aPerfectlyConstantBaselineStillYieldsItsQuietWindow() {
        // A constant 120 prefix must not be swallowed by the onset walk-back — the
        // plateau is baseline, and the plunge out of it is the textbook suspect.
        val constant = trace(
            120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f,
            114f, 104f, 92f, 80f, 70f, 62f
        )
        val suspect = assess(constant)
        assertNotNull(suspect)
        assertEquals(t0 + 11 * minute, suspect!!.onsetMillis)
        assertEquals(120f, suspect.baselineMgdl, 0.01f)
    }

    @Test
    fun insulinThatExplainsTheFallBlocksSuspicion() {
        assertNull(assess(ongoingPlunge, iob = 2.5f))
    }

    @Test
    fun brokenIobBlocksSuspicion() {
        assertNull(assess(ongoingPlunge, iob = Float.NaN))
        assertNull(assess(ongoingPlunge, iob = -0.1f))
    }

    @Test
    fun dosePeakAheadBlocksSuspicion() {
        assertNull(assess(ongoingPlunge, peakPassed = false))
    }

    @Test
    fun staleDataBlocksSuspicion() {
        // The newest sample is 7 minutes old: whatever was falling then is history now.
        assertNull(assess(ongoingPlunge, nowMs = nowAtLastSample + 7 * minute))
    }

    @Test
    fun aFlatTraceIsNoSuspect() {
        val flat = trace(
            120f, 121f, 120f, 119f, 120f, 121f, 120f, 120f, 119f, 120f, 121f, 120f
        )
        assertNull(assess(flat, nowMs = t0 + 11 * minute))
    }

    @Test
    fun shallowOngoingDipIsNoSuspect() {
        val shallow = trace(
            120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f,
            112f, 104f
        )
        assertNull(assess(shallow, nowMs = t0 + 13 * minute))
    }

    @Test
    fun fallOutOfAFallingCurveIsNoSuspect() {
        val falling = trace(
            140f, 139f, 137f, 136f, 134f, 133f, 131f, 130f, 128f, 127f, 125f, 124f,
            118f, 108f, 96f, 84f, 74f, 66f
        )
        assertNull(assess(falling))
    }

    @Test
    fun slowDescentIsNoSuspect() {
        // Depth passes 25 eventually, but at -1.5/min mean it is physiology.
        val slow = trace(
            120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f
        ) + (1..20).map { Sample(t0 + (11 + it) * minute, 120f - it * 1.5f) }
        assertNull(assess(slow, nowMs = t0 + 31 * minute))
    }

    @Test
    fun tuningOverridesAreHonored() {
        // The owner opted for a more sensitive depth floor: the shallow dip now counts.
        val shallow = trace(
            120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f,
            112f, 100f
        )
        // iob 0.1 keeps the IOB gate out of the way: this test isolates the depth knob.
        val sensitive = Tuning(minDropDepthMgdl = 15f)
        assertNotNull(assess(shallow, nowMs = t0 + 13 * minute, iob = 0.1f, tuning = sensitive))
    }

    @Test
    fun nonsenseTuningFallsBackInsteadOfCrashing() {
        val garbage = Tuning(
            suspectDropMgdlPerMinute = Float.NaN,
            minDropDepthMgdl = -50f,
            unexplainedFactor = Float.NEGATIVE_INFINITY
        )
        // NaN rate falls back to the default, negative depth clamps to the floor —
        // detection still runs and the textbook plunge is still a suspect.
        assertNotNull(assess(ongoingPlunge, tuning = garbage))
    }
}
