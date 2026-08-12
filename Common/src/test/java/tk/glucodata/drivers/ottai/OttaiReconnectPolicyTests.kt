package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiReconnectPolicyTests {

    private val now = 1_785_000_000_000L

    private val minute = 60_000L
    private val aheadTolerance = 120

    /**
     * A start at or after "now" — a clock that moved backwards, or a bogus committed start —
     * must not invert the bound. A zero or negative ceiling rejects every record, which is the
     * exact failure this filter exists to avoid causing, and it cannot recover on its own
     * because only an accepted record can widen the bound again.
     */
    @Test
    fun aStartNotInThePastNeverProducesAZeroOrNegativeBound() {
        val fromFuture = OttaiBleManager.dataNoCeilingFor(
            authoritativeStartMs = now + 24 * 60 * minute, nowMs = now, lastDataNo = -1, live = true,
        )
        assertEquals(Int.MAX_VALUE, fromFuture)

        val fromNow = OttaiBleManager.dataNoCeilingFor(
            authoritativeStartMs = now, nowMs = now, lastDataNo = -1, live = true,
        )
        assertEquals(Int.MAX_VALUE, fromNow)

        // History with the same skew still falls back to the persisted high-water mark rather
        // than to a negative bound.
        val historySkewed = OttaiBleManager.dataNoCeilingFor(
            authoritativeStartMs = now + 24 * 60 * minute, nowMs = now, lastDataNo = 1_600,
            live = false,
        )
        assertEquals(1_600 + aheadTolerance, historySkewed)
    }

    /**
     * The ceiling's own inputs are only ever updated by a record that already passed it, so a
     * wrong bound is self-reinforcing. Whole payloads rejected in a row mean the bound is wrong,
     * not the sensor — corruption arrives in occasional frames, not in every frame in a row.
     */
    @Test
    fun theCeilingIsDistrustedAfterConsecutiveWholePayloadRejections() {
        val limit = OttaiBleManager.MAX_CONSECUTIVE_CEILING_FULL_DROPS
        assertTrue(limit > 1) // one genuinely corrupt frame must not switch the filter off
        for (drops in 0 until limit) {
            assertFalse(OttaiBleManager.shouldDistrustCeiling(drops))
        }
        assertTrue(OttaiBleManager.shouldDistrustCeiling(limit))
        assertTrue(OttaiBleManager.shouldDistrustCeiling(limit + 1))
    }

    private val ours = "70:D0:7E:42:4D:A2"
    private val stranger = "C0:9B:9E:60:07:37"

    /**
     * Two activation starts corroborate each other when they land within
     * CONFIRMED_START_AGREEMENT_MS. Both are (wall clock - dataNo * interval) from independent
     * reads, so a genuine pair differs only by flooring and poll jitter, while a corrupt dataNo
     * lands hours or days away. The commit is one-way and is the sole input the dataNo ceiling
     * trusts, so a single frame must never be enough to write it.
     */
    @Test
    fun activationStartsAgreeOnlyWithinACoupleOfRecords() {
        val tolerance = OttaiBleManager.CONFIRMED_START_AGREEMENT_MS
        assertEquals(2 * minute, tolerance)

        // Same start seen twice, and a start one record apart: corroborated.
        assertTrue(kotlin.math.abs(now - now) <= tolerance)
        assertTrue(kotlin.math.abs(now - (now - minute)) <= tolerance)

        // A corrupt dataNo of 17_000 against a true one of 1_600 puts the derived starts
        // 15_400 records apart — nowhere near agreement.
        val truthful = now - 1_600 * minute
        val corrupt = now - 17_000 * minute
        assertFalse(kotlin.math.abs(truthful - corrupt) <= tolerance)

        // Even a modest corruption of three records fails to corroborate.
        assertFalse(kotlin.math.abs(truthful - (truthful - 3 * minute)) <= tolerance)
    }

    @Test
    fun aVerifiedCandidateBecomesTheTransportAddress() {
        assertEquals(
            stranger,
            OttaiBleManager.addressAfterCandidateFor(
                candidateAddress = stranger, candidateVerified = true,
                homeAddress = ours, recordAddress = ours,
            ),
        )
    }

    @Test
    fun anUnverifiedCandidateIsNeverAdoptedEvenIfItWasNeverDisproved() {
        // The decisive case: a probe that dies before the signature read — GATT 133, a
        // service-discovery timeout, a remote drop — never reaches rejectActivationCandidate.
        // Keying on "was it disproved" would leave that stranger installed, and
        // SensorBluetooth dispatches on address before any admission check.
        assertEquals(
            ours,
            OttaiBleManager.addressAfterCandidateFor(
                candidateAddress = stranger, candidateVerified = false,
                homeAddress = ours, recordAddress = ours,
            ),
        )
    }

    @Test
    fun exactKnownAddressKeepsNormalAuthenticationBehaviour() {
        assertTrue(
            OttaiBleManager.isKnownActivationAddress(
                candidateAddress = ours,
                homeAddress = ours.lowercase(),
                recordAddress = null,
            ),
        )
    }

    @Test
    fun nameOnlyNeighbourStillRequiresVerifiedSignature() {
        assertFalse(
            OttaiBleManager.isKnownActivationAddress(
                candidateAddress = stranger,
                homeAddress = ours,
                recordAddress = ours,
            ),
        )
    }

    @Test
    fun theRegistryRecordBacksUpAMissingHomeAddress() {
        // The second entry into candidate discovery used not to capture a home address at all,
        // which made the restore a no-op on that path.
        assertEquals(
            ours,
            OttaiBleManager.addressAfterCandidateFor(
                candidateAddress = stranger, candidateVerified = false,
                homeAddress = null, recordAddress = ours,
            ),
        )
    }

    @Test
    fun anUnverifiedCandidateStandsOnlyWhenWeKnowOfNoAddressOfOurOwn() {
        assertEquals(
            stranger,
            OttaiBleManager.addressAfterCandidateFor(
                candidateAddress = stranger, candidateVerified = false,
                homeAddress = null, recordAddress = null,
            ),
        )
    }

    @Test
    fun aMissingCandidateFallsBackToOurOwnAddress() {
        assertEquals(
            ours,
            OttaiBleManager.addressAfterCandidateFor(
                candidateAddress = null, candidateVerified = true,
                homeAddress = null, recordAddress = ours,
            ),
        )
        assertEquals(
            ours,
            OttaiBleManager.addressAfterCandidateFor(
                candidateAddress = "   ", candidateVerified = true,
                homeAddress = ours, recordAddress = null,
            ),
        )
    }

    @Test
    fun confirmedStartBoundsDataNoToTheSensorsElapsedLife() {
        val twoDaysAgo = now - 2 * 24 * 60 * minute
        assertEquals(
            2_880 + aheadTolerance,
            OttaiBleManager.dataNoCeilingFor(twoDaysAgo, now, lastDataNo = -1, live = true),
        )
    }

    @Test
    fun liveIsUnboundedUntilAStartIsConfirmed() {
        // Live must not be gated on the persisted high-water mark: a legitimate sample after a
        // long offline gap is far past it. Without a confirmed start there is nothing else to
        // bound it by, and the frame that gets through is the one that confirms the start.
        assertEquals(
            Int.MAX_VALUE,
            OttaiBleManager.dataNoCeilingFor(0L, now, lastDataNo = 1_600, live = true),
        )
    }

    @Test
    fun historyRejectsCorruptFramesUsingThePersistedHighWaterMark() {
        // bd2730ef's motivating case: front ~17k while the sensor sits at ~1.6k.
        val ceiling = OttaiBleManager.dataNoCeilingFor(0L, now, lastDataNo = 1_600, live = false)
        assertEquals(1_600 + aheadTolerance, ceiling)
        assertEquals(
            Int.MAX_VALUE,
            OttaiBleManager.dataNoCeilingFor(0L, now, lastDataNo = -1, live = false),
        )
    }

    @Test
    fun twoDropsInsideWindowMakeLinkUnstable() {
        val drops = listOf(now - 5 * 60_000L, now - 30_000L)
        assertTrue(OttaiBleManager.isLinkUnstable(drops, now))
    }

    @Test
    fun singleDropIsNotUnstable() {
        assertFalse(OttaiBleManager.isLinkUnstable(listOf(now - 30_000L), now))
    }

    @Test
    fun dropsOlderThanWindowDoNotCount() {
        val drops = listOf(
            now - OttaiBleManager.UNSTABLE_LINK_WINDOW_MS - 60_000L,
            now - OttaiBleManager.UNSTABLE_LINK_WINDOW_MS - 1_000L,
            now - 30_000L,
        )
        assertFalse(OttaiBleManager.isLinkUnstable(drops, now))
    }

    @Test
    fun holdsFastParamsForStormRenegotiation() {
        // The exact renegotiation observed in the 2026-07-25 jamming storm logs.
        assertTrue(
            OttaiBleManager.shouldHoldFastParams(
                intervalUnits = 308,
                latency = 4,
                unstable = true,
                nowMs = now,
                lastReassertMs = 0L,
                reassertsThisConnection = 0,
            ),
        )
    }

    @Test
    fun highSlaveLatencyAloneTriggersHold() {
        assertTrue(
            OttaiBleManager.shouldHoldFastParams(
                intervalUnits = 24,
                latency = 4,
                unstable = true,
                nowMs = now,
                lastReassertMs = 0L,
                reassertsThisConnection = 0,
            ),
        )
    }

    @Test
    fun fastParamsAreLeftAlone() {
        assertFalse(
            OttaiBleManager.shouldHoldFastParams(
                intervalUnits = 12,
                latency = 0,
                unstable = true,
                nowMs = now,
                lastReassertMs = 0L,
                reassertsThisConnection = 0,
            ),
        )
    }

    @Test
    fun stableLinkKeepsSensorPreferredParams() {
        assertFalse(
            OttaiBleManager.shouldHoldFastParams(
                intervalUnits = 308,
                latency = 4,
                unstable = false,
                nowMs = now,
                lastReassertMs = 0L,
                reassertsThisConnection = 0,
            ),
        )
    }

    @Test
    fun reassertIsRateLimited() {
        assertFalse(
            OttaiBleManager.shouldHoldFastParams(
                intervalUnits = 308,
                latency = 4,
                unstable = true,
                nowMs = now,
                lastReassertMs = now - OttaiBleManager.PRIORITY_REASSERT_MIN_GAP_MS + 1_000L,
                reassertsThisConnection = 0,
            ),
        )
    }

    /**
     * linkUnstable is permanently true for a user in a jamming environment — two abnormal drops in
     * 15 minutes is a low bar — so without a per-connection cap the grip runs for the life of the
     * link: ~133 reasserts per sensor per 4h in the 2026-08-01 logs, each one dropping the
     * sensor's effective listen period from 1925ms to 15ms for ~5.8s.
     */
    @Test
    fun theReassertIsCappedPerConnection() {
        val cap = OttaiBleManager.MAX_PRIORITY_REASSERTS_PER_CONNECTION
        for (done in 0 until cap) {
            assertTrue(
                OttaiBleManager.shouldHoldFastParams(
                    intervalUnits = 308,
                    latency = 4,
                    unstable = true,
                    nowMs = now,
                    lastReassertMs = 0L,
                    reassertsThisConnection = done,
                ),
            )
        }
        assertFalse(
            OttaiBleManager.shouldHoldFastParams(
                intervalUnits = 308,
                latency = 4,
                unstable = true,
                nowMs = now,
                lastReassertMs = 0L,
                reassertsThisConnection = cap,
            ),
        )
    }

    @Test
    fun connectingStaleThresholdBacksOffAndCaps() {
        assertEquals(90_000L, OttaiBleManager.connectingStaleThresholdMs(0))
        assertEquals(180_000L, OttaiBleManager.connectingStaleThresholdMs(1))
        assertEquals(360_000L, OttaiBleManager.connectingStaleThresholdMs(2))
        assertEquals(360_000L, OttaiBleManager.connectingStaleThresholdMs(7))
        assertEquals(90_000L, OttaiBleManager.connectingStaleThresholdMs(-1))
    }

    /**
     * All 88 drops of the 2026-08-01 storm were status=8, and 15 of those outages ended within
     * 10s of close(); the peer had stopped answering but was still there, so the 3s default was
     * pure hold. Half of it and no more: this path re-arms from the BT callback thread with the
     * disconnect event still in flight, and connecting inside MIUI's registerApp() churn is how a
     * status=133 gets earned.
     */
    @Test
    fun aSupervisionTimeoutReArmsSooner() {
        assertEquals(1_500L, OttaiBleManager.reconnectDelayAfterDisconnectMs(8, fastReArmAllowed = true))
    }

    /**
     * 19 is a live peer closing the link itself — re-arming early against that builds a
     * connect/terminate loop, and the 3s default demonstrably recovered from it (drop at
     * 1785606352, reconnected 1785606359). 22 is our own teardown, 133 never appeared on this
     * path at all (all five in the logs were ATT reads), and 0 is a clean close.
     */
    @Test
    fun everyOtherDisconnectStatusKeepsTheThreeSecondDefault() {
        for (status in intArrayOf(0, 19, 22, 62, 133, 147)) {
            assertEquals(
                3_000L,
                OttaiBleManager.reconnectDelayAfterDisconnectMs(status, fastReArmAllowed = true),
            )
        }
    }

    /**
     * The shortened re-arm is worth 0.7% of the storm's downtime, so one bounce off the stack's
     * own client registration is enough to give it up: after that the outage runs on the default.
     */
    @Test
    fun aWithdrawnFastReArmFallsBackToTheDefault() {
        assertEquals(3_000L, OttaiBleManager.reconnectDelayAfterDisconnectMs(8, fastReArmAllowed = false))
    }

    @Test
    fun aStatusOneThreeThreeRightAfterAShortenedReArmWithdrawsIt() {
        assertTrue(OttaiBleManager.fastReArmBounced(133, now, now - 1_800L))
        assertTrue(OttaiBleManager.fastReArmBounced(133, now, now))
    }

    @Test
    fun anUnrelatedOneThreeThreeLeavesTheShortenedReArmAlone() {
        // Nothing armed: the pre-existing 133s were ATT reads on an established link, not
        // connect bounces, and they must not disable a delay that was never used.
        assertFalse(OttaiBleManager.fastReArmBounced(133, now, 0L))
        // Long after the re-arm — that is an ordinary failed connect, not the clientIf window.
        assertFalse(OttaiBleManager.fastReArmBounced(133, now, now - 30_000L))
        // A clock step backwards must not count as a bounce either.
        assertFalse(OttaiBleManager.fastReArmBounced(133, now, now + 5_000L))
        // Every other status leaves it armed, supervision timeouts above all.
        assertFalse(OttaiBleManager.fastReArmBounced(8, now, now - 500L))
        assertFalse(OttaiBleManager.fastReArmBounced(19, now, now - 500L))
    }
}
