package tk.glucodata.alerts

/** The direction an alert speaks about for cross-family suppression. */
internal enum class AlertDirection { FALLING, RISING }

/** The alert that fired first and the moment it did, for the suppression log line. */
internal data class SameDirectionSuppressor(val type: AlertType, val firedAtMs: Long)

/**
 * Cross-family quiet period for alerts of one direction.
 *
 * "Falling fast" (delta family) and "predicted low" (standard glucose family)
 * are one observation stated twice - the second is the extrapolation of the
 * first - yet the two families evaluate independently and nothing stops them
 * from firing thirty seconds apart. This state is shared by both evaluations:
 * after one direction's alert fires, a *different* alert of the same direction
 * is dropped until the window has passed. Order does not matter; whichever
 * comes second is the one dropped.
 *
 * It is suppression, not deferral: [blockedBy] is a pure query and records
 * nothing, so a suppressed alert is never queued for later delivery. If the
 * situation persists, its own family fires it again once the window is over.
 *
 * LOW, VERY_LOW and VERY_HIGH are exempt by construction. HIGH can optionally
 * join the rising group, but only an acknowledged first alert may cover the
 * second one. This preserves the stronger HIGH alarm when the earlier alert may
 * not have been seen. A window of zero disables the mechanism.
 */
internal class SameDirectionAlertSuppression {
    private val lastFired = mutableMapOf<AlertDirection, SameDirectionSuppressor>()

    /**
     * Returns the alert that keeps [type] quiet at [nowMs], or null when it may
     * fire. An alert is never blocked by its own earlier firing - re-firing of
     * the same type is its own family's business.
     */
    fun blockedBy(
        type: AlertType,
        nowMs: Long,
        windowMs: Long,
        acknowledgedHighCoverage: Boolean = false,
        isAcknowledged: (AlertType) -> Boolean = { false }
    ): SameDirectionSuppressor? {
        if (windowMs <= 0L) return null
        val direction = directionOf(type, acknowledgedHighCoverage) ?: return null
        val last = lastFired[direction] ?: return null
        if (last.type == type) return null
        if ((type == AlertType.HIGH || last.type == AlertType.HIGH) && !isAcknowledged(last.type)) {
            return null
        }
        return if (nowMs - last.firedAtMs < windowMs) last else null
    }

    /** Records an actual delivery; alerts outside the enabled groups are ignored. */
    fun onFired(type: AlertType, nowMs: Long, acknowledgedHighCoverage: Boolean = false) {
        val direction = directionOf(type, acknowledgedHighCoverage) ?: return
        lastFired[direction] = SameDirectionSuppressor(type, nowMs)
    }

    fun clear() {
        lastFired.clear()
    }

    companion object {
        fun directionOf(type: AlertType, acknowledgedHighCoverage: Boolean = false): AlertDirection? = when (type) {
            AlertType.FALLING_FAST, AlertType.PRE_LOW -> AlertDirection.FALLING
            AlertType.RISING_FAST, AlertType.PRE_HIGH -> AlertDirection.RISING
            AlertType.HIGH -> if (acknowledgedHighCoverage) AlertDirection.RISING else null
            // LOW, VERY_LOW, VERY_HIGH and every non-glucose alert: no direction,
            // never suppressed, never suppressing.
            else -> null
        }
    }
}
