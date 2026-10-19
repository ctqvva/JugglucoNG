package tk.glucodata.drivers.mq

internal object MQCloudRecovery {
    fun history(
        rangeLookup: () -> MQCloudHistoryResult,
        snapshotLookup: () -> MQCloudHistoryResult,
    ): MQCloudHistoryResult {
        val range = rangeLookup()
        if (range.history.isNotEmpty() || range.failure == MQBootstrapFailure.AUTH_EXPIRED) return range
        val snapshot = snapshotLookup()
        return when {
            snapshot.history.isNotEmpty() || snapshot.failure != MQBootstrapFailure.NONE -> snapshot
            else -> range
        }
    }

    // Endpoint-specific response observed from goOn, not a general HTTP/app-code success rule.
    fun isAlreadyMonitoring(code: Int?, success: Boolean, message: String?): Boolean =
        code == 302 && success && message?.trim() == "手机号在监测周期中"
}
