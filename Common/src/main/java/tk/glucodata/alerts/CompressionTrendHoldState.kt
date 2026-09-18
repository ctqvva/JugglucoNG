package tk.glucodata.alerts

import kotlin.math.max
import kotlin.math.min

/**
 * Bounded confirmation wait for detector-backed sensor-pressure warnings.
 *
 * A pressure dip can fire falling alarms on the way down and rising alarms when pressure is
 * released. Falling candidates track their nadir and rising candidates track their peak. A
 * reversal drops the warning; movement still standing at the deadline is released. HIGH and
 * VERY_HIGH use the same bounded wait but require their threshold condition to clear rather
 * than treating a small reversal above threshold as recovery. LOW and VERY_LOW use the deeper
 * compression detector and never enter this state.
 *
 * One hold is tracked per alert type because the forecast and delta alarms have independent
 * episode latches. The caller owns those latches and tells this state when a candidate clears.
 */
internal class CompressionTrendHoldState {
    enum class Decision {
        /** Keep the early warning pending. */
        HOLD,

        /** The trace recovered during the wait; consume this candidate without alerting. */
        DROP,

        /** The fall did not recover, or a safety rail spoke; run the normal alert path. */
        ALLOW
    }

    private data class Hold(
        val startedAtMs: Long,
        var latestReadingTimeMs: Long,
        val falling: Boolean,
        var extremeMgdl: Float
    )

    private val holds = mutableMapOf<AlertType, Hold>()
    private val decidedAtReading = mutableMapOf<AlertType, Long>()

    fun onCandidate(
        type: AlertType,
        nowMs: Long,
        readingTimeMs: Long,
        valueMgdl: Float,
        actualLow: Boolean
    ): Decision {
        if (type !in confirmationTypes) {
            return Decision.ALLOW
        }
        val falling = isFalling(type)
        if (!valueMgdl.isFinite() || (falling && actualLow)) {
            holds.remove(type)
            decidedAtReading[type] = readingTimeMs
            return Decision.ALLOW
        }
        if (type in decidedAtReading) return Decision.ALLOW

        val running = holds[type]
        if (running == null) {
            holds[type] = Hold(nowMs, readingTimeMs, falling, valueMgdl)
            return Decision.HOLD
        }
        if (nowMs < running.startedAtMs || readingTimeMs < running.latestReadingTimeMs) {
            holds.remove(type)
            decidedAtReading[type] = readingTimeMs
            return Decision.ALLOW
        }

        running.latestReadingTimeMs = readingTimeMs
        running.extremeMgdl = if (running.falling) {
            min(running.extremeMgdl, valueMgdl)
        } else {
            max(running.extremeMgdl, valueMgdl)
        }
        if (nowMs - running.startedAtMs < CONFIRMATION_MS) {
            return Decision.HOLD
        }

        holds.remove(type)
        decidedAtReading[type] = readingTimeMs
        if (type == AlertType.HIGH || type == AlertType.VERY_HIGH) {
            return Decision.ALLOW
        }
        val recovered = if (running.falling) {
            valueMgdl >= running.extremeMgdl + RECOVERY_MGDL
        } else {
            valueMgdl <= running.extremeMgdl - RECOVERY_MGDL
        }
        return if (recovered) {
            Decision.DROP
        } else {
            Decision.ALLOW
        }
    }

    /**
     * A threshold exit on a newer reading resolves a held early warning. Forecast episode
     * hysteresis may briefly clear and re-enter on scheduler passes of the SAME reading;
     * that is not new evidence and must not restart the bounded confirmation clock.
     * Omitting [readingTimeMs] is an explicit force-clear for settings and lifecycle changes.
     */
    fun onCandidateCleared(type: AlertType, readingTimeMs: Long? = null) {
        val candidateReading = holds[type]?.latestReadingTimeMs ?: decidedAtReading[type]
        if (readingTimeMs != null && candidateReading != null && readingTimeMs <= candidateReading) {
            return
        }
        holds.remove(type)
        decidedAtReading.remove(type)
    }

    /**
     * FALLING_FAST has no threshold episode object. A newer reading with no candidate means
     * its delta run broke; scheduler passes of the same reading must not cancel a live wait.
     */
    fun onDeltaCandidateMissing(type: AlertType, readingTimeMs: Long) {
        if (type != AlertType.FALLING_FAST && type != AlertType.RISING_FAST) return
        val candidateReading = holds[type]?.latestReadingTimeMs
            ?: decidedAtReading[type]
            ?: return
        if (readingTimeMs > candidateReading) {
            holds.remove(type)
            decidedAtReading.remove(type)
        }
    }

    /** An actual-low alarm cancels only falling early-warning waits. */
    fun clearFalling() {
        for (type in fallingTypes) {
            holds.remove(type)
            decidedAtReading.remove(type)
        }
    }

    /** Disabling the feature or replacing the sensor reading baseline cancels every wait. */
    fun clear() {
        holds.clear()
        decidedAtReading.clear()
    }

    internal fun isHolding(type: AlertType): Boolean = type in holds

    internal fun supports(type: AlertType): Boolean = type in confirmationTypes

    companion object {
        const val CONFIRMATION_MINUTES = 6L
        const val RECOVERY_MGDL = 3f
        private const val CONFIRMATION_MS = CONFIRMATION_MINUTES * 60_000L
        private val fallingTypes = setOf(AlertType.PRE_LOW, AlertType.FALLING_FAST)
        private val confirmationTypes = fallingTypes + setOf(
            AlertType.PRE_HIGH,
            AlertType.RISING_FAST,
            AlertType.HIGH,
            AlertType.VERY_HIGH
        )

        private fun isFalling(type: AlertType): Boolean = type in fallingTypes
    }
}
