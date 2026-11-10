package tk.glucodata

import tk.glucodata.Log

/**
 * Registration seam for [CloneOutgoingRecoveryBridge] (plan P1/Q1).
 *
 * Shared/main Clone exchange code used to find
 * `tk.glucodata.data.CloneOutgoingRecoveryAccess` by name. Explicit registration
 * leaves ordinary interface calls behind. Every method keeps the old failure
 * contract: a failure degrades to the empty default.
 */
object CloneOutgoingRecoveryAccessBridge {
    private const val TAG = "CloneOutgoingRecoveryAccessBridge"

    @Volatile
    private var bridge: CloneOutgoingRecoveryBridge? = null

    @JvmStatic
    fun register(bridge: CloneOutgoingRecoveryBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    @JvmStatic
    fun probeOutgoing(iceLabel: String, connectionGeneration: Long): String =
        runCatching { bridge?.probeOutgoing(iceLabel, connectionGeneration) }
            .onFailure { Log.stack(TAG, "probeOutgoing failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun startOutgoingPush(
        iceLabel: String,
        connectionGeneration: Long,
        modeWire: String,
        includeJournal: Boolean,
        recoverFromReceiver: Boolean,
    ): String =
        runCatching {
            bridge?.startOutgoingPush(iceLabel, connectionGeneration, modeWire, includeJournal, recoverFromReceiver)
        }.onFailure { Log.stack(TAG, "startOutgoingPush failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun nextOutgoingAction(iceLabel: String, connectionGeneration: Long): ByteArray =
        runCatching { bridge?.nextOutgoingAction(iceLabel, connectionGeneration) }
            .onFailure { Log.stack(TAG, "nextOutgoingAction failed", it) }
            .getOrNull() ?: ByteArray(0)

    @JvmStatic
    fun reportOutgoingResult(iceLabel: String, connectionGeneration: Long, result: ByteArray): Int =
        runCatching { bridge?.reportOutgoingResult(iceLabel, connectionGeneration, result) }
            .onFailure { Log.stack(TAG, "reportOutgoingResult failed", it) }
            .getOrNull() ?: 0

    @JvmStatic
    fun outgoingStatusJson(iceLabel: String): String =
        runCatching { bridge?.outgoingStatusJson(iceLabel) }
            .onFailure { Log.stack(TAG, "outgoingStatusJson failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun cancelOutgoing(iceLabel: String): String =
        runCatching { bridge?.cancelOutgoing(iceLabel) }
            .onFailure { Log.stack(TAG, "cancelOutgoing failed", it) }
            .getOrNull() ?: ""

    @JvmStatic
    fun resumeOutgoing(iceLabel: String, connectionGeneration: Long): Int =
        runCatching { bridge?.resumeOutgoing(iceLabel, connectionGeneration) }
            .onFailure { Log.stack(TAG, "resumeOutgoing failed", it) }
            .getOrNull() ?: -1

    @JvmStatic
    fun outgoingLabelsJson(): String =
        runCatching { bridge?.outgoingLabelsJson() }
            .onFailure { Log.stack(TAG, "outgoingLabelsJson failed", it) }
            .getOrNull() ?: "[]"

    @JvmStatic
    fun clearOutgoing(iceLabel: String): Boolean =
        runCatching { bridge?.clearOutgoing(iceLabel) }
            .onFailure { Log.stack(TAG, "clearOutgoing failed", it) }
            .getOrNull() ?: false
}
