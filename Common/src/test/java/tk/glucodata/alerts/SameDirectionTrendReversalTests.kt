package tk.glucodata.alerts

import org.junit.Assert.*
import org.junit.Test

class SameDirectionTrendReversalTests {
    private val t0 = 1_000_000L
    private val window = 15 * 60_000L

    private fun blocker(state: SameDirectionAlertSuppression, type: AlertType, at: Long) =
        state.blockedBy(type, at, window, isAcknowledged = { true })

    @Test fun upwardReversalClearsTheFallingWindowAndItDoesNotReturnOnAFreshFall() {
        val state = SameDirectionAlertSuppression()
        state.observeTrend(t0, -2f, true)
        state.onFired(AlertType.PRE_LOW, t0)
        state.observeTrend(t0 + 60_000L, -0.8f, true)
        assertNotNull(blocker(state, AlertType.FALLING_FAST, t0 + 60_000L))

        state.observeTrend(t0 + 2 * 60_000L, 0.6f, true)
        assertNull(blocker(state, AlertType.FALLING_FAST, t0 + 2 * 60_000L))
        state.observeTrend(t0 + 3 * 60_000L, -2f, true)
        assertNull(blocker(state, AlertType.FALLING_FAST, t0 + 3 * 60_000L))

        state.onFired(AlertType.FALLING_FAST, t0 + 3 * 60_000L)
        assertEquals(AlertType.FALLING_FAST,
            blocker(state, AlertType.PRE_LOW, t0 + 4 * 60_000L)?.type)
    }

    @Test fun downwardReversalClearsTheRisingWindow() {
        val state = SameDirectionAlertSuppression()
        state.observeTrend(t0, 2f, true)
        state.onFired(AlertType.PRE_HIGH, t0)
        state.observeTrend(t0 + 60_000L, 0.8f, true)
        assertNotNull(blocker(state, AlertType.RISING_FAST, t0 + 60_000L))

        state.observeTrend(t0 + 2 * 60_000L, -0.6f, true)
        assertNull(blocker(state, AlertType.RISING_FAST, t0 + 2 * 60_000L))
    }

    @Test fun flatReadingsAndSmallSignChangesPreserveBothWindows() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.PRE_LOW, t0)
        state.onFired(AlertType.PRE_HIGH, t0)
        listOf(-0.5f, -0.1f, 0f, 0.1f, 0.5f).forEachIndexed { index, rate ->
            val at = t0 + (index + 1) * 60_000L
            state.observeTrend(at, rate, true)
            assertNotNull(blocker(state, AlertType.FALLING_FAST, at))
            assertNotNull(blocker(state, AlertType.RISING_FAST, at))
        }
    }

    @Test fun unavailableOrUntrustedRatesCannotCancelTheWindow() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.PRE_LOW, t0)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEachIndexed { index, rate ->
            state.observeTrend(t0 + (index + 1) * 60_000L, rate, true)
        }
        state.observeTrend(t0 + 4 * 60_000L, 2f, false)
        assertNotNull(blocker(state, AlertType.FALLING_FAST, t0 + 4 * 60_000L))
    }

    @Test fun repeatedOrOlderReadingsCannotClearANewlyOpenedWindow() {
        val state = SameDirectionAlertSuppression()
        state.observeTrend(t0, 1f, true)
        state.onFired(AlertType.FALLING_FAST, t0)

        // Delta and forecast families can disagree on one reading. A timer tick
        // must not reuse that same reading to erase the window just opened.
        state.observeTrend(t0, 1f, true)
        state.observeTrend(t0 - 60_000L, 2f, true)
        assertNotNull(blocker(state, AlertType.PRE_LOW, t0 + 60_000L))

        state.observeTrend(t0 + 60_000L, 1f, true)
        assertNull(blocker(state, AlertType.PRE_LOW, t0 + 60_000L))
    }

    @Test fun reversalClearsOnlyTheOppositeDirectionsWindow() {
        val state = SameDirectionAlertSuppression()
        state.onFired(AlertType.PRE_LOW, t0)
        state.onFired(AlertType.PRE_HIGH, t0)
        state.observeTrend(t0 + 60_000L, 1f, true)

        assertNull(blocker(state, AlertType.FALLING_FAST, t0 + 60_000L))
        assertNotNull(blocker(state, AlertType.RISING_FAST, t0 + 60_000L))
    }
}
