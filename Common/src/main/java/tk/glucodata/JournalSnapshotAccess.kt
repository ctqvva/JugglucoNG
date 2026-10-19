package tk.glucodata

/**
 * Registration seam for [JournalSnapshotBridge] (plan P1/Q1).
 *
 * This used to be five name-based lookups of
 * `tk.glucodata.OutboundApiJournalSnapshot` in `OutboundApi`,
 * `ApiGlucoseSourceManager`, `JournalIobAccess` and `HistorySyncAccess`. R8
 * renames those members in release builds; the keep rule listed most of them but
 * not `nightscoutUploadIobSnapshot`, so that one was already silently returning
 * null there. Explicit registration leaves ordinary interface calls behind.
 *
 * Every method keeps the old reflective failure contract: a failure inside the
 * mobile implementation degrades to the default instead of reaching the shared
 * caller.
 */
object JournalSnapshotAccess {
    private const val TAG = "JournalSnapshotAccess"

    @Volatile
    private var bridge: JournalSnapshotBridge? = null

    /** Registered at startup, before any template or broadcast can need it. */
    @JvmStatic
    fun register(bridge: JournalSnapshotBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    /** Empty when there is no journal to serve, or the snapshot failed. */
    @JvmStatic
    fun snapshotJson(timeMillis: Long): String =
        runCatching { bridge?.snapshotJson(timeMillis) }
            .onFailure { Log.stack(TAG, "snapshotJson failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun importFromJsonForSource(raw: String, sourcePrefix: String): Int =
        runCatching { bridge?.importFromJsonForSource(raw, sourcePrefix) }
            .onFailure { Log.stack(TAG, "importFromJsonForSource failed", it) }
            .getOrNull() ?: 0

    @JvmStatic
    fun broadcastIobSnapshot(timeMillis: Long): FloatArray? =
        runCatching { bridge?.broadcastIobSnapshot(timeMillis) }
            .onFailure { Log.stack(TAG, "broadcastIobSnapshot failed", it) }
            .getOrNull()

    @JvmStatic
    fun invalidateBroadcastIobCache() =
        runCatching { bridge?.invalidateBroadcastIobCache() }
            .onFailure { Log.stack(TAG, "invalidateBroadcastIobCache failed", it) }

    @JvmStatic
    fun nightscoutUploadIobSnapshot(timeMillis: Long): FloatArray? =
        runCatching { bridge?.nightscoutUploadIobSnapshot(timeMillis) }
            .onFailure { Log.stack(TAG, "nightscoutUploadIobSnapshot failed", it) }
            .getOrNull()

    @JvmStatic
    fun cloneIobSnapshotJson(timeMillis: Long): String =
        runCatching { bridge?.cloneIobSnapshotJson(timeMillis) }
            .onFailure { Log.stack(TAG, "cloneIobSnapshotJson failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun importCloneIobSnapshot(raw: String): Boolean =
        runCatching { bridge?.importCloneIobSnapshot(raw) }
            .onFailure { Log.stack(TAG, "importCloneIobSnapshot failed", it) }
            .getOrNull() ?: false

    @JvmStatic
    fun cloneJournalSnapshotJson(timeMillis: Long): String =
        runCatching { bridge?.cloneJournalSnapshotJson(timeMillis) }
            .onFailure { Log.stack(TAG, "cloneJournalSnapshotJson failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun importCloneJournalSnapshot(raw: String, transportCode: Int): Boolean =
        runCatching { bridge?.importCloneJournalSnapshot(raw, transportCode) }
            .onFailure { Log.stack(TAG, "importCloneJournalSnapshot failed", it) }
            .getOrNull() ?: false
}
