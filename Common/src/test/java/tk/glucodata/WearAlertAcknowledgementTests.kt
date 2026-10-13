package tk.glucodata

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.alerts.AlertType

class WearAlertAcknowledgementTests {
    private val now = 1_800_000_000_000L
    private fun action(kind: Int = AlertType.HIGH.id, snoozed: Boolean = true) =
        WearAlertAcknowledgement(kind, snoozed, now, now + 15 * 60_000)

    @Test fun bothActionsRoundTripForEverySupportedAlert() {
        for (type in AlertType.settingsEntries) for (snoozed in listOf(false, true)) {
            val action = action(type.id, snoozed)
            assertEquals(action, WearAlertAcknowledgement.decode(action.encode(), now + 30_000))
        }
    }

    @Test fun delayedReceiptKeepsTheOriginalDeadline() {
        val action = action()
        val received = WearAlertAcknowledgement.decode(action.encode(), now + 14 * 60_000)!!
        assertEquals(60_000L, received.untilMs - (now + 14 * 60_000))
        assertEquals(action, WearAlertAcknowledgement.decode(action.encode(), action.untilMs + 1))
    }

    @Test fun duplicatesAndOlderActionsCannotReplaceTheLatestState() {
        val latest = action().copy(actedAtMs = now + 10_000)
        assertFalse(action().supersedes(latest))
        assertFalse(latest.supersedes(latest))
        assertTrue(latest.supersedes(action()))
        assertFalse(latest.copy(untilMs = latest.untilMs + 60_000).supersedes(latest))
    }

    @Test fun malformedUnknownAndUnboundedActionsAreRejected() {
        assertNull(WearAlertAcknowledgement.decode(null, now))
        for (size in listOf(0, 1, 21, 23, 1000)) {
            assertNull(WearAlertAcknowledgement.decode(ByteArray(size), now))
        }
        for (invalid in listOf(action(99), action(AlertType.AVAILABLE.id), action().copy(actedAtMs = 0),
            action().copy(actedAtMs = now + 60_001), action().copy(untilMs = now - 1),
            action().copy(untilMs = now + WearAlertAcknowledgement.MAX_DURATION_MS + 1))) {
            assertNull(WearAlertAcknowledgement.decode(invalid.encode(), now))
        }
        assertNull(WearAlertAcknowledgement.decode(action().encode().apply { this[0] = 2 }, now))
        assertNull(WearAlertAcknowledgement.decode(action().encode().apply { this[5] = 2 }, now))
    }
}
