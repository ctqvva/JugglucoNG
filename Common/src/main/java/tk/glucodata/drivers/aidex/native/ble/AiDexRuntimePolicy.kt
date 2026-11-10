package tk.glucodata.drivers.aidex.native.ble

import android.bluetooth.BluetoothDevice

internal object AiDexRuntimePolicy {

    enum class PairKeyStartAction {
        USE_SAVED_KEY,
        FRESH_PAIR,
    }

    enum class KeyExchangeFailureAction {
        RETRY_CLEAN_GATT,
        /** The saved key is dead and this phone holds the sensor's bond: replace it over F001. */
        REPLACE_SAVED_KEY,
        /**
         * The fresh pair that followed a confirmed reset kept failing: the reset evidently did
         * not rotate the sensor's credential, so go back to the key that was kept for this.
         */
        RESTORE_SAVED_KEY,
        BROADCAST_ONLY,
    }

    enum class InvalidSetupRecoveryAction {
        RECONNECT,
    }

    enum class MissingCccdCallbackAction {
        IGNORE,
        WAIT,
        ASSUME_COMPLETE,
        /** An MTU exchange crossed this write; its callback is lost and the GATT is wedged. */
        RECOVER_WEDGED_GATT,
    }

    /**
     * Which credential the next key exchange starts from.
     *
     * A saved key is used whenever one exists, bonded or not. F002 and F003 accept CCCD
     * writes over an unencrypted link — the sensor's security there is the app-layer AES
     * with the PAIR key, not SMP — and only F001 demands a bond. So an unbonded phone that
     * holds the key reads F002 with it and never touches F001 or asks for a bond: that is
     * the recovery after a network-settings reset (new phone identity, sensor's one bond
     * slot still taken) and the cross-device restore. Never createBond() from the phone
     * side; the sensor refuses that (BOND_NONE, then status 22).
     *
     * Without a saved key the sensor is paired fresh over F001, which is what makes it
     * initiate pairing. A saved key that has failed its bounded retries ([savedKeyExhausted])
     * is replaced over F001 when this phone is the sensor's bonded device — F001 on a bonded
     * link is exactly what every pre-1.2.0 connection did — or when the user presses Pair.
     * An unbonded phone never runs F001 on its own: the sensor refuses it while another
     * device holds the bond slot, so there the user decides.
     */
    fun decidePairKeyStartAction(
        hasSavedPairKey: Boolean,
        savedKeyExhausted: Boolean = false,
        explicitPairRequested: Boolean = false,
        bonded: Boolean = false,
        pairKeyResetPending: Boolean = false,
    ): PairKeyStartAction = when {
        !hasSavedPairKey -> PairKeyStartAction.FRESH_PAIR
        // The sensor acknowledged CLEAR_STORAGE, which wipes its bond and PAIR credential:
        // the saved key is dead, so skip the retries that would only prove it. It stays
        // stored until a fresh key decrypts live data, and comes back if the fresh pair fails.
        pairKeyResetPending -> PairKeyStartAction.FRESH_PAIR
        savedKeyExhausted && (explicitPairRequested || bonded) -> PairKeyStartAction.FRESH_PAIR
        else -> PairKeyStartAction.USE_SAVED_KEY
    }

    /**
     * Both saved-key reconnects and fresh pairs retry through a clean GATT this many times.
     * Once a saved key has used up its retries on a bonded link it is replaced over F001
     * rather than parked; anything else holds in broadcast-only until the user acts.
     */
    fun decideKeyExchangeFailureAction(
        consecutiveFailures: Int,
        maxFailures: Int,
        usedSavedKey: Boolean = false,
        bonded: Boolean = false,
        freshPairAfterReset: Boolean = false,
    ): KeyExchangeFailureAction = when {
        consecutiveFailures < maxFailures -> KeyExchangeFailureAction.RETRY_CLEAN_GATT
        freshPairAfterReset -> KeyExchangeFailureAction.RESTORE_SAVED_KEY
        usedSavedKey && bonded -> KeyExchangeFailureAction.REPLACE_SAVED_KEY
        else -> KeyExchangeFailureAction.BROADCAST_ONLY
    }

    /**
     * The saved-key exchange reads the 17-byte BOND vector from F002. A sensor that answers
     * with anything else — the GX-01S gives a single `00` to an unbonded reader, then drops
     * the link after ~7s with status 19 — has refused, and that refusal has to count as a
     * key-exchange failure or the saved key is never marked exhausted and neither the
     * bonded replace-over-F001 path nor the Pair button can ever leave the saved key behind.
     * One re-read is allowed in case the vector was not ready yet.
     */
    fun decideShortBondReadAction(replyLength: Int, rereads: Int, maxRereads: Int): ShortBondReadAction = when {
        replyLength == BOND_VECTOR_LENGTH -> ShortBondReadAction.ACCEPT
        rereads < maxRereads -> ShortBondReadAction.REREAD
        else -> ShortBondReadAction.FAIL_KEY_EXCHANGE
    }

    enum class ShortBondReadAction {
        ACCEPT,
        REREAD,
        FAIL_KEY_EXCHANGE,
    }

    const val BOND_VECTOR_LENGTH = 17

    /**
     * Status byte of an accepted F002 command. AiDex ACKs `[opcode, status, crc16]` with
     * `0x01` for success: calibration (0x25) has always read it that way, every DELETE_BOND
     * (0xF2) in the field logs answered `0x01`, and CLEAR_STORAGE (0xF3) answered `0x01` on
     * 1.6.0 / 1.7.1 / 1.8.0 sensors that then came back with history `newest=1` and a zeroed
     * session start. The one `0x00` on record is a 1.8.3 CLEAR_STORAGE that left the old
     * history in place — a refusal.
     */
    const val COMMAND_ACCEPTED = 0x01

    /** Longest reply that is still a bare `[opcode, status, crc16]` ACK. */
    private const val MAX_ACK_LENGTH = 4

    fun shouldClearPersistedPairKey(deleteBondPending: Boolean, responseStatus: Int): Boolean =
        deleteBondPending && responseStatus == COMMAND_ACCEPTED

    /**
     * Whether a CLEAR_STORAGE reply proves the sensor accepted the reset. Anything longer
     * than an ACK is not one — newer firmware is reported to answer a reset with key
     * material — and must not be read as a status byte.
     */
    fun isClearStorageConfirmed(responseLength: Int, responseStatus: Int): Boolean =
        responseLength in 2..MAX_ACK_LENGTH && responseStatus == COMMAND_ACCEPTED

    /** First firmware that refuses the lifecycle reset (CLEAR_STORAGE). */
    private val RESET_REFUSING_FIRMWARE = listOf(1, 8, 3)

    /**
     * Whether this firmware still accepts the lifecycle reset. From 1.8.3 the sensor refuses
     * it (the 1.8.3 trace answered `0x00` and kept its history), so the action is withheld
     * there. An unknown or unparseable version is not a reason to hide it: the driver only
     * sends the command over an established session, by which point the version is read.
     */
    fun supportsLifecycleReset(firmwareVersion: String?): Boolean {
        val parts = firmwareVersion
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.split('.')
            ?.map { it.trim().toIntOrNull() ?: return true }
            ?: return true
        for (i in RESET_REFUSING_FIRMWARE.indices) {
            val part = parts.getOrElse(i) { 0 }
            if (part != RESET_REFUSING_FIRMWARE[i]) return part < RESET_REFUSING_FIRMWARE[i]
        }
        return false
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
        hasSensorReportedWearDays: Boolean,
    ): Boolean {
        if (
            startupMetadataComplete &&
            hasModelMetadata &&
            hasAuthoritativeSessionStart &&
            hasSensorReportedWearDays
        ) return false
        return !hasModelMetadata || !hasAuthoritativeSessionStart || !hasSensorReportedWearDays
    }

    const val STARTUP_DEVICE_INFO_MAX_ATTEMPTS = 3
    const val STARTUP_DEVICE_INFO_RETRY_SPACING_MS = 30_000L

    /**
     * Startup `0x10` is the only source of the sensor's own wear byte. It used to be sent once
     * per connection, so a single lost or unusable reply left the card on the model rating
     * until the next reconnect while the metadata pass kept re-arming every minute. A few
     * spaced attempts per connection cover a dropped reply without polling the sensor forever.
     */
    fun shouldRequestStartupDeviceInfo(
        startupMetadataComplete: Boolean,
        attemptsThisConnection: Int,
        lastRequestAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (startupMetadataComplete) return false
        if (attemptsThisConnection >= STARTUP_DEVICE_INFO_MAX_ATTEMPTS) return false
        if (attemptsThisConnection == 0 || lastRequestAtMs <= 0L) return true
        return nowMs - lastRequestAtMs >= STARTUP_DEVICE_INFO_RETRY_SPACING_MS
    }

    /**
     * [calibrationRangeKnownEmpty] is set once the sensor has answered `GET_CALIBRATION_RANGE`
     * with an empty range. A sensor that has never been calibrated would otherwise keep the
     * routine refresh armed forever, since it can never populate the record cache.
     */
    fun shouldRequestRoutineCalibrationRefresh(
        hasCachedCalibrationRecords: Boolean,
        calibrationDownloading: Boolean,
        calibrationRangeKnownEmpty: Boolean = false,
    ): Boolean = !hasCachedCalibrationRecords &&
        !calibrationDownloading &&
        !calibrationRangeKnownEmpty

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
        // callback arrives. If the last F001 descriptor callback never lands but
        // bonded pre-auth F003 traffic is already flowing, treat that as setup being
        // far enough along to force key exchange instead of stalling forever.
        if (challengeWritten || bondDataRead) return false
        return true
    }

    fun decideMissingCccdCallbackAction(
        cccdWriteInProgress: Boolean,
        hasPendingCccd: Boolean,
        timeoutRetries: Int,
        maxRetries: Int,
        canInferComplete: Boolean,
        mtuExchangeCrossedWrite: Boolean = false,
    ): MissingCccdCallbackAction {
        if (!cccdWriteInProgress || !hasPendingCccd) return MissingCccdCallbackAction.IGNORE
        // Waiting longer, or inferring success, only defers the reconnect that is coming:
        // the next write on this BluetoothGatt is refused regardless.
        if (mtuExchangeCrossedWrite) return MissingCccdCallbackAction.RECOVER_WEDGED_GATT
        if (!canInferComplete) return MissingCccdCallbackAction.WAIT
        return if (timeoutRetries < maxRetries) {
            MissingCccdCallbackAction.WAIT
        } else {
            MissingCccdCallbackAction.ASSUME_COMPLETE
        }
    }

    /**
     * How long the first CCCD write of a connection must wait after the most recent
     * `onMtuChanged`, or 0 when it may go out now.
     *
     * The GX-01S runs its own ATT MTU exchange a few hundred ms after connecting, on top of
     * the one we request. If our CCCD Write Request is outstanding when that second exchange
     * lands, the write's completion is lost: `onDescriptorWrite` never arrives, `mDeviceBusy`
     * stays latched and every later op on that `BluetoothGatt` is refused until we reconnect —
     * which replays the same timing and loses the same race. A first connect survives because
     * full service discovery is slow enough to miss the window; a reconnect on a cached GATT
     * db is not, so it loops forever at "Configuring notifications". Holding the write until
     * the bearer has been quiet for [settleMs] costs at most that long per connection.
     */
    fun cccdStartDelayMs(lastMtuCallbackAtMs: Long, nowMs: Long, settleMs: Long): Long {
        if (lastMtuCallbackAtMs <= 0L) return 0L
        val age = nowMs - lastMtuCallbackAtMs
        if (age < 0L) return settleMs
        return (settleMs - age).coerceAtLeast(0L)
    }

    /**
     * An `onMtuChanged` landed while a CCCD write was still waiting for its callback. That
     * write is expected to be dead (see [cccdStartDelayMs]). The link is not torn down on
     * the spot — a stack that merely reports the exchange late would still complete the
     * write — but if the callback misses its first window the driver reconnects instead of
     * inferring success and having the next write refused.
     */
    fun mtuExchangeCrossedPendingCccd(
        phase: AiDexBleManager.Phase,
        cccdWriteInProgress: Boolean,
        hasPendingCccd: Boolean,
    ): Boolean =
        phase == AiDexBleManager.Phase.CCCD_CHAIN && cccdWriteInProgress && hasPendingCccd

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
        // Transport/setup failures are not proof that either the Android SMP bond or the
        // sensor's stable PAIR credential is invalid. Automatic bond removal can force an
        // unnecessary F001 PAIR exchange, so recovery is always non-destructive.
        return InvalidSetupRecoveryAction.RECONNECT
    }
}
