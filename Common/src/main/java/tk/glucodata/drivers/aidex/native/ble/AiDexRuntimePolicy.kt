package tk.glucodata.drivers.aidex.native.ble

import android.bluetooth.BluetoothDevice

internal object AiDexRuntimePolicy {

    enum class InvalidSetupRecoveryAction {
        RECONNECT,
        REMOVE_BOND_AND_RECONNECT,
    }

    enum class MissingCccdCallbackAction {
        IGNORE,
        WAIT,
        ASSUME_COMPLETE,
    }

    fun connectedWarmupStatus(
        connectionPart: String,
        anchorMs: Long,
        nowMs: Long,
        lastGlucoseTimeMs: Long,
        warmupDurationMs: Long,
        firstValidReadingWaitMaxMs: Long,
        firstValidReadingWaitActive: Boolean,
    ): String? {
        if (anchorMs <= 0L || nowMs < anchorMs) return null

        val hasValidReadingSinceStart = lastGlucoseTimeMs >= anchorMs && lastGlucoseTimeMs > 0L
        if (hasValidReadingSinceStart) return null

        val ageMs = nowMs - anchorMs
        return when {
            ageMs < warmupDurationMs -> {
                val remaining = ((warmupDurationMs - ageMs) + 59_999L) / 60_000L
                "$connectionPart — Warmup ${remaining}m"
            }
            ageMs < firstValidReadingWaitMaxMs -> {
                val remaining = ((firstValidReadingWaitMaxMs - ageMs) + 59_999L) / 60_000L
                "$connectionPart — Warmup extended ${remaining}m"
            }
            firstValidReadingWaitActive -> "$connectionPart — No valid data yet"
            else -> null
        }
    }

    fun initialAssistDelayMs(
        nowMs: Long,
        phaseStreaming: Boolean,
        pendingInitialHistoryRequest: Boolean,
        historyDownloading: Boolean,
        streamingStartedAtMs: Long,
        initialHistoryRequestDelayMs: Long,
    ): Long? {
        if (!phaseStreaming || !pendingInitialHistoryRequest || historyDownloading || streamingStartedAtMs <= 0L) {
            return null
        }
        val elapsedMs = (nowMs - streamingStartedAtMs).coerceAtLeast(0L)
        val remainingMs = initialHistoryRequestDelayMs - elapsedMs
        return remainingMs.takeIf { it > 0L }
    }

    fun shouldContinueAssistScanning(
        stop: Boolean,
        broadcastOnlyMode: Boolean,
        phaseStreaming: Boolean,
        hasRecentLiveData: Boolean,
        pendingInitialHistoryRequest: Boolean,
        historyDownloading: Boolean,
        anchorMs: Long,
        nowMs: Long,
        lastGlucoseTimeMs: Long,
        firstValidReadingWaitMaxMs: Long,
    ): Boolean {
        if (stop || broadcastOnlyMode || !phaseStreaming || hasRecentLiveData) return false
        if (pendingInitialHistoryRequest && !historyDownloading) return false
        if (anchorMs <= 0L || nowMs < anchorMs) return false
        if (lastGlucoseTimeMs >= anchorMs && lastGlucoseTimeMs > 0L) return false
        return (nowMs - anchorMs) < firstValidReadingWaitMaxMs
    }

    fun shouldAcceptBroadcastFallback(
        broadcastOnlyMode: Boolean,
        waitingForFirstDirectLive: Boolean,
        hadRecentLiveDataBeforeBroadcast: Boolean,
    ): Boolean {
        return broadcastOnlyMode || waitingForFirstDirectLive || !hadRecentLiveDataBeforeBroadcast
    }

    fun shouldContinueBroadcastScanning(
        broadcastOnlyMode: Boolean,
        noDirectLiveBroadcastFallbackMode: Boolean,
    ): Boolean = broadcastOnlyMode || noDirectLiveBroadcastFallbackMode

    fun firstValidReadingWaitStatus(
        anchorMs: Long,
        nowMs: Long,
        warmupDurationMs: Long,
        firstValidReadingWaitMaxMs: Long,
    ): String? {
        if (anchorMs <= 0L || nowMs < anchorMs) return null
        val ageMs = nowMs - anchorMs
        val ageMin = ageMs / 60_000L
        return when {
            ageMs < warmupDurationMs -> {
                val remaining = ((warmupDurationMs - ageMs) + 59_999L) / 60_000L
                "age=${ageMin}m warmup=${remaining}m"
            }
            ageMs < firstValidReadingWaitMaxMs -> {
                val remaining = ((firstValidReadingWaitMaxMs - ageMs) + 59_999L) / 60_000L
                "age=${ageMin}m extended=${remaining}m"
            }
            else -> "age=${ageMin}m no-valid-data"
        }
    }

    fun shouldStartHistoryImmediately(
        pendingInitialHistoryRequest: Boolean,
        historyDownloading: Boolean,
    ): Boolean = pendingInitialHistoryRequest && !historyDownloading

    fun shouldRequestRoutineStreamingMetadata(
        startupMetadataComplete: Boolean,
        hasModelMetadata: Boolean,
        hasAuthoritativeSessionStart: Boolean,
    ): Boolean {
        if (startupMetadataComplete && hasModelMetadata && hasAuthoritativeSessionStart) return false
        return !hasModelMetadata || !hasAuthoritativeSessionStart
    }

    fun shouldRequestRoutineCalibrationRefresh(
        hasCachedCalibrationRecords: Boolean,
        calibrationDownloading: Boolean,
    ): Boolean = !hasCachedCalibrationRecords && !calibrationDownloading

    fun shouldRunOptionalStreamingSync(
        phase: AiDexBleManager.Phase,
        hasGatt: Boolean,
        historyDownloading: Boolean,
        pendingInitialHistoryRequest: Boolean,
        noDirectLiveBroadcastFallbackMode: Boolean,
        hasDirectLiveThisConnection: Boolean,
        hasRecentLiveData: Boolean,
    ): Boolean {
        if (phase != AiDexBleManager.Phase.STREAMING) return false
        if (!hasGatt) return false
        if (historyDownloading || pendingInitialHistoryRequest) return false
        if (noDirectLiveBroadcastFallbackMode) return false
        if (!hasDirectLiveThisConnection) return false
        return hasRecentLiveData
    }

    fun shouldRecoverFromSetupStall(
        phase: AiDexBleManager.Phase,
        phaseAgeMs: Long,
        bondState: Int,
        keyExchangePendingBond: Boolean,
        setupTimeoutMs: Long,
        bondingTimeoutMs: Long,
    ): Boolean {
        if (phase != AiDexBleManager.Phase.DISCOVERING_SERVICES && phase != AiDexBleManager.Phase.CCCD_CHAIN) {
            return false
        }
        val timeoutMs = if (bondState == BluetoothDevice.BOND_BONDING || keyExchangePendingBond) {
            bondingTimeoutMs
        } else {
            setupTimeoutMs
        }
        return phaseAgeMs >= timeoutMs
    }

    fun shouldRecoverFromConnectAttemptStall(
        phase: AiDexBleManager.Phase,
        phaseAgeMs: Long,
        connectTimeoutMs: Long,
    ): Boolean {
        return phase == AiDexBleManager.Phase.GATT_CONNECTING && phaseAgeMs >= connectTimeoutMs
    }

    fun shouldRecoverFromPreAuthEncryptedTraffic(
        phase: AiDexBleManager.Phase,
        bondState: Int,
        keyExchangePendingBond: Boolean,
        encryptedFrameCount: Int,
        firstEncryptedFrameAtMs: Long,
        nowMs: Long,
        minFrames: Int,
        timeoutMs: Long,
    ): Boolean {
        if (phase != AiDexBleManager.Phase.DISCOVERING_SERVICES && phase != AiDexBleManager.Phase.CCCD_CHAIN) {
            return false
        }
        if (bondState != BluetoothDevice.BOND_BONDED || keyExchangePendingBond) {
            return false
        }
        if (encryptedFrameCount < minFrames || firstEncryptedFrameAtMs <= 0L || nowMs < firstEncryptedFrameAtMs) {
            return false
        }
        return (nowMs - firstEncryptedFrameAtMs) >= timeoutMs
    }

    fun shouldAdvanceBondedReconnectToKeyExchange(
        phase: AiDexBleManager.Phase,
        bondState: Int,
        keyExchangePendingBond: Boolean,
        cccdQueueEmpty: Boolean,
        cccdWriteInProgress: Boolean,
        cccdChainComplete: Boolean,
        challengeWritten: Boolean,
        bondDataRead: Boolean,
    ): Boolean {
        if (phase != AiDexBleManager.Phase.CCCD_CHAIN) return false
        if (bondState != BluetoothDevice.BOND_BONDED || keyExchangePendingBond) return false
        if (!cccdQueueEmpty || cccdChainComplete) return false
        // The CCCD queue is drained when the descriptor write is issued, before the
        // callback arrives. If the final initial descriptor callback never lands but
        // bonded pre-auth F003 traffic is already flowing, treat that as setup being
        // far enough along to force key exchange instead of stalling forever.
        if (challengeWritten || bondDataRead) return false
        return true
    }

    fun shouldUseSavedPairKeyReconnect(
        bondState: Int,
        hasSavedPairKey: Boolean,
    ): Boolean {
        return bondState == BluetoothDevice.BOND_BONDED && hasSavedPairKey
    }

    fun decideMissingCccdCallbackAction(
        cccdWriteInProgress: Boolean,
        hasPendingCccd: Boolean,
        timeoutRetries: Int,
        maxRetries: Int,
        canInferComplete: Boolean,
    ): MissingCccdCallbackAction {
        if (!cccdWriteInProgress || !hasPendingCccd) return MissingCccdCallbackAction.IGNORE
        if (!canInferComplete) return MissingCccdCallbackAction.WAIT
        return if (timeoutRetries < maxRetries) {
            MissingCccdCallbackAction.WAIT
        } else {
            MissingCccdCallbackAction.ASSUME_COMPLETE
        }
    }

    fun shouldRecoverFromBlockedReconnect(
        phase: AiDexBleManager.Phase,
        hasGatt: Boolean,
        connectAttemptInFlight: Boolean,
        hasRecentLiveData: Boolean,
        lastLiveReadingObservedTimeMs: Long,
    ): Boolean {
        if (phase == AiDexBleManager.Phase.IDLE && (hasGatt || connectAttemptInFlight)) {
            return true
        }
        if (
            phase == AiDexBleManager.Phase.STREAMING &&
            !hasGatt &&
            !connectAttemptInFlight &&
            lastLiveReadingObservedTimeMs > 0L &&
            !hasRecentLiveData
        ) {
            return true
        }
        return false
    }

    fun decideInvalidSetupRecoveryAction(
        consecutiveRecoveries: Int,
        bondState: Int,
        bondResetThreshold: Int,
        bondValidatedByStreaming: Boolean,
    ): InvalidSetupRecoveryAction {
        return if (
            bondState == BluetoothDevice.BOND_BONDED &&
            !bondValidatedByStreaming &&
            consecutiveRecoveries >= bondResetThreshold
        ) {
            InvalidSetupRecoveryAction.REMOVE_BOND_AND_RECONNECT
        } else {
            InvalidSetupRecoveryAction.RECONNECT
        }
    }
}
