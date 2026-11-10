package tk.glucodata

/**
 * The phone's journal, as the shared code needs it (plan P1/Q1).
 *
 * The journal's Room layer only exists in the mobile source set, so the phone
 * registers its implementation ([tk.glucodata.data.journal.WearJournalBridge])
 * from [Specific.registerBridges]. The watch registers nothing, and the shared
 * caller sees the absence as null/false (P2).
 *
 * The implementation does its own encoding, so the surface is byte arrays rather
 * than journal types.
 */
interface JournalBridge {
    /** Encoded journal payload, or null when there is no journal to serve. */
    fun serveEntries(fromMs: Long): ByteArray?

    /** Applies an add or delete the watch sent. @return whether it was applied. */
    fun applyCommand(data: ByteArray): Boolean

    fun isJournalEnabled(): Boolean
}
