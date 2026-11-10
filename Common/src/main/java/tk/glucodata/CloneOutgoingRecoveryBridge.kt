package tk.glucodata

/**
 * The mobile-only Clone outgoing-recovery coordinator, as the shared code needs
 * it (plan P1/Q1).
 *
 * Registered from the mobile `Specific.registerBridges`; the watch registers
 * nothing (P2).
 */
interface CloneOutgoingRecoveryBridge {
    fun probeOutgoing(iceLabel: String, connectionGeneration: Long): String

    fun startOutgoingPush(
        iceLabel: String,
        connectionGeneration: Long,
        modeWire: String,
        includeJournal: Boolean,
        recoverFromReceiver: Boolean,
    ): String

    fun nextOutgoingAction(iceLabel: String, connectionGeneration: Long): ByteArray

    fun reportOutgoingResult(iceLabel: String, connectionGeneration: Long, result: ByteArray): Int

    fun outgoingStatusJson(iceLabel: String): String

    fun cancelOutgoing(iceLabel: String): String

    fun resumeOutgoing(iceLabel: String, connectionGeneration: Long): Int

    fun outgoingLabelsJson(): String

    fun clearOutgoing(iceLabel: String): Boolean
}
