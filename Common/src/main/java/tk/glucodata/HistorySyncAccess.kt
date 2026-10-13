package tk.glucodata

import android.util.Log
import androidx.annotation.Keep
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Shared-code bridge to the mobile-only history stack (plan P1/Q1).
 *
 * This object's own name and its public methods are a **JNI contract**:
 * `curve/javacurve.cpp` does `FindClass("tk/glucodata/HistorySyncAccess")` and
 * `GetStaticMethodID` for the Clone-exchange entries, so they must keep their
 * names (`@Keep` + the historical direct references). Inside, it no longer
 * resolves the mobile classes by name: the phone registers its implementations
 * from [Specific.registerBridges] and each method delegates to them.
 *
 * Every method keeps the old reflective failure contract: a failure inside a
 * mobile implementation degrades to the default instead of reaching the caller.
 */
@Keep
object HistorySyncAccess {
    private const val TAG = "HistorySyncAccess"
    private const val MAXIMUM_RECOVERY_CHUNK_BYTES = 256 * 1024
    private const val MAXIMUM_RECOVERY_CONTROL_BYTES = 256 * 1024
    private const val MAXIMUM_RECOVERY_ACTION_BYTES =
        20 + 256 + MAXIMUM_RECOVERY_CHUNK_BYTES
    private const val MAXIMUM_RECOVERY_RESULT_BYTES = 16 + 64 * 1024
    private const val MAXIMUM_RECOVERY_ICE_LABEL_BYTES = 256

    /** Called only by the native mirror receiver before it imports remote sensor files. */
    @JvmStatic
    fun markCloneSensor(serial: String?, transportCode: Int, connectionIdentity: String?): Boolean =
        CloneSensorRegistry.markCloneSensor(serial, transportCode, connectionIdentity)

    @JvmStatic
    fun reconcilePrimaryCloneSensor(serial: String?) {
        if (!CloneSensorRegistry.isReceptionEnabled()) return
        CloneSensorRegistry.reconcilePrimaryCloneSensor(serial)
    }

    // ---- Clone IOB / journal snapshots ----

    @JvmStatic
    fun exportCloneIobSnapshot(): String =
        JournalSnapshotAccess.cloneIobSnapshotJson(System.currentTimeMillis())

    @JvmStatic
    fun importCloneIobSnapshot(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return CloneSensorRegistry.whileReceptionEnabled {
            JournalSnapshotAccess.importCloneIobSnapshot(raw)
        } ?: false
    }

    @JvmStatic
    fun exportCloneJournalSnapshot(): String =
        JournalSnapshotAccess.cloneJournalSnapshotJson(System.currentTimeMillis())

    @JvmStatic
    fun importCloneJournalSnapshot(raw: String?, transportCode: Int): Boolean {
        if (raw.isNullOrBlank()) return false
        return CloneSensorRegistry.whileReceptionEnabled {
            JournalSnapshotAccess.importCloneJournalSnapshot(raw, transportCode)
        } ?: false
    }

    // ---- Clone recovery receiver ----

    @JvmStatic
    fun exportCloneRecoveryCapabilities(): ByteArray =
        CloneRecoveryAccessBridge.capabilitiesJson().toRecoveryBytes()

    @JvmStatic
    fun receiveCloneRecoveryRequest(raw: ByteArray?): Boolean {
        val json = decodeRecoveryControl(raw) ?: return false
        return CloneRecoveryAccessBridge.preparePullExport(json)
    }

    @JvmStatic
    fun readCloneRecoveryPullFile(
        jobId: String,
        packageChunk: Boolean,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray? =
        runCatching {
            CloneHistoryRecoveryProtocol.validateJobId(jobId)
            require(offset >= 0 && maximumBytes in 1..CloneOutgoingRecoveryProtocol.GET_PAGE_BYTES)
            CloneRecoveryAccessBridge.readPullFile(jobId, packageChunk, offset, maximumBytes)
        }.getOrNull()

    @JvmStatic
    fun receiveCloneRecoveryManifest(raw: ByteArray?): ByteArray {
        val json = decodeRecoveryControl(raw) ?: return ByteArray(0)
        return CloneRecoveryAccessBridge.prepareIncomingPush(json).toRecoveryBytes()
    }

    @JvmStatic
    fun receiveCloneRecoveryChunk(
        jobId: String?,
        offset: Long,
        raw: ByteArray?,
    ): ByteArray {
        if (jobId.isNullOrBlank() || offset < 0L || raw == null || raw.isEmpty() ||
            raw.size > CloneHistoryRecoveryProtocol.MAXIMUM_CHUNK_BYTES
        ) {
            return ByteArray(0)
        }
        return CloneRecoveryAccessBridge.writeIncomingChunk(jobId, offset, raw).toRecoveryBytes()
    }

    @JvmStatic
    fun exportCloneRecoveryStatus(jobId: String?): ByteArray {
        if (jobId.isNullOrBlank()) return ByteArray(0)
        return CloneRecoveryAccessBridge.statusJson(jobId).toRecoveryBytes()
    }

    @JvmStatic
    fun receiveCloneRecoveryCancel(raw: ByteArray?): ByteArray {
        val json = decodeRecoveryControl(raw) ?: return ByteArray(0)
        return CloneRecoveryAccessBridge.cancelIncoming(json).toRecoveryBytes()
    }

    @JvmStatic
    fun receiveCloneRecoveryCommit(raw: ByteArray?, transportCode: Int): Boolean {
        val json = decodeRecoveryControl(raw) ?: return false
        return CloneRecoveryAccessBridge.commitIncomingAsync(json, transportCode)
    }

    // ---- Clone recovery sender ----

    @JvmStatic
    fun probeCloneRecoveryOutgoing(iceLabel: String?, connectionGeneration: Long): ByteArray {
        if (!validCloneRecoveryOutgoingIdentity(iceLabel, connectionGeneration)) {
            return ByteArray(0)
        }
        return CloneOutgoingRecoveryAccessBridge.probeOutgoing(iceLabel!!, connectionGeneration)
            .toRecoveryBytes()
    }

    @JvmStatic
    fun startCloneRecoveryOutgoing(
        iceLabel: String?,
        connectionGeneration: Long,
        modeWire: String?,
        includeJournal: Boolean,
        recoverFromReceiver: Boolean,
    ): ByteArray {
        if (!validCloneRecoveryOutgoingIdentity(iceLabel, connectionGeneration) ||
            modeWire.isNullOrBlank() || modeWire.length > 64
        ) {
            return ByteArray(0)
        }
        return CloneOutgoingRecoveryAccessBridge.startOutgoingPush(
            iceLabel!!,
            connectionGeneration,
            modeWire,
            includeJournal,
            recoverFromReceiver,
        ).toRecoveryBytes()
    }

    @JvmStatic
    fun nextCloneRecoveryOutgoingAction(
        iceLabel: String?,
        connectionGeneration: Long,
    ): ByteArray {
        if (!validCloneRecoveryOutgoingIdentity(iceLabel, connectionGeneration)) {
            return ByteArray(0)
        }
        return CloneOutgoingRecoveryAccessBridge.nextOutgoingAction(iceLabel!!, connectionGeneration)
            .takeIf { it.size in 1..MAXIMUM_RECOVERY_ACTION_BYTES }
            ?: ByteArray(0)
    }

    @JvmStatic
    fun reportCloneRecoveryOutgoingResult(
        iceLabel: String?,
        connectionGeneration: Long,
        raw: ByteArray?,
    ): Int {
        if (!validCloneRecoveryOutgoingIdentity(iceLabel, connectionGeneration) ||
            raw == null || raw.size !in 1..MAXIMUM_RECOVERY_RESULT_BYTES
        ) {
            return 0
        }
        return CloneOutgoingRecoveryAccessBridge.reportOutgoingResult(iceLabel!!, connectionGeneration, raw)
            .takeIf { it == 0 || it == 1 } ?: 0
    }

    @JvmStatic
    fun cloneRecoveryOutgoingStatus(iceLabel: String?): ByteArray {
        if (!validCloneRecoveryOutgoingIdentity(iceLabel, 0L)) return ByteArray(0)
        return CloneOutgoingRecoveryAccessBridge.outgoingStatusJson(iceLabel!!).toRecoveryBytes()
    }

    @JvmStatic
    fun cancelCloneRecoveryOutgoing(iceLabel: String?): ByteArray {
        if (!validCloneRecoveryOutgoingIdentity(iceLabel, 0L)) return ByteArray(0)
        return CloneOutgoingRecoveryAccessBridge.cancelOutgoing(iceLabel!!).toRecoveryBytes()
    }

    @JvmStatic
    fun resumeCloneRecoveryOutgoing(
        iceLabel: String?,
        connectionGeneration: Long,
    ): Int {
        if (!validCloneRecoveryOutgoingIdentity(iceLabel, connectionGeneration)) return -1
        return CloneOutgoingRecoveryAccessBridge.resumeOutgoing(iceLabel!!, connectionGeneration)
            .takeIf { it == 0 || it == 1 } ?: -1
    }

    private fun validCloneRecoveryOutgoingIdentity(
        iceLabel: String?,
        connectionGeneration: Long,
    ): Boolean {
        if (iceLabel.isNullOrBlank() || connectionGeneration < 0L) return false
        val bytes = iceLabel.toByteArray(StandardCharsets.UTF_8)
        return bytes.size in 1..MAXIMUM_RECOVERY_ICE_LABEL_BYTES &&
            iceLabel.none(Char::isISOControl)
    }

    private fun decodeRecoveryControl(raw: ByteArray?): String? {
        if (raw == null || raw.isEmpty() || raw.size > MAXIMUM_RECOVERY_CONTROL_BYTES) {
            return null
        }
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        }.onFailure {
            Log.w(TAG, "Rejected malformed Clone recovery control record", it)
        }.getOrNull()
    }

    private fun String.toRecoveryBytes(): ByteArray {
        val bytes = toByteArray(StandardCharsets.UTF_8)
        return bytes.takeIf { it.size <= MAXIMUM_RECOVERY_CONTROL_BYTES } ?: ByteArray(0)
    }

    // ---- Native -> Room history sync ----

    @JvmStatic
    @JvmOverloads
    fun syncSensorFromNative(serial: String?, forceFull: Boolean = false) =
        HistorySyncBridgeAccess.syncSensorFromNative(serial, forceFull)

    @JvmStatic
    fun syncRecentSensorFromNative(serial: String?, anchorTimeMs: Long) =
        HistorySyncBridgeAccess.syncRecentSensorFromNative(serial, anchorTimeMs)

    @JvmStatic
    fun forceFullSyncForSensor(serial: String?) =
        HistorySyncBridgeAccess.forceFullSyncForSensor(serial)

    @JvmStatic
    fun mergeFullSyncForSensor(serial: String?) =
        HistorySyncBridgeAccess.mergeFullSyncForSensor(serial)

    /** Not JNI; used by the native mirror on the JNI thread — kept for its caller. */
    @JvmStatic
    fun markSensorReset(serial: String?) =
        HistorySyncBridgeAccess.markSensorReset(serial)

    @JvmStatic
    fun resetBackfillFlag() = HistoryRepositoryAccess.resetBackfillFlag()

    @JvmStatic
    fun storeAidexReadingAsync(timestamp: Long, valueMmol: Float) {
        HistoryRepositoryAccess.storeAidexReadingAsync(timestamp, valueMmol, 4)
    }

    @JvmStatic
    fun storeCurrentReadingAsync(
        timestamp: Long,
        valueMgdl: Float,
        rawValueMgdl: Float,
        rate: Float,
        sensorSerial: String?,
    ) {
        if (timestamp <= 0L || sensorSerial.isNullOrBlank()) return
        HistoryRepositoryAccess.storeCurrentReadingAsync(timestamp, valueMgdl, rawValueMgdl, rate, sensorSerial)
    }

    @JvmStatic
    fun storeCurrentReadingWithSourceAsync(
        timestamp: Long,
        valueMgdl: Float,
        rawValueMgdl: Float,
        rate: Float,
        sensorSerial: String?,
        source: String,
    ) {
        if (timestamp <= 0L || sensorSerial.isNullOrBlank()) return
        HistoryRepositoryAccess.storeCurrentReadingWithSourceAsync(
            timestamp,
            valueMgdl,
            rawValueMgdl,
            rate,
            sensorSerial,
            source,
        )
    }

    @JvmStatic
    fun storeSensorHistoryBatchAsync(
        sensorSerial: String?,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
    ): Boolean {
        if (sensorSerial.isNullOrBlank()) return false
        if (timestamps.isEmpty()) return true
        HistoryRepositoryAccess.storeHistoryBatchAsync(sensorSerial, timestamps, valuesMgdl, rawValuesMgdl)
        return true
    }

    @JvmStatic
    fun storeSensorHistoryBatchWithCompletionAsync(
        sensorSerial: String?,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
        completion: HistoryOperationCompletion,
    ): Boolean {
        if (sensorSerial.isNullOrBlank() || timestamps.isEmpty()) {
            val valid = timestamps.isEmpty() && !sensorSerial.isNullOrBlank()
            completion.complete(valid)
            return valid
        }
        HistoryRepositoryAccess.storeHistoryBatchWithCompletionAsync(
            sensorSerial,
            timestamps,
            valuesMgdl,
            rawValuesMgdl,
            completion,
        )
        return true
    }

    @JvmStatic
    fun storeSensorHistoryBatchBlocking(
        sensorSerial: String?,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
    ): Boolean {
        if (sensorSerial.isNullOrBlank()) return false
        if (timestamps.isEmpty()) return true
        return HistoryRepositoryAccess.storeHistoryBatchBlocking(sensorSerial, timestamps, valuesMgdl, rawValuesMgdl)
    }

    @JvmStatic
    fun storeSensorHistoryBatchWithSourceBlocking(
        sensorSerial: String?,
        timestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
        source: String,
    ): Boolean {
        if (sensorSerial.isNullOrBlank()) return false
        if (timestamps.isEmpty()) return true
        return HistoryRepositoryAccess.storeHistoryBatchWithSourceBlocking(
            sensorSerial,
            timestamps,
            valuesMgdl,
            rawValuesMgdl,
            source,
        )
    }

    @JvmStatic
    fun getLatestTimestampForSensor(sensorSerial: String?): Long {
        if (sensorSerial.isNullOrBlank()) return 0L
        return HistoryRepositoryAccess.getLatestTimestampForSensorBlocking(sensorSerial)
    }

    @JvmStatic
    fun getHistoryTimestampsForSensorOrNull(
        sensorSerial: String?,
        startTime: Long,
        endTime: Long,
    ): LongArray? {
        if (sensorSerial.isNullOrBlank() || endTime < startTime) return LongArray(0)
        return HistoryRepositoryAccess.getHistoryTimestampsForSensorOrNull(sensorSerial, startTime, endTime)
    }

    @JvmStatic
    fun getHistoryTimestampsForSensor(sensorSerial: String?, startTime: Long, endTime: Long): LongArray =
        getHistoryTimestampsForSensorOrNull(sensorSerial, startTime, endTime) ?: LongArray(0)

    @JvmStatic
    fun deleteReadingsForSensorAfter(sensorSerial: String?, timestampExclusive: Long): Int {
        if (sensorSerial.isNullOrBlank() || timestampExclusive <= 0L) return 0
        return HistoryRepositoryAccess.deleteReadingsForSensorAfterBlocking(sensorSerial, timestampExclusive)
    }

    @JvmStatic
    fun replaceProvisionalHistoryAsync(
        sensorSerial: String?,
        provisionalTimestamps: LongArray,
        correctedTimestamps: LongArray,
        valuesMgdl: FloatArray,
        rawValuesMgdl: FloatArray,
        completion: HistoryOperationCompletion,
    ): Boolean {
        if (sensorSerial.isNullOrBlank() || provisionalTimestamps.isEmpty()) {
            completion.complete(false)
            return false
        }
        HistoryRepositoryAccess.replaceProvisionalHistoryAsync(
            sensorSerial,
            provisionalTimestamps,
            correctedTimestamps,
            valuesMgdl,
            rawValuesMgdl,
            completion,
        )
        return true
    }
}
