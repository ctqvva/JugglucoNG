package tk.glucodata.data.journal

import java.util.Locale
import tk.glucodata.WearJournalSync

/**
 * What the watch's journal screen shows for IOB / eIOB / COB, decided without any UI so the
 * visibility rules can be tested. The numbers are journal-derived ([WearJournalIob]) and are
 * labelled as such; they carry no risk colouring (#500).
 *
 * Archived-preset gap: a dose whose preset was archived afterwards still counts on the phone but
 * not here, so IOB can read low by that dose; see #500 before treating the figure as exact.
 *
 * Truncation: when the served list is at the phone's cap and its oldest entry is still inside the
 * activity horizon, older active entries may be missing, so the totals are unavailable (empty).
 *
 * Rules, mirroring the phone (`JournalCobState`, `JournalIobAccess.formatUnits`):
 * - a value that would render as zero (below 0.05, or not finite) is `null` and is not shown;
 * - whole numbers print without a decimal, everything else with one;
 * - COB is `null` when [WearJournalIob.cobGrams] cannot state it (a counted carb entry without
 *   absorption minutes), which hides it rather than showing 0;
 * - pending watch-only entries are not counted by the maths underneath, so they never appear here.
 */
data class WearJournalIobDisplay(
    val iob: String?,
    val eiob: String?,
    val cob: String?,
) {
    /** Nothing to show: the caller omits the whole block. */
    val isEmpty: Boolean get() = iob == null && eiob == null && cob == null

    companion object {
        private const val MIN_SHOWN = 0.05f

        fun of(
            journal: WearJournalSync.Journal,
            atMillis: Long,
            locale: Locale = Locale.getDefault(),
        ): WearJournalIobDisplay {
            // A full served list may be missing an older, still-active dose or meal: show nothing
            // rather than a silently low total.
            if (WearJournalIob.isPossiblyTruncated(journal, atMillis)) {
                return WearJournalIobDisplay(iob = null, eiob = null, cob = null)
            }
            val insulin = WearJournalIob.compute(journal, atMillis)
            return WearJournalIobDisplay(
                iob = format(insulin.iobUnits, locale),
                eiob = format(insulin.eiobUnits, locale),
                cob = WearJournalIob.cobGrams(journal, atMillis)?.let { format(it, locale) },
            )
        }

        /** The phone's rule: hidden below 0.05, no decimal on a whole number, else one decimal. */
        fun format(value: Float, locale: Locale): String? {
            if (!value.isFinite() || value < MIN_SHOWN) return null
            return if (value % 1f < MIN_SHOWN) {
                Math.round(value).toString()
            } else {
                String.format(locale, "%.1f", value)
            }
        }
    }
}
