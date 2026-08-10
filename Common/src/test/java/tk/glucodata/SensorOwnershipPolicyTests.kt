package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.SensorOwnershipPolicy.Intent
import tk.glucodata.SensorOwnershipPolicy.PeerReport
import tk.glucodata.SensorOwnershipPolicy.shouldReadLocally

/**
 * Getting this wrong in the unsafe direction means neither device reads the
 * sensor, so every way that can happen is pinned here.
 */
class SensorOwnershipPolicyTests {
    private val now = 1_800_000_000_000L
    private val silentAfter = 3L * 60L * 1000L

    private fun decide(
        isPhone: Boolean = true,
        intent: Intent = Intent.TAKE,
        localHasConnection: Boolean = false,
        localLastReadingMs: Long = 0L,
        peer: PeerReport? = null,
        yieldUntilMs: Long = 0L,
    ) = shouldReadLocally(
        isPhone = isPhone,
        intent = intent,
        localHasConnection = localHasConnection,
        localLastReadingMs = localLastReadingMs,
        peer = peer,
        nowMs = now,
        peerSilentAfterMs = silentAfter,
        yieldUntilMs = yieldUntilMs,
    )

    @Test
    fun readsWhenNothingHasEverBeenHeardFromThePeer() {
        assertTrue(decide(peer = null))
    }

    @Test
    fun takesOverWhenThePeerGoesSilent() {
        // Out of range, flat battery, app killed — identical from here.
        val stale = PeerReport(owns = true, lastReadingMs = now, receivedAtMs = now - silentAfter - 1)
        assertTrue(decide(peer = stale))
    }

    @Test
    fun keepsReadingWhileThePeerIsStillReporting() {
        val fresh = PeerReport(owns = true, lastReadingMs = now, receivedAtMs = now - 30_000L)
        assertFalse(decide(localHasConnection = false, peer = fresh))
    }

    @Test
    fun readsWhenThePeerIsThereButNotHoldingTheSensor() {
        val idle = PeerReport(owns = false, lastReadingMs = 0L, receivedAtMs = now - 10_000L)
        assertTrue(decide(peer = idle))
    }

    @Test
    fun neverTakesASensorTheUserAssignedElsewhere() {
        // Even with the peer gone: the user said this device must not read it.
        assertFalse(decide(intent = Intent.NEVER, peer = null))
        assertFalse(decide(intent = Intent.NEVER, localHasConnection = true, peer = null))
    }

    @Test
    fun bothHoldingItTheFresherReadingWins() {
        val peer = PeerReport(owns = true, lastReadingMs = now - 60_000L, receivedAtMs = now - 5_000L)
        assertTrue(decide(localHasConnection = true, localLastReadingMs = now, peer = peer))

        val fresherPeer = PeerReport(owns = true, lastReadingMs = now, receivedAtMs = now - 5_000L)
        assertFalse(
            decide(localHasConnection = true, localLastReadingMs = now - 60_000L, peer = fresherPeer),
        )
    }

    @Test
    fun bothDevicesReachTheSameAnswerSoItCannotFlap() {
        // Same facts from each side: exactly one of them may conclude "mine".
        val readingOnPhone = now - 20_000L
        val readingOnWatch = now - 45_000L

        val phoneDecides = shouldReadLocally(
            isPhone = true,
            intent = Intent.TAKE,
            localHasConnection = true,
            localLastReadingMs = readingOnPhone,
            peer = PeerReport(true, readingOnWatch, now - 1_000L),
            nowMs = now,
            peerSilentAfterMs = silentAfter,
        )
        val watchDecides = shouldReadLocally(
            isPhone = false,
            intent = Intent.TAKE,
            localHasConnection = true,
            localLastReadingMs = readingOnWatch,
            peer = PeerReport(true, readingOnPhone, now - 1_000L),
            nowMs = now,
            peerSilentAfterMs = silentAfter,
        )

        assertTrue("exactly one device must keep the sensor", phoneDecides != watchDecides)
        assertTrue("the fresher reading should have kept it", phoneDecides)
    }

    @Test
    fun anExactTieGoesToThePhoneRatherThanBeingTradedBack() {
        val sameReading = now - 10_000L
        val phoneDecides = shouldReadLocally(
            isPhone = true,
            intent = Intent.TAKE,
            localHasConnection = true,
            localLastReadingMs = sameReading,
            peer = PeerReport(true, sameReading, now - 1_000L),
            nowMs = now,
            peerSilentAfterMs = silentAfter,
        )
        val watchDecides = shouldReadLocally(
            isPhone = false,
            intent = Intent.TAKE,
            localHasConnection = true,
            localLastReadingMs = sameReading,
            peer = PeerReport(true, sameReading, now - 1_000L),
            nowMs = now,
            peerSilentAfterMs = silentAfter,
        )

        assertTrue(phoneDecides)
        assertFalse(watchDecides)
    }

    @Test
    fun aPeerClockRunningAheadCannotStopUsReading() {
        // Peer timestamps are only ever compared with each other, never with our
        // clock, so a skewed peer cannot make us stand down while it is silent.
        val skewed = PeerReport(
            owns = true,
            lastReadingMs = now + 10L * 60L * 1000L,
            receivedAtMs = now - silentAfter - 1,
        )
        assertTrue(decide(peer = skewed))
    }

    @Test
    fun neitherDeviceEverStandsDownTogether() {
        // Exhaustive over the inputs that decide it: for every combination, at
        // least one side reads.
        val readings = listOf(0L, now - 60_000L, now)
        val received = listOf(now - 1_000L, now - silentAfter - 1)
        for (phoneConn in listOf(true, false)) {
            for (watchConn in listOf(true, false)) {
                for (phoneRead in readings) {
                    for (watchRead in readings) {
                        for (age in received) {
                            val phone = shouldReadLocally(
                                isPhone = true,
                                intent = Intent.TAKE,
                                localHasConnection = phoneConn,
                                localLastReadingMs = phoneRead,
                                peer = PeerReport(watchConn, watchRead, age),
                                nowMs = now,
                                peerSilentAfterMs = silentAfter,
                            )
                            val watch = shouldReadLocally(
                                isPhone = false,
                                intent = Intent.TAKE,
                                localHasConnection = watchConn,
                                localLastReadingMs = watchRead,
                                peer = PeerReport(phoneConn, phoneRead, age),
                                nowMs = now,
                                peerSilentAfterMs = silentAfter,
                            )
                            assertTrue(
                                "nobody would read: phoneConn=$phoneConn watchConn=$watchConn " +
                                    "phoneRead=$phoneRead watchRead=$watchRead age=$age",
                                phone || watch,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun handingOverLetsGoSoTheOtherDeviceCanConnect() {
        // A sensor that serves one client can only be handed over by actually
        // releasing it, so during the window this device must not read.
        assertFalse(
            decide(
                intent = Intent.YIELD,
                localHasConnection = true,
                localLastReadingMs = now,
                peer = PeerReport(owns = false, lastReadingMs = 0L, receivedAtMs = now - 1_000L),
                yieldUntilMs = now + 60_000L,
            ),
        )
    }

    @Test
    fun aHandoverThatFailsGivesTheSensorBackRatherThanLosingIt() {
        // Window spent, peer still has not taken it: read it again.
        assertTrue(
            decide(
                intent = Intent.YIELD,
                peer = PeerReport(owns = false, lastReadingMs = 0L, receivedAtMs = now - 1_000L),
                yieldUntilMs = now - 1L,
            ),
        )
    }

    @Test
    fun onceTheOtherDeviceHasItTheWindowIsIrrelevant() {
        val peerHasIt = PeerReport(owns = true, lastReadingMs = now, receivedAtMs = now - 5_000L)
        assertFalse(decide(intent = Intent.YIELD, peer = peerHasIt, yieldUntilMs = 0L))
        assertFalse(decide(intent = Intent.TAKE, peer = peerHasIt))
    }

    @Test
    fun assignedWatchKeepsOwnershipEvenWhenPhoneReconnectsOnTheSameTimestamp() {
        val peerHasIt = PeerReport(owns = true, lastReadingMs = now, receivedAtMs = now - 1_000L)
        assertFalse(
            decide(
                intent = Intent.YIELD,
                localHasConnection = true,
                localLastReadingMs = now,
                peer = peerHasIt,
            ),
        )
    }

    @Test
    fun assignedWatchDoesNotReleaseForThePhonesStaleExactTimestampReport() {
        val stalePhoneReport = PeerReport(owns = true, lastReadingMs = now, receivedAtMs = now - 1_000L)
        assertTrue(
            decide(
                isPhone = false,
                intent = Intent.PREFER,
                localHasConnection = true,
                localLastReadingMs = now,
                peer = stalePhoneReport,
            ),
        )
    }

    @Test
    fun assignedWatchMayAcquireWhileThePhonesPreReleaseReportIsStillCurrent() {
        val preReleasePhoneReport = PeerReport(owns = true, lastReadingMs = now, receivedAtMs = now - 1_000L)
        assertTrue(
            decide(
                isPhone = false,
                intent = Intent.PREFER,
                localHasConnection = false,
                peer = preReleasePhoneReport,
            ),
        )
    }

    @Test
    fun revokingAssignmentEndsAnOpenYieldWindowWhenPeerDoesNotOwnIt() {
        val peerIdle = PeerReport(owns = false, lastReadingMs = 0L, receivedAtMs = now - 1_000L)
        assertFalse(decide(intent = Intent.YIELD, peer = peerIdle, yieldUntilMs = now + 60_000L))
        assertTrue(decide(intent = Intent.TAKE, peer = peerIdle, yieldUntilMs = now + 60_000L))
    }

    @Test
    fun aHandoverStillEndsWithSomebodyReadingIfTheOtherDeviceVanishes() {
        // Assigned away, window spent, peer silent entirely.
        assertTrue(
            decide(
                intent = Intent.YIELD,
                peer = PeerReport(owns = true, lastReadingMs = now, receivedAtMs = now - silentAfter - 1),
                yieldUntilMs = now - 1L,
            ),
        )
    }

    @Test
    fun phoneUiDistinguishesHandoffFromAProvenWatchStream() {
        assertEquals(
            SensorHandoffUiState.NONE,
            resolveSensorHandoffUiState(false, peerOwns = true, peerReportFresh = true),
        )
        assertEquals(
            SensorHandoffUiState.HANDING_TO_WATCH,
            resolveSensorHandoffUiState(true, peerOwns = false, peerReportFresh = true),
        )
        assertEquals(
            SensorHandoffUiState.HANDING_TO_WATCH,
            resolveSensorHandoffUiState(true, peerOwns = true, peerReportFresh = false),
        )
        assertEquals(
            SensorHandoffUiState.STREAMING_FROM_WATCH,
            resolveSensorHandoffUiState(true, peerOwns = true, peerReportFresh = true),
        )
    }
}
