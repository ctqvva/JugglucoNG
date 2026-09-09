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
        assertFalse(shouldUseAutoConnect(userSetting = false, consecutiveConnectTimeouts = 0))
    }

    @Test
    fun oneTimeoutIsEnoughToStopBurningThirtySecondTimers() {
        assertTrue(shouldUseAutoConnect(userSetting = false, consecutiveConnectTimeouts = 1))
        assertTrue(shouldUseAutoConnect(userSetting = false, consecutiveConnectTimeouts = 3))
    }

    @Test
    fun aUserWhoAskedForAutoConnectAlwaysGetsIt() {
        assertTrue(shouldUseAutoConnect(userSetting = true, consecutiveConnectTimeouts = 0))
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
