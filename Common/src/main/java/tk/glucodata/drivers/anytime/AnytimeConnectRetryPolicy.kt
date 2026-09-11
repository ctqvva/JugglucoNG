package tk.glucodata.drivers.anytime

// When a reconnect should stop trying to reach the transmitter directly.
//
// A direct connect (autoConnect=false) gives Android roughly 30 seconds to reach the
// peripheral and then reports status 147. That is the right trade for a device that is
// advertising now; it is the wrong one for a transmitter that has been told `lowPower`
// and is only reliably connectable near its next scheduled push. The 2026-09-09 CT5
// trace spent 96 seconds on three back-to-back timeouts (30016ms, 30027ms, 30038ms)
// before the transmitter woke on its own.
//
// A background connect is not documented to be timeout-free, and OEM stacks vary, but
// it is the mode meant for a peripheral that is not advertising yet: the stack holds
// the address and links up when it appears, rather than expiring a fixed timer. After
// an attempt that already burned that timer, it is the better of the two bets.
//
// Note this is keyed on an observed timeout, not on a sensor family. Every Anytime
// generation sends `lowPower` on entering streaming, so any of them can end up
// unreachable between pushes; equally, a transmitter that answers a direct connect
// never trips this and keeps the user's setting.

/**
 * `BluetoothGatt.GATT_CONNECTION_TIMEOUT`, which is API 35. Spelled out because the
 * driver compiles against a lower minimum and the constant is not available there.
 */
internal const val GATT_CONNECTION_TIMEOUT_STATUS = 147

/** One timeout is already 30 seconds of silence — do not spend a second one to be sure. */
internal const val AUTO_CONNECT_AFTER_TIMEOUTS = 1

/** Overflow guard only: the reading-interval cap below is what actually bounds the wait. */
internal const val MAX_CONNECT_BACKOFF_DOUBLINGS = 30

internal fun isConnectTimeoutStatus(status: Int): Boolean =
    status == GATT_CONNECTION_TIMEOUT_STATUS

internal fun shouldUseAutoConnect(userSetting: Boolean, directConnectUnreachable: Boolean): Boolean =
    userSetting || directConnectUnreachable

/**
 * Reconnect delay after [consecutiveConnectTimeouts] attempts that never reached the
 * transmitter. Doubles from [baseDelayMs] and stops at one reading interval: waiting
 * longer than a full cadence slot cannot help, because the transmitter is connectable
 * at least once per slot.
 */
internal fun connectRetryDelayMs(
    consecutiveConnectTimeouts: Int,
    baseDelayMs: Long,
    readingIntervalMs: Long,
): Long {
    if (consecutiveConnectTimeouts <= 0 || baseDelayMs <= 0L) return baseDelayMs
    val cap = maxOf(readingIntervalMs, baseDelayMs)
    val scaled = baseDelayMs shl consecutiveConnectTimeouts.coerceAtMost(MAX_CONNECT_BACKOFF_DOUBLINGS)
    return scaled.coerceIn(baseDelayMs, cap)
}

/**
 * What the driver has learned about reaching this transmitter, across a process.
 *
 * Two things are tracked because they expire differently. The backoff counts the
 * failures happening right now and any connection at all ends that run. Whether a
 * direct connect can reach this transmitter at all is a property of the transmitter,
 * and connecting over a background connect says nothing about it — clearing the mode
 * there would send the next outage back through another 30-second timer, every time.
 * Only a direct connect that actually succeeded is evidence that direct connects work.
 */
internal class AnytimeConnectModeState {

    /** Failed attempts in the current run, for the reconnect backoff. */
    @Volatile
    var consecutiveConnectTimeouts: Int = 0
        private set

    /** Set once a direct connect has demonstrably failed to reach the transmitter. */
    @Volatile
    var directConnectUnreachable: Boolean = false
        private set

    /**
     * [usedAutoConnect] is the mode the attempt that just connected was opened with.
     */
    fun onConnected(usedAutoConnect: Boolean) {
        consecutiveConnectTimeouts = 0
        if (!usedAutoConnect) {
            directConnectUnreachable = false
        }
    }

    /**
     * [wasConnecting] separates an attempt that never reached the transmitter from a
     * link that was up and dropped; only the former says anything about reachability.
     */
    fun onDisconnected(status: Int, wasConnecting: Boolean) {
        if (!wasConnecting || !isConnectTimeoutStatus(status)) {
            consecutiveConnectTimeouts = 0
            return
        }
        consecutiveConnectTimeouts += 1
        if (consecutiveConnectTimeouts >= AUTO_CONNECT_AFTER_TIMEOUTS) {
            directConnectUnreachable = true
        }
    }

    fun useAutoConnect(userSetting: Boolean): Boolean =
        shouldUseAutoConnect(userSetting, directConnectUnreachable)

    fun retryDelayMs(baseDelayMs: Long, readingIntervalMs: Long): Long =
        connectRetryDelayMs(consecutiveConnectTimeouts, baseDelayMs, readingIntervalMs)
}
