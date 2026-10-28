package tk.glucodata.drivers.icanhealth

import org.junit.Assert.*
import org.junit.Test

class ICanHealthRepairStateTests {
    private val installed = 0x0190
    private val stall = ICanHealthProvisioningPolicy.REPAIR_STALL_MS
    private val cooldown = ICanHealthProvisioningPolicy.REPAIR_COOLDOWN_MS
    private val retry = ICanHealthProvisioningPolicy.RepairDecision.RETRY
    private val none = ICanHealthProvisioningPolicy.RepairDecision.NONE
    private val exhausted = ICanHealthProvisioningPolicy.RepairDecision.EXHAUSTED

    private fun stalled(): ICanHealthRepairState = ICanHealthRepairState().apply {
        selectSensor("sensor-a")
        observeSequence(9516, 0)
    }

    private fun ICanHealthRepairState.decisionAt(time: Long, capped: Boolean = false) =
        decision(installed, capped, time)

    @Test fun cachedReconnectsStillProbeAfterBackfillIsSatisfied() {
        val state = stalled()
        assertTrue(state.statusProbeDue(0))
        state.statusProbeRequested(0)
        // A reconnect must preserve throttle and liveness even when no history is requested.
        for (time in 8_000L until 60_000L step 8_000L) {
            assertFalse(state.selectSensor("sensor-a"))
            assertFalse(state.statusProbeDue(time))
        }
        assertTrue(state.statusProbeDue(60_000L))
        state.statusProbeRequested(60_000L)
        state.observeSequence(9516, 60_000L)
        state.selectSensor("sensor-a")
        assertTrue(state.statusProbeDue(stall))
        assertEquals(retry, state.decisionAt(stall))
    }

    @Test fun refusedFirstChunksDoNotSpendAttemptsOrStartCooldown() {
        val state = stalled()
        repeat(4) {
            assertFalse(state.startWrite(true, installed, false, stall + it * 8_000L) { false })
            assertEquals(0, state.attemptCount())
        }
        assertTrue(state.startWrite(true, installed, false, stall + 32_000L) { true })
        assertEquals(1, state.attemptCount())
    }

    @Test fun partialWriteRetainsCooldownAndBudgetAcrossReconnects() {
        val state = stalled()
        assertTrue(state.startWrite(true, installed, false, stall) { true })
        // No completion: disconnect after the first accepted chunk.
        assertFalse(state.selectSensor("sensor-a"))
        state.observeSequence(9516, stall + 8_000L)
        assertEquals(1, state.attemptCount())
        assertEquals(none, state.decisionAt(stall + cooldown - 1))
        assertEquals(retry, state.decisionAt(stall + cooldown))
    }

    @Test fun progressDuringTheDelayOrQueueBacklogCancelsTheRepair() {
        val state = stalled()
        assertEquals(retry, state.decisionAt(stall)) // chain armed, first chunk delayed
        state.observeSequence(9519, stall + 1_000L) // real live progress before dispatch
        var writes = 0
        assertFalse(state.startWrite(true, installed, false, stall + 3_000L) { writes++; true })
        assertEquals(0, writes)
        assertEquals(0, state.attemptCount())
        assertEquals(retry, state.decisionAt(2 * stall + 1_000L))
    }

    @Test fun cappedCounterNeverAuthorizesARewriteEvenWithFreshHistory() {
        val state = stalled()
        state.observeSequence(ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES, 1)
        var writes = 0
        for (time in listOf(stall + 1, cooldown * 3)) {
            assertEquals(none, state.decisionAt(time, capped = true))
            assertFalse(state.startWrite(true, installed, true, time) { writes++; true })
        }
        assertEquals(0, writes)
        assertEquals(0, state.attemptCount())
    }

    @Test fun threeDispatchedWritesExhaustBudgetUntilRealProgressOrReplacement() {
        val state = stalled()
        repeat(3) { attempt ->
            val time = stall + attempt * cooldown
            assertTrue(state.startWrite(true, installed, false, time) { true })
            assertEquals(attempt + 1, state.attemptCount())
        }
        assertEquals(exhausted, state.decisionAt(stall + 3 * cooldown))
        state.observeSequence(9516, stall + 3 * cooldown) // repeated status is not recovery
        assertEquals(exhausted, state.decisionAt(stall + 3 * cooldown))
        state.observeSequence(9519, stall + 3 * cooldown)
        assertEquals(0, state.attemptCount())
        assertEquals(none, state.decisionAt(stall + 3 * cooldown))
        state.selectSensor("sensor-b")
        assertEquals(0, state.attemptCount())
        assertTrue(state.statusProbeDue(stall + 3 * cooldown))
        assertEquals(none, state.decisionAt(stall + 4 * cooldown)) // no baseline for replacement
    }

    @Test fun initialProvisioningAlsoStartsCooldownButDoesNotSpendRepairBudget() {
        val state = stalled()
        assertTrue(state.startWrite(false, 0xffff, false, stall) { true })
        state.writeCompleted(stall + 1_000L)
        assertEquals(0, state.attemptCount())
        assertEquals(none, state.decisionAt(stall + cooldown))
        assertEquals(retry, state.decisionAt(stall + cooldown + 1_000L))
    }

    @Test fun liveProgressBetweenStatusReadsDoesNotLookLikeAStall() {
        val state = stalled()
        state.observeSequence(9519, stall - 1)
        state.observeSequence(9519, stall) // status repeats the latest live sequence
        assertEquals(none, state.decisionAt(stall))
        state.observeSequence(9516, stall + 1) // an older packet is not forward progress
        assertEquals(retry, state.decisionAt(2 * stall - 1))
    }
}
