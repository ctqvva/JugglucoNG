// JugglucoNG — AiDex Native Kotlin Driver
// AiDexReconnect.kt — Reconnection strategy with exponential backoff & lockout
//
// Standalone, testable reconnection state machine. No Android framework deps.
// Ported from AiDexSensor.kt reconnect constants (lines 738-850) and iOS
// AiDexSensorManager.swift reconnect logic.

package tk.glucodata.drivers.aidex.native.ble

/**
 * Manages reconnection delays and failure tracking for a single AiDex sensor.
 *
 * Three independent failure tracks:
 *   1. **Soft reconnect** — normal disconnects or signal loss
 *   2. **Auth failure** — GATT status 5 / SMP auth rejected (exponential backoff)
 *   3. **Bond failure** — repeated SMP bond failures trigger lockout
 *
 * Also provides:
 *   - Service discovery watchdog timeout
 *   - Stale setup detection
 *   - Competing session delay (another app connected to sensor)
 */
class AiDexReconnect {

    // -- Configuration (all in milliseconds) --

    /** Base delay for first reconnect attempt after disconnect */
    var reconnectBaseMs: Long = 2_500L
        private set

    /** Per-slow-streak adaptive bump added to base */
    var reconnectAdaptiveStepMs: Long = 400L
        private set

    /** Maximum adaptive reconnect delay */
    var reconnectMaxAdaptiveMs: Long = 5_000L
        private set

    /** 2nd fallback delay (longer, for app restart scenarios) */
    var reconnect2ndFallbackMs: Long = 5_000L
        private set

    /** Delay when a competing BLE session is detected */
    var competingSessionDelayMs: Long = 20_000L
        private set

    /** Auth failure backoff base (doubles each failure) */
    var authFailureBaseMs: Long = 2_000L
        private set

    /** Maximum auth failure delay */
    var authFailureMaxMs: Long = 60_000L
        private set

    /** Max consecutive auth failures before lockout */
    var maxAuthFailures: Int = 5
        private set

    /** Max consecutive bond failures before lockout */
    var maxBondFailures: Int = 3
        private set

    /** Duration of bond failure lockout */
    var bondLockoutDurationMs: Long = 5 * 60_000L
        private set

    /** Timeout for service discovery watchdog */
    var serviceDiscoveryTimeoutMs: Long = 12_000L
        private set

    /** Timeout for stale setup detection (no progress in handshake) */
    var staleSetupTimeoutMs: Long = 35_000L
        private set

    /** Base timeout for connectGatt() producing its first callback */
    var connectAttemptTimeoutBaseMs: Long = 55_000L
        private set

    /** Per-slow-streak adaptive bump added to connect-attempt timeout */
    var connectAttemptTimeoutAdaptiveStepMs: Long = 10_000L
        private set

    /** Maximum connect-attempt timeout */
    var connectAttemptTimeoutMaxMs: Long = 75_000L
        private set

    /** Callback age beyond which a connect attempt is considered "slow" */
    var slowConnectCallbackThresholdMs: Long = 15_000L
        private set

    /** Max slow-execute-connect streak before adaptive delay maxes out */
    var maxSlowStreaks: Int = 5
        private set

    // -- State --

    /** Consecutive soft reconnect attempts */
    var softAttempts: Int = 0
        private set

    /** Slow execute-connect streak (register() works but response takes >2.5s) */
    var slowExecuteStreak: Int = 0
        private set

    /** Consecutive auth failure count */
    var authFailureCount: Int = 0
        private set

    /** Consecutive bond failure count */
    var bondFailureCount: Int = 0
        private set

    /** Timestamp when bond lockout expires (0 = no lockout) */
    var bondLockoutUntilMs: Long = 0L
        private set

    /** Whether we're in broadcast-only fallback mode (auth failures exhausted or manually set) */
    var isBroadcastOnlyMode: Boolean = false
        internal set

    // -- Reconnect Delay Calculation --

    /**
     * Calculate the delay for the next reconnect attempt after a normal disconnect.
     *
     * First attempt uses base + adaptive bump. Second uses the longer 2nd fallback.
     * Further attempts alternate between adaptive and 2nd fallback.
     */
    fun nextReconnectDelayMs(): Long {
        val attempt = softAttempts
        softAttempts++
        return if (attempt == 0) {
            adaptiveDelayMs()
        } else {
            reconnect2ndFallbackMs
        }
    }

    /**
     * Calculate adaptive delay based on slow-execute-connect streak.
     */
    fun adaptiveDelayMs(): Long {
        val bump = slowExecuteStreak.coerceIn(0, maxSlowStreaks) * reconnectAdaptiveStepMs
        return (reconnectBaseMs + bump).coerceAtMost(reconnectMaxAdaptiveMs)
    }

    /**
     * Bounded timeout for a pending connectGatt() attempt to produce its first
     * callback. This stays independent from normal reconnect delay selection:
     * reconnect delays answer "when should we try again?", while this timeout
     * answers "how long do we tolerate an unresolved connect attempt before we
     * declare the connection state stale?".
     */
    fun currentConnectAttemptTimeoutMs(): Long {
        val bump = slowExecuteStreak.coerceIn(0, maxSlowStreaks) * connectAttemptTimeoutAdaptiveStepMs
        return (connectAttemptTimeoutBaseMs + bump).coerceAtMost(connectAttemptTimeoutMaxMs)
    }

    /**
     * Delay to use when a competing BLE session is detected
     * (another app/phone is connected to the sensor).
     */
    fun competingSessionDelay(): Long = competingSessionDelayMs

    // -- Auth Failure Backoff --

    /**
     * Calculate delay after an authentication failure (GATT status 5).
     * Uses exponential backoff: 2s, 4s, 8s, 16s, 32s, capped at 60s.
     *
     * @return delay in ms, or null if auth failures are exhausted (enter broadcast-only mode)
     */
    fun nextAuthFailureDelayMs(): Long? {
        authFailureCount++
        if (authFailureCount > maxAuthFailures) {
            isBroadcastOnlyMode = true
            return null
        }
        val delay = authFailureBaseMs * (1L shl (authFailureCount - 1).coerceAtMost(5))
        return delay.coerceAtMost(authFailureMaxMs)
    }

    // -- Bond Failure Tracking --

    /**
     * Record a bond failure. Returns true if lockout is now active.
     *
     * @param nowMs current time in milliseconds
     */
    fun onBondFailure(nowMs: Long): Boolean {
        bondFailureCount++
        if (bondFailureCount >= maxBondFailures) {
            bondLockoutUntilMs = nowMs + bondLockoutDurationMs
            return true
        }
        return false
    }

    /**
     * Check if bond failure lockout is currently active.
     *
     * @param nowMs current time in milliseconds
     */
    fun isBondLockoutActive(nowMs: Long): Boolean {
        if (bondLockoutUntilMs == 0L) return false
        if (nowMs >= bondLockoutUntilMs) {
            // Lockout expired — reset
            bondLockoutUntilMs = 0L
            bondFailureCount = 0
            return false
        }
        return true
    }

    /**
     * Remaining lockout time in milliseconds (0 if not locked out).
     */
    fun bondLockoutRemainingMs(nowMs: Long): Long {
        if (!isBondLockoutActive(nowMs)) return 0L
        return (bondLockoutUntilMs - nowMs).coerceAtLeast(0L)
    }

    // -- Slow Execute Connect Tracking --

    /**
     * Record that a register->executeConnect round-trip was slow.
     * Increases adaptive delay for subsequent reconnects.
     */
    fun recordSlowExecuteConnect() {
        slowExecuteStreak = (slowExecuteStreak + 1).coerceAtMost(maxSlowStreaks)
    }

    /**
     * Record that a register->executeConnect round-trip was fast.
     * Resets the slow streak counter.
     */
    fun recordFastExecuteConnect() {
        slowExecuteStreak = 0
    }

    /**
     * Record how long a connectGatt() attempt took to deliver its first
     * callback. Slow callbacks increase the tolerance for future unresolved
     * connect attempts on the same device/stack, while fast callbacks reset the
     * tolerance back to the normal base.
     */
    fun recordConnectCallbackAgeMs(callbackAgeMs: Long) {
        if (callbackAgeMs >= slowConnectCallbackThresholdMs) {
            recordSlowExecuteConnect()
        } else {
            recordFastExecuteConnect()
        }
    }

    // -- Success / Reset --

    /**
     * Call when a connection is fully established (key exchange complete, streaming).
     * Resets soft attempt counter and auth failure state.
     */
    fun onConnectionSuccess() {
        softAttempts = 0
        authFailureCount = 0
        isBroadcastOnlyMode = false
    }

    /**
     * Call when a successful bond is established.
     * Resets bond failure counter and lockout.
     */
    fun onBondSuccess() {
        bondFailureCount = 0
        bondLockoutUntilMs = 0L
    }

    /**
     * Full reset — clears all state (e.g. when sensor changes).
     */
    fun reset() {
        softAttempts = 0
        slowExecuteStreak = 0
        authFailureCount = 0
        bondFailureCount = 0
        bondLockoutUntilMs = 0L
        isBroadcastOnlyMode = false
    }

    // -- Watchdog Helpers --

    /**
     * Check if setup has stalled (no progress since [setupStartMs]).
     *
     * @param nowMs current time
     * @param setupStartMs when the current setup attempt started
     * @return true if stale and should be restarted
     */
    fun isSetupStale(nowMs: Long, setupStartMs: Long): Boolean {
        if (setupStartMs == 0L) return false
        return (nowMs - setupStartMs) > staleSetupTimeoutMs
    }

    /**
     * Check if service discovery has timed out.
     *
     * @param nowMs current time
     * @param discoveryStartMs when discoverServices() was called
     * @return true if watchdog should fire
     */
    fun isServiceDiscoveryTimedOut(nowMs: Long, discoveryStartMs: Long): Boolean {
        if (discoveryStartMs == 0L) return false
        return (nowMs - discoveryStartMs) > serviceDiscoveryTimeoutMs
    }
}
