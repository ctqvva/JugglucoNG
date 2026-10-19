package tk.glucodata

/**
 * The mobile-only LibreView journal entries, as the shared/JNI bridge needs them
 * (plan P1/Q1).
 *
 * Registered from the mobile `Specific.registerBridges`; the watch registers
 * nothing and [LibreviewJournalEntriesAccess] then contributes nothing, which is
 * what the old absent class did.
 *
 * [tk.glucodata.LibreviewJournal] itself stays a JNI contract (its statics are
 * found by name from `journalentries.cpp`); it delegates here instead of
 * reflecting.
 */
interface LibreviewJournalEntriesBridge {
    /** Returns the rendered size in bytes, or 0 when there is nothing to send. */
    fun prepare(libre3: Boolean): Int

    fun foodEntries(): String

    fun insulinEntries(): String

    fun noteEntries(): String

    fun commit()

    fun discard()
}
