package tk.glucodata

/**
 * The mobile-only journal/IOB snapshot provider, as the shared code needs it
 * (plan P1/Q1).
 *
 * The journal's Room layer only exists in the mobile source set, so the phone
 * registers its implementation ([tk.glucodata.OutboundApiJournalSnapshot]) from
 * [Specific.registerBridges]. The watch registers nothing and the shared callers
 * see safe defaults (P2).
 */
interface JournalSnapshotBridge {
    /** The {iob}/{cob}/{journal}/{journal_events} template snapshot, as JSON. */
    fun snapshotJson(timeMillis: Long): String

    /** Ingest an API journal payload under [sourcePrefix]. @return rows imported. */
    fun importFromJsonForSource(raw: String, sourcePrefix: String): Int

    /** `[classicIob, eiob, cob, iobNext30, cobNext30]`, or null when unavailable. */
    fun broadcastIobSnapshot(timeMillis: Long): FloatArray?

    /**
     * Drop the cached broadcast IOB snapshot so the next read recomputes.
     * Follower sources call this when their remote state changes or is cleared;
     * otherwise a stale cache keeps serving the previous remote values.
     */
    fun invalidateBroadcastIobCache()

    /** Local journal-only IOB/COB for upload; never echoes Clone or follower state. */
    fun nightscoutUploadIobSnapshot(timeMillis: Long): FloatArray?

    fun cloneIobSnapshotJson(timeMillis: Long): String

    fun importCloneIobSnapshot(raw: String): Boolean

    fun cloneJournalSnapshotJson(timeMillis: Long): String

    fun importCloneJournalSnapshot(raw: String, transportCode: Int): Boolean
}
