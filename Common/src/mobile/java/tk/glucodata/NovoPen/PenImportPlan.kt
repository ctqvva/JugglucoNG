package tk.glucodata.NovoPen

/**
 * What confirming a review sheet has to write: which doses take over an entry the reader
 * wrote by hand, and which are written as rows of their own.
 *
 * The two are exclusive, which is the whole point of keeping this apart from the writing:
 * a dose that lands on an entry must not also be inserted, or the duplicate the merge
 * exists to prevent is created by the merge itself.
 */
data class PenImportPlan(
    val adopt: List<PenManualMerge>,
    val insert: List<PenDose>,
) {
    companion object {
        val EMPTY = PenImportPlan(emptyList(), emptyList())

        /**
         * @param doses what the reader confirmed, air shots already gone
         * @param merges the proposals, by dose counter; only doses in [doses] are honoured
         * @param alreadyWritten source record ids the journal already holds, which are
         *   neither adopted nor inserted: an earlier scan wrote that dose.
         */
        fun of(
            doses: List<PenDose>,
            merges: Map<Long, PenManualMerge>,
            alreadyWritten: (PenDose) -> Boolean,
        ): PenImportPlan {
            if (doses.isEmpty()) return EMPTY
            val adopt = ArrayList<PenManualMerge>()
            val insert = ArrayList<PenDose>()
            val claimedEntries = HashSet<Long>()
            doses.forEach { dose ->
                if (alreadyWritten(dose)) return@forEach
                val merge = merges[dose.relativeSeconds]
                // One entry cannot stand for two doses, whatever a stale proposal says.
                if (merge != null && claimedEntries.add(merge.entryId)) {
                    adopt.add(merge)
                } else {
                    insert.add(dose)
                }
            }
            return PenImportPlan(adopt, insert)
        }
    }
}
