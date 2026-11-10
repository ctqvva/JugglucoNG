package tk.glucodata

/**
 * The mobile-only Clone history-recovery receiver coordinator, as the shared
 * code needs it (plan P1/Q1).
 *
 * Registered from the mobile `Specific.registerBridges`; the watch registers
 * nothing and [CloneRecoveryAccessBridge] then returns the same empty results the
 * old absent class produced (P2).
 */
interface CloneRecoveryBridge {
    fun capabilitiesJson(): String

    fun preparePullExport(requestJson: String): Boolean

    fun readPullFile(jobId: String, packageChunk: Boolean, offset: Long, maximumBytes: Int): ByteArray?

    fun prepareIncomingPush(manifestJson: String): String

    fun writeIncomingChunk(jobId: String, offset: Long, bytes: ByteArray): String

    fun statusJson(jobId: String): String

    fun cancelIncoming(cancelJson: String): String

    fun commitIncomingAsync(commitJson: String, transportCode: Int): Boolean
}
