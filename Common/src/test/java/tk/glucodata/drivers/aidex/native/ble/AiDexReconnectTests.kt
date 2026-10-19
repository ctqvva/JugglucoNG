// JugglucoNG — AiDex Native Kotlin Driver
// AiDexReconnectTests.kt — Unit tests for reconnection strategy
//
// Tests the reconnect delay calculation, auth failure backoff, bond lockout,
// and stale setup detection. No Android framework dependencies.

package tk.glucodata.drivers.aidex.native.ble

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AiDexReconnectTests {

    private lateinit var reconnect: AiDexReconnect

    @Before
    fun setUp() {
        reconnect = AiDexReconnect()
    }

    // ========================================================================
    // Soft Reconnect Delay
    // ========================================================================

    @Test
    fun testFirstReconnectUsesBaseDelay() {
        val delay = reconnect.nextReconnectDelayMs()
        assertEquals(2_500L, delay)
        assertEquals(1, reconnect.softAttempts)
    }

    @Test
    fun testSecondReconnectUses2ndFallback() {
        reconnect.nextReconnectDelayMs() // 1st
        val delay = reconnect.nextReconnectDelayMs() // 2nd
        assertEquals(5_000L, delay)
        assertEquals(2, reconnect.softAttempts)
    }

    @Test
    fun testThirdReconnectUses2ndFallbackAgain() {
        reconnect.nextReconnectDelayMs()
        reconnect.nextReconnectDelayMs()
        val delay = reconnect.nextReconnectDelayMs()
        assertEquals(5_000L, delay)
    }

    // ========================================================================
    // Adaptive Delay
    // ========================================================================

    @Test
    fun testAdaptiveDelayWithNoSlowStreak() {
        assertEquals(2_500L, reconnect.adaptiveDelayMs())
    }

    @Test
    fun testAdaptiveDelayWithSlowStreak() {
        reconnect.recordSlowExecuteConnect()
        reconnect.recordSlowExecuteConnect()
        // 2500 + 2 * 400 = 3300
        assertEquals(3_300L, reconnect.adaptiveDelayMs())
    }

    @Test
    fun testAdaptiveDelayCappedAtMax() {
        // Push streak to maximum (5)
        repeat(10) { reconnect.recordSlowExecuteConnect() }
        // 2500 + 5 * 400 = 4500, within max of 5000
        assertEquals(4_500L, reconnect.adaptiveDelayMs())
    }

    @Test
    fun testAdaptiveDelayMaxValue() {
        // Even with very high streaks, shouldn't exceed max
        repeat(100) { reconnect.recordSlowExecuteConnect() }
        assertTrue(reconnect.adaptiveDelayMs() <= 5_000L)
    }

    @Test
    fun testFastConnectResetsSlowStreak() {
        repeat(5) { reconnect.recordSlowExecuteConnect() }
        reconnect.recordFastExecuteConnect()
        assertEquals(0, reconnect.slowExecuteStreak)
        assertEquals(2_500L, reconnect.adaptiveDelayMs())
    }

    @Test
    fun testConnectAttemptTimeoutTracksSlowConnectStreak() {
        assertEquals(55_000L, reconnect.currentConnectAttemptTimeoutMs())

        reconnect.recordSlowExecuteConnect()
        assertEquals(65_000L, reconnect.currentConnectAttemptTimeoutMs())

        repeat(10) { reconnect.recordSlowExecuteConnect() }
        assertEquals(75_000L, reconnect.currentConnectAttemptTimeoutMs())
    }

    @Test
    fun testConnectCallbackAgeMarksSlowAndFastCallbacks() {
        reconnect.recordConnectCallbackAgeMs(20_000L)
        assertEquals(1, reconnect.slowExecuteStreak)

        reconnect.recordConnectCallbackAgeMs(5_000L)
        assertEquals(0, reconnect.slowExecuteStreak)
    }

    // ========================================================================
    // Auth Failure Backoff
    // ========================================================================

    @Test
    fun testAuthFailureExponentialBackoff() {
        val d1 = reconnect.nextAuthFailureDelayMs()
        assertEquals(2_000L, d1)
        assertEquals(1, reconnect.authFailureCount)

        val d2 = reconnect.nextAuthFailureDelayMs()
        assertEquals(4_000L, d2)

        val d3 = reconnect.nextAuthFailureDelayMs()
        assertEquals(8_000L, d3)

        val d4 = reconnect.nextAuthFailureDelayMs()
        assertEquals(16_000L, d4)

        val d5 = reconnect.nextAuthFailureDelayMs()
        assertEquals(32_000L, d5)
    }

    @Test
    fun testAuthFailureMaxDelay() {
        // 5th failure = 32s, 6th should return null (exhausted)
        repeat(5) { reconnect.nextAuthFailureDelayMs() }
        val d6 = reconnect.nextAuthFailureDelayMs()
        assertNull(d6)
        assertTrue(reconnect.isBroadcastOnlyMode)
    }

    @Test
    fun testAuthFailureNotBroadcastOnlyBeforeExhaustion() {
        reconnect.nextAuthFailureDelayMs()
        assertFalse(reconnect.isBroadcastOnlyMode)
    }

    @Test
    fun testAuthFailureDelayCappedAt60s() {
        // Even if internal calculation exceeds 60s, should be capped
        // The 5th attempt = 2000 * 2^4 = 32000, which is < 60000
        val d5 = (1..5).map { reconnect.nextAuthFailureDelayMs() }.last()
        assertTrue(d5!! <= 60_000L)
    }

    // ========================================================================
    // Bond Failure Lockout
    // ========================================================================

    @Test
    fun testBondFailureTracking() {
        val now = 100_000L
        assertFalse(reconnect.onBondFailure(now))
        assertEquals(1, reconnect.bondFailureCount)
        assertFalse(reconnect.isBondLockoutActive(now))
    }

    @Test
    fun testBondFailureLockout() {
        val now = 100_000L
        reconnect.onBondFailure(now) // 1st
        reconnect.onBondFailure(now) // 2nd
        val locked = reconnect.onBondFailure(now) // 3rd = lockout
        assertTrue(locked)
        assertTrue(reconnect.isBondLockoutActive(now))
    }

    @Test
    fun testBondLockoutDuration() {
        val now = 100_000L
        reconnect.onBondFailure(now)
        reconnect.onBondFailure(now)
        reconnect.onBondFailure(now)

        // Should be locked out for 5 minutes (300_000ms)
        assertTrue(reconnect.isBondLockoutActive(now + 299_999L))
        assertFalse(reconnect.isBondLockoutActive(now + 300_001L))
    }

    @Test
    fun testBondLockoutRemaining() {
        val now = 100_000L
        reconnect.onBondFailure(now)
        reconnect.onBondFailure(now)
        reconnect.onBondFailure(now)

        assertEquals(300_000L, reconnect.bondLockoutRemainingMs(now))
        assertEquals(200_000L, reconnect.bondLockoutRemainingMs(now + 100_000L))
        assertEquals(0L, reconnect.bondLockoutRemainingMs(now + 400_000L))
    }

    @Test
    fun testBondSuccessResets() {
        val now = 100_000L
        reconnect.onBondFailure(now)
        reconnect.onBondFailure(now)
        reconnect.onBondSuccess()
        assertEquals(0, reconnect.bondFailureCount)
        assertFalse(reconnect.isBondLockoutActive(now))
    }

    @Test
    fun testBondLockoutAutoExpiry() {
        val now = 100_000L
        reconnect.onBondFailure(now)
        reconnect.onBondFailure(now)
        reconnect.onBondFailure(now)
        assertTrue(reconnect.isBondLockoutActive(now))

        // After lockout expires, should auto-reset
        assertFalse(reconnect.isBondLockoutActive(now + 400_000L))
        assertEquals(0, reconnect.bondFailureCount) // Reset by isBondLockoutActive()
    }

    // ========================================================================
    // Connection Success / Reset
    // ========================================================================

    @Test
    fun testConnectionSuccessResets() {
        reconnect.nextReconnectDelayMs()
        reconnect.nextReconnectDelayMs()
        reconnect.nextAuthFailureDelayMs()
        reconnect.nextAuthFailureDelayMs()

        reconnect.onConnectionSuccess()

        assertEquals(0, reconnect.softAttempts)
        assertEquals(0, reconnect.authFailureCount)
        assertFalse(reconnect.isBroadcastOnlyMode)
    }

    @Test
    fun testConnectionSuccessDoesNotResetBondFailures() {
        val now = 100_000L
        reconnect.onBondFailure(now)
        reconnect.onBondFailure(now)
        reconnect.onConnectionSuccess()
        // Bond failure count is separate — only onBondSuccess resets it
        assertEquals(2, reconnect.bondFailureCount)
    }

    @Test
    fun testFullReset() {
        val now = 100_000L
        reconnect.nextReconnectDelayMs()
        reconnect.nextAuthFailureDelayMs()
        reconnect.onBondFailure(now)
        reconnect.onBondFailure(now)
        reconnect.onBondFailure(now)
        reconnect.recordSlowExecuteConnect()

        reconnect.reset()

        assertEquals(0, reconnect.softAttempts)
        assertEquals(0, reconnect.slowExecuteStreak)
        assertEquals(0, reconnect.authFailureCount)
        assertEquals(0, reconnect.bondFailureCount)
        assertEquals(0L, reconnect.bondLockoutUntilMs)
        assertFalse(reconnect.isBroadcastOnlyMode)
    }

    // ========================================================================
    // Watchdog Helpers
    // ========================================================================

    @Test
    fun testStaleSetupDetection() {
        val now = 100_000L
        val setupStart = now - 36_000L // 36 seconds ago (> 35s threshold)
        assertTrue(reconnect.isSetupStale(now, setupStart))
    }

    @Test
    fun testSetupNotStale() {
        val now = 100_000L
        val setupStart = now - 30_000L // 30 seconds ago (< 35s threshold)
        assertFalse(reconnect.isSetupStale(now, setupStart))
    }

    @Test
    fun testSetupNotStaleWhenZero() {
        assertFalse(reconnect.isSetupStale(100_000L, 0L))
    }

    @Test
    fun testServiceDiscoveryTimeout() {
        val now = 100_000L
        val discoveryStart = now - 13_000L // 13 seconds ago (> 12s threshold)
        assertTrue(reconnect.isServiceDiscoveryTimedOut(now, discoveryStart))
    }

    @Test
    fun testServiceDiscoveryNotTimedOut() {
        val now = 100_000L
        val discoveryStart = now - 10_000L
        assertFalse(reconnect.isServiceDiscoveryTimedOut(now, discoveryStart))
    }

    @Test
    fun testServiceDiscoveryNotTimedOutWhenZero() {
        assertFalse(reconnect.isServiceDiscoveryTimedOut(100_000L, 0L))
    }

    // ========================================================================
    // Competing Session
    // ========================================================================

    @Test
    fun testCompetingSessionDelay() {
        assertEquals(20_000L, reconnect.competingSessionDelay())
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    fun testBroadcastOnlyModeCleared() {
        // Exhaust auth failures to enter broadcast-only mode
        repeat(6) { reconnect.nextAuthFailureDelayMs() }
        assertTrue(reconnect.isBroadcastOnlyMode)

        // Connection success should clear it
        reconnect.onConnectionSuccess()
        assertFalse(reconnect.isBroadcastOnlyMode)
    }

    @Test
    fun testSlowStreakMaxClamp() {
        repeat(100) { reconnect.recordSlowExecuteConnect() }
        assertEquals(5, reconnect.slowExecuteStreak) // Clamped at maxSlowStreaks
    }

    @Test
    fun testFirstReconnectWithSlowStreakUsesAdaptive() {
        reconnect.recordSlowExecuteConnect()
        reconnect.recordSlowExecuteConnect()
        reconnect.recordSlowExecuteConnect()
        val delay = reconnect.nextReconnectDelayMs() // 1st attempt uses adaptive
        // 2500 + 3 * 400 = 3700
        assertEquals(3_700L, delay)
    }
}
