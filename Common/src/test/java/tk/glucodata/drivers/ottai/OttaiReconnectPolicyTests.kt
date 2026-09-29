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

    /**
     * The count behind that distrust (noteCeilingOutcome). Only payloads the bound actually judged
     * count, and a payload that kept anything ends the run: corruption comes in occasional frames,
     * a wrong bound in every frame.
     */
    @Test
    fun onlyConsecutiveWholePayloadRejectionsBuildTowardsDistrust() {
        val bounded = 500
        assertEquals(2, OttaiBleManager.ceilingFullDropsAfter(2, offered = 0, kept = 0, ceiling = bounded))
        assertEquals(2, OttaiBleManager.ceilingFullDropsAfter(2, offered = 5, kept = 0, ceiling = Int.MAX_VALUE))
        assertEquals(0, OttaiBleManager.ceilingFullDropsAfter(2, offered = 5, kept = 1, ceiling = bounded))
        assertEquals(3, OttaiBleManager.ceilingFullDropsAfter(2, offered = 5, kept = 0, ceiling = bounded))

        fun dropsAfter(vararg keptPerPayload: Int): Int =
            keptPerPayload.fold(0) { drops, kept -> OttaiBleManager.ceilingFullDropsAfter(drops, 5, kept, bounded) }
        assertTrue(OttaiBleManager.shouldDistrustCeiling(dropsAfter(0, 0, 0)))
        // A mixed payload in between resets the run.
        assertFalse(OttaiBleManager.shouldDistrustCeiling(dropsAfter(0, 0, 2, 0)))
        assertEquals(1, dropsAfter(0, 0, 2, 0))
    }

    /**
     * dataNoCeiling() is private and reads the driver's fields, so its two rules are pinned in the
     * source (comments stripped, whitespace flattened): once distrusted it is unbounded, before
     * anything else; otherwise only the confirmed start (materials.activeTimeMs) bounds it. The
     * provisional or the stream anchor there is the dataNoCeiling poison: a start near "now" drops
     * every record, and the anchor that would learn the true start is seeded downstream.
     */
    @Test
    fun theCeilingIsBoundedOnlyByTheConfirmedStart() {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !java.io.File(dir, "Common/src/main/java").isDirectory) dir = dir.parentFile
        val source = java.io.File(dir ?: error("repo root not found"),
            "Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt").readText()
        val from = source.indexOf("private fun dataNoCeiling(")
        assertTrue(from >= 0)
        val to = source.indexOf("private fun noteCeilingOutcome(", from)
        assertTrue(to > from)
        val body = source.substring(from, to)
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
            .replace(Regex("//[^\\n]*"), " ")
            .replace(Regex("\\s+"), " ")
        val distrusted = body.indexOf("if (ceilingDistrusted) return Int.MAX_VALUE")
        val bounded = body.indexOf("return dataNoCeilingFor(")
        assertTrue("distrust must short-circuit the bound", distrusted in 0 until bounded)
        assertEquals(1, Regex("dataNoCeilingFor\\(").findAll(body).count())
        assertTrue(body.contains("authoritativeStartMs = materials.activeTimeMs,"))
        for (forbidden in listOf("effectiveActiveTimeMs(", "provisionalActiveTimeMs", "streamStartTimeMs")) {
            assertFalse("dataNoCeiling must not read $forbidden", body.contains(forbidden))
        }
    }

    private val ours = "70:D0:7E:42:4D:A2"
    private val stranger = "C0:9B:9E:60:07:37"

    /**
     * Two activation starts corroborate each other only when they come from different records and
     * land within CONFIRMED_START_AGREEMENT_MS. A genuine pair differs only by flooring and poll
     * jitter, a corrupt dataNo lands hours or days away, and one record read twice agrees with
     * itself by construction. The commit is one-way and is the sole input the dataNo ceiling
     * trusts, so a single record must never be enough to write it. Calls the driver's predicate
     * rather than restating its arithmetic.
     */
    @Test
    fun activationStartsAgreeOnlyWithinACoupleOfRecords() {
        val tolerance = OttaiBleManager.CONFIRMED_START_AGREEMENT_MS
        assertEquals(2 * minute, tolerance)

        val truthful = now - 1_600 * minute
        val corrupt = now - 17_000 * minute

        // Consecutive records whose starts agree, or sit a record apart either way after flooring.
        assertTrue(OttaiBleManager.startsCorroborate(truthful, 1_600, truthful, 1_601))
        assertTrue(OttaiBleManager.startsCorroborate(truthful, 1_600, truthful + minute, 1_601))
        assertTrue(OttaiBleManager.startsCorroborate(truthful, 1_600, truthful - minute, 1_601))

        // One record read twice — a notify and the poll behind it — even a minute later. A corrupt
        // front of 17_000 arriving that way would have committed a start 10.7 days early, for good.
        assertFalse(OttaiBleManager.startsCorroborate(corrupt, 17_000, corrupt, 17_000))
        assertFalse(OttaiBleManager.startsCorroborate(corrupt, 17_000, corrupt + minute, 17_000))
        assertFalse(OttaiBleManager.startsCorroborate(truthful, 1_600, truthful, 1_600))

        // A corrupt dataNo of 17_000 against a true one of 1_600 puts the derived starts
        // 15_400 records apart — nowhere near agreement.
        assertFalse(OttaiBleManager.startsCorroborate(truthful, 1_600, corrupt, 17_000))

        // Even a modest corruption of three records fails to corroborate.
        assertFalse(OttaiBleManager.startsCorroborate(truthful, 1_600, truthful - 3 * minute, 1_601))
        // Nothing pending yet: nothing to agree with.
        assertFalse(OttaiBleManager.startsCorroborate(0L, -1, truthful, 1_600))
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

    /**
     * A single outage-driven storm: the phone can neither reach the peripheral
     * nor establish a link, and the flat 3s default (justified above for an
     * isolated drop) just re-tries at the same cadence forever. Escalating
     * after a few consecutive failures bounds how long a stuck reconnect can
     * hammer the radio before backing off.
     */
    @Test
    fun repeatedFailuresEscalateBackoffInsteadOfRetryingFlat() {
        assertEquals(
            3_000L,
            OttaiBleManager.reconnectDelayAfterDisconnectMs(147, fastReArmAllowed = true, consecutiveFailures = 0),
        )
        assertEquals(
            3_000L,
            OttaiBleManager.reconnectDelayAfterDisconnectMs(147, fastReArmAllowed = true, consecutiveFailures = 2),
        )
        assertEquals(
            6_000L,
            OttaiBleManager.reconnectDelayAfterDisconnectMs(147, fastReArmAllowed = true, consecutiveFailures = 3),
        )
        assertEquals(
            12_000L,
            OttaiBleManager.reconnectDelayAfterDisconnectMs(147, fastReArmAllowed = true, consecutiveFailures = 4),
        )
        assertEquals(
            30_000L,
            OttaiBleManager.reconnectDelayAfterDisconnectMs(147, fastReArmAllowed = true, consecutiveFailures = 10),
        )
    }

    /**
     * status=8's fast re-arm is the one evidence-based exception in this table
     * (see aSupervisionTimeoutReArmsSooner) — a failure streak must not
     * override it, or a genuinely recoverable supervision timeout would be
     * punished for outages it was not part of.
     */
    @Test
    fun supervisionTimeoutFastReArmIgnoresTheFailureStreak() {
        assertEquals(
            1_500L,
            OttaiBleManager.reconnectDelayAfterDisconnectMs(8, fastReArmAllowed = true, consecutiveFailures = 5),
        )
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
