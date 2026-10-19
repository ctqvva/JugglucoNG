package tk.glucodata.drivers.mq

internal object MQSessionRestorePolicy {
    fun canRestore(
        requestedBleId: String?,
        snapshotBleId: String?,
        startAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        val requested = MQVendorIdentity.bleId(requestedBleId).orEmpty()
        val actual = MQVendorIdentity.bleId(snapshotBleId).orEmpty()
        if (requested.isBlank() || requested != actual) return false
        val start = startAtMs ?: return false
        val lifetimeMs = MQConstants.DEFAULT_RATED_LIFETIME_DAYS * 24L * 60L * 60L * 1000L
        return start > 0L && start <= nowMs && nowMs - start < lifetimeMs
    }

    fun withoutSession(config: MQBootstrapConfig): MQBootstrapConfig = config.copy(
        snapshotId = null,
        sensorStartAtMs = null,
        restoredPacketIndex = null,
        restoredLastProcessed = null,
        restoredKValue = null,
        restoredBValue = null,
    )
}
