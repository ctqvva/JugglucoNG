package tk.glucodata.alerts

import tk.glucodata.TrendArrowAngle

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
 * Only an *acknowledged* first alert (dismissed or snoozed) may cover the
 * second one. Suppression means "you already saw this", not "it already made a
 * sound somewhere": an alarm that fired while the user was away, or that a
 * quiet window silenced, was never seen, so it must not take the early warning
 * of the next one away. LOW, VERY_LOW and VERY_HIGH are exempt by construction.
 * HIGH can optionally join the rising group. Acknowledgement belongs to the
 * delivery, not the current episode: clearing a forecast or expiring a snooze
 * does not end the window. A fresh trend in the opposite direction does end
 * it, using the same flat boundary as the displayed arrow. A window of zero
 * disables the mechanism.
 */
internal class SameDirectionAlertSuppression {
    private val lastFired = mutableMapOf<AlertDirection, SameDirectionSuppressor>()
    private var lastTrendReadingTimeMs = 0L

    /** Flat, unavailable and repeated readings cannot turn an existing trend around. */
    fun observeTrend(readingTimeMs: Long, rate: Float, trendTrusted: Boolean) {
        if (!trendTrusted || !rate.isFinite() || readingTimeMs <= lastTrendReadingTimeMs) return
        lastTrendReadingTimeMs = readingTimeMs
        val angle = TrendArrowAngle.rotationDegrees(rate)
        when {
            angle < 0f -> lastFired.remove(AlertDirection.FALLING)
            angle > 0f -> lastFired.remove(AlertDirection.RISING)
        }
    }

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
        // Query acknowledgement of that delivery, even if its episode/snooze has ended.
        if (!isAcknowledged(last.type)) return null
        return if (nowMs - last.firedAtMs < windowMs) last else null
    }

    /** Records an actual delivery; alerts outside the enabled groups are ignored. */
    fun onFired(type: AlertType, nowMs: Long, acknowledgedHighCoverage: Boolean = false) {
        val direction = directionOf(type, acknowledgedHighCoverage) ?: return
        lastFired[direction] = SameDirectionSuppressor(type, nowMs)
    }

    fun clear() {
        lastFired.clear()
        lastTrendReadingTimeMs = 0L
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
