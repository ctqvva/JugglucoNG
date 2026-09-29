package tk.glucodata.drivers.aidex.native.ble

import android.bluetooth.BluetoothDevice
import tk.glucodata.drivers.aidex.native.protocol.AiDexOpcodes

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
     * A saved key is used whenever one exists, bonded or not, unless a reset is pending (last
     * paragraph). F002 and F003 accept CCCD
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
     *
     * The one exception to both: after an erase the sensor accepted ([pairKeyResetPending]) its
     * bond and PAIR credential are gone, so the saved key is skipped and the next exchange pairs
     * fresh over F001, bonded or not. The saved key stays stored as the fallback.
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
     * rather than parked, and a fresh pair after an accepted erase that has used up its retries
     * goes back to the kept key; anything else holds in broadcast-only until the user acts.
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
     * Accept status of CLEAR_STORAGE (0xF3) and DELETE_BOND (0xF2) only, in their
     * `[opcode, status, crc16]` ACK: every 0xF2 in the field logs answered `0x01`, and 0xF3
     * answered `0x01` on 1.6.0 / 1.7.1 / 1.8.0 sensors that then came back with history
     * `newest=1` and a zeroed session start. The one 0xF3 `0x00` on record is a 1.8.3 reset that
     * left the old history in place — a refusal. Not a rule for other opcodes: 0x20 has its own
     * convention ([setNewSensorAck]), and 0x25 reading `0x01` as success is the code's reading,
     * not a capture (the device log showed `0x00`).
     */
    const val COMMAND_ACCEPTED = 0x01

    /** Longest reply that is still a bare `[opcode, status, crc16]` ACK. */
    const val MAX_ACK_LENGTH = 4

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

    enum class LifecycleReset { ALLOWED, REFUSED, UNKNOWN }

    /**
     * What this firmware does with the lifecycle reset. From 1.8.3 the sensor refuses it (the 1.8.3
     * trace answered `0x00` and kept its history). UNKNOWN when the version cannot decide: blank or
     * unparseable, or missing the component that matters — "1.8" (all the 0x10/0x21 replies carry)
     * is 1.8.0 or 1.8.3; "1.7" and "1.9" decide on their own.
     */
    fun lifecycleReset(firmwareVersion: String?): LifecycleReset {
        val parts = firmwareVersion
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.split('.')
            ?.map { it.trim().toIntOrNull() ?: return LifecycleReset.UNKNOWN }
            ?: return LifecycleReset.UNKNOWN
        for (i in RESET_REFUSING_FIRMWARE.indices) {
            val part = parts.getOrNull(i) ?: return LifecycleReset.UNKNOWN
            if (part != RESET_REFUSING_FIRMWARE[i]) {
                return if (part < RESET_REFUSING_FIRMWARE[i]) LifecycleReset.ALLOWED else LifecycleReset.REFUSED
            }
        }
        return LifecycleReset.REFUSED
    }

    /**
     * Whether the reset action is offered. Only a firmware known to refuse hides it: the version is
     * read late (streaming metadata), and the UI should not flicker until then. The send itself
     * waits for [lifecycleReset] ALLOWED (resetSensor).
     */
    fun supportsLifecycleReset(firmwareVersion: String?): Boolean =
        lifecycleReset(firmwareVersion) != LifecycleReset.REFUSED

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

    enum class ZeroStartActivation {
        /** The sensor confirmed this app's 0xF3 erase; the 0x20 it owes is sent now. */
        POST_RESET,
        /** Nothing says this sensor ever ran: start it, as the official app and upstream do. */
        FRESH_SENSOR,
        /** 0x20 already went out (or was refused) on this connection; never a second one. */
        ALREADY_ATTEMPTED,
        /** This app has seen the sensor running, so zeros are a bad read, not a new sensor. */
        KNOWN_RUNNING,
    }

    /**
     * What an all-zero CGM Session Start Time (0x2AAA) on a source that may activate leads to.
     *
     * A sensor that has never been started reports zeros until SET_NEW_SENSOR (0x20), and it
     * produces no glucose until it gets one, so a new sensor is started right here, once per
     * connection — the official app and upstream do the same. Zeros from a sensor this app has
     * already seen running are a contradiction, not a new sensor: 0x20 would restamp its session
     * to "now" and quarantine its stored ring behind the history barrier, so it is refused.
     * Evidence of a running session, all of it persisted except [hasAcceptedReading]:
     * - [hasStoredPairCredential]: the vault is written only after a CRC-valid live frame, or by
     *   importing a key for a sensor paired elsewhere;
     * - [hasDownloadedHistory]: a history cursor past zero;
     * - [hasAcceptedReading]: a reading this driver accepted in this process;
     * - [bondValidatedByStreaming]: direct live data arrived over this bond.
     * A confirmed erase ([needsPostResetActivation]) outranks that evidence: the old session is
     * gone on the sensor's own word.
     */
    fun decideZeroSessionStartActivation(
        needsPostResetActivation: Boolean,
        autoActivationAttemptedThisConnection: Boolean,
        hasStoredPairCredential: Boolean,
        hasDownloadedHistory: Boolean,
        hasAcceptedReading: Boolean,
        bondValidatedByStreaming: Boolean,
    ): ZeroStartActivation = when {
        autoActivationAttemptedThisConnection -> ZeroStartActivation.ALREADY_ATTEMPTED
        needsPostResetActivation -> ZeroStartActivation.POST_RESET
        hasStoredPairCredential || hasDownloadedHistory || hasAcceptedReading || bondValidatedByStreaming ->
            ZeroStartActivation.KNOWN_RUNNING
        else -> ZeroStartActivation.FRESH_SENSOR
    }

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

    /**
     * [calibrationRangeKnownEmpty] is set once the sensor has answered `GET_CALIBRATION_RANGE`
     * with an empty range. A sensor that has never been calibrated would otherwise keep the
     * routine refresh armed forever, since it can never populate the record cache.
     *
     * [calibrationFetchAttempted] covers the other half of that hole: a sensor that reports a
     * non-empty range but whose 0x27 records never parse (short frame, body not a multiple of the
     * row size, no answer at all) also leaves the cache empty, and the fetch clears
     * [calibrationDownloading] on a fixed delay after the last request rather than on an answer.
     * It is cleared per connection, so a reconnect still gets one fresh attempt.
     */
    fun shouldRequestRoutineCalibrationRefresh(
        hasCachedCalibrationRecords: Boolean,
        calibrationDownloading: Boolean,
        calibrationRangeKnownEmpty: Boolean = false,
        calibrationFetchAttempted: Boolean = false,
    ): Boolean = !hasCachedCalibrationRecords &&
        !calibrationDownloading &&
        !calibrationRangeKnownEmpty &&
        !calibrationFetchAttempted

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

    /**
     * Read-only F002 queries are the only writes a completion path may re-send. A missing or
     * failed write callback does not prove the sensor never got the frame, so a state-changing
     * command (0x20 new sensor, 0x25 calibration, 0x30 default param, 0xF0/0xF1/0xF2/0xF3
     * maintenance) has to fail into a safe state instead of being replayed. Anything not listed
     * here counts as state-changing — an untagged write included.
     */
    fun isRetryableF002Query(opcode: Int): Boolean = opcode in RETRYABLE_F002_QUERY_OPCODES

    /**
     * SET_NEW_SENSOR (0x20) must not arm the post-reset barrier or wipe history cursors
     * unless a usable GATT link and session key can actually send the write.
     */
    fun maySendSetNewSensor(
        gattReady: Boolean,
        servicesReady: Boolean,
        sessionKeyPresent: Boolean,
        resetInFlight: Boolean,
        unpairInFlight: Boolean,
    ): Boolean {
        if (resetInFlight || unpairInFlight) return false
        if (!gattReady || !servicesReady) return false
        return sessionKeyPresent
    }

    enum class SetNewSensorLocalFate {
        ROLLBACK_NEVER_ON_AIR,
        ROLLBACK_SENSOR_NACK,
        KEEP_DISPATCHED_UNCONFIRMED,
    }

    fun setNewSensorLocalFate(dispatched: Boolean, nackSeen: Boolean): SetNewSensorLocalFate {
        if (!dispatched) return SetNewSensorLocalFate.ROLLBACK_NEVER_ON_AIR
        if (nackSeen) return SetNewSensorLocalFate.ROLLBACK_SENSOR_NACK
        return SetNewSensorLocalFate.KEEP_DISPATCHED_UNCONFIRMED
    }

    /**
     * Binder peek and [handleNewSensorAck] must agree: a truncated 0x20 frame is not a sensor
     * refusal. Treating a missing status byte as NACK would skip the local commit after
     * [android.bluetooth.BluetoothGatt.writeCharacteristic] already returned true, so a later
     * disconnect would roll back as never-on-air instead of KEEP.
     */
    fun isSetNewSensorNack(plaintext: ByteArray): Boolean {
        if (plaintext.size < 2) return false
        if ((plaintext[0].toInt() and 0xFF) != AiDexOpcodes.SET_NEW_SENSOR) return false
        return setNewSensorAck(plaintext[1].toInt() and 0xFF) == SetNewSensorAck.REFUSED
    }

    enum class SetNewSensorAck { ACCEPTED, UNCONFIRMED, REFUSED }

    /**
     * What a 0x20 answer's status says. 0x00 is the documented acceptance. 0x01 is what a new
     * AiDex X answered in the 2026-09-23 field trace, and that sensor did start — a real session
     * start came 2 s later, and the same firmware answers 0x34/0x35 with 0x01 too — so it is no
     * refusal on its own: the session start read after it decides ([decideUnconfirmedActivation]).
     * Nothing is sent because of it. Any other status stays a refusal.
     */
    fun setNewSensorAck(status: Int): SetNewSensorAck = when (status) {
        0x00 -> SetNewSensorAck.ACCEPTED
        0x01 -> SetNewSensorAck.UNCONFIRMED
        else -> SetNewSensorAck.REFUSED
    }

    enum class UnconfirmedActivation { STARTED, READ_AGAIN, REFUSED }

    /**
     * After an unconfirmed 0x20 answer the sensor's own session start decides. A 0x20 stamps the
     * phone's time as the new start, so only a start at or after the dispatch ([startMs] against
     * [dispatchAtMs], wall clock, less [slackMs] for skew) means the sensor took it; a non-zero
     * start older than that is the session it kept, and zeros ([startMs] 0) mean it started
     * nothing: both are a refusal — never the convenient reading — once [settleMs] has passed
     * since the answer ([ackAtMs] and [nowMs] on the elapsed clock). Read sooner, either may be a
     * sensor that has not updated 0x2AAA yet, so read again. An unknown dispatch time cannot rule
     * a start out.
     */
    fun decideUnconfirmedActivation(
        startMs: Long,
        dispatchAtMs: Long,
        ackAtMs: Long,
        nowMs: Long,
        settleMs: Long,
        slackMs: Long,
    ): UnconfirmedActivation = when {
        startMs > 0L && (dispatchAtMs <= 0L || startMs >= dispatchAtMs - slackMs) -> UnconfirmedActivation.STARTED
        nowMs - ackAtMs >= settleMs -> UnconfirmedActivation.REFUSED
        else -> UnconfirmedActivation.READ_AGAIN
    }

    /**
     * SET_DEFAULT_PARAM (0x30) rewrites the sensor's whole parameter table from a catalog blob
     * that has never been byte-compared with the official app on the wire. Two independent
     * barriers hold it, and they live here rather than in AiDexBleManager's private companion so
     * that a plain JVM test can see them: as private constants of an Android class, flipping
     * either one passed a green run.
     *
     * [mayAutoApplyDefaultParam] keeps the unattended pass read-only — it runs the 0x31 probe and
     * nothing else. [hasVerifiedDefaultParamAckStatus] refuses to START an apply at all, the
     * manual maintenance path included: the success status for 0x30 is unknown, so the first ACK
     * would abort the run and leave the sensor holding half the old table and half the new one.
     * 0xF3 and 0xF2 answer 0x01 ([COMMAND_ACCEPTED]), 0x25 is read the same way, and 0x20 has been
     * seen answering both 0x00 and 0x01 (0x01 on a field AiDex X that started), so the convention
     * is not settled per opcode and a guess here would pick blind.
     *
     * Both are device decisions, not code decisions, and they lift together: only a capture of the
     * official app doing a real 0x30 exchange settles the ACK status and the chunk bytes.
     */
    fun mayAutoApplyDefaultParam(): Boolean = DEFAULT_PARAM_AUTO_APPLY_ENABLED

    fun hasVerifiedDefaultParamAckStatus(): Boolean = DEFAULT_PARAM_ACK_SUCCESS_STATUSES.isNotEmpty()

    /**
     * A 0x30 chunk ACK counts as "accepted" only for a status this set names. A short ACK carries
     * no status at all ([status] null) and an unrecognised status is a refusal; both have to stop
     * the run rather than push the rest of the table over a possible refusal.
     */
    fun isDefaultParamAckAccepted(status: Int?): Boolean =
        status != null && status in DEFAULT_PARAM_ACK_SUCCESS_STATUSES

    /** Status byte of an F002 command ACK. A frame too short to carry one reads as 0xFF, a refusal. */
    fun commandAckStatus(plaintext: ByteArray): Int =
        if (plaintext.size >= 2) plaintext[1].toInt() and 0xFF else 0xFF

    /**
     * Status of a CLEAR_STORAGE reply for [clearStorageResponseOutcome]. A reply longer than a bare
     * ACK is not one ([isClearStorageConfirmed]) and reads as 0xFF, a refusal.
     */
    fun clearStorageAckStatus(plaintext: ByteArray): Int =
        if (plaintext.size > MAX_ACK_LENGTH) 0xFF else commandAckStatus(plaintext)

    /**
     * The binder peek for CLEAR_STORAGE (0xF3). It claims the erase before the handler sees the
     * frame — the DISCONNECTED wipe would otherwise delete a posted 0x01 and then abandon an erase
     * the sensor confirmed — so it must accept exactly the frames [clearStorageResponseOutcome]
     * confirms: a bare 0xF3 ACK carrying [COMMAND_ACCEPTED].
     */
    fun isClearStorageAccepted(plaintext: ByteArray): Boolean =
        plaintext.isNotEmpty() &&
            (plaintext[0].toInt() and 0xFF) == AiDexOpcodes.CLEAR_STORAGE &&
            isClearStorageConfirmed(plaintext.size, commandAckStatus(plaintext))

    enum class ClearStorageResponseOutcome {
        CONFIRM,
        ABANDON,
        LATE_ACCEPT,
        IGNORE,
    }

    /**
     * What a 0xF3 ACK does to the reset latch ([resetPending]) and its ACK stamp ([ackStamped]).
     *
     * Only [COMMAND_ACCEPTED] confirms ([status] from [clearStorageAckStatus]), and CONFIRM is
     * idempotent: the binder peek may already have stamped. A refusal — 0x00 included, which is
     * what the 1.8.3 firmware answered while keeping its history — abandons only an attempt
     * nothing has confirmed yet; after an accept, a duplicate or any other frame must not unwind
     * the erase. An accept that arrives after the attempt was abandoned credits nothing: no debt,
     * no bond removal, no 0x20. The manager reports it and marks the PAIR key reset-pending, since
     * the sensor did erase. HEAD stamped the ACK for any status and abandoned
     * on any other status, even with nothing pending, which cleared the debt and the barrier of
     * an earlier confirmed erase.
     */
    fun clearStorageResponseOutcome(
        resetPending: Boolean,
        ackStamped: Boolean,
        status: Int,
    ): ClearStorageResponseOutcome = when {
        resetPending && status == COMMAND_ACCEPTED -> ClearStorageResponseOutcome.CONFIRM
        resetPending && !ackStamped && status != COMMAND_ACCEPTED -> ClearStorageResponseOutcome.ABANDON
        !resetPending && !ackStamped && status == COMMAND_ACCEPTED -> ClearStorageResponseOutcome.LATE_ACCEPT
        else -> ClearStorageResponseOutcome.IGNORE
    }

    const val POST_RESET_START_SLACK_MS: Long = 2L * 60_000L

    /**
     * A reported session start older than the reset press minus [POST_RESET_START_SLACK_MS]
     * belongs to the session the erase was meant to end: it is not authoritative, and on a source
     * that may activate it forces the one 0x20. Pure extraction of applyParsedSessionStartTime.
     * Keep the slack: without it a firmware that confirms 0xF3 and keeps its session, or a
     * start that clock skew put just before the press, gets an extra automatic 0x20.
     */
    fun sessionStartPredatesReset(
        needsPostResetActivation: Boolean,
        resetRequestedAtMs: Long,
        startMs: Long,
    ): Boolean = needsPostResetActivation &&
        resetRequestedAtMs > 0L &&
        startMs < resetRequestedAtMs - POST_RESET_START_SLACK_MS

    enum class PostResetReconnectStep {
        CONNECT,
        HOLD_FOR_BOND_REMOVAL,
    }

    /**
     * After a confirmed erase: hold on broadcasts, or connect after the settle delay. Evaluated in
     * completePostResetReconnect right after removeBond(), which only asks — BOND_NONE arrives later
     * on its own broadcast, and [mayLeavePostResetBondHold] then releases the hold. An unconfirmed
     * erase, BOND_NONE or no device connects after the delay, as on HEAD (which connected whatever
     * the bond state).
     */
    fun postResetReconnectStep(eraseConfirmed: Boolean, bondState: Int?): PostResetReconnectStep =
        if (eraseConfirmed && bondState != null && bondState != BluetoothDevice.BOND_NONE) {
            PostResetReconnectStep.HOLD_FOR_BOND_REMOVAL
        } else {
            PostResetReconnectStep.CONNECT
        }

    /**
     * The post-reset bond hold's only exit: the settle delay is over and the held device's Android
     * bond is gone. No timer releases it onto the old bond; the user's Reconnect is the other way out.
     */
    fun mayLeavePostResetBondHold(nowElapsed: Long, notBeforeElapsed: Long, bondState: Int?): Boolean =
        nowElapsed >= notBeforeElapsed && bondState == BluetoothDevice.BOND_NONE

    enum class QuietWindowExpiry {
        IGNORE,
        ABANDON_NEVER_DISPATCHED,
        COMPLETE_NO_GATT,
        LOCAL_DISCONNECT,
    }

    /**
     * The CLEAR_STORAGE quiet-window timer. The window is armed when resetSensor() latches, so it
     * also fires for a 0xF3 that drainGattQueue swallowed: with no dispatch stamp nothing reached
     * the sensor, and completing would drop the bond and quarantine the ring for a reset that never
     * happened. A claimed 0x01 is proof of dispatch too: abandonPendingReset refuses a confirmed
     * attempt, and taking that branch for one would leave the latch and the window up with no owner.
     */
    fun decideQuietWindowExpiry(
        forgotten: Boolean,
        resetPending: Boolean,
        writeDispatchedAtMs: Long,
        ackClaimed: Boolean,
        hasGatt: Boolean,
    ): QuietWindowExpiry = when {
        forgotten || !resetPending -> QuietWindowExpiry.IGNORE
        writeDispatchedAtMs == 0L && !ackClaimed -> QuietWindowExpiry.ABANDON_NEVER_DISPATCHED
        !hasGatt -> QuietWindowExpiry.COMPLETE_NO_GATT
        else -> QuietWindowExpiry.LOCAL_DISCONNECT
    }

    enum class DisconnectOwner {
        UNCONFIRMED_UNPAIR,
        POST_RESET,
        INVALID_SETUP_RECOVERY,
        STALE_RECOVERY,
        AUTH_FAILURE,
        DEFAULT,
    }

    /**
     * Which branch of STATE_DISCONNECTED owns the disconnect, read after
     * resetConnectionRuntimeState (which has already abandoned an unconfirmed reset latch, so
     * [resetPending] here is a confirmed erase). The 0xF2 and 0xF3 latches come first. HEAD let
     * the invalid-setup, stale-recovery and status-5 exits return before them: an unconfirmed
     * unpair then kept isUnpaired with nothing left to clear it, and a confirmed erase reconnected
     * on the old bond.
     */
    fun decideDisconnectOwner(
        unpairPending: Boolean,
        resetPending: Boolean,
        invalidSetupRecoveryPending: Boolean,
        staleRecoveryPending: Boolean,
        status: Int,
    ): DisconnectOwner = when {
        unpairPending -> DisconnectOwner.UNCONFIRMED_UNPAIR
        resetPending -> DisconnectOwner.POST_RESET
        invalidSetupRecoveryPending -> DisconnectOwner.INVALID_SETUP_RECOVERY
        staleRecoveryPending -> DisconnectOwner.STALE_RECOVERY
        status == 5 -> DisconnectOwner.AUTH_FAILURE // GATT_INSUFFICIENT_AUTHENTICATION
        else -> DisconnectOwner.DEFAULT
    }

    enum class ResetAdmission {
        ALLOW,
        REFUSE_RESET_IN_FLIGHT,
        REFUSE_UNPAIR_IN_FLIGHT,
        REFUSE_NOT_CONNECTED,
    }

    /**
     * resetSensor()'s refusals in HEAD order (pinned): a second press while a reset is latched or
     * its quiet window is up, an unpair that owns the next disconnect, and a link that cannot carry
     * the 0xF3 (no GATT, or services not discovered yet). The missing-session-key refusal follows
     * at the command build.
     */
    fun decideResetAdmission(
        resetInFlight: Boolean,
        unpairInFlight: Boolean,
        linkUsable: Boolean,
    ): ResetAdmission = when {
        resetInFlight -> ResetAdmission.REFUSE_RESET_IN_FLIGHT
        unpairInFlight -> ResetAdmission.REFUSE_UNPAIR_IN_FLIGHT
        !linkUsable -> ResetAdmission.REFUSE_NOT_CONNECTED
        else -> ResetAdmission.ALLOW
    }

    enum class UnpairAdmission {
        ALLOW,
        ALREADY_IN_PROGRESS,
        REFUSE_RESET_IN_FLIGHT,
        REFUSE_NOT_READY,
    }

    /**
     * unpairSensor()'s gate. A second press while 0xF2 is latched answers "in progress" without
     * enqueueing — HEAD put a second 0xF2 on air. A reset in flight refuses, the mirror of
     * [decideResetAdmission]; HEAD refused only while the quiet window was up. Then HEAD's own
     * refusal: no session key, no usable link, or the quiet window.
     */
    fun decideUnpairAdmission(
        unpairInFlight: Boolean,
        resetInFlight: Boolean,
        linkUsable: Boolean,
        quietWindowActive: Boolean,
        sessionKeyPresent: Boolean,
    ): UnpairAdmission = when {
        unpairInFlight -> UnpairAdmission.ALREADY_IN_PROGRESS
        resetInFlight -> UnpairAdmission.REFUSE_RESET_IN_FLIGHT
        !(sessionKeyPresent && linkUsable && !quietWindowActive) -> UnpairAdmission.REFUSE_NOT_READY
        else -> UnpairAdmission.ALLOW
    }

    data class UnconfirmedWriteAction(
        val requeueQuery: Boolean,
        val abandonReset: Boolean,
        val abandonUnpair: Boolean,
        val calibrationUnconfirmed: Boolean,
    )

    /**
     * failGattWrite for a write no callback confirmed, pinned to HEAD. Only a read-only query goes
     * back on the queue, while [retryCount] < [maxQueryRetries] (the caller still increments it);
     * nothing else is ever re-sent. A 0x25 is reported as unconfirmed. A 0xF3 whose error callback
     * arrived before any ACK is abandoned — a stamped ACK outranks the transport status. A 0xF2
     * with the unpair latch still set ends in the key-retained fallback.
     */
    fun decideUnconfirmedWrite(
        opcode: Int,
        retryCount: Int,
        maxQueryRetries: Int,
        callbackReceived: Boolean,
        clearStorageAckStamped: Boolean,
        unpairPending: Boolean,
    ): UnconfirmedWriteAction {
        if (isRetryableF002Query(opcode) && retryCount < maxQueryRetries) {
            return UnconfirmedWriteAction(
                requeueQuery = true,
                abandonReset = false,
                abandonUnpair = false,
                calibrationUnconfirmed = false,
            )
        }
        return UnconfirmedWriteAction(
            requeueQuery = false,
            abandonReset = opcode == AiDexOpcodes.CLEAR_STORAGE && callbackReceived && !clearStorageAckStamped,
            abandonUnpair = opcode == AiDexOpcodes.DELETE_BOND && unpairPending,
            calibrationUnconfirmed = opcode == AiDexOpcodes.SET_CALIBRATION,
        )
    }

    enum class BroadcastFallbackExit {
        STAY,
        LEAVE,
    }

    /**
     * maybeLeaveBroadcastOnlyFallback's gates, pinned to HEAD. [enteredAtElapsed] 0 means "no timed
     * return". A screen known to be off keeps the fallback — the way back runs on uptime handler
     * posts, which stop in suspend — while an unknown one (null: no PowerManager) leaves, as on
     * HEAD. [screenInteractive] is a system call, so it runs only after every cheap gate passed.
     */
    fun decideBroadcastFallbackExit(
        enteredAtElapsed: Long,
        broadcastOnly: Boolean,
        stop: Boolean,
        paused: Boolean,
        unpaired: Boolean,
        nowElapsed: Long,
        holdMs: Long,
        screenInteractive: () -> Boolean?,
    ): BroadcastFallbackExit {
        if (enteredAtElapsed == 0L || !broadcastOnly) return BroadcastFallbackExit.STAY
        if (stop || paused || unpaired) return BroadcastFallbackExit.STAY
        if (nowElapsed - enteredAtElapsed < holdMs) return BroadcastFallbackExit.STAY
        if (screenInteractive() == false) return BroadcastFallbackExit.STAY
        return BroadcastFallbackExit.LEAVE
    }

    private const val DEFAULT_PARAM_AUTO_APPLY_ENABLED = false

    private val DEFAULT_PARAM_ACK_SUCCESS_STATUSES = emptySet<Int>()

    private val RETRYABLE_F002_QUERY_OPCODES = setOf(
        AiDexOpcodes.GET_STARTUP_DEVICE_INFO,
        AiDexOpcodes.GET_BROADCAST_DATA,
        AiDexOpcodes.GET_LOCAL_START_TIME,
        AiDexOpcodes.GET_HISTORY_RANGE,
        AiDexOpcodes.GET_HISTORIES_RAW,
        AiDexOpcodes.GET_HISTORIES,
        AiDexOpcodes.GET_CALIBRATION_RANGE,
        AiDexOpcodes.GET_CALIBRATION,
        AiDexOpcodes.GET_DEFAULT_PARAM,
    )
}
