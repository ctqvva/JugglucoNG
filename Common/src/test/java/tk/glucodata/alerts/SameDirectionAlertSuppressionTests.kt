package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-family quiet period: after one alert of a direction fires, another
 * alert of the same direction stays quiet for the window. The class is pure so
 * the rules can be pinned without Android or the clock.
 */
class SameDirectionAlertSuppressionTests {

    private val window = 5 * 60_000L
    private val t0 = 1_000_000L

    @Test
    fun predictedLowIsSuppressedShortlyAfterFallingFast() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.FALLING_FAST, t0)

        val blocker = state.blockedBy(AlertType.PRE_LOW, t0 + 30_000L, window)

        assertNotNull(blocker)
        assertEquals(AlertType.FALLING_FAST, blocker!!.type)
        assertEquals(t0, blocker.firedAtMs)
    }

    @Test
    fun fallingFastIsSuppressedShortlyAfterPredictedLow() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.PRE_LOW, t0)

        val blocker = state.blockedBy(AlertType.FALLING_FAST, t0 + 30_000L, window)

        assertEquals(AlertType.PRE_LOW, blocker?.type)
    }

    @Test
    fun risingSideIsSymmetric() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.RISING_FAST, t0)
        assertEquals(AlertType.RISING_FAST, state.blockedBy(AlertType.PRE_HIGH, t0 + 30_000L, window)?.type)

        val reversed = SameDirectionAlertSuppression()
        reversed.onFired(AlertType.PRE_HIGH, t0)
        assertEquals(AlertType.PRE_HIGH, reversed.blockedBy(AlertType.RISING_FAST, t0 + 30_000L, window)?.type)
    }

    @Test
    fun thresholdAlertsAreNeverSuppressed() {
        // The most important rule: arriving low or high is always announced,
        // even half a minute after the warning about it.
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.FALLING_FAST, t0)
        state.onFired(AlertType.RISING_FAST, t0)

        for (type in listOf(AlertType.LOW, AlertType.VERY_LOW, AlertType.HIGH, AlertType.VERY_HIGH)) {
            assertNull(type.name, state.blockedBy(type, t0 + 30_000L, window))
        }
    }

    @Test
    fun thresholdAlertsDoNotSuppressEither() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.LOW, t0)
        state.onFired(AlertType.HIGH, t0)

        assertNull(state.blockedBy(AlertType.PRE_LOW, t0 + 30_000L, window))
        assertNull(state.blockedBy(AlertType.FALLING_FAST, t0 + 30_000L, window))
        assertNull(state.blockedBy(AlertType.PRE_HIGH, t0 + 30_000L, window))
        assertNull(state.blockedBy(AlertType.RISING_FAST, t0 + 30_000L, window))
    }

    @Test
    fun oppositeDirectionIsNotSuppressed() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.FALLING_FAST, t0)

        assertNull(state.blockedBy(AlertType.PRE_HIGH, t0 + 30_000L, window))
        assertNull(state.blockedBy(AlertType.RISING_FAST, t0 + 30_000L, window))
    }

    @Test
    fun expiredWindowLetsTheSecondTypeFireAgain() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.FALLING_FAST, t0)

        assertNotNull(state.blockedBy(AlertType.PRE_LOW, t0 + window - 1L, window))
        assertNull(state.blockedBy(AlertType.PRE_LOW, t0 + window, window))
    }

    @Test
    fun zeroWindowRestoresIndependentFiring() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.FALLING_FAST, t0)

        assertNull(state.blockedBy(AlertType.PRE_LOW, t0 + 30_000L, 0L))
        assertNull(state.blockedBy(AlertType.PRE_LOW, t0 + 30_000L, -1L))
    }

    @Test
    fun aTypeIsNeverBlockedByItsOwnEarlierFiring() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.FALLING_FAST, t0)

        assertNull(state.blockedBy(AlertType.FALLING_FAST, t0 + 30_000L, window))
    }

    @Test
    fun aSuppressedAlertLeavesNoTraceAndIsNotDeliveredLater() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.FALLING_FAST, t0)

        // Asking is side-effect free: the window still counts from the
        // FALLING_FAST firing, not from the suppressed attempt.
        assertNotNull(state.blockedBy(AlertType.PRE_LOW, t0 + 30_000L, window))
        assertNotNull(state.blockedBy(AlertType.PRE_LOW, t0 + 60_000L, window))
        assertNull(state.blockedBy(AlertType.PRE_LOW, t0 + window, window))
        // ...and the suppressed PRE_LOW did not start a window of its own
        // that would now block a new FALLING_FAST run.
        assertNull(state.blockedBy(AlertType.FALLING_FAST, t0 + window, window))

        // Mirrors the runtime: a suppressed standard alert drops its pending
        // delivery (here left over from a snooze), so the running episode
        // never offers it again; only a fresh entry can.
        val episodes = AlertEpisodeState<AlertType>()
        assertTrue(episodes.update(setOf(AlertType.PRE_LOW)).shouldTryFire(AlertType.PRE_LOW))
        episodes.markPendingDelivery(AlertType.PRE_LOW)
        assertTrue(episodes.update(setOf(AlertType.PRE_LOW)).shouldTryFire(AlertType.PRE_LOW))
        if (state.blockedBy(AlertType.PRE_LOW, t0 + 30_000L, window) != null) {
            episodes.clearPending(AlertType.PRE_LOW)
        }
        assertFalse(episodes.update(setOf(AlertType.PRE_LOW)).shouldTryFire(AlertType.PRE_LOW))
    }

    @Test
    fun theLatestFiringInADirectionOwnsTheWindow() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.FALLING_FAST, t0)
        state.onFired(AlertType.PRE_LOW, t0 + 4 * 60_000L)

        // FALLING_FAST's own window has passed, but PRE_LOW fired since.
        val blocker = state.blockedBy(AlertType.FALLING_FAST, t0 + window + 1L, window)
        assertEquals(AlertType.PRE_LOW, blocker?.type)
    }
}
