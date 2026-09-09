package tk.glucodata.drivers.anytime

/**
 * When a reconnect should stop hammering the peripheral directly.
 *
 * A direct connect (autoConnect=false) gives Android 30 seconds to reach the
 * peripheral and then reports status 147. That is the right trade for a device
 * that is advertising now; it is the wrong one for an Anytime transmitter that
 * has been told `lowPower` and only becomes connectable near its next scheduled
 * push. The 2026-09-09 CT5 trace lost 96 seconds to three back-to-back 30-second
 * timeouts (30016ms, 30027ms, 30038ms) before the transmitter woke on its own.
 *
 * autoConnect=true has no timeout: the stack keeps the address on its background
 * connect list and links up on the first advertisement, which is exactly the
 * behaviour a duty-cycled transmitter needs.
 *
 * Nothing here changes an attempt that has not timed out, so a family whose
 * connects land promptly keeps the user's setting and this policy never applies.
 */

/**
 * `BluetoothGatt.GATT_CONNECTION_TIMEOUT`, added in API 33. Spelled out because
 * the constant is not available on the minimum SDK this driver supports.
 */
internal const val GATT_CONNECTION_TIMEOUT_STATUS = 147

/** One timeout is already 30 seconds of silence — do not spend a second one to be sure. */
internal const val AUTO_CONNECT_AFTER_TIMEOUTS = 1

/** Overflow guard only: the reading-interval cap below is what actually bounds the wait. */
internal const val MAX_CONNECT_BACKOFF_DOUBLINGS = 30

internal fun isConnectTimeoutStatus(status: Int): Boolean =
    status == GATT_CONNECTION_TIMEOUT_STATUS

internal fun shouldUseAutoConnect(userSetting: Boolean, consecutiveConnectTimeouts: Int): Boolean =
    userSetting || consecutiveConnectTimeouts >= AUTO_CONNECT_AFTER_TIMEOUTS

/**
 * Reconnect delay after [consecutiveConnectTimeouts] attempts that never reached
 * the peripheral. Doubles from [baseDelayMs] and stops at one reading interval:
 * waiting longer than a full cadence slot cannot help, because the transmitter is
 * connectable at least once per slot.
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
