package tk.glucodata

/**
 * Registration seam for [LibreviewJournalEntriesBridge] (plan P1/Q1).
 *
 * [LibreviewJournal] used to resolve
 * `tk.glucodata.data.journal.LibreviewJournalEntries` by name. The class lookup
 * survived R8 renaming, but the members did not; the keep rule was the only thing
 * holding them. Explicit registration leaves ordinary interface calls behind.
 *
 * Every method keeps the old reflective failure contract: a failure degrades to
 * the empty default.
 */
object LibreviewJournalEntriesAccess {
    private const val TAG = "LibreviewJournal"

    @Volatile
    private var bridge: LibreviewJournalEntriesBridge? = null

    @JvmStatic
    fun register(bridge: LibreviewJournalEntriesBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    @JvmStatic
    fun prepare(libre3: Boolean): Int =
        runCatching { bridge?.prepare(libre3) }
            .onFailure { Log.stack(TAG, "prepare failed", it) }
            .getOrNull() ?: 0

    @JvmStatic
    fun foodEntries(): String =
        runCatching { bridge?.foodEntries() }
            .onFailure { Log.stack(TAG, "foodEntries failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun insulinEntries(): String =
        runCatching { bridge?.insulinEntries() }
            .onFailure { Log.stack(TAG, "insulinEntries failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun noteEntries(): String =
        runCatching { bridge?.noteEntries() }
            .onFailure { Log.stack(TAG, "noteEntries failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun commit() {
        runCatching { bridge?.commit() }
            .onFailure { Log.stack(TAG, "commit failed", it) }
    }

    @JvmStatic
    fun discard() {
        runCatching { bridge?.discard() }
            .onFailure { Log.stack(TAG, "discard failed", it) }
    }
}
