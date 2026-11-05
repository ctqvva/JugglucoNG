package tk.glucodata.alerts

import org.junit.Assert.*
import org.junit.Test

class SignalRestoredAlertStateTests {
    private val start = 1_800_000_000_000L
    private val wait = 30 * 60_000L - 20_000L // Same deadline as Signal loss.
    private val fresh = 5 * 60_000L

    private fun SignalRestoredAlertState.read(at: Long, now: Long = at, id: String = "sensor-A",
        lossWait: Long = wait) = onReading(id, at, now, lossWait, fresh)

    @Test fun startupAndOrdinaryReadingsStaySilent() {
        val state = SignalRestoredAlertState()
        assertFalse(state.read(start))
        repeat(40) { assertFalse(state.read(start + (it + 1) * 60_000L)) }
    }

    @Test fun firstFreshReadingAfterTimeoutFiresOnceAndAnotherOutageRearms() {
        val state = SignalRestoredAlertState()
        assertFalse(state.read(start))
        assertTrue(state.read(start + wait))
        assertFalse(state.read(start + wait))
        assertFalse(state.read(start + wait + 60_000L))
        assertTrue(state.read(start + 2 * wait + 60_000L))
    }

    @Test fun aShortDisconnectDoesNotAnnounceRecovery() {
        val state = SignalRestoredAlertState()
        state.read(start)
        assertFalse(state.read(start + wait - 1))
    }

    @Test fun timeoutUsesTheArrivalTimeJustLikeTheLossAlarm() {
        val state = SignalRestoredAlertState()
        state.read(start)
        assertTrue(state.read(start + wait - 60_000L, now = start + wait))
    }

    @Test fun replayAndDuplicateRowsCannotConsumeTheRecoveryEdge() {
        val state = SignalRestoredAlertState()
        state.read(start)
        val now = start + 2 * wait
        assertFalse(state.read(start - 60_000L, now))
        assertFalse(state.read(start, now))
        assertFalse(state.read(start + wait, now)) // Newer, but still stale history.
        assertTrue(state.read(now))
        assertFalse(state.read(now))
    }

    @Test fun aDifferentMainSensorDoesNotInheritTheOutage() {
        val state = SignalRestoredAlertState()
        state.read(start)
        assertFalse(state.read(start + wait, id = "sensor-B"))
        assertFalse(state.read(start + wait + 60_000L, id = "sensor-B"))
    }

    @Test fun startupSeedRetainsAnOutageAcrossProcessRestart() {
        val state = SignalRestoredAlertState()
        state.seed("sensor-A", start)
        state.seed("sensor-A", start + wait) // A later display sync must not eat the gap.
        assertTrue(state.read(start + wait))
    }

    @Test fun disabledRecoveryStillTracksReadingsAndCannotAnnounceAnOldRecoveryOnEnable() {
        val state = SignalRestoredAlertState()
        state.read(start, lossWait = 0)
        assertFalse(state.read(start + wait, lossWait = 0))
        assertFalse(state.read(start + wait + 60_000L))
        assertTrue(state.read(start + 2 * wait + 60_000L))
    }

    @Test fun invalidAndFutureReadingsDoNotChangeTheBaseline() {
        val state = SignalRestoredAlertState()
        state.read(start)
        assertFalse(state.onReading(null, start + wait, start + wait, wait, fresh))
        assertFalse(state.read(0, start + wait))
        assertFalse(state.read(start + 2 * wait, start + wait))
        assertTrue(state.read(start + wait))
    }

    @Test fun settingsAndSoundMatchTheRequestedPlacementAndExistingCue() {
        val entries = AlertType.settingsEntries
        assertEquals(AlertType.SIGNAL_RESTORED, entries[entries.indexOf(AlertType.LOSS) + 1])
        val config = AlertDefaults.defaultConfig(AlertType.SIGNAL_RESTORED, false)
        assertFalse(config.enabled)
        assertEquals(AlertDeliveryMode.NOTIFICATION_ONLY, config.deliveryMode)
        assertTrue(config.soundEnabled)
        assertNull(config.durationMinutes)
        assertEquals(AlertSoundDefaults.cueFor(AlertType.SENSOR_EXPIRY.id),
            AlertSoundDefaults.cueFor(config.type.id))
        assertNotEquals(AlertSoundDefaults.cueFor(AlertType.LOSS.id), AlertSoundDefaults.cueFor(config.type.id))
        assertFalse(AlertSoundDefaults.hasNativeSlot(config.type.id))
    }
}
