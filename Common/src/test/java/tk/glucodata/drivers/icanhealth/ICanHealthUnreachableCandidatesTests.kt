package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-30 report: onboarding latched the car head unit `GeelyVehicle995` by name; every connect
 * timed out with status 147 and the driver retried it for hours.
 */
class ICanHealthUnreachableCandidatesTests {
    private val car = "74:53:72:E1:E9:CE"
    private val other = "D4:FE:28:EB:50:6B"
    private val cooldown = ICanHealthConstants.UNREACHABLE_CANDIDATE_COOLDOWN_MS
    private val strikes = ICanHealthConstants.UNREACHABLE_CANDIDATE_REJECT_STRIKES

    @Test
    fun oneTimeoutIsNotEnough() {
        assertTrue(strikes >= 2)
        assertTrue(cooldown > 0L)
        val candidates = ICanHealthUnreachableCandidates()
        assertFalse(candidates.attemptEnded(car, reachedConnected = false, nowMs = 0L))
        assertFalse(candidates.isSetAside(car, 0L))
    }

    @Test
    fun consecutiveFailedConnectsSetTheAddressAside() {
        val candidates = ICanHealthUnreachableCandidates()
        repeat(strikes - 1) { assertFalse(candidates.attemptEnded(car, false, it * 40_000L)) }
        assertTrue(candidates.attemptEnded(car, false, strikes * 40_000L))
        assertTrue(candidates.isSetAside(car, strikes * 40_000L))
        assertEquals(0, candidates.strikes(car))
    }

    @Test
    fun aConnectionInBetweenStartsTheCountOver() {
        val candidates = ICanHealthUnreachableCandidates()
        repeat(strikes - 1) { candidates.attemptEnded(car, false, 0L) }
        assertFalse(candidates.attemptEnded(car, reachedConnected = true, nowMs = 0L))
        assertEquals(0, candidates.strikes(car))
        repeat(strikes - 1) { assertFalse(candidates.attemptEnded(car, false, 0L)) }
        assertFalse(candidates.isSetAside(car, 0L))
    }

    @Test
    fun addressesAreCountedSeparately() {
        val candidates = ICanHealthUnreachableCandidates()
        repeat(strikes - 1) { candidates.attemptEnded(car, false, 0L) }
        assertFalse(candidates.attemptEnded(other, false, 0L))
        assertTrue(candidates.attemptEnded(car, false, 0L))
        assertFalse(candidates.isSetAside(other, 0L))
    }

    @Test
    fun theAddressIsEligibleAgainAfterTheCooldown() {
        // A real sensor briefly out of reach fails the same way, so it must get another turn.
        val candidates = ICanHealthUnreachableCandidates()
        repeat(strikes) { candidates.attemptEnded(car, false, 1_000L) }
        assertTrue(candidates.isSetAside(car, 1_000L + cooldown - 1L))
        assertFalse(candidates.isSetAside(car, 1_000L + cooldown))
        assertFalse(candidates.isSetAside(car, 1_000L))
    }

    @Test
    fun aNewOnboardingForgetsEverything() {
        val candidates = ICanHealthUnreachableCandidates()
        repeat(strikes) { candidates.attemptEnded(car, false, 0L) }
        candidates.attemptEnded(other, false, 0L)
        candidates.clear()
        assertFalse(candidates.isSetAside(car, 0L))
        assertEquals(0, candidates.strikes(other))
    }
}
