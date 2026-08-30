package tk.glucodata.alerts

import kotlin.math.min

/**
 * Bounded confirmation wait for early low warnings while sensor-pressure mode is enabled.
 *
 * PRE_LOW and FALLING_FAST are valuable because they arrive before an actual low, but that
 * also makes them the alerts most exposed to a short, false compression fall. A candidate
 * waits just long enough to distinguish the two outcomes: recovery drops the early warning;
 * a fall still standing at the deadline is released. LOW and VERY_LOW never enter this state.
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
        var lowestMgdl: Float
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
        if (type != AlertType.PRE_LOW && type != AlertType.FALLING_FAST) {
            return Decision.ALLOW
        }
        if (!valueMgdl.isFinite() || actualLow) {
            holds.remove(type)
            decidedAtReading[type] = readingTimeMs
            return Decision.ALLOW
        }
        if (type in decidedAtReading) return Decision.ALLOW

        val running = holds[type]
        if (running == null) {
            holds[type] = Hold(nowMs, readingTimeMs, valueMgdl)
            return Decision.HOLD
        }
        if (nowMs < running.startedAtMs || readingTimeMs < running.latestReadingTimeMs) {
            holds.remove(type)
            decidedAtReading[type] = readingTimeMs
            return Decision.ALLOW
        }

        running.latestReadingTimeMs = readingTimeMs
        running.lowestMgdl = min(running.lowestMgdl, valueMgdl)
        if (nowMs - running.startedAtMs < CONFIRMATION_MS) {
            return Decision.HOLD
        }

        holds.remove(type)
        decidedAtReading[type] = readingTimeMs
        return if (valueMgdl >= running.lowestMgdl + RECOVERY_MGDL) {
            Decision.DROP
        } else {
            Decision.ALLOW
        }
    }

    /** A threshold exit or a broken delta run resolves a held early warning as an artifact. */
    fun onCandidateCleared(type: AlertType) {
        holds.remove(type)
        decidedAtReading.remove(type)
    }

    /**
     * FALLING_FAST has no threshold episode object. A newer reading with no candidate means
     * its delta run broke; scheduler passes of the same reading must not cancel a live wait.
     */
    fun onDeltaCandidateMissing(readingTimeMs: Long) {
        val candidateReading = holds[AlertType.FALLING_FAST]?.latestReadingTimeMs
            ?: decidedAtReading[AlertType.FALLING_FAST]
            ?: return
        if (readingTimeMs > candidateReading) {
            holds.remove(AlertType.FALLING_FAST)
            decidedAtReading.remove(AlertType.FALLING_FAST)
        }
    }

    /** Actual-low priority or disabling the feature cancels every early-warning wait. */
    fun clear() {
        holds.clear()
        decidedAtReading.clear()
    }

    internal fun isHolding(type: AlertType): Boolean = type in holds

    companion object {
        const val CONFIRMATION_MINUTES = 6L
        const val RECOVERY_MGDL = 3f
        private const val CONFIRMATION_MS = CONFIRMATION_MINUTES * 60_000L
    }
}
