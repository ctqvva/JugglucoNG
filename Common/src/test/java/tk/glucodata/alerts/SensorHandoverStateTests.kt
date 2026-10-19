package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorHandoverStateTests {

    private val now = 1_000_000_000_000L
    private val oldEnd = now - 60_000L          // primary expired a minute ago
    private val futureEnd = now + 10L * 24 * 60 * 60 * 1000

    private class FakeStore : SensorHandoverStore {
        var handled: String? = null
        var warned: String? = null
        var suppressUntil = 0L
        var suppressSuccessor: String? = null

        override fun isHandled(serial: String, endMs: Long) = handled == "$serial|$endMs"
        override fun markHandled(serial: String, endMs: Long) { handled = "$serial|$endMs" }
        override fun isWarned(serial: String, endMs: Long) = warned == "$serial|$endMs"
        override fun markWarned(serial: String, endMs: Long) { warned = "$serial|$endMs" }
        override fun suppressionUntilMs() = suppressUntil
        override fun suppressionSuccessor() = suppressSuccessor
        override fun openSuppression(untilMs: Long, successorSerial: String) {
            suppressUntil = untilMs
            suppressSuccessor = successorSerial
        }
        override fun clearSuppression() {
            suppressUntil = 0L
            suppressSuccessor = null
        }
    }

    private fun expiredPrimary(endMs: Long = oldEnd) =
        HandoverSensor("OLD", startMs = endMs - 14L * 24 * 60 * 60 * 1000, endMs = endMs)

    /** Successor activated 90 min ago: warmup is over, so "no reading" means disconnected, not warming. */
    private fun successor(
        serial: String = "NEW",
        endMs: Long = futureEnd,
        delivering: Boolean = true,
        startMs: Long = now - 90 * 60_000L
    ) = HandoverSensor(serial, startMs = startMs, endMs = endMs) { delivering }

    /** Successor activated 20 min ago and silent: genuinely inside the warmup phase. */
    private fun warmingSuccessor(serial: String = "NEW") =
        successor(serial, delivering = false, startMs = now - 20 * 60_000L)

    // Test 1: expired primary + one delivering successor -> exactly one switch.
    @Test
    fun switchesOnceWhenPrimaryExpiredAndSuccessorDelivers() {
        val store = FakeStore()
        val state = SensorHandoverState(store)
        val decision = state.evaluate(true, expiredPrimary(), listOf(successor()), now)
        assertEquals(HandoverDecision.Switch("OLD", "NEW", successorWarming = false), decision)

        // The runtime latches before acting; afterwards the same expiry never retriggers.
        store.markHandled("OLD", oldEnd)
        assertEquals(
            HandoverDecision.None,
            state.evaluate(true, expiredPrimary(), listOf(successor()), now + 15_000L)
        )
    }

    // Test 2: successor still in its warmup phase -> switch anyway, flagged as warming.
    @Test
    fun switchesToWarmingSuccessorWithWarmingFlag() {
        val state = SensorHandoverState(FakeStore())
        val decision = state.evaluate(
            true, expiredPrimary(), listOf(warmingSuccessor()), now
        )
        assertEquals(HandoverDecision.Switch("OLD", "NEW", successorWarming = true), decision)
    }

    // A successor whose warmup is long over but which has no readings is
    // disconnected, not warming: switch without the warming flag, so no
    // suppression window can mask the outage.
    @Test
    fun silentSuccessorPastWarmupIsNotWarming() {
        val state = SensorHandoverState(FakeStore())
        val decision = state.evaluate(
            true, expiredPrimary(), listOf(successor(delivering = false)), now
        )
        assertEquals(HandoverDecision.Switch("OLD", "NEW", successorWarming = false), decision)
    }

    // Unknown start time: cannot prove warmup, so never claim warming.
    @Test
    fun unknownStartSuccessorIsNotWarming() {
        val state = SensorHandoverState(FakeStore())
        val decision = state.evaluate(
            true, expiredPrimary(), listOf(successor(delivering = false, startMs = 0L)), now
        )
        assertEquals(HandoverDecision.Switch("OLD", "NEW", successorWarming = false), decision)
    }

    // Test 3: no successor -> nothing happens (and no latch, so a successor
    // scanned after the expiry still gets the handover).
    @Test
    fun noSuccessorDoesNothingUntilOneAppears() {
        val store = FakeStore()
        val state = SensorHandoverState(store)
        assertEquals(HandoverDecision.None, state.evaluate(true, expiredPrimary(), emptyList(), now))
        assertEquals(null, store.handled)

        val later = now + 30 * 60_000L
        assertEquals(
            HandoverDecision.Switch("OLD", "NEW", successorWarming = true),
            state.evaluate(
                true,
                expiredPrimary(),
                listOf(successor(delivering = false, startMs = later - 10 * 60_000L)),
                later
            )
        )
    }

    // Test 4: several candidates -> warn, no automatic switch.
    @Test
    fun multipleCandidatesWarnInsteadOfSwitching() {
        val state = SensorHandoverState(FakeStore())
        val decision = state.evaluate(
            true, expiredPrimary(), listOf(successor("NEW1"), successor("NEW2")), now
        )
        assertEquals(
            HandoverDecision.WarnMultiple("OLD", listOf("NEW1", "NEW2")),
            decision
        )
    }

    // The warning fires once per expiry ...
    @Test
    fun multipleCandidatesWarningIsLatchedPerExpiry() {
        val store = FakeStore()
        val state = SensorHandoverState(store)
        store.markWarned("OLD", oldEnd)
        assertEquals(
            HandoverDecision.None,
            state.evaluate(true, expiredPrimary(), listOf(successor("NEW1"), successor("NEW2")), now)
        )
    }

    // ... but the switch stays armed: resolving the ambiguity resumes the automation.
    @Test
    fun resolvingTheAmbiguityStillSwitches() {
        val store = FakeStore()
        val state = SensorHandoverState(store)
        store.markWarned("OLD", oldEnd)
        assertEquals(
            HandoverDecision.Switch("OLD", "NEW1", successorWarming = false),
            state.evaluate(true, expiredPrimary(), listOf(successor("NEW1")), now + 60_000L)
        )
    }

    // Test 5: setting off -> never anything, regardless of state.
    @Test
    fun disabledNeverDecidesAnything() {
        val state = SensorHandoverState(FakeStore())
        assertEquals(
            HandoverDecision.None,
            state.evaluate(false, expiredPrimary(), listOf(successor()), now)
        )
    }

    @Test
    fun notYetExpiredPrimaryDoesNothing() {
        val state = SensorHandoverState(FakeStore())
        val runningPrimary = HandoverSensor("OLD", startMs = now, endMs = now + 60_000L)
        assertEquals(
            HandoverDecision.None,
            state.evaluate(true, runningPrimary, listOf(successor()), now)
        )
    }

    @Test
    fun unknownPrimaryEndNeverTriggers() {
        val state = SensorHandoverState(FakeStore())
        val unknownEnd = HandoverSensor("OLD", startMs = 0L, endMs = 0L)
        assertEquals(
            HandoverDecision.None,
            state.evaluate(true, unknownEnd, listOf(successor()), now)
        )
    }

    // An expired lingering sensor in the list is not a successor candidate.
    @Test
    fun expiredSensorIsNotACandidate() {
        val state = SensorHandoverState(FakeStore())
        val staleExpired = successor("STALE", endMs = now - 5 * 60_000L)
        assertEquals(
            HandoverDecision.Switch("OLD", "NEW", successorWarming = false),
            state.evaluate(true, expiredPrimary(), listOf(staleExpired, successor()), now)
        )
    }

    // Sensors with unknown ends stay candidates (they are in the active list).
    @Test
    fun unknownEndCandidateStaysEligible() {
        val state = SensorHandoverState(FakeStore())
        val unknownEndCandidate = HandoverSensor("NEW", startMs = now, endMs = 0L) { true }
        assertEquals(
            HandoverDecision.Switch("OLD", "NEW", successorWarming = false),
            state.evaluate(true, expiredPrimary(), listOf(unknownEndCandidate), now)
        )
    }

    // Test 7: idempotency across a process restart - fresh state over the same store.
    @Test
    fun restartDoesNotRepeatTheHandover() {
        val store = FakeStore()
        val first = SensorHandoverState(store)
        assertEquals(
            HandoverDecision.Switch("OLD", "NEW", successorWarming = false),
            first.evaluate(true, expiredPrimary(), listOf(successor()), now)
        )
        store.markHandled("OLD", oldEnd)

        val afterRestart = SensorHandoverState(store)
        assertEquals(
            HandoverDecision.None,
            afterRestart.evaluate(true, expiredPrimary(), listOf(successor()), now + 60_000L)
        )
    }

    // A later expiry (next sensor generation) rearms the latch.
    @Test
    fun nextExpiryRearmsAfterEarlierHandover() {
        val store = FakeStore()
        val state = SensorHandoverState(store)
        store.markHandled("OLD", oldEnd)
        val nextEnd = now + 100L
        val nextPrimary = expiredPrimary(endMs = nextEnd)
        assertEquals(
            HandoverDecision.Switch("OLD", "NEW", successorWarming = false),
            state.evaluate(true, nextPrimary, listOf(successor()), nextEnd + 15_000L)
        )
    }

    // Two sensors sharing an end timestamp do not swallow each other's handover.
    @Test
    fun latchIsKeyedOnSerialPlusEnd() {
        val store = FakeStore()
        val state = SensorHandoverState(store)
        store.markHandled("OTHER", oldEnd)
        assertEquals(
            HandoverDecision.Switch("OLD", "NEW", successorWarming = false),
            state.evaluate(true, expiredPrimary(), listOf(successor()), now)
        )
    }

    // Test 6: missed-reading suppression window around a warming handover.
    @Test
    fun suppressionWindowBlocksUntilSuccessorReading() {
        val store = FakeStore()
        val state = SensorHandoverState(store)
        assertFalse(state.missedReadingSuppressed(now))

        state.openMissedReadingSuppression(now, "NEW")
        assertTrue(state.missedReadingSuppressed(now + 10 * 60_000L))

        // A reading from some other sensor does not close the window.
        state.onReading("OTHER")
        assertTrue(state.missedReadingSuppressed(now + 11 * 60_000L))

        // The successor's first reading does.
        state.onReading("NEW")
        assertFalse(state.missedReadingSuppressed(now + 12 * 60_000L))
    }

    @Test
    fun suppressionWindowExpiresAtTheCap() {
        val store = FakeStore()
        val state = SensorHandoverState(store)
        state.openMissedReadingSuppression(now, "NEW")
        val afterCap = now + SensorHandoverState.MISSED_READING_GRACE_MS
        assertFalse(state.missedReadingSuppressed(afterCap))
        // The store is cleaned up so the window cannot reopen.
        assertEquals(0L, store.suppressUntil)
    }

    @Test
    fun readingWithoutOpenWindowIsIgnored() {
        val store = FakeStore()
        val state = SensorHandoverState(store)
        state.onReading("NEW")
        assertEquals(0L, store.suppressUntil)
        assertFalse(state.missedReadingSuppressed(now))
    }
}
