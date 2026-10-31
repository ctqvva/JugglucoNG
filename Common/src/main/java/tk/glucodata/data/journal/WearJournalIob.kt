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
 * **Truncated lists.** The phone serves only the newest 60 entries of any type, so notes and
 * fingersticks can push a still-active dose or meal out. [isPossiblyTruncated] detects that and
 * the display then withholds the totals instead of showing a silently low number.
 *
 * Also, a dose whose preset is non-counting AND archived is absent from the served presets, so its
 * snapshot curve is still sent and counted here, unlike on the phone (#500).
 *
 * One thing the watch cannot compute yet, raised on #500 rather than worked around:
 *
 * - **A dose whose preset has since been archived.** The phone counts it on purpose
 *   ("archived presets intentionally still count"), and `serveEntries` filters archived
 *   presets out of the payload, so the curve never arrives. The watch under-counts by
 *   exactly that dose. This is still open; nothing here fixes it.
 *
 * **COB** is computed by [cobGrams] from the absorption minutes every carb entry now carries on
 * the wire: the phone resolves them at encode time (the entry's own duration, else grams over the
 * absorption rate at that instant, clamped), so the watch needs no rate and no profile and feeds
 * the same `JournalIobMath.activeCarbsGrams` the phone does. The only disagreement is rounding of
 * resolved minutes to whole minutes. A carb entry that arrives without minutes (a phone that
 * predates the field) makes COB unavailable (`null`) rather than a partial number.
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

    /** Longest COB absorption the phone allows (the 360 minute clamp). */
    private const val COB_HORIZON_MINUTES = 360L

    /**
     * True when the served list may have dropped an entry that is still active, so IOB/COB would
     * read low without saying so. The phone serves only the newest [WearJournalSync.SERVED_ENTRY_CAP]
     * entries of any type, so a full list whose oldest entry is still inside the horizon (the longest
     * of 360 minutes and the longest curve end among the insulin entries) may be missing an older
     * dose or meal. Pending watch-only entries are ignored: they are not part of a serve.
     */
    fun isPossiblyTruncated(journal: WearJournalSync.Journal, atMillis: Long): Boolean {
        val served = journal.entries.filterNot { it.pending }
        if (served.size < WearJournalSync.SERVED_ENTRY_CAP) return false
        val curveEnd = served.maxOf { entry ->
            if (entry.type == WearJournalSync.TYPE_INSULIN) entry.curveMinutes.maxOrNull() ?: 0 else 0
        }
        val horizonMinutes = maxOf(COB_HORIZON_MINUTES, curveEnd.toLong())
        val oldest = served.minOf { it.timestampMs }
        return oldest > atMillis - horizonMinutes * 60_000L
    }

    /** IOB and eIOB in units at [atMillis], from the shared maths the phone uses. */
    fun compute(journal: WearJournalSync.Journal, atMillis: Long): IobResult =
        JournalIobMath.compute(dosesFromWire(journal), atMillis)

    /**
     * Active carbs in grams at [atMillis], or `null` when COB cannot be stated: some carb entry the
     * phone counts has no absorption minutes (an old phone), and summing only the others would show
     * a number that may disagree with the phone's. `0f` means "carbs known, none active", including
     * a journal with no carbs at all, so a caller can tell zero from unavailable.
     *
     * Entries that contribute nothing on the phone (non-finite or non-positive amount) are skipped
     * before the minutes check, as the phone skips them. Pending entries (made on the watch, not yet
     * confirmed by a serve) are excluded and never withhold COB, matching [dosesFromWire], where a
     * pending dose is excluded because it carries no curve. No rate is looked up and the phone's
     * 30..360 minute clamp is not repeated: the minutes arrive resolved, and the shared function
     * does the rest.
     */
    fun cobGrams(journal: WearJournalSync.Journal, atMillis: Long): Float? {
        val carbs = ArrayList<CarbEntry>()
        for (entry in journal.entries) {
            if (entry.type != WearJournalSync.TYPE_CARBS || entry.pending) continue
            val grams = entry.amount.takeIf { it.isFinite() && it > 0f } ?: continue
            val minutes = entry.absorptionMinutes ?: return null
            carbs += CarbEntry(entry.timestampMs, grams, minutes.toFloat())
        }
        // Every entry has explicit minutes, so the rate is never consulted.
        return JournalIobMath.activeCarbsGrams(carbs, atMillis) { Float.NaN }
    }
}
