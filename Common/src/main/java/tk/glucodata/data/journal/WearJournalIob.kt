package tk.glucodata.data.journal

import tk.glucodata.WearJournalSync

/**
 * Insulin IOB/eIOB from the watch's own copy of the journal, as ruled on #500: the watch
 * shows journal-derived values labelled as such, and the phone's remote device-status
 * snapshot is not synced to it.
 *
 * The doses come off the wire, where the phone has already resolved the curve that applies
 * to each one — `WearJournalBridge` sends the entry's own snapshot when it has two or more
 * points, otherwise the preset's curve, and sends no curve at all for a preset that does not
 * count toward IOB (`curvePointsOf`). So the rule here is deliberately narrower than the
 * phone's: an insulin entry with no usable curve contributes nothing rather than being
 * resolved again from a preset the watch may not have.
 *
 * Two things the watch cannot compute yet, both raised on #500 rather than worked around:
 *
 * - **A dose whose preset has since been archived.** The phone counts it on purpose
 *   ("archived presets intentionally still count"), and `serveEntries` filters archived
 *   presets out of the payload, so the curve never arrives. The watch under-counts by
 *   exactly that dose.
 * - **COB.** A carb entry's own absorption time lives in `JournalEntry.durationMinutes`,
 *   which the wire does not carry, and the fallback is `PredictionModelProfileStore`, which
 *   is phone-side and time-dependent. So `activeCarbsGrams` needs either a wire addition or a
 *   synced profile before the watch can show a number that matches the phone's.
 */
object WearJournalIob {

    /**
     * Insulin doses the watch can account for, in the order the journal carries them.
     *
     * Two points is the same threshold the phone applies to a snapshot and the bridge applies
     * before it will use one: a single point describes no curve, so there is nothing to
     * integrate over.
     */
    fun dosesFromWire(journal: WearJournalSync.Journal): List<IobDose> =
        journal.entries.mapNotNull { entry ->
            if (entry.type != WearJournalSync.TYPE_INSULIN) return@mapNotNull null
            val units = entry.amount.takeIf { it.isFinite() && it > 0f } ?: return@mapNotNull null
            if (entry.curveMinutes.size < 2) return@mapNotNull null
            IobDose(
                timestampMillis = entry.timestampMs,
                amountUnits = units,
                curvePoints = entry.curveMinutes.mapIndexed { index, minute ->
                    IobCurvePoint(minute = minute, activity = entry.curveActivity[index])
                }
            )
        }

    /** IOB and eIOB in units at [atMillis], from the shared maths the phone uses. */
    fun compute(journal: WearJournalSync.Journal, atMillis: Long): IobResult =
        JournalIobMath.compute(dosesFromWire(journal), atMillis)
}