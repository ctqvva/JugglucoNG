package tk.glucodata.alerts

/**
 * The direction a trend alert speaks about. Threshold alerts (LOW, VERY_LOW,
 * HIGH, VERY_HIGH) deliberately have none: they report an arrival, not a
 * movement, and are never suppressed here.
 */
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
 * Threshold alerts are exempt by construction ([directionOf] returns null for
 * them): actually arriving low or high must always be announced, even half a
 * minute after a warning about it. A window of zero disables the mechanism.
 */
internal class SameDirectionAlertSuppression {
    private val lastFired = mutableMapOf<AlertDirection, SameDirectionSuppressor>()

    /**
     * Returns the alert that keeps [type] quiet at [nowMs], or null when it may
     * fire. An alert is never blocked by its own earlier firing - re-firing of
     * the same type is its own family's business.
     */
    fun blockedBy(type: AlertType, nowMs: Long, windowMs: Long): SameDirectionSuppressor? {
        if (windowMs <= 0L) return null
        val direction = directionOf(type) ?: return null
        val last = lastFired[direction] ?: return null
        if (last.type == type) return null
        return if (nowMs - last.firedAtMs < windowMs) last else null
    }

    /** Records an actual delivery; alerts without a direction are ignored. */
    fun onFired(type: AlertType, nowMs: Long) {
        val direction = directionOf(type) ?: return
        lastFired[direction] = SameDirectionSuppressor(type, nowMs)
    }

    fun clear() {
        lastFired.clear()
    }

    companion object {
        fun directionOf(type: AlertType): AlertDirection? = when (type) {
            AlertType.FALLING_FAST, AlertType.PRE_LOW -> AlertDirection.FALLING
            AlertType.RISING_FAST, AlertType.PRE_HIGH -> AlertDirection.RISING
            // LOW, VERY_LOW, HIGH, VERY_HIGH and every non-glucose alert: no
            // direction, never suppressed, never suppressing.
            else -> null
        }
    }
}
