package tk.glucodata

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local connection gate for sensors deliberately released to a peer.
 *
 * The driver pause bit is user-facing state and can be cleared by UI or driver
 * recovery code. Ownership release is a different contract: until arbitration
 * resumes the sensor, every path towards connectGatt must remain closed.
 */
internal class SensorOwnershipReleaseState(
    private val keyOf: (String) -> String,
) {
    private val released = ConcurrentHashMap<String, Boolean>()

    fun release(serial: String) {
        released[keyOf(serial)] = true
    }

    fun resume(serial: String) {
        released.remove(keyOf(serial))
    }

    fun isReleased(serial: String): Boolean = released[keyOf(serial)] == true

    fun releasedSerials(): Set<String> = released.keys.toSet()
}
