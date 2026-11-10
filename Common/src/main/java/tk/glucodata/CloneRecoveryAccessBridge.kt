package tk.glucodata

import tk.glucodata.Log

/**
 * Registration seam for [CloneRecoveryBridge] (plan P1/Q1).
 *
 * Shared/main Clone exchange code used to find
 * `tk.glucodata.data.CloneHistoryRecoveryAccess` by name. Explicit registration
 * leaves ordinary interface calls behind. Every method keeps the old failure
 * contract: a failure degrades to the empty default.
 */
object CloneRecoveryAccessBridge {
    private const val TAG = "CloneRecoveryAccessBridge"

    @Volatile
    private var bridge: CloneRecoveryBridge? = null

    @JvmStatic
    fun register(bridge: CloneRecoveryBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    @JvmStatic
    fun capabilitiesJson(): String =
        runCatching { bridge?.capabilitiesJson() }
            .onFailure { Log.stack(TAG, "capabilitiesJson failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun preparePullExport(requestJson: String): Boolean =
        runCatching { bridge?.preparePullExport(requestJson) }
            .onFailure { Log.stack(TAG, "preparePullExport failed", it) }
            .getOrNull() ?: false

    @JvmStatic
    fun readPullFile(jobId: String, packageChunk: Boolean, offset: Long, maximumBytes: Int): ByteArray? =
        runCatching { bridge?.readPullFile(jobId, packageChunk, offset, maximumBytes) }
            .onFailure { Log.stack(TAG, "readPullFile failed", it) }
            .getOrNull()

    @JvmStatic
    fun prepareIncomingPush(manifestJson: String): String =
        runCatching { bridge?.prepareIncomingPush(manifestJson) }
            .onFailure { Log.stack(TAG, "prepareIncomingPush failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun writeIncomingChunk(jobId: String, offset: Long, bytes: ByteArray): String =
        runCatching { bridge?.writeIncomingChunk(jobId, offset, bytes) }
            .onFailure { Log.stack(TAG, "writeIncomingChunk failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun statusJson(jobId: String): String =
        runCatching { bridge?.statusJson(jobId) }
            .onFailure { Log.stack(TAG, "statusJson failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun cancelIncoming(cancelJson: String): String =
        runCatching { bridge?.cancelIncoming(cancelJson) }
            .onFailure { Log.stack(TAG, "cancelIncoming failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun commitIncomingAsync(commitJson: String, transportCode: Int): Boolean =
        runCatching { bridge?.commitIncomingAsync(commitJson, transportCode) }
            .onFailure { Log.stack(TAG, "commitIncomingAsync failed", it) }
            .getOrNull() ?: false
}
