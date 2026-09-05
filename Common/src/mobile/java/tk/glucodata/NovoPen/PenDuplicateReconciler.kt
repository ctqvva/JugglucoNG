package tk.glucodata.NovoPen

import kotlin.math.abs
import kotlin.math.roundToInt

/** How a pen dose is named in the journal, so a rescan recognises what it already wrote. */
object PenSourceIds {

    /**
     * Keyed on the pen's own counter, which is the one number about a dose that survives
     * being read twice. Earlier builds keyed on the anchored epoch second instead; those
     * ids are the [isLegacy] ones and every rescan minted a fresh one for the same dose.
     */
    fun stable(serial: String, dose: PenDose): String = "pen:$serial:rel:${dose.relativeSeconds}"

    fun belongsTo(serial: String, sourceRecordId: String?): Boolean =
        sourceRecordId != null && sourceRecordId.startsWith("pen:$serial:")

    fun isLegacy(serial: String, sourceRecordId: String?): Boolean =
        belongsTo(serial, sourceRecordId) && !sourceRecordId!!.startsWith("pen:$serial:rel:")
}

/** The parts of a journal entry the reconciler reasons about. */
data class PenJournalEntry(
    val id: Long,
    val timestampSeconds: Long,
    val units: Float,
    val sourceRecordId: String,
)

/**
 * @param adopt entry id to the stable record id it should carry from now on
 * @param delete entries the pen's own log says were never separate doses
 */
data class PenReconcilePlan(
    val adopt: Map<Long, String>,
    val delete: List<Long>,
) {
    val isEmpty: Boolean get() = adopt.isEmpty() && delete.isEmpty()

    companion object {
        val EMPTY = PenReconcilePlan(emptyMap(), emptyList())
    }
}

/**
 * Reconciles what a pen just reported against what earlier scans of it wrote to the journal.
 *
 * Two things need repairing, both left behind by keying entries on the anchored epoch second.
 * Entries written by those builds cannot be recomputed into stable ids, because the anchor
 * they used is gone, so they have to be recognised by shape instead: same pen, same units, a
 * timestamp near the dose. And where a dose was imported more than once, each anchor shifted
 * the copy a little, so the journal holds several rows for one injection — which inflates
 * IOB, not just the list.
 *
 * The pen's log is treated as the truth about how many injections there were. Doses and old
 * entries are matched one to one, closest in time first; a matched entry is renamed rather
 * than rewritten, so an edited amount or note survives. What is left over inside the span the
 * scan covered, next to an entry of the same size, is the extra copy, and only that is
 * deleted. An entry standing on its own is never touched, so an amount the reader corrected
 * by hand cannot be mistaken for a duplicate of a dose it no longer matches.
 */
object PenDuplicateReconciler {

    /**
     * How far a legacy entry may sit from the dose it stands for.
     *
     * It covers the anchor jitter of a single read plus the drift between the phone clock
     * and the pen counter since the entry was written, and stays well short of how far
     * apart two real injections of the same size normally are.
     */
    const val MATCH_WINDOW_SECONDS = 300L

    fun plan(
        serial: String,
        doses: List<PenDose>,
        entries: List<PenJournalEntry>,
    ): PenReconcilePlan {
        if (doses.isEmpty()) return PenReconcilePlan.EMPTY
        val mine = entries.filter { PenSourceIds.belongsTo(serial, it.sourceRecordId) }
        val legacy = mine.filter { PenSourceIds.isLegacy(serial, it.sourceRecordId) }
        if (legacy.isEmpty()) return PenReconcilePlan.EMPTY

        // Only the stretch of time this read actually covered; anything outside it was not
        // reported on, so there is nothing to compare it against.
        val from = doses.minOf(PenDose::timestampSeconds) - MATCH_WINDOW_SECONDS
        val to = doses.maxOf(PenDose::timestampSeconds) + MATCH_WINDOW_SECONDS
        val candidates = legacy.filter { it.timestampSeconds in from..to }
        if (candidates.isEmpty()) return PenReconcilePlan.EMPTY

        val alreadyStable = mine.mapTo(HashSet()) { it.sourceRecordId }
        val pairs = doses.flatMap { dose ->
            candidates.mapNotNull { entry ->
                val distance = abs(entry.timestampSeconds - dose.timestampSeconds)
                if (distance <= MATCH_WINDOW_SECONDS && tenths(entry.units) == tenths(dose.units)) {
                    Triple(distance, dose, entry)
                } else {
                    null
                }
            }
        }.sortedWith(compareBy({ it.first }, { it.third.id }))

        val adopt = LinkedHashMap<Long, String>()
        val delete = ArrayList<Long>()
        val claimedDoses = HashSet<Long>()
        val claimedEntries = HashSet<Long>()
        pairs.forEach { (_, dose, entry) ->
            if (!claimedDoses.add(dose.relativeSeconds)) return@forEach
            if (!claimedEntries.add(entry.id)) {
                claimedDoses.remove(dose.relativeSeconds)
                return@forEach
            }
            val stable = PenSourceIds.stable(serial, dose)
            if (stable in alreadyStable) {
                // The dose is already in the journal under its stable id, so this row is
                // the copy an earlier scan added.
                delete.add(entry.id)
            } else {
                adopt[entry.id] = stable
                alreadyStable.add(stable)
            }
        }

        // Whatever no dose claimed is only a duplicate if the row it was copied from is
        // still there: same size, right beside it, and standing for a dose of its own.
        // A leftover on its own is a record the pen no longer explains, and guessing at
        // that would throw away therapy.
        val originals = mine.filter { it.id in adopt || !PenSourceIds.isLegacy(serial, it.sourceRecordId) }
        candidates.filterNot { it.id in claimedEntries }.forEach { leftover ->
            val original = originals.any { other ->
                other.id != leftover.id &&
                    tenths(other.units) == tenths(leftover.units) &&
                    abs(other.timestampSeconds - leftover.timestampSeconds) <= MATCH_WINDOW_SECONDS
            }
            if (original) delete.add(leftover.id)
        }

        return PenReconcilePlan(adopt, delete.distinct())
    }

    /** Doses come off the pen in tenths of a unit; compare them there, not as floats. */
    private fun tenths(units: Float): Int = (units * 10f).roundToInt()
}
