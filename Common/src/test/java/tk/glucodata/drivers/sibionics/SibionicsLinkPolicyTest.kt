package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsLinkPolicyTest {
    private fun view(
        phase: SibionicsLinkPhase,
        idleReason: SibionicsIdleReason = SibionicsIdleReason.NONE,
        paused: Boolean = false,
        bluetoothOn: Boolean = true,
        restoring: Boolean = false,
        inHistoryTransfer: Boolean = false,
        historyShowable: Boolean = false,
        hasLiveReading: Boolean = false,
        hasAnyReading: Boolean = false,
    ) = SibionicsLinkPolicy.view(
        phase = phase,
        idleReason = idleReason,
        paused = paused,
        bluetoothOn = bluetoothOn,
        restoring = restoring,
        inHistoryTransfer = inHistoryTransfer,
        historyShowable = historyShowable,
        hasLiveReading = hasLiveReading,
        hasAnyReading = hasAnyReading,
    )

    private fun watchdog(
        phase: SibionicsLinkPhase,
        idleReason: SibionicsIdleReason = SibionicsIdleReason.NONE,
        sinceProgressMs: Long = 0L,
        reconnectOverdueMs: Long = 0L,
        recoveryActive: Boolean = false,
        restoring: Boolean = false,
        bluetoothOn: Boolean = true,
    ) = SibionicsLinkPolicy.watchdogAction(
        phase = phase,
        idleReason = idleReason,
        sinceProgressMs = sinceProgressMs,
        reconnectOverdueMs = reconnectOverdueMs,
        recoveryActive = recoveryActive,
        restoring = restoring,
        bluetoothOn = bluetoothOn,
    )

    @Test
    fun scanWaitAndNextAttemptAllReadConnecting() {
        // The reported "Disconnected" header, then "Searching for sensor…" flipping with
        // "Connecting…": one connect that has not landed yet, so one word for all of it.
        for (reason in listOf(
            SibionicsIdleReason.RECONNECT_SCHEDULED,
            SibionicsIdleReason.SEARCHING,
            SibionicsIdleReason.NEEDS_SCAN,
        )) {
            assertEquals(reason.name, SibionicsLinkView.CONNECTING, view(SibionicsLinkPhase.IDLE, reason))
        }
    }

    @Test
    fun backlogPageGapsKeepShowingHistoryProgress() {
        assertEquals(
            SibionicsLinkView.HISTORY,
            view(
                SibionicsLinkPhase.IDLE,
                SibionicsIdleReason.RECONNECT_SCHEDULED,
                inHistoryTransfer = true,
                historyShowable = true,
            ),
        )
    }

    @Test
    fun phasesInsideAConnectionKeepTheirOwnStepText() {
        for (phase in listOf(
            SibionicsLinkPhase.CONNECTING,
            SibionicsLinkPhase.DISCOVERING,
            SibionicsLinkPhase.ENABLING_NOTIFY,
            SibionicsLinkPhase.PROBING_CHINESE,
            SibionicsLinkPhase.AUTHENTICATING,
            SibionicsLinkPhase.ACTIVATING,
            SibionicsLinkPhase.SYNCING_TIME,
            SibionicsLinkPhase.REQUESTING_DATA,
        )) {
            assertEquals(phase.name, SibionicsLinkView.PHASE_STEP, view(phase))
            assertEquals(
                phase.name,
                SibionicsLinkView.PHASE_STEP,
                view(phase, inHistoryTransfer = true, historyShowable = true),
            )
        }
    }

    @Test
    fun streamingMatchesThePreviousHeaderRules() {
        assertEquals(SibionicsLinkView.CONNECTED, view(SibionicsLinkPhase.STREAMING, hasLiveReading = true))
        assertEquals(SibionicsLinkView.HISTORY, view(SibionicsLinkPhase.STREAMING, historyShowable = true))
        assertEquals(SibionicsLinkView.CONNECTED, view(SibionicsLinkPhase.STREAMING, hasAnyReading = true))
        assertEquals(SibionicsLinkView.WAITING_FOR_DATA, view(SibionicsLinkPhase.STREAMING))
    }

    @Test
    fun pausedBluetoothOffAndFailureWinOverPhase() {
        assertEquals(SibionicsLinkView.DISCONNECTED, view(SibionicsLinkPhase.STREAMING, paused = true))
        assertEquals(SibionicsLinkView.BLUETOOTH_OFF, view(SibionicsLinkPhase.IDLE, bluetoothOn = false))
        assertEquals(SibionicsLinkView.FAILED, view(SibionicsLinkPhase.ERROR))
        assertEquals(SibionicsLinkView.DISCONNECTED, view(SibionicsLinkPhase.IDLE))
        assertEquals(SibionicsLinkView.CONNECTING, view(SibionicsLinkPhase.IDLE, restoring = true))
    }

    @Test
    fun silentStreamingLinkIsRecycled() {
        assertEquals(
            SibionicsWatchdogAction.RECYCLE,
            watchdog(SibionicsLinkPhase.STREAMING, sinceProgressMs = SibionicsLinkPolicy.STREAMING_STALL_MS),
        )
        assertEquals(
            SibionicsWatchdogAction.NONE,
            watchdog(SibionicsLinkPhase.STREAMING, sinceProgressMs = 120_000L),
        )
    }

    @Test
    fun stuckSetupAndHandshakeAreRecycled() {
        assertEquals(
            SibionicsWatchdogAction.RECYCLE,
            watchdog(SibionicsLinkPhase.ENABLING_NOTIFY, sinceProgressMs = SibionicsLinkPolicy.SETUP_STALL_MS),
        )
        assertEquals(
            SibionicsWatchdogAction.NONE,
            watchdog(SibionicsLinkPhase.AUTHENTICATING, sinceProgressMs = SibionicsLinkPolicy.SETUP_STALL_MS),
        )
        assertEquals(
            SibionicsWatchdogAction.RECYCLE,
            watchdog(SibionicsLinkPhase.REQUESTING_DATA, sinceProgressMs = SibionicsLinkPolicy.HANDSHAKE_STALL_MS),
        )
        assertEquals(
            SibionicsWatchdogAction.RECYCLE,
            watchdog(SibionicsLinkPhase.ERROR, sinceProgressMs = SibionicsLinkPolicy.SETUP_STALL_MS),
        )
    }

    @Test
    fun idleDriverWithNothingScheduledConnects() {
        assertEquals(SibionicsWatchdogAction.CONNECT, watchdog(SibionicsLinkPhase.IDLE))
        assertEquals(
            SibionicsWatchdogAction.CONNECT,
            watchdog(SibionicsLinkPhase.IDLE, SibionicsIdleReason.NEEDS_SCAN),
        )
        assertEquals(
            SibionicsWatchdogAction.CONNECT,
            watchdog(SibionicsLinkPhase.IDLE, SibionicsIdleReason.BLUETOOTH_OFF),
        )
    }

    @Test
    fun pendingWorkIsLeftAlone() {
        assertEquals(
            SibionicsWatchdogAction.NONE,
            watchdog(SibionicsLinkPhase.IDLE, SibionicsIdleReason.RECONNECT_SCHEDULED, reconnectOverdueMs = 5_000L),
        )
        assertEquals(
            SibionicsWatchdogAction.CONNECT,
            watchdog(
                SibionicsLinkPhase.IDLE,
                SibionicsIdleReason.RECONNECT_SCHEDULED,
                reconnectOverdueMs = SibionicsLinkPolicy.RECONNECT_OVERDUE_MS,
            ),
        )
        assertEquals(
            SibionicsWatchdogAction.NONE,
            watchdog(SibionicsLinkPhase.IDLE, SibionicsIdleReason.SEARCHING, recoveryActive = true),
        )
        assertEquals(SibionicsWatchdogAction.NONE, watchdog(SibionicsLinkPhase.IDLE, restoring = true))
    }

    @Test
    fun nothingHappensWithBluetoothOff() {
        assertEquals(SibionicsWatchdogAction.NONE, watchdog(SibionicsLinkPhase.IDLE, bluetoothOn = false))
        assertEquals(
            SibionicsWatchdogAction.NONE,
            watchdog(SibionicsLinkPhase.STREAMING, sinceProgressMs = Long.MAX_VALUE, bluetoothOn = false),
        )
    }

    @Test
    fun onlyTransportNoticesClearOnConnect() {
        assertTrue(SibionicsLinkPolicy.isTransportNotice(SibionicsLinkNotice.LINK_STATUS))
        assertTrue(SibionicsLinkPolicy.isTransportNotice(SibionicsLinkNotice.NOT_FOUND))
        assertFalse(SibionicsLinkPolicy.isTransportNotice(SibionicsLinkNotice.AUTH_FAILED))
        assertFalse(SibionicsLinkPolicy.isTransportNotice(SibionicsLinkNotice.NOT_RESPONDING))
    }
}
