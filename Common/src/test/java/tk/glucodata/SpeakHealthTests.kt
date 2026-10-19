package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpeakHealth turns speak() outcomes into the "engine is dead, recreate it" signal that
 * SuperGattCallback.initAlarmTalk() and the periodic announce gate act on.
 */
class SpeakHealthTests {

    @Test
    fun freshTalkerIsHealthy() {
        val h = SpeakHealth()
        assertFalse(h.needsReinit())
        assertEquals(0, h.consecutiveFailures())
    }

    @Test
    fun repeatedRefusalsOnABoundEngineTripReinit() {
        val h = SpeakHealth()
        repeat(SpeakHealth.REINIT_FAILURE_THRESHOLD - 1) { h.record(false, true) }
        assertFalse("one short of the threshold must not trip", h.needsReinit())
        h.record(false, true)
        assertTrue(h.needsReinit())
    }

    @Test
    fun refusalsBeforeOnInitAreNotHealthFailures() {
        // Boot: LossOfSensorAlarm creates the talker and immediately speaks the alarm, then a
        // touch-talk or message lands inside the bind window. Both are refused with the same
        // ERROR code a dead engine returns, but the engine is simply not bound yet.
        val h = SpeakHealth()
        repeat(SpeakHealth.REINIT_FAILURE_THRESHOLD + 3) { h.record(false, false) }
        assertEquals(0, h.consecutiveFailures())
        assertFalse(h.needsReinit())
    }

    @Test
    fun aSuccessResetsTheStreak() {
        val h = SpeakHealth()
        h.record(false, true)
        h.record(true, true)
        h.record(false, true)
        assertEquals(1, h.consecutiveFailures())
        assertFalse(h.needsReinit())
    }

    @Test
    fun unboundAfterUpdateStillDetected() {
        // The scenario the fix targets: onInit succeeded long ago, then the TTS package was
        // replaced and every speak is refused. engineReady stays true, so these count.
        val h = SpeakHealth()
        h.record(true, true)
        h.record(false, true)
        h.record(false, true)
        assertTrue(h.needsReinit())
    }

    @Test
    fun firstHealthRecreateIsAlwaysAllowed() {
        assertTrue(SpeakHealth.recreateAllowed(0L, 1_000L))
    }

    @Test
    fun recreateFloorBlocksARecreateInsideTheWindow() {
        val last = 1_000_000L
        assertFalse(SpeakHealth.recreateAllowed(last, last + SpeakHealth.RECREATE_FLOOR_MS - 1))
        assertTrue(SpeakHealth.recreateAllowed(last, last + SpeakHealth.RECREATE_FLOOR_MS))
    }
}
