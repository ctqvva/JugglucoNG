package tk.glucodata.NovoPen

import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import kotlin.math.abs
import kotlin.math.roundToInt

/** An insulin dose somebody wrote down themselves, before the pen was read. */
data class ManualInsulinEntry(
    val id: Long,
    val timestampSeconds: Long,
    val units: Float,
    /** The insulin chosen for it; the editor cannot save an insulin entry without one. */
    val insulinPresetId: Long?,
)

/** One entry the pen has now confirmed: it keeps its row and takes the dose's name and time. */
data class PenManualMerge(
    val entryId: Long,
    val sourceRecordId: String,
    val timestampSeconds: Long,
    /** The dose it stands for, so the caller can drop it from what it offers. */
    val doseRelativeSeconds: Long,
    /** When that entry was written, so a sheet can say which one it would replace. */
    val entryTimestampSeconds: Long,
    /** What it said at the time, so the amount can decide again when this is carried out. */
    val entryUnits: Float,
)

/**
 * @param merges what to adopt, in the order the doses were injected
 * @param alignmentBreak where the sequence stopped lining up, for the log; null when it held
 */
data class PenManualMergePlan(
    val merges: List<PenManualMerge>,
    val alignmentBreak: AlignmentBreak? = null,
) {
    val isEmpty: Boolean get() = merges.isEmpty()

    /** Position in the walk, and the two amounts that disagreed there. */
    data class AlignmentBreak(
        val position: Int,
        val doseUnits: Float,
        val entryUnits: Float,
        val secondsApart: Long,
    )

    companion object {
        val EMPTY = PenManualMergePlan(emptyList(), null)
    }
}

/**
 * Matches a pen's doses against insulin entries the reader wrote by hand.
 *
 * Deciding to correct while looking at the chart, writing the dose down, and injecting a
 * minute later without the app is one injection with two records: the entry, and later the
 * dose the scan reads off the pen. [PenDuplicateReconciler] already merges the equivalent
 * case for earlier pen imports, but only for entries that carry a `pen:` id, so a hand-written
 * one falls through and the journal ends up holding the injection twice, with twice its IOB.
 *
 * Because only minutes pass between writing a dose down and giving it, the two sequences line
 * up: the n-th dose the pen reports is the n-th entry in the journal. That is steadier than
 * hunting for the nearest entry in time, and it fails differently — one wrong pairing shifts
 * every pairing after it, silently, onto wrong times and amounts. So the order proposes the
 * pairing and **the amount decides it**: at the first pair whose amounts differ, or that sits
 * further apart than [MATCH_WINDOW_SECONDS], the alignment counts as lost. The walk stops
 * there, the rest is matched pairwise instead — nearest in time, same amount, each side
 * claimed once, the way [PenDuplicateReconciler] matches — and whatever stays unmatched is
 * offered as a new dose, so a person decides it rather than this.
 *
 * Priming shots are left out before the walk: they exist in the pen's log and never in the
 * journal, so counting them would shift the sequence by one from the start.
 */
object PenManualMergePlanner {

    /**
     * How far a hand-written entry may sit from the dose it stands for.
     *
     * Generous on purpose: the sequence decides the pairing, and this only asks whether the
     * result is plausible. Fifteen minutes covers writing a dose down, walking to the pen and
     * injecting, and stays far short of the gap between two separate corrections.
     */
    const val MATCH_WINDOW_SECONDS = 15L * 60L

    /**
     * @param penInsulinPresetId the insulin this pen is known to hold. A pen that has none
     *   recorded yet merges nothing: bolus and basal cannot be told apart without it, and a
     *   pen is only without one on its first read, when the reader is at the review sheet
     *   anyway and can untick what they already wrote down.
     */
    /**
     * The entries a merge may consider: insulin somebody wrote by hand, with an amount to
     * check against. Anything an importer wrote is left out, the pen's own rows included,
     * so a dose already imported can never be read as a hand-written record of itself.
     */
    fun candidates(entries: List<JournalEntry>): List<ManualInsulinEntry> = entries.mapNotNull { entry ->
        if (entry.source != JournalEntrySource.MANUAL || entry.type != JournalEntryType.INSULIN) {
            return@mapNotNull null
        }
        val units = entry.amount ?: return@mapNotNull null
        ManualInsulinEntry(entry.id, entry.timestamp / 1000L, units, entry.insulinPresetId)
    }

    fun plan(
        serial: String,
        doses: List<PenDose>,
        entries: List<ManualInsulinEntry>,
        penInsulinPresetId: Long?,
    ): PenManualMergePlan {
        val insulin = penInsulinPresetId?.takeIf { it > 0L } ?: return PenManualMergePlan.EMPTY
        val injections = doses.filterNot(PenDose::priming).sortedBy(PenDose::timestampSeconds)
        if (injections.isEmpty() || entries.isEmpty()) return PenManualMergePlan.EMPTY

        // Only the stretch the doses cover, and only this pen's insulin: a second pen of
        // another kind has a sequence of its own and must not join this one.
        val from = injections.first().timestampSeconds - MATCH_WINDOW_SECONDS
        val to = injections.last().timestampSeconds + MATCH_WINDOW_SECONDS
        val candidates = entries
            .filter { it.timestampSeconds in from..to && it.insulinPresetId == insulin }
            .sortedWith(compareBy(ManualInsulinEntry::timestampSeconds, ManualInsulinEntry::id))
        if (candidates.isEmpty()) return PenManualMergePlan.EMPTY

        val merges = ArrayList<PenManualMerge>()
        var breakAt: PenManualMergePlan.AlignmentBreak? = null
        var walked = 0
        while (walked < injections.size && walked < candidates.size) {
            val dose = injections[walked]
            val entry = candidates[walked]
            val apart = abs(entry.timestampSeconds - dose.timestampSeconds)
            if (tenths(entry.units) != tenths(dose.units) || apart > MATCH_WINDOW_SECONDS) {
                breakAt = PenManualMergePlan.AlignmentBreak(walked, dose.units, entry.units, apart)
                break
            }
            merges.add(merge(serial, dose, entry))
            walked++
        }

        if (breakAt != null) {
            merges.addAll(
                pairwise(serial, injections.drop(walked), candidates.drop(walked)),
            )
        }
        return PenManualMergePlan(merges, breakAt)
    }

    /**
     * What is left once the order can no longer be trusted: the same one-to-one, closest
     * first, same-amount matching [PenDuplicateReconciler] uses. Anything it cannot pair
     * confidently is left alone and offered as a new dose.
     */
    private fun pairwise(
        serial: String,
        doses: List<PenDose>,
        entries: List<ManualInsulinEntry>,
    ): List<PenManualMerge> {
        if (doses.isEmpty() || entries.isEmpty()) return emptyList()
        val pairs = doses.flatMap { dose ->
            entries.mapNotNull { entry ->
                val apart = abs(entry.timestampSeconds - dose.timestampSeconds)
                if (apart <= MATCH_WINDOW_SECONDS && tenths(entry.units) == tenths(dose.units)) {
                    Triple(apart, dose, entry)
                } else {
                    null
                }
            }
        }.sortedWith(compareBy({ it.first }, { it.third.id }))

        val claimedDoses = HashSet<Long>()
        val claimedEntries = HashSet<Long>()
        val merges = ArrayList<PenManualMerge>()
        pairs.forEach { (_, dose, entry) ->
            if (dose.relativeSeconds in claimedDoses || entry.id in claimedEntries) return@forEach
            claimedDoses.add(dose.relativeSeconds)
            claimedEntries.add(entry.id)
            merges.add(merge(serial, dose, entry))
        }
        return merges.sortedBy(PenManualMerge::timestampSeconds)
    }

    private fun merge(serial: String, dose: PenDose, entry: ManualInsulinEntry) = PenManualMerge(
        entryId = entry.id,
        sourceRecordId = PenSourceIds.stable(serial, dose),
        timestampSeconds = dose.timestampSeconds,
        doseRelativeSeconds = dose.relativeSeconds,
        entryTimestampSeconds = entry.timestampSeconds,
        entryUnits = entry.units,
    )

    /** Doses come off the pen in tenths of a unit; compare them there, not as floats. */
    private fun tenths(units: Float): Int = (units * 10f).roundToInt()
}
