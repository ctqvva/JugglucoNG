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
    private val yieldWindow = 3L * 60L * 1000L
    private val yieldRetry = 15L * 60L * 1000L

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
            resolveSensorHandoffUiState(
                companionEnabled = true,
                releasedLocally = false,
                peerOwns = true,
                peerReportFresh = true,
            ),
        )
        assertEquals(
            SensorHandoffUiState.HANDING_TO_WATCH,
            resolveSensorHandoffUiState(
                companionEnabled = true,
                releasedLocally = true,
                peerOwns = false,
                peerReportFresh = true,
            ),
        )
        assertEquals(
            SensorHandoffUiState.HANDING_TO_WATCH,
            resolveSensorHandoffUiState(
                companionEnabled = true,
                releasedLocally = true,
                peerOwns = true,
                peerReportFresh = false,
            ),
        )
        assertEquals(
            SensorHandoffUiState.STREAMING_FROM_WATCH,
            resolveSensorHandoffUiState(
                companionEnabled = true,
                releasedLocally = true,
                peerOwns = true,
                peerReportFresh = true,
            ),
        )
    }

    @Test
    fun disabledCompanionCannotOwnThePhoneUiOrSensorIntent() {
        assertEquals(
            SensorHandoffUiState.NONE,
            resolveSensorHandoffUiState(
                companionEnabled = false,
                releasedLocally = true,
                peerOwns = true,
                peerReportFresh = true,
            ),
        )
        assertEquals(
            Intent.TAKE,
            resolveSensorOwnershipIntent(
                isWearable = false,
                companionEnabled = false,
                directRequested = false,
                assignedToWatch = true,
            ),
        )
    }

    @Test
    fun discoveryLosingThePeerIsWhatNoticesItLeaving() {
        val grace = 30_000L
        // The failure this replaces: the watch switched off while the phone was
        // already standing down, so no announcement was due to fail, and the
        // phone stayed released while discovery reported no watch for a minute.
        assertTrue(
            resolvePeerGone(
                autoSwitchEnabled = true,
                undiscoverableForMs = grace,
                graceMs = grace,
            ),
        )
        // A blip must not hand the sensor over: an assigned owner coming back
        // straight after would open a fresh stand-down window.
        assertFalse(
            resolvePeerGone(
                autoSwitchEnabled = true,
                undiscoverableForMs = grace - 1L,
                graceMs = grace,
            ),
        )
        // Discovery finding the peer, reported as a negative duration.
        assertFalse(
            resolvePeerGone(
                autoSwitchEnabled = true,
                undiscoverableForMs = -1L,
                graceMs = grace,
            ),
        )
        // An empty snapshot is not the peer leaving until the grace has run.
        assertFalse(
            resolvePeerGone(
                autoSwitchEnabled = true,
                undiscoverableForMs = 0L,
                graceMs = grace,
            ),
        )
        // None of it applies with the switch off: that arbitration is unchanged.
        assertFalse(
            resolvePeerGone(
                autoSwitchEnabled = false,
                undiscoverableForMs = 10L * grace,
                graceMs = grace,
            ),
        )
    }

    @Test
    fun anAbsentPeerGetsNoHandoverWindowAtAll() {
        // Automatic switching through an explicit handover: the watch stays the
        // assigned owner, but a watch that cannot be reached must not keep the
        // phone off the sensor for the whole silence timeout.
        val window = resolveYieldWindow(
            intent = Intent.YIELD,
            peerOwns = true,
            peerGone = true,
            startedAtMs = now - 30_000L,
            nowMs = now,
            windowMs = yieldWindow,
            retryIntervalMs = yieldRetry,
        )
        assertEquals(0L, window.deadlineMs)
        // Forgotten, so the watch coming back opens a fresh window rather than
        // resuming a spent one.
        assertEquals(null, window.startedAtMs)

        // And the phone then actually reads: a stale report is what reconcile
        // passes alongside peerGone.
        assertTrue(
            decide(
                intent = Intent.YIELD,
                peer = null,
                yieldUntilMs = window.deadlineMs,
            ),
        )
    }

    @Test
    fun aReachablePeerKeepsTheBoundedHandoverWindow() {
        val opened = resolveYieldWindow(
            intent = Intent.YIELD,
            peerOwns = false,
            peerGone = false,
            startedAtMs = null,
            nowMs = now,
            windowMs = yieldWindow,
            retryIntervalMs = yieldRetry,
        )
        assertEquals(now + yieldWindow, opened.deadlineMs)
        assertEquals(now, opened.startedAtMs)
        assertFalse(decide(intent = Intent.YIELD, yieldUntilMs = opened.deadlineMs))

        // Window spent without the peer taking it: read it again, but remember
        // the attempt so the retry is timed from it.
        val spent = resolveYieldWindow(
            intent = Intent.YIELD,
            peerOwns = false,
            peerGone = false,
            startedAtMs = now - yieldWindow,
            nowMs = now,
            windowMs = yieldWindow,
            retryIntervalMs = yieldRetry,
        )
        assertEquals(0L, spent.deadlineMs)
        assertEquals(now - yieldWindow, spent.startedAtMs)

        val retried = resolveYieldWindow(
            intent = Intent.YIELD,
            peerOwns = false,
            peerGone = false,
            startedAtMs = now - yieldRetry,
            nowMs = now,
            windowMs = yieldWindow,
            retryIntervalMs = yieldRetry,
        )
        assertEquals(now + yieldWindow, retried.deadlineMs)
    }

    @Test
    fun aPeerThatTookTheSensorNeedsNoWindowButIsRemembered() {
        val held = resolveYieldWindow(
            intent = Intent.YIELD,
            peerOwns = true,
            peerGone = false,
            startedAtMs = now - yieldRetry,
            nowMs = now,
            windowMs = yieldWindow,
            retryIntervalMs = yieldRetry,
        )
        assertEquals(0L, held.deadlineMs)
        // Remembered as of now: the peer later dropping the sensor must not read
        // as a fresh handover and blank the phone again.
        assertEquals(now, held.startedAtMs)
    }

    @Test
    fun peerOwningEndsAnyStandDownWindow() {
        assertEquals(
            null,
            resolvePeerStandDownStart(
                previousOwns = false,
                reportOwns = true,
                currentFalseSinceMs = now - 5_000L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun anOwnerLettingGoStartsTheGraceWindow() {
        val since = resolvePeerStandDownStart(
            previousOwns = true,
            reportOwns = false,
            currentFalseSinceMs = null,
            nowMs = now,
        )
        assertEquals(now, since)
        assertFalse(resolvePeerStandDownConfirmed(since, now, confirmMs = 15_000L))
    }

    @Test
    fun aRepeatedFalseKeepsTheWindowItIsAlreadyIn() {
        assertEquals(
            now - 10_000L,
            resolvePeerStandDownStart(
                previousOwns = false,
                reportOwns = false,
                currentFalseSinceMs = now - 10_000L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun aPeerNeverSeenOwningIsBelievedAtOnce() {
        // The first report after a start, or from a peer that was idle all
        // along: nothing to debounce, and debouncing it invented an owner.
        val first = resolvePeerStandDownStart(
            previousOwns = null,
            reportOwns = false,
            currentFalseSinceMs = null,
            nowMs = now,
        )
        assertEquals(null, first)
        assertTrue(resolvePeerStandDownConfirmed(first, now, confirmMs = 15_000L))
        val stillIdle = resolvePeerStandDownStart(
            previousOwns = false,
            reportOwns = false,
            currentFalseSinceMs = null,
            nowMs = now,
        )
        assertEquals(null, stillIdle)
    }

    @Test
    fun theGraceWindowClosesAfterConfirmMs() {
        assertFalse(resolvePeerStandDownConfirmed(now - 14_999L, now, confirmMs = 15_000L))
        assertTrue(resolvePeerStandDownConfirmed(now - 15_000L, now, confirmMs = 15_000L))
    }

    @Test
    fun neitherAFailedSendNorABriefEmptySnapshotIsThePeerLeaving() {
        val grace = 30_000L
        assertFalse(
            resolvePeerGone(
                autoSwitchEnabled = true,
                undiscoverableForMs = -1L,
                graceMs = grace,
            ),
        )
        // Even with discovery empty, the grace still applies.
        assertFalse(
            resolvePeerGone(
                autoSwitchEnabled = true,
                undiscoverableForMs = 0L,
                graceMs = grace,
            ),
        )
    }

    private val perReadingFresh = 11L * 60_000L
    private val connectedStale = 20L * 60_000L

    private fun proof(
        released: Boolean = false,
        gattConnected: Boolean = false,
        connectsPerReading: Boolean = false,
        seenAt: Long? = null,
    ) = resolveLocalOwnershipProof(
        released = released,
        gattConnected = gattConnected,
        connectsPerReading = connectsPerReading,
        lastLocalReadingSeenAtMs = seenAt,
        nowMs = now,
        perReadingFreshMs = perReadingFresh,
        connectedStaleMs = connectedStale,
    )

    @Test
    fun aG7BetweenReadingsIsStillOwned() {
        // Hung up 4 minutes ago, as it does after every reading.
        assertTrue(proof(connectsPerReading = true, seenAt = now - 4L * 60_000L))
        // Two readings missed: no longer.
        assertFalse(proof(connectsPerReading = true, seenAt = now - perReadingFresh - 1L))
        // A connection alone proves nothing for it.
        assertFalse(proof(connectsPerReading = true, gattConnected = true, seenAt = null))
    }

    @Test
    fun aConnectedSensorIsOwnedUntilItsReadingsGoStale() {
        // Warm-up, pairing, a new sensor: the link is all there is.
        assertTrue(proof(gattConnected = true, seenAt = null))
        assertTrue(proof(gattConnected = true, seenAt = now - 60_000L))
        // Up, and delivering nothing for twenty minutes.
        assertFalse(proof(gattConnected = true, seenAt = now - connectedStale - 1L))
        // Disconnected: not ours, however recent the last reading.
        assertFalse(proof(gattConnected = false, seenAt = now - 60_000L))
    }

    @Test
    fun aReleasedSensorIsNeverOwned() {
        assertFalse(proof(released = true, gattConnected = true, seenAt = now))
        assertFalse(proof(released = true, connectsPerReading = true, seenAt = now))
    }

    @Test
    fun enabledCompanionStillHonoursExplicitWatchAssignment() {
        assertEquals(
            Intent.YIELD,
            resolveSensorOwnershipIntent(
                isWearable = false,
                companionEnabled = true,
                directRequested = false,
                assignedToWatch = true,
            ),
        )
        assertEquals(
            Intent.PREFER,
            resolveSensorOwnershipIntent(
                isWearable = true,
                companionEnabled = true,
                directRequested = true,
                assignedToWatch = false,
            ),
        )
    }
}
