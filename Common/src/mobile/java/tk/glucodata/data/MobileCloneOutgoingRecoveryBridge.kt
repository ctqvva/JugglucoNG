package tk.glucodata.data

import tk.glucodata.CloneOutgoingRecoveryBridge

/**
 * Phone implementation of [CloneOutgoingRecoveryBridge] (plan P1/Q1). Delegates
 * to the mobile-only [CloneOutgoingRecoveryAccess].
 */
object MobileCloneOutgoingRecoveryBridge : CloneOutgoingRecoveryBridge {
    override fun probeOutgoing(iceLabel: String, connectionGeneration: Long): String =
        CloneOutgoingRecoveryAccess.probeOutgoing(iceLabel, connectionGeneration)

    override fun startOutgoingPush(
        iceLabel: String,
        connectionGeneration: Long,
        modeWire: String,
        includeJournal: Boolean,
        recoverFromReceiver: Boolean,
    ): String = CloneOutgoingRecoveryAccess.startOutgoingPush(
        iceLabel,
        connectionGeneration,
        modeWire,
        includeJournal,
        recoverFromReceiver,
    )

    override fun nextOutgoingAction(iceLabel: String, connectionGeneration: Long): ByteArray =
        CloneOutgoingRecoveryAccess.nextOutgoingAction(iceLabel, connectionGeneration)

    override fun reportOutgoingResult(
        iceLabel: String,
        connectionGeneration: Long,
        result: ByteArray,
    ): Int = CloneOutgoingRecoveryAccess.reportOutgoingResult(iceLabel, connectionGeneration, result)

    override fun outgoingStatusJson(iceLabel: String): String =
        CloneOutgoingRecoveryAccess.outgoingStatusJson(iceLabel)

    override fun cancelOutgoing(iceLabel: String): String = CloneOutgoingRecoveryAccess.cancelOutgoing(iceLabel)

    override fun resumeOutgoing(iceLabel: String, connectionGeneration: Long): Int =
        CloneOutgoingRecoveryAccess.resumeOutgoing(iceLabel, connectionGeneration)

    override fun outgoingLabelsJson(): String = CloneOutgoingRecoveryAccess.outgoingLabelsJson()

    override fun clearOutgoing(iceLabel: String): Boolean = CloneOutgoingRecoveryAccess.clearOutgoing(iceLabel)
}
