package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconnect policy after a direct connect that never reaches the transmitter.
 * Numbers come from the 2026-09-09 CT5 trace: three 30-second timeouts (status
 * 147) at a flat 2-second retry cost 96 seconds of dead air.
 */
class AnytimeConnectRetryPolicyTests {

    private val threeMinutesMs = 3L * 60L * 1000L
    private val baseMs = 2_000L

    @Test
    fun onlyTheThirtySecondTimeoutStatusCountsAsAConnectTimeout() {
        assertTrue(isConnectTimeoutStatus(147))
        // 257 (GATT_FAILURE) and 133 (GATT_ERROR) are lost links, not unreachable
        // peripherals: they say nothing about whether the transmitter is advertising.
        assertFalse(isConnectTimeoutStatus(257))
        assertFalse(isConnectTimeoutStatus(133))
        assertFalse(isConnectTimeoutStatus(0))
    }

    @Test
    fun firstAttemptKeepsTheUsersDirectConnectSetting() {
        assertFalse(shouldUseAutoConnect(userSetting = false, directConnectUnreachable = false))
    }

    @Test
    fun aUserWhoAskedForAutoConnectAlwaysGetsIt() {
        assertTrue(shouldUseAutoConnect(userSetting = true, directConnectUnreachable = false))
    }

    @Test
    fun aSensorThatAnswersDirectConnectsNeverChangesMode() {
        // The policy is keyed on an observed timeout, not on a sensor family, so this
        // is what every generation that connects normally gets — CT2.5 through CT4 as
        // much as a healthy CT5. Nothing here has timed out, so nothing changes.
        val state = AnytimeConnectModeState()

        repeat(5) {
            state.onConnected(usedAutoConnect = false)
            // Ordinary end of a session: remote hung up, link was up at the time.
            state.onDisconnected(status = 19, wasConnecting = false)
            assertFalse(state.directConnectUnreachable)
            assertFalse(state.useAutoConnect(userSetting = false))
            assertEquals(baseMs, state.retryDelayMs(baseMs, threeMinutesMs))
        }
    }

    @Test
    fun aLinkThatDropsAfterConnectingSaysNothingAboutReachability() {
        val state = AnytimeConnectModeState()
        state.onConnected(usedAutoConnect = false)

        // 257 one second after the low-power ack — the 2026-09-09 disconnect. The link
        // was up, so it is not evidence that a direct connect cannot reach the sensor.
        state.onDisconnected(status = 257, wasConnecting = false)

        assertFalse(state.directConnectUnreachable)
        assertFalse(state.useAutoConnect(userSetting = false))
    }

    @Test
    fun oneTimeoutIsEnoughToStopBurningThirtySecondTimers() {
        val state = AnytimeConnectModeState()

        state.onDisconnected(status = 147, wasConnecting = true)

        assertTrue(state.directConnectUnreachable)
        assertTrue(state.useAutoConnect(userSetting = false))
    }

    @Test
    fun theCt5ReconnectStormBacksOffInsteadOfRepeatingThirtySecondTimeouts() {
        val state = AnytimeConnectModeState()

        // 22:57:22 — the link we had drops.
        state.onDisconnected(status = 257, wasConnecting = false)
        assertFalse(state.useAutoConnect(userSetting = false))
        assertEquals(baseMs, state.retryDelayMs(baseMs, threeMinutesMs))

        // 22:57:54, 22:58:26, 22:58:58 — three direct connects that never arrive.
        state.onDisconnected(status = 147, wasConnecting = true)
        assertTrue(state.useAutoConnect(userSetting = false))
        assertEquals(4_000L, state.retryDelayMs(baseMs, threeMinutesMs))

        state.onDisconnected(status = 147, wasConnecting = true)
        state.onDisconnected(status = 147, wasConnecting = true)
        assertEquals(16_000L, state.retryDelayMs(baseMs, threeMinutesMs))

        // 22:59:23 — it advertises and the stack links up.
        state.onConnected(usedAutoConnect = true)
        assertEquals(baseMs, state.retryDelayMs(baseMs, threeMinutesMs))
    }

    @Test
    fun connectingOverAutoConnectDoesNotBuyBackAnotherThirtySecondTimeout() {
        // The transmitter is still only connectable near its push; a background connect
        // succeeding proves nothing about direct connects, so the next outage must not
        // start by spending the timer again.
        val state = AnytimeConnectModeState()
        state.onDisconnected(status = 147, wasConnecting = true)

        state.onConnected(usedAutoConnect = true)
        state.onDisconnected(status = 19, wasConnecting = false)

        assertTrue(state.directConnectUnreachable)
        assertTrue(state.useAutoConnect(userSetting = false))
    }

    @Test
    fun aDirectConnectThatSucceedsAgainReturnsTheUsersSetting() {
        // The only evidence that direct connects work is one that worked: a new sensor,
        // a firmware that advertises differently, or simply better conditions.
        val state = AnytimeConnectModeState()
        state.onDisconnected(status = 147, wasConnecting = true)
        assertTrue(state.useAutoConnect(userSetting = false))

        state.onConnected(usedAutoConnect = false)

        assertFalse(state.directConnectUnreachable)
        assertFalse(state.useAutoConnect(userSetting = false))
    }

    @Test
    fun aConnectionThatSucceededRetriesAtTheNormalDelay() {
        assertEquals(
            baseMs,
            connectRetryDelayMs(
                consecutiveConnectTimeouts = 0,
                baseDelayMs = baseMs,
                readingIntervalMs = threeMinutesMs,
            )
        )
    }

    @Test
    fun repeatedTimeoutsBackOffInsteadOfRetryingEveryTwoSeconds() {
        val delays = (1..4).map {
            connectRetryDelayMs(
                consecutiveConnectTimeouts = it,
                baseDelayMs = baseMs,
                readingIntervalMs = threeMinutesMs,
            )
        }
        assertEquals(listOf(4_000L, 8_000L, 16_000L, 32_000L), delays)
    }

    @Test
    fun backoffNeverOutlastsOneCadenceSlot() {
        // The transmitter is connectable at least once per reading interval, so
        // waiting longer than one slot only delays recovery.
        val delay = connectRetryDelayMs(
            consecutiveConnectTimeouts = 20,
            baseDelayMs = baseMs,
            readingIntervalMs = threeMinutesMs,
        )
        assertEquals(threeMinutesMs, delay)
    }

    @Test
    fun aBaseDelayLongerThanTheIntervalIsStillHonoured() {
        assertEquals(
            10_000L,
            connectRetryDelayMs(
                consecutiveConnectTimeouts = 5,
                baseDelayMs = 10_000L,
                readingIntervalMs = 1_000L,
            )
        )
    }
}
