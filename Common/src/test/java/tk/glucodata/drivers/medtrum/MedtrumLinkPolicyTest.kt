package tk.glucodata.drivers.medtrum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.medtrum.MedtrumReconnectPolicy.Route

class MedtrumReconnectPolicyTest {

    @Test
    fun aLostLinkGoesBackToTheKnownAddress() {
        val policy = MedtrumReconnectPolicy()
        policy.onConnected()

        assertEquals(Route.DIRECT, policy.onLinkLost())
    }

    @Test
    fun repeatedFailedDirectAttemptsFallBackToAScan() {
        val policy = MedtrumReconnectPolicy(maxDirectFailures = 3)

        assertEquals(Route.DIRECT, policy.onAttemptFailed())
        assertEquals(Route.DIRECT, policy.onAttemptFailed())
        assertEquals(Route.SCAN, policy.onAttemptFailed())
    }

    @Test
    fun aScanWindowThatFoundNothingReturnsToTheKnownAddress() {
        // With the screen off an unfiltered scan reports nothing; it must not be the only route.
        val policy = MedtrumReconnectPolicy(maxDirectFailures = 1)
        assertEquals(Route.SCAN, policy.onAttemptFailed())

        assertEquals(Route.DIRECT, policy.onAttemptFailed())
        assertEquals(Route.SCAN, policy.onAttemptFailed())
    }

    @Test
    fun aConnectionResetsTheFailureCount() {
        val policy = MedtrumReconnectPolicy(maxDirectFailures = 2)
        policy.onAttemptFailed()
        policy.onConnected()

        assertEquals(Route.DIRECT, policy.onAttemptFailed())
        assertEquals(Route.SCAN, policy.onAttemptFailed())
    }

    @Test
    fun aLostLinkAfterAScanFallbackResetsToDirect() {
        val policy = MedtrumReconnectPolicy(maxDirectFailures = 1)
        policy.onAttemptFailed()

        assertEquals(Route.DIRECT, policy.onLinkLost())
        assertEquals(Route.DIRECT, policy.route)
    }
}

class MedtrumAuthSessionTest {

    @Test
    fun startsFromTheStoredDialect() {
        assertFalse(MedtrumAuthSession(storedLegacy = false).legacy)
        assertTrue(MedtrumAuthSession(storedLegacy = true).legacy)
    }

    @Test
    fun aRejectionTriesTheOtherDialectOncePerLink() {
        val session = MedtrumAuthSession(storedLegacy = false)

        assertTrue(session.onRejected())
        assertTrue(session.legacy)
        assertFalse(session.onRejected())
    }

    @Test
    fun aStoredLegacyDialectCanFallBackToModern() {
        // The old code only ever switched modern → legacy, so one stray rejection pinned the
        // patch to the wrong key for good.
        val session = MedtrumAuthSession(storedLegacy = true)

        assertTrue(session.onRejected())
        assertFalse(session.legacy)
    }
}
