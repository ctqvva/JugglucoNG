package tk.glucodata.alerts

/**
 * Tracks threshold episodes independently from delivery ownership.
 *
 * Multiple alerts can be active at once (for example HIGH and PRE_HIGH, or HIGH
 * and VERY_HIGH). Only the highest-priority active alert should own delivery,
 * but lower-priority active alerts must still be remembered as already active so
 * they do not fire later when the owner clears on the opposite crossing.
 */
internal class AlertEpisodeState<T> {
    private val active = mutableSetOf<T>()
    private val pendingDelivery = mutableSetOf<T>()

    fun update(activeNow: Set<T>): AlertEpisodeTransition<T> {
        val entered = activeNow.filterTo(mutableSetOf()) { it !in active }
        val cleared = active.filterTo(mutableSetOf()) { it !in activeNow }
        active.clear()
        active.addAll(activeNow)
        pendingDelivery.retainAll(active)
        return AlertEpisodeTransition(
            entered = entered,
            cleared = cleared,
            pendingDelivery = pendingDelivery.toSet()
        )
    }

    fun markPendingDelivery(key: T) {
        if (key in active) {
            pendingDelivery.add(key)
        }
    }

    fun clearPending(key: T) {
        pendingDelivery.remove(key)
    }

    fun clear(key: T) {
        active.remove(key)
        pendingDelivery.remove(key)
    }

    fun clearAll() {
        active.clear()
        pendingDelivery.clear()
    }

    fun isActive(key: T): Boolean = key in active
}

internal data class AlertEpisodeTransition<T>(
    val entered: Set<T>,
    val cleared: Set<T>,
    val pendingDelivery: Set<T>
) {
    fun shouldTryFire(key: T): Boolean {
        return key in entered || key in pendingDelivery
    }
}
