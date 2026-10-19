package tk.glucodata.NovoPen

/**
 * What the review sheet offers, and what it starts out with ticked.
 *
 * One rule, asked once, so the heading, the list and the button cannot disagree about how
 * many doses a scan found. They did: the heading counted every dose the read returned, air
 * shots included, while the button counted what was actually going to be written, so a scan
 * that read nothing but an air shot opened a sheet saying "1 new dose" with a disabled
 * "add 0 doses" underneath it and no way out but dismissing.
 */
object PenSheetOffer {

    /**
     * An air shot is not therapy and is never written, so it is not something to offer or
     * count. It is still reported, as a line under the list, rather than quietly dropped.
     */
    fun offerable(doses: List<PenDose>): List<PenDose> = doses.filterNot(PenDose::priming)

    fun skipped(doses: List<PenDose>): Int = doses.count(PenDose::priming)

    /**
     * What starts out ticked: a dose that stands for an entry the reader already wrote by
     * hand always does, since leaving it unticked would keep the duplicate it was found to
     * be. Otherwise the pen's own rule applies, which holds back the log of a pen nobody has
     * read before rather than offering weeks of it as new.
     */
    fun preselection(
        doses: List<PenDose>,
        mergeable: Set<Long>,
        preselectFromSeconds: Long,
    ): Set<Long> = offerable(doses)
        .filter { it.relativeSeconds in mergeable || it.timestampSeconds >= preselectFromSeconds }
        .mapTo(LinkedHashSet(), PenDose::relativeSeconds)
}
