package tk.glucodata.alerts

/**
 * One sensor as the handover evaluation sees it. [endMs] is the official end
 * (start + wear duration, the same source the expiry alarm uses); 0 means
 * unknown. [graceEndMs] is the end of the grace period the sensor keeps
 * reading through after [endMs] (Dexcom, Libre 2: 12 h), 0 when it has none.
 * [hasRecentReading] is evaluated lazily because it needs a history lookup -
 * the decision only asks for it when the answer matters.
 */
internal data class HandoverSensor(
    val serial: String,
    val startMs: Long,
    val endMs: Long,
    val graceEndMs: Long = 0L,
    val hasRecentReading: () -> Boolean = { false }
)

internal sealed class HandoverDecision {
    object None : HandoverDecision()

    /** Switch primary from [oldSerial] to [newSerial]. [successorWarming] when the successor is inside its warmup phase with no reading yet. */
    data class Switch(
        val oldSerial: String,
        val newSerial: String,
        val successorWarming: Boolean
    ) : HandoverDecision()

    /** More than one possible successor - no automation, the user decides. */
    data class WarnMultiple(
        val expiredSerial: String,
        val candidateSerials: List<String>
    ) : HandoverDecision()
}

/**
 * Durable handover state. Production backs this with SharedPreferences
 * ([SensorHandoverRuntime.store]); tests inject a fake. Latches are keyed on
 * the expiring sensor's serial plus its end time so two sensors sharing an
 * end timestamp cannot swallow each other's handover.
 */
internal interface SensorHandoverStore {
    /** True when the handover for this expiry was already performed (or attempted). */
    fun isHandled(serial: String, endMs: Long): Boolean

    fun markHandled(serial: String, endMs: Long)

    /** True when the multiple-candidates warning for this expiry already fired. */
    fun isWarned(serial: String, endMs: Long): Boolean

    fun markWarned(serial: String, endMs: Long)

    /** Missed-reading suppression window end (ms); 0 when no window is open. */
    fun suppressionUntilMs(): Long

    /** Serial of the successor whose first reading closes the window. */
    fun suppressionSuccessor(): String?

    fun openSuppression(untilMs: Long, successorSerial: String)

    fun clearSuppression()
}

/**
 * Decision logic for the automatic sensor handover, kept free of Android and
 * native dependencies so it is unit-testable.
 *
 * Trigger: the primary sensor's official end time has passed and a successor
 * exists. A sensor with a grace period stays the source while it still
 * delivers readings in it; the switch then comes when its readings stop or
 * the grace period ends, so those hours are not thrown away. A successor
 * that is still warming up is switched to regardless (it is the only source);
 * the caller shows an informational notification for that case.
 *
 * Exactly-once: the caller persists the handled key via
 * [SensorHandoverStore.markHandled] *before* performing the switch, so a
 * process restart cannot repeat a handover (at-most-once semantics). The
 * multiple-candidates warning has its own latch ([SensorHandoverStore.markWarned])
 * so it fires once per expiry - but the switch itself stays armed: if the
 * ambiguity is resolved (one candidate removed) the handover still happens.
 */
internal class SensorHandoverState(
    private val store: SensorHandoverStore,
    private val matches: (String?, String?) -> Boolean = { a, b -> a == b }
) {

    fun evaluate(
        enabled: Boolean,
        primary: HandoverSensor?,
        others: List<HandoverSensor>,
        nowMs: Long
    ): HandoverDecision {
        if (!enabled || primary == null) return HandoverDecision.None
        // Unknown end (0) never triggers; the expiry has to be positively known.
        if (primary.endMs <= 0L || primary.endMs > nowMs) return HandoverDecision.None
        if (store.isHandled(primary.serial, primary.endMs)) return HandoverDecision.None
        // Still reading in its grace period: nothing has ended yet.
        if (primary.graceEndMs > nowMs && primary.hasRecentReading()) return HandoverDecision.None

        // A successor must not itself be expired; sensors with unknown ends stay
        // candidates (they are in the active list, so they are running).
        val candidates = others.filter { it.endMs <= 0L || it.endMs > nowMs }
        // No successor: nothing happens, behaviour as today. Deliberately not
        // latched - a successor scanned after the expiry still gets the handover.
        if (candidates.isEmpty()) return HandoverDecision.None
        if (candidates.size > 1) {
            // Warn once per expiry, but keep the switch armed: dropping back to
            // a single candidate resumes the automation.
            return if (store.isWarned(primary.serial, primary.endMs)) {
                HandoverDecision.None
            } else {
                HandoverDecision.WarnMultiple(primary.serial, candidates.map { it.serial })
            }
        }
        val successor = candidates.single()
        // "Warming" strictly means inside the warmup phase after activation. A
        // successor that is merely disconnected (old start, no readings) is NOT
        // warming - it gets no suppression window, so a real outage still alarms.
        val warming = successor.startMs > 0L &&
            nowMs - successor.startMs < WARMUP_DURATION_MS &&
            !successor.hasRecentReading()
        return HandoverDecision.Switch(
            oldSerial = primary.serial,
            newSerial = successor.serial,
            successorWarming = warming
        )
    }

    /**
     * Open the missed-reading suppression window after a handover to a
     * still-warming successor. A brief data gap while the successor warms up
     * must not alarm as an outage; the window closes on the successor's first
     * reading or after [MISSED_READING_GRACE_MS] (then missed-reading resumes
     * normally, which is the fallback warning).
     */
    fun openMissedReadingSuppression(nowMs: Long, successorSerial: String) {
        store.openSuppression(nowMs + MISSED_READING_GRACE_MS, successorSerial)
    }

    fun missedReadingSuppressed(nowMs: Long): Boolean {
        val until = store.suppressionUntilMs()
        if (until <= 0L) return false
        if (nowMs >= until) {
            store.clearSuppression()
            return false
        }
        return true
    }

    /** A reading from the awaited successor ends the suppression window. */
    fun onReading(sensorId: String?) {
        if (store.suppressionUntilMs() <= 0L) return
        val successor = store.suppressionSuccessor()
        if (successor.isNullOrEmpty() || matches(sensorId, successor)) {
            store.clearSuppression()
        }
    }

    companion object {
        /**
         * Warmup phase of a freshly activated sensor (Libre 3: ~1h). Bounds
         * both the "warming" classification and the suppression window; if no
         * reading arrives by the end of it, the missed-reading alarm resumes
         * as the fallback.
         */
        const val WARMUP_DURATION_MS = 60L * 60_000L
        const val MISSED_READING_GRACE_MS = WARMUP_DURATION_MS

        /**
         * The longest stretch past the official end taken as a grace period:
         * Dexcom's documented 12 h, which Libre 2 has too. Longer native
         * estimates (Sibionics) are wear extensions, not a grace period, and
         * keep the switch at the official end.
         */
        const val MAX_GRACE_MS = 12L * 60 * 60_000L

        /**
         * The grace period's end for [HandoverSensor.graceEndMs], 0 when there
         * is none. [hasGracePeriod] is the sensor kind's own answer: native's
         * expected end adds 12 h to every kind it does not special-case
         * (AiDEX and other managed shells too), so the time gap alone does not
         * say the sensor keeps reading past its official end.
         */
        fun graceEnd(hasGracePeriod: Boolean, officialEndMs: Long, expectedEndMs: Long): Long {
            if (!hasGracePeriod) return 0L
            if (officialEndMs <= 0L || expectedEndMs <= officialEndMs) return 0L
            return if (expectedEndMs - officialEndMs <= MAX_GRACE_MS) expectedEndMs else 0L
        }
    }
}
