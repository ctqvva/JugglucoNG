package tk.glucodata.NovoPen

/**
 * Where the "stop reading here" mark may move after a scan that had nothing to offer.
 *
 * The cursor tells the next scan to stop once it reaches a dose at or below it, so every
 * dose behind the cursor is one the app will never look at again unless a full read is
 * asked for. That makes moving it past a dose nobody imported the one mistake a scan cannot
 * take back: a dose that slipped through once — anchored wrongly, dropped by the window,
 * never checked against the journal — would be skipped on every scan after.
 */
object PenImportCursor {

    /**
     * The newest dose the scan saw in the journal, or null when it vouched for none.
     *
     * Only doses whose record id was looked up and found count. Doses the scan never
     * checked — outside the review window, or missing from the read — are not vouched for,
     * so a newer one of those keeps the cursor below it, and the next scan sees it again.
     */
    fun provenUpTo(doses: List<PenDose>, inJournal: (PenDose) -> Boolean): Long? =
        doses.filter(inJournal).maxOfOrNull(PenDose::relativeSeconds)

    /**
     * Whether a dose is still up for review: strictly past the cursor, or any dose at all
     * when a full read was asked for.
     *
     * The cursor is where the last import ended. A dose the reader left unticked then is
     * at or below it, and stays declined rather than being offered again on every tap; a
     * pen never imported on this build has a cursor of 0, so everything it holds is ahead.
     */
    fun isAhead(dose: PenDose, cursor: Long, fullRead: Boolean): Boolean =
        fullRead || dose.relativeSeconds > cursor
}
