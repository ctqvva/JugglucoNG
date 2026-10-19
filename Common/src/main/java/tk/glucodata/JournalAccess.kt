package tk.glucodata

/**
 * Bridge from src/main to the phone's journal, which lives in the mobile source
 * set only (plan P1/Q1).
 *
 * The phone registers its [JournalBridge] from [Specific.registerBridges]; the
 * watch registers nothing, and the shared caller sees the absence as
 * null/false (P2).
 *
 * This used to resolve `tk.glucodata.data.journal.WearJournalBridge` by name.
 * R8 renames those members in release builds, so the watch journal silently did
 * nothing there; explicit registration leaves ordinary interface calls behind.
 *
 * Every method keeps the old reflective failure contract: a failure inside the
 * mobile implementation degrades to the default instead of reaching the shared
 * caller.
 */
object JournalAccess {
    private const val TAG = "JournalAccess"

    @Volatile
    private var bridge: JournalBridge? = null

    /** Registered at startup, before the watch can ask for the journal. */
    @JvmStatic
    fun register(bridge: JournalBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    /** Encoded journal payload, or null when there is no journal to serve. */
    @JvmStatic
    fun serveEntries(fromMs: Long): ByteArray? =
        runCatching { bridge?.serveEntries(fromMs) }
            .onFailure { Log.stack(TAG, "serveEntries failed", it) }
            .getOrNull()

    @JvmStatic
    fun applyCommand(data: ByteArray): Boolean =
        runCatching { bridge?.applyCommand(data) }
            .onFailure { Log.stack(TAG, "applyCommand failed", it) }
            .getOrNull() ?: false

    @JvmStatic
    fun isJournalEnabled(): Boolean =
        runCatching { bridge?.isJournalEnabled() }
            .onFailure { Log.stack(TAG, "isJournalEnabled failed", it) }
            .getOrNull() ?: false
}
