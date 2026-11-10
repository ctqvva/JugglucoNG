package tk.glucodata.data

import tk.glucodata.CloneRecoveryBridge

/**
 * Phone implementation of [CloneRecoveryBridge] (plan P1/Q1). Delegates to the
 * mobile-only [CloneHistoryRecoveryAccess].
 */
object MobileCloneRecoveryBridge : CloneRecoveryBridge {
    override fun capabilitiesJson(): String = CloneHistoryRecoveryAccess.capabilitiesJson()

    override fun preparePullExport(requestJson: String): Boolean =
        CloneHistoryRecoveryAccess.preparePullExport(requestJson)

    override fun readPullFile(
        jobId: String,
        packageChunk: Boolean,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray? = CloneHistoryRecoveryAccess.readPullFile(jobId, packageChunk, offset, maximumBytes)

    override fun prepareIncomingPush(manifestJson: String): String =
        CloneHistoryRecoveryAccess.prepareIncomingPush(manifestJson)

    override fun writeIncomingChunk(jobId: String, offset: Long, bytes: ByteArray): String =
        CloneHistoryRecoveryAccess.writeIncomingChunk(jobId, offset, bytes)

    override fun statusJson(jobId: String): String = CloneHistoryRecoveryAccess.statusJson(jobId)

    override fun cancelIncoming(cancelJson: String): String =
        CloneHistoryRecoveryAccess.cancelIncoming(cancelJson)

    override fun commitIncomingAsync(commitJson: String, transportCode: Int): Boolean =
        CloneHistoryRecoveryAccess.commitIncomingAsync(commitJson, transportCode)
}
