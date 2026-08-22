package tk.glucodata.alerts

import kotlin.math.min

/**
 * State machine for the compression-low hold: when a LOW alarm is due and the trace
 * carries a live compression signature, the alarm is held back — never suppressed — for
 * a bounded window in which a pressure artifact resolves itself and a real low does not.
 *
 * The rules that make the hold defensible, none negotiable:
 *  - HARD FLOOR: at or below the very-low value the hold ends instantly, whatever the
 *    pattern says. The floor also guards the case where the user disabled the VERY_LOW
 *    alert type — the held LOW then fires in its place.
 *  - BOUNDED: the hold never outlives its configured window; expiry fires the alarm.
 *  - ONLY WHILE THE PATTERN HOLDS: pressure lifting means an upturn within minutes. If
 *    the value has not risen off its floor by the grace deadline, the alarm fires.
 *  - The caller owes the user a signal at hold start (the turn-over cue) — a hold is
 *    quieter than an alarm, never silent.
 *
 * Pure and clock-free: every decision takes `nowMs`, and the caller owns persistence,
 * suspicion assessment, and delivery. One instance tracks one LOW episode at a time.
 */
internal class CompressionHoldState {

    /** With no upturn this many minutes into the hold, the low is treated as real. */
    val upturnGraceMinutes = 6L

    /** An upturn means at least this far back off the lowest held value (mg/dL). */
    val upturnEpsilonMgdl = 3f

    sealed class Action {
        /** Not holding: let the normal alarm path run. */
        object None : Action()

        /** Conditions met: withhold the LOW alarm and play the turn-over cue. */
        object StartHold : Action()

        /** Still inside the window with the pattern intact: keep withholding. */
        object ContinueHold : Action()

        /** The hold is over and the LOW alarm must fire NOW. */
        data class Escalate(val reason: String) : Action()

        /** The low cleared while held: the artifact resolved itself. */
        data class Resolved(val heldMillis: Long) : Action()
    }

    private var holdStartMs = NOT_HOLDING
    private var lowestHeldMgdl = Float.MAX_VALUE

    val holding: Boolean get() = holdStartMs != NOT_HOLDING

    fun holdStartedAtMs(): Long? = holdStartMs.takeIf { it != NOT_HOLDING }

    /**
     * Called while the LOW condition is active and undelivered. [suspicionHeld] is the
     * live detector verdict, consulted only to start a hold — an already-running hold is
     * governed by floor, window and upturn, not by re-assessment.
     */
    fun onLowActive(
        nowMs: Long,
        valueMgdl: Float,
        hardFloorMgdl: Float,
        maxHoldMs: Long,
        suspicionHeld: Boolean
    ): Action {
        if (!valueMgdl.isFinite()) return escalateIfHolding("broken-reading")
        if (hardFloorMgdl.isFinite() && valueMgdl <= hardFloorMgdl) {
            return escalateIfHolding("hard-floor")
        }
        if (!holding) {
            if (!suspicionHeld) return Action.None
            holdStartMs = nowMs
            lowestHeldMgdl = valueMgdl
            return Action.StartHold
        }
        lowestHeldMgdl = min(lowestHeldMgdl, valueMgdl)
        val heldMs = nowMs - holdStartMs
        if (heldMs >= maxHoldMs) {
            reset()
            return Action.Escalate("hold-expired")
        }
        if (heldMs >= upturnGraceMinutes * MINUTE_MS && valueMgdl <= lowestHeldMgdl + upturnEpsilonMgdl) {
            reset()
            return Action.Escalate("no-upturn")
        }
        return Action.ContinueHold
    }

    /** Called when the LOW condition clears (value back above threshold). */
    fun onLowCleared(nowMs: Long): Action {
        if (!holding) return Action.None
        val heldMs = nowMs - holdStartMs
        reset()
        return Action.Resolved(heldMs)
    }

    /** Disabling the feature mid-hold releases the alarm rather than leaving a latch. */
    fun onDisabled(): Action = escalateIfHolding("feature-disabled")

    /**
     * Ends a running hold for a reason decided outside the state machine — e.g. the
     * VERY_LOW alert taking over: the hard floor fired through a different alert type,
     * so the hold is over and counts as escalated, but LOW itself must not double-alarm.
     */
    fun forceEscalate(reason: String): Action = escalateIfHolding(reason)

    private fun escalateIfHolding(reason: String): Action {
        if (!holding) return Action.None
        reset()
        return Action.Escalate(reason)
    }

    private fun reset() {
        holdStartMs = NOT_HOLDING
        lowestHeldMgdl = Float.MAX_VALUE
    }

    private companion object {
        const val NOT_HOLDING = -1L
        const val MINUTE_MS = 60_000L
    }
}
