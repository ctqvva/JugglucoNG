package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnytimeCt5LiveClockTests {
    private val interval = 180_000L
    private val start = 1_000_000L
    private fun slot(id: Int) = start + id * interval

    private fun advancingClock() = AnytimeCt5LiveClock().apply {
        observeLive(137, slot(137), hasGlucose = true, errorCode = 0)
        observeLive(138, slot(138), hasGlucose = true, errorCode = 0)
    }

    private fun expected(
        clock: AnytimeCt5LiveClock,
        now: Long = slot(138) + 262_000L,
        streamingSince: Long = slot(138) + 217_000L,
        observed: Int = 138,
        horizon: Int = 7695,
    ) = clock.expectedCurrentId(now, interval, streamingSince, observed, start, horizon)

    @Test
    fun october29ShortOutageCanRecover139BeforeTheNextLivePush() {
        // Last live 138 at 17:36:48; reconnect streams at 17:40:25. Once the
        // link settles at 17:41:10, id 139 (due 17:39:48) must be requested.
        val id = expected(advancingClock())
        assertEquals(139, id)
        assertEquals(AnytimeIdRange(139, 140), ct5ReconnectCatchupGap(138, 138, id, 480))
    }

    @Test
    fun reconnectingBeforeTheNextCadenceDoesNotInventAMissingId() {
        val id = expected(advancingClock(), now = slot(139) - 1L, streamingSince = slot(138) + 60_000L)
        assertEquals(138, id)
        assertNull(ct5ReconnectCatchupGap(138, 138, id, 480))
    }

    @Test
    fun aLivePushDuringTheSettleWaitSupersedesTheClockGuess() {
        val clock = advancingClock()
        assertEquals(139, expected(clock))
        clock.observeLive(140, slot(138) + 250_000L, hasGlucose = true, errorCode = 0)
        assertEquals(140, expected(clock, observed = 140))
        // Only the actual hole remains; do not project yet another id.
        assertEquals(AnytimeIdRange(139, 140), ct5ReconnectCatchupGap(138, 140, expected(clock, observed = 140), 480))
    }

    @Test
    fun aRepeatedIdDuringTheSettleWaitCancelsEarlyExtrapolation() {
        val clock = advancingClock()
        assertEquals(139, expected(clock))
        clock.observeLive(138, slot(138) + 220_000L, hasGlucose = true, errorCode = 0)
        assertEquals(138, expected(clock))
    }

    @Test
    fun repeatedIdsStayProtectedAcrossAnotherReconnect() {
        val clock = advancingClock()
        clock.observeLive(138, slot(138) + 180_000L, hasGlucose = true, errorCode = 0)
        assertEquals(138, expected(clock))
    }

    @Test
    fun noGlucoseOrErrorFramesDoNotAuthorizeEarlyCatchup() {
        for ((hasGlucose, error) in listOf(false to 0, false to 2, true to 2)) {
            val clock = AnytimeCt5LiveClock()
            clock.observeLive(137, slot(137), hasGlucose = true, errorCode = 0)
            clock.observeLive(138, slot(138), hasGlucose = hasGlucose, errorCode = error)
            assertEquals(138, expected(clock))
        }
    }

    @Test
    fun currentSessionDataRetainsTheRecentFrameGuard() {
        assertEquals(138, expected(advancingClock(), streamingSince = slot(138) - 1L))
    }

    @Test
    fun aSingleLiveFrameIsNotProofOfAdvancingIds() {
        val clock = AnytimeCt5LiveClock()
        clock.observeLive(138, slot(138), hasGlucose = true, errorCode = 0)
        assertEquals(138, expected(clock))
    }

    @Test
    fun rollbackResetCannotReuseTheOldProgressEvidence() {
        val clock = advancingClock()
        clock.reset()
        clock.observeLive(1, slot(1), hasGlucose = true, errorCode = 0)
        assertEquals(1, expected(clock, now = slot(2) + 50_000L, streamingSince = slot(2), observed = 1))
        clock.observeLive(2, slot(2), hasGlucose = true, errorCode = 0)
        assertEquals(3, expected(clock, now = slot(3) + 50_000L, streamingSince = slot(3), observed = 2))
    }

    @Test
    fun completingACarriedRangeStillOwesTheNewerClockTail() {
        val clock = AnytimeCt5LiveClock()
        clock.observeLive(128, slot(128), hasGlucose = true, errorCode = 0)
        clock.observeLive(129, slot(129), hasGlucose = true, errorCode = 0)
        val now = slot(132) + 58_000L
        val since = slot(132) + 11_000L
        // The old session only remembered 130. Importing it is history, not
        // proof of a current live push: 131..132 are still owed on this link.
        val id = expected(clock, now = now, streamingSince = since, observed = 130)
        assertEquals(132, id)
        assertEquals(AnytimeIdRange(131, 133), ct5ReconnectCatchupGap(130, 130, id, 480))
        // After that tail is imported, do not start the same pass again.
        val caughtUp = expected(clock, now = now, streamingSince = since, observed = 132)
        assertNull(ct5ReconnectCatchupGap(132, 132, caughtUp, 480))
    }

    @Test
    fun aFrozenFinalIdNeverGrowsPastTheKnownHorizon() {
        val clock = AnytimeCt5LiveClock()
        clock.observeLive(8175, slot(8175), hasGlucose = false, errorCode = 2)
        clock.observeLive(8175, slot(8176), hasGlucose = false, errorCode = 2)
        assertEquals(8175, expected(clock, now = slot(8177), streamingSince = slot(8176) + 1L, observed = 8175))
        assertEquals(8175, expected(clock, now = slot(8180), streamingSince = slot(8179), observed = 8175))
    }

    @Test
    fun coldStartRetainsTheBoundedClockFallback() {
        val clock = AnytimeCt5LiveClock()
        assertEquals(139, expected(clock))
        assertEquals(150, expected(clock, now = slot(200), horizon = 150))
        assertEquals(-1, clock.expectedCurrentId(slot(139), interval, slot(139), 138, 0L, 7695))
        assertEquals(-1, clock.expectedCurrentId(slot(139), 0L, slot(139), 138, start, 7695))
    }
}
