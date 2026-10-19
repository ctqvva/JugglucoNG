package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the GDH-style delta run-length counter behind FALLING_FAST / RISING_FAST.
 *
 * The counter is the fragile, safety-critical part (it decides whether a hypo/hyper warning
 * fires), so it lives in an isolated, purely computational class exercised here without Android,
 * SharedPreferences, or the clock.
 *
 * The delta is measured over the configured interval (1 or 5 minutes) via GlucoseDelta, so the
 * threshold is cadence-independent: the same "10 mg/dl per 5 min" fires the same way on a 1-minute
 * sensor and a 5-minute sensor. Timestamps here are in minutes-from-zero for readability.
 */
class DeltaAlarmStateTests {

    private val MIN = 60_000L

    // Feed one reading (time given in minutes) and return whether it triggers.
    private fun DeltaAlarmState.feed(
        value: Float,
        atMinute: Long,
        interval: Int = 5,
        threshold: Float = 10f,
        count: Int = 3,
        border: Float = 120f,
        enabled: Boolean = true,
        activeNow: Boolean = true,
        snoozed: Boolean = false,
        earlyTrigger: Boolean = false
    ): Boolean = shouldTrigger(
        enabled = enabled,
        activeNow = activeNow,
        snoozed = snoozed,
        value = value,
        readingTimeMs = atMinute * MIN,
        deltaThreshold = threshold,
        deltaCount = count,
        deltaBorder = border,
        intervalMinutes = interval,
        earlyTriggerEnabled = earlyTrigger
    )

    // ---- 1. Run length over 5-minute readings ----

    @Test
    fun threeSteepFiveMinuteDropsFire() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))   // baseline, no 5-min-old sample yet
        assertFalse(state.feed(140f, 5))   // -10 over 5 min -> drop 1
        assertFalse(state.feed(130f, 10))  // drop 2
        assertTrue(state.feed(120f, 15))   // drop 3 -> fire, 120 <= border
    }

    @Test
    fun twoSteepDropsDoNotFire() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))
        assertFalse(state.feed(130f, 10))
    }

    @Test
    fun shallowDropsNeverAccumulate() {
        val state = DeltaAlarmState(falling = true)
        // 6 mg/dl per 5 min is below the 10 threshold.
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(144f, 5))
        assertFalse(state.feed(138f, 10))
        assertFalse(state.feed(132f, 15))
        assertFalse(state.feed(126f, 20))
    }

    // ---- 2. Cadence independence: 1-minute sensor, 5-minute interval ----

    @Test
    fun oneMinuteSensorWithFiveMinuteIntervalMatchesFiveMinuteSensor() {
        // Readings every minute, still dropping 2 mg/dl/min = 10 per 5 min.
        val state = DeltaAlarmState(falling = true)
        var fired = false
        var v = 150f
        for (m in 0..15) {
            fired = state.feed(v, m.toLong())
            v -= 2f
        }
        // By minute 15 (value 120) three consecutive 5-min deltas of -10 have accrued.
        assertTrue(fired)
    }

    @Test
    fun oneMinuteSensorNeedsFullWindowBeforeCounting() {
        val state = DeltaAlarmState(falling = true)
        // First 4 minutes: no sample is a full ~5 min old yet, so nothing accrues.
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(148f, 1))
        assertFalse(state.feed(146f, 2))
        assertFalse(state.feed(144f, 3))
        assertFalse(state.feed(142f, 4))
    }

    // ---- 3. Reset on counter-movement ----

    @Test
    fun flatWindowResetsCounterThenReaccumulates() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))   // drop 1
        assertFalse(state.feed(130f, 10))  // drop 2
        assertFalse(state.feed(130f, 15))  // flat over 5 min -> reset
        assertFalse(state.feed(120f, 20))  // drop 1 again
        assertFalse(state.feed(110f, 25))  // drop 2
        assertTrue(state.feed(100f, 30))   // drop 3 -> fire
    }

    @Test
    fun risingWindowResetsFallingCounter() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))   // drop 1
        assertFalse(state.feed(130f, 10))  // drop 2
        assertFalse(state.feed(145f, 15))  // jumps up -> reset
        assertFalse(state.feed(135f, 20))  // drop 1
        assertFalse(state.feed(125f, 25))  // drop 2
        assertTrue(state.feed(115f, 30))   // drop 3 -> fire
    }

    // ---- 4. Border ----

    @Test
    fun steepDropsAboveBorderDoNotFireUntilBelow() {
        val state = DeltaAlarmState(falling = true)
        // border 130: only warn once at/below 130.
        assertFalse(state.feed(170f, 0, border = 130f))
        assertFalse(state.feed(160f, 5, border = 130f))  // drop 1
        assertFalse(state.feed(150f, 10, border = 130f)) // drop 2
        assertFalse(state.feed(140f, 15, border = 130f)) // drop 3 but 140 > 130 -> no fire
        assertTrue(state.feed(130f, 20, border = 130f))  // drop 4, 130 <= 130 -> fire
    }

    // ---- 5. Fire once per run, re-arm only when the run breaks ----

    @Test
    fun doesNotReFireWhileTheDropContinues() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))
        assertFalse(state.feed(130f, 10))
        assertTrue(state.feed(120f, 15))   // fires
        assertFalse(state.feed(110f, 20))  // still dropping, already alarmed this run
        assertFalse(state.feed(100f, 25))
    }

    @Test
    fun reArmsAndFiresAgainAfterTheRunBreaks() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))
        assertFalse(state.feed(130f, 10))
        assertTrue(state.feed(120f, 15))   // first alarm
        assertFalse(state.feed(120f, 20))  // flat -> run breaks, re-arm
        assertFalse(state.feed(110f, 25))  // drop 1
        assertFalse(state.feed(100f, 30))  // drop 2
        assertTrue(state.feed(90f, 35))    // drop 3 -> alarm again
    }

    // ---- 6. Only genuinely newer readings advance the counter ----

    @Test
    fun duplicateTimestampDoesNotAdvanceOrResetCounter() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))   // drop 1
        assertFalse(state.feed(130f, 10))  // drop 2
        assertFalse(state.feed(130f, 10))  // SAME timestamp -> ignored (not a flat reset)
        assertTrue(state.feed(120f, 15))   // drop 3 -> fire (proves the duplicate was ignored)
    }

    // ---- 7. Suppression while inactive / snoozed, then firing once allowed ----

    @Test
    fun inactiveWindowSuppressesButRunStaysArmed() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0, activeNow = false))
        assertFalse(state.feed(140f, 5, activeNow = false))
        assertFalse(state.feed(130f, 10, activeNow = false))
        assertFalse(state.feed(120f, 15, activeNow = false)) // would fire, but window inactive
        // A later tick with the same reading, now inside the active window, fires.
        assertTrue(state.feed(120f, 15, activeNow = true))
    }

    @Test
    fun snoozeSuppressesFiring() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0, snoozed = true))
        assertFalse(state.feed(140f, 5, snoozed = true))
        assertFalse(state.feed(130f, 10, snoozed = true))
        assertFalse(state.feed(120f, 15, snoozed = true))  // suppressed by snooze
        assertTrue(state.feed(120f, 15, snoozed = false))  // snooze cleared -> fires
    }

    // ---- 8. Disabled resets everything ----

    @Test
    fun disabledResetsAndRequiresRebuild() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))
        assertFalse(state.feed(130f, 10))
        assertFalse(state.feed(120f, 15, enabled = false)) // disabled -> reset, no fire
        // Re-enabled: history is gone, must rebuild the whole run.
        assertFalse(state.feed(110f, 20))  // new baseline
        assertFalse(state.feed(100f, 25))  // drop 1
        assertFalse(state.feed(90f, 30))   // drop 2
        assertTrue(state.feed(80f, 35))    // drop 3 -> fire
    }

    // ---- 9. Rising mirror ----

    @Test
    fun risingDirectionFiresOnSteepRisesAboveBorder() {
        val state = DeltaAlarmState(falling = false)
        // border 200: only warn once at/above 200.
        assertFalse(state.feed(170f, 0, border = 200f))
        assertFalse(state.feed(180f, 5, border = 200f))  // rise 1
        assertFalse(state.feed(190f, 10, border = 200f)) // rise 2, still < 200
        assertTrue(state.feed(200f, 15, border = 200f))  // rise 3, 200 >= 200 -> fire
    }

    @Test
    fun risingCounterResetsOnDrop() {
        val state = DeltaAlarmState(falling = false)
        assertFalse(state.feed(170f, 0, border = 200f))
        assertFalse(state.feed(180f, 5, border = 200f))  // rise 1
        assertFalse(state.feed(190f, 10, border = 200f)) // rise 2
        assertFalse(state.feed(180f, 15, border = 200f)) // drop -> reset
        assertFalse(state.feed(200f, 20, border = 200f)) // rise 1
        assertFalse(state.feed(210f, 25, border = 200f)) // rise 2
        assertTrue(state.feed(220f, 30, border = 200f))  // rise 3 -> fire
    }

    // ---- 10. Calibration baseline reset ----

    @Test
    fun resetBaselinePreventsCalibrationJumpFromCounting() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))   // drop 1
        state.resetBaseline()              // recalibration shifts the displayed value
        assertFalse(state.feed(100f, 10))  // huge apparent drop, but it is the new baseline
        assertFalse(state.feed(90f, 15))   // drop 1
        assertFalse(state.feed(80f, 20))   // drop 2
        assertTrue(state.feed(70f, 25))    // drop 3 -> fire (calibration jump did not count)
    }

    // ---- 11. Data gap breaks the run ----

    @Test
    fun aLongDataGapBreaksTheRun() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))   // drop 1
        assertFalse(state.feed(130f, 10))  // drop 2
        // 30-minute outage: the next pair is further apart than the max gap -> no usable delta.
        assertFalse(state.feed(120f, 40))  // gap too large -> run does not advance to 3
        assertFalse(state.feed(110f, 45))  // drop 1 of a fresh run
        assertFalse(state.feed(100f, 50))  // drop 2
        assertTrue(state.feed(90f, 55))    // drop 3 -> fire
    }

    // ---- 12. Config guards ----

    @Test
    fun nonPositiveThresholdNeverFires() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0, threshold = 0f))
        assertFalse(state.feed(140f, 5, threshold = 0f))
        assertFalse(state.feed(130f, 10, threshold = 0f))
        assertFalse(state.feed(120f, 15, threshold = 0f))
    }

    @Test
    fun nonPositiveCountNeverFires() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0, count = 0))
        assertFalse(state.feed(140f, 5, count = 0))
        assertFalse(state.feed(130f, 10, count = 0))
    }

    // ---- 13. NaN handling ----

    @Test
    fun nonFiniteValueIsIgnored() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))          // drop 1
        assertFalse(state.feed(Float.NaN, 10))    // gap reading dropped, run preserved
        assertFalse(state.feed(130f, 10))         // (same minute as the NaN) still drop 2 vs 140@5
        assertTrue(state.feed(120f, 15))          // drop 3 -> fire
    }

    // ---- 14. One-minute interval on a one-minute sensor ----

    @Test
    fun oneMinuteIntervalCountsPerMinuteDeltas() {
        val state = DeltaAlarmState(falling = true)
        // interval 1, threshold 3 mg/dl per minute, count 3, below the default 120 border.
        assertFalse(state.feed(115f, 0, interval = 1, threshold = 3f))
        assertFalse(state.feed(111f, 1, interval = 1, threshold = 3f)) // -4/min -> drop 1
        assertFalse(state.feed(107f, 2, interval = 1, threshold = 3f)) // drop 2
        assertTrue(state.feed(103f, 3, interval = 1, threshold = 3f))  // drop 3 -> fire (103 <= 120)
    }

    // ---- 15. Non-overlapping checkpoints on fast streams ----

    @Test
    fun overlappingRollingDeltasOnAOneMinuteStreamDoNotSatisfyTheCount() {
        // Live regression (2026-07-16): threshold 10/5min, count 3, border 145. A ~7-minute
        // drop from 157 to 141 produced three overlapping rolling deltas (-13, -14, -13) on
        // consecutive minutes; they cover the SAME fall and must count as one interval, not three.
        val state = DeltaAlarmState(falling = true)
        val values = listOf(157f, 156f, 154f, 151f, 148f, 144f, 142f, 141f)
        values.forEachIndexed { m, v ->
            assertFalse("minute $m", state.feed(v, m.toLong(), border = 145f))
        }
    }

    @Test
    fun countOneStillFiresOnTheFirstSteepDelta() {
        val state = DeltaAlarmState(falling = true)
        val values = listOf(157f, 156f, 154f, 151f, 148f)
        values.forEachIndexed { m, v ->
            assertFalse(state.feed(v, m.toLong(), count = 1, border = 145f))
        }
        // First steep rolling delta (-13 vs minute 0) -> immediate alarm, 144 <= 145.
        assertTrue(state.feed(144f, 5, count = 1, border = 145f))
    }

    @Test
    fun fifteenMinutesOfSustainedFallFireAtTheSecondCheckpointAfterRunStart() {
        // 1-minute stream falling 2.5 mg/dl per minute: every rolling 5-min delta is -12.5.
        // Run starts at minute 5, checkpoints at 10 and 15 -> count 3 after ~15 min of fall.
        val state = DeltaAlarmState(falling = true)
        var v = 150f
        for (m in 0..14L) {
            assertFalse("minute $m", state.feed(v, m))
            v -= 2.5f
        }
        assertTrue(state.feed(v, 15))  // 112.5 <= 120 border
    }

    @Test
    fun sameTrajectoryFiresAtTheSameTimeOnOneAndFiveMinuteCadence() {
        // Identical fall (-2 mg/dl per minute from 150) sampled at both cadences: the 5-minute
        // series behaves exactly like the per-reading counter used to, and the 1-minute series
        // matches it instead of firing three times earlier.
        fun valueAt(m: Long) = 150f - 2f * m

        val fiveMinute = DeltaAlarmState(falling = true)
        var firedAtFive = -1L
        for (m in 0..15L step 5) if (fiveMinute.feed(valueAt(m), m)) firedAtFive = m

        val oneMinute = DeltaAlarmState(falling = true)
        var firedAtOne = -1L
        for (m in 0..15L) if (oneMinute.feed(valueAt(m), m)) firedAtOne = m

        assertEquals(15L, firedAtFive)
        assertEquals(15L, firedAtOne)
    }

    @Test
    fun reversedSignBetweenCheckpointsBreaksTheRunImmediately() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(148f, 1))
        assertFalse(state.feed(146f, 2))
        assertFalse(state.feed(144f, 3))
        assertFalse(state.feed(142f, 4))
        assertFalse(state.feed(140f, 5))   // -10 vs minute 0 -> run starts
        assertFalse(state.feed(152f, 6))   // +4 vs minute 1 -> reversed sign -> run breaks NOW
        // A fresh steep run must accumulate from scratch: start at 7, checkpoints at 12 and 17.
        assertFalse(state.feed(136f, 7))   // -10 vs minute 2 -> run restarts (count 1)
        assertFalse(state.feed(126f, 12))  // checkpoint -> count 2
        assertTrue(state.feed(116f, 17))   // checkpoint -> count 3 -> fire (116 <= 120)
    }

    // ---- 16. Effective window change resets the run ----

    @Test
    fun changingTheIntervalMidRunDiscardsRunAndHistory() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))   // drop 1
        assertFalse(state.feed(130f, 10))  // drop 2
        // The effective window flips to 1 minute: run and history no longer match it.
        // The next reading is only a fresh baseline, however steep it looks.
        assertFalse(state.feed(120f, 11, interval = 1, threshold = 3f))
        assertFalse(state.feed(116f, 12, interval = 1, threshold = 3f)) // -4/min -> drop 1
        assertFalse(state.feed(112f, 13, interval = 1, threshold = 3f)) // drop 2
        assertTrue(state.feed(108f, 14, interval = 1, threshold = 3f))  // drop 3 -> fire
    }

    @Test
    fun aShallowSameSignDeltaBetweenCheckpointsDoesNotBreakTheRun() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(148f, 1))
        assertFalse(state.feed(146f, 2))
        assertFalse(state.feed(144f, 3))
        assertFalse(state.feed(142f, 4))
        assertFalse(state.feed(140f, 5))   // -10 vs minute 0 -> run starts
        assertFalse(state.feed(139f, 6))   // -9 vs minute 1: same sign, not steep -> run stands
        assertFalse(state.feed(130f, 10))  // checkpoint: -10 vs minute 5 -> count 2
        assertTrue(state.feed(120f, 15))   // checkpoint: -10 -> count 3 -> fire
    }

    // ---- 17. Early trigger on the implied total distance (count x threshold) ----

    @Test
    fun withoutEarlyTriggerAFastFullDistanceDropStillWaitsForTheCheckpoints() {
        val state = DeltaAlarmState(falling = true)
        // -5/min: the full implied distance (3 x 10 = 30) is covered within ~6 minutes,
        // but with the escalation off the alarm still waits for the third checkpoint.
        var v = 150f
        for (m in 0..14L) {
            assertFalse("minute $m", state.feed(v, m))
            v -= 5f
        }
        assertTrue(state.feed(v, 15))  // third checkpoint -> count 3
    }

    @Test
    fun earlyTriggerFiresWhenTheFirstDeltaCoversTheFullDistance() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0, earlyTrigger = true))
        // One rolling delta of -30: the run starts and the full distance is already covered.
        assertTrue(state.feed(120f, 5, earlyTrigger = true))
        // Shared arming: the continuing drop must not fire a second alarm this run.
        assertFalse(state.feed(90f, 10, earlyTrigger = true))
        assertFalse(state.feed(60f, 15, earlyTrigger = true))
    }

    @Test
    fun earlyTriggerFiresBetweenCheckpointsOnceTheDistanceIsCovered() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0, earlyTrigger = true))
        assertFalse(state.feed(132f, 5, earlyTrigger = true))  // -18 -> run starts, 18 of 30 covered
        // 33 covered from the anchor: fires between checkpoints, not at the third one.
        assertTrue(state.feed(117f, 8, earlyTrigger = true))
    }

    @Test
    fun earlyTriggerStillRespectsTheBorder() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(190f, 0, earlyTrigger = true))
        assertFalse(state.feed(155f, 5, earlyTrigger = true))  // 35 covered, but 155 > 120 border
        assertTrue(state.feed(120f, 10, earlyTrigger = true))  // at the border -> fire
    }

    @Test
    fun aRunBreakDiscardsTheEarlyTriggerAnchor() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0, earlyTrigger = true))
        assertFalse(state.feed(132f, 5, earlyTrigger = true))  // -18 -> run starts, anchored at 150
        assertFalse(state.feed(140f, 10, earlyTrigger = true)) // reversal -> run breaks, anchor discarded
        assertFalse(state.feed(120f, 15, earlyTrigger = true)) // new run anchored at 140: 20 of 30
        // 28 from the NEW anchor -> no alarm; the stale 150 anchor would have given 38.
        assertFalse(state.feed(112f, 18, earlyTrigger = true))
    }

    // ---- Failed delivery must not consume the alarm ----

    @Test
    fun failedDeliveryReoffersWhileTheRunStands() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))
        assertFalse(state.feed(130f, 10))
        assertTrue(state.feed(120f, 15))       // fires, but delivery fails
        state.rearmAfterFailedDelivery()
        assertTrue(state.feed(110f, 20))       // run still steep: offered again
        assertFalse(state.feed(100f, 25))      // delivered this time -> once per run
    }

    @Test
    fun failedDeliveryDoesNotFireAfterTheRunBreaks() {
        val state = DeltaAlarmState(falling = true)
        assertFalse(state.feed(150f, 0))
        assertFalse(state.feed(140f, 5))
        assertFalse(state.feed(130f, 10))
        assertTrue(state.feed(120f, 15))       // fires, delivery fails
        state.rearmAfterFailedDelivery()
        // Reversal breaks the run: the stale falling-fast alarm dies with it.
        assertFalse(state.feed(131f, 20))
        assertFalse(state.feed(130f, 25))      // no late fire from the old run
    }
}
