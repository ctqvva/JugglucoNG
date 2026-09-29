package tk.glucodata.drivers.aidex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.aidex.AiDexSetupPolicy.ConnectingState
import tk.glucodata.drivers.aidex.AiDexSetupPolicy.NotConnectedReason

class AiDexSetupPolicyTests {

    private fun state(
        session: Boolean = false,
        pairing: Boolean = false,
        gaveUp: Boolean = false,
        stays: Boolean = false,
        now: Long = 10_000L,
        deadline: Long = 90_000L,
        hardDeadline: Long = 180_000L,
        notConnectedDeadline: Long = 220_000L,
    ) = AiDexSetupPolicy.decideConnectingState(
        sessionEstablished = session,
        pairingInProgress = pairing,
        driverGaveUp = gaveUp,
        sensorStays = stays,
        nowMs = now,
        deadlineMs = deadline,
        hardDeadlineMs = hardDeadline,
        notConnectedDeadlineMs = notConnectedDeadline,
    )

    @Test
    fun aStreamingDriverIsReadyWithoutWaitingForGlucose() {
        // A new sensor has no glucose for the length of its warm-up; the old check waited for a
        // stored key, which only a valid reading writes, so a new sensor could never pass.
        assertEquals(ConnectingState.READY, state(session = true))
        assertEquals(ConnectingState.READY, state(session = true, now = 200_000L))
        assertEquals(ConnectingState.READY, state(session = true, gaveUp = true, pairing = true))
    }

    @Test
    fun aPairingHoldsOffTheSoftDeadlineButNotTheHardOne() {
        assertEquals(ConnectingState.AWAITING_PAIRING_CONFIRMATION, state(pairing = true))
        assertEquals(ConnectingState.AWAITING_PAIRING_CONFIRMATION, state(pairing = true, now = 95_000L))
        assertEquals(ConnectingState.TIMED_OUT, state(pairing = true, now = 180_000L))
    }

    @Test
    fun aPairingOutranksADriverThatGaveUpUntilTheHardDeadline() {
        // The user is looking at a pairing prompt: asking them to tap Retry instead would be wrong.
        assertEquals(ConnectingState.AWAITING_PAIRING_CONFIRMATION, state(pairing = true, gaveUp = true))
        assertEquals(ConnectingState.AWAITING_PAIRING_CONFIRMATION, state(pairing = true, gaveUp = true, now = 179_999L))
        assertEquals(ConnectingState.NOT_CONNECTED, state(pairing = true, gaveUp = true, now = 180_000L))
    }

    @Test
    fun aDriverThatGaveUpWaitsForTheUserUntilItsOwnLimit() {
        assertEquals(ConnectingState.NOT_CONNECTED, state(gaveUp = true))
        // Past the soft and the hard deadline: those bound a connect, not the user reading the screen.
        assertEquals(ConnectingState.NOT_CONNECTED, state(gaveUp = true, now = 95_000L))
        assertEquals(ConnectingState.NOT_CONNECTED, state(gaveUp = true, now = 219_999L))
        // Then the setup ends like any timeout, before the driver's own retry can prompt again.
        assertEquals(ConnectingState.TIMED_OUT, state(gaveUp = true, now = 220_000L))
        assertEquals(ConnectingState.TIMED_OUT, state(gaveUp = true, now = 500_000L))
    }

    @Test
    fun aTimeoutOnASensorThatStaysIsKeptNotFailed() {
        // A sensor this setup added that the rollback keeps (stored key or completed key exchange)
        // stays registered; a "pairing did not complete" would be false.
        assertEquals(ConnectingState.KEPT, state(stays = true, now = 90_000L))
        assertEquals(ConnectingState.KEPT, state(stays = true, gaveUp = true, now = 220_000L))
        assertEquals(ConnectingState.TIMED_OUT, state(stays = false, now = 90_000L))
        assertEquals(ConnectingState.TIMED_OUT, state(stays = false, gaveUp = true, now = 220_000L))
        // Only instead of a timeout: every other state wins as before.
        assertEquals(ConnectingState.CONNECTING, state(stays = true, now = 89_999L))
        assertEquals(ConnectingState.READY, state(stays = true, session = true, now = 500_000L))
        assertEquals(ConnectingState.AWAITING_PAIRING_CONFIRMATION, state(stays = true, pairing = true, now = 95_000L))
        assertEquals(ConnectingState.NOT_CONNECTED, state(stays = true, gaveUp = true, now = 219_999L))
    }

    @Test
    fun anythingElseTimesOutAtTheSoftDeadline() {
        assertEquals(ConnectingState.CONNECTING, state())
        assertEquals(ConnectingState.CONNECTING, state(now = 89_999L))
        assertEquals(ConnectingState.TIMED_OUT, state(now = 90_000L))
    }

    private fun next(
        current: AiDexSetupPolicy.Deadlines,
        now: Long,
        pairing: Boolean = false,
        gaveUp: Boolean = false,
    ) = AiDexSetupPolicy.nextDeadlines(
        current = current,
        nowMs = now,
        pairingInProgress = pairing,
        driverGaveUp = gaveUp,
        sessionTimeoutMs = 90_000L,
        hardTimeoutMs = 180_000L,
        graceMs = 40_000L,
        notConnectedLimitMs = 180_000L,
    )

    private val start = AiDexSetupPolicy.initialDeadlines(nowMs = 0L, sessionTimeoutMs = 90_000L, hardTimeoutMs = 180_000L)

    private fun stateFor(
        deadlines: AiDexSetupPolicy.Deadlines,
        now: Long,
        gaveUp: Boolean = false,
        pairing: Boolean = false,
    ) = state(
        gaveUp = gaveUp,
        pairing = pairing,
        now = now,
        deadline = deadlines.softMs,
        hardDeadline = deadlines.hardMs,
        notConnectedDeadline = deadlines.notConnectedMs,
    )

    @Test
    fun nextDeadlines_leavesRoomForTheHandshakeAfterALatePairing() {
        assertEquals(AiDexSetupPolicy.Deadlines(90_000L, 180_000L), start)
        assertEquals(start, next(start, now = 85_000L))
        // Confirmed at 85 s, the key exchange still has 40 s before the soft deadline can fire.
        assertEquals(AiDexSetupPolicy.Deadlines(125_000L, 180_000L), next(start, now = 85_000L, pairing = true))
        // Never pulled in: an early pairing keeps the full soft deadline.
        assertEquals(start, next(start, now = 20_000L, pairing = true))
        assertEquals(AiDexSetupPolicy.Deadlines(180_000L, 180_000L), next(start, now = 175_000L, pairing = true))
    }

    @Test
    fun nextDeadlines_theDriversNextAttemptGetsAFullWindow() {
        // The driver falls back to broadcast-only 40 s in and is trying again at 200 s, inside the
        // wait for the user. The times only illustrate the flip: its own timed exit comes ten
        // minutes after a fallback (a hold that began before this wait can end inside it), and a
        // Retry starts a new wait instead. Measured from the old start that connect would time
        // out on the first poll and be rolled back mid-attempt.
        var deadlines = start
        var now = 40_000L
        while (now < 200_000L) {
            deadlines = next(deadlines, now = now, gaveUp = true)
            // The wait for the user keeps its first-poll limit; the connect deadlines wait.
            assertEquals(AiDexSetupPolicy.Deadlines(90_000L, 180_000L, 220_000L), deadlines)
            assertEquals(ConnectingState.NOT_CONNECTED, stateFor(deadlines, now = now, gaveUp = true))
            now += 500L
        }
        deadlines = next(deadlines, now = 200_000L, gaveUp = false)
        assertEquals(AiDexSetupPolicy.Deadlines(290_000L, 380_000L, 0L), deadlines)
        assertEquals(ConnectingState.CONNECTING, stateFor(deadlines, now = 200_000L))
        assertEquals(ConnectingState.CONNECTING, stateFor(deadlines, now = 289_999L))
        assertEquals(ConnectingState.TIMED_OUT, stateFor(deadlines, now = 290_000L))
        // That attempt pairs again: the user is asked to confirm, not rolled back under the prompt.
        val pairing = next(deadlines, now = 205_000L, pairing = true)
        assertEquals(ConnectingState.AWAITING_PAIRING_CONFIRMATION, stateFor(pairing, now = 205_000L, pairing = true))
    }

    @Test
    fun nextDeadlines_aNextAttemptThatStartsWithAPairingStillGetsAFullWindow() {
        // Android is pairing on the very poll where the driver stops giving up, after the old
        // hard deadline: the fresh window must hold, or the pairing is rolled back under the user.
        var deadlines = next(start, now = 40_000L, gaveUp = true)
        deadlines = next(deadlines, now = 150_000L, pairing = true, gaveUp = true)
        deadlines = next(deadlines, now = 200_000L, pairing = true, gaveUp = false)
        assertEquals(AiDexSetupPolicy.Deadlines(290_000L, 380_000L, 0L), deadlines)
        assertEquals(ConnectingState.AWAITING_PAIRING_CONFIRMATION, stateFor(deadlines, now = 200_000L, pairing = true))
    }

    @Test
    fun nextDeadlines_theWaitForTheUserRunsFromTheFirstGaveUpPoll() {
        var deadlines = start
        var now = 40_000L
        while (now < 220_000L) {
            deadlines = next(deadlines, now = now, gaveUp = true)
            assertEquals(220_000L, deadlines.notConnectedMs)
            assertEquals(ConnectingState.NOT_CONNECTED, stateFor(deadlines, now = now, gaveUp = true))
            now += 500L
        }
        deadlines = next(deadlines, now = 220_000L, gaveUp = true)
        assertEquals(ConnectingState.TIMED_OUT, stateFor(deadlines, now = 220_000L, gaveUp = true))
        // The driver trying again drops it; giving up again starts a new one.
        val resumed = next(deadlines, now = 100_000L, gaveUp = false)
        assertEquals(0L, resumed.notConnectedMs)
        assertEquals(480_000L, next(resumed, now = 300_000L, gaveUp = true).notConnectedMs)
    }

    @Test
    fun nextDeadlines_aPairingPollNeitherMovesNorKeepsTheWaitForTheUserWrongly() {
        // A pairing poll while the driver has given up starts the limit...
        assertEquals(230_000L, next(start, now = 50_000L, pairing = true, gaveUp = true).notConnectedMs)
        // ...does not move a limit already running...
        val latched = next(start, now = 40_000L, gaveUp = true)
        val later = next(latched, now = 60_000L, pairing = true, gaveUp = true)
        assertEquals(220_000L, later.notConnectedMs)
        assertEquals(220_000L, next(later, now = 60_500L, pairing = true, gaveUp = true).notConnectedMs)
        // ...and drops it when the driver tries again.
        assertEquals(0L, next(later, now = 61_000L, pairing = true, gaveUp = false).notConnectedMs)
    }

    @Test
    fun notConnectedReason_onlyAPairingThatNeverBondedWasNotConfirmed() {
        assertEquals(NotConnectedReason.PAIRING_NOT_CONFIRMED, AiDexSetupPolicy.notConnectedReason(sawPairing = true, sawBonded = false))
        // Confirmed, then the key exchange failed: telling the user to confirm again is wrong.
        assertEquals(NotConnectedReason.CONNECT_FAILED, AiDexSetupPolicy.notConnectedReason(sawPairing = true, sawBonded = true))
        assertEquals(NotConnectedReason.CONNECT_FAILED, AiDexSetupPolicy.notConnectedReason(sawPairing = false, sawBonded = false))
        assertEquals(NotConnectedReason.CONNECT_FAILED, AiDexSetupPolicy.notConnectedReason(sawPairing = false, sawBonded = true))
    }

    @Test
    fun mayRollBack_onlyASensorWhoseHandshakeNeverFinished() {
        assertTrue(AiDexSetupPolicy.mayRollBack(vendorPaired = false, handshakeCompleted = false))
        assertFalse(AiDexSetupPolicy.mayRollBack(vendorPaired = true, handshakeCompleted = false))
        assertFalse(AiDexSetupPolicy.mayRollBack(vendorPaired = false, handshakeCompleted = true))
        assertFalse(AiDexSetupPolicy.mayRollBack(vendorPaired = true, handshakeCompleted = true))
    }
}
