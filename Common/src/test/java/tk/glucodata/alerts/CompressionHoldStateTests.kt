package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.alerts.CompressionHoldState.Action

/**
 * The hold's rails: a hard floor no pattern overrides, a bounded window, escalation the
 * moment the upturn fails to arrive, and a clean release when the low resolves. The
 * state machine never decides suspicion — it is handed the verdict and owns only the
 * clock and floor arithmetic.
 */
class CompressionHoldStateTests {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L
    private val tenMinutes = 10 * minute

    private fun holding(state: CompressionHoldState = CompressionHoldState()): CompressionHoldState {
        val action = state.onLowActive(t0, 74f, 55f, tenMinutes, suspicionHeld = true)
        assertEquals(Action.StartHold, action)
        return state
    }

    @Test
    fun suspicionStartsAHoldAndItsAbsenceDoesNot() {
        val state = CompressionHoldState()
        assertEquals(Action.None, state.onLowActive(t0, 74f, 55f, tenMinutes, suspicionHeld = false))
        assertFalse(state.holding)
        assertEquals(Action.StartHold, state.onLowActive(t0, 74f, 55f, tenMinutes, suspicionHeld = true))
        assertTrue(state.holding)
    }

    @Test
    fun belowTheHardFloorNoHoldEverStarts() {
        val state = CompressionHoldState()
        val action = state.onLowActive(t0, 54f, 55f, tenMinutes, suspicionHeld = true)
        assertEquals(Action.None, action)
        assertFalse(state.holding)
    }

    @Test
    fun hittingTheHardFloorMidHoldEscalatesInstantly() {
        val state = holding()
        val action = state.onLowActive(t0 + minute, 55f, 55f, tenMinutes, suspicionHeld = true)
        assertEquals(Action.Escalate("hard-floor"), action)
        assertFalse(state.holding)
    }

    @Test
    fun aDisabledFloorMeansTheOtherRailsStillGovern() {
        // The owner may disable the floor (NaN); expiry then remains the backstop.
        val state = CompressionHoldState()
        state.onLowActive(t0, 74f, Float.NaN, tenMinutes, suspicionHeld = true)
        assertEquals(Action.ContinueHold,
            state.onLowActive(t0 + minute, 40f, Float.NaN, tenMinutes, suspicionHeld = true))
        assertEquals(Action.Escalate("hold-expired"),
            state.onLowActive(t0 + tenMinutes, 40f, Float.NaN, tenMinutes, suspicionHeld = true))
    }

    @Test
    fun theHoldNeverOutlivesItsWindow() {
        val state = holding()
        assertEquals(Action.ContinueHold,
            state.onLowActive(t0 + 5 * minute, 70f, 55f, tenMinutes, suspicionHeld = true))
        assertEquals(Action.Escalate("hold-expired"),
            state.onLowActive(t0 + tenMinutes, 70f, 55f, tenMinutes, suspicionHeld = true))
        assertFalse(state.holding)
    }

    @Test
    fun noUpturnByTheGraceDeadlineEscalates() {
        // Falls to 62 and sits there: at six minutes the pattern has failed.
        val state = holding()
        state.onLowActive(t0 + 2 * minute, 62f, 55f, tenMinutes, suspicionHeld = true)
        state.onLowActive(t0 + 4 * minute, 62f, 55f, tenMinutes, suspicionHeld = true)
        val action = state.onLowActive(t0 + 6 * minute, 63f, 55f, tenMinutes, suspicionHeld = true)
        assertEquals(Action.Escalate("no-upturn"), action)
    }

    @Test
    fun aRealUpturnKeepsTheHoldAlive() {
        // Bottoms at 62, rising again by the deadline: exactly the artifact pattern.
        val state = holding()
        state.onLowActive(t0 + 2 * minute, 62f, 55f, tenMinutes, suspicionHeld = true)
        state.onLowActive(t0 + 4 * minute, 66f, 55f, tenMinutes, suspicionHeld = true)
        assertEquals(Action.ContinueHold,
            state.onLowActive(t0 + 6 * minute, 70f, 55f, tenMinutes, suspicionHeld = true))
        assertEquals(Action.ContinueHold,
            state.onLowActive(t0 + 8 * minute, 76f, 55f, tenMinutes, suspicionHeld = true))
    }

    @Test
    fun theLowClearingResolvesTheHoldWithItsDuration() {
        val state = holding()
        state.onLowActive(t0 + 3 * minute, 70f, 55f, tenMinutes, suspicionHeld = true)
        val action = state.onLowCleared(t0 + 7 * minute)
        assertEquals(Action.Resolved(7 * minute), action)
        assertFalse(state.holding)
    }

    @Test
    fun clearingWithoutAHoldIsANoOp() {
        assertEquals(Action.None, CompressionHoldState().onLowCleared(t0))
    }

    @Test
    fun disablingTheFeatureMidHoldReleasesTheAlarm() {
        val state = holding()
        assertEquals(Action.Escalate("feature-disabled"), state.onDisabled())
        assertFalse(state.holding)
    }

    @Test
    fun aBrokenReadingEscalatesRatherThanHoldsBlind() {
        val state = holding()
        val action = state.onLowActive(t0 + minute, Float.NaN, 55f, tenMinutes, suspicionHeld = true)
        assertEquals(Action.Escalate("broken-reading"), action)
    }

    @Test
    fun afterEscalationTheNextEpisodeStartsFresh() {
        val state = holding()
        state.onLowActive(t0 + tenMinutes, 70f, 55f, tenMinutes, suspicionHeld = true)
        assertFalse(state.holding)
        assertEquals(Action.StartHold,
            state.onLowActive(t0 + 60 * minute, 74f, 55f, tenMinutes, suspicionHeld = true))
    }
}
