package tk.glucodata.data

/**
 * Decides whether a reading shows the number it was recorded as, or a number
 * derived from the settings in force right now.
 *
 * The rule this replaces was "whatever the calibration currently computes",
 * applied destructively to the stored reading. That made the past a function of
 * the present: every calibration edit, every raw/auto flip, every toggle of
 * "apply to past" moved values the user had already read — and, because the
 * rewrite fed on its own output, moved them further each time. Freezing settles
 * that: after the grace window a displayed value is a record, not a derivation.
 *
 * Pure on purpose. Everything Android-shaped — the preference, the calibration
 * model, the clock — is the caller's to supply, so the rule itself is testable
 * and has exactly one definition.
 */
internal object ReadingDisplayPolicy {

    /**
     * The value to show, in mg/dL.
     *
     * @param recorded what was recorded for this reading, if anything.
     * @param derivedMgdl what the current settings produce for it.
     * @param freezeEnabled the user's "keep displayed values" setting.
     */
    fun displayMgdl(
        recorded: ReadingDisplay?,
        derivedMgdl: Float,
        nowMs: Long,
        freezeEnabled: Boolean
    ): Float {
        if (!freezeEnabled) return derivedMgdl
        if (recorded == null || !recorded.isUsable) return derivedMgdl
        if (!recorded.isSealedAt(nowMs)) return derivedMgdl
        return recorded.displayMgdl
    }

    /**
     * Whether to write (or overwrite) the record for a reading.
     *
     * A reading with no record gets one. A record still inside its grace window
     * is refreshed, so the recent tail tracks a calibration the user is in the
     * middle of entering. A sealed record is never touched — that is the whole
     * guarantee, and it is what makes recording idempotent: replaying the same
     * pass cannot walk a value anywhere, because the second pass either writes
     * the identical derivation or declines.
     */
    fun shouldRecord(
        recorded: ReadingDisplay?,
        nowMs: Long,
        freezeEnabled: Boolean
    ): Boolean {
        if (!freezeEnabled) return false
        if (recorded == null) return true
        return !recorded.isSealedAt(nowMs)
    }

    /**
     * Whether an explicit "recompute history" may replace a record.
     *
     * The deliberate act the seal makes possible: the user asked, so sealed
     * records inside the requested range are fair game. Nothing else in the app
     * may pass true here.
     */
    fun shouldRecomputeExplicitly(
        recorded: ReadingDisplay?,
        fromTimestampMs: Long,
        readingTimestampMs: Long
    ): Boolean {
        if (recorded == null) return true
        return readingTimestampMs >= fromTimestampMs
    }
}
