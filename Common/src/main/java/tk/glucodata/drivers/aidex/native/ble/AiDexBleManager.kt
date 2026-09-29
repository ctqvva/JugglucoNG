// JugglucoNG — AiDex Native Kotlin Driver
// AiDexBleManager.kt — Per-sensor BLE connection manager extending SuperGattCallback
//
// Replaces the vendor native lib (libblecomm-lib.so) for AiDex sensors.
// Uses AiDexKeyExchange for crypto, AiDexCommandBuilder for F002 commands,
// and AiDexParser for parsing responses.
//
// Integration: Drop-in replacement for the vendor-mode path in AiDexSensor.kt.
// SensorBluetooth still manages scanning and the gattcallbacks list.

package tk.glucodata.drivers.aidex.native.ble

import android.app.AlarmManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import tk.glucodata.Applic
import tk.glucodata.BatteryTrace
import tk.glucodata.ExchangeTrend
import tk.glucodata.Log
import tk.glucodata.logd
import tk.glucodata.logi
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorViewModeStore
import tk.glucodata.drivers.aidex.AiDexScanReceiver
import tk.glucodata.drivers.aidex.AiDexDriver
import tk.glucodata.drivers.aidex.AiDexTemperatureStore
import tk.glucodata.drivers.aidex.AiDexProvisioningStore
import tk.glucodata.drivers.aidex.AiDexSerialIdentity
import tk.glucodata.drivers.aidex.CalibrationRecord as SharedCalibrationRecord
import tk.glucodata.drivers.aidex.native.crypto.Crc16CcittFalse
import tk.glucodata.drivers.aidex.native.crypto.SerialCrypto
import tk.glucodata.drivers.aidex.native.data.*
import tk.glucodata.drivers.aidex.native.protocol.*
import java.util.ArrayDeque
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

internal fun aiDexDeviceNameMatchesSerial(deviceName: String, serialNumber: String): Boolean {
    val bareSerial = SerialCrypto.stripPrefix(serialNumber)
    if (bareSerial.isEmpty()) return false
    // Compact local names (X2222267V4E, LUMIX2222267V4E) have no separator before the body.
    // Scan binding already accepts those; the radio match has to accept the same body and
    // still refuse a longer serial that only starts with this one.
    val normalizedBody = tk.glucodata.drivers.aidex.AiDexScanIdentity.normalizeSerial(deviceName)
        ?.removePrefix("X-")
    if (normalizedBody != null && normalizedBody.equals(bareSerial, ignoreCase = true)) return true
    if (containsBoundedToken(deviceName, bareSerial)) return true
    return !serialNumber.equals(bareSerial, ignoreCase = true) &&
        containsBoundedToken(deviceName, serialNumber)
}

private fun containsBoundedToken(haystack: String, token: String): Boolean {
    if (token.isEmpty()) return false
    var from = 0
    while (from < haystack.length) {
        val idx = haystack.indexOf(token, from, ignoreCase = true)
        if (idx < 0) return false
        val beforeOk = idx == 0 || !haystack[idx - 1].isLetterOrDigit()
        val end = idx + token.length
        val afterOk = end >= haystack.length || !haystack[end].isLetterOrDigit()
        if (beforeOk && afterOk) return true
        from = idx + 1
    }
    return false
}

internal fun aiDexPreferredAdvertisedName(cachedDeviceName: String?, scanRecord: ByteArray): String? {
    return aiDexExtractLocalName(scanRecord) ?: cachedDeviceName
}

internal fun aiDexPairingKeyProblemStatusRes(usedSavedKey: Boolean): Int =
    if (usedSavedKey) R.string.aidex_key_rejected else R.string.aidex_key_missing
/**
 * Brand words MicroTech sells the same GX-series hardware under, plus the bare "CGM"/"sensor"
 * generic. All of them belong in the card's family chip, never the title — so the title loop
 * below strips every leading one ("Wellion AiDEX …" loses both words) and whatever remains
 * (normally the "X-…" serial) is the title.
 */
private val AIDEX_TITLE_PREFIX = Regex(
    "^(?:aidex|linx|glucorx|vista|lumiflex|lumi|diax?expert|wellion|cgm|sensors?)(?:[\\s_\\-]+|$)",
    RegexOption.IGNORE_CASE,
)

/**
 * Pick the name a sensor card should be titled with.
 *
 * The card already carries the family chip, so brand words the sensor advertises
 * ("AiDEX X-2222267V4E") are dropped from the title the same way Sibionics strips its managed
 * prefix; "X-2222267V4E" is what remains. [SuperGattCallback.mygetDeviceName] falls back to
 * the MAC address and then "?" before the first connect attempt — neither is a sensor name,
 * so those collapse to the serial instead.
 */
internal fun aiDexDisplayName(advertisedName: String?, deviceAddress: String?, serialNumber: String): String {
    val name = advertisedName?.trim().orEmpty()
    if (name.isEmpty() || name == "?" || name.equals(deviceAddress, ignoreCase = true)) return serialNumber
    var stripped = name
    while (true) {
        val next = stripped.replace(AIDEX_TITLE_PREFIX, "").trim()
        if (next == stripped) break
        stripped = next
    }
    return stripped.ifEmpty { serialNumber }
}

internal fun aiDexExtractLocalName(scanRecord: ByteArray): String? {
    var offset = 0
    var shortName: String? = null
    var completeName: String? = null
    while (offset < scanRecord.size - 1) {
        val len = scanRecord[offset].toInt() and 0xFF
        if (len == 0) break
        val type = scanRecord[offset + 1].toInt() and 0xFF
        if (type == 0x09 || type == 0x08) {
            val start = offset + 2
            val endExclusive = (start + len - 1).coerceAtMost(scanRecord.size)
            if (endExclusive > start) {
                val name = try {
                    String(scanRecord, start, endExclusive - start, Charsets.UTF_8)
                } catch (_: Throwable) {
                    null
                }
                if (name != null) {
                    if (type == 0x09) completeName = name else if (shortName == null) shortName = name
                }
            }
        }
        offset += len + 1
    }
    return completeName ?: shortName
}

internal data class AiDexActivationTimeZone(
    val tzQuarters: Int,
    val dstQuarters: Int,
)

internal fun aiDexActivationTimeZone(calendar: Calendar, timeZone: TimeZone): AiDexActivationTimeZone {
    val quarterHourMs = 15 * 60 * 1000
    val tzQuarters = timeZone.rawOffset / quarterHourMs
    val dstQuarters = if (timeZone.inDaylightTime(calendar.time)) {
        timeZone.dstSavings / quarterHourMs
    } else {
        0
    }
    return AiDexActivationTimeZone(
        tzQuarters = tzQuarters,
        dstQuarters = dstQuarters,
    )
}

/**
 * UTC offset of a 0x2AAA session start, in seconds. The DST byte is the BT SIG DST offset in
 * quarter hours — [aiDexActivationTimeZone] writes 2, 4 or 8 — and 255 means "unknown".
 * Time-zone byte 0x80 is also "unknown" (Bluetooth). Sign-extended it is -128 quarters, 32 hours,
 * which would stamp every later sample a day off. Real zones stay signed; only that sentinel is 0.
 */
internal fun aiDexSessionStartOffsetSeconds(tzQuarters: Int, dstQuarters: Int): Int {
    val tz = if (tzQuarters == -128 || tzQuarters == 0x80) 0 else tzQuarters
    val dstSeconds = if (dstQuarters == 2 || dstQuarters == 4 || dstQuarters == 8) dstQuarters * 15 * 60 else 0
    return tz * 15 * 60 + dstSeconds
}

/**
 * Per-sensor BLE connection manager for AiDex sensors.
 *
 * Extends [SuperGattCallback] so it plugs into JugglucoNG's existing
 * [SensorBluetooth.gattcallbacks] list and multi-sensor management.
 *
 * Lifecycle:
 *   1. SensorBluetooth creates this and adds to gattcallbacks
 *   2. SensorBluetooth calls connectDevice() when device is found
 *   3. GATT connects → discover services → CCCD chain → key exchange → streaming
 *   4. F003 notifications deliver live glucose via handleGlucoseResult()
 *   5. History/calibration are fetched after streaming starts
 *   6. On disconnect, reconnect strategy manages retry timing
 *
 * @param serial Sensor serial number (bare, without "X-" prefix)
 * @param dataptr Native data pointer from Natives.getdataptr(serial)
 * @param sensorGen Sensor generation identifier
 */
@SuppressLint("MissingPermission")
class AiDexBleManager(
    serial: String,
    dataptr: Long,
    sensorGen: Int,
) : SuperGattCallback(serial, dataptr, sensorGen), SensorBleController, AiDexDriver {

    companion object {
        private const val TAG = "AiDexBleManager"

        // -- BLE UUIDs --
        val SERVICE_F000: UUID = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
        val CHAR_F001: UUID = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")
        val CHAR_F002: UUID = UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")
        val CHAR_F003: UUID = UUID.fromString("0000f003-0000-1000-8000-00805f9b34fb")

        // Standard BLE: Device Information Service (0x180A)
        val SERVICE_DIS: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val CHAR_MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val CHAR_SOFTWARE_REV: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
        val CHAR_MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")

        // Standard BLE: CGM Session Start Time (0x2AAA) under CGM service (0x181F = SERVICE_F000)
        val CHAR_CGM_SESSION_START: UUID = UUID.fromString("00002aaa-0000-1000-8000-00805f9b34fb")
        val CHAR_CGM_SESSION_RUN: UUID = UUID.fromString("00002aab-0000-1000-8000-00805f9b34fb")

        // -- GATT Queue --
        private const val GATT_OP_MAX_RETRIES = 10
        private const val NO_RESPONSE_ADVANCE_MS = 35L
        private const val BONDING_PAUSE_TIMEOUT_MS = 15_000L
        private const val GATT_WRITE_RETRY_DELAY_MS = 200L

        // -- Timeouts --
        /**
         * Deadline for [onMtuChanged] after a successful `requestMtu()`. Service discovery is
         * normally started from that callback; this timer only decides when to give up on the link.
         *
         * We never discover on expiry. Starting discovery — and therefore the first CCCD descriptor
         * write — while the MTU exchange is still outstanding wedges the connection: the write never
         * gets `onDescriptorWrite`, `mDeviceBusy` stays latched, and every later GATT op on that
         * link is rejected until we reconnect. A 15h device trace measured exactly that: 184
         * expiries, 184 discoveries started anyway, zero sessions established, while all 11 sessions
         * that did come up came through the callback.
         *
         * The deadline must therefore stay comfortably longer than a real MTU round trip — the same
         * trace saw a median of 0.6s and a worst case of 5.0s, so the old 2s was cutting off round
         * trips that were about to succeed.
         */
        private const val MTU_CALLBACK_TIMEOUT_MS = 8_000L
        /**
         * Quiet time required after the last `onMtuChanged` before the first CCCD write goes
         * out. The sensor runs a second, peer-initiated MTU exchange shortly after connect; a
         * Write Request outstanding when it lands never completes and wedges the GATT. See
         * [AiDexRuntimePolicy.cccdStartDelayMs].
         */
        private const val MTU_SETTLE_BEFORE_CCCD_MS = 750L
        /** Re-reads allowed when F002 returns something other than the 17-byte BOND vector. */
        private const val BOND_READ_MAX_REREADS = 1
        private const val BOND_REREAD_DELAY_MS = 400L
        private const val DISCOVERY_RETRY_DELAY_MS = 1_500L
        private const val DISCOVERY_MAX_RETRIES = 2
        private const val HISTORY_PAGE_TIMEOUT_MS = 25_000L
        /** Extra fence after the page watchdog so a late pre-Start 0x22 is not treated as the new range. */
        private const val HISTORY_STALE_RESPONSE_GRACE_MS = 5_000L
        private const val HISTORY_REQUEST_DELAY_MS = 80L
        private const val KEY_EXCHANGE_TIMEOUT_MS = 35_000L
        /**
         * How long after an unconfirmed 0x20 answer zeros in 0x2AAA, or a start older than the
         * 0x20, count as a refusal; also the delay of the re-read that asks for the verdict.
         */
        private const val UNCONFIRMED_ACTIVATION_SETTLE_MS = 3_000L
        private const val SETUP_STALL_TIMEOUT_MS = 25_000L
        private const val SETUP_BONDING_STALL_TIMEOUT_MS = 35_000L
        private const val PRE_AUTH_ENCRYPTED_TRAFFIC_TIMEOUT_MS = 5_000L
        private const val PRE_AUTH_ENCRYPTED_TRAFFIC_MIN_FRAMES = 3
        private const val CCCD_WRITE_CALLBACK_TIMEOUT_MS = 2_500L
        private const val CCCD_WRITE_CALLBACK_MAX_EXTRA_WAITS = 1
        private const val INVALID_SETUP_BOND_RESET_THRESHOLD = 2
        // decideInvalidSetupRecoveryAction can only answer RECONNECT once bondValidatedByStreaming
        // is set, and that flag is persisted for any sensor that ever streamed — so a bond that
        // stops accepting the post-BOND config used to loop here every 60s for the life of the
        // process. This is the cap that ends it. Removing a working Android bond nobody asked to
        // remove stays off the table: that is the defect full circle #1 filed twice as high.
        private const val INVALID_SETUP_BROADCAST_FALLBACK_THRESHOLD = 6
        private const val GATT_OP_TIMEOUT_MS = 15_000L    // Watchdog for stuck GATT operations
        private const val GATT_OP_WATCHDOG_RETRIES = 2  // Max retries on watchdog timeout before dropping op
        private const val STALE_CONNECTION_RECOVERY_FALLBACK_MS = 3_000L
        private const val CLEAR_STORAGE_QUIET_WINDOW_MS = 12_000L
        // How long a caller waits for the AiDex looper before it stops waiting (teardown, Start).
        private const val HANDLER_WAIT_MS = 3_000L
        // Written only after the sensor confirmed an erase. The legacy key was written at the
        // button press and is dropped unread (see init).
        private const val ACTIVATION_DEBT_PREF = "postResetActivationDebtV2"
        private const val LEGACY_ACTIVATION_DEBT_PREF = "needsPostResetActivation"
        /**
         * Written by the builds from 27819cd0d (2026-09-23) up to the 0x01 rule, on a 0xF3 answered
         * 0x00, which the 0x01 rule reads as a refusal.
         */
        private const val PRE_0X01_ACTIVATION_DEBT_PREF = "postResetActivationDebt"
        private const val POST_RESET_RECONNECT_DELAY_MS = 5_000L
        // The reset outcome is produced while the link is already down, and the sensor list polls
        // the status every 2-5s (SensorViewModel), so the default 5s transient window could expire
        // between two polls — before the post-reset reconnect had even started.
        private const val POST_RESET_OUTCOME_STATUS_MS = 20_000L
        // Compared as well as shown: resetConnectionRuntimeState must not replace it with "not sent".
        private const val CALIBRATION_NOT_CONFIRMED_STATUS = "Calibration not confirmed"
        private const val EXPECTED_LIVE_INTERVAL_MS = 60_000L
        private const val EXPECTED_LIVE_GRACE_MS = 20_000L
        private const val NO_STREAM_WATCHDOG_MS = EXPECTED_LIVE_INTERVAL_MS + EXPECTED_LIVE_GRACE_MS
        private const val CONNECTED_BROADCAST_REQUEST_WAIT_MS = 30_000L
        private const val INITIAL_HISTORY_REQUEST_DELAY_MS = 65_000L
        private const val POST_KEY_CCCD_REFRESH_DELAY_MS = 250L
        private const val POST_BOND_CONFIG_SETTLE_MS = 500L
        private const val POST_BOND_CONFIG_MAX_DEFERRALS = 4

        // A rejected post-BOND config write reconnects instead of escalating: one bad ATT status
        // must not spend the invalid-setup budget, whose second strike removes the user's bond.
        // Only a bond that fails the handshake this many times running counts as actually broken.
        private const val POST_BOND_CONFIG_MAX_REJECTIONS = 3
        private const val DEFERRED_BOND_CHECK_DELAY_MS = 2_500L
        private const val DEFERRED_BOND_CHECK_MAX_ATTEMPTS = 4
        private const val STARTUP_CONTROL_ACK_TIMEOUT_MS = 3_000L
        private const val OPTIONAL_DEFAULT_PARAM_PROVISIONING_DELAY_MS = 15_000L
        private const val OPTIONAL_STREAMING_METADATA_DELAY_MS = 5_000L
        private const val OPTIONAL_CALIBRATION_REFRESH_DELAY_MS = EXPECTED_LIVE_INTERVAL_MS + 5_000L
        private const val STARTUP_DEVICE_INFO_REQUEST_DELAY_MS = 2_000L
        private const val DEFAULT_PARAM_WRITE_ACK_TIMEOUT_MS = 10_000L
        private const val DEFAULT_PARAM_MAX_CHUNK_PAYLOAD_BYTES = 160
        // Both SET_DEFAULT_PARAM (0x30) barriers moved to AiDexRuntimePolicy
        // (mayAutoApplyDefaultParam / hasVerifiedDefaultParamAckStatus / isDefaultParamAckAccepted)
        // so a JVM test can assert on them; here, private in an Android class's companion, a flip
        // passed a green run. Today 0x30 never leaves the phone: the unattended pass is probe-only
        // AND the apply refuses to start, so the manual maintenance path stops at the same gate.
        private const val KEY_EXCHANGE_MAX_FAILURES = 3

        // -- History Storage --
        private const val MIN_VALID_GLUCOSE_MGDL = 20
        private const val MAX_VALID_GLUCOSE_MGDL = 500
        private const val MAX_OFFSET_DAYS = 30
        private const val WARMUP_DURATION_MS = 7L * 60_000L  // 7 minutes (matches Kotlin AiDex warmup gate)
        private const val FIRST_VALID_READING_WAIT_MAX_MS = 60L * 60_000L  // Total wait window for the first usable reading
        private const val POST_RESET_WAITING_STATUS = "Waiting for first valid reading"
        private const val CONNECTED_BROADCAST_FALLBACK_STATUS = "Connected (broadcast fallback)"
        private const val SETUP_DISCONNECT_BROADCAST_FALLBACK_THRESHOLD = 3
        private const val DISCOVERY_FAILURE_BROADCAST_FALLBACK_THRESHOLD = 3
        // A link that never gets past GATT_CONNECTING is counted by neither of the two above:
        // shouldCountSetupDisconnectForBroadcastFallback only sees DISCOVERING_SERVICES, CCCD_CHAIN
        // and KEY_EXCHANGE, and consecutiveDiscoveryFailures needs discovery to have started. That
        // was survivable while broadcast-only was terminal; once maybeLeaveBroadcastOnlyFallback
        // started handing the sensor back to the direct path, a one-way exit strands it there with
        // no readings at all. Higher than the two above because a failed connect is also an
        // ordinary one-off, and each attempt already costs an escalating backoff.
        private const val CONNECT_FAILURE_BROADCAST_FALLBACK_THRESHOLD = 6
        // Hold time for the automatic fallbacks that arm a timed return — the ones that enter
        // through enterBroadcastOnlyFallback() with isUnpaired false. The two authorization
        // fallbacks (status 5, and the BOND_NONE branch of bonded()) stamp 0L on purpose and
        // never come back on a timer; the user's own broadcast-only choice and the unpair entries
        // that come through enterBroadcastOnlyFallback() stamp 0L too. handleDeleteBondResponse
        // sets isBroadcastOnlyMode directly and stamps nothing — its isUnpaired gate in
        // maybeLeaveBroadcastOnlyFallback() is what keeps that entry out of the timed return.
        private const val BROADCAST_FALLBACK_RETRY_MS = 10L * 60_000L
        private const val BROADCAST_FALLBACK_LIVE_TIMEOUT_MS = 90_000L
        private const val LIVE_HISTORY_CONTINUITY_BUCKET_MS = 60_000L
        private const val LIVE_HISTORY_CONTINUITY_MAX_MISSING_BUCKETS = 10
        private const val BROADCAST_ASSIST_SCAN_DELAY_MS = 15_000L
        private const val BROADCAST_ASSIST_SCAN_WINDOW_MS = 12_000L
        private const val BROADCAST_ASSIST_SETUP_STALL_MS = 45_000L
        // -- Broadcast Scan --
        private const val BROADCAST_SCAN_WINDOW_MS = 12_000L  // Healthy steady-state scan window
        private const val BROADCAST_RECOVERY_SCAN_WINDOW_MS = 30_000L  // Larger window while recovering broadcasts
        private const val BROADCAST_SCAN_INTERVAL_MS = 60_000L  // Time between scans (fallback)
        private const val BROADCAST_DUPLICATE_SUPPRESS_MS = 70_000L
        private const val BROADCAST_SCAN_ALARM_MIN_DELAY_MS = 10_000L
        private const val BROADCAST_REJECTION_LOG_INTERVAL_MS = 60_000L
        private const val BROADCAST_REJECTION_INITIAL_LOGS = 3

        // -- Phase-locked broadcast scan --
        // After we catch one broadcast we know roughly when the next will arrive.
        // Anchor a tight scan window to (lastBroadcastTime + observedCadence - PRE_OPEN);
        // this gives ~per-minute reliability while keeping radio-on time low (≈18% / 60s
        // → ≥82% deep sleep) instead of the 50% duty of the wide recovery scan.
        private const val DEFAULT_BROADCAST_CADENCE_MS = 60_000L
        private const val MIN_BROADCAST_CADENCE_MS = 45_000L
        private const val MAX_BROADCAST_CADENCE_MS = 90_000L
        private const val PHASE_LOCKED_SCAN_WINDOW_MS = 11_000L
        private const val PHASE_LOCKED_PRE_OPEN_MS = 3_000L
        private const val PHASE_LOCK_LOSS_MISS_COUNT = 3

    }

    // -- Protocol Objects --
    private data class ProtocolSession(
        val keyExchange: AiDexKeyExchange,
        val commandBuilder: AiDexCommandBuilder = AiDexCommandBuilder(keyExchange),
    )

    @Volatile private var protocolSession = ProtocolSession(
        AiDexKeyExchange(serial, AiDexPairingMaterialRegistry.find(serial))
    )
    private val keyExchange: AiDexKeyExchange get() = protocolSession.keyExchange
    private val commandBuilder: AiDexCommandBuilder get() = protocolSession.commandBuilder

    // -- Reconnect Strategy --
    val reconnect = AiDexReconnect()

    // -- Connection Phase Tracking --
    enum class Phase {
        IDLE,
        GATT_CONNECTING,
        DISCOVERING_SERVICES,
        CCCD_CHAIN,
        KEY_EXCHANGE,
        STREAMING,
    }

    @Volatile var phase: Phase = Phase.IDLE
        private set
    @Volatile private var phaseStartedAtMs: Long = 0L

    // -- Handler --
    private val handlerThread = HandlerThread("AiDex-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * Hand a state mutation to the thread that owns it.
     *
     * gattQueue/cccdQueue are plain ArrayDeques walked by drainGattQueue and writeNextCccd on
     * [handlerThread] — which is why enqueueGattOp and the GATT callbacks post there. The
     * connection callbacks arrive on a binder thread and the bond/adapter broadcasts on main;
     * clearing a deque from there while the handler is between isEmpty() and removeFirst()
     * throws NoSuchElementException on a looper with no uncaught-exception handler, which kills
     * the whole process, and a clear() racing add() silently corrupts head/tail instead.
     * Runs inline when the caller already owns the thread, so ordering inside a handler runnable
     * is unchanged.
     */
    private fun runOnHandler(block: () -> Unit) {
        if (Thread.currentThread() === handlerThread) block() else handler.post(block)
    }

    private fun dispatchF002Response(data: ByteArray, gatt: BluetoothGatt) {
        val copy = data.copyOf()
        peekCommandAcksOnBinder(copy)
        handler.post { handleF002Response(copy, gatt) }
    }

    /**
     * Latch a 0x20 refusal, and claim a 0xF3 status 0x01, on the binder before the F002 hop.
     * Disconnect wipe [resetConnectionRuntimeState] starts with
     * [Handler.removeCallbacksAndMessages], which would otherwise drop the posted
     * [handleNewSensorAck] or [handleClearStorageResponse]: a refused 0x20 would stay committed,
     * and a confirmed erase — the sensor reboots right after it, so that disconnect is the
     * expected next event — would be abandoned. Only the verdict is taken here; the bookkeeping
     * runs on the handler ([consumeBinderPeeksOnHandler]).
     */
    private fun peekCommandAcksOnBinder(data: ByteArray) {
        if (data.isEmpty()) return
        if (!keyExchange.isComplete) return
        val plaintext = keyExchange.decrypt(data) ?: return
        if (plaintext.isEmpty()) return
        if (AiDexRuntimePolicy.isSetNewSensorNack(plaintext)) {
            setNewSensorNackSeen = true
        }
        if (AiDexRuntimePolicy.isClearStorageAccepted(plaintext)) {
            claimClearStorageConfirmation("binder-peek")
        }
    }

    /** Handler only: settle what the binder peek latched, after a wipe may have dropped the frame. */
    private fun consumeBinderPeeksOnHandler() {
        consumePendingSetNewSensorNackOnHandler()
        commitConfirmedClearStorageIfOwed()
    }

    private fun isHistoryQueryOpcode(opcode: Int): Boolean {
        return opcode == AiDexOpcodes.GET_HISTORY_RANGE ||
            opcode == AiDexOpcodes.GET_HISTORIES_RAW ||
            opcode == AiDexOpcodes.GET_HISTORIES
    }

    private fun dropQueuedHistoryQueries() {
        gattQueue.removeAll { op -> op is GattOp.Write && isHistoryQueryOpcode(op.opcode) }
    }

    private fun clearUncommittedSetNewSensorAttempt(reason: String) {
        if (setNewSensorWriteDispatchedThisAttempt) return
        setNewSensorWriteQueuedThisConnection = false
        setNewSensorAttemptInProgress = false
        setNewSensorWriteDispatchedThisAttempt = false
        Log.i(TAG, "startNewSensor: never-on-air — latch cleared ($reason)")
        rearmInitialHistoryAfterSetNewSensorOutcome()
    }

    private fun captureSetNewSensorSnapshot() {
        setNewSensorSnapRawNext = historyRawNextIndex
        setNewSensorSnapBriefNext = historyBriefNextIndex
        setNewSensorSnapBarrierAtMs = postResetRequestedAtMs
        setNewSensorSnapLiveCutoff = liveOffsetCutoff
        setNewSensorSnapLastDirectLiveMs = lastDirectLiveReadingTimeMs
        setNewSensorSnapAuthoritativeStart = hasAuthoritativeSessionStart
        setNewSensorSnapFirstValidAnchorMs = firstValidReadingAnchorMs
        setNewSensorSnapCalibratedCache.clear()
        setNewSensorSnapCalibratedCache.putAll(calibratedGlucoseCache)
        setNewSensorSnapCalibratedFallback = lastCalibratedGlucoseFallback
        setNewSensorSnapshotValid = true
    }

    private fun commitSetNewSensorLocalSession() {
        captureSetNewSensorSnapshot()
        val now = System.currentTimeMillis()
        setNewSensorDispatchedAtMs = now
        hasAuthoritativeSessionStart = false
        if (postResetRequestedAtMs == 0L) {
            armPostResetHistoryBarrier("start-new-sensor", now)
        }
        armFirstValidReadingWait(now, "start-new-sensor")
        tk.glucodata.HistorySyncAccess.markSensorReset(SerialNumber)
        historyRawNextIndex = 0
        historyBriefNextIndex = 0
        writeIntPref("historyRawNextIndex", 0)
        writeIntPref("historyBriefNextIndex", 0)
        liveOffsetCutoff = 0
        lastDirectLiveReadingTimeMs = 0L
        if (pendingRoomHistoryTimestamps.isNotEmpty()) historyRoomBufferDropped = true
        clearPendingRoomHistory("start-new-sensor")
        calibratedGlucoseCache.clear()
        lastCalibratedGlucoseFallback = null
        Log.i(TAG, "startNewSensor: 0x20 dispatched, history indices reset")
    }

    private fun rollbackSetNewSensorLocalSession(reason: String) {
        setNewSensorUnconfirmedAckAtElapsed = 0L
        if (!setNewSensorSnapshotValid) {
            setNewSensorNackSeen = false
            setNewSensorAttemptInProgress = false
            setNewSensorWriteDispatchedThisAttempt = false
            setNewSensorWriteQueuedThisConnection = false
            rearmInitialHistoryAfterSetNewSensorOutcome()
            return
        }
        historyRawNextIndex = setNewSensorSnapRawNext
        historyBriefNextIndex = setNewSensorSnapBriefNext
        writeIntPref("historyRawNextIndex", historyRawNextIndex)
        writeIntPref("historyBriefNextIndex", historyBriefNextIndex)
        postResetRequestedAtMs = setNewSensorSnapBarrierAtMs
        writeLongPref("postResetRequestedAtMs", postResetRequestedAtMs)
        liveOffsetCutoff = setNewSensorSnapLiveCutoff
        lastDirectLiveReadingTimeMs = setNewSensorSnapLastDirectLiveMs
        hasAuthoritativeSessionStart = setNewSensorSnapAuthoritativeStart
        firstValidReadingAnchorMs = setNewSensorSnapFirstValidAnchorMs
        writeLongPref("firstValidReadingAnchorMs", firstValidReadingAnchorMs)
        calibratedGlucoseCache.clear()
        calibratedGlucoseCache.putAll(setNewSensorSnapCalibratedCache)
        lastCalibratedGlucoseFallback = setNewSensorSnapCalibratedFallback
        setNewSensorSnapCalibratedCache.clear()
        setNewSensorSnapCalibratedFallback = null
        setNewSensorSnapshotValid = false
        setNewSensorNackSeen = false
        setNewSensorAttemptInProgress = false
        setNewSensorWriteDispatchedThisAttempt = false
        setNewSensorWriteQueuedThisConnection = false
        Log.i(TAG, "startNewSensor: rolled back local session ($reason)")
        rearmInitialHistoryAfterSetNewSensorOutcome()
    }

    private fun rearmInitialHistoryAfterSetNewSensorOutcome() {
        pendingInitialHistoryRequest = true
        handler.removeCallbacks(delayedInitialHistoryRequest)
        handler.postDelayed(delayedInitialHistoryRequest, INITIAL_HISTORY_REQUEST_DELAY_MS)
    }

    private fun bumpHistoryResponseGeneration() {
        historyResponseGeneration += 1
        historyRangeAcceptedThisGeneration = false
        historyRangeQuietUntilElapsed =
            SystemClock.elapsedRealtime() + HISTORY_PAGE_TIMEOUT_MS + HISTORY_STALE_RESPONSE_GRACE_MS
    }

    /** New GATT session: drop in-flight 0x22/0x23/0x24 identity without arming the Start quiet window. */
    private fun invalidateStaleHistoryResponses(reason: String) {
        historyResponseGeneration += 1
        historyRangeAcceptedThisGeneration = false
        historyRangeQuietUntilElapsed = 0L
        Log.i(TAG, "History generation invalidated ($reason) gen=$historyResponseGeneration")
    }

    private fun historyRangeQuietActive(): Boolean {
        return SystemClock.elapsedRealtime() < historyRangeQuietUntilElapsed
    }

    private fun consumePendingSetNewSensorNackOnHandler() {
        val fate = AiDexRuntimePolicy.setNewSensorLocalFate(
            dispatched = setNewSensorWriteDispatchedThisAttempt,
            nackSeen = setNewSensorNackSeen,
        )
        when (fate) {
            AiDexRuntimePolicy.SetNewSensorLocalFate.ROLLBACK_SENSOR_NACK ->
                rollbackSetNewSensorLocalSession("sensor-nack")
            AiDexRuntimePolicy.SetNewSensorLocalFate.ROLLBACK_NEVER_ON_AIR ->
                clearUncommittedSetNewSensorAttempt("runtime-reset")
            AiDexRuntimePolicy.SetNewSensorLocalFate.KEEP_DISPATCHED_UNCONFIRMED -> {
                setNewSensorAttemptInProgress = false
            }
        }
    }

    /**
     * The teardown every stop path shares, on the AiDex looper: wipe the handler, then settle what
     * the binder peeked ([consumeBinderPeeksOnHandler]), then release the CLEAR_STORAGE quiet
     * window — which abandons a reset the sensor has not confirmed. Wipe first, same order as
     * [resetConnectionRuntimeState]: the wipe drops a posted [handleNewSensorAck], and the peek
     * still decides that 0x20's fate.
     *
     * From another thread this waits up to 3s. A handler busy for longer must not wipe later: by
     * then the caller has returned and posted its own follow-ups (rePairSensor's un-pause,
     * setBroadcastOnlyConnection's scan), and the next link has armed its watchdogs. So on a
     * timeout the caller cancels its queued runnable through its own [AiDexHandlerTicket] and wipes
     * now, before it returns; the consume and the release follow as a front-of-queue tail that does
     * not wipe. If the runnable already started, the caller waits for it instead. A forgotten
     * manager keeps the late wipe: forgetVendor()/onTerminalFree() rely on it to drop queued work —
     * a queued 0xF3 among it — before quitSafely() delivers it. The consume and the release run
     * exactly once either way.
     */
    private fun teardownOnHandler(reason: String) {
        val tail = {
            consumeBinderPeeksOnHandler()
            releaseClearStorageQuietWindow(reason)
        }
        if (Thread.currentThread() === handlerThread) {
            handler.removeCallbacksAndMessages(null)
            tail()
            return
        }
        if (!handlerThread.isAlive) {
            Log.w(TAG, "handler teardown skipped — looper dead ($reason)")
            return
        }
        val ticket = AiDexHandlerTicket()
        val done = java.util.concurrent.CountDownLatch(1)
        val teardown = Runnable {
            if (!ticket.tryStart()) return@Runnable
            try {
                handler.removeCallbacksAndMessages(null)
                tail()
            } finally {
                done.countDown()
            }
        }
        if (!handler.postAtFrontOfQueue(teardown)) {
            Log.w(TAG, "handler teardown not posted ($reason)")
            return
        }
        if (done.await(HANDLER_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) return
        if (forgotten) {
            Log.w(TAG, "handler teardown still pending ($reason) — left queued for the forgotten manager")
            return
        }
        if (ticket.tryCancel()) {
            Log.w(TAG, "handler teardown timed out ($reason) — wiping from the caller, consume and release re-posted")
            handler.removeCallbacksAndMessages(null)
            handler.postAtFrontOfQueue { tail() }
        } else if (!done.await(HANDLER_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "handler teardown started but still running ($reason)")
        }
    }

    private fun consumeThenClearSetNewSensorConnectionFlags() {
        consumeBinderPeeksOnHandler()
        setNewSensorUnconfirmedAckAtElapsed = 0L
        setNewSensorWriteQueuedThisConnection = false
        setNewSensorAttemptInProgress = false
        setNewSensorNackSeen = false
        setNewSensorSnapshotValid = false
        setNewSensorWriteDispatchedThisAttempt = false
    }

    private fun noteSetNewSensorDispatched() {
        if (forgotten) {
            noteActivationWriteDelivered("write-dispatched-forgotten")
            return
        }
        noteActivationWriteDelivered("write-dispatched")
        if (setNewSensorNackSeen) {
            rollbackSetNewSensorLocalSession("nack-before-commit")
            return
        }
        commitSetNewSensorLocalSession()
        setNewSensorWriteDispatchedThisAttempt = true
    }

    // -- GATT Queue --
    private sealed class GattOp {
        var retryCount: Int = 0
        /**
         * [opcode] is the plaintext F002 command byte behind the encrypted [data]. The frame
         * itself is opaque once encrypted, so this is the only way a completion path can tell a
         * read-only query from a command that changes sensor state. Only the queries listed in
         * [AiDexRuntimePolicy.isRetryableF002Query] are ever re-sent, so leaving it unset is the
         * safe default for a new write.
         */
        data class Write(val charUuid: UUID, val data: ByteArray, val opcode: Int = 0) : GattOp()
        data class Read(val charUuid: UUID, val serviceUuid: UUID = SERVICE_F000) : GattOp()
    }

    private val gattQueue = ArrayDeque<GattOp>()
    @Volatile private var gattOpActive = false
    @Volatile private var queuePausedForBonding = false
    private var currentGattOp: GattOp? = null  // Tracks active op for watchdog retry

    /** Watchdog fires when a GATT operation callback never arrives within GATT_OP_TIMEOUT_MS. */
    private val gattOpWatchdog = Runnable {
        if (!gattOpActive) return@Runnable
        val op = currentGattOp
        Log.e(TAG, "GATT operation watchdog FIRED — no callback received in ${GATT_OP_TIMEOUT_MS}ms for $op")
        gattOpActive = false
        currentGattOp = null
        if (pendingResetReconnect && clearStorageQuietWindowActive) {
            resetDiag("pre-f3-active-op-watchdog-dropped", "op=${describeGattOp(op)}")
            Log.w(TAG, "GATT watchdog: dropping pre-reset operation so exclusive CLEAR_STORAGE can proceed")
            drainGattQueue()
        } else when (op) {
            is GattOp.Write -> failGattWrite(op, "no write callback in ${GATT_OP_TIMEOUT_MS}ms", callbackReceived = false)
            is GattOp.Read -> {
                // Reads carry no side effect, so re-issuing one is free.
                if (op.retryCount < GATT_OP_WATCHDOG_RETRIES) {
                    op.retryCount++
                    Log.w(TAG, "GATT watchdog: retrying read (attempt ${op.retryCount}/$GATT_OP_WATCHDOG_RETRIES)")
                    gattQueue.addFirst(op)
                } else {
                    Log.e(TAG, "GATT watchdog: read retries exhausted — dropping")
                }
                drainGattQueue()
            }
            null -> {
                Log.e(TAG, "GATT watchdog: no active op — dropping")
                drainGattQueue()
            }
        }
    }

    // -- CCCD State --
    @Volatile private var servicesReady = false
    /** Set by forgetVendor(): the sensor was removed, and this instance must never reconnect or scan. */
    @Volatile private var forgotten = false
    /**
     * Set by [suppressPostUnpairBroadcastScan] before a delete-with-unbind. The ACK must not
     * start a broadcast scan that [forgetVendor] will stop before the platform has registered it.
     */
    @Volatile private var postUnpairBroadcastScanSuppressed = false
    /**
     * Pairs the ACK's write of [AiDexReconnect.isBroadcastOnlyMode] with the release that reads it.
     * The ACK runs on the handler. Release runs on the disconnect coroutine's IO thread.
     * The field is not volatile.
     */
    private val postUnpairScanLock = Any()
    /**
     * Set once [beginServiceDiscovery] has called `discoverServices()` on this link.
     *
     * Besides keeping that call idempotent, this is how [mtuCallbackTimeout] tells "onMtuChanged
     * never arrived" from "it arrived while the timer was already running" — that timer drops the
     * link, it never discovers (see [MTU_CALLBACK_TIMEOUT_MS]). Both flags are written from the
     * binder callbacks and read on the handler, hence @Volatile.
     */
    @Volatile private var serviceDiscoveryStarted = false
    /**
     * Wall time of the most recent `onMtuChanged` on this connection, ours or the sensor's.
     * Written from the binder callback, read on the handler by [scheduleCccdChainStart].
     */
    @Volatile private var lastMtuCallbackAtMs = 0L
    /** Set when an `onMtuChanged` lands on top of the pending CCCD write; see the watchdog. */
    @Volatile private var mtuExchangeCrossedPendingCccd = false
    /** F002 BOND reads on this connection that came back short (not 17 bytes). */
    private var shortBondReads = 0
    private var cccdQueue = ArrayDeque<UUID>() // Characteristics to enable notifications on
    private var cccdWriteInProgress = false
    /**
     * Counts the CCCD write attempts [writeNextCccd] has made, refused ones included.
     * [onMtuChanged] snapshots it on the binder when the callback is delivered, so that
     * [onLateMtuExchange], which runs later on the handler, can tell a write issued after that
     * delivery from one that may have been on the air when the exchange ran. A write issued
     * between the exchange and the delivery still counts as crossed; that is the safe side.
     */
    @Volatile private var cccdWriteSeq = 0L
    private var cccdChainComplete = false
    private var cccdPendingWriteUuid: UUID? = null
    private var cccdMissingCallbackRetries = 0
    private var pendingBondedCccdUuid: UUID? = null

    // -- Key Exchange State --
    private var challengeWritten = false
    private var pairingKeyProblemStatus: String? = null
    private var bondDataRead = false
    /** Set when the sensor rejected the post-BOND config write (onCharacteristicWrite status). */
    private var postBondConfigWriteRejected = false
    private var postBondConfigDeferrals = 0
    /** Settles spent sitting out the bonding pause. Separate budget — see [postBondConfigSettle]. */
    private var postBondConfigBondingWaits = 0
    /**
     * Rejections on the current bond, counted across connections. Cleared by enterStreamingPhase()
     * only when this handshake actually confirmed the write (see [postBondConfigUnconfirmed]), and
     * by the CONNECTED branch when the device is no longer bonded (the bond it counted against is
     * gone).
     */
    private var postBondConfigRejections = 0
    /**
     * Set when [postBondConfigSettle] gives up on a write that never left the queue and enters
     * streaming anyway. That entry proves nothing about the write, and once the phase leaves
     * KEY_EXCHANGE its outcome stops being recorded at all — its only recorder,
     * onCharacteristicWrite, is gated on that phase and the settle is no longer scheduled — so
     * enterStreamingPhase() must not read it as proof and clear [postBondConfigRejections].
     * Cleared by [sendPostBondConfig] with the other three, so it never outlives its handshake.
     */
    private var postBondConfigUnconfirmed = false
    private var keyExchangePendingBond = false
    private var bondStateAtConnection: Int = BluetoothDevice.BOND_NONE
    private var bondBecameBondedThisConnection = false
    @Volatile private var bondValidatedByStreaming = false
    @Volatile private var persistedPairKey: ByteArray? = null
    private var keyExchangeUsingSavedPairKey = false
    private var pairKeyAwaitingLiveValidation = false
    /**
     * Consecutive key-exchange failures on this credential path; cleared by a valid live frame.
     * Persisted: a process death mid-retry (Samsung's OOM killer, a crash loop) used to reset
     * this to zero, so a dead saved key got three fresh tries per process and never latched.
     */
    private var keyExchangeFailures = 0
        set(value) {
            field = value
            writeIntPref("keyExchangeFailures", value)
        }
    /**
     * Latched once saved-key reconnects hit [KEY_EXCHANGE_MAX_FAILURES]; cleared by a valid
     * live frame. Persisted for the same reason as [keyExchangeFailures]: the Pair button and
     * the bonded auto-replace both key off it, and both must survive a manager recreation.
     */
    private var savedKeyExhausted = false
        set(value) {
            field = value
            writeBoolPref("savedKeyExhausted", value)
        }
    /**
     * Set by [rePairSensor]: the user pressed Pair. A saved key that has been exhausted may
     * then be replaced by a fresh F001 exchange — the only route off a dead credential. Held
     * across the bounded retries and cleared by a valid live frame, by giving up into
     * broadcast-only, or by removing the sensor.
     */
    @Volatile private var explicitPairRequested = false
    /**
     * Set when the sensor acknowledges CLEAR_STORAGE: the reset wipes its bond and PAIR
     * credential, so the next exchange pairs fresh over F001 instead of retrying a dead key.
     * The saved key is kept, not deleted — it is replaced only by a fresh key that yields a valid
     * live reading (sentinel frames during warm-up do not count), and restored if the fresh pair
     * keeps failing. Persisted: the reset is
     * followed by a disconnect, and the manager may be recreated before the next connect.
     * Every change, either way, starts [postResetFreshPairConnections] afresh.
     */
    @Volatile private var pairKeyResetPending = false
        set(value) {
            field = value
            writeBoolPref("pairKeyResetPending", value)
            postResetFreshPairConnections = 0
        }
    /**
     * Connections that set out to pair fresh because of [pairKeyResetPending]. Bounds the fresh
     * pair where [handleKeyExchangeFailure] never runs ([notePostResetFreshPairConnection]).
     */
    @Volatile private var postResetFreshPairConnections = 0
        set(value) {
            field = value
            writeIntPref("postResetFreshPairConnections", value)
        }
    /** The running exchange is the fresh pair started by [pairKeyResetPending] over a saved key. */
    @Volatile private var keyExchangeIsFreshPairAfterReset = false
    private var preAuthEncryptedFrameCount = 0
    private var preAuthFirstEncryptedFrameAtMs = 0L
    private var preAuthLastEncryptedFrameAtMs = 0L
    @Volatile private var consecutiveInvalidSetupRecoveries = 0

    private enum class PendingInvalidSetupRecovery {
        NONE,
        RECONNECT,
    }

    private enum class PostCccdFollowUp {
        NONE,
        ENTER_STREAMING,
        RESUME_STREAMING,
    }

    private enum class StartupControlStage {
        IDLE,
        WAIT_DYNAMIC_ADV_ACK,
        WAIT_AUTO_UPDATE_ACK,
        COMPLETE,
        FAILED,
    }

    @Volatile private var postCccdFollowUp = PostCccdFollowUp.NONE
    @Volatile private var startupControlStage = StartupControlStage.IDLE
    @Volatile private var pendingInvalidSetupRecovery = PendingInvalidSetupRecovery.NONE
    @Volatile private var pendingStaleConnectionRecovery = false
    @Volatile private var connectAttemptInFlight = false
    private var streamingStartedAtMs: Long = 0L
    private var noStreamConnectedBroadcastAttempted = false
    private var noStreamRecoveryAttempted = false
    private var noStreamHistoryRecoveryAttempted = false
    private var noStreamFallbackReadingObservedAtMs: Long = 0L
    private var postBondLiveRefreshAttempted = false
    private var pendingInitialHistoryRequest = false
    private var pendingDefaultParamAutoProvisioning = false
    private var pendingDefaultParamAutoProvisioningReason: String? = null
    private var pendingDefaultParamAutoProvisioningScheduled = false
    private var defaultParamAutoProvisioningAttemptedThisConnection = false
    private var pendingStreamingMetadataRead = false
    private var pendingStreamingMetadataReason: String? = null
    private var pendingStreamingMetadataScheduled = false
    private var pendingCalibrationRefresh = false
    private var pendingCalibrationRefreshReason: String? = null
    private var pendingCalibrationRefreshScheduled = false
    private var lastF002FrameTimeMs: Long = 0L
    private var negotiatedMtu: Int = 23
    private var defaultParamProbeTotalWords = 0
    private var defaultParamProbeRawBuffer: ByteArray? = null
    @Volatile private var lastDefaultParamRawHex: String? = null
    @Volatile private var lastDefaultParamDiagnostics: AiDexDefaultParamProvisioning.Diagnostics? = null
    private var defaultParamProbeUserInitiated = false
    private var defaultParamAutoProvisioning = false
    private var pendingDefaultParamApplyAfterProbe = false
    private var defaultParamApplyVerifying = false
    private var startupDeviceInfoRequested = false
    private var legacyStartTimeRequested = false

    private data class DefaultParamApplyState(
        val plan: AiDexDefaultParamProvisioning.ApplyPlan,
        var nextChunkIndex: Int = 0,
    )

    private var defaultParamApplyState: DefaultParamApplyState? = null

    private val defaultParamWriteAckWatchdog = Runnable {
        val state = defaultParamApplyState ?: return@Runnable
        Log.e(
            TAG,
            "Default param write ACK timeout after ${DEFAULT_PARAM_WRITE_ACK_TIMEOUT_MS}ms " +
                "(chunk ${state.nextChunkIndex + 1}/${state.plan.chunks.size})"
        )
        abortDefaultParamApply(
            reason = "ack-timeout",
            statusMessage = "DP apply timed out",
        )
    }

    /** Watchdog: force-disconnect if key exchange doesn't complete within timeout. */
    private val keyExchangeWatchdog = Runnable {
        if (phase == Phase.KEY_EXCHANGE) {
            Log.e(TAG, "Key exchange watchdog FIRED — timeout after ${KEY_EXCHANGE_TIMEOUT_MS}ms")
            handleKeyExchangeFailure("key-exchange-timeout")
        }
    }

    /**
     * The handshake counts as done only once the post-BOND config write has really left the GATT
     * queue. Entering streaming on a bare timer declared "Connected", cancelled
     * [keyExchangeWatchdog] and reset the reconnect/auth and invalid-setup counters while that
     * write could still be queued, paused for bonding or retrying after a false
     * writeCharacteristic() — the failure then surfaced only ~80s later as "no F003". Every wait
     * here is bounded and ends in this runnable as long as the handshake reaches
     * [sendPostBondConfig] in time: the budgets below start there, while [keyExchangeWatchdog] has
     * been running since [startKeyExchange], so a bond slow enough to eat that head start still
     * ends in the watchdog. The deferrals run out into streaming; a rejected write or a bonding
     * pause over budget drops the link and retries the handshake on a fresh one — until the
     * rejections reach [POST_BOND_CONFIG_MAX_REJECTIONS], where the retry is handed to
     * [recoverFromInvalidSetupState], which does spend [consecutiveInvalidSetupRecoveries] and can
     * remove the bond.
     */
    private val postBondConfigSettle: Runnable = Runnable {
        if (phase != Phase.KEY_EXCHANGE) return@Runnable
        if (postBondConfigWriteRejected) {
            // Returning here parked the link in KEY_EXCHANGE until keyExchangeWatchdog fired 35s
            // later, and that escalation removes the user's bond on its second strike — far too
            // much weight for one bad ATT status. Reconnect instead: it touches neither the bond
            // nor consecutiveInvalidSetupRecoveries, and the CONNECTED branch resets the whole key
            // exchange, so the handshake is retried cleanly. Only a bond that keeps failing gets
            // handed to the path that is allowed to change the pairing.
            postBondConfigRejections += 1
            if (postBondConfigRejections >= POST_BOND_CONFIG_MAX_REJECTIONS) {
                Log.e(
                    TAG,
                    "Key exchange: post-BOND config write rejected $postBondConfigRejections times " +
                        "since the last confirmed handshake on this bond — escalating to invalid-setup recovery"
                )
                recoverFromInvalidSetupState("post-bond-config-rejected x$postBondConfigRejections")
            } else {
                Log.e(
                    TAG,
                    "Key exchange: post-BOND config write was rejected " +
                        "($postBondConfigRejections/$POST_BOND_CONFIG_MAX_REJECTIONS) — reconnecting"
                )
                recoverFromBrokenLink("post-bond-config-rejected")
            }
            return@Runnable
        }
        if (gattOpActive || gattQueue.isNotEmpty() || queuePausedForBonding) {
            if (queuePausedForBonding) {
                // The queue is stopped deliberately while the stack bonds, and the 2s deferral
                // budget would guarantee we give up on a write that was never allowed to leave —
                // and then enter streaming on exactly the unsent write this settle waits for. So
                // sit the pause out on its own budget: BONDING_PAUSE_TIMEOUT_MS of cumulative
                // bonding-pause hold across this handshake, not per pause —
                // postBondConfigBondingWaits is zeroed only by sendPostBondConfig, so a pause
                // re-armed by handleWriteFailure (which re-queues without spending a retry, so the
                // chain has no bound of its own) keeps spending the same budget instead of handing
                // the outcome to keyExchangeWatchdog. Reconnect instead, as the rejected-write
                // branch above does below its rejection limit: that touches neither the bond nor
                // consecutiveInvalidSetupRecoveries. Past the limit the rejected-write branch
                // escalates to recoverFromInvalidSetupState, which counts and can remove the bond;
                // this branch never does.
                postBondConfigBondingWaits += 1
                if (postBondConfigBondingWaits * POST_BOND_CONFIG_SETTLE_MS >= BONDING_PAUSE_TIMEOUT_MS) {
                    Log.e(
                        TAG,
                        "Key exchange: post-BOND config write held by the bonding pause for " +
                            "${postBondConfigBondingWaits * POST_BOND_CONFIG_SETTLE_MS}ms " +
                            "(cumulative this handshake) — reconnecting"
                    )
                    recoverFromBrokenLink("post-bond-config-bonding-pause")
                    return@Runnable
                }
                Log.w(TAG, "Key exchange: post-BOND config write held by the bonding pause — waiting")
                handler.postDelayed(postBondConfigSettle, POST_BOND_CONFIG_SETTLE_MS)
                return@Runnable
            }
            // Bounded on purpose. Deferring for ever hands the escalation to keyExchangeWatchdog,
            // and that path can reach removeBondSafely() — a change to the pairing state, which
            // this fix has no business introducing on a link that is merely slow. After the
            // deferrals run out we enter streaming exactly as before and say so loudly; a write
            // the sensor actually rejected is caught by the branch above, not by this one.
            if (postBondConfigDeferrals < POST_BOND_CONFIG_MAX_DEFERRALS) {
                postBondConfigDeferrals += 1
                Log.w(
                    TAG,
                    "Key exchange: post-BOND config write still pending — deferring streaming entry " +
                        "($postBondConfigDeferrals/$POST_BOND_CONFIG_MAX_DEFERRALS)"
                )
                handler.postDelayed(postBondConfigSettle, POST_BOND_CONFIG_SETTLE_MS)
                return@Runnable
            }
            Log.e(
                TAG,
                "Key exchange: post-BOND config write still unsent after " +
                    "${POST_BOND_CONFIG_MAX_DEFERRALS * POST_BOND_CONFIG_SETTLE_MS}ms — entering streaming anyway"
            )
            // The write is still in the queue and its outcome will never be recorded now: its
            // only recorder, onCharacteristicWrite, is gated on Phase.KEY_EXCHANGE and this
            // settle is not re-armed. Mark the
            // handshake unconfirmed so enterStreamingPhase() does not read this entry as proof
            // that the bond takes the config write.
            postBondConfigUnconfirmed = true
        }
        onKeyExchangeComplete()
    }

    /** Watchdog: connectGatt() must produce a callback within a bounded time. */
    private val connectAttemptWatchdog = Runnable {
        val now = System.currentTimeMillis()
        val phaseAgeMs = (now - phaseStartedAtMs).coerceAtLeast(0L)
        val connectTimeoutMs = reconnect.currentConnectAttemptTimeoutMs()
        if (
            AiDexRuntimePolicy.shouldRecoverFromConnectAttemptStall(
                phase = phase,
                phaseAgeMs = phaseAgeMs,
                connectTimeoutMs = connectTimeoutMs,
            )
        ) {
            // The other half of the connect-failure count. A stalled GATT_CONNECTING that never
            // gets a callback — the usual way an autoConnect attempt dies in a dead RF spot — ends
            // here and then in completeStaleConnectionRecovery, which returns before the
            // DISCONNECTED branch's count ever runs. Counted in the same counter, so the two kinds
            // of failure add up to one threshold; CONNECTED clears it.
            consecutiveConnectFailures += 1
            if (
                consecutiveConnectFailures >= CONNECT_FAILURE_BROADCAST_FALLBACK_THRESHOLD &&
                !stop && !isPaused && !isUnpaired && !reconnect.isBroadcastOnlyMode
            ) {
                Log.w(
                    TAG,
                    "Connect attempt stalled $consecutiveConnectFailures times in a row — entering broadcast-only fallback"
                )
                consecutiveConnectFailures = 0
                recoverFromBrokenLink("connect-attempt-timeout-fallback", scheduleReconnect = false)
                enterBroadcastOnlyFallback(
                    reason = "connect-failure-fallback",
                    statusText = "Broadcast fallback",
                )
                return@Runnable
            }
            recoverFromStaleConnectionState(
                "connect-attempt-timeout age=${phaseAgeMs}ms timeout=${connectTimeoutMs}ms gatt=${mBluetoothGatt != null}"
            )
        }
    }

    /** Watchdog: setup must progress out of DISCOVERING_SERVICES/CCCD_CHAIN within a bounded time. */
    private val setupProgressWatchdog = Runnable {
        val now = System.currentTimeMillis()
        val phaseAgeMs = (now - phaseStartedAtMs).coerceAtLeast(0L)
        val bondState = currentBondState()
        if (
            AiDexRuntimePolicy.shouldRecoverFromSetupStall(
                phase = phase,
                phaseAgeMs = phaseAgeMs,
                bondState = bondState,
                keyExchangePendingBond = keyExchangePendingBond,
                setupTimeoutMs = SETUP_STALL_TIMEOUT_MS,
                bondingTimeoutMs = SETUP_BONDING_STALL_TIMEOUT_MS,
            )
        ) {
            recoverFromInvalidSetupState(
                reason = "setup-stall phase=$phase age=${phaseAgeMs}ms bondState=$bondState pendingBond=$keyExchangePendingBond"
            )
        }
    }

    /** Watchdog: encrypted pre-auth traffic must not persist before key exchange starts. */
    private val preAuthEncryptedTrafficWatchdog = Runnable {
        val now = System.currentTimeMillis()
        val bondState = currentBondState()
        if (
            AiDexRuntimePolicy.shouldRecoverFromPreAuthEncryptedTraffic(
                phase = phase,
                bondState = bondState,
                keyExchangePendingBond = keyExchangePendingBond,
                encryptedFrameCount = preAuthEncryptedFrameCount,
                firstEncryptedFrameAtMs = preAuthFirstEncryptedFrameAtMs,
                nowMs = now,
                minFrames = PRE_AUTH_ENCRYPTED_TRAFFIC_MIN_FRAMES,
                timeoutMs = PRE_AUTH_ENCRYPTED_TRAFFIC_TIMEOUT_MS,
            )
        ) {
            val trafficAgeMs = (now - preAuthFirstEncryptedFrameAtMs).coerceAtLeast(0L)
            val gatt = mBluetoothGatt
            if (
                gatt != null &&
                AiDexRuntimePolicy.shouldAdvanceBondedReconnectToKeyExchange(
                    phase = phase,
                    bondState = bondState,
                    keyExchangePendingBond = keyExchangePendingBond,
                    cccdQueueEmpty = cccdQueue.isEmpty(),
                    cccdWriteInProgress = cccdWriteInProgress,
                    cccdChainComplete = cccdChainComplete,
                    challengeWritten = challengeWritten,
                    bondDataRead = bondDataRead,
                )
            ) {
                advanceBondedReconnectToKeyExchange(gatt, trafficAgeMs)
                return@Runnable
            }
            recoverFromInvalidSetupState(
                reason = "pre-auth-encrypted-traffic phase=$phase frames=$preAuthEncryptedFrameCount age=${trafficAgeMs}ms bondState=$bondState"
            )
        }
    }

    /**
     * Deadline for `onMtuChanged`. Discovery is deliberately NOT started here: doing it over an
     * in-flight MTU exchange is what wedges the link (see [MTU_CALLBACK_TIMEOUT_MS]), so the only
     * move left is to drop this link and come back with a fresh one.
     */
    private val mtuCallbackTimeout = Runnable {
        if (mBluetoothGatt == null) return@Runnable
        // onMtuChanged cancels this timer from a binder thread, and removeCallbacks() cannot
        // cancel a runnable the handler has already entered. Without this check a link whose
        // callback landed on the deadline — and which is discovering normally — gets torn down
        // here and charged to consecutiveDiscoveryFailures, three of which mean broadcast-only.
        // Once discovery is running, scheduleDiscoveryRetries() owns the deadline instead.
        if (serviceDiscoveryStarted || servicesReady) return@Runnable
        Log.w(TAG, "No onMtuChanged within ${MTU_CALLBACK_TIMEOUT_MS}ms — dropping the link instead of discovering over an outstanding MTU exchange")
        recoverFromServiceDiscoveryFailure()
    }

    /** Deferred first CCCD write; runs once the MTU bearer has been quiet long enough. */
    private val cccdChainStartAfterMtuSettle: Runnable = Runnable {
        val gatt = mBluetoothGatt ?: return@Runnable
        if (phase != Phase.CCCD_CHAIN || cccdWriteInProgress || cccdQueue.isEmpty()) return@Runnable
        writeNextCccd(gatt)
    }

    /** Watchdog: Android must callback after descriptor writes, but some stacks drop CCCD callbacks. */
    private val cccdWriteWatchdog: Runnable = Runnable {
        val pendingUuid = cccdPendingWriteUuid ?: return@Runnable
        val gatt = mBluetoothGatt ?: return@Runnable
        when (
            AiDexRuntimePolicy.decideMissingCccdCallbackAction(
                cccdWriteInProgress = cccdWriteInProgress,
                hasPendingCccd = true,
                timeoutRetries = cccdMissingCallbackRetries,
                maxRetries = CCCD_WRITE_CALLBACK_MAX_EXTRA_WAITS,
                canInferComplete = canInferMissingCccdCallbackComplete(),
                mtuExchangeCrossedWrite = mtuExchangeCrossedPendingCccd,
            )
        ) {
            AiDexRuntimePolicy.MissingCccdCallbackAction.IGNORE -> Unit
            AiDexRuntimePolicy.MissingCccdCallbackAction.RECOVER_WEDGED_GATT -> {
                Log.w(
                    TAG,
                    "CCCD $pendingUuid descriptor callback missing after ${CCCD_WRITE_CALLBACK_TIMEOUT_MS}ms " +
                        "and an MTU exchange crossed the write — GATT is wedged, reconnecting"
                )
                mtuExchangeCrossedPendingCccd = false
                cccdPendingWriteUuid = null
                cccdWriteInProgress = false
                cccdMissingCallbackRetries = 0
                lastInferredCccdUuid = null
                recoverFromInvalidSetupState("mtu-exchange-crossed-cccd-write")
            }
            AiDexRuntimePolicy.MissingCccdCallbackAction.WAIT -> {
                cccdMissingCallbackRetries += 1
                Log.w(
                    TAG,
                    "CCCD $pendingUuid descriptor callback missing after ${CCCD_WRITE_CALLBACK_TIMEOUT_MS}ms — " +
                        "waiting one more window (${cccdMissingCallbackRetries}/${CCCD_WRITE_CALLBACK_MAX_EXTRA_WAITS})"
                )
                handler.postDelayed(cccdWriteWatchdog, CCCD_WRITE_CALLBACK_TIMEOUT_MS)
            }
            AiDexRuntimePolicy.MissingCccdCallbackAction.ASSUME_COMPLETE -> {
                Log.w(
                    TAG,
                    "CCCD $pendingUuid descriptor callback still missing — " +
                        "assuming write completed and continuing chain"
                )
                finishCccdWrite(
                    gatt = gatt,
                    charUuid = pendingUuid,
                    status = BluetoothGatt.GATT_SUCCESS,
                    inferred = true,
                )
            }
        }
    }

    /** Fallback: if an intentional recovery disconnect never yields a callback, force cleanup anyway. */
    private val invalidSetupRecoveryFallback = Runnable {
        if (pendingInvalidSetupRecovery == PendingInvalidSetupRecovery.NONE) return@Runnable
        Log.w(TAG, "Invalid setup recovery disconnect did not callback — forcing cleanup")
        handlePendingInvalidSetupRecovery()
    }

    /** Fallback: stale runtime recovery requested a disconnect but Android never called back. */
    private val staleConnectionRecoveryFallback = Runnable {
        if (!pendingStaleConnectionRecovery) return@Runnable
        Log.w(TAG, "Stale connection recovery disconnect did not callback — forcing cleanup")
        completeStaleConnectionRecovery("disconnect-timeout", stateAlreadyReset = false)
    }

    /** Fallback when Android does not report the local post-clear disconnect. */
    private val postResetDisconnectFallback = Runnable {
        if (!pendingResetReconnect) return@Runnable
        Log.w(TAG, "Post-reset disconnect did not callback — forcing cleanup")
        resetDiag("disconnect-callback-timeout")
        completePostResetReconnect(
            trigger = "clear-storage-disconnect-timeout",
            stateAlreadyReset = false,
        )
    }

    /** Let CLEAR_STORAGE finish without another sensor command, then reconnect for verification. */
    private val clearStorageQuietWindowReconnect = Runnable {
        val gatt = mBluetoothGatt
        when (
            AiDexRuntimePolicy.decideQuietWindowExpiry(
                forgotten = forgotten,
                resetPending = pendingResetReconnect,
                writeDispatchedAtMs = postResetClearStorageWriteAtMs,
                ackClaimed = postResetClearStorageAckAtMs > 0L,
                hasGatt = gatt != null,
            )
        ) {
            AiDexRuntimePolicy.QuietWindowExpiry.IGNORE -> return@Runnable
            AiDexRuntimePolicy.QuietWindowExpiry.ABANDON_NEVER_DISPATCHED -> {
                // The window is armed the moment resetSensor() latches its flags, so this also
                // fires for a CLEAR_STORAGE that drainGattQueue swallowed (no GATT, characteristic
                // not found, write retries exhausted). Nothing reached the sensor, so completing
                // the post-reset flow here would drop the user's bond and quarantine the sensor's
                // real history for a reset that never happened.
                abandonPendingReset("clear-storage-never-dispatched")
                return@Runnable
            }
            AiDexRuntimePolicy.QuietWindowExpiry.COMPLETE_NO_GATT -> {
                completePostResetReconnect("clear-storage-no-gatt", stateAlreadyReset = false)
                return@Runnable
            }
            AiDexRuntimePolicy.QuietWindowExpiry.LOCAL_DISCONNECT -> Unit
        }
        Log.i(TAG, "CLEAR_STORAGE quiet window complete — disconnecting locally for clean post-reset verification")
        postResetDisconnectRequestedAtMs = System.currentTimeMillis()
        resetDiag("quiet-window-complete-local-disconnect", nowMs = postResetDisconnectRequestedAtMs)
        try {
            gatt?.disconnect()  // non-null: LOCAL_DISCONNECT requires hasGatt
            handler.removeCallbacks(postResetDisconnectFallback)
            handler.postDelayed(postResetDisconnectFallback, STALE_CONNECTION_RECOVERY_FALLBACK_MS)
        } catch (_: Throwable) {
            completePostResetReconnect("clear-storage-disconnect-throw", stateAlreadyReset = false)
        }
    }

    private fun scheduleClearStorageQuietWindow(reason: String, nowMs: Long = System.currentTimeMillis()) {
        handler.removeCallbacks(clearStorageQuietWindowReconnect)
        handler.postDelayed(clearStorageQuietWindowReconnect, CLEAR_STORAGE_QUIET_WINDOW_MS)
        resetDiag(
            stage = "quiet-window-start",
            details = "reason=$reason durationMs=$CLEAR_STORAGE_QUIET_WINDOW_MS",
            nowMs = nowMs,
        )
    }

    /** Watchdog: try bounded startup recovery steps, then reconnect if direct F003 never appears. */
    private val noStreamWatchdog = Runnable {
        mBluetoothGatt ?: return@Runnable
        if (phase != Phase.STREAMING) return@Runnable
        if (streamingStartedAtMs <= 0L || lastF003FrameTimeMs >= streamingStartedAtMs) return@Runnable
        // Not while a reset owns the link: every step would be dropped by the quiet window, and
        // RECONNECT would close() the link under the erase without a release. Deferred, not
        // cancelled, so an abandoned reset on a live link still gets its ladder.
        if (pendingResetReconnect || clearStorageQuietWindowActive) {
            scheduleNoStreamWatchdog()
            return@Runnable
        }
        val now = System.currentTimeMillis()

        when (
            AiDexStreamingPolicy.decideNoStreamRecovery(
                hasRecentBroadcastData = hasRecentBroadcastData(now),
                historyDownloading = historyDownloading,
                allowConnectedBroadcastRequest = shouldAttemptConnectedBroadcastRequest(),
                connectedBroadcastRequestAttempted = noStreamConnectedBroadcastAttempted,
                hasSessionFallbackData = noStreamFallbackReadingObservedAtMs > 0L,
                historyRefreshAttempted = noStreamHistoryRecoveryAttempted,
                liveCccdRefreshAttempted = noStreamRecoveryAttempted,
            )
        ) {
            AiDexStreamingPolicy.NoStreamRecoveryAction.KEEP_WAITING -> {
                if (hasRecentBroadcastData(now)) {
                    Log.i(TAG, "No direct F003 yet, but broadcast fallback is healthy — keeping session alive")
                    scheduleNoStreamWatchdog(EXPECTED_LIVE_INTERVAL_MS + EXPECTED_LIVE_GRACE_MS)
                } else {
                    Log.i(TAG, "No F003 yet, but history download is still running — extending no-stream watchdog")
                    scheduleNoStreamWatchdog()
                }
            }
            AiDexStreamingPolicy.NoStreamRecoveryAction.REQUEST_CONNECTED_BROADCAST -> {
                noStreamConnectedBroadcastAttempted = true
                Log.w(
                    TAG,
                    "No direct F003 yet — requesting connected broadcast data before CCCD recovery"
                )
                requestConnectedBroadcastData("no-stream-recovery")
                scheduleNoStreamWatchdog(CONNECTED_BROADCAST_REQUEST_WAIT_MS)
            }
            AiDexStreamingPolicy.NoStreamRecoveryAction.REQUEST_HISTORY_REFRESH -> {
                noStreamHistoryRecoveryAttempted = true
                Log.w(
                    TAG,
                    "No direct F003, but this session already produced valid fallback data — requesting bounded history refresh before CCCD recovery"
                )
                requestHistoryBackfill()
                scheduleNoStreamWatchdog(EXPECTED_LIVE_INTERVAL_MS + EXPECTED_LIVE_GRACE_MS)
            }
            AiDexStreamingPolicy.NoStreamRecoveryAction.REFRESH_LIVE_CCCDS -> {
                noStreamRecoveryAttempted = true
                Log.w(TAG, "No F003 within ${NO_STREAM_WATCHDOG_MS / 1000}s of streaming start — refreshing F003/F002 CCCDs once")
                refreshLiveCccds(PostCccdFollowUp.RESUME_STREAMING, "no-stream-watchdog")
            }
            AiDexStreamingPolicy.NoStreamRecoveryAction.RECONNECT -> {
                if (keyExchangeUsingSavedPairKey || pairKeyAwaitingLiveValidation) {
                    handleKeyExchangeFailure("no-valid-direct-f003")
                    return@Runnable
                }
                val delay = reconnect.nextReconnectDelayMs()
                Log.w(TAG, "No F003 after bounded no-stream recovery — reconnecting in ${delay}ms")
                constatstatusstr = "Reconnecting"
                setPhase(Phase.IDLE)
                close()
                handler.postDelayed({ connectDevice(0) }, delay)
            }
        }
    }

    private val delayedInitialHistoryRequest = Runnable {
        pendingInitialHistoryRequest = false
        if (phase != Phase.STREAMING || mBluetoothGatt == null) return@Runnable
        Log.i(TAG, "Initial history request starting after live-stream settle")
        requestHistoryRange()
    }

    private var pendingRawPageOffset = 0
    private var pendingRawPageGeneration = 0
    private val delayedRawHistoryPage = Runnable {
        requestHistoryPage(AiDexOpcodes.GET_HISTORIES_RAW, pendingRawPageOffset, pendingRawPageGeneration)
    }
    private var pendingBriefPageOffset = 0
    private var pendingBriefPageGeneration = 0
    private val delayedBriefHistoryPage = Runnable {
        requestHistoryPage(AiDexOpcodes.GET_HISTORIES, pendingBriefPageOffset, pendingBriefPageGeneration)
    }

    private fun scheduleRawHistoryPage(offset: Int) {
        pendingRawPageOffset = offset
        pendingRawPageGeneration = historyResponseGeneration
        handler.removeCallbacks(delayedRawHistoryPage)
        handler.postDelayed(delayedRawHistoryPage, HISTORY_REQUEST_DELAY_MS)
    }

    private fun scheduleBriefHistoryPage(offset: Int) {
        pendingBriefPageOffset = offset
        pendingBriefPageGeneration = historyResponseGeneration
        handler.removeCallbacks(delayedBriefHistoryPage)
        handler.postDelayed(delayedBriefHistoryPage, HISTORY_REQUEST_DELAY_MS)
    }

    private val startupControlAckTimeout = Runnable {
        if (phase != Phase.STREAMING) return@Runnable
        when (startupControlStage) {
            StartupControlStage.WAIT_DYNAMIC_ADV_ACK,
            StartupControlStage.WAIT_AUTO_UPDATE_ACK -> {
                Log.w(
                    TAG,
                    "Streaming startup control stalled at $startupControlStage — falling back to direct connected broadcast request"
                )
                startupControlStage = StartupControlStage.FAILED
                requestConnectedBroadcastData("startup-control-timeout")
            }
            else -> Unit
        }
    }

    private val delayedStreamingMetadataRequest = Runnable {
        pendingStreamingMetadataScheduled = false
        val reason = pendingStreamingMetadataReason ?: "scheduled"
        pendingStreamingMetadataReason = null
        requestStreamingMetadataIfNeeded(reason)
    }

    private val delayedDefaultParamAutoProvisioningRequest = Runnable {
        pendingDefaultParamAutoProvisioningScheduled = false
        val reason = pendingDefaultParamAutoProvisioningReason ?: "scheduled"
        pendingDefaultParamAutoProvisioningReason = null
        requestAutomaticDefaultParamProvisioningIfNeeded(reason)
    }

    private val delayedCalibrationRefreshRequest = Runnable {
        pendingCalibrationRefreshScheduled = false
        val reason = pendingCalibrationRefreshReason ?: "scheduled"
        pendingCalibrationRefreshReason = null
        requestRoutineCalibrationRefreshIfNeeded(reason)
    }

    // -- SharedPreferences for per-sensor state persistence --
    private val prefs by lazy {
        Applic.app.getSharedPreferences("AiDexNativePrefs", Context.MODE_PRIVATE)
    }

    private fun prefKey(name: String): String = "${name}_${SerialNumber}"

    private fun readIntPref(name: String, default: Int): Int {
        val key = prefKey(name)
        return if (prefs.contains(key)) prefs.getInt(key, default) else default
    }

    private fun writeIntPref(name: String, value: Int) {
        prefs.edit().putInt(prefKey(name), value).apply()
    }

    private fun readBoolPref(name: String, default: Boolean): Boolean {
        val key = prefKey(name)
        return if (prefs.contains(key)) prefs.getBoolean(key, default) else default
    }

    private fun writeBoolPref(name: String, value: Boolean) {
        prefs.edit().putBoolean(prefKey(name), value).apply()
    }

    private fun readLongPref(name: String, default: Long): Long {
        val key = prefKey(name)
        return if (prefs.contains(key)) prefs.getLong(key, default) else default
    }

    private fun writeLongPref(name: String, value: Long) {
        prefs.edit().putLong(prefKey(name), value).apply()
    }

    private fun readStringPref(name: String, default: String = ""): String {
        val key = prefKey(name)
        return if (prefs.contains(key)) prefs.getString(key, default) ?: default else default
    }

    private fun writeStringPref(name: String, value: String) {
        prefs.edit().putString(prefKey(name), value).apply()
    }

    // -- History State --
    @Volatile private var historyDownloading = false
    private var historyRawNextIndex = 0
    private var historyBriefNextIndex = 0
    private var historyNewestOffset = 0
    private var historyStoredCount = 0  // entries stored via aidexProcessData this download
    private var historyDownloadStartIndex = 0  // snapshot of starting index for progress display
    private var historyPhase: HistoryPhase = HistoryPhase.IDLE
    // The three below are written by historyPageWatchdog on the handler — the last one also by the
    // teardown and new-connection paths — and read by the 0x23/0x24 handlers on the BLE callback
    // thread: same reason historyDownloading is volatile.
    /** The download stopped on a page timeout, not on a deliberate abort. */
    @Volatile private var historyPageTimedOut = false
    /** One timeout-triggered restart per connection: a sensor losing every page must not be re-asked in a loop. */
    @Volatile private var historyTimeoutRestartUsed = false
    /** One 0x22 retry per connection after CRC/timeout before any range was accepted. */
    @Volatile private var historyRangeRetryUsed = false
    /** Handler only. The empty 0x23 page at the raw cursor has been asked for once more this download. */
    private var emptyRawPageRetried = false
    private var rawPageGapRetried = false
    /**
     * A pending Room batch was dropped after its rows had already reached the native store — by the
     * page watchdog, by a teardown, by a connection arriving without one, by resetSensor, whose
     * CLEAR_STORAGE erases the ring the rows were read from, or by startNewSensor. Nothing can
     * re-request those minutes — the persisted cursors are past them, or the ring itself is gone —
     * so only a full merge can still get them into Room: this is a debt towards Room, not
     * per-connection state, and a new session does not cancel it. Cleared by
     * onHistoryDownloadComplete, which pays it.
     */
    @Volatile private var historyRoomBufferDropped = false
    private val pendingRoomHistoryTimestamps = ArrayList<Long>()
    private val pendingRoomHistoryValues = ArrayList<Float>()
    private val pendingRoomHistoryRawValues = ArrayList<Float>()

    // -- Post-Reset Activation Debt --
    // Owed after this driver saw the sensor answer CLEAR_STORAGE (0xF3) with status 0x01. While it
    // is owed, two automatic SET_NEW_SENSOR (0x20) paths exist, each at most once per connection:
    // an all-zero 0x2AAA read, and a 0x2AAA start older than the barrier minus
    // POST_RESET_START_SLACK_MS (applyParsedSessionStartTime; on M1.8.3 its 0x20 was answered 0x00
    // without effect, after a 0xF3 that firmware had refused).
    // Without the debt, only an all-zero 0x2AAA read on a sensor this app has never seen running
    // sends one (a new sensor; AiDexRuntimePolicy.decideZeroSessionStartActivation, FRESH_SENSOR).
    // No other session start sends a 0x20. Set in exactly one place,
    // commitConfirmedClearStorageIfOwed(), on the handler, after claimClearStorageConfirmation()
    // won — never at the button press, so a process that dies before the sensor answers leaves no
    // debt behind. Persisted under ACTIVATION_DEBT_PREF; the pre-2026-09-23 key was written at the
    // press, cannot tell a confirmed erase from an unanswered one, and is dropped unread in init.
    // abandonPendingReset() never clears it: an abandoned attempt proves nothing about an earlier
    // one the sensor did confirm.
    // Cleared by:
    //   1. noteActivationWriteDelivered — the 0x20 provably left the phone.
    //   2. applyParsedSessionStartTime, when the parsed start is accepted as authoritative, i.e.
    //      AiDexRuntimePolicy.sessionStartPredatesReset() did not rule it out. Deliberately not
    //      gated on allowActivation: the legacy 0x21 sources pass false and a firmware that really
    //      restarted reports its new start there. That rule leans on the barrier, which carries the
    //      press time: handleHistoryRangeResponse leaves it armed while this flag is set.
    // Two clears stay forbidden: where the 0x20 is merely queued, and on the 0x20 ACK (a lost ACK
    // would then re-send an actuator command blindly). Not a bounded wait: firmware that never
    // answers 0x2AAA and reports no usable legacy start keeps the debt, and the barrier with it.
    // Local history rewrite is committed only after writeCharacteristic == true; a lost ACK keeps
    // that rewrite; a sensor NACK restores the snapshot. This flag is never restored on NACK.
    @Volatile private var needsPostResetActivation: Boolean = false
    @Volatile private var postResetWarmupExtensionActive: Boolean = false
    @Volatile private var firstValidReadingAnchorMs: Long = 0L
    @Volatile private var hasAuthoritativeSessionStart: Boolean = false
    @Volatile private var autoActivationAttemptedThisConnection: Boolean = false
    /** A key exchange completed on this instance; never cleared, a link drop does not undo it. */
    @Volatile private var handshakeCompleted: Boolean = false
    @Volatile private var setNewSensorWriteQueuedThisConnection: Boolean = false
    @Volatile private var setNewSensorWriteDispatchedThisAttempt: Boolean = false
    @Volatile private var setNewSensorAttemptInProgress: Boolean = false
    /** elapsedRealtime of a 0x20 answer that neither accepted nor refused it (status 0x01); 0 = none. */
    @Volatile private var setNewSensorUnconfirmedAckAtElapsed: Long = 0L
    /** Wall-clock time the last 0x20 left the phone: its start stamp, for [settleUnconfirmedActivation]. */
    @Volatile private var setNewSensorDispatchedAtMs: Long = 0L
    @Volatile private var setNewSensorNackSeen: Boolean = false
    @Volatile private var setNewSensorSnapshotValid: Boolean = false
    private var setNewSensorSnapRawNext: Int = 0
    private var setNewSensorSnapBriefNext: Int = 0
    private var setNewSensorSnapBarrierAtMs: Long = 0L
    private var setNewSensorSnapLiveCutoff: Int = 0
    private var setNewSensorSnapLastDirectLiveMs: Long = 0L
    private var setNewSensorSnapAuthoritativeStart: Boolean = false
    private var setNewSensorSnapFirstValidAnchorMs: Long = 0L
    private val setNewSensorSnapCalibratedCache = HashMap<Int, Int>()
    private var setNewSensorSnapCalibratedFallback: Int? = null
    private var historyResponseGeneration: Int = 0
    private var issuedHistoryGeneration: Int = 0
    @Volatile private var historyRangeAcceptedThisGeneration: Boolean = false
    @Volatile private var historyRangeQuietUntilElapsed: Long = 0L
    @Volatile private var postResetRequestedAtMs: Long = 0L
    @Volatile private var postResetClearStorageWriteAtMs: Long = 0L
    // "This attempt's 0xF3 got status 0x01" and nothing else: set only by
    // claimClearStorageConfirmation(), under resetClaimLock.
    @Volatile private var postResetClearStorageAckAtMs: Long = 0L
    @Volatile private var postResetDisconnectRequestedAtMs: Long = 0L
    private var postResetDroppedGattOps: Int = 0
    /** Button-press time of the reset in flight; the barrier is armed with it once the sensor confirms. */
    @Volatile private var resetAttemptRequestedAtMs: Long = 0L
    /** A claimed 0x01 whose bookkeeping commitConfirmedClearStorageIfOwed() has not run yet. */
    @Volatile private var clearStorageConfirmOwed: Boolean = false
    /**
     * The confirm (claimClearStorageConfirmation) and the verdict "not confirmed"
     * (abandonPendingReset, completePostResetReconnect, the STATE_CONNECTED latch arm) run on the
     * handler, the binder and the caller's thread. Under this lock exactly one of them wins, so a
     * 0x01 that races the disconnect teardown is either credited or dropped — never both.
     */
    private val resetClaimLock = Any()

    init {
        // Restore persisted history offsets so reconnects only download new data.
        // Matches the vendor driver's Edit 47 approach (SharedPreferences persistence).
        historyRawNextIndex = readIntPref("historyRawNextIndex", 0)
        historyBriefNextIndex = readIntPref("historyBriefNextIndex", 0)
        if (historyRawNextIndex > 0 || historyBriefNextIndex > 0) {
            Log.i(TAG, "Restored history offsets: raw=$historyRawNextIndex, brief=$historyBriefNextIndex")
        }
        // Builds before 2026-09-23 wrote this key when Reset was pressed, before the sensor
        // answered, so a true here cannot be told apart from a reset the sensor never saw. Dropped
        // with its barrier; a sensor that really was erased then needs the user's Start.
        if (prefs.contains(prefKey(LEGACY_ACTIVATION_DEBT_PREF))) {
            if (readBoolPref(LEGACY_ACTIVATION_DEBT_PREF, false)) {
                writeLongPref("postResetRequestedAtMs", 0L)
                Log.w(TAG, "RESET_DIAG stage=legacy-unconfirmed-debt-dropped sensor=$SerialNumber — not auto-activating")
            }
            prefs.edit().remove(prefKey(LEGACY_ACTIVATION_DEBT_PREF)).apply()
        }
        // Builds from 2026-09-23 up to the 0x01 rule wrote this key on a 0xF3 answered 0x00, which is
        // what the 1.8.3 firmware answered while it refused the erase. Such a debt is dropped with its
        // barrier and warmup extension; a sensor that really was erased then needs the user's Start.
        if (prefs.contains(prefKey(PRE_0X01_ACTIVATION_DEBT_PREF))) {
            if (readBoolPref(PRE_0X01_ACTIVATION_DEBT_PREF, false)) {
                writeLongPref("postResetRequestedAtMs", 0L)
                writeBoolPref("postResetWarmupExtensionActive", false)
                Log.w(TAG, "RESET_DIAG stage=pre-0x01-rule-debt-dropped sensor=$SerialNumber — not auto-activating")
            }
            prefs.edit().remove(prefKey(PRE_0X01_ACTIVATION_DEBT_PREF)).apply()
        }
        // Restore post-reset activation debt — survives driver instance recreation
        // (e.g., finish/unfinish sensor cycle that creates a new AiDexBleManager)
        needsPostResetActivation = readBoolPref(ACTIVATION_DEBT_PREF, false)
        if (needsPostResetActivation) {
            Log.i(TAG, "Restored confirmed post-reset activation debt from prefs — will auto-activate on next connect")
        }
        postResetWarmupExtensionActive = readBoolPref("postResetWarmupExtensionActive", false)
        firstValidReadingAnchorMs = readLongPref("firstValidReadingAnchorMs", 0L)
        postResetRequestedAtMs = readLongPref("postResetRequestedAtMs", 0L)
        if (postResetRequestedAtMs > 0L) {
            Log.i(TAG, "Restored post-reset history barrier from prefs: requestedAt=$postResetRequestedAtMs")
        }
        bondValidatedByStreaming = readBoolPref("bondValidatedByStreaming", false)
        if (bondValidatedByStreaming) {
            Log.i(TAG, "Restored bondValidatedByStreaming=true from prefs")
        }
        persistedPairKey = AiDexPairKeyVault.load(Applic.app, SerialNumber)
        if (persistedPairKey != null) {
            keyExchangeFailures = readIntPref("keyExchangeFailures", 0)
            savedKeyExhausted = readBoolPref("savedKeyExhausted", false)
            // Read before the flag is restored: its setter zeroes the counter and its pref.
            val freshPairConnections = readIntPref("postResetFreshPairConnections", 0)
            pairKeyResetPending = readBoolPref("pairKeyResetPending", false)
            postResetFreshPairConnections = if (pairKeyResetPending) freshPairConnections else 0
            Log.i(
                TAG,
                "Restored verified AiDex PAIR credential from redundant storage " +
                    "(fp=${AiDexPairKeyVault.fingerprint(persistedPairKey)}, " +
                    "failures=$keyExchangeFailures, exhausted=$savedKeyExhausted, " +
                    "resetPending=$pairKeyResetPending, freshPairConnections=$postResetFreshPairConnections)"
            )
        } else {
            // No credential, nothing to have exhausted: the counters belong to the key.
            keyExchangeFailures = 0
            savedKeyExhausted = false
            pairKeyResetPending = false
        }
    }

    private enum class HistoryPhase {
        IDLE,
        DOWNLOADING_CALIBRATED,  // 0x23 (calibrated glucose)
        DOWNLOADING_RAW,         // 0x24 (ADC/raw data)
    }

    /**
     * Mirror a skin-temperature sample into the display-only sidecar store.
     *
     * The F003 `i2` channel carries skin temperature in °C (verified against a
     * co-worn Sibionics sensor). Implausible values are dropped by the store;
     * nothing here touches glucose storage or calibration.
     */
    private fun appendSkinTemperature(timestampMs: Long, temperatureC: Float?) {
        if (timestampMs <= 0L || temperatureC == null) return
        if (!AiDexTemperatureStore.isPlausibleSkinTemperatureC(temperatureC)) return
        val context = Applic.app ?: return
        AiDexTemperatureStore.appendTemperatureHistory(
            context,
            SerialNumber,
            listOf(AiDexTemperatureStore.TemperatureRecord(timestampMs, temperatureC)),
        )
    }

    private fun clearPendingRoomHistory(reason: String? = null) {
        // All three, not just the first: a clear that lands between the add() calls in
        // storeHistoryEntries can leave the timestamp list empty while the other two still hold
        // rows, and the short-circuit would then leave those rows to be paired with the next
        // download's timestamps.
        if (
            pendingRoomHistoryTimestamps.isEmpty() &&
            pendingRoomHistoryValues.isEmpty() &&
            pendingRoomHistoryRawValues.isEmpty()
        ) return
        pendingRoomHistoryTimestamps.clear()
        pendingRoomHistoryValues.clear()
        pendingRoomHistoryRawValues.clear()
        if (reason != null) {
            Log.d(TAG, "Cleared pending Room history buffer: $reason")
        }
    }

    private fun flushPendingRoomHistoryToRoom(): Boolean {
        if (pendingRoomHistoryTimestamps.isEmpty()) {
            return true
        }

        // The three buffers are appended one entry at a time on the BLE callback thread while a
        // reset, the adapter-off broadcast or the page watchdog can clear them from another
        // thread. Unequal lengths mean the columns no longer line up, and indexing them anyway
        // would store a glucose value under a timestamp it never belonged to — visible to the
        // user and exported. Drop the batch; the caller falls back to mergeFullSyncForSensor,
        // which rebuilds Room from the native store, where every row was paired at write time.
        val size = pendingRoomHistoryTimestamps.size
        if (pendingRoomHistoryValues.size != size || pendingRoomHistoryRawValues.size != size) {
            Log.e(
                TAG,
                "Pending Room history desynced (ts=$size values=${pendingRoomHistoryValues.size} " +
                    "raw=${pendingRoomHistoryRawValues.size}) — discarding batch for full resync"
            )
            clearPendingRoomHistory("desync")
            return false
        }
        val timestamps = LongArray(size) { index -> pendingRoomHistoryTimestamps[index] }
        val values = FloatArray(size) { index -> pendingRoomHistoryValues[index] }
        val rawValues = FloatArray(size) { index -> pendingRoomHistoryRawValues[index] }
        val stored = tk.glucodata.HistorySyncAccess.storeSensorHistoryBatchBlocking(
            sensorSerial = SerialNumber,
            timestamps = timestamps,
            valuesMgdl = values,
            rawValuesMgdl = rawValues
        )
        if (stored) {
            Log.i(TAG, "Stored $size history rows for direct Room merge")
            clearPendingRoomHistory()
        }
        return stored
    }

    /** Watchdog fires when a history page response never arrives. */
    private val historyPageWatchdog = Runnable {
        if (historyDownloading) {
            Log.e(TAG, "History page watchdog FIRED — no response in ${HISTORY_PAGE_TIMEOUT_MS}ms (phase=$historyPhase)")
            val awaitingRange = !historyRangeAcceptedThisGeneration
            historyDownloading = false
            historyPhase = HistoryPhase.IDLE
            // A timeout is not an abandoned download. It is routinely just a slow round trip: the
            // watchdog is armed when the page is QUEUED, so a bonding pause or a stuck GATT op can
            // burn most of the 25s before the sensor even sees the request. Record why the flag
            // dropped — dropStaleHistoryPage restarts the download once on the late page.
            historyPageTimedOut = true
            // The rows of the batch below already went to the native store; only a full merge can
            // still get them into Room (see onHistoryDownloadComplete).
            if (pendingRoomHistoryTimestamps.isNotEmpty()) historyRoomBufferDropped = true
            clearPendingRoomHistory("history-watchdog")
            // Persist current offsets so next reconnect resumes where we stopped
            writeIntPref("historyRawNextIndex", historyRawNextIndex)
            writeIntPref("historyBriefNextIndex", historyBriefNextIndex)
            Log.i(TAG, "History download aborted. Will resume on a late page or on the next connection from raw=$historyRawNextIndex, brief=$historyBriefNextIndex")
            if (
                awaitingRange &&
                !historyRangeRetryUsed &&
                phase == Phase.STREAMING &&
                mBluetoothGatt != null &&
                !forgotten
            ) {
                historyRangeRetryUsed = true
                Log.w(TAG, "History range 0x22 never accepted — retrying once")
                invalidateStaleHistoryResponses("range-retry")
                requestHistoryRange()
            }
        }
    }

    // -- History Merge Cache --
    // 0x23 calibrated glucose values, keyed by offset minute.
    // Populated during 0x23 download, consumed during 0x24 download.
    // The vendor driver does the same: caches raw ADC by offset, then
    // merges with calibrated glucose when storing.
    // Our wire format is swapped: 0x23 = calibrated, 0x24 = raw ADC.
    private val calibratedGlucoseCache = HashMap<Int, Int>()
    // Fallback glucose for 0x24 entries without an exact 0x23 offset match.
    // Carried across pages so edge-of-page entries still get a valid glucose.
    private var lastCalibratedGlucoseFallback: Int? = null

    // -- F003 Live Data --
    private var lastGlucoseTimeMs: Long = 0L
    private var lastF003FrameTimeMs: Long = 0L
    /**
     * Wall clock of the last direct F003 reading actually STORED this connection (0 = none).
     * Deliberately not lastLiveReadingObservedTimeMs: that one is also stamped by the broadcast
     * path, and history dedup must only trust a minute the direct live pipeline really wrote.
     */
    private var lastDirectLiveReadingTimeMs: Long = 0L
    private var lastOffsetMinutes: Int = 0
    private var lastLiveReadingObservedTimeMs: Long = 0L
    private var lastLiveContinuitySyncBucket: Long = -1L

    // -- Calibration State --
    /** End index of sensor's calibration range (from GET_CALIBRATION_RANGE). */
    private var calibrationRangeEndIndex: Int = 0
    /** Whether a calibration download is in progress. */
    private var calibrationDownloading: Boolean = false
    /** Set once the sensor has reported an empty calibration range on this connection. */
    private var calibrationRangeKnownEmpty: Boolean = false
    /**
     * Set once a pagination pass over a non-empty range has finished on this connection, whether
     * or not a record actually parsed. "Nothing parsed" is not "not asked": the 500ms tail below
     * clears [calibrationDownloading] no matter what came back, so without this the routine
     * refresh re-armed on every live reading and re-ran the whole fetch about once a minute.
     */
    private var calibrationFetchAttempted: Boolean = false

    // -- Startup Metadata / Legacy Start Time (0x10 / 0x21) --
    // The vendor/original stack treats raw `0x10` as startup device-info and
    // raw `0x21` as a follow-up local start-time query. Keep both bounded and
    // optional; DIS + 2AAA remain the primary metadata source.
    @Volatile private var startupMetadataComplete = false

    // -- Reconnection Prevention Flags --
    // Matches vendor driver's layered defense against unwanted reconnection.
    // _isPaused blocks external reconnection triggers (LossOfSensorAlarm, reconnectall).
    // isUnpaired is a persistent flag for UI status display.
    @Volatile private var _isPaused: Boolean = false
    @Volatile private var isUnpaired: Boolean = false

    // -- Live Offset Cutoff (History Dedup) --
    // Tracks the highest offset stored by live F003 readings this session.
    // History entries at or above this offset are skipped because the live
    // pipeline already stored them. Matches vendor driver's
    // vendorHistoryAutoUpdateCutoff mechanism.
    @Volatile private var liveOffsetCutoff: Int = 0

    // -- History Catch-up Broadcast --
    // Tracks the newest valid entry stored during history download for the
    // catch-up broadcast in onHistoryDownloadComplete().
    private var lastHistoryNewestGlucose: Float = 0f
    private var lastHistoryNewestOffset: Int = 0

    // -- Reset Reconnect Flag --
    // Set true BEFORE sending CLEAR_STORAGE (RAM only). The disconnect that ends the attempt
    // clears per-connection crypto and keeps the stable PAIR credential; only an attempt whose
    // 0x01 was claimed goes on to drop the Android bond. Every true->false transition decides
    // confirmed/unconfirmed under resetClaimLock.
    @Volatile private var pendingResetReconnect: Boolean = false
    @Volatile private var clearStorageQuietWindowActive: Boolean = false
    // Non-null while a confirmed erase waits for Android to drop the old bond; see
    // publishPostResetBondHold / pollPostResetBondHold.
    private class PostResetBondHold(
        val device: BluetoothDevice,
        /** What the card shows while the hold lasts. */
        val status: String,
        /** Not left before this: the sensor settles after the erase, as HEAD's 5s reconnect delay did. */
        val notBeforeElapsed: Long,
    )
    @Volatile private var postResetBondHold: PostResetBondHold? = null

    // -- Unpair Disconnect Flag --
    // Set true by unpairSensor(). The DELETE_BOND command is sent first; the sensor's ACK
    // performs the confirmed cleanup, while a disconnect or a failed write without it keeps the
    // key and the bond. Exactly one of those three consumes the flag, through claimUnpairLatch().
    @Volatile private var pendingUnpairDisconnect: Boolean = false
    private val unpairLock = Any()
    @Volatile private var consecutiveSetupDisconnects: Int = 0

    // elapsedRealtime() of the automatic broadcast-only entry that is still holding; 0 when
    // broadcast-only was entered by something that must not time out back to GATT (user switch,
    // unpair, auth exhaustion) or when nothing is holding. A timestamp instead of a pending
    // callback because every callback this driver owns dies in resetConnectionRuntimeState()'s
    // removeCallbacksAndMessages(null), which any adapter STATE_OFF reaches through
    // onBluetoothAdapterUnavailable() — deliberately NOT cleared there.
    @Volatile private var broadcastFallbackEnteredAtElapsed: Long = 0L

    // -- AiDexDriver State --
    @Volatile private var _batteryMillivolts: Int = 0
    @Volatile private var _sensorExpired: Boolean = false
    @Volatile private var _wearDays: Int = 0  // authoritative only after sensor 0x10 reports wear_days
    @Volatile private var sensorReportedWearDays: Boolean = false
    @Volatile private var _firmwareVersion: String = ""
    @Volatile private var _hardwareVersion: String = ""
    @Volatile private var _modelName: String = ""
    @Volatile private var _calibrationRecords: List<SharedCalibrationRecord> = emptyList()
    @Volatile private var _viewModeInternal: Int = 0
    init {
        val restored = restorePersistedViewMode()
        _viewModeInternal = restored
        applyViewModeToNative(restored)
        if (restored != 0) {
            Log.i(TAG, "Restored ViewMode=$restored for $SerialNumber")
        }
    }
    @Volatile private var _resetCompensationEnabled: Boolean = false

    private fun persistedNativeWearDays(): Int? {
        if (dataptr == 0L || sensorstartmsec <= 0L) return null
        val nativeStart = runCatching { Natives.getSensorStartmsec(dataptr) }.getOrDefault(0L)
        // Official end is nativeStart + wear. A kotlin start that has moved by a
        // whole number of days would otherwise be stored as a different life.
        if (nativeStart <= 0L || kotlin.math.abs(sensorstartmsec - nativeStart) >= 1_000L) return null
        val nativeEnd = runCatching { Natives.getSensorEndTime(dataptr, true) }.getOrDefault(0L)
        return AiDexWearProfile.persistedWearDays(nativeStart, nativeEnd)
    }

    private fun reportedWearDaysOrNull(): Int? {
        val memoryByte = _wearDays.takeIf { sensorReportedWearDays && it > 0 }
        return AiDexWearProfile.resolve(
            sensorDays = memoryByte ?: persistedNativeWearDays(),
            modelDays = AiDexWearProfile.ratedDays(_modelName),
        )
    }

    private fun persistResolvedWearDays(source: String) {
        val days = reportedWearDaysOrNull() ?: return
        if (sensorstartmsec > 0L) {
            updateSensorExpiredFromStart(System.currentTimeMillis())
        }
        if (dataptr == 0L) return
        try {
            Natives.aidexSetWearDays(dataptr, days)
            Log.i(TAG, "aidexSetWearDays: days=$days ($source)")
        } catch (_: Throwable) {}
    }

    private fun hasCompleteStartupMetadata(): Boolean =
        _modelName.isNotBlank() &&
            _firmwareVersion.isNotBlank() &&
            hasAuthoritativeSessionStart &&
            sensorstartmsec > 0L &&
            sensorReportedWearDays

    private fun updateSensorExpiredFromStart(now: Long): Boolean {
        val wearDays = reportedWearDaysOrNull()
        if (sensorstartmsec <= 0L || wearDays == null) {
            _sensorExpired = false
            return false
        }
        val expiryMs = sensorstartmsec + (wearDays.toLong() * 24 * 3600_000L)
        _sensorExpired = now > expiryMs
        return _sensorExpired
    }

    // -- Broadcast Scan State --
    @Volatile private var broadcastScanActive: Boolean = false
    @Volatile private var broadcastScanContinuousMode: Boolean = false
    private var broadcastScanCallback: ScanCallback? = null
    private var broadcastScanner: android.bluetooth.le.BluetoothLeScanner? = null
    @Volatile private var lastBroadcastGlucose: Float = 0f
    @Volatile private var lastBroadcastTime: Long = 0L
    @Volatile private var lastBroadcastStoredTime: Long = 0L
    @Volatile private var lastBroadcastStoredOffsetMinutes: Int = -1
    @Volatile private var lastBroadcastOffsetSeen: Long = -1L
    @Volatile private var lastBroadcastTrendSeen: Int = Int.MIN_VALUE
    @Volatile private var lastBroadcastGlucoseSeen: Int = Int.MIN_VALUE
    @Volatile private var lastBroadcastOffsetSeenAtMs: Long = 0L
    @Volatile private var broadcastScanMisses: Int = 0
    @Volatile private var noDirectLiveBroadcastFallbackMode: Boolean = false
    @Volatile private var broadcastScanStartedAtElapsed: Long = 0L
    @Volatile private var broadcastWakeLock: PowerManager.WakeLock? = null
    private var broadcastRejectionLogCount: Int = 0
    private var lastBroadcastRejectionLogAtMs: Long = 0L

    // Phase-lock state: learned per-broadcast cadence + count of confident catches.
    // `lastFreshBroadcastElapsedMs` is the *monotonic* (elapsedRealtime) stamp of the last
    // *non-duplicate* accepted broadcast — both the cadence estimator and the schedule anchor
    // must use this and NOT `lastBroadcastTime` (which gets bumped on dup mid-cycle and would
    // otherwise poison the estimator pulling cadence below the real value). Monotonic and not
    // wall clock because both consumers ask "how long since", never "at what time": a backward
    // clock correction would otherwise land straight on the next scan delay, and the alarm
    // armed from it, with nothing to re-arm the scan in between.
    @Volatile private var observedBroadcastCadenceMs: Long = DEFAULT_BROADCAST_CADENCE_MS
    @Volatile private var phaseLockHits: Int = 0
    @Volatile private var lastBroadcastOffsetForCadence: Int = -1
    @Volatile private var lastFreshBroadcastElapsedMs: Long = 0L

    // -- Transient Status --
    /**
     * Temporary status message (e.g. a calibration or reset outcome). [transientStatusUntilMs] is
     * what actually bounds it: [resetConnectionRuntimeState] wipes every pending handler message,
     * and the disconnect that follows a calibration or an abandoned reset regularly lands inside
     * the window, so [transientStatusClearRunnable] cannot be the only thing that ends the message.
     * Without the deadline a stale "not confirmed" outcome outlives the link it was about and
     * covers "Connected" for the rest of the session.
     */
    @Volatile private var transientStatusMessage: String? = null
    @Volatile private var transientStatusUntilMs: Long = 0L
    private val broadcastAssistRunnable: Runnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (stop || reconnect.isBroadcastOnlyMode || hasRecentLiveData(now)) return
            AiDexRuntimePolicy.initialAssistDelayMs(
                nowMs = now,
                phaseStreaming = phase == Phase.STREAMING,
                pendingInitialHistoryRequest = pendingInitialHistoryRequest,
                historyDownloading = historyDownloading,
                streamingStartedAtMs = streamingStartedAtMs,
                initialHistoryRequestDelayMs = INITIAL_HISTORY_REQUEST_DELAY_MS,
            )?.let { remainingMs ->
                Log.d(
                    TAG,
                    "Waiting for initial history/live handoff — delaying assist scan for ${remainingMs / 1000}s"
                )
                handler.postDelayed(this, remainingMs)
                return
            }
            val anchor = effectiveWarmupAnchorMs()
            if (anchor > 0L && now >= anchor) {
                val ageMs = now - anchor
                val hasValidReadingSinceAnchor = lastGlucoseTimeMs >= anchor && lastGlucoseTimeMs > 0L
                if (!hasValidReadingSinceAnchor && ageMs < FIRST_VALID_READING_WAIT_MAX_MS) {
                    Log.i(
                        TAG,
                        "Waiting for first valid reading (${firstValidReadingWaitStatus(now) ?: "warming"}) — " +
                            "starting assist scan for broadcast fallback"
                    )
                }
            }
            if (phase == Phase.DISCOVERING_SERVICES || phase == Phase.CCCD_CHAIN || phase == Phase.KEY_EXCHANGE) {
                val setupAge = if (connectTime > 0L) now - connectTime else 0L
                if (setupAge in 1 until BROADCAST_ASSIST_SETUP_STALL_MS) {
                    Log.d(TAG, "Waiting for first valid reading (${firstValidReadingWaitStatus(now) ?: "setup"}) — delaying assist scan until setup completes")
                    handler.postDelayed(this, BROADCAST_ASSIST_SCAN_DELAY_MS)
                    return
                }
            }
            Log.i(TAG, "Waiting for first valid reading (${firstValidReadingWaitStatus(now) ?: "no-anchor"}) — starting assist scan")
            startBroadcastScan("assist-no-data", continuous = false)
        }
    }
    private val transientStatusClearRunnable = Runnable {
        transientStatusMessage = null
        AiDexDriver.deviceListDirty = true
    }

    private fun showTransientStatus(message: String, durationMs: Long = 5000L) {
        transientStatusMessage = message
        // Monotonic and after the message on purpose: elapsedRealtime keeps running in deep sleep
        // while the postDelayed below does not, so the deadline can only end the message earlier,
        // never later, and no reader can catch an old message under a fresh deadline.
        transientStatusUntilMs = SystemClock.elapsedRealtime() + durationMs
        AiDexDriver.deviceListDirty = true
        handler.removeCallbacks(transientStatusClearRunnable)
        handler.postDelayed(transientStatusClearRunnable, durationMs)
    }

    private fun armFirstValidReadingWait(anchorMs: Long, reason: String) {
        val normalizedAnchor = anchorMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        firstValidReadingAnchorMs = normalizedAnchor
        writeLongPref("firstValidReadingAnchorMs", normalizedAnchor)
        Log.i(TAG, "First-valid-reading wait armed at $normalizedAnchor ($reason)")
        UiRefreshBus.requestStatusRefresh()
        handler.removeCallbacks(broadcastAssistRunnable)
        handler.postDelayed(broadcastAssistRunnable, BROADCAST_ASSIST_SCAN_DELAY_MS)
    }

    private fun clearFirstValidReadingWait(reason: String) {
        if (firstValidReadingAnchorMs == 0L && !postResetWarmupExtensionActive) return
        firstValidReadingAnchorMs = 0L
        writeLongPref("firstValidReadingAnchorMs", 0L)
        if (postResetWarmupExtensionActive) {
            postResetWarmupExtensionActive = false
            writeBoolPref("postResetWarmupExtensionActive", false)
        }
        if (constatstatusstr == POST_RESET_WAITING_STATUS) {
            constatstatusstr = ""
        }
        Log.i(TAG, "First-valid-reading wait cleared ($reason)")
        UiRefreshBus.requestStatusRefresh()
    }

    private fun armPostResetHistoryBarrier(reason: String, now: Long = System.currentTimeMillis()) {
        postResetRequestedAtMs = now
        writeLongPref("postResetRequestedAtMs", now)
        Log.i(TAG, "Post-reset history barrier armed at $now ($reason)")
    }

    private fun clearPostResetHistoryBarrier(reason: String) {
        if (postResetRequestedAtMs == 0L) return
        resetDiag("barrier-clear", "reason=$reason")
        Log.i(TAG, "Post-reset history barrier cleared ($reason, requestedAt=$postResetRequestedAtMs)")
        postResetRequestedAtMs = 0L
        writeLongPref("postResetRequestedAtMs", 0L)
    }

    private fun noteValidReadingAvailable(timestampMs: Long, reason: String) {
        if (timestampMs > 0L && timestampMs > lastGlucoseTimeMs) {
            lastGlucoseTimeMs = timestampMs
        }
        // Every path here decoded the reading over this device's own connection,
        // which is the ownership claim Clone reads.
        markLocalReadingAccepted(timestampMs)
        handler.removeCallbacks(broadcastAssistRunnable)
        clearFirstValidReadingWait(reason)
        if (phase == Phase.STREAMING && streamingStartedAtMs > 0L && lastF003FrameTimeMs < streamingStartedAtMs) {
            noStreamFallbackReadingObservedAtMs = System.currentTimeMillis()
            // Do NOT clear noStreamHistoryRecoveryAttempted here. storeHistoryEntries calls this
            // once per 0x24 page that stored anything, so the very history refresh the watchdog
            // just requested cleared the flag that was supposed to retire that step:
            // REQUEST_HISTORY_REFRESH repeated every watchdog period and the ladder never reached
            // REFRESH_LIVE_CCCDS/RECONNECT — the only two steps that can repair a dead F003
            // subscription. It is a once-per-streaming-session step, exactly like its two
            // neighbours.
            scheduleNoStreamWatchdog()
        }
    }

    private fun maybeRequestHistoryContinuitySyncAfterLive(now: Long, source: String) {
        val previousReadingMs = lastLiveReadingObservedTimeMs
        lastLiveReadingObservedTimeMs = now
        if (previousReadingMs <= 0L || historyDownloading) {
            return
        }
        val continuityDecision = tk.glucodata.LiveContinuityPolicy.decideContinuitySync(
                previousReadingMs,
                now,
                LIVE_HISTORY_CONTINUITY_BUCKET_MS,
                LIVE_HISTORY_CONTINUITY_MAX_MISSING_BUCKETS,
            )
        if (!continuityDecision.shouldRequestContinuitySync) {
            return
        }

        if (lastLiveContinuitySyncBucket >= continuityDecision.currentBucket) {
            return
        }
        lastLiveContinuitySyncBucket = continuityDecision.currentBucket
        Log.i(
            TAG,
            "Detected $source continuity gap: missing ${continuityDecision.missingBuckets} minute bucket(s) between " +
                "${continuityDecision.previousBucket} and ${continuityDecision.currentBucket} — requesting incremental history continuity sync"
        )
        tk.glucodata.HistorySyncAccess.syncSensorFromNative(SerialNumber)
    }

    private fun effectiveWarmupAnchorMs(): Long {
        if (hasAuthoritativeSessionStart && sensorstartmsec > 0L) return sensorstartmsec
        if (firstValidReadingAnchorMs > 0L) return firstValidReadingAnchorMs
        // A freshly added existing sensor often starts with a placeholder local
        // sensorstartmsec until 2AAA/history arrives. Treating that placeholder
        // as authoritative produces a bogus "Warmup 7m" on clean installs.
        // Only fall back to the local start time when this connection actually
        // initiated a new/reset sensor flow.
        if (autoActivationAttemptedThisConnection || postResetWarmupExtensionActive) {
            return sensorstartmsec.takeIf { it > 0L } ?: 0L
        }
        return 0L
    }

    private fun hasRecentBroadcastData(now: Long = System.currentTimeMillis()): Boolean {
        // lastBroadcastTime is stamped from the wall clock at reception, so it can never legitimately
        // sit ahead of `now`: a negative age is a backward clock step, not a fresh broadcast.
        return lastBroadcastTime > 0L &&
            (now - lastBroadcastTime) in 0L until BROADCAST_FALLBACK_LIVE_TIMEOUT_MS
    }

    private fun waitingForFirstDirectLive(): Boolean {
        return phase == Phase.STREAMING && streamingStartedAtMs > 0L && lastF003FrameTimeMs < streamingStartedAtMs
    }

    private fun shouldAttemptConnectedBroadcastRequest(): Boolean {
        if (!waitingForFirstDirectLive()) return false
        if (bondStateAtConnection != BluetoothDevice.BOND_BONDED) return false
        if (bondBecameBondedThisConnection) return false
        // GET_BROADCAST_DATA (0x11) is a read-only query, so an owed activation is no reason to
        // withhold it — and [needsPostResetActivation] is persistent: once the only start the
        // sensor ever reports predates the reset, nothing clears it again and this ladder step was
        // gone for good on a dead F003 subscription. A sensor that is merely warming up is already
        // excluded, because its sentinel frames stamp lastF003FrameTimeMs and
        // waitingForFirstDirectLive() is false there.
        if (autoActivationAttemptedThisConnection) return false
        return true
    }

    private fun isAuthRelatedCccdFailure(status: Int): Boolean {
        return when (status) {
            0x03, // GATT_WRITE_NOT_PERMITTED
            0x05, // GATT_INSUFFICIENT_AUTHENTICATION
            0x08, // GATT_INSUFFICIENT_AUTHORIZATION
            0x0F, // GATT_INSUFFICIENT_ENCRYPTION
            -> true
            else -> false
        }
    }

    private fun shouldContinueBroadcastScanning(): Boolean {
        return AiDexRuntimePolicy.shouldContinueBroadcastScanning(
            broadcastOnlyMode = reconnect.isBroadcastOnlyMode,
            noDirectLiveBroadcastFallbackMode = noDirectLiveBroadcastFallbackMode,
        )
    }

    private fun enableNoDirectLiveBroadcastFallbackMode(reason: String) {
        if (reconnect.isBroadcastOnlyMode || noDirectLiveBroadcastFallbackMode || !waitingForFirstDirectLive()) {
            return
        }
        noDirectLiveBroadcastFallbackMode = true
        if (constatstatusstr.isBlank() || constatstatusstr == "Connected" || constatstatusstr == CONNECTED_BROADCAST_FALLBACK_STATUS) {
            constatstatusstr = CONNECTED_BROADCAST_FALLBACK_STATUS
        }
        Log.i(TAG, "No direct F003 yet — enabling broadcast fallback mode ($reason)")
    }

    private fun disableNoDirectLiveBroadcastFallbackMode(reason: String) {
        if (!noDirectLiveBroadcastFallbackMode) return
        noDirectLiveBroadcastFallbackMode = false
        if (constatstatusstr == CONNECTED_BROADCAST_FALLBACK_STATUS) {
            constatstatusstr = if (phase == Phase.STREAMING) "Connected" else ""
        }
        Log.i(TAG, "Direct live resumed — disabling broadcast fallback mode ($reason)")
        if (!reconnect.isBroadcastOnlyMode) {
            cancelBroadcastScan()
        }
    }

    private fun shouldContinueAssistScanning(now: Long = System.currentTimeMillis()): Boolean {
        return AiDexRuntimePolicy.shouldContinueAssistScanning(
            stop = stop,
            broadcastOnlyMode = reconnect.isBroadcastOnlyMode,
            phaseStreaming = phase == Phase.STREAMING,
            hasRecentLiveData = hasRecentLiveData(now),
            pendingInitialHistoryRequest = pendingInitialHistoryRequest,
            historyDownloading = historyDownloading,
            anchorMs = effectiveWarmupAnchorMs(),
            nowMs = now,
            lastGlucoseTimeMs = lastGlucoseTimeMs,
            firstValidReadingWaitMaxMs = FIRST_VALID_READING_WAIT_MAX_MS,
        )
    }

    private fun firstValidReadingWaitStatus(now: Long = System.currentTimeMillis()): String? {
        return AiDexRuntimePolicy.firstValidReadingWaitStatus(
            anchorMs = effectiveWarmupAnchorMs(),
            nowMs = now,
            warmupDurationMs = WARMUP_DURATION_MS,
            firstValidReadingWaitMaxMs = FIRST_VALID_READING_WAIT_MAX_MS,
        )
    }

    private fun currentBondState(): Int {
        return mBluetoothGatt?.device?.bondState ?: BluetoothDevice.BOND_NONE
    }

    private fun scheduleSetupProgressWatchdog() {
        handler.removeCallbacks(setupProgressWatchdog)
        if (phase == Phase.DISCOVERING_SERVICES || phase == Phase.CCCD_CHAIN) {
            val timeoutMs = if (currentBondState() == BluetoothDevice.BOND_BONDING || keyExchangePendingBond) {
                SETUP_BONDING_STALL_TIMEOUT_MS
            } else {
                SETUP_STALL_TIMEOUT_MS
            }
            val phaseAgeMs = (System.currentTimeMillis() - phaseStartedAtMs).coerceAtLeast(0L)
            val remainingMs = (timeoutMs - phaseAgeMs).coerceAtLeast(500L)
            handler.postDelayed(setupProgressWatchdog, remainingMs)
        }
    }

    private fun clearInvalidSetupTracking(resetRecoveryCounter: Boolean, reason: String) {
        handler.removeCallbacks(connectAttemptWatchdog)
        handler.removeCallbacks(setupProgressWatchdog)
        handler.removeCallbacks(preAuthEncryptedTrafficWatchdog)
        handler.removeCallbacks(cccdWriteWatchdog)
        handler.removeCallbacks(invalidSetupRecoveryFallback)
        handler.removeCallbacks(staleConnectionRecoveryFallback)
        preAuthEncryptedFrameCount = 0
        preAuthFirstEncryptedFrameAtMs = 0L
        preAuthLastEncryptedFrameAtMs = 0L
        if (resetRecoveryCounter) {
            consecutiveInvalidSetupRecoveries = 0
        }
        Log.d(TAG, "Invalid-setup tracking cleared ($reason, resetCounter=$resetRecoveryCounter)")
    }

    private fun setBondValidatedByStreaming(validated: Boolean, reason: String) {
        if (bondValidatedByStreaming == validated) return
        bondValidatedByStreaming = validated
        writeBoolPref("bondValidatedByStreaming", validated)
        Log.i(TAG, "Bond validation state -> $validated ($reason)")
    }

    private fun ageSinceLabel(eventTimeMs: Long, nowMs: Long): String {
        return if (eventTimeMs > 0L && nowMs >= eventTimeMs) {
            "${nowMs - eventTimeMs}ms"
        } else {
            "n/a"
        }
    }

    private fun describeGattOp(op: GattOp?): String {
        return when (op) {
            is GattOp.Write -> "Write(${op.charUuid}, op=0x${"%02X".format(op.opcode)}, len=${op.data.size}, retry=${op.retryCount})"
            is GattOp.Read -> "Read(${op.serviceUuid}/${op.charUuid}, retry=${op.retryCount})"
            null -> "none"
        }
    }

    private fun resetDiag(
        stage: String,
        details: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val suffix = if (details.isBlank()) "" else " $details"
        Log.i(
            TAG,
            "RESET_DIAG stage=$stage sensor=$SerialNumber fw=${_firmwareVersion.ifBlank { "?" }} " +
                "model=${_modelName.ifBlank { "?" }} phase=$phase bond=${currentBondState()} key=${keyExchange.isComplete} " +
                "requestAge=${ageSinceLabel(postResetRequestedAtMs, nowMs)} " +
                "attemptAge=${ageSinceLabel(resetAttemptRequestedAtMs, nowMs)} " +
                "writeAge=${ageSinceLabel(postResetClearStorageWriteAtMs, nowMs)} " +
                "ackAge=${ageSinceLabel(postResetClearStorageAckAtMs, nowMs)} " +
                "disconnectRequestAge=${ageSinceLabel(postResetDisconnectRequestedAtMs, nowMs)} " +
                "pending=$pendingResetReconnect quiet=$clearStorageQuietWindowActive " +
                "queue=${gattQueue.size} active=${describeGattOp(currentGattOp)} dropped=$postResetDroppedGattOps$suffix"
        )
    }

    private fun logDisconnectContext(
        gatt: BluetoothGatt,
        status: Int,
        disconnectPhase: Phase,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        Log.w(
            TAG,
            "Disconnect context: status=$status phase=$disconnectPhase " +
                "address=${gatt.device?.address ?: "?"} " +
                "sessionAge=${ageSinceLabel(connectTime, nowMs)} " +
                "lastF003Age=${ageSinceLabel(lastF003FrameTimeMs, nowMs)} " +
                "lastLiveAge=${ageSinceLabel(lastLiveReadingObservedTimeMs, nowMs)} " +
                "lastF002Age=${ageSinceLabel(lastF002FrameTimeMs, nowMs)} " +
                "gattOpActive=$gattOpActive currentOp=${describeGattOp(currentGattOp)} queueSize=${gattQueue.size} " +
                "connectAttemptInFlight=$connectAttemptInFlight servicesReady=$servicesReady " +
                "cccdComplete=$cccdChainComplete cccdQueue=${cccdQueue.size} cccdPending=$cccdPendingWriteUuid " +
                "bondValidated=$bondValidatedByStreaming " +
                "historyDownloading=$historyDownloading pendingInitialHistory=$pendingInitialHistoryRequest " +
                "pendingMetadata=$pendingStreamingMetadataRead/$pendingStreamingMetadataReason " +
                "startupControl=$startupControlStage keyExchangeComplete=${keyExchange.isComplete} " +
                "challengeWritten=$challengeWritten bondDataRead=$bondDataRead keyExchangePendingBond=$keyExchangePendingBond " +
                "broadcastScanActive=$broadcastScanActive broadcastContinuous=$broadcastScanContinuousMode " +
                "noDirectFallback=$noDirectLiveBroadcastFallbackMode"
        )
    }

    private fun resetConnectionRuntimeState(reason: String, resetInvalidSetupCounter: Boolean) {
        // A 0x25 lost here would otherwise leave "Calibrating..." to expire as if it had worked:
        // the watchdog that would have failed it goes with the handler wipe below, and the queue is
        // cleared without failing anything. One in flight may or may not have reached the sensor;
        // one still queued never left the phone.
        val calibrationInFlight = (currentGattOp as? GattOp.Write)?.opcode == AiDexOpcodes.SET_CALIBRATION
        handler.removeCallbacksAndMessages(null)
        handler.post {
            consumeBinderPeeksOnHandler()
            invalidateStaleHistoryResponses("runtime-state-reset")
        }
        // Between the two on purpose: after the wipe, which would take this message's clear timer,
        // and before the release, because an abandoned reset posts its own, longer outcome there
        // and that is the message that has to stay on screen.
        if (calibrationInFlight) showTransientStatus(CALIBRATION_NOT_CONFIRMED_STATUS, POST_RESET_OUTCOME_STATUS_MS)
        // Read before the release, which abandons the reset on this same condition: the queued-0x25
        // message below runs after it and must not cover that outcome.
        val resetAbandoning = pendingResetReconnect && postResetClearStorageAckAtMs == 0L
        releaseClearStorageQuietWindow("runtime-state-reset:$reason")
        cancelBroadcastScan()
        connectAttemptInFlight = false
        negotiatedMtu = 23

        // Only the two deques move: the rest of this function must stay synchronous because
        // every caller posts its own reconnect right after it returns, and the
        // removeCallbacksAndMessages(null) above would then cancel that reconnect instead of the
        // work it is meant to cancel.
        runOnHandler {
            val calibrationQueued = gattQueue.any { it is GattOp.Write && it.opcode == AiDexOpcodes.SET_CALIBRATION }
            gattQueue.clear()
            cccdQueue.clear()
            // In flight outranks queued: "not sent" must not hide "not confirmed" — the sensor may
            // have taken that one — and neither may cover an abandoned reset's outcome. The in-flight
            // write's own failure callback can land before this teardown and show "not confirmed"
            // with currentGattOp already cleared, so what is on screen counts too.
            val unconfirmedOnScreen = transientStatusMessage == CALIBRATION_NOT_CONFIRMED_STATUS &&
                SystemClock.elapsedRealtime() < transientStatusUntilMs
            if (calibrationQueued && !calibrationInFlight && !unconfirmedOnScreen && !resetAbandoning) {
                showTransientStatus("Calibration not sent — connection lost")
            }
        }
        gattOpActive = false
        queuePausedForBonding = false
        currentGattOp = null
        servicesReady = false
        serviceDiscoveryStarted = false
        handler.removeCallbacks(mtuCallbackTimeout)
        lastMtuCallbackAtMs = 0L
        mtuExchangeCrossedPendingCccd = false
        shortBondReads = 0
        handler.removeCallbacks(cccdChainStartAfterMtuSettle)
        cccdChainComplete = false
        cccdWriteInProgress = false
        cccdPendingWriteUuid = null
        cccdMissingCallbackRetries = 0
        pendingBondedCccdUuid = null
        keyExchangePendingBond = false
        keyExchangeUsingSavedPairKey = false
        keyExchangeIsFreshPairAfterReset = false
        pairKeyAwaitingLiveValidation = false
        postCccdFollowUp = PostCccdFollowUp.NONE
        historyDownloading = false
        cccdRetryCount = 0
        discoveryRetryAttempt = 0
        streamingStartedAtMs = 0L
        lastF003FrameTimeMs = 0L
        lastDirectLiveReadingTimeMs = 0L
        noStreamConnectedBroadcastAttempted = false
        noStreamRecoveryAttempted = false
        noStreamHistoryRecoveryAttempted = false
        noStreamFallbackReadingObservedAtMs = 0L
        postBondLiveRefreshAttempted = false
        pendingInitialHistoryRequest = false
        pendingDefaultParamAutoProvisioning = false
        pendingDefaultParamAutoProvisioningReason = null
        pendingDefaultParamAutoProvisioningScheduled = false
        defaultParamAutoProvisioningAttemptedThisConnection = false
        pendingStreamingMetadataRead = false
        pendingStreamingMetadataReason = null
        pendingStreamingMetadataScheduled = false
        pendingCalibrationRefresh = false
        pendingCalibrationRefreshReason = null
        pendingCalibrationRefreshScheduled = false
        calibrationRangeKnownEmpty = false
        // The pagination chain and its 500ms tail are handler messages, and this function opens by
        // killing all of them — without these two the flags stay latched until the next CONNECTED
        // callback, which clears the same pair. A teardown with no reconnect behind it (stopped,
        // broadcast-only) never reaches that branch, so the routine calibration refresh is cleared
        // here too rather than waiting for a connection that may not come.
        calibrationDownloading = false
        calibrationFetchAttempted = false
        startupControlStage = StartupControlStage.IDLE
        lastF002FrameTimeMs = 0L
        noDirectLiveBroadcastFallbackMode = false
        broadcastScanMisses = 0
        lastBroadcastGlucose = 0f
        lastBroadcastTime = 0L
        lastBroadcastStoredTime = 0L
        lastBroadcastStoredOffsetMinutes = -1
        broadcastScanStartedAtElapsed = 0L
        lastBroadcastOffsetSeen = -1L
        lastBroadcastTrendSeen = Int.MIN_VALUE
        lastBroadcastGlucoseSeen = Int.MIN_VALUE
        lastBroadcastOffsetSeenAtMs = 0L
        observedBroadcastCadenceMs = DEFAULT_BROADCAST_CADENCE_MS
        phaseLockHits = 0
        lastBroadcastOffsetForCadence = -1
        lastFreshBroadcastElapsedMs = 0L
        clearDefaultParamProbeState()
        clearDefaultParamApplyState()
        defaultParamProbeUserInitiated = false
        // A teardown mid-download drops the batch exactly the way the page watchdog does: those
        // rows are already in the native store and the persisted cursors sit past them, so only a
        // full merge can still get the minutes into Room.
        if (pendingRoomHistoryTimestamps.isNotEmpty()) historyRoomBufferDropped = true
        clearPendingRoomHistory(reason)
        clearInvalidSetupTracking(resetRecoveryCounter = resetInvalidSetupCounter, reason = reason)

        calibratedGlucoseCache.clear()
        lastCalibratedGlucoseFallback = null
        lastOffsetMinutes = 0
        liveOffsetCutoff = 0
        historyNewestOffset = 0
        lastHistoryNewestGlucose = 0f
        lastHistoryNewestOffset = 0
        startupMetadataComplete = hasCompleteStartupMetadata()
    }

    private fun shouldRecoverBlockedReconnectNow(now: Long = System.currentTimeMillis()): Boolean {
        return AiDexRuntimePolicy.shouldRecoverFromBlockedReconnect(
            phase = phase,
            hasGatt = mBluetoothGatt != null,
            connectAttemptInFlight = connectAttemptInFlight,
            hasRecentLiveData = hasRecentLiveData(now),
            lastLiveReadingObservedTimeMs = lastLiveReadingObservedTimeMs,
        )
    }

    private fun recoverFromStaleConnectionState(reason: String) {
        if (stop || isPaused || isUnpaired || reconnect.isBroadcastOnlyMode) {
            Log.w(TAG, "Stale connection recovery ignored ($reason) stop=$stop paused=$isPaused unpaired=$isUnpaired broadcastOnly=${reconnect.isBroadcastOnlyMode}")
            return
        }
        if (pendingStaleConnectionRecovery) {
            Log.w(TAG, "Stale connection recovery already pending ($reason)")
            return
        }
        pendingStaleConnectionRecovery = true
        constatstatusstr = "Reconnecting"
        Log.w(TAG, "Stale connection state detected — forcing cleanup ($reason)")
        UiRefreshBus.requestStatusRefresh()
        if (mBluetoothGatt != null) {
            try {
                mBluetoothGatt?.disconnect()
                handler.removeCallbacks(staleConnectionRecoveryFallback)
                handler.postDelayed(staleConnectionRecoveryFallback, STALE_CONNECTION_RECOVERY_FALLBACK_MS)
            } catch (_: Throwable) {
                completeStaleConnectionRecovery("disconnect-throw", stateAlreadyReset = false)
            }
        } else {
            completeStaleConnectionRecovery("no-gatt", stateAlreadyReset = false)
        }
    }

    private fun completeStaleConnectionRecovery(trigger: String, stateAlreadyReset: Boolean) {
        if (!pendingStaleConnectionRecovery) return
        handler.removeCallbacks(staleConnectionRecoveryFallback)
        pendingStaleConnectionRecovery = false
        connectAttemptInFlight = false
        if (!stateAlreadyReset) {
            connectTime = 0L
            setPhase(Phase.IDLE)
            resetConnectionRuntimeState(reason = "stale-recovery:$trigger", resetInvalidSetupCounter = false)
        }
        close()
        val delay = reconnect.nextReconnectDelayMs()
        Log.w(TAG, "Stale connection recovery: reconnecting in ${delay}ms ($trigger)")
        handler.postDelayed({ connectDevice(0) }, delay)
    }

    private fun completePostResetReconnect(
        trigger: String,
        stateAlreadyReset: Boolean,
    ) {
        // The latch drop and the confirmed/unconfirmed verdict are one step: a 0x01 claim that
        // loses this race finds pendingResetReconnect false and is not credited.
        val confirmed = synchronized(resetClaimLock) {
            if (forgotten) {
                pendingResetReconnect = false
                clearStorageQuietWindowActive = false
                return
            }
            if (!pendingResetReconnect) return
            pendingResetReconnect = false
            clearStorageQuietWindowActive = false
            postResetClearStorageAckAtMs > 0L
        }
        resetDiag(
            stage = "post-reset-cleanup",
            details = "trigger=$trigger stateAlreadyReset=$stateAlreadyReset confirmed=$confirmed deviceBond=${currentBondState()}",
        )
        handler.removeCallbacks(clearStorageQuietWindowReconnect)
        handler.removeCallbacks(postResetDisconnectFallback)
        connectAttemptInFlight = false
        if (!stateAlreadyReset) {
            connectTime = 0L
            setPhase(Phase.IDLE)
            resetConnectionRuntimeState(reason = "post-reset:$trigger", resetInvalidSetupCounter = false)
        }

        // A confirmed CLEAR_STORAGE also wipes the sensor's bond and PAIR credential, so
        // commitConfirmedClearStorageIfOwed has marked the saved key reset-pending and the
        // reconnect pairs fresh over F001. The saved key itself survives on both branches below:
        // it is the fallback until a fresh one is validated by live data.
        keyExchange.reset()
        var bondDevice: BluetoothDevice? = null
        var bondRemovalAccepted = false
        if (confirmed) {
            // A confirmed erase is a lifecycle reset the sensor has carried out, and it drops its
            // side of the pairing with it. The next connect pairs fresh over F001 (the saved key
            // is reset-pending, and comes back if the fresh pair fails); over the old Android bond
            // that is known to fail. One removal per erase: the 5s runnable below does not connect
            // while this device still holds the bond.
            bondDevice = mBluetoothGatt?.device ?: mActiveBluetoothDevice
            bondRemovalAccepted = removeBondSafely(bondDevice, "postReset")
        } else {
            // "The write left the phone" is not "the sensor erased itself": the ACK is lost first
            // on exactly the link that is dropping anyway. Restamping the session with 0x20 and
            // quarantining the sensor's real ring on that assumption is unrecoverable when the
            // guess is wrong, so an unacknowledged CLEAR_STORAGE counts as "did not happen" and
            // the user is told to check the sensor.
            Log.w(TAG, "CLEAR_STORAGE dispatched but never acknowledged ($trigger) — not activating")
            resetDiag("clear-storage-unconfirmed", "trigger=$trigger")
            abandonPendingReset("clear-storage-unacknowledged")
        }
        // removeBond() only asks: BOND_NONE arrives later on its own broadcast, so a device that
        // still holds the bond right now goes into the hold at once, and pollPostResetBondHold lets
        // it go. Published before close()/reconnect.reset(): reset() clears broadcast-only, and
        // connectDevice() refuses on the hold itself, so no automatic connect (reconnectall,
        // adapter STATE_ON, othersworking) can raise GATT on the old bond in between either.
        val holdDevice = bondDevice
        val hold = if (
            holdDevice != null &&
            AiDexRuntimePolicy.postResetReconnectStep(confirmed, holdDevice.bondState) ==
            AiDexRuntimePolicy.PostResetReconnectStep.HOLD_FOR_BOND_REMOVAL
        ) {
            publishPostResetBondHold(holdDevice, bondRemovalAccepted)
        } else {
            null
        }
        close()
        reconnect.reset()
        // Still ours: a pause or a Reconnect in the meantime cleared it, and a pause, unpair or
        // forget that is still on its way owns the link as well.
        if (hold != null && postResetBondHold === hold && !_isPaused && !isUnpaired && !forgotten) {
            enterBroadcastOnlyFallback(reason = "post-reset-bond-hold", statusText = hold.status)
            // After the call, not before: enterBroadcastOnlyFallback stamps elapsedRealtime() for
            // a sensor that is not unpaired, and that stamp would time the hold out onto the old bond.
            broadcastFallbackEnteredAtElapsed = 0L
        }
        Log.i(TAG, "Post-reset cleanup complete ($trigger) — reconnecting after quiet delay")
        handler.removeCallbacks(postResetSettle)
        handler.postDelayed(postResetSettle, POST_RESET_RECONNECT_DELAY_MS)
    }

    /** POST_RESET_RECONNECT_DELAY_MS after completePostResetReconnect: connect, or check the hold. */
    private val postResetSettle = Runnable {
        // A pause, unpair or forget that landed after this was posted keeps the sensor where the
        // user put it: completePostResetReconnect can run on the binder after softDisconnect's wipe.
        if (_isPaused || isUnpaired || forgotten) return@Runnable
        if (postResetBondHold != null) {
            pollPostResetBondHold("settle")
        } else {
            stop = false
            connectDevice(0)
        }
    }

    /**
     * The post-reset bond hold: a confirmed erase whose device still held the Android bond when
     * completePostResetReconnect asked for its removal. Connecting would run the key exchange over a
     * pairing the sensor has dropped, and a failed bond cycle can spend the auth budget into a
     * broadcast-only fallback with no way back. So the sensor stays on broadcasts, with no timed
     * return, and leaves only through [pollPostResetBondHold] — settle delay over and the bond gone,
     * polled from the settle runnable, from [bonded] and from every scan window — or the user's
     * Reconnect or Re-pair. One hold and no second removeBond(): when the platform refused the removal,
     * Android's Bluetooth settings are the user's way out, and the BOND_NONE from there ends it.
     * Returns null, publishing nothing, when a pause, unpair or forget owns the link.
     */
    private fun publishPostResetBondHold(device: BluetoothDevice, removalAccepted: Boolean): PostResetBondHold? {
        if (_isPaused || isUnpaired || forgotten) return null
        val status = if (removalAccepted) {
            "Reset confirmed — waiting for Android to drop the old pairing"
        } else {
            "Remove the sensor in Android Bluetooth settings, then tap Reconnect"
        }
        val hold = PostResetBondHold(
            device = device,
            status = status,
            notBeforeElapsed = SystemClock.elapsedRealtime() + POST_RESET_RECONNECT_DELAY_MS,
        )
        postResetBondHold = hold
        resetDiag("post-reset-bond-hold", "removalAccepted=$removalAccepted bond=${device.bondState}")
        return hold
    }

    /**
     * Handler only. The post-reset bond hold's one exit: once the settle delay is over and the
     * held device's Android bond is gone, hand the sensor back to the direct path. Polled from the
     * settle runnable, from every bond broadcast ([bonded]) and from every broadcast-scan window
     * ([maybeLeaveBroadcastOnlyFallback]), so a missed BOND_NONE broadcast or a handler wipe cannot
     * strand the hold. Returns true when it connected.
     */
    private fun pollPostResetBondHold(source: String): Boolean {
        val hold = postResetBondHold ?: return false
        if (
            !AiDexRuntimePolicy.mayLeavePostResetBondHold(
                nowElapsed = SystemClock.elapsedRealtime(),
                notBeforeElapsed = hold.notBeforeElapsed,
                bondState = hold.device.bondState,
            )
        ) return false
        // Before the hold is dropped: a pause that sets only `stop` (SensorOwnershipRuntime's
        // release, the clone block, bluediag) keeps the hold, and the resume's connectDevice() then
        // polls it again. Dropped here, it would leave broadcast-only with no timed return.
        if (stop || _isPaused || isUnpaired || forgotten) return false
        postResetBondHold = null
        Log.i(TAG, "Post-reset bond hold released ($source) — connecting")
        broadcastFallbackEnteredAtElapsed = 0L
        reconnect.isBroadcastOnlyMode = false
        cancelBroadcastScan()
        connectDevice(0)
        return true
    }

    /**
     * Both callers wipe every pending handler message, and that includes
     * [clearStorageQuietWindowReconnect] and [postResetDisconnectFallback] — the only timers that
     * ever clear [clearStorageQuietWindowActive]. While the window is up [enqueueGattOp] drops
     * every GATT operation, the bond read for key exchange included, so a window left latched here
     * survives into every later connection and starves the sensor for the life of this instance.
     *
     * A reset the sensor has not confirmed is abandoned here: the answer only arrives over the link
     * being torn down, so the verdict is already final. A confirmed one keeps its latch for
     * completePostResetReconnect and only drops the window — also when a 0x01 claim wins the race
     * against this abandon.
     */
    private fun releaseClearStorageQuietWindow(reason: String) {
        if (pendingResetReconnect && postResetClearStorageAckAtMs == 0L && abandonPendingReset(reason)) return
        clearStorageQuietWindowActive = false
    }

    /**
     * Drop a reset the sensor did not confirm: the latch, the quiet window and a CLEAR_STORAGE
     * still queued. Nothing persisted is touched. The debt, the barrier and the warmup extension
     * only ever exist after a confirmed 0x01 ([commitConfirmedClearStorageIfOwed]); if they are set
     * here they belong to an earlier erase the sensor did confirm.
     *
     * Returns false and does nothing when this attempt's 0x01 was claimed:
     * completePostResetReconnect or the STATE_CONNECTED latch arm owns a confirmed attempt.
     */
    private fun abandonPendingReset(reason: String): Boolean {
        val wasLatched = synchronized(resetClaimLock) {
            if (postResetClearStorageAckAtMs > 0L) return false
            // Inside the lock: a reset admitted right after it must keep its own timer and press time.
            handler.removeCallbacks(clearStorageQuietWindowReconnect)
            handler.removeCallbacks(postResetDisconnectFallback)
            resetAttemptRequestedAtMs = 0L
            val latched = pendingResetReconnect || clearStorageQuietWindowActive
            pendingResetReconnect = false
            clearStorageQuietWindowActive = false
            latched
        }
        if (wasLatched) {
            resetDiag("reset-abandoned", "reason=$reason")
            Log.w(TAG, "Pending sensor reset abandoned ($reason) — treating the reset as not carried out")
        }
        // Outside the guard on purpose: completePostResetReconnect clears both flags before it
        // abandons, so a message inside would be lost on exactly the path that used to carry it.
        // Every reachable caller is a reset the user pressed for — only resetSensor() sets the
        // flags and drainGattQueue refuses 0xF3 without them.
        showTransientStatus("Reset not confirmed by sensor", POST_RESET_OUTCOME_STATUS_MS)
        // A still-queued CLEAR_STORAGE has to go with the latch. The quiet window expires 12s after
        // the request while a stuck operation owns the queue for 15s (GATT_OP_TIMEOUT_MS,
        // BONDING_PAUSE_TIMEOUT_MS), so the write regularly is still queued here — and would erase
        // the sensor after the abandon, with nothing left to account for it.
        runOnHandler {
            if (gattQueue.removeAll { it is GattOp.Write && it.opcode == AiDexOpcodes.CLEAR_STORAGE }) {
                Log.w(TAG, "Abandoned reset: dropped the queued CLEAR_STORAGE (0xF3) before it reached the sensor")
                resetDiag("f3-dequeued-on-abandon", "reason=$reason")
            }
        }
        return true
    }

    /**
     * Credit this attempt's CLEAR_STORAGE status 0x01. First caller is the binder peek in
     * [dispatchF002Response], which runs before a DISCONNECTED teardown can wipe the posted frame
     * — the sensor reboots right after the erase, so that teardown is the expected next event —
     * and then [handleClearStorageResponse], where it is a no-op if the peek already won. The
     * persisted bookkeeping stays on the handler ([commitConfirmedClearStorageIfOwed]).
     */
    private fun claimClearStorageConfirmation(source: String): Boolean {
        synchronized(resetClaimLock) {
            // A 0x01 that arrives before this attempt's own 0xF3 has gone out answers an earlier,
            // abandoned one. drainGattQueue stamps the write before the frame is on air.
            if (
                !pendingResetReconnect ||
                postResetClearStorageWriteAtMs == 0L ||
                postResetClearStorageAckAtMs > 0L
            ) return false
            postResetClearStorageAckAtMs = System.currentTimeMillis()
            clearStorageConfirmOwed = true
        }
        resetDiag("f3-accept-claimed", "source=$source", nowMs = postResetClearStorageAckAtMs)
        return true
    }

    /**
     * Handler only; once per claim. The persisted side of a confirmed erase — everything here used
     * to run in resetSensor() before the sensor had answered (see the debt KDoc).
     */
    private fun commitConfirmedClearStorageIfOwed() {
        if (!clearStorageConfirmOwed) return
        clearStorageConfirmOwed = false
        // The rollback a refused 0x20 would restore describes the ring this erase wiped.
        setNewSensorSnapshotValid = false
        setNewSensorNackSeen = false
        setNewSensorSnapCalibratedCache.clear()
        setNewSensorSnapCalibratedFallback = null
        _calibrationRecords = emptyList()
        tk.glucodata.HistorySyncAccess.markSensorReset(SerialNumber)
        // The press time, as before this moved: sessionStartPredatesReset measures against it.
        armPostResetHistoryBarrier(
            "clear-storage-confirmed",
            resetAttemptRequestedAtMs.takeIf { it > 0L } ?: postResetClearStorageAckAtMs,
        )
        setActivationDebt(true, "clear-storage-confirmed")
        historyRawNextIndex = 0
        historyBriefNextIndex = 0
        writeIntPref("historyRawNextIndex", 0)
        writeIntPref("historyBriefNextIndex", 0)
        liveOffsetCutoff = 0
        // Its pair. The snap in handleHistoryRangeResponse keys off lastDirectLiveReadingTimeMs
        // alone, so a live minute stored before the reset would snap the cutoff onto the newest
        // offset reported after it.
        lastDirectLiveReadingTimeMs = 0L
        postResetWarmupExtensionActive = true
        writeBoolPref("postResetWarmupExtensionActive", true)
        markPairKeyResetPending("clear-storage-confirmed")
        Log.i(TAG, "CLEAR_STORAGE confirmed — activation debt, barrier and history cursors committed")
    }

    /**
     * Handler only. The sensor accepted CLEAR_STORAGE, which wipes its bond and PAIR credential:
     * the next connection pairs fresh over F001 instead of retrying the dead key. The saved key is
     * kept — replaced only by a fresh key that yields a valid live reading (sentinels do not count), restored if the fresh pair fails.
     */
    private fun markPairKeyResetPending(source: String) {
        val key = persistedPairKey ?: return
        pairKeyResetPending = true
        // The running session was keyed before this erase, even when it was itself an earlier
        // post-reset fresh pair: it must not settle the new mark.
        keyExchangeIsFreshPairAfterReset = false
        keyExchangeFailures = 0
        savedKeyExhausted = false
        Log.i(
            TAG,
            "CLEAR_STORAGE accepted ($source) — next connection pairs fresh; keeping PAIR credential " +
                "fp=${AiDexPairKeyVault.fingerprint(key)} until a new one is validated"
        )
    }

    /**
     * Handler only; once per connection, where the CCCD chain is shaped. The post-reset fresh pair
     * is bounded by connections as well as by failures inside the exchange: a sensor that refuses
     * the new pairing fails in CCCD_CHAIN or in bonding (BOND_NONE, status 22, invalid setup), where
     * [handleKeyExchangeFailure] never runs. After [KEY_EXCHANGE_MAX_FAILURES] such connections the
     * kept key gets its turn, as RESTORE_SAVED_KEY gives it.
     */
    private fun notePostResetFreshPairConnection() {
        if (!pairKeyResetPending || persistedPairKey == null) return
        if (postResetFreshPairConnections >= KEY_EXCHANGE_MAX_FAILURES) {
            restoreKeptPairKey("fresh pair not completed in $KEY_EXCHANGE_MAX_FAILURES connections")
        } else {
            postResetFreshPairConnections += 1
        }
    }

    /**
     * The post-reset fresh pair did not produce a working key, so the reset may not have touched
     * the sensor's credential: go back to the key that was kept for this.
     */
    private fun restoreKeptPairKey(reason: String) {
        pairKeyResetPending = false
        keyExchangeFailures = 0
        savedKeyExhausted = false
        Log.w(
            TAG,
            "Post-reset fresh pair given up ($reason); falling back to the kept " +
                "PAIR credential (fp=${AiDexPairKeyVault.fingerprint(persistedPairKey)})"
        )
    }

    private fun setActivationDebt(owed: Boolean, reason: String) {
        needsPostResetActivation = owed
        writeBoolPref(ACTIVATION_DEBT_PREF, owed)
        Log.i(TAG, "Post-reset activation debt -> $owed ($reason)")
    }

    /**
     * Clears post-reset activation debt once 0x20 has left the phone. Does not rewrite history
     * cursors (that is [commitSetNewSensorLocalSession] after `writeCharacteristic == true`).
     */
    private fun noteActivationWriteDelivered(source: String) {
        if (!needsPostResetActivation) return
        setActivationDebt(false, "SET_NEW_SENSOR (0x20) delivered ($source)")
    }

    private fun notePreAuthEncryptedTraffic(source: String, now: Long = System.currentTimeMillis()) {
        if (phase != Phase.DISCOVERING_SERVICES && phase != Phase.CCCD_CHAIN) return
        if (keyExchange.isComplete || keyExchangePendingBond) return
        val bondState = currentBondState()
        if (bondState != BluetoothDevice.BOND_BONDED) return
        if (preAuthEncryptedFrameCount == 0) {
            preAuthFirstEncryptedFrameAtMs = now
        }
        preAuthEncryptedFrameCount += 1
        preAuthLastEncryptedFrameAtMs = now
        Log.w(
            TAG,
            "Encrypted pre-auth traffic observed from $source while $phase " +
                "(count=$preAuthEncryptedFrameCount age=${(now - preAuthFirstEncryptedFrameAtMs).coerceAtLeast(0L)}ms)"
        )
        handler.removeCallbacks(preAuthEncryptedTrafficWatchdog)
        handler.postDelayed(preAuthEncryptedTrafficWatchdog, PRE_AUTH_ENCRYPTED_TRAFFIC_TIMEOUT_MS)
    }

    private fun canInferMissingCccdCallbackComplete(): Boolean {
        return currentBondState() == BluetoothDevice.BOND_BONDED
    }

    /**
     * Returns true only when this process has no reason to believe an Android bond survived: the
     * device was already BOND_NONE, or the hidden removeBond() accepted the request. Acceptance is
     * everything the platform answers synchronously — BOND_NONE arrives later on its own bond-state
     * broadcast — so a caller may say "unpaired", never "the bond is proven gone". A missing handle,
     * BOND_BONDING, a refused call and a throwing one all return false: the pairing may still be
     * there, and the status the user reads has to say so.
     */
    private fun removeBondSafely(device: BluetoothDevice?, reason: String): Boolean {
        // Cleared for every attempt, and before the reflection rather than after it: when
        // removeBond() is refused or throws the bond survives, and a stale "streaming validated
        // this bond" is exactly what keeps REMOVE_BOND_AND_RECONNECT unreachable in
        // AiDexRuntimePolicy.decideInvalidSetupRecoveryAction — the only automatic way out of a
        // pairing this driver could not remove. It is re-earned on the next direct-live frame.
        setBondValidatedByStreaming(false, "$reason-removeBond")
        if (device == null) {
            Log.w(TAG, "$reason: no device handle — Android bond left in place")
            return false
        }
        val bondState = device.bondState
        if (bondState != BluetoothDevice.BOND_BONDED) {
            // BOND_NONE is the only state that means nothing is paired; BOND_BONDING is a bond on
            // its way in, and this driver has never cancelled one.
            Log.i(TAG, "$reason: no bond to remove (bondState=$bondState)")
            return bondState == BluetoothDevice.BOND_NONE
        }
        return try {
            val removeBond = device.javaClass.getMethod("removeBond")
            val accepted = removeBond.invoke(device) as? Boolean ?: false
            Log.i(TAG, "$reason: removeBond() accepted=$accepted (BOND_NONE still has to follow)")
            accepted
        } catch (t: Throwable) {
            Log.w(TAG, "$reason: removeBond failed: ${t.message}")
            false
        }
    }

    private fun advanceBondedReconnectToKeyExchange(gatt: BluetoothGatt, trafficAgeMs: Long) {
        Log.w(
            TAG,
            "Bonded pre-auth traffic persisted for ${trafficAgeMs}ms with empty CCCD queue; " +
                "forcing key exchange instead of waiting for a missing final descriptor callback"
        )
        pendingBondedCccdUuid = null
        cccdWriteInProgress = false
        cccdChainComplete = true
        startKeyExchangeForCurrentConnection(gatt)
    }

    private fun recoverFromInvalidSetupState(reason: String) {
        if (stop || isPaused || isUnpaired || reconnect.isBroadcastOnlyMode) {
            Log.w(TAG, "Invalid setup recovery ignored ($reason) stop=$stop paused=$isPaused unpaired=$isUnpaired broadcastOnly=${reconnect.isBroadcastOnlyMode}")
            return
        }
        if (phase == Phase.KEY_EXCHANGE) {
            handleKeyExchangeFailure(reason)
            return
        }
        val bondState = currentBondState()
        consecutiveInvalidSetupRecoveries += 1
        if (consecutiveInvalidSetupRecoveries >= INVALID_SETUP_BROADCAST_FALLBACK_THRESHOLD) {
            // The only ladder in the driver without a terminal state. Hand the session to
            // broadcast-only like the other ladders do: the advertisements still carry readings, and
            // the fallback's own hold returns to GATT once the air has had time to clear. The
            // counters are kept on purpose: after the hold the next failure comes straight back here
            // instead of replaying the whole ladder with no scan running. A confirmed handshake
            // clears them.
            Log.w(
                TAG,
                "Invalid setup recovery gave up after $consecutiveInvalidSetupRecoveries attempts " +
                    "($reason) — falling back to broadcast-only"
            )
            postBondConfigUnconfirmed = false
            pendingInvalidSetupRecovery = PendingInvalidSetupRecovery.NONE
            // Called on a live link, mid-handshake. enterBroadcastOnlyFallback() only close()s it,
            // which leaves the phase at KEY_EXCHANGE and every setup watchdog still posted, so run
            // the same teardown recoverFromServiceDiscoveryFailure() runs before the same call.
            recoverFromBrokenLink("invalid-setup-recoveries-exhausted", scheduleReconnect = false)
            enterBroadcastOnlyFallback(
                reason = "invalid-setup-recoveries-exhausted",
                statusText = "Broadcast fallback",
            )
            return
        }
        val action = AiDexRuntimePolicy.decideInvalidSetupRecoveryAction(
            consecutiveRecoveries = consecutiveInvalidSetupRecoveries,
            bondState = bondState,
            bondResetThreshold = INVALID_SETUP_BOND_RESET_THRESHOLD,
            bondValidatedByStreaming = bondValidatedByStreaming,
        )
        pendingInvalidSetupRecovery = when (action) {
            AiDexRuntimePolicy.InvalidSetupRecoveryAction.RECONNECT -> PendingInvalidSetupRecovery.RECONNECT
        }
        clearInvalidSetupTracking(resetRecoveryCounter = false, reason = "recover:$reason")
        constatstatusstr = when (pendingInvalidSetupRecovery) {
            PendingInvalidSetupRecovery.RECONNECT -> "Recovering connection"
            PendingInvalidSetupRecovery.NONE -> constatstatusstr
        } ?: "Recovering"
        Log.w(
            TAG,
            "Invalid setup state detected — scheduling ${pendingInvalidSetupRecovery.name.lowercase()} " +
                "(attempt=$consecutiveInvalidSetupRecoveries validatedBond=$bondValidatedByStreaming reason=$reason)"
        )
        UiRefreshBus.requestStatusRefresh()
        try {
            mBluetoothGatt?.disconnect()
            handler.removeCallbacks(invalidSetupRecoveryFallback)
            handler.postDelayed(invalidSetupRecoveryFallback, 3_000L)
        } catch (_: Throwable) {
            close()
            handler.post { handlePendingInvalidSetupRecovery() }
        }
    }

    private fun handlePendingInvalidSetupRecovery() {
        val recovery = pendingInvalidSetupRecovery
        if (recovery == PendingInvalidSetupRecovery.NONE) return
        handler.removeCallbacks(invalidSetupRecoveryFallback)
        pendingInvalidSetupRecovery = PendingInvalidSetupRecovery.NONE
        keyExchange.reset()
        challengeWritten = false
        bondDataRead = false
        keyExchangePendingBond = false
        cccdWriteInProgress = false
        cccdChainComplete = false
        // Coming from the disconnect callback the phase is already IDLE. Coming from
        // invalidSetupRecoveryFallback — or from the catch around a throwing disconnect() — that
        // callback never ran, and the close() below buries it for good, so the phase has to be
        // cleared here or the reconnect scheduled a few lines down is refused by connectDevice().
        if (phase != Phase.IDLE) {
            connectTime = 0L
            setPhase(Phase.IDLE)
            resetConnectionRuntimeState(reason = "invalid-setup-recovery", resetInvalidSetupCounter = false)
        }
        when (recovery) {
            PendingInvalidSetupRecovery.RECONNECT -> {
                val delay = reconnect.nextReconnectDelayMs()
                Log.w(TAG, "Invalid setup recovery: reconnecting in ${delay}ms")
                close()
                handler.postDelayed({ connectDevice(0) }, delay)
            }
            PendingInvalidSetupRecovery.NONE -> Unit
        }
    }

    // -- Listeners --
    /** Called when a live glucose reading is parsed from F003. */
    var onGlucoseReading: ((GlucoseReading) -> Unit)? = null

    /** Called when calibrated history entries are parsed from 0x23. */
    var onCalibratedHistory: ((List<CalibratedHistoryEntry>) -> Unit)? = null

    /** Called when ADC history entries are parsed from 0x24. */
    var onAdcHistory: ((List<AdcHistoryEntry>) -> Unit)? = null

    /** Called when calibration records are parsed from 0x27. */
    var onCalibrationRecords: ((List<CalibrationRecord>) -> Unit)? = null

    /** Called when sensor info is received (activation date, etc.) */
    var onSensorInfo: ((SensorInfo) -> Unit)? = null

    /** Called on phase changes for UI status updates. */
    var onPhaseChange: ((Phase) -> Unit)? = null

    // =========================================================================
    // SuperGattCallback Overrides
    // =========================================================================

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        if (deviceName == null) return false
        return aiDexDeviceNameMatchesSerial(deviceName, SerialNumber)
    }

    override fun mygetDeviceName(): String =
        aiDexDisplayName(super.mygetDeviceName(), mActiveDeviceAddress, SerialNumber)

    override fun getService(): UUID = SERVICE_F000

    /**
     * Guard against double-connect and unwanted reconnection.
     *
     * Multiple paths call connectDevice():
     *   1. SensorBluetooth init (initializeBluetooth, possiblybluetooth→connectDevices)
     *   2. LossOfSensorAlarm → reconnectall() → reconnect() → connectDevice(0)
     *   3. Bluetooth STATE_ON → connectToAllActiveDevices(500)
     *   4. othersworking() → shouldreconnect() → reconnect()
     *
     * The base SuperGattCallback.reconnect() does NOT check the `stop` flag
     * before calling connectDevice(). We must guard here.
     *
     * Guards (matching vendor driver's connectDevice() at line 5595):
     *   - isPaused / isUnpaired: set by unpairSensor(), softDisconnect()
     *   - isBroadcastOnlyMode: set after auth failure exhaustion
     *   - phase != IDLE: already actively connected/connecting
     *   - mBluetoothGatt != null: GATT handle exists
     */
    // BluetoothAdapter.getDefaultAdapter(): no Context reaches this callback; minSdk 26
    @Suppress("DEPRECATION")
    override fun connectDevice(delayMillis: Long): Boolean {
        // true, like the other refusals: a false makes SensorBluetooth start its own scanner.
        if (forgotten) return true
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled != true) {
            Log.i(TAG, "connectDevice: skip — Bluetooth is disabled")
            connectAttemptInFlight = false
            setPhase(Phase.IDLE)
            handler.postDelayed({
                if (!stop && !isPaused && !isUnpaired && mBluetoothGatt == null && phase == Phase.IDLE) {
                    connectDevice(0)
                }
            }, 10_000L)
            return true
        }
        // Broadcast-only is the sensor's only remaining data path, and its scan loop lives
        // entirely in handler posts plus one alarm — onBluetoothAdapterUnavailable() wipes
        // both through resetConnectionRuntimeState() (removeCallbacksAndMessages +
        // cancelBroadcastScan), and STATE_ON only comes back here. connectDevice() is the one
        // funnel every external revival trigger passes through (adapter STATE_ON,
        // LossOfSensorAlarm -> reconnectall -> reconnect, othersworking), so re-arm the scan
        // here instead of returning quietly into a permanently dead loop.
        // Checked BEFORE the isPaused guard on purpose: softDisconnect() leaves _isPaused set
        // on the unpair and setBroadcastOnlyConnection(true) entry paths, which would
        // otherwise swallow the re-arm. `stop` still falls through to that guard, so a paused
        // sensor keeps its old "return true, no scan" behaviour.
        // The post-reset bond hold counts as broadcast-only from the moment it is published, a few
        // lines before completePostResetReconnect switches the mode on: no GATT on the old bond.
        if ((reconnect.isBroadcastOnlyMode || postResetBondHold != null) && !stop) {
            // Same reason this branch re-arms the scan: the fallback's way back cannot live in a
            // pending callback either, so an expired hold returns to GATT from right here.
            if (maybeLeaveBroadcastOnlyFallback()) return true
            Log.d(TAG, "connectDevice: skip — broadcast-only mode; re-arming broadcast scan")
            handler.post { if (!stop) startBroadcastScan("connect-request") }
            // true, because we just armed our own scan. SensorBluetooth.connectToActiveDevice()
            // reads a false here as "nothing is looking for this sensor" and starts the generic
            // scanner, which cannot see an AiDex (this manager overrides no scan callback) yet
            // still spends one of Android's 5-starts-per-30s slots against our own scan.
            return true
        }
        // Guard: paused, unpaired, or stopped broadcast-only — refuse connection
        if (isPaused || isUnpaired) {
            // An unpair still latched with no link left cannot be confirmed any more: close()-only
            // teardowns (recoverFromBrokenLink, the no-stream RECONNECT, adapter-off, a 0xF2 the
            // stack refused ten times) end the link without the DISCONNECTED branch that settles
            // it, and every reconnect lands here. Keep the key and the bond, and hand the sensor to
            // the timed fallback instead of refusing into darkness. A user pause stays paused.
            if (
                pendingUnpairDisconnect && !isPaused && !stop && mBluetoothGatt == null &&
                abandonUnconfirmedUnpair("connect-with-link-gone")
            ) {
                keyExchange.reset()
                enterBroadcastOnlyFallback(
                    reason = "unpair-not-confirmed",
                    statusText = "Unpair not confirmed — key retained",
                )
                return true
            }
            Log.d(TAG, "connectDevice: skip — isPaused=$isPaused isUnpaired=$isUnpaired")
            return true  // Return true so SensorBluetooth doesn't start a scan
        }
        if (connectAttemptInFlight) {
            if (shouldRecoverBlockedReconnectNow()) {
                recoverFromStaleConnectionState(
                    reason = "blocked-unresolved-connect phase=$phase gatt=${mBluetoothGatt != null} recentLive=${hasRecentLiveData()}"
                )
            } else {
                Log.d(TAG, "connectDevice: skip — unresolved connect attempt already in flight")
            }
            return true
        }
        if (shouldRecoverBlockedReconnectNow()) {
            recoverFromStaleConnectionState(
                reason = "blocked-reconnect phase=$phase gatt=${mBluetoothGatt != null} recentLive=${hasRecentLiveData()}"
            )
            return true
        }
        if (phase != Phase.IDLE) {
            Log.d(TAG, "connectDevice: skip — already in phase $phase")
            return true
        }
        if (mBluetoothGatt != null) {
            Log.d(TAG, "connectDevice: skip — GATT already exists")
            return true
        }
        val scheduled = super.connectDevice(delayMillis)
        if (scheduled) {
            connectAttemptInFlight = true
            // servicesReady belongs to the link being replaced, and a close() that suppresses the
            // DISCONNECTED callback — softDisconnect() among them — leaves it set. resetSensor()
            // reads it as "a write can be dispatched", which a link still connecting cannot do.
            servicesReady = false
            setPhase(Phase.GATT_CONNECTING)
        }
        return scheduled
    }

    private fun hasRecentNoStreamFallbackProgress(now: Long = System.currentTimeMillis()): Boolean {
        // Wall-clock stamp of an observation: a negative age is a backward clock step, not progress.
        return noStreamFallbackReadingObservedAtMs > 0L &&
            (now - noStreamFallbackReadingObservedAtMs) in 0L until NO_STREAM_WATCHDOG_MS
    }

    private fun shouldCountSetupDisconnectForBroadcastFallback(
        disconnectPhase: Phase,
        status: Int,
    ): Boolean {
        if (status == 0 || stop || isPaused || isUnpaired) return false
        if (pendingUnpairDisconnect || pendingResetReconnect) return false
        if (reconnect.isBroadcastOnlyMode) return false
        if (status == 22) {
            return when (disconnectPhase) {
                Phase.DISCOVERING_SERVICES,
                Phase.CCCD_CHAIN,
                Phase.KEY_EXCHANGE,
                -> true
                else -> false
            }
        }
        if (bondStateAtConnection != BluetoothDevice.BOND_BONDED) return false
        if (bondBecameBondedThisConnection) return false
        return when (disconnectPhase) {
            Phase.DISCOVERING_SERVICES,
            Phase.CCCD_CHAIN,
            Phase.KEY_EXCHANGE,
            -> true
            else -> false
        }
    }

    private fun shouldEnterImmediateSetupBroadcastFallback(
        disconnectPhase: Phase,
        status: Int,
    ): Boolean {
        if (!shouldCountSetupDisconnectForBroadcastFallback(disconnectPhase, status)) return false
        return status == 22 && (disconnectPhase == Phase.CCCD_CHAIN || disconnectPhase == Phase.KEY_EXCHANGE)
    }

    /**
     * Hand the session over to broadcasts, arming the way back as a timestamp, not a timer.
     *
     * Not the only entry — these set reconnect.isBroadcastOnlyMode without coming through here:
     * [setBroadcastOnlyConnection] (the user's own choice), the auth-failures-exhausted branch of
     * the disconnect callback (status 5), [handleDeleteBondResponse] (post-unpair cleanup), and
     * [AiDexReconnect.nextAuthFailureDelayMs], which flips the mode itself when the auth budget
     * runs out — reached from the BOND_NONE branch of [bonded] as well as from that status-5
     * branch.
     *
     * Every entry owes [broadcastFallbackEnteredAtElapsed] a value: elapsedRealtime() for a
     * fallback meant to be left again, 0L for a mode the user chose or one that must not
     * re-attempt GATT on its own. The stamp does not follow the mode — reconnect.reset() and
     * reconnect.onConnectionSuccess() clear isBroadcastOnlyMode and leave the stamp standing — so
     * an entry that skips it inherits the deadline of an unrelated earlier fallback. The status-5
     * branch, the BOND_NONE branch of [bonded] and [setBroadcastOnlyConnection] all stamp 0L; the
     * one entry that stamps nothing is [handleDeleteBondResponse], and it is covered only by the
     * isUnpaired gate in [maybeLeaveBroadcastOnlyFallback] — [unpairSensor] sets isUnpaired before
     * the 0xF2 ever goes out.
     */
    private fun enterBroadcastOnlyFallback(reason: String, statusText: String) {
        close()
        consecutiveSetupDisconnects = 0
        consecutiveDiscoveryFailures = 0
        constatstatusstr = statusText
        reconnect.isBroadcastOnlyMode = true
        // The unpair entries come through here too — see the KDoc above for what every entry owes
        // this field. A sensor-acknowledged unpair runs with isUnpaired set and gets 0L: there the
        // broadcast mode is the user's own choice and holds until a re-pair. An unpair the sensor
        // never acknowledged clears isUnpaired first, because the pairing is still on the sensor,
        // so it is stamped like any other automatic fallback and the direct path is retried.
        // The test reads isUnpaired and not pendingUnpairDisconnect because the unpair entries
        // clear that flag just before calling; manualReconnectNow()/rePairSensor() clear
        // pendingUnpairDisconnect wherever they clear isUnpaired.
        broadcastFallbackEnteredAtElapsed = if (isUnpaired) 0L else SystemClock.elapsedRealtime()
        stop = false
        UiRefreshBus.requestStatusRefresh()
        handler.post { startBroadcastScan(reason) }
    }

    /**
     * Leave an automatic broadcast-only fallback once its hold has expired.
     *
     * Re-checked from the points that keep running anyway instead of armed as a callback: a
     * pending post does not survive resetConnectionRuntimeState(), which any adapter STATE_OFF
     * reaches through onBluetoothAdapterUnavailable(), so one Bluetooth or airplane-mode toggle
     * inside the hold window used to strand the sensor on broadcasts for the rest of the process.
     * While the scan loop lives it re-enters [startBroadcastScan] once per window; when it has
     * been wiped, [connectDevice] is the funnel every external revival trigger comes through.
     * Between the two the deadline cannot be missed.
     *
     * A sensor-acknowledged unpair keeps no way back on purpose — [isUnpaired] holds until the
     * user re-pairs; an unacknowledged one does not set it, and comes back like any other hold.
     *
     * Returns true when the direct path has taken over and the caller should stand down.
     */
    private fun maybeLeaveBroadcastOnlyFallback(): Boolean {
        // The post-reset bond hold never times out onto the old bond; its only exit is the bond
        // being gone. Polled on the handler — connectDevice() off it re-arms the scan, whose window
        // lands back here.
        if (postResetBondHold != null) {
            return Thread.currentThread() === handlerThread && pollPostResetBondHold("scan-window")
        }
        // Screen off (the last gate, evaluated only once the hold has expired): every deadline on
        // the way back — the connect and key-exchange watchdogs, the reconnect backoff — is a
        // handler post on uptime, which stops in suspend, while the scan alarm is what keeps
        // running in doze. Leaving now can strand the sensor with neither a scan nor a session
        // until the screen comes on, so stay on advertisements and retry on the first scan tick
        // after it does.
        val exit = AiDexRuntimePolicy.decideBroadcastFallbackExit(
            enteredAtElapsed = broadcastFallbackEnteredAtElapsed,
            broadcastOnly = reconnect.isBroadcastOnlyMode,
            stop = stop,
            paused = isPaused,
            unpaired = isUnpaired,
            nowElapsed = SystemClock.elapsedRealtime(),
            holdMs = BROADCAST_FALLBACK_RETRY_MS,
            screenInteractive = {
                (Applic.app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive
            },
        )
        if (exit == AiDexRuntimePolicy.BroadcastFallbackExit.STAY) return false

        Log.i(
            TAG,
            "Broadcast fallback held for ${BROADCAST_FALLBACK_RETRY_MS / 60_000L}min — retrying the direct path once"
        )
        broadcastFallbackEnteredAtElapsed = 0L
        reconnect.isBroadcastOnlyMode = false
        connectDevice(0)
        // connectDevice() answers true from six refusal branches — Bluetooth off, paused or
        // unpaired, an unresolved attempt in flight, a stale-reconnect recovery, a non-IDLE phase,
        // a GATT that already exists — because SensorBluetooth reads a false as "nobody is looking
        // for this sensor" and starts its own scanner, which cannot see an AiDex. Reading that bare
        // true as success is what leaves the sensor with neither a GATT session nor a scan loop:
        // only the branch that really schedules sets these two.
        if (connectAttemptInFlight || phase == Phase.GATT_CONNECTING) {
            // One direct attempt per hold, not a fresh ladder. With the count at zero the retry
            // spent the whole threshold — minutes of stalls with no scan, since every stale recovery
            // on the way wipes it — before handing back. CONNECTED clears the count if the link
            // comes up.
            consecutiveConnectFailures = CONNECT_FAILURE_BROADCAST_FALLBACK_THRESHOLD - 1
            // Same for the discovery ladder, which enterBroadcastOnlyFallback() zeroed on the way in.
            // The invalid-setup count is deliberately not preloaded: this runs after every hold,
            // whatever caused it, and would cut an unvalidated bond off REMOVE_BOND for good.
            consecutiveDiscoveryFailures = DISCOVERY_FAILURE_BROADCAST_FALLBACK_THRESHOLD - 1
            return true
        }
        // Nothing was scheduled — go back to scanning rather than leave the user with neither a
        // GATT session nor broadcasts, and restart the hold so the next window tries again.
        reconnect.isBroadcastOnlyMode = true
        broadcastFallbackEnteredAtElapsed = SystemClock.elapsedRealtime()
        return false
    }

    private fun shouldSuppressExternalReconnect(now: Long): Boolean {
        if (phase != Phase.STREAMING) return false
        if (stop || isPaused || isUnpaired || reconnect.isBroadcastOnlyMode) return false
        if (mBluetoothGatt == null) return false
        if (hasRecentLiveData(now)) return true
        if (historyDownloading || pendingInitialHistoryRequest) return true
        if (hasRecentNoStreamFallbackProgress(now)) return true
        return false
    }

    override fun reconnect(now: Long): Boolean {
        if (shouldSuppressExternalReconnect(now)) {
            Log.i(
                TAG,
                "reconnect: suppressing external reconnect while streaming session is still progressing " +
                    "(recentLive=${hasRecentLiveData(now)}, historyDownloading=$historyDownloading, " +
                    "pendingInitialHistoryRequest=$pendingInitialHistoryRequest, " +
                    "noStreamFallbackProgress=${hasRecentNoStreamFallbackProgress(now)})"
            )
            return true
        }
        return super.reconnect(now)
    }

    // =========================================================================
    // GATT Callbacks
    // =========================================================================

    // Consecutive GATT_CONNECTING failures.
    // A hard-to-reach sensor retried every few seconds can wedge the Bluetooth stack, so
    // these are backed off progressively. Reset on a successful connection.
    private var consecutiveConnectFailures = 0

    /**
     * Clear the Android GATT service cache for this connection via the hidden
     * BluetoothGatt.refresh() (reflection). Helps recover from the stale connection
     * state that causes persistent status-133 connect failures.
     */
    private fun refreshDeviceCache(gatt: BluetoothGatt?) {
        if (gatt == null) return
        try {
            val ok = gatt.javaClass.getMethod("refresh").invoke(gatt) as? Boolean ?: false
            Log.i(TAG, "refreshDeviceCache: gatt.refresh()=$ok")
        } catch (t: Throwable) {
            Log.w(TAG, "refreshDeviceCache: refresh() failed: ${t.message}")
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        noteFirstGattCallback("onConnectionStateChange", gatt)
        super.onConnectionStateChange(gatt, status, newState)
        if (newState == BluetoothProfile.STATE_CONNECTED || newState == BluetoothProfile.STATE_DISCONNECTED) {
            connectAttemptInFlight = false
        }

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            val now = System.currentTimeMillis()
            val connectCallbackAgeMs = if (phase == Phase.GATT_CONNECTING && phaseStartedAtMs > 0L) {
                (now - phaseStartedAtMs).coerceAtLeast(0L)
            } else {
                -1L
            }
            if (connectCallbackAgeMs >= 0L) {
                reconnect.recordConnectCallbackAgeMs(connectCallbackAgeMs)
                Log.i(
                    TAG,
                    "Connect callback after ${connectCallbackAgeMs}ms " +
                        "(slowStreak=${reconnect.slowExecuteStreak} timeout=${reconnect.currentConnectAttemptTimeoutMs()}ms)"
                )
            }
            connectTime = now
            constatstatusstr = "Connected"
            consecutiveConnectFailures = 0  // link established — clear the connect-failure streak
            _isPaused = false  // Clear paused flag — connection is active
            cancelBroadcastScan()
            liveOffsetCutoff = 0  // Reset live offset cutoff for this connection session
            setPhase(Phase.DISCOVERING_SERVICES)
            if (postResetRequestedAtMs > 0L) {
                resetDiag("reconnect-connected", "address=${gatt.device?.address ?: "?"}", nowMs = now)
            }

            // Reset per-connection state
            bondStateAtConnection = gatt.device?.bondState ?: BluetoothDevice.BOND_NONE
            bondBecameBondedThisConnection = false
            if (bondStateAtConnection != BluetoothDevice.BOND_BONDED) {
                setBondValidatedByStreaming(false, "new-connection-unbonded")
                // The rejection count belongs to one bond. unpairSensor(), forgetVendor(),
                // removeBondSafely() and the deferred unpair branch all destroy it, and carrying
                // the old count onto the bond this connection is about to create would escalate
                // to bond removal on its first rejection. Cleared here rather than at each removal
                // site: this is the one place that sees every way a bond can disappear, the user
                // removing it in Android settings included, and nothing consumes the count before
                // KEY_EXCHANGE. Deliberately not in rePairSensor()/manualReconnectNow(): those do
                // not recreate the bond, so a bonded device keeps its count and stays escalatable.
                postBondConfigRejections = 0
            }
            keyExchange.reset()
            keyExchangeUsingSavedPairKey = false
            keyExchangeIsFreshPairAfterReset = false
            pairKeyAwaitingLiveValidation = false
            challengeWritten = false
            bondDataRead = false
            servicesReady = false
            cccdChainComplete = false
            keyExchangePendingBond = false
            postCccdFollowUp = PostCccdFollowUp.NONE
            // Same handover as resetConnectionRuntimeState: this branch runs on the binder
            // thread, and the queues plus their write bookkeeping belong to the handler.
            runOnHandler {
                gattQueue.clear()
                gattOpActive = false
                queuePausedForBonding = false
                currentGattOp = null
                cccdQueue.clear()
                cccdWriteInProgress = false
                cccdRetryCount = 0
                pendingBondedCccdUuid = null
                consumeThenClearSetNewSensorConnectionFlags()
                historyDownloading = false
                historyNewestOffset = 0
                historyPhase = HistoryPhase.IDLE
                historyPageTimedOut = false
                historyTimeoutRestartUsed = false
                historyRangeRetryUsed = false
                invalidateStaleHistoryResponses("connected")
            }
            startupMetadataComplete = hasCompleteStartupMetadata()
            startupDeviceInfoRequested = false
            legacyStartTimeRequested = false
            autoActivationAttemptedThisConnection = false
            streamingStartedAtMs = 0L
            lastF003FrameTimeMs = 0L
            lastDirectLiveReadingTimeMs = 0L
            lastLiveReadingObservedTimeMs = 0L
            lastLiveContinuitySyncBucket = -1L
            noStreamConnectedBroadcastAttempted = false
            noStreamRecoveryAttempted = false
            noStreamHistoryRecoveryAttempted = false
            noStreamFallbackReadingObservedAtMs = 0L
            postBondLiveRefreshAttempted = false
            pendingInitialHistoryRequest = false
            // historyPhase / download flags are cleared on the handler with the generation bump
            // so an in-flight 0x22 cannot accept after CONNECTED invalidates the old generation.
            // historyRoomBufferDropped is deliberately NOT cleared here: it is a debt towards
            // Room, not link state. The usual reason a page timed out is the same radio drop that
            // brings us back to this branch, so clearing it on the new connection loses every
            // dropped batch — its rows are already in the native store and the persisted cursors
            // have moved past them, so nothing ever re-requests those minutes. Paid and cleared by
            // onHistoryDownloadComplete.
            pendingDefaultParamAutoProvisioning = false
            pendingDefaultParamAutoProvisioningReason = null
            pendingDefaultParamAutoProvisioningScheduled = false
            defaultParamAutoProvisioningAttemptedThisConnection = false
            pendingStreamingMetadataRead = false
            pendingStreamingMetadataReason = null
            pendingStreamingMetadataScheduled = false
            pendingCalibrationRefresh = false
            pendingCalibrationRefreshReason = null
            pendingCalibrationRefreshScheduled = false
            calibrationRangeKnownEmpty = false
            calibrationDownloading = false
            calibrationFetchAttempted = false
            startupControlStage = StartupControlStage.IDLE
            lastF002FrameTimeMs = 0L
            negotiatedMtu = 23
            clearDefaultParamProbeState()
            clearDefaultParamApplyState()
            defaultParamProbeUserInitiated = false
            noDirectLiveBroadcastFallbackMode = false
            // Same debt as above: a connection that arrives without a teardown before it drops the
            // previous download's batch, whose rows are in the native store and behind the
            // persisted cursors.
            if (pendingRoomHistoryTimestamps.isNotEmpty()) historyRoomBufferDropped = true
            clearPendingRoomHistory("new-connection")
            handler.removeCallbacks(noStreamWatchdog)
            handler.removeCallbacks(delayedInitialHistoryRequest)
            handler.removeCallbacks(delayedStreamingMetadataRequest)
            handler.removeCallbacks(delayedCalibrationRefreshRequest)
            handler.removeCallbacks(startupControlAckTimeout)
            pendingInvalidSetupRecovery = PendingInvalidSetupRecovery.NONE
            pendingStaleConnectionRecovery = false
            // A reset still latched here ended without completePostResetReconnect. Either close()
            // suppressed the DISCONNECTED callback — recoverFromBrokenLink(), softDisconnect(), the
            // no-stream RECONNECT, completeStaleConnectionRecovery() and
            // handlePendingInvalidSetupRecovery() all close() the link — or a teardown on another
            // thread released the window inside resetSensor() before the latch was set. The two
            // timers that would have finished it died with that teardown's handler wipe, so this
            // connection decides it. Left latched, the first ordinary disconnect hours later would
            // take the post-reset branch and remove a bond nobody asked to remove.
            //   - Not confirmed: the verdict abandonPendingReset gives everywhere else. Its lock
            //     also decides a 0x01 claim racing this line.
            //   - Confirmed, and this link came up on the old Android bond: do not adopt it. The
            //     sensor dropped its side of that pairing, so completePostResetReconnect removes the
            //     bond once and holds until BOND_NONE, as after an ordinary post-erase disconnect.
            //   - Confirmed and unbonded: this is the verification connection. The debt and the
            //     barrier are real and stay; the latch and the window go, and with them the
            //     write/ACK stamps — a verification connection reached through
            //     completePostResetReconnect never enters this block and keeps them for RESET_DIAG.
            if (pendingResetReconnect) {
                resetDiag("stale-reset-dropped-on-connect", "address=${gatt.device?.address ?: "?"}", nowMs = now)
                if (postResetClearStorageAckAtMs == 0L && abandonPendingReset("stale-reset-dropped-on-connect")) {
                    postResetClearStorageWriteAtMs = 0L
                } else if (bondStateAtConnection != BluetoothDevice.BOND_NONE) {
                    completePostResetReconnect(trigger = "connected-on-old-bond", stateAlreadyReset = false)
                    return
                } else {
                    synchronized(resetClaimLock) {
                        pendingResetReconnect = false
                        clearStorageQuietWindowActive = false
                    }
                    handler.removeCallbacks(clearStorageQuietWindowReconnect)
                    handler.removeCallbacks(postResetDisconnectFallback)
                    postResetClearStorageWriteAtMs = 0L
                    postResetClearStorageAckAtMs = 0L
                }
            }
            clearInvalidSetupTracking(resetRecoveryCounter = false, reason = "new-connection")
            broadcastScanMisses = 0
            lastBroadcastGlucose = 0f
            lastBroadcastTime = 0L
            lastBroadcastStoredTime = 0L
            lastBroadcastStoredOffsetMinutes = -1
            broadcastScanStartedAtElapsed = 0L
            lastBroadcastOffsetSeen = -1L
            lastBroadcastTrendSeen = Int.MIN_VALUE
            lastBroadcastGlucoseSeen = Int.MIN_VALUE
            lastBroadcastOffsetSeenAtMs = 0L
            observedBroadcastCadenceMs = DEFAULT_BROADCAST_CADENCE_MS
            phaseLockHits = 0
            lastBroadcastOffsetForCadence = -1
            lastFreshBroadcastElapsedMs = 0L

            serviceDiscoveryStarted = false
            lastMtuCallbackAtMs = 0L
            mtuExchangeCrossedPendingCccd = false
            shortBondReads = 0
            handler.removeCallbacks(cccdChainStartAfterMtuSettle)
            Log.i(TAG, "Connected to ${gatt.device?.address}. Requesting MTU 512...")
            val mtuRequested = runCatching { gatt.requestMtu(512) }.getOrDefault(false)
            handler.removeCallbacks(broadcastAssistRunnable)
            handler.postDelayed(broadcastAssistRunnable, BROADCAST_ASSIST_SCAN_DELAY_MS)

            // Service discovery waits for onMtuChanged. Discovering while the MTU exchange is
            // still outstanding lets the first CCCD descriptor write race it, and that write is
            // then never completed — the link survives but no GATT op on it can ever succeed.
            handler.removeCallbacks(mtuCallbackTimeout)
            if (mtuRequested) {
                handler.postDelayed(mtuCallbackTimeout, MTU_CALLBACK_TIMEOUT_MS)
            } else {
                Log.w(TAG, "requestMtu(512) was refused — discovering services immediately")
                beginServiceDiscovery(gatt, "mtu-request-refused")
            }

        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            val disconnectPhase = phase
            Log.i(TAG, "Disconnected. status=$status")
            logDisconnectContext(gatt, status, disconnectPhase)
            lastLiveReadingObservedTimeMs = 0L
            lastLiveContinuitySyncBucket = -1L
            constatstatusstr = pairingKeyProblemStatus ?: "Disconnected"
            connectTime = 0L
            setPhase(Phase.IDLE)
            resetConnectionRuntimeState(reason = "disconnect", resetInvalidSetupCounter = false)

            // Who owns this disconnect. An unpair the sensor has not confirmed, and a reset it has —
            // resetConnectionRuntimeState just abandoned an unconfirmed one — come first: behind the
            // recovery and status-5 exits, an unpair stayed latched with connectDevice() refusing
            // every reconnect, and a confirmed erase went back onto the old bond.
            when (
                AiDexRuntimePolicy.decideDisconnectOwner(
                    unpairPending = pendingUnpairDisconnect,
                    resetPending = pendingResetReconnect,
                    invalidSetupRecoveryPending = pendingInvalidSetupRecovery != PendingInvalidSetupRecovery.NONE,
                    staleRecoveryPending = pendingStaleConnectionRecovery,
                    status = status,
                )
            ) {
                AiDexRuntimePolicy.DisconnectOwner.UNCONFIRMED_UNPAIR -> {
                    // Delivery without the sensor ACK is ambiguous: keep the credential and the bond.
                    if (abandonUnconfirmedUnpair("disconnect status=$status")) {
                        pendingInvalidSetupRecovery = PendingInvalidSetupRecovery.NONE
                        pendingStaleConnectionRecovery = false
                        keyExchange.reset()
                        enterBroadcastOnlyFallback(
                            reason = "unpair-not-confirmed",
                            statusText = "Unpair not confirmed — key retained",
                        )
                        return
                    }
                    // Lost the claim: the DELETE_BOND ACK is settling the unpair right now. The
                    // disconnect still takes the ordinary tail below — a refused 0xF2 leaves the
                    // sensor paired and waiting for its reconnect.
                }
                AiDexRuntimePolicy.DisconnectOwner.POST_RESET -> {
                    pendingInvalidSetupRecovery = PendingInvalidSetupRecovery.NONE
                    pendingStaleConnectionRecovery = false
                    resetDiag(
                        stage = "disconnect-callback",
                        details = "status=$status disconnectPhase=$disconnectPhase",
                    )
                    completePostResetReconnect(
                        trigger = "sensor-disconnect",
                        stateAlreadyReset = true,
                    )
                    return
                }
                AiDexRuntimePolicy.DisconnectOwner.INVALID_SETUP_RECOVERY -> {
                    Log.w(TAG, "Disconnect is owned by invalid-setup recovery (${pendingInvalidSetupRecovery.name})")
                    handlePendingInvalidSetupRecovery()
                    return
                }
                AiDexRuntimePolicy.DisconnectOwner.STALE_RECOVERY -> {
                    Log.w(TAG, "Disconnect is owned by stale-connection recovery")
                    completeStaleConnectionRecovery("disconnect-callback", stateAlreadyReset = true)
                    return
                }
                AiDexRuntimePolicy.DisconnectOwner.AUTH_FAILURE,
                AiDexRuntimePolicy.DisconnectOwner.DEFAULT,
                -> Unit
            }

            // Handle specific failure cases
            when (status) {
                5 -> { // GATT_INSUFFICIENT_AUTHENTICATION
                    consecutiveSetupDisconnects = 0
                    val delay = reconnect.nextAuthFailureDelayMs()
                    if (delay != null) {
                        Log.i(TAG, "Auth failure — reconnecting in ${delay}ms (attempt ${reconnect.authFailureCount})")
                        close()
                        handler.postDelayed({ connectDevice(0) }, delay)
                    } else {
                        Log.w(TAG, "Auth failures exhausted — broadcast-only fallback")
                        close()
                        constatstatusstr = Applic.getContext().getString(R.string.aidex_status_pairing_failed_broadcast_only)
                        reconnect.isBroadcastOnlyMode = true
                        // Deliberately no timed return (0 = no hold): going back to GATT here means
                        // another bonding cycle against a sensor that just refused every attempt,
                        // and a failed bond can take the working pairing with it. Broadcasts keep
                        // the readings coming; the way out is the user's Reconnect / Re-pair.
                        broadcastFallbackEnteredAtElapsed = 0L
                        stop = false
                        handler.post { startBroadcastScan("auth-failure-fallback") }
                        UiRefreshBus.requestStatusRefresh()
                    }
                    return
                }
                19 -> { // GATT_CONN_TERMINATE_PEER_USER — normal disconnect from sensor
                    consecutiveSetupDisconnects = 0
                    Log.i(TAG, "Sensor terminated connection (normal)")
                }
                else -> {
                    if (status != 0) {
                        Log.w(TAG, "Unexpected disconnect status=$status")
                    }
                }
            }

            val countSetupDisconnect = shouldCountSetupDisconnectForBroadcastFallback(
                disconnectPhase = disconnectPhase,
                status = status,
            )
            if (countSetupDisconnect) {
                consecutiveSetupDisconnects += 1
                Log.w(
                    TAG,
                    "Early setup disconnect during $disconnectPhase (status=$status) — " +
                        "broadcast assist attempt ${consecutiveSetupDisconnects}/$SETUP_DISCONNECT_BROADCAST_FALLBACK_THRESHOLD"
                )
                if (shouldEnterImmediateSetupBroadcastFallback(disconnectPhase, status)) {
                    Log.w(TAG, "Setup disconnect status=$status during $disconnectPhase looks like pair/takeover rejection — entering broadcast-only fallback immediately")
                    enterBroadcastOnlyFallback(
                        reason = "setup-disconnect-status-$status",
                        statusText = "Pair rejected — Broadcast Only",
                    )
                    return
                }
                handler.post { startBroadcastScan("setup-disconnect-assist", continuous = false) }
                if (consecutiveSetupDisconnects >= SETUP_DISCONNECT_BROADCAST_FALLBACK_THRESHOLD) {
                    Log.w(TAG, "Repeated early setup disconnects exhausted — entering broadcast-only fallback")
                    enterBroadcastOnlyFallback(
                        reason = "setup-disconnect-fallback",
                        statusText = "Broadcast fallback",
                    )
                    return
                }
            } else if (status != 0) {
                consecutiveSetupDisconnects = 0
            }

            // Schedule reconnect — but NOT if paused (stop=true) or broadcast-only. An unconfirmed
            // unpair and a confirmed reset were handed off above.
            if (stop) {
                consecutiveSetupDisconnects = 0
                Log.i(TAG, "Paused (stop=true) — not scheduling reconnect")
                close()
            } else if (!reconnect.isBroadcastOnlyMode) {
                // status 133 (GATT_ERROR / CONNECTION_FAILED_ESTABLISHMENT) is a hard
                // link-layer connect failure; retrying every few seconds can wedge the
                // Bluetooth stack (as seen in the field). Back off progressively and clear
                // the stale GATT cache before retrying. Other statuses use the normal delay.
                val extraDelay = if (status == 133) {
                    consecutiveConnectFailures += 1
                    refreshDeviceCache(gatt)
                    (consecutiveConnectFailures.toLong() * 3_000L).coerceAtMost(30_000L)
                } else {
                    // Any other status that ends an attempt still in GATT_CONNECTING is the same
                    // failure as far as the fallback is concerned — a direct connect on Android 15+
                    // times out with 147 — so it counts instead of wiping what the watchdog and
                    // earlier 133s counted. CONNECTED clears the count once a link is up.
                    if (disconnectPhase == Phase.GATT_CONNECTING && status != 0) consecutiveConnectFailures += 1
                    0L
                }
                if (consecutiveConnectFailures >= CONNECT_FAILURE_BROADCAST_FALLBACK_THRESHOLD) {
                    // The phone can still hear this sensor's advertisements; what it cannot do is
                    // raise a GATT link. Broadcast-only is where the driver's three other ladders
                    // end, and its 10-minute hold retries the direct path on its own.
                    Log.w(
                        TAG,
                        "Connect failed $consecutiveConnectFailures times in a row — entering broadcast-only fallback"
                    )
                    consecutiveConnectFailures = 0
                    // enterBroadcastOnlyFallback() closes the link and posts the scan itself.
                    enterBroadcastOnlyFallback(
                        reason = "connect-failure-fallback",
                        statusText = "Broadcast fallback",
                    )
                } else {
                    val delay = reconnect.nextReconnectDelayMs() + extraDelay
                    Log.i(TAG, "Scheduling reconnect in ${delay}ms (attempt ${reconnect.softAttempts}, connectFailures=$consecutiveConnectFailures)")
                    close()
                    handler.postDelayed({ connectDevice(0) }, delay)
                }
            } else {
                Log.i(TAG, "Broadcast-only mode — starting broadcast scan instead of reconnect")
                close()
                UiRefreshBus.requestStatusRefresh()
                handler.postDelayed({ startBroadcastScan("post-disconnect") }, 2_000L)
            }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        super.onMtuChanged(gatt, mtu, status)
        if (gatt !== mBluetoothGatt) {
            Log.w(TAG, "onMtuChanged: stale callback, ignoring")
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            negotiatedMtu = mtu
            Log.i(TAG, "MTU negotiated: $mtu")
        } else {
            Log.w(TAG, "onMtuChanged: status=$status mtu=$mtu")
        }
        lastMtuCallbackAtMs = System.currentTimeMillis()

        // The ATT bearer is free again — now it is safe to discover and write CCCDs.
        handler.removeCallbacks(mtuCallbackTimeout)
        if (serviceDiscoveryStarted || servicesReady) {
            // A later exchange on a link already past discovery — typically the sensor's own,
            // a few hundred ms after connect. What it means for the CCCD chain is decided on the
            // handler, where the chain is built and every CCCD write is issued.
            val cccdWriteSeqAtExchange = cccdWriteSeq
            handler.post { onLateMtuExchange(gatt, cccdWriteSeqAtExchange) }
            return
        }
        beginServiceDiscovery(gatt, "mtu-callback")
    }

    /** Handler side of an `onMtuChanged` that landed after service discovery had started. */
    private fun onLateMtuExchange(gatt: BluetoothGatt, cccdWriteSeqAtExchange: Long) {
        if (gatt !== mBluetoothGatt) return
        if (
            AiDexRuntimePolicy.mtuExchangeCrossedPendingCccd(
                phase = phase,
                cccdWriteInProgress = cccdWriteInProgress,
                hasPendingCccd = cccdPendingWriteUuid != null,
            )
        ) {
            if (cccdWriteSeq != cccdWriteSeqAtExchange) {
                // The handler issued this write after the exchange had already completed, e.g.
                // the next one in the chain after the previous write's callback.
                Log.i(TAG, "MTU exchange completed before the CCCD write on $cccdPendingWriteUuid went out — not flagging it")
                return
            }
            // The sensor's own exchange landed on top of our outstanding Write Request. That
            // write is expected never to call back; the watchdog reconnects if it does not,
            // instead of inferring success and having the next write refused.
            Log.w(TAG, "MTU exchange crossed pending CCCD write on $cccdPendingWriteUuid — expecting its callback to be lost")
            mtuExchangeCrossedPendingCccd = true
            return
        }
        if (phase == Phase.CCCD_CHAIN && !cccdWriteInProgress && cccdQueue.isNotEmpty()) {
            // Chain not started yet: a late exchange restarts the quiet window.
            scheduleCccdChainStart(gatt, "late-mtu-callback")
        }
    }

    /**
     * Issue the first CCCD write now, or once [MTU_SETTLE_BEFORE_CCCD_MS] has passed since
     * the last `onMtuChanged`. Sensors that exchange MTU once pay nothing here.
     */
    private fun scheduleCccdChainStart(gatt: BluetoothGatt, reason: String) {
        handler.removeCallbacks(cccdChainStartAfterMtuSettle)
        val delay = AiDexRuntimePolicy.cccdStartDelayMs(
            lastMtuCallbackAtMs = lastMtuCallbackAtMs,
            nowMs = System.currentTimeMillis(),
            settleMs = MTU_SETTLE_BEFORE_CCCD_MS,
        )
        if (delay <= 0L) {
            writeNextCccd(gatt)
            return
        }
        Log.i(TAG, "Holding first CCCD write ${delay}ms for the MTU exchange to settle ($reason)")
        handler.postDelayed(cccdChainStartAfterMtuSettle, delay)
    }

    /**
     * Start service discovery exactly once per connection.
     *
     * Only ever called when the ATT bearer is known to be free: from [onMtuChanged], or straight
     * away when `requestMtu()` itself was refused so no exchange was ever started. Never call it
     * while an MTU exchange is still in flight — see [MTU_CALLBACK_TIMEOUT_MS].
     */
    private fun beginServiceDiscovery(gatt: BluetoothGatt, reason: String) {
        if (gatt !== mBluetoothGatt) return
        if (serviceDiscoveryStarted || servicesReady) return
        serviceDiscoveryStarted = true
        Log.i(TAG, "Discovering services ($reason)...")
        gatt.discoverServices()
        scheduleDiscoveryRetries(gatt)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        super.onServicesDiscovered(gatt, status)

        if (gatt !== mBluetoothGatt) {
            Log.w(TAG, "onServicesDiscovered: stale callback, ignoring")
            return
        }
        if (servicesReady || phase != Phase.DISCOVERING_SERVICES) {
            Log.i(TAG, "onServicesDiscovered: duplicate callback in phase=$phase servicesReady=$servicesReady — ignoring")
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "onServicesDiscovered: failed status=$status — triggering recovery")
            recoverFromServiceDiscoveryFailure()
            return
        }

        val service = gatt.getService(SERVICE_F000)
        if (service == null) {
            Log.e(TAG, "onServicesDiscovered: SERVICE_F000 (0x181F) not found! Triggering recovery")
            recoverFromServiceDiscoveryFailure()
            return
        }

        servicesReady = true
        consecutiveDiscoveryFailures = 0
        setPhase(Phase.CCCD_CHAIN)
        handler.removeCallbacks(cccdWriteWatchdog)

        // Build CCCD queue: F003 first (data), then F002 (commands), then F001 (auth) — and F001
        // only for a fresh pairing (no key, an exhausted key being replaced, or a key an accepted
        // erase has wiped): a saved-key reconnect never touches it, so the sensor is never asked
        // for a credential this phone holds and the sensor still has.
        // On the handler: this is a binder callback, while the same queue is cleared from the
        // connection callbacks and from the main-thread bond broadcast. Posting the build makes
        // those clears ordered ahead of it instead of interleaved with it, and keeps every
        // writeNextCccd on one thread (the retry, watchdog and refresh paths already are). The
        // saved-key/fresh-pair decision is taken on that same thread, so the queue it shapes
        // cannot be built from a decision another thread has already invalidated.
        handler.post {
            if (mBluetoothGatt !== gatt) return@post
            notePostResetFreshPairConnection()
            val keyStartAction = decidePairKeyStartAction()
            Log.i(
                TAG,
                when (keyStartAction) {
                    AiDexRuntimePolicy.PairKeyStartAction.USE_SAVED_KEY ->
                        "Services discovered. Starting saved-key CCCD chain..."
                    AiDexRuntimePolicy.PairKeyStartAction.FRESH_PAIR ->
                        "Services discovered. Starting fresh-pair CCCD chain..."
                }
            )
            cccdQueue.clear()
            cccdQueue.add(CHAR_F003)
            cccdQueue.add(CHAR_F002)
            if (keyStartAction == AiDexRuntimePolicy.PairKeyStartAction.FRESH_PAIR) {
                cccdQueue.add(CHAR_F001)
            }
            cccdWriteInProgress = false
            cccdPendingWriteUuid = null
            cccdMissingCallbackRetries = 0

            scheduleCccdChainStart(gatt, "services-discovered")
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (gatt !== mBluetoothGatt) {
            Log.w(TAG, "onDescriptorWrite: stale callback, ignoring")
            return
        }

        val charUuid = descriptor.characteristic.uuid
        handler.post {
            handleDescriptorWrite(gatt, charUuid, status)
        }
    }

    private fun handleDescriptorWrite(gatt: BluetoothGatt, charUuid: UUID, status: Int) {
        if (gatt !== mBluetoothGatt) {
            Log.w(TAG, "onDescriptorWrite: stale posted callback for $charUuid, ignoring")
            return
        }

        if (
            phase == Phase.KEY_EXCHANGE &&
            postCccdFollowUp == PostCccdFollowUp.NONE &&
            challengeWritten &&
            cccdQueue.isEmpty()
        ) {
            Log.i(TAG, "onDescriptorWrite: late initial CCCD callback for $charUuid after key exchange start — ignoring")
            return
        }

        val pendingUuid = cccdPendingWriteUuid
        if (pendingUuid != charUuid) {
            Log.i(
                TAG,
                "onDescriptorWrite: late/mismatched CCCD callback for $charUuid " +
                    "(pending=$pendingUuid status=$status) — ignoring"
            )
            return
        }

        handler.removeCallbacks(cccdWriteWatchdog)
        cccdPendingWriteUuid = null
        cccdMissingCallbackRetries = 0
        if (mtuExchangeCrossedPendingCccd) {
            Log.i(TAG, "onDescriptorWrite: CCCD $charUuid callback arrived despite the crossed MTU exchange")
            mtuExchangeCrossedPendingCccd = false
        }

        if (isAuthRelatedCccdFailure(status)) {
            Log.i(TAG, "onDescriptorWrite: CCCD $charUuid auth/perm fail (status=$status) — re-queuing for retry after bond")
            // Re-queue this characteristic for retry after bonding
            cccdQueue.addFirst(charUuid)
            cccdWriteInProgress = false
            if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                handler.postDelayed({
                    if (mBluetoothGatt === gatt && !cccdWriteInProgress && cccdQueue.peekFirst() == charUuid) {
                        Log.i(TAG, "onDescriptorWrite: retrying CCCD $charUuid after bonded settle")
                        writeNextCccd(gatt)
                    }
                }, 500L)
            } else if (gatt.device.bondState == BluetoothDevice.BOND_BONDING) {
                pendingBondedCccdUuid = charUuid
                scheduleDeferredBondCompletionCheck(gatt, attempt = 1)
            }
            return
        }

        finishCccdWrite(gatt, charUuid, status, inferred = false)
    }

    private fun finishCccdWrite(gatt: BluetoothGatt, charUuid: UUID, status: Int, inferred: Boolean) {
        handler.removeCallbacks(cccdWriteWatchdog)
        cccdPendingWriteUuid = null

        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "onDescriptorWrite: CCCD $charUuid failed status=$status")
            cccdWriteInProgress = false
            lastInferredCccdUuid = null
            // Try next anyway
        } else {
            val suffix = if (inferred) " (callback inferred)" else ""
            Log.i(TAG, "onDescriptorWrite: CCCD $charUuid enabled successfully$suffix")
            cccdWriteInProgress = false
            cccdMissingCallbackRetries = 0
            lastInferredCccdUuid = if (inferred) charUuid else null
            if (pendingBondedCccdUuid == charUuid) {
                pendingBondedCccdUuid = null
            }
        }

        // Continue CCCD chain
        if (cccdQueue.isNotEmpty()) {
            if (inferred) {
                handler.postDelayed({ if (mBluetoothGatt === gatt) writeNextCccd(gatt) }, 1_000L)
            } else {
                writeNextCccd(gatt)
            }
        } else {
            cccdChainComplete = true

            when (postCccdFollowUp) {
                PostCccdFollowUp.ENTER_STREAMING -> {
                    postCccdFollowUp = PostCccdFollowUp.NONE
                    Log.i(TAG, "Post-key-exchange CCCD re-registration complete. Entering streaming...")
                    enterStreamingPhase(requestHistory = true)
                    return
                }
                PostCccdFollowUp.RESUME_STREAMING -> {
                    postCccdFollowUp = PostCccdFollowUp.NONE
                    Log.i(TAG, "Live CCCD refresh complete. Waiting for F003 stream...")
                    resumeStreamingAfterLiveCccdRefresh()
                    return
                }
                PostCccdFollowUp.NONE -> Unit
            }

            // Initial CCCD chain complete — check bond state before starting key exchange
            val bondState = gatt.device.bondState
            Log.i(TAG, "All CCCDs enabled. Bond state: $bondState")

            when (bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    // Already bonded (reconnect case, or bonding finished during CCCD chain).
                    // Small delay to let encryption fully settle.
                    Log.i(TAG, "Already bonded. Starting key exchange after 500ms settle delay...")
                    handler.postDelayed({ startKeyExchangeForCurrentConnection(gatt) }, 500L)
                }
                BluetoothDevice.BOND_BONDING -> {
                    // Bonding in progress — defer key exchange to bonded() callback.
                    // The sensor ignores writes on an unencrypted link.
                    Log.i(TAG, "Bonding in progress. Deferring key exchange until BOND_BONDED...")
                    keyExchangePendingBond = true
                    scheduleDeferredBondCompletionCheck(gatt, attempt = 1)
                }
                else -> {
                    // Unbonded. On the saved-key path, read F002 with the key right here —
                    // F002/F003 do not need link encryption, and asking for a bond is what the
                    // sensor refuses (BOND_NONE, then status 22). On the fresh path (no key, an
                    // exhausted key replaced on the user's Pair, or one an accepted erase wiped)
                    // the chain has F001 enabled and writing the challenge makes the sensor
                    // initiate pairing.
                    Log.i(TAG, "Unbonded AiDex link — starting key exchange without requesting a bond")
                    startKeyExchangeForCurrentConnection(gatt)
                }
            }
        }
    }

    private fun scheduleDeferredBondCompletionCheck(gatt: BluetoothGatt, attempt: Int) {
        handler.postDelayed({
            if (mBluetoothGatt !== gatt) {
                return@postDelayed
            }

            val waitingForBondedCccd =
                phase == Phase.CCCD_CHAIN &&
                    !cccdWriteInProgress &&
                    pendingBondedCccdUuid != null &&
                    cccdQueue.peekFirst() == pendingBondedCccdUuid

            val waitingForDeferredKeyExchange = cccdChainComplete && keyExchangePendingBond

            if (!waitingForBondedCccd && !waitingForDeferredKeyExchange) {
                return@postDelayed
            }

            when (gatt.device.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    if (bondStateAtConnection != BluetoothDevice.BOND_BONDED) {
                        bondBecameBondedThisConnection = true
                    }

                    if (waitingForBondedCccd) {
                        val charUuid = pendingBondedCccdUuid
                        pendingBondedCccdUuid = null
                        Log.w(TAG, "BOND_BONDED observed via fallback check — resuming CCCD chain for $charUuid")
                        if (charUuid != null && !cccdQueue.contains(charUuid)) {
                            cccdQueue.addFirst(charUuid)
                        }
                        writeNextCccd(gatt)
                    }

                    if (waitingForDeferredKeyExchange) {
                        keyExchangePendingBond = false
                        Log.w(TAG, "BOND_BONDED observed via fallback check — starting deferred key exchange")
                        startKeyExchangeForCurrentConnection(gatt)
                    }
                }
                BluetoothDevice.BOND_BONDING -> {
                    if (attempt < DEFERRED_BOND_CHECK_MAX_ATTEMPTS) {
                        Log.i(
                            TAG,
                            "Deferred bond check: still bonding after ${attempt * DEFERRED_BOND_CHECK_DELAY_MS}ms " +
                                "(attempt $attempt/$DEFERRED_BOND_CHECK_MAX_ATTEMPTS)"
                        )
                        scheduleDeferredBondCompletionCheck(gatt, attempt + 1)
                    } else {
                        pendingBondedCccdUuid = null
                        Log.w(TAG, "Deferred bond check exhausted while still bonding — forcing setup recovery")
                        recoverFromInvalidSetupState("bonding-stall attempts=$attempt")
                    }
                }
                else -> {
                    pendingBondedCccdUuid = null
                    Log.w(TAG, "Deferred bond check observed state=${gatt.device.bondState} — not starting key exchange")
                }
            }
        }, DEFERRED_BOND_CHECK_DELAY_MS)
    }

    // legacy BluetoothGattCallback overload: the platform still invokes it below API 33
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (gatt !== mBluetoothGatt) {
            Log.w(TAG, "onCharacteristicChanged: stale callback, ignoring")
            return
        }
        val uuid = characteristic.uuid
        val data = characteristic.value ?: return
        if (data.isEmpty()) return

        // Lazy: this fires per notification and the hex preview copies + formats bytes.
        logi(TAG) { "onCharacteristicChanged: uuid=$uuid len=${data.size} hex=${AiDexParser.hexString(data.copyOfRange(0, minOf(data.size, 8)))}" }

        when (uuid) {
            CHAR_F003 -> handleF003(data)
            CHAR_F001 -> handleF001Response(data, gatt)
            CHAR_F002 -> dispatchF002Response(data, gatt)
            else -> Log.w(TAG, "onCharacteristicChanged: unexpected uuid=$uuid")
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        super.onCharacteristicWrite(gatt, characteristic, status)
        if (gatt !== mBluetoothGatt) {
            Log.w(TAG, "onCharacteristicWrite: stale callback, ignoring")
            return
        }
        logd(TAG) { "onCharacteristicWrite: uuid=${characteristic.uuid} status=$status" }
        // The queue advances on the callback regardless of status, so a handshake write the sensor
        // rejected is otherwise indistinguishable from one it took. bondDataRead is true only for
        // the post-BOND config write, never for the challenge (which bypasses the queue).
        if (
            phase == Phase.KEY_EXCHANGE &&
            bondDataRead &&
            characteristic.uuid == CHAR_F001 &&
            status != BluetoothGatt.GATT_SUCCESS
        ) {
            postBondConfigWriteRejected = true
            Log.e(TAG, "Key exchange: post-BOND config write failed (status=$status)")
        }
        if (
            pendingResetReconnect &&
            characteristic.uuid == CHAR_F002 &&
            postResetClearStorageWriteAtMs > 0L &&
            postResetClearStorageAckAtMs == 0L
        ) {
            resetDiag("f3-write-callback", "status=$status")
        }
        val uuid = characteristic.uuid
        handler.post {
            val op = currentGattOp
            // The stack hands us no operation id, so the characteristic is the only identity we
            // get: a callback for anything else — the out-of-band F001 challenge write — must not
            // cancel the watchdog of, or complete, whatever the queue has in flight.
            if (!gattOpActive || op !is GattOp.Write || op.charUuid != uuid) return@post
            handler.removeCallbacks(gattOpWatchdog)
            currentGattOp = null
            gattOpActive = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failGattWrite(op, "write callback status=$status", callbackReceived = true)
                return@post
            }
            if (op.opcode == AiDexOpcodes.SET_NEW_SENSOR) {
                noteActivationWriteDelivered("write-callback")
            }
            drainGattQueue()
        }
    }

    // legacy BluetoothGattCallback overload: the platform still invokes it below API 33
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        super.onCharacteristicRead(gatt, characteristic, status)
        if (gatt !== mBluetoothGatt) {
            Log.w(TAG, "onCharacteristicRead: stale callback, ignoring")
            return
        }
        val uuid = characteristic.uuid
        val data = characteristic.value

        handler.post {
            val op = currentGattOp
            if (!gattOpActive || op !is GattOp.Read || op.charUuid != uuid) return@post
            handler.removeCallbacks(gattOpWatchdog)
            currentGattOp = null
            gattOpActive = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Reads are idempotent, so the existing bounded retry path applies; without this
                // a failed read was completed as if it had succeeded and silently dropped.
                handleReadFailure(op, gatt)
                return@post
            }
            drainGattQueue()
        }

        if (status != BluetoothGatt.GATT_SUCCESS || data == null) {
            Log.w(TAG, "onCharacteristicRead: uuid=$uuid status=$status data=${data?.size}")
            return
        }

        when (uuid) {
            CHAR_F002 -> {
                if (!bondDataRead && phase == Phase.KEY_EXCHANGE) {
                    handleBondReadReply(data, gatt)
                } else {
                    dispatchF002Response(data, gatt)
                }
            }
            // Device Information Service reads
            CHAR_MODEL_NUMBER -> {
                _modelName = String(data, Charsets.UTF_8).trim('\u0000', ' ')
                Log.i(TAG, "DIS Model Number: $_modelName")
                applyWearProfileFromModel(_modelName)
                if (phase == Phase.STREAMING && _firmwareVersion.isNotBlank()) {
                    scheduleOptionalStreamingSync("dis-model-ready")
                }
            }
            CHAR_SOFTWARE_REV -> {
                _firmwareVersion = String(data, Charsets.UTF_8).trim('\u0000', ' ')
                Log.i(TAG, "DIS Software Revision: $_firmwareVersion")
                if (phase == Phase.STREAMING && _modelName.isNotBlank()) {
                    scheduleOptionalStreamingSync("dis-fw-ready")
                }
            }
            CHAR_MANUFACTURER -> {
                val manufacturer = String(data, Charsets.UTF_8).trim('\u0000', ' ')
                Log.i(TAG, "DIS Manufacturer Name: $manufacturer")
            }
            // CGM Session Start Time (0x2AAA) — parse activation date; wear days come from 0x10.
            CHAR_CGM_SESSION_START -> handleCGMSessionStartTime(data)
            // CGM Session Run Time (0x2AAB) — how long sensor has been running
            CHAR_CGM_SESSION_RUN -> {
                if (data.size >= 2) {
                    val minutes = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    Log.i(TAG, "CGM Session Run Time: ${minutes}min = ${minutes / 60}h = ~${minutes / 1440} days")
                }
            }
            else -> Log.d(TAG, "onCharacteristicRead: unknown uuid=$uuid len=${data.size}")
        }
    }

    private fun applyParsedSessionStartTime(
        parsed: AiDexParser.LocalStartTime,
        source: String,
        allowActivation: Boolean,
    ) {
        val tzOffsetSeconds = aiDexSessionStartOffsetSeconds(parsed.tzQuarters, parsed.dstQuarters)
        if (parsed.tzQuarters != 0 || parsed.dstQuarters != 0) {
            Log.i(
                TAG,
                "$source start time: ${parsed.year}-${parsed.month}-${parsed.day} " +
                    "${parsed.hour}:${parsed.minute}:${parsed.second} " +
                    "TZ=${parsed.tzQuarters * 15}min DST=${parsed.dstQuarters}"
            )
        } else {
            Log.i(
                TAG,
                "$source start time: ${parsed.year}-${parsed.month}-${parsed.day} " +
                    "${parsed.hour}:${parsed.minute}:${parsed.second} (no TZ)"
            )
        }

        if (parsed.isAllZeros) {
            Log.i(TAG, "$source start time: all zeros")
            if (postResetRequestedAtMs > 0L) {
                resetDiag("session-start-verification", "source=$source result=zeros")
            }
            hasAuthoritativeSessionStart = false
            armFirstValidReadingWait(System.currentTimeMillis(), "$source-zero-session-start")
            if (allowActivation) {
                if (settleUnconfirmedActivation(startMs = 0L, source = source)) return
                val hasStoredPairCredential = persistedPairKey != null
                val hasDownloadedHistory = historyRawNextIndex > 0 || historyBriefNextIndex > 0
                val hasAcceptedReading = lastGlucoseTimeMs > 0L
                when (
                    AiDexRuntimePolicy.decideZeroSessionStartActivation(
                        needsPostResetActivation = needsPostResetActivation,
                        autoActivationAttemptedThisConnection = autoActivationAttemptedThisConnection,
                        hasStoredPairCredential = hasStoredPairCredential,
                        hasDownloadedHistory = hasDownloadedHistory,
                        hasAcceptedReading = hasAcceptedReading,
                        bondValidatedByStreaming = bondValidatedByStreaming,
                    )
                ) {
                    AiDexRuntimePolicy.ZeroStartActivation.POST_RESET -> {
                        autoActivationAttemptedThisConnection = true
                        Log.i(TAG, "$source start time: post-reset auto-activating sensor with SET_NEW_SENSOR (0x20)")
                        startNewSensor()
                        handler.postDelayed({ readCGMSessionCharacteristics() }, 2_000L)
                    }
                    AiDexRuntimePolicy.ZeroStartActivation.FRESH_SENSOR -> {
                        autoActivationAttemptedThisConnection = true
                        Log.i(TAG, "$source start time: fresh sensor auto-activating with SET_NEW_SENSOR (0x20)")
                        startNewSensor()
                        handler.postDelayed({ readCGMSessionCharacteristics() }, 2_000L)
                    }
                    AiDexRuntimePolicy.ZeroStartActivation.ALREADY_ATTEMPTED ->
                        Log.w(TAG, "$source start time still zero after activation attempt — waiting for first valid reading")
                    AiDexRuntimePolicy.ZeroStartActivation.KNOWN_RUNNING ->
                        Log.w(
                            TAG,
                            "$source start time: all zeros on a sensor this app has seen running " +
                                "(storedKey=$hasStoredPairCredential history=$hasDownloadedHistory " +
                                "reading=$hasAcceptedReading bondValidated=$bondValidatedByStreaming) — not auto-activating"
                        )
                }
                return
            }

            // A zero start on a source that may not activate stays unknown: the sensor is the only
            // authority for it, and the local 0x20 rewrite this used to probe for was removed as
            // unverified on hardware — live/history data anchors the session instead.
            return
        }

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(parsed.year, parsed.month - 1, parsed.day, parsed.hour, parsed.minute, parsed.second)
        cal.set(Calendar.MILLISECOND, 0)
        val startMs = cal.timeInMillis - (tzOffsetSeconds * 1000L)
        if (startMs <= 0L || startMs >= System.currentTimeMillis() + 86400_000L) {
            Log.w(TAG, "$source start time parse produced out-of-range epoch: $startMs")
            if (postResetRequestedAtMs > 0L) {
                resetDiag("session-start-verification", "source=$source result=invalid startMs=$startMs")
            }
            return
        }

        if (postResetRequestedAtMs > 0L) {
            resetDiag(
                stage = "session-start-verification",
                details = "source=$source result=parsed startMs=$startMs deltaFromResetMs=${startMs - postResetRequestedAtMs}",
            )
        }

        // Whether this source may fire the 0x20 is [allowActivation]'s business; whether the
        // reported start predates the erase is not. The two legacy 0x21 handlers pass
        // allowActivation=false, and gating the whole branch on it let a pre-reset start reach the
        // block below: marked authoritative, written into sensorstartmsec and into the native
        // store, and — worst — clearing the persisted needsPostResetActivation, leaving the sensor
        // erased by 0xF3, never activated and with nothing left to retry it.
        // The 2-minute slack stays (AiDexRuntimePolicy.POST_RESET_START_SLACK_MS): a start just before
        // the press is ambiguous — the old session, or a new one seen through clock skew — and an
        // ambiguous start must not become an automatic 0x20. Such a start falls through and clears.
        if (
            AiDexRuntimePolicy.sessionStartPredatesReset(
                needsPostResetActivation = needsPostResetActivation,
                resetRequestedAtMs = postResetRequestedAtMs,
                startMs = startMs,
            )
        ) {
            if (allowActivation && !autoActivationAttemptedThisConnection) {
                Log.w(
                    TAG,
                    "$source start time predates reset request (startMs=$startMs, resetAt=$postResetRequestedAtMs) — " +
                        "forcing SET_NEW_SENSOR (0x20) for reset fallback"
                )
                startNewSensor()
                handler.postDelayed({ readCGMSessionCharacteristics() }, 2_000L)
            } else {
                // Re-arming over a healthy stream restamps the persisted firstValidReadingAnchorMs
                // with the reset time: the overlay then says "Waiting for first valid reading" and
                // calibrateSensor refuses as "warming up" on a sensor that is delivering. Nothing
                // is lost by skipping it — broadcastAssistRunnable returns immediately on recent
                // live data anyway, and it is re-posted on every connect and failed scan.
                // "Delivering" has to mean a reading this driver accepted after the reset was
                // asked for, which is not what hasRecentLiveData() answers: lastBroadcastTime is
                // stamped before the duplicate, post-wear and fallback gates, so an advert this
                // handler then dropped counts as live, and its live half looks back
                // BROADCAST_FALLBACK_LIVE_TIMEOUT_MS, far past the reset's quiet window.
                // postResetRequestedAtMs is > 0 for this whole branch and lastGlucoseTimeMs only
                // ever moves in noteValidReadingAvailable, i.e. on a reading that was accepted.
                //
                // That is all it proves. It is not evidence of a NEW session: the stamp comes from
                // resolveOffsetBackedTimestampMs (sensorstartmsec + offset), so on a ring the
                // firmware never restarted the series runs straight through the reset and reads
                // as "after" it. Nothing available in the app separates the two runs — only a
                // capture showing whether the ring and its offset really restart can.
                val liveFlowing = lastGlucoseTimeMs >= postResetRequestedAtMs
                val waitNote = if (liveFlowing) {
                    "live data still arriving — leaving the first-valid-reading wait alone"
                } else {
                    "waiting for first valid reading"
                }
                Log.w(
                    TAG,
                    "$source start time predates reset request (startMs=$startMs, resetAt=$postResetRequestedAtMs) — " +
                        "not authoritative (allowActivation=$allowActivation, " +
                        "attempted=$autoActivationAttemptedThisConnection) — $waitNote"
                )
                if (!liveFlowing) {
                    armFirstValidReadingWait(postResetRequestedAtMs, "$source-stale-post-reset-start")
                }
            }
            return
        }

        if (settleUnconfirmedActivation(startMs = startMs, source = source)) return
        hasAuthoritativeSessionStart = true
        if (needsPostResetActivation) {
            // The sensor itself reports a real session start that does not predate the reset
            // request, so whatever activation was owed has happened. That the branch above can
            // rule a pre-reset start out at all rests on the barrier — it carries the reset
            // timestamp, and handleHistoryRangeResponse leaves it armed for as long as this flag
            // is set, precisely so this clear cannot fire without that proof.
            // Left set, this persistent flag turns the next zero read of 0x2AAA — a reconnect
            // glitch, a re-added sensor — into an unrequested 0x20 that restamps the session to
            // "now" and quarantines the sensor's real ring.
            setActivationDebt(false, "$source start time is authoritative")
        }
        val ageMs = System.currentTimeMillis() - startMs
        val ageDays = ageMs.toDouble() / 86400_000.0
        val reportedWearDays = reportedWearDaysOrNull()
        val remainDays = reportedWearDays?.let { it - ageDays }
        Log.i(
            TAG,
            "$source start time parsed: startMs=$startMs, age=${String.format("%.1f", ageDays)} days, " +
                "remaining=${remainDays?.let { String.format("%.1f days", it) } ?: "unknown"}"
        )

        if (sensorstartmsec <= 0L || kotlin.math.abs(sensorstartmsec - startMs) > 60_000L) {
            Log.i(TAG, "Updating sensorstartmsec from $source: $sensorstartmsec → $startMs")
            sensorstartmsec = startMs
            Natives.aidexSetStartTime(dataptr, startMs)
        }
        // Resolve again on the handler, after the start-time write. A binder-thread snapshot
        // of the wear byte can otherwise land after 0x10 and replace a longer life with the
        // catalog value. Posting after aidexSetStartTime keeps the two native writes ordered.
        runOnHandler { persistResolvedWearDays("session-start $source") }
        if (lastGlucoseTimeMs < startMs) {
            armFirstValidReadingWait(startMs, "$source-authoritative-start")
        }

        updateSensorExpiredFromStart(System.currentTimeMillis())
        if (
            pendingInitialHistoryRequest &&
            !historyDownloading &&
            !autoActivationAttemptedThisConnection &&
            streamingStartedAtMs > 0L &&
            lastF003FrameTimeMs < streamingStartedAtMs
        ) {
            Log.i(TAG, "$source start time confirmed an existing session — keeping delayed history fallback; still waiting for first live frame")
        }
    }

    /**
     * Parse CGM Session Start Time (0x2AAA):
     * Bytes: year(u16LE), month, day, hour, minute, second, timezone(s8), DST(u8)
     * Sets sensorstartmsec. Wear duration is reported separately by startup device info 0x10.
     */
    private fun handleCGMSessionStartTime(data: ByteArray) {
        val parsed = AiDexParser.parseLocalStartTimePayload(data)
        if (parsed == null) {
            Log.w(TAG, "CGM Session Start Time: invalid payload (${data.size} bytes)")
            return
        }
        applyParsedSessionStartTime(
            parsed = parsed,
            source = "CGM session",
            allowActivation = true,
        )
    }

    override fun bonded() {
        // Note: SensorBluetooth calls bonded() on EVERY bond state change
        // (BONDING, BONDED, NONE, ERROR), not just BOND_BONDED.
        // Ahead of the GATT guard: the post-reset bond hold has no GATT, and the held device's
        // BOND_NONE is the hold's exit, not a pairing that failed.
        if (postResetBondHold != null) {
            handler.post { pollPostResetBondHold("bond-state-changed") }
            return
        }
        val gatt = mBluetoothGatt ?: return
        val bondState = gatt.device.bondState
        Log.i(TAG, "bonded() callback: bondState=$bondState")
        scheduleSetupProgressWatchdog()

        if (bondState == BluetoothDevice.BOND_BONDED) {
            if (bondStateAtConnection != BluetoothDevice.BOND_BONDED) {
                bondBecameBondedThisConnection = true
            }
            reconnect.onBondSuccess()

            // bonded() runs on the main thread (SensorBluetooth's bond-state receiver), so the
            // queue and the CCCD bookkeeping are handed over in one piece — and in this order.
            // Split across three posts, the resume below was issued first and the deferred block
            // then nulled cccdWriteInProgress/cccdPendingWriteUuid out from under the descriptor
            // write it had just started; the real onDescriptorWrite was dropped as
            // "late/mismatched" and the chain sat there until setupProgressWatchdog.
            handler.post {
                if (mBluetoothGatt !== gatt) return@post
                // Resume GATT queue if it was paused for bonding
                if (queuePausedForBonding) {
                    queuePausedForBonding = false
                    drainGattQueue()
                }

                // A CCCD write that failed with an auth status while BOND_BONDING was re-queued
                // and parked here; put it back at the head before the chain is resumed.
                pendingBondedCccdUuid?.let { charUuid ->
                    pendingBondedCccdUuid = null
                    if (cccdWriteInProgress) {
                        // The bonded-settle retry or the deferred bond check already put a write
                        // on the wire — clearing the bookkeeping would orphan its callback.
                        Log.i(TAG, "Bond complete: CCCD write for $cccdPendingWriteUuid already in flight — leaving chain alone")
                        return@let
                    }
                    cccdPendingWriteUuid = null
                    Log.i(TAG, "Bond complete. Resuming CCCD chain after deferred $charUuid")
                    if (!cccdQueue.contains(charUuid)) {
                        cccdQueue.addFirst(charUuid)
                    }
                }

                // Resume CCCD chain if it was interrupted by auth failure
                if (cccdQueue.isNotEmpty()) {
                    writeNextCccd(gatt)
                }
            }

            // Start deferred key exchange if CCCDs completed while bonding
            if (keyExchangePendingBond && cccdChainComplete) {
                keyExchangePendingBond = false
                // 500ms delay to let encryption fully settle after bonding,
                // matching vendor driver's approach (AiDexSensor.kt line 6013)
                Log.i(TAG, "Bond complete. Starting deferred key exchange after 500ms settle delay...")
                handler.postDelayed({ startKeyExchangeForCurrentConnection(gatt) }, 500L)
            } else if (
                phase == Phase.STREAMING &&
                keyExchange.isComplete &&
                !historyDownloading &&
                !gattOpActive &&
                cccdQueue.isEmpty() &&
                !postBondLiveRefreshAttempted &&
                streamingStartedAtMs > 0L &&
                lastF003FrameTimeMs < streamingStartedAtMs
            ) {
                postBondLiveRefreshAttempted = true
                handler.postDelayed({
                    if (
                        mBluetoothGatt === gatt &&
                        phase == Phase.STREAMING &&
                        !historyDownloading &&
                        !gattOpActive &&
                        cccdQueue.isEmpty() &&
                        streamingStartedAtMs > 0L &&
                        lastF003FrameTimeMs < streamingStartedAtMs
                    ) {
                        Log.i(TAG, "Bond completed after streaming start but no F003 arrived — refreshing live CCCDs once")
                        refreshLiveCccds(PostCccdFollowUp.RESUME_STREAMING, "post-bond-no-f003")
                    }
                }, 800L)
            }
        } else if (bondState == BluetoothDevice.BOND_BONDING) {
            Log.d(TAG, "bonded() callback: BOND_BONDING — waiting for BOND_BONDED")
        } else if (bondState == BluetoothDevice.BOND_NONE) {
            setBondValidatedByStreaming(false, "bonded-callback-none")
            pendingBondedCccdUuid = null
            // User cancelled pairing dialog or bonding failed
            Log.w(TAG, "bonded() callback: BOND_NONE — pairing cancelled/failed")
            val delay = reconnect.nextAuthFailureDelayMs()
            if (delay == null) {
                // Exhausted auth retries — stop trying. nextAuthFailureDelayMs() flips
                // isBroadcastOnlyMode itself on this branch, so the stamp has to be set here or the
                // mode inherits whatever deadline an unrelated earlier fallback left standing. 0L
                // for the same reason the status-5 branch uses it: coming back to GATT would mean
                // another bonding cycle against a sensor that just refused every attempt.
                broadcastFallbackEnteredAtElapsed = 0L
                Log.w(TAG, "Pairing cancelled — max auth failures reached, stopping reconnect")
                softDisconnect()
                constatstatusstr = "Pairing cancelled — tap to retry"
                // softDisconnect() stopped the scan and set stop, but nextAuthFailureDelayMs() has
                // already switched to broadcast-only: without a scan of its own that mode delivers
                // nothing. Same tail as handleDeleteBondResponse.
                stop = false
                handler.post { startBroadcastScan("bond-none-exhausted") }
            } else {
                Log.i(TAG, "Pairing cancelled — attempt ${reconnect.authFailureCount}/${reconnect.maxAuthFailures}, next retry in ${delay}ms")
                // Don't disconnect here — the GATT disconnect callback will handle reconnect with backoff
            }
        } else {
            Log.w(TAG, "bonded() callback: unexpected bond state $bondState")
        }
    }

    // legacy BLE API: required at minSdk 26; the API 33 overloads are not a drop-in replacement
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun enableAiDexNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        val descriptor = characteristic.getDescriptor(mCharacteristicConfigDescriptor)
        if (descriptor == null) {
            Log.e(TAG, "enableAiDexNotification: CCCD missing for ${characteristic.uuid}")
            return false
        }

        val originalWriteType = characteristic.writeType
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val writeAccepted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                )
                if (result != 0) {
                    Log.e(TAG, "enableAiDexNotification: writeDescriptor(${characteristic.uuid}) failed code=$result")
                    false
                } else {
                    true
                }
            } else {
                if (!descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.e(TAG, "enableAiDexNotification: descriptor.setValue(${characteristic.uuid}) failed")
                    false
                } else if (!gatt.writeDescriptor(descriptor)) {
                    Log.e(TAG, "enableAiDexNotification: writeDescriptor(${characteristic.uuid}) returned false")
                    false
                } else {
                    true
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enableAiDexNotification: writeDescriptor(${characteristic.uuid}) threw ${t.message}")
            false
        } finally {
            characteristic.writeType = originalWriteType
        }

        if (!writeAccepted) return false

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.e(TAG, "enableAiDexNotification: setCharacteristicNotification(${characteristic.uuid}) failed")
            return false
        }

        Log.i(TAG, "enableAiDexNotification: descriptor write accepted for ${characteristic.uuid}")
        return true
    }
    // =========================================================================
    // CCCD Chain
    // =========================================================================

    private var cccdRetryCount = 0
    private val CCCD_MAX_RETRIES = 5
    private val CCCD_RETRY_DELAY_MS = 1_000L
    private var lastInferredCccdUuid: UUID? = null

    private fun writeNextCccd(gatt: BluetoothGatt) {
        if (cccdWriteInProgress) return
        val charUuid = cccdQueue.peekFirst() ?: return

        val service = gatt.getService(SERVICE_F000)
        val characteristic = service?.getCharacteristic(charUuid)
        if (characteristic == null) {
            Log.w(TAG, "writeNextCccd: characteristic $charUuid not found, skipping")
            cccdQueue.pollFirst()
            writeNextCccd(gatt) // Try next
            return
        }

        cccdWriteInProgress = true
        cccdPendingWriteUuid = charUuid
        // Before enableNotification(), not after it succeeds: the Write Request can be on the
        // air while writeDescriptor() is still in its binder call, and an exchange landing then
        // has to count as crossing it.
        cccdWriteSeq += 1
        // All AiDex characteristics (F001, F002, F003) use NOTIFY, not INDICATE.
        // F001 props=0x18 (WRITE + NOTIFY), F002 props=WRITE+NOTIFY, F003 props=0x10 (NOTIFY).
        // Writing indication (02 00) to a NOTIFY-only CCCD fails with status=3.
        val ok = enableNotification(gatt, characteristic)
        if (!ok) {
            cccdWriteInProgress = false
            cccdPendingWriteUuid = null
            if (
                charUuid == CHAR_F002 &&
                lastInferredCccdUuid == CHAR_F003
            ) {
                Log.w(TAG, "F002 CCCD rejected after inferred F003 CCCD — treating GATT as stale and reconnecting")
                cccdRetryCount = 0
                lastInferredCccdUuid = null
                recoverFromInvalidSetupState("stale-gatt-after-inferred-f003-cccd")
                return
            }
            cccdRetryCount++
            if (cccdRetryCount >= CCCD_MAX_RETRIES) {
                Log.e(TAG, "writeNextCccd: CCCD $charUuid failed after $cccdRetryCount retries — skipping")
                cccdRetryCount = 0
                cccdMissingCallbackRetries = 0
                cccdQueue.pollFirst()
                if (cccdQueue.isEmpty()) {
                    cccdChainComplete = true
                    when (postCccdFollowUp) {
                        PostCccdFollowUp.ENTER_STREAMING -> {
                            postCccdFollowUp = PostCccdFollowUp.NONE
                            Log.w(TAG, "Post-key-exchange CCCD re-registration had failures. Entering streaming anyway...")
                            enterStreamingPhase(requestHistory = true)
                        }
                        PostCccdFollowUp.RESUME_STREAMING -> {
                            postCccdFollowUp = PostCccdFollowUp.NONE
                            Log.w(TAG, "Live CCCD refresh had failures. Resuming streaming wait anyway...")
                            resumeStreamingAfterLiveCccdRefresh()
                        }
                        PostCccdFollowUp.NONE -> Unit
                    }
                } else {
                    writeNextCccd(gatt)
                }
            } else {
                Log.w(TAG, "writeNextCccd: CCCD $charUuid write failed — retry $cccdRetryCount/$CCCD_MAX_RETRIES in ${CCCD_RETRY_DELAY_MS}ms")
                handler.postDelayed({
                    if (mBluetoothGatt != null) writeNextCccd(gatt)
                }, CCCD_RETRY_DELAY_MS)
            }
        } else {
            // Success — remove from queue, reset retry count. onDescriptorWrite will advance chain.
            cccdQueue.pollFirst()
            cccdRetryCount = 0
            lastInferredCccdUuid = null
            handler.removeCallbacks(cccdWriteWatchdog)
            handler.postDelayed(cccdWriteWatchdog, CCCD_WRITE_CALLBACK_TIMEOUT_MS)
        }
    }

    // =========================================================================
    // Key Exchange
    // =========================================================================

    private fun decidePairKeyStartAction(): AiDexRuntimePolicy.PairKeyStartAction {
        if (persistedPairKey == null) {
            // A handoff writes the vault straight to SharedPreferences and may land after this
            // manager was constructed, which is when the key is normally read. Without this the
            // receiving device (typically the watch) would fresh-pair a sensor whose key it now
            // holds — and a fresh F001 is what the sensor refuses while another device is bonded.
            AiDexPairKeyVault.load(Applic.app, SerialNumber)?.let { stored ->
                Log.i(TAG, "Picked up an AiDex PAIR credential stored after this manager started")
                persistedPairKey = stored
                savedKeyExhausted = false
            }
        }
        return AiDexRuntimePolicy.decidePairKeyStartAction(
            hasSavedPairKey = persistedPairKey?.size == AiDexPairKeyBackup.PAIR_KEY_BYTES,
            savedKeyExhausted = savedKeyExhausted,
            explicitPairRequested = explicitPairRequested,
            bonded = currentBondState() == BluetoothDevice.BOND_BONDED,
            pairKeyResetPending = pairKeyResetPending,
        )
    }

    private fun startKeyExchangeForCurrentConnection(gatt: BluetoothGatt) {
        when (decidePairKeyStartAction()) {
            AiDexRuntimePolicy.PairKeyStartAction.USE_SAVED_KEY -> startSavedPairKeyExchange(gatt)
            AiDexRuntimePolicy.PairKeyStartAction.FRESH_PAIR -> startFreshPairKeyExchange(gatt)
        }
    }

    private fun startSavedPairKeyExchange(gatt: BluetoothGatt) {
        val savedPairKey = persistedPairKey?.takeIf {
            it.size == AiDexPairKeyBackup.PAIR_KEY_BYTES
        } ?: run {
            Log.e(TAG, "Saved-key exchange requested without a valid PAIR credential")
            enterBroadcastOnlyFallback(
                reason = "invalid-saved-pair-key",
                statusText = "Pairing key unavailable — Broadcast Only",
            )
            return
        }

        // A saved key needs no provisioning material; just clear any prior pairing error.
        pairingKeyProblemStatus = null
        clearInvalidSetupTracking(resetRecoveryCounter = false, reason = "start-saved-key-exchange")
        setPhase(Phase.KEY_EXCHANGE)
        keyExchange.reset()
        keyExchange.onPairKeyReceived(savedPairKey)
        keyExchangeUsingSavedPairKey = true
        keyExchangeIsFreshPairAfterReset = false
        pairKeyAwaitingLiveValidation = false
        challengeWritten = false
        bondDataRead = false

        handler.removeCallbacks(keyExchangeWatchdog)
        handler.postDelayed(keyExchangeWatchdog, KEY_EXCHANGE_TIMEOUT_MS)
        Log.i(TAG, "Key exchange: reading fresh F002 BOND data with saved PAIR credential")
        readBondData(gatt)
    }

    // legacy BLE API: required at minSdk 26; the API 33 overloads are not a drop-in replacement
    @Suppress("DEPRECATION")
    private fun startFreshPairKeyExchange(gatt: BluetoothGatt) {
        if (persistedPairKey != null) {
            Log.w(
                TAG,
                "Fresh pair over a stored credential (fp=${AiDexPairKeyVault.fingerprint(persistedPairKey)}, " +
                    "explicit=$explicitPairRequested, bonded=${currentBondState() == BluetoothDevice.BOND_BONDED}, " +
                    "afterReset=$pairKeyResetPending); a validated F001 key will replace it"
            )
        }
        pairingKeyProblemStatus = null
        maybeUseAdvertisedProtocolSerial()
        maybeUseProvisionedPairingMaterial()
        clearInvalidSetupTracking(resetRecoveryCounter = false, reason = "start-fresh-key-exchange")
        setPhase(Phase.KEY_EXCHANGE)
        keyExchange.reset()
        keyExchangeUsingSavedPairKey = false
        keyExchangeIsFreshPairAfterReset = pairKeyResetPending && persistedPairKey != null
        pairKeyAwaitingLiveValidation = false
        challengeWritten = false
        bondDataRead = false

        // Start watchdog timer — force disconnect if key exchange doesn't complete
        handler.removeCallbacks(keyExchangeWatchdog)
        handler.postDelayed(keyExchangeWatchdog, KEY_EXCHANGE_TIMEOUT_MS)

        // Step 1: Write SN challenge to F001
        val challenge = keyExchange.getChallenge()
        Log.i(TAG, "Key exchange: writing one-time challenge to F001")

        val service = gatt.getService(SERVICE_F000)
        val f001 = service?.getCharacteristic(CHAR_F001)
        if (service == null || f001 == null) {
            Log.e(TAG, "startFreshPairKeyExchange: SERVICE_F000 or F001 not found")
            handleKeyExchangeFailure("f001-unavailable")
            return
        }

        f001.value = challenge
        f001.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        challengeWritten = gatt.writeCharacteristic(f001)
        if (!challengeWritten) {
            Log.e(TAG, "Fresh PAIR challenge was rejected by Android GATT")
            handleKeyExchangeFailure("f001-write-rejected")
        }
    }

    /**
     * Compatibility for sensors saved by older setup code as `X-<MAC>` after it failed to parse
     * a generation-prefixed advertisement. This changes only the protocol secret; it deliberately
     * does not rename storage or native sensor identity while a sensor is active.
     */
    private fun maybeUseAdvertisedProtocolSerial() {
        // Raw stack name, not the display override: protocol identity must not depend on title logic.
        val advertisedName = try {
            super.mygetDeviceName()
        } catch (_: Throwable) {
            null
        }
        val address = mActiveDeviceAddress ?: mActiveBluetoothDevice?.address
        val advertisedSerial = AiDexSerialIdentity.advertisedProtocolSerialForMacFallback(
            storedSensorId = SerialNumber,
            address = address,
            advertisedName = advertisedName,
        ) ?: return
        if (advertisedSerial.equals(keyExchange.bareSerial, ignoreCase = true)) return

        AiDexProvisioningStore.installSaved(Applic.app, advertisedSerial)
        protocolSession = ProtocolSession(
            AiDexKeyExchange(advertisedSerial, AiDexPairingMaterialRegistry.find(advertisedSerial))
        )
        Log.w(
            TAG,
            "Using advertised AiDEX protocol serial $advertisedSerial for MAC-fallback identity $SerialNumber"
        )
    }

    /** Pick up material installed or removed after construction but before F001 is written. */
    private fun maybeUseProvisionedPairingMaterial() {
        val protocolSerial = keyExchange.bareSerial
        AiDexProvisioningStore.installSaved(Applic.app, protocolSerial)
        val material = AiDexPairingMaterialRegistry.find(protocolSerial)
        if (material == null && !keyExchange.usesProvisionedPairingMaterial) return
        protocolSession = ProtocolSession(AiDexKeyExchange(protocolSerial, material))
        if (material != null) {
            Log.i(TAG, "Using provisioned AiDEX pairing material for $protocolSerial")
        } else {
            Log.i(TAG, "Provisioned AiDEX pairing material cleared for $protocolSerial; using serial derivation")
        }
    }

    /**
     * Handle F001 notification — contains the PAIR key.
     */
    private fun handleF001Response(data: ByteArray, gatt: BluetoothGatt) {
        if (data.size < 16) {
            Log.w(TAG, "F001 response too short (${data.size} bytes, hex=${AiDexParser.hexString(data)})")
            if (
                data.size == 1 &&
                (data[0].toInt() and 0xFF) == 0
            ) {
                pairingKeyProblemStatus = Applic.getContext().getString(
                    aiDexPairingKeyProblemStatusRes(keyExchange.usesProvisionedPairingMaterial),
                )
                constatstatusstr = pairingKeyProblemStatus.orEmpty()
                Log.e(TAG, pairingKeyProblemStatus.orEmpty())
                UiRefreshBus.requestStatusRefresh()
            }
            return
        }

        if (!challengeWritten) {
            Log.w(TAG, "F001 response received but challenge not written yet — ignoring")
            return
        }

        if (keyExchange.pairKey != null) {
            Log.d(TAG, "F001 response: PAIR key already set, ignoring duplicate")
            return
        }

        // Extract PAIR key (first 16 bytes of notification)
        val pairKeyData = data.copyOfRange(0, 16)
        keyExchange.onPairKeyReceived(pairKeyData)
        pairingKeyProblemStatus = null
        val stored = persistedPairKey
        val relation = when {
            stored == null -> "no stored key"
            stored.contentEquals(pairKeyData) -> "same as stored"
            else -> "differs from stored fp=${AiDexPairKeyVault.fingerprint(stored)}"
        }
        Log.i(
            TAG,
            "Key exchange: PAIR credential received (fp=${AiDexPairKeyVault.fingerprint(pairKeyData)}, $relation); " +
                "awaiting end-to-end validation"
        )

        // Step 3: Read BOND data from F002
        readBondData(gatt)
    }

    /**
     * Read BOND data from F002 characteristic.
     */
    private fun readBondData(gatt: BluetoothGatt) {
        Log.i(TAG, "Key exchange: reading BOND data from F002")
        enqueueGattOp(GattOp.Read(CHAR_F002))
    }

    /** The reply to [readBondData]: the 17-byte BOND vector, or the sensor declining to give one. */
    private fun handleBondReadReply(data: ByteArray, gatt: BluetoothGatt) {
        when (
            AiDexRuntimePolicy.decideShortBondReadAction(
                replyLength = data.size,
                rereads = shortBondReads,
                maxRereads = BOND_READ_MAX_REREADS,
            )
        ) {
            AiDexRuntimePolicy.ShortBondReadAction.ACCEPT -> handleBondData(data, gatt)
            AiDexRuntimePolicy.ShortBondReadAction.REREAD -> {
                shortBondReads += 1
                Log.w(
                    TAG,
                    "Key exchange: F002 BOND read returned ${data.size} byte(s) " +
                        "(${AiDexParser.hexString(data)}) — re-reading (${shortBondReads}/$BOND_READ_MAX_REREADS)"
                )
                handler.postDelayed({
                    if (mBluetoothGatt === gatt && phase == Phase.KEY_EXCHANGE && !bondDataRead) readBondData(gatt)
                }, BOND_REREAD_DELAY_MS)
            }
            AiDexRuntimePolicy.ShortBondReadAction.FAIL_KEY_EXCHANGE -> {
                Log.e(
                    TAG,
                    "Key exchange: sensor refused BOND data (${data.size} byte(s) " +
                        "${AiDexParser.hexString(data)}, saved=$keyExchangeUsingSavedPairKey, " +
                        "bond=${currentBondState()}) — counting as key exchange failure"
                )
                handleKeyExchangeFailure("f002-bond-read-refused")
            }
        }
    }

    /**
     * Handle BOND data read from F002 (17 bytes).
     */
    private fun handleBondData(data: ByteArray, gatt: BluetoothGatt) {
        if (bondDataRead) return
        bondDataRead = true

        Log.i(TAG, "Key exchange: BOND data received (${data.size} bytes)")

        if (!keyExchange.decryptBond(data)) {
            // The BOND vector is a per-connection ciphertext, not a secret; the key is only
            // ever logged as a fingerprint. Together they tell a rotated sensor key apart from
            // a corrupted stored one in the next bug report.
            Log.e(
                TAG,
                "Key exchange: BOND decryption/CRC failed (saved=$keyExchangeUsingSavedPairKey, " +
                    "fp=${AiDexPairKeyVault.fingerprint(keyExchange.pairKey)}, " +
                    "bond=${AiDexParser.hexString(data)})"
            )
            handleKeyExchangeFailure("f002-decrypt-or-crc")
            return
        }

        pairKeyAwaitingLiveValidation = !keyExchangeUsingSavedPairKey
        if (keyExchangeIsFreshPairAfterReset) {
            // The post-reset fresh pair got a working session, so the erase did rotate the
            // credential: this connection no longer counts toward giving the kept key back. Only
            // connections that fail before this point do (notePostResetFreshPairConnection).
            postResetFreshPairConnections = 0
        }
        Log.i(TAG, "Key exchange: session key established; waiting for valid direct F003")

        // Step 5: Send post-BOND config
        sendPostBondConfig(gatt)
    }

    /**
     * A key exchange — saved-key or fresh — did not produce a working session. Neither path
     * touches the stored credential: a saved key is retained through every failure, and a
     * fresh pair replaces it only after live validation. Retry through a clean GATT a bounded number of
     * times. A saved key that used up its retries while this phone holds the sensor's bond
     * is then replaced over F001 (the sensor accepts F001 from its bonded device, and the
     * new key only overwrites the stored one after a CRC-valid live frame). A fresh pair after a
     * confirmed reset that used up its retries inside the exchange goes back to the kept key.
     * Otherwise hold in broadcast-only until the user acts.
     */
    private fun handleKeyExchangeFailure(reason: String) {
        val usedSavedKey = keyExchangeUsingSavedPairKey
        // Only a failure INSIDE the exchange says anything about the credential. The no-stream
        // watchdog lands here too, at Phase.STREAMING, when F001/F002 already decrypted and the
        // CRC passed and the sensor merely went quiet on F003 - the key demonstrably works. Read
        // the phase before setPhase(Phase.IDLE) below; every genuine key-exchange caller runs at
        // KEY_EXCHANGE.
        val savedKeySuspect = usedSavedKey && phase == Phase.KEY_EXCHANGE
        val freshPairAfterReset = keyExchangeIsFreshPairAfterReset
        // The same rule for the fresh pair after a confirmed reset: a fresh key that got as far as
        // streaming shows the reset did rotate the credential, so the kept key is no way back.
        val freshPairAfterResetSuspect = freshPairAfterReset && phase == Phase.KEY_EXCHANGE
        // Read before close(): afterwards there is no GATT device to ask.
        val bonded = currentBondState() == BluetoothDevice.BOND_BONDED ||
            bondStateAtConnection == BluetoothDevice.BOND_BONDED
        keyExchangeFailures += 1
        keyExchange.reset()
        keyExchangeUsingSavedPairKey = false
        keyExchangeIsFreshPairAfterReset = false
        pairKeyAwaitingLiveValidation = false
        bondDataRead = false
        challengeWritten = false
        handler.removeCallbacks(keyExchangeWatchdog)
        setPhase(Phase.IDLE)
        close()

        val pathName = when {
            usedSavedKey -> "Saved-key reconnect"
            freshPairAfterReset -> "Post-reset fresh pair"
            else -> "Fresh pair"
        }
        when (
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(
                consecutiveFailures = keyExchangeFailures,
                maxFailures = KEY_EXCHANGE_MAX_FAILURES,
                usedSavedKey = savedKeySuspect,
                bonded = bonded,
                freshPairAfterReset = freshPairAfterResetSuspect,
            )
        ) {
            AiDexRuntimePolicy.KeyExchangeFailureAction.RESTORE_SAVED_KEY -> {
                val delay = reconnect.nextReconnectDelayMs()
                restoreKeptPairKey("$pathName failed $KEY_EXCHANGE_MAX_FAILURES times ($reason); reconnecting in ${delay}ms")
                handler.postDelayed({ connectDevice(0) }, delay)
                return
            }
            AiDexRuntimePolicy.KeyExchangeFailureAction.REPLACE_SAVED_KEY -> {
                savedKeyExhausted = true
                // The fresh pair gets its own retry budget.
                keyExchangeFailures = 0
                val delay = reconnect.nextReconnectDelayMs()
                Log.w(
                    TAG,
                    "$pathName failed $KEY_EXCHANGE_MAX_FAILURES times ($reason) on a bonded link; " +
                        "replacing the stored credential over F001 in ${delay}ms"
                )
                constatstatusstr = Applic.getContext().getString(R.string.aidex_status_saved_key_rejected_repairing)
                UiRefreshBus.requestStatusRefresh()
                handler.postDelayed({ connectDevice(0) }, delay)
                return
            }
            AiDexRuntimePolicy.KeyExchangeFailureAction.BROADCAST_ONLY -> {
                if (savedKeySuspect) savedKeyExhausted = true
                Log.w(TAG, "$pathName failed $keyExchangeFailures times ($reason); stored credential untouched")
                explicitPairRequested = false
                enterBroadcastOnlyFallback(
                    reason = "key-exchange-failed:$reason",
                    statusText = Applic.getContext().getString(
                        if (usedSavedKey) {
                            R.string.aidex_status_saved_key_rejected_press_pair
                        } else {
                            R.string.aidex_status_pairing_failed_broadcast_only
                        },
                    ),
                )
                return
            }
            AiDexRuntimePolicy.KeyExchangeFailureAction.RETRY_CLEAN_GATT -> Unit
        }
        val delay = reconnect.nextReconnectDelayMs()
        Log.w(
            TAG,
            "$pathName failed ($reason); retrying clean GATT in ${delay}ms " +
                "($keyExchangeFailures/$KEY_EXCHANGE_MAX_FAILURES)"
        )
        handler.postDelayed({ connectDevice(0) }, delay)
    }

    /**
     * Send post-BOND config (plaintext 10 C1 F3, encrypted).
     */
    private fun sendPostBondConfig(gatt: BluetoothGatt) {
        if (currentBondState() != BluetoothDevice.BOND_BONDED) {
            // F001 is the one characteristic that needs link encryption. On an unbonded
            // saved-key session the write never completes: Android tries to pair, the sensor
            // refuses, the callback never comes, every queued F002 command waits behind it,
            // and the sensor drops the link ~19s in. The sensor streams F003 without this
            // config (phone B, 05:01:40), so skip it and let the F002 startup commands run.
            Log.i(TAG, "Key exchange: unbonded link — skipping post-BOND config write to F001")
            onKeyExchangeComplete()
            return
        }
        val configData = keyExchange.getPostBondConfig()
        if (configData == null) {
            Log.e(TAG, "Key exchange: failed to encrypt post-BOND config")
            return
        }

        Log.i(TAG, "Key exchange: writing post-BOND config to F001")
        postBondConfigWriteRejected = false
        postBondConfigDeferrals = 0
        postBondConfigBondingWaits = 0
        postBondConfigUnconfirmed = false
        enqueueGattOp(GattOp.Write(CHAR_F001, configData))

        // Transition to streaming once the write has actually been taken, not on a bare timer.
        handler.removeCallbacks(postBondConfigSettle)
        handler.postDelayed(postBondConfigSettle, POST_BOND_CONFIG_SETTLE_MS)
    }

    /**
     * Called when key exchange is fully complete. Start receiving data.
     *
     * Re-registers F003 and F002 CCCDs before sending commands.
     * On first connection, SMP bonding is triggered by the F001 CCCD write,
     * but F003 and F002 CCCDs were written BEFORE bonding started. The sensor
     * invalidates pre-bond CCCDs when the security level changes, so notifications
     * never arrive. Re-writing CCCDs after key exchange (post-bond) fixes this.
     * On reconnections where the device is already bonded, this is a harmless no-op.
     */
    private fun onKeyExchangeComplete() {
        val shouldRefreshLiveCccds = AiDexStreamingPolicy.shouldRefreshLiveCccdsAfterKeyExchange(
            bondStateAtConnection = bondStateAtConnection,
            bondBecameBondedThisConnection = bondBecameBondedThisConnection,
        )
        if (!shouldRefreshLiveCccds) {
            Log.i(TAG, "Key exchange complete — already-bonded reconnect, entering streaming without CCCD re-registration")
            handler.removeCallbacks(keyExchangeWatchdog)
            enterStreamingPhase(requestHistory = true)
            return
        }
        Log.i(TAG, "Key exchange complete — settling briefly before CCCD re-registration")
        handler.postDelayed({
            if (mBluetoothGatt != null && phase == Phase.KEY_EXCHANGE) {
                refreshLiveCccds(PostCccdFollowUp.ENTER_STREAMING, "post-key-exchange")
            }
        }, POST_KEY_CCCD_REFRESH_DELAY_MS)
    }

    private fun refreshLiveCccds(followUp: PostCccdFollowUp, reason: String) {
        val gatt = mBluetoothGatt
        if (gatt == null) {
            Log.e(TAG, "refreshLiveCccds($reason): no active GATT!")
            return
        }

        val service = gatt.getService(SERVICE_F000)
        if (service == null) {
            Log.e(TAG, "refreshLiveCccds($reason): SERVICE_F000 not found!")
            return
        }

        // Re-register F003 (glucose data) and F002 (command responses) CCCDs.
        // F001 was written during/after bonding so it's already valid.
        // Use the CCCD chain mechanism for serialized writes.
        handler.removeCallbacks(cccdWriteWatchdog)
        cccdQueue.clear()
        cccdQueue.add(CHAR_F003)
        cccdQueue.add(CHAR_F002)
        cccdWriteInProgress = false
        cccdPendingWriteUuid = null
        cccdMissingCallbackRetries = 0
        cccdChainComplete = false

        // After CCCDs are re-registered, either enter streaming (first connect)
        // or just resume waiting for the live stream (recovery path).
        postCccdFollowUp = followUp

        Log.i(TAG, "Re-registering F003 + F002 CCCDs ($reason)...")
        writeNextCccd(gatt)
    }

    /**
     * Enter streaming phase and send initial commands.
     * Called after post-key-exchange CCCD re-registration completes.
     */
    private fun enterStreamingPhase(requestHistory: Boolean) {
        handler.removeCallbacks(keyExchangeWatchdog)  // Cancel watchdog — key exchange succeeded
        clearInvalidSetupTracking(resetRecoveryCounter = true, reason = "enter-streaming")
        // Only a write this handshake actually confirmed is proof that the bond takes the
        // post-BOND config. Entering streaming after the deferral budget ran out proves nothing —
        // the write is still queued — and clearing the count there kept
        // POST_BOND_CONFIG_MAX_REJECTIONS permanently out of reach whenever a slow link alternated
        // with rejections, so recoverFromInvalidSetupState, the only automatic way out of a bond
        // that never takes the write, was never entered. The count still clears with the bond
        // itself in the CONNECTED branch.
        if (!postBondConfigUnconfirmed) {
            postBondConfigRejections = 0
        }
        consecutiveSetupDisconnects = 0
        handshakeCompleted = true
        setPhase(Phase.STREAMING)
        reconnect.onConnectionSuccess()
        constatstatusstr = "Connected"
        streamingStartedAtMs = System.currentTimeMillis()
        noStreamConnectedBroadcastAttempted = false
        noStreamRecoveryAttempted = false
        scheduleNoStreamWatchdog()
        pendingInitialHistoryRequest = false
        pendingStreamingMetadataRead = shouldRequestRoutineStreamingMetadata()
        pendingStreamingMetadataReason = if (pendingStreamingMetadataRead) "streaming-start" else null
        pendingCalibrationRefresh = shouldRequestRoutineCalibrationRefresh()
        pendingCalibrationRefreshReason = if (pendingCalibrationRefresh) "streaming-start" else null
        startupControlStage = StartupControlStage.IDLE
        defaultParamProbeTotalWords = 0
        defaultParamProbeRawBuffer = null
        handler.removeCallbacks(startupControlAckTimeout)
        handler.removeCallbacks(delayedInitialHistoryRequest)
        handler.removeCallbacks(delayedStreamingMetadataRequest)
        handler.removeCallbacks(delayedCalibrationRefreshRequest)
        Log.i(
            TAG,
            "Streaming phase entered." +
                if (requestHistory) {
                    " Waiting for first F003 before history (${INITIAL_HISTORY_REQUEST_DELAY_MS / 1000}s max)"
                } else {
                    " Waiting for first live data"
                } +
                "..."
        )

        if (requestHistory) {
            pendingInitialHistoryRequest = true
            beginStartupControlBootstrap("streaming-start")
            val shouldPrimeSessionCharacteristics = AiDexStreamingPolicy.shouldReadSessionCharacteristicsBeforeFirstLive(
                bondStateAtConnection = bondStateAtConnection,
                bondBecameBondedThisConnection = bondBecameBondedThisConnection,
                autoActivationAttemptedThisConnection = autoActivationAttemptedThisConnection,
                needsPostResetActivation = needsPostResetActivation,
                hasPersistedHistoryState = historyRawNextIndex > 0 || historyBriefNextIndex > 0,
            )
            if (shouldPrimeSessionCharacteristics) {
                Log.i(TAG, "Streaming startup: reading CGM session characteristics before first live")
                readCGMSessionCharacteristics()
            } else {
                Log.i(TAG, "Streaming startup: deferring CGM session characteristics until live/history fallback")
            }
            handler.postDelayed(delayedInitialHistoryRequest, INITIAL_HISTORY_REQUEST_DELAY_MS)
        }
    }

    private fun resumeStreamingAfterLiveCccdRefresh() {
        if (phase != Phase.STREAMING) {
            setPhase(Phase.STREAMING)
        }
        scheduleNoStreamWatchdog()
    }

    private fun scheduleNoStreamWatchdog(delayMs: Long = NO_STREAM_WATCHDOG_MS) {
        handler.removeCallbacks(noStreamWatchdog)
        if (phase == Phase.STREAMING && streamingStartedAtMs > 0L && lastF003FrameTimeMs < streamingStartedAtMs) {
            val now = System.currentTimeMillis()
            val latestKnownReadingMs = lastGlucoseTimeMs
            val effectiveDelay = AiDexStreamingPolicy.resolveNoStreamWatchdogDelayMs(
                defaultDelayMs = delayMs,
                nowMs = now,
                latestKnownReadingMs = latestKnownReadingMs,
                expectedLiveIntervalMs = EXPECTED_LIVE_INTERVAL_MS,
                expectedLiveGraceMs = EXPECTED_LIVE_GRACE_MS,
            )
            if (latestKnownReadingMs > 0L && effectiveDelay > delayMs + 1_000L) {
                Log.i(
                    TAG,
                    "No-stream watchdog extended to ${effectiveDelay / 1000}s because latest known reading is " +
                        "${(now - latestKnownReadingMs).coerceAtLeast(0L) / 1000}s old"
                )
            }
            handler.postDelayed(noStreamWatchdog, effectiveDelay)
        }
    }

    private fun handleParsedLiveReading(
        now: Long,
        source: String,
        autoValue: Float,
        trustedTimeOffsetMinutes: Int?,
        rawValue: Float?,
        sensorGlucose: Float?,
        rawI1: Float?,
        rawI2: Float?,
    ) {
        if (
            trustedTimeOffsetMinutes != null &&
            !AiDexHistoryPolicy.isWithinWearDuration(trustedTimeOffsetMinutes, reportedWearDaysOrNull())
        ) {
            _sensorExpired = true
            Log.w(
                TAG,
                "F003($source): skipping post-wear live reading offset=$trustedTimeOffsetMinutes wearDays=${reportedWearDaysOrNull()}"
            )
            return
        }

        val normalizedRawValue = HistoryMerge.normalizeRawMgDl(rawValue)
        if (rawValue != null && normalizedRawValue == null) {
            Log.w(TAG, "Dropping implausible raw value from $source: rawMgDl=$rawValue")
        }

        if (trustedTimeOffsetMinutes == null) {
            Log.w(TAG, "F003($source): untrusted offset — not stored, not used for history/live cadence")
            return
        }

        ensureSensorStartTime(now, trustedTimeOffsetMinutes)
        val sampleTimestampMs = AiDexHistoryPolicy.resolveOffsetBackedTimestampMs(
            observedAtMs = now,
            sensorStartMs = sensorstartmsec,
            offsetMinutes = trustedTimeOffsetMinutes,
        )
        if (!AiDexHistoryPolicy.shouldAcceptRealtimeTimestamp(sampleTimestampMs, lastGlucoseTimeMs)) {
            Log.w(
                TAG,
                "F003($source): dropping stale realtime sample time=$sampleTimestampMs " +
                    "latestAccepted=$lastGlucoseTimeMs wireOffset=$trustedTimeOffsetMinutes"
            )
            return
        }

        lastOffsetMinutes = trustedTimeOffsetMinutes
        if (trustedTimeOffsetMinutes > liveOffsetCutoff) {
            liveOffsetCutoff = trustedTimeOffsetMinutes
        }
        markStartupControlComplete("first-live-$source")
        val shouldStartHistoryNow = AiDexRuntimePolicy.shouldStartHistoryImmediately(
            pendingInitialHistoryRequest = pendingInitialHistoryRequest,
            historyDownloading = historyDownloading,
        )
        disableNoDirectLiveBroadcastFallbackMode("first-live-$source")
        if (shouldStartHistoryNow) {
            pendingInitialHistoryRequest = false
            handler.removeCallbacks(delayedInitialHistoryRequest)
            Log.i(TAG, "First live reading arrived from $source — starting history now")
            runOnHandler { requestHistoryRange() }
        } else if (!historyDownloading) {
            scheduleOptionalStreamingSync("first-live-$source")
        }

        if (broadcastScanActive && !broadcastScanContinuousMode) {
            stopBroadcastScan("live-reading", found = true)
        }

        noteValidReadingAvailable(sampleTimestampMs, "valid-$source")
        if (AiDexHistoryPolicy.shouldStampDirectLiveForDedupe(trustedTimeOffsetMinutes)) {
            lastDirectLiveReadingTimeMs = now
        }

        val bareSerial = tk.glucodata.drivers.aidex.native.crypto.SerialCrypto.stripPrefix(SerialNumber)
        val reading = GlucoseReading(
            timestamp = sampleTimestampMs,
            sensorSerial = bareSerial,
            autoValue = autoValue,
            rawValue = normalizedRawValue,
            sensorGlucose = sensorGlucose,
            rawI1 = rawI1,
            rawI2 = rawI2,
            timeOffsetMinutes = trustedTimeOffsetMinutes,
            temperatureC = rawI2,
        )
        onGlucoseReading?.invoke(reading)
        appendSkinTemperature(sampleTimestampMs, rawI2)

        if (dataptr != 0L) {
            try {
                val res = Natives.aidexProcessData(
                    dataptr,
                    byteArrayOf(0),
                    sampleTimestampMs,
                    autoValue,
                    normalizedRawValue ?: 0f,
                    1.0f,
                    // The direct F003 live frame carries no trend byte.
                    Natives.AIDEX_TREND_UNKNOWN
                )
                handleGlucoseResult(res, sampleTimestampMs, normalizedRawValue ?: Float.NaN)
                if (constatstatusstr == "Connected") {
                    constatstatusstr = ""
                }
                maybeRequestHistoryContinuitySyncAfterLive(sampleTimestampMs, source)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "F003($source): Native library mismatch: $e")
                val mgdlInt = autoValue.toInt().coerceIn(0, 0xFFFF) * 10
                handleGlucoseResult(mgdlInt.toLong() and 0xFFFFFFFFL, sampleTimestampMs, normalizedRawValue ?: Float.NaN)
                maybeRequestHistoryContinuitySyncAfterLive(sampleTimestampMs, "$source-fallback")
            } catch (e: Throwable) {
                Log.e(TAG, "F003($source): aidexProcessData failed: $e")
                val mgdlInt = autoValue.toInt().coerceIn(0, 0xFFFF) * 10
                handleGlucoseResult(mgdlInt.toLong() and 0xFFFFFFFFL, sampleTimestampMs, normalizedRawValue ?: Float.NaN)
                maybeRequestHistoryContinuitySyncAfterLive(sampleTimestampMs, "$source-fallback")
            }
        } else {
            Log.w(TAG, "F003($source): dataptr is 0 — cannot store reading")
            val mgdlInt = autoValue.toInt().coerceIn(0, 0xFFFF) * 10
            handleGlucoseResult(mgdlInt.toLong() and 0xFFFFFFFFL, sampleTimestampMs, normalizedRawValue ?: Float.NaN)
            maybeRequestHistoryContinuitySyncAfterLive(sampleTimestampMs, "$source-no-dataptr")
        }
    }

    private fun resolveTrustedLiveOffsetMinutes(frame: GlucoseFrame, now: Long): Pair<Int?, String?> {
        val wireOffset = frame.timeOffsetMinutes
        val maxOffsetMinutes = MAX_OFFSET_DAYS * 24L * 60L

        fun trust(offset: Int, reason: String? = null): Pair<Int?, String?> = offset to reason
        fun untrusted(reason: String): Pair<Int?, String?> = null to reason

        if (wireOffset <= 0) {
            return untrusted("wire offset=$wireOffset is not positive")
        }
        if (wireOffset.toLong() > maxOffsetMinutes) {
            // Kept as a safety net. Until the bytes[4..5] fix this fired on *every* frame on
            // every firmware, because the offset was being read from the wrong bytes; a genuine
            // out-of-range offset should now be rare. Accept the reading either way, but do not
            // let that field rewrite start time or dedupe state.
            val derivedFromSession = deriveOffsetFromAuthoritativeSession(now)
            if (derivedFromSession != null) {
                return trust(
                    derivedFromSession,
                    "wire offset=$wireOffset invalid; derived live offset=$derivedFromSession from authoritative session"
                )
            }
            val derivedFromHistory = deriveOffsetFromKnownHistory(now)
            if (derivedFromHistory != null) {
                return trust(
                    derivedFromHistory,
                    "wire offset=$wireOffset invalid; derived live offset=$derivedFromHistory from history cadence"
                )
            }
            return untrusted("wire offset=$wireOffset exceeds ${MAX_OFFSET_DAYS}d limit")
        }

        val startMs = sensorstartmsec.takeIf { it > 0L } ?: 0L
        if (startMs > 0L) {
            val timestampMs = startMs + wireOffset.toLong() * 60_000L
            if (timestampMs > now + 5L * 60_000L) {
                val derivedFromSession = deriveOffsetFromAuthoritativeSession(now)
                if (derivedFromSession != null) {
                    return trust(
                        derivedFromSession,
                        "wire offset=$wireOffset points into the future; using session-derived offset=$derivedFromSession"
                    )
                }
                return untrusted("wire offset=$wireOffset points into the future")
            }
            val oldestAllowed = now - (MAX_OFFSET_DAYS * 24L * 60L * 60_000L)
            if (timestampMs < oldestAllowed) {
                return untrusted("wire offset=$wireOffset points older than ${MAX_OFFSET_DAYS}d")
            }
        }

        return trust(wireOffset)
    }

    private fun deriveOffsetFromAuthoritativeSession(now: Long): Int? {
        if (!hasAuthoritativeSessionStart || sensorstartmsec <= 0L || now <= sensorstartmsec) return null
        val derived = ((now - sensorstartmsec + 30_000L) / 60_000L).toInt()
        return derived.takeIf { it > 0 && it.toLong() <= (MAX_OFFSET_DAYS * 24L * 60L) }
    }

    private fun deriveOffsetFromKnownHistory(now: Long): Int? {
        val baseOffset = maxOf(lastOffsetMinutes, historyNewestOffset, lastHistoryNewestOffset)
        if (baseOffset <= 0) return null
        val latestKnownTimeMs = lastGlucoseTimeMs.takeIf { it > 0L } ?: return baseOffset
        val advanceMinutes = ((now - latestKnownTimeMs + 30_000L) / 60_000L).coerceAtLeast(0L).toInt()
        val derived = baseOffset + advanceMinutes
        return derived.takeIf { it > 0 && it.toLong() <= (MAX_OFFSET_DAYS * 24L * 60L) }
    }

    private fun hasDirectLiveThisConnection(): Boolean {
        return lastF003FrameTimeMs > 0L &&
            (streamingStartedAtMs <= 0L || lastF003FrameTimeMs >= streamingStartedAtMs)
    }

    private fun shouldRequestRoutineStreamingMetadata(): Boolean {
        return AiDexRuntimePolicy.shouldRequestRoutineStreamingMetadata(
            startupMetadataComplete = startupMetadataComplete,
            hasModelMetadata = _modelName.isNotBlank() && _firmwareVersion.isNotBlank(),
            hasAuthoritativeSessionStart = hasAuthoritativeSessionStart,
            hasSensorReportedWearDays = sensorReportedWearDays,
        )
    }

    private fun shouldRequestRoutineCalibrationRefresh(): Boolean {
        return AiDexRuntimePolicy.shouldRequestRoutineCalibrationRefresh(
            hasCachedCalibrationRecords = _calibrationRecords.isNotEmpty(),
            calibrationDownloading = calibrationDownloading,
            calibrationRangeKnownEmpty = calibrationRangeKnownEmpty,
            calibrationFetchAttempted = calibrationFetchAttempted,
        )
    }

    private fun shouldRunOptionalStreamingSync(now: Long = System.currentTimeMillis()): Boolean {
        return AiDexRuntimePolicy.shouldRunOptionalStreamingSync(
            phase = phase,
            hasGatt = mBluetoothGatt != null,
            historyDownloading = historyDownloading,
            pendingInitialHistoryRequest = pendingInitialHistoryRequest,
            noDirectLiveBroadcastFallbackMode = noDirectLiveBroadcastFallbackMode,
            hasDirectLiveThisConnection = hasDirectLiveThisConnection(),
            hasRecentLiveData = hasRecentLiveData(now),
        )
    }

    private fun scheduleOptionalStreamingSync(reason: String) {
        armAutomaticDefaultParamProvisioning(reason)
        if (shouldRequestRoutineStreamingMetadata()) {
            pendingStreamingMetadataRead = true
            pendingStreamingMetadataReason = reason
        }
        if (shouldRequestRoutineCalibrationRefresh()) {
            pendingCalibrationRefresh = true
            pendingCalibrationRefreshReason = reason
        }
        if (!pendingStreamingMetadataRead && !pendingCalibrationRefresh) {
            if (
                !pendingDefaultParamAutoProvisioning ||
                pendingDefaultParamAutoProvisioningScheduled
            ) {
                return
            }
        }
        if (!shouldRunOptionalStreamingSync()) {
            Log.i(
                TAG,
                "Optional streaming sync armed ($reason): waiting for stable direct live " +
                    "(phase=$phase hasDirectLive=${hasDirectLiveThisConnection()} historyDownloading=$historyDownloading)"
            )
            return
        }
        if (pendingStreamingMetadataRead) {
            if (!pendingStreamingMetadataScheduled) {
                pendingStreamingMetadataScheduled = true
                handler.postDelayed(delayedStreamingMetadataRequest, OPTIONAL_STREAMING_METADATA_DELAY_MS)
            }
        }
        if (pendingDefaultParamAutoProvisioning) {
            if (!pendingDefaultParamAutoProvisioningScheduled) {
                pendingDefaultParamAutoProvisioningScheduled = true
                handler.postDelayed(
                    delayedDefaultParamAutoProvisioningRequest,
                    OPTIONAL_DEFAULT_PARAM_PROVISIONING_DELAY_MS
                )
            }
        }
        if (pendingCalibrationRefresh) {
            if (!pendingCalibrationRefreshScheduled) {
                pendingCalibrationRefreshScheduled = true
                handler.postDelayed(delayedCalibrationRefreshRequest, OPTIONAL_CALIBRATION_REFRESH_DELAY_MS)
            }
        }
    }

    private fun shouldAutoProvisionDefaultParam(): Boolean {
        if (defaultParamAutoProvisioningAttemptedThisConnection) return false
        if (_modelName.isBlank() || _firmwareVersion.isBlank()) return false
        if (!keyExchange.isComplete) return false
        return true
    }

    private fun armAutomaticDefaultParamProvisioning(reason: String) {
        if (defaultParamAutoProvisioningAttemptedThisConnection) return
        if (pendingDefaultParamAutoProvisioning) return
        if (defaultParamAutoProvisioning || pendingDefaultParamApplyAfterProbe || defaultParamApplyVerifying) return
        pendingDefaultParamAutoProvisioning = true
        pendingDefaultParamAutoProvisioningReason = reason
    }

    private fun requestAutomaticDefaultParamProvisioningIfNeeded(reason: String) {
        if (!pendingDefaultParamAutoProvisioning) return
        if (!shouldRunOptionalStreamingSync()) {
            pendingDefaultParamAutoProvisioningReason = reason
            return
        }
        if (!shouldAutoProvisionDefaultParam()) {
            pendingDefaultParamAutoProvisioningReason = reason
            pendingDefaultParamAutoProvisioningScheduled = false
            return
        }

        pendingDefaultParamAutoProvisioning = false
        pendingDefaultParamAutoProvisioningReason = null
        pendingDefaultParamAutoProvisioningScheduled = false
        defaultParamAutoProvisioningAttemptedThisConnection = true
        clearDefaultParamProbeState()
        clearDefaultParamApplyState()
        defaultParamProbeUserInitiated = false
        // Only the read-only 0x31 probe runs unattended; the 0x30 write it chains into is an
        // actuator and stays behind the switch. Leaving the flags false lets the completed probe
        // fall out through the no-apply branch of maybeStartDefaultParamApplyAfterProbe.
        val autoApplyAllowed = AiDexRuntimePolicy.mayAutoApplyDefaultParam()
        defaultParamAutoProvisioning = autoApplyAllowed
        pendingDefaultParamApplyAfterProbe = autoApplyAllowed
        if (!autoApplyAllowed) {
            Log.w(
                TAG,
                "Automatic default-param apply (0x30, $reason) suppressed — read-only probe only, " +
                    "until the write is verified on hardware and byte-compared with the official app"
            )
        }
        Log.i(TAG, "Requesting automatic default-param provisioning probe ($reason)")
        requestDefaultParamProbe(0x01, "auto-provision-$reason")
    }

    private fun requestStreamingMetadataIfNeeded(reason: String) {
        if (!pendingStreamingMetadataRead) return
        if (!shouldRunOptionalStreamingSync()) {
            pendingStreamingMetadataReason = reason
            return
        }
        val needsModelMetadata = _modelName.isBlank() || _firmwareVersion.isBlank()
        val needsSessionMetadata = !hasAuthoritativeSessionStart
        val needsStartupWearDays = !sensorReportedWearDays
        if (!needsModelMetadata && !needsSessionMetadata && !needsStartupWearDays) {
            pendingStreamingMetadataRead = false
            pendingStreamingMetadataReason = null
            pendingStreamingMetadataScheduled = false
            startupMetadataComplete = true
            Log.i(TAG, "Skipping routine streaming metadata ($reason): metadata already known")
            return
        }
        pendingStreamingMetadataRead = false
        pendingStreamingMetadataReason = null
        pendingStreamingMetadataScheduled = false
        Log.i(TAG, "Requesting streaming metadata ($reason)")
        // Keep non-essential reads out of the reconnect/bootstrap critical path.
        // Only request the specific metadata still missing for this sensor.
        if (needsModelMetadata) {
            readDeviceInformationService()
        }
        if (needsSessionMetadata) {
            readCGMSessionCharacteristics()
        }
        if (!startupMetadataComplete || needsModelMetadata || needsStartupWearDays) {
            handler.postDelayed({
                maybeRequestStartupDeviceInfo("streaming-metadata-$reason")
            }, STARTUP_DEVICE_INFO_REQUEST_DELAY_MS)
        }
    }

    private fun requestRoutineCalibrationRefreshIfNeeded(reason: String) {
        if (!pendingCalibrationRefresh) return
        if (!shouldRunOptionalStreamingSync()) {
            pendingCalibrationRefreshReason = reason
            return
        }
        if (!shouldRequestRoutineCalibrationRefresh()) {
            pendingCalibrationRefresh = false
            pendingCalibrationRefreshReason = null
            pendingCalibrationRefreshScheduled = false
            Log.i(TAG, "Skipping routine calibration refresh ($reason): calibration data already available")
            return
        }
        pendingCalibrationRefresh = false
        pendingCalibrationRefreshReason = null
        pendingCalibrationRefreshScheduled = false
        val cmd = commandBuilder.getCalibrationRange() ?: return
        // Set on asking, not on the answer: a 0x26 reply dropped by the CRC gate, one that fails to
        // decrypt, a truncated one and one that never arrives all bypass the response handler, and
        // each of them used to re-arm this refresh on every live reading for the rest of the link.
        calibrationFetchAttempted = true
        Log.i(TAG, "Requesting routine calibration refresh ($reason)")
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.GET_CALIBRATION_RANGE))
    }

    /**
     * Read Device Information Service (0x180A) characteristics:
     * Model Number (0x2A24), Software Revision (0x2A28), Manufacturer Name (0x2A29).
     */
    private fun readDeviceInformationService() {
        enqueueGattOp(GattOp.Read(CHAR_MODEL_NUMBER, SERVICE_DIS))
        enqueueGattOp(GattOp.Read(CHAR_SOFTWARE_REV, SERVICE_DIS))
        enqueueGattOp(GattOp.Read(CHAR_MANUFACTURER, SERVICE_DIS))
    }

    /**
     * Read standard CGM characteristics under service 0x181F (same as SERVICE_F000):
     * CGM Session Start Time (0x2AAA) — sensor activation date + timezone.
     * CGM Session Run Time (0x2AAB) — how long the sensor has been running.
     */
    private fun readCGMSessionCharacteristics() {
        enqueueGattOp(GattOp.Read(CHAR_CGM_SESSION_START, SERVICE_F000))
        enqueueGattOp(GattOp.Read(CHAR_CGM_SESSION_RUN, SERVICE_F000))
    }

    private fun requestConnectedBroadcastData(reason: String) {
        val cmd = commandBuilder.getBroadcastData()
        if (cmd == null) {
            Log.w(TAG, "requestConnectedBroadcastData($reason): session key not ready")
            return
        }
        Log.i(TAG, "Requesting connected broadcast data ($reason)")
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.GET_BROADCAST_DATA))
    }

    private fun maybeRequestStartupDeviceInfo(reason: String) {
        if (startupMetadataComplete || startupDeviceInfoRequested) return
        if (!keyExchange.isComplete) return
        if (hasCompleteStartupMetadata()) {
            startupMetadataComplete = true
            return
        }

        val cmd = commandBuilder.getStartupDeviceInfo() ?: run {
            Log.w(TAG, "maybeRequestStartupDeviceInfo($reason): session key not ready")
            return
        }
        startupDeviceInfoRequested = true
        Log.i(TAG, "Requesting startup device info (0x10, $reason)")
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.GET_STARTUP_DEVICE_INFO))
    }

    private fun mergedFirmwareVersion(existing: String?, candidate: String?): String {
        val current = existing.orEmpty().trim()
        val incoming = candidate.orEmpty().trim()
        if (current.isBlank()) return incoming
        if (incoming.isBlank()) return current
        if (current == incoming) return incoming
        if (current.startsWith("$incoming.")) return current
        if (incoming.startsWith("$current.")) return incoming
        return incoming
    }

    private fun requestLegacyStartTime(reason: String) {
        if (legacyStartTimeRequested || hasAuthoritativeSessionStart) return
        val cmd = commandBuilder.getLegacyStartTime() ?: run {
            Log.w(TAG, "requestLegacyStartTime($reason): session key not ready")
            return
        }
        legacyStartTimeRequested = true
        Log.i(TAG, "Requesting legacy local start time (0x21, $reason)")
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.GET_LOCAL_START_TIME))
    }

    private fun clearDefaultParamProbeState() {
        defaultParamProbeTotalWords = 0
        defaultParamProbeRawBuffer = null
    }

    private fun clearDefaultParamApplyState() {
        handler.removeCallbacks(defaultParamWriteAckWatchdog)
        pendingDefaultParamApplyAfterProbe = false
        defaultParamApplyVerifying = false
        defaultParamApplyState = null
    }

    private fun beginManualDefaultParamProbe(applyAfterProbe: Boolean, reason: String) {
        clearDefaultParamProbeState()
        clearDefaultParamApplyState()
        defaultParamProbeUserInitiated = true
        pendingDefaultParamApplyAfterProbe = applyAfterProbe
        requestDefaultParamProbe(0x01, reason)
    }

    private fun requestDefaultParamProbe(startIndex: Int, reason: String) {
        val cmd = commandBuilder.getDefaultParam(startIndex) ?: run {
            Log.w(TAG, "requestDefaultParamProbe($reason): session key not ready")
            return
        }
        Log.i(TAG, "Requesting read-only default params (0x31, start=$startIndex, $reason)")
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.GET_DEFAULT_PARAM))
    }

    private fun defaultParamChunkPayloadBytes(): Int {
        val maxGattPayloadBytes = (negotiatedMtu - 3).coerceAtLeast(20)
        val maxCommandPayloadBytes = (maxGattPayloadBytes - 5).coerceAtLeast(2)
        val capped = minOf(DEFAULT_PARAM_MAX_CHUNK_PAYLOAD_BYTES, maxCommandPayloadBytes)
        return if (capped % 2 == 0) capped else capped - 1
    }

    private fun maybeStartDefaultParamApplyAfterProbe(rawHex: String, diagnostics: AiDexDefaultParamProvisioning.Diagnostics) {
        if (!pendingDefaultParamApplyAfterProbe) {
            if (defaultParamProbeUserInitiated) {
                val statusMessage = when {
                    diagnostics.bestComparison == null -> "DP compare unavailable"
                    diagnostics.exactMatch -> "DP OK: ${diagnostics.bestComparison.entry.settingVersion}"
                    else -> "DP diff ${diagnostics.bestComparison.diffByteCount}: ${diagnostics.bestComparison.entry.settingVersion}"
                }
                showTransientStatus(statusMessage)
            }
            defaultParamProbeUserInitiated = false
            defaultParamAutoProvisioning = false
            return
        }

        pendingDefaultParamApplyAfterProbe = false
        if (!AiDexRuntimePolicy.hasVerifiedDefaultParamAckStatus()) {
            // Refuse to START, not just to continue — and on every path that reaches here, the
            // manual sendMaintenanceCommand(SET_DEFAULT_PARAM) as much as the automatic pass. The
            // success status for 0x30 is unknown, so every ACK aborts the run
            // (handleSetDefaultParamResponse) and beginning anyway would leave the sensor's
            // parameter table half rewritten, which is worse than never touching it.
            // Because this returns first, everything below it is unreachable today: the apply
            // fingerprint bookkeeping and planGuardedApply, sendNextDefaultParamApplyChunk, the
            // ACK handling in handleSetDefaultParamResponse and the post-apply verify branch. That
            // is the barrier holding, not dead code — the code stays so the rules are already
            // right when a capture lifts it, together with mayAutoApplyDefaultParam().
            Log.w(TAG, "Default-param apply refused — no verified 0x30 ACK status; capture the official app first")
            if (defaultParamProbeUserInitiated) showTransientStatus("DP apply disabled")
            clearDefaultParamApplyState()
            defaultParamProbeUserInitiated = false
            defaultParamAutoProvisioning = false
            return
        }
        val autoApplyFingerprint = if (defaultParamAutoProvisioning) {
            diagnostics.bestComparison?.let { comparison ->
                val importedAt = diagnostics.catalog.importedUpdatedAtMs
                "${comparison.entry.settingType}|${comparison.entry.aidexVersion}|${comparison.entry.version}|" +
                    "${comparison.entry.settingVersion}|${comparison.entry.source.name}|" +
                    "curr=${comparison.current.versionHex}|cand=${comparison.candidate.versionHex}|importedAt=$importedAt"
            }
        } else {
            null
        }
        if (defaultParamAutoProvisioning && autoApplyFingerprint != null) {
            val lastAttemptFingerprint = readStringPref("defaultParamAutoAttemptFingerprint")
            if (!diagnostics.exactMatch && lastAttemptFingerprint == autoApplyFingerprint) {
                Log.i(TAG, "Skipping automatic default-param apply — identical fingerprint was already attempted")
                defaultParamAutoProvisioning = false
                defaultParamProbeUserInitiated = false
                return
            }
        }
        val plan = AiDexDefaultParamProvisioning.planGuardedApply(
            currentRawHex = rawHex,
            modelName = _modelName,
            firmwareVersion = _firmwareVersion,
            maxChunkPayloadBytes = defaultParamChunkPayloadBytes(),
        )
        if (plan == null) {
            val statusMessage = when {
                diagnostics.bestComparison == null -> "DP apply unavailable"
                diagnostics.exactMatch -> "DP already matches"
                else -> "DP apply blocked"
            }
            showTransientStatus(statusMessage)
            defaultParamProbeUserInitiated = false
            defaultParamAutoProvisioning = false
            return
        }

        if (defaultParamAutoProvisioning && autoApplyFingerprint != null) {
            writeStringPref("defaultParamAutoAttemptFingerprint", autoApplyFingerprint)
        }
        defaultParamApplyState = DefaultParamApplyState(plan = plan)
        Log.i(TAG, "Starting guarded default-param apply: ${plan.summaryLine()}")
        showTransientStatus("Applying DP...")
        sendNextDefaultParamApplyChunk("manual-start")
    }

    private fun sendNextDefaultParamApplyChunk(reason: String) {
        val state = defaultParamApplyState ?: return
        val chunk = state.plan.chunks.getOrNull(state.nextChunkIndex) ?: return
        val cmd = commandBuilder.setDefaultParamChunk(
            totalCount = chunk.totalWords,
            startIndex = chunk.startIndex,
            payload = chunk.payload,
        ) ?: run {
            abortDefaultParamApply(
                reason = "session-key-unavailable",
                statusMessage = "DP apply failed",
            )
            return
        }
        Log.i(
            TAG,
            "Sending default-param chunk ${state.nextChunkIndex + 1}/${state.plan.chunks.size} " +
                "start=${chunk.startIndex} bytes=${chunk.payloadByteCount} ($reason)"
        )
        handler.removeCallbacks(defaultParamWriteAckWatchdog)
        handler.postDelayed(defaultParamWriteAckWatchdog, DEFAULT_PARAM_WRITE_ACK_TIMEOUT_MS)
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.SET_DEFAULT_PARAM))
    }

    private fun abortDefaultParamApply(reason: String, statusMessage: String) {
        Log.w(TAG, "Aborting default-param apply: $reason")
        clearDefaultParamApplyState()
        defaultParamProbeUserInitiated = false
        defaultParamAutoProvisioning = false
        showTransientStatus(statusMessage)
    }

    // =========================================================================
    // F003 Data Handling
    // =========================================================================

    private fun handleF003(encryptedData: ByteArray) {
        val now = System.currentTimeMillis()
        logi(TAG) { "handleF003: len=${encryptedData.size}, raw=${AiDexParser.hexString(encryptedData.copyOfRange(0, minOf(encryptedData.size, 8)))}" }

        // Status/keepalive frames (5 bytes) — decrypt and extract battery voltage
        val frameType = AiDexParser.classifyFrame(encryptedData)
        if (frameType == AiDexParser.FrameType.STATUS) {
            handleStatusFrame(encryptedData)
            return
        }

        if (frameType != AiDexParser.FrameType.DATA) {
            // 13-byte F003 frames appear after calibration commands (opcode 0x0A in logs).
            // Decrypt and log them — they may be calibration-related notifications.
            if (encryptedData.size == 13) {
                handleCalibrationNotificationFrame(encryptedData)
                return
            }
            Log.w(TAG, "F003: Unknown frame size ${encryptedData.size}")
            return
        }

        // Decrypt
        val decrypted = keyExchange.decrypt(encryptedData)
        if (decrypted == null) {
            notePreAuthEncryptedTraffic("F003", now)
            Log.w(TAG, "F003: Cannot decrypt — session key not available")
            return
        }

        // Parse
        val frame = AiDexParser.parseDataFrame(decrypted)
        if (frame == null) {
            Log.w(TAG, "F003: Failed to parse decrypted data frame (${AiDexParser.hexString(decrypted.copyOfRange(0, minOf(decrypted.size, 16)))})")
            return
        }

        logi(TAG) {
            "F003 parsed: offset=${frame.timeOffsetMinutes}min glucose=${frame.glucoseMgDl} mg/dL " +
                "packed=${frame.rawGlucosePacked} i1=${frame.i1} i2=${frame.i2} " +
                "opcode=0x${"%02X".format(frame.opcode)} valid=${frame.isValid}"
        }

        // Validate CRC-16 embedded in frame (bytes 15-16)
        val frameCrc = Crc16CcittFalse.checksum(decrypted.copyOfRange(0, 15))
        if (frameCrc != frame.crc16) {
            Log.w(TAG, "F003: CRC-16 mismatch (expected=0x${"%04X".format(frameCrc)}, got=0x${"%04X".format(frame.crc16)})")
            return
        }

        // Only a decrypted, CRC-clean DATA frame proves the live subscription is really up and
        // the session key still valid. Stamping this on every F003 notification let a 5-byte
        // STATUS keepalive, a 13-byte calibration notification or an undecryptable frame disarm
        // the no-stream watchdog for the rest of the connection: scheduleNoStreamWatchdog() then
        // sees lastF003FrameTimeMs >= streamingStartedAtMs and turns into a no-op, so the bounded
        // ladder (connected broadcast -> history refresh -> CCCD refresh -> reconnect) and the
        // broadcast fallback never run again. Sentinel frames deliberately still count: they
        // decrypt and pass CRC, so no ladder step can speed up a warming-up sensor.
        lastF003FrameTimeMs = now
        handler.removeCallbacks(noStreamWatchdog)

        if (!frame.isValid) {
            Log.w(TAG, "F003: Invalid reading (sentinel or out of range)")
            firstValidReadingWaitStatus(now)?.let {
                Log.i(TAG, "F003: still waiting for first valid reading ($it)")
            }
            // handleGlucoseResult() with 0 will set charcha[1] for failure tracking
            handleGlucoseResult(0L, now)
            if (firstValidReadingAnchorMs > 0L || postResetWarmupExtensionActive) {
                val warmupAnchor = effectiveWarmupAnchorMs()
                val warmupElapsed = warmupAnchor > 0L && (now - warmupAnchor) >= WARMUP_DURATION_MS
                if (warmupElapsed && constatstatusstr != POST_RESET_WAITING_STATUS) {
                    constatstatusstr = POST_RESET_WAITING_STATUS
                }
                UiRefreshBus.requestStatusRefresh()
            }
            return
        }

        if (pairKeyAwaitingLiveValidation) {
            val candidate = keyExchange.pairKey
            if (candidate != null && AiDexPairKeyVault.saveValidated(Applic.app, SerialNumber, candidate)) {
                persistedPairKey = candidate.copyOf()
                pairKeyAwaitingLiveValidation = false
                Log.i(
                    TAG,
                    "Persisted AiDex PAIR credential after valid direct F003 validation " +
                        "(fp=${AiDexPairKeyVault.fingerprint(candidate)})"
                )
                UiRefreshBus.requestStatusRefresh()
            } else {
                Log.e(TAG, "Could not persist validated AiDex PAIR credential; will retry on the next live frame")
            }
        }
        keyExchangeUsingSavedPairKey = false
        keyExchangeFailures = 0
        savedKeyExhausted = false
        explicitPairRequested = false
        if (!pairKeyAwaitingLiveValidation) {
            // Only the post-reset fresh pair's own session settles the reset, once its key is
            // persisted. The session that carried the 0xF3 ACK was keyed before the erase, so its
            // frames say nothing about the credential after it; after RESTORE_SAVED_KEY the flag
            // is already down.
            if (pairKeyResetPending && keyExchangeIsFreshPairAfterReset) {
                pairKeyResetPending = false
                Log.i(TAG, "Post-reset pairing settled by valid live data")
            }
            keyExchangeIsFreshPairAfterReset = false
        }

        if (currentBondState() == BluetoothDevice.BOND_BONDED) {
            setBondValidatedByStreaming(true, "direct-live")
        }

        val (trustedOffsetMinutes, offsetResolutionNote) = resolveTrustedLiveOffsetMinutes(frame, now)
        offsetResolutionNote?.let {
            Log.w(
                TAG,
                "F003 timing: $it " +
                    "(wireOffset=${frame.timeOffsetMinutes} glucose=${frame.glucoseMgDl} i1=${frame.i1} i2=${frame.i2})"
            )
        }

        handleParsedLiveReading(
            now = now,
            source = "native",
            autoValue = frame.glucoseMgDl,
            trustedTimeOffsetMinutes = trustedOffsetMinutes,
            rawValue = frame.i1 * 10f,
            sensorGlucose = frame.i1 * 18.0182f,
            rawI1 = frame.i1,
            rawI2 = frame.i2,
        )
    }

    /**
     * Handle 5-byte F003 status/keepalive frame.
     * Decrypts and attempts to extract battery voltage.
     *
     * These frames arrive every ~4 minutes between glucose data frames.
     * Format (after decryption) is not fully documented. We decrypt and log
     * the plaintext for analysis, and attempt to extract battery voltage
     * from bytes 1-2 as u16 LE millivolts (matching the vendor driver's
     * 2-byte LE battery format from AUTO_UPDATE_BATTERY_VOLTAGE).
     */
    private fun handleStatusFrame(encryptedData: ByteArray) {
        val decrypted = keyExchange.decrypt(encryptedData)
        if (decrypted == null) {
            notePreAuthEncryptedTraffic("F003-status")
            Log.d(TAG, "F003: Status frame — cannot decrypt (no session key)")
            return
        }

        Log.d(TAG, "F003: Status frame decrypted: ${AiDexParser.hexString(decrypted)}")

        // Attempt battery voltage extraction: bytes 1-2 as u16 LE millivolts.
        // Vendor driver reports typical range ~1530-1560 mV.
        // Accept any value in 500-3500 mV range as plausible.
        if (decrypted.size >= 3) {
            val candidate = (decrypted[1].toInt() and 0xFF) or ((decrypted[2].toInt() and 0xFF) shl 8)
            if (candidate in 500..3500) {
                _batteryMillivolts = candidate
                Log.i(TAG, "F003: Battery voltage: ${candidate} mV (${String.format("%.3f", candidate / 1000.0)} V)")
            }
        }
    }

    /**
     * Handle a 13-byte F003 frame — observed after calibration commands.
     *
     * The log shows frames like `0A EC 33 F1 EE 18 7D D2 ...` (opcode 0x0A) arriving
     * immediately after SET_CALIBRATION (0x25) is acknowledged. These may be
     * calibration-related notifications from the sensor confirming internal state changes.
     *
     * We decrypt, log, and attempt to parse any useful information.
     */
    private fun handleCalibrationNotificationFrame(encryptedData: ByteArray) {
        val decrypted = keyExchange.decrypt(encryptedData)
        if (decrypted == null) {
            notePreAuthEncryptedTraffic("F003-13-byte")
            Log.d(TAG, "F003: 13-byte frame — cannot decrypt (no session key)")
            return
        }

        val opcode = decrypted[0].toInt() and 0xFF
        Log.i(TAG, "F003: 13-byte notification frame: opcode=0x${"%02X".format(opcode)}, " +
                "decrypted=${AiDexParser.hexString(decrypted)}")

        // Opcode 0x0A is the only 13-byte F003 opcode we've observed in logs.
        // It appears to be a calibration state update notification.
        // For now, log the payload. If the frame contains calibration data in a known
        // format, we can parse it in the future.
        when (opcode) {
            0x0A -> {
                Log.i(TAG, "F003: Calibration notification (opcode 0x0A, ${decrypted.size} bytes)")
                // The sensor is confirming it updated its internal calibration state.
                // We already auto-refresh calibration records after a successful SET_CALIBRATION ACK,
                // so no additional action is needed here.
            }
            else -> {
                Log.d(TAG, "F003: Unknown 13-byte frame opcode 0x${"%02X".format(opcode)}")
            }
        }
    }

    // =========================================================================
    // F002 Command Responses
    // =========================================================================

    private fun handleF002Response(data: ByteArray, gatt: BluetoothGatt) {
        if (data.isEmpty()) return
        lastF002FrameTimeMs = System.currentTimeMillis()
        Log.i(TAG, "handleF002Response: len=${data.size}, raw=${AiDexParser.hexString(data.copyOfRange(0, minOf(data.size, 16)))}")

        // Decrypt if session key is available
        val plaintext = if (keyExchange.isComplete) {
            keyExchange.decrypt(data)
        } else {
            data
        }
        if (plaintext == null) {
            Log.w(TAG, "F002: Cannot decrypt response")
            return
        }

        // Validate CRC-16 on decrypted response.
        // Known data opcodes (0x21-0x24, 0x26-0x27) always have CRC trailers —
        // reject on mismatch to prevent processing corrupt data.
        // Control/ACK opcodes (0x11, 0x20, 0x25, 0x34, 0x35, 0xF0, 0xF2, 0xF3) may lack CRC.
        val crcValid = plaintext.size < 3 || Crc16CcittFalse.validateResponse(plaintext)

        val opcode = plaintext[0].toInt() and 0xFF
        Log.d(TAG, "F002 response: opcode=0x${"%02X".format(opcode)}, len=${plaintext.size}, crc=$crcValid")

        if (pendingResetReconnect && clearStorageQuietWindowActive && opcode != AiDexOpcodes.CLEAR_STORAGE) {
            resetDiag(
                stage = "quiet-window-response-ignored",
                details = "opcode=0x${"%02X".format(opcode)} len=${plaintext.size} crc=$crcValid",
            )
            return
        }

        // For data-carrying opcodes, reject on CRC failure
        if (!crcValid && opcode in intArrayOf(0x21, 0x22, 0x23, 0x24, 0x26, 0x27)) {
            Log.e(TAG, "F002: CRC-16 FAILED for data opcode 0x${"%02X".format(opcode)} — rejecting corrupt response")
            return
        }

        when (opcode) {
            0x10 -> handleStartupDeviceInfoResponse(plaintext)
            0x21 -> handleLegacyStartTimeResponse(plaintext)
            0x22 -> handleHistoryRangeResponse(plaintext)
            0x23 -> handleHistoryRawResponse(plaintext)
            0x24 -> handleHistoryBriefResponse(plaintext)
            0x25 -> handleCalibrationAck(plaintext)
            0x26 -> handleCalibrationRangeResponse(plaintext)
            0x27 -> handleCalibrationResponse(plaintext)
            0x30 -> handleSetDefaultParamResponse(plaintext)
            0x11 -> handleBroadcastDataResponse(plaintext)
            0x20 -> handleNewSensorAck(plaintext)
            0x31 -> handleDefaultParamResponse(plaintext)
            0x34 -> handleAutoUpdateStatusAck(plaintext)
            0x35 -> handleDynamicAdvModeAck(plaintext)
            0xF3 -> handleClearStorageResponse(plaintext)
            0xF0 -> handleResetResponse(plaintext)
            0xF2 -> handleDeleteBondResponse(plaintext)
            else -> {
                // AUTO_UPDATE_CALIBRATION detection: sensor pushes unsolicited calibration
                // data with an unknown opcode. Heuristic: valid CRC, size >= 12 (opcode +
                // status + startIndex_u16 + at least 1×8-byte calibration record), and the
                // opcode doesn't match any known command.
                if (plaintext.size >= 12 && Crc16CcittFalse.validateResponse(plaintext)) {
                    Log.i(TAG, "F002: Unsolicited push (opcode=0x${"%02X".format(opcode)}): " +
                            "attempting AUTO_UPDATE_CALIBRATION parse")
                    handleAutoUpdateCalibration(plaintext)
                } else {
                    Log.d(TAG, "F002: Unknown opcode 0x${"%02X".format(opcode)}")
                }
            }
        }
    }

    // -- Response Handlers --

    private fun handleStartupDeviceInfoResponse(data: ByteArray) {
        val payloadEndExclusive = if (data.size >= 4 && Crc16CcittFalse.validateResponse(data)) {
            data.size - 2
        } else {
            data.size
        }
        if (payloadEndExclusive <= 1) {
            Log.d(TAG, "Startup device info 0x10: too short (${data.size} bytes)")
            return
        }

        val payload = data.copyOfRange(1, payloadEndExclusive)
        val parsed = AiDexParser.parseStartupDeviceInfoFrame(data, payloadEndExclusive)
        if (parsed == null) {
            Log.d(
                TAG,
                "Startup device info 0x10: unsupported payload len=${payload.size} " +
                    "hex=${AiDexParser.hexString(payload.copyOfRange(0, minOf(payload.size, 16)))} — keeping DIS/2AAA as source of truth"
            )
            return
        }

        val resolvedFirmwareVersion = mergedFirmwareVersion(_firmwareVersion, parsed.firmwareVersion)
        _firmwareVersion = resolvedFirmwareVersion
        _hardwareVersion = parsed.hardwareVersion
        if (parsed.wearDays > 0) {
            _wearDays = parsed.wearDays
            sensorReportedWearDays = true
        }
        _modelName = parsed.modelName
        persistResolvedWearDays(
            "startup-0x10 raw=${if (sensorReportedWearDays) _wearDays.toString() else "none"} model=$_modelName"
        )
        startupMetadataComplete = hasCompleteStartupMetadata()
        Log.i(
            TAG,
            "Startup device info 0x10: fw=${parsed.firmwareVersion}" +
                if (resolvedFirmwareVersion != parsed.firmwareVersion) " mergedFw=$resolvedFirmwareVersion" else "" +
                " hw=$_hardwareVersion " +
                "days=${reportedWearDaysOrNull()?.toString() ?: "unknown"} source=${if (sensorReportedWearDays) "sensor" else "unknown"} model=$_modelName"
        )
        if (phase == Phase.STREAMING) {
            scheduleOptionalStreamingSync("startup-0x10-ready")
        }

        if (!hasAuthoritativeSessionStart) {
            requestLegacyStartTime("post-0x10")
        }
    }

    private fun handleLegacyStartTimeResponse(data: ByteArray) {
        val payloadEndExclusive = if (data.size >= 4 && Crc16CcittFalse.validateResponse(data)) {
            data.size - 2
        } else {
            data.size
        }
        if (payloadEndExclusive <= 2) {
            Log.d(TAG, "Legacy start time 0x21: too short (${data.size} bytes)")
            return
        }

        val payload = data.copyOfRange(2, payloadEndExclusive)
        val parsedStartTime = AiDexParser.parseLocalStartTimePayload(payload)
        if (parsedStartTime != null) {
            applyParsedSessionStartTime(
                parsed = parsedStartTime,
                source = "Legacy 0x21",
                allowActivation = false,
            )
            startupMetadataComplete = startupMetadataComplete || hasCompleteStartupMetadata()
            return
        }

        handleLegacyCombinedMetadataResponse(data)
    }

    private fun handleLegacyCombinedMetadataResponse(data: ByteArray) {
        if (data.size < 3) {
            Log.d(TAG, "Legacy combined metadata 0x21: too short (${data.size} bytes)")
            return
        }
        val statusByte = data[1].toInt() and 0xFF
        Log.i(TAG, "Legacy combined metadata 0x21: status=0x${"%02X".format(statusByte)}, len=${data.size}")
        if (data.size < 17 || statusByte != 0) {
            Log.d(TAG, "Legacy combined metadata 0x21 not supported on this sensor — using DIS + 2AAA")
            return
        }

        try {
            val fwMajor = data[3].toInt() and 0xFF
            val fwMinor = data[4].toInt() and 0xFF
            val hwMajor = data[5].toInt() and 0xFF
            val hwMinor = data[6].toInt() and 0xFF
            _firmwareVersion = mergedFirmwareVersion(_firmwareVersion, "$fwMajor.$fwMinor")
            _hardwareVersion = "$hwMajor.$hwMinor"

            val modelBytes = data.copyOfRange(9, 17)
            val nullIdx = modelBytes.indexOf(0.toByte())
            val modelStr = if (nullIdx >= 0) String(modelBytes, 0, nullIdx, Charsets.US_ASCII)
            else String(modelBytes, Charsets.US_ASCII)
            if (modelStr.isNotBlank()) {
                _modelName = modelStr.trim()
                applyWearProfileFromModel(_modelName)
            }
            Log.i(TAG, "Legacy combined metadata 0x21: fw=$_firmwareVersion hw=$_hardwareVersion model=$_modelName")
            if (phase == Phase.STREAMING && _modelName.isNotBlank() && _firmwareVersion.isNotBlank()) {
                scheduleOptionalStreamingSync("legacy-0x21-ready")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy combined metadata 0x21 parse failed: ${t.message}")
        }

        if (data.size >= 26) {
            val maybeStartTime = AiDexParser.parseLocalStartTimePayload(data.copyOfRange(17, data.size))
            if (maybeStartTime != null) {
                applyParsedSessionStartTime(
                    parsed = maybeStartTime,
                    source = "Legacy combined 0x21",
                    allowActivation = false,
                )
            } else {
                Log.d(TAG, "Legacy combined metadata 0x21: start-time tail was not plausible — using 2AAA/history instead")
            }
        }

        startupMetadataComplete = startupMetadataComplete || hasCompleteStartupMetadata()
    }

    private fun handleBroadcastDataResponse(data: ByteArray) {
        markStartupControlComplete("connected-broadcast-response")
        val crcValid = data.size >= 4 && Crc16CcittFalse.validateResponse(data)
        val payloadEndExclusive = Crc16CcittFalse.connectedBroadcastPayloadEnd(data.size, crcValid)
        if (payloadEndExclusive == null) {
            Log.e(TAG, "F002: CRC-16 FAILED for broadcast opcode 0x11 — rejecting corrupt response")
            return
        }
        if (payloadEndExclusive <= 2) {
            Log.d(TAG, "Connected broadcast response too short: len=${data.size}")
            return
        }
        val payload = data.copyOfRange(2, payloadEndExclusive)
        handleBroadcastPayload(
            payload = payload,
            source = "connected-broadcast",
            stopActiveScanAfterHandling = false,
        )
    }

    private fun handleDynamicAdvModeAck(data: ByteArray) {
        val status = data.getOrNull(1)?.toInt()?.and(0xFF)
        Log.i(
            TAG,
            "Dynamic adv mode ACK: len=${data.size}" +
                (status?.let { " status=0x${"%02X".format(it)}" } ?: "")
        )
        if (startupControlStage != StartupControlStage.WAIT_DYNAMIC_ADV_ACK) {
            Log.d(TAG, "Dynamic adv mode ACK ignored in stage=$startupControlStage")
            return
        }
        val cmd = commandBuilder.setAutoUpdateStatus(true)
        if (cmd == null) {
            Log.w(TAG, "Dynamic adv mode ACK received but auto-update command is unavailable — requesting connected broadcast directly")
            startupControlStage = StartupControlStage.FAILED
            handler.removeCallbacks(startupControlAckTimeout)
            requestConnectedBroadcastData("startup-control-no-auto-update")
            return
        }
        startupControlStage = StartupControlStage.WAIT_AUTO_UPDATE_ACK
        handler.removeCallbacks(startupControlAckTimeout)
        handler.postDelayed(startupControlAckTimeout, STARTUP_CONTROL_ACK_TIMEOUT_MS)
        Log.i(TAG, "Streaming startup: enabling auto-update before connected broadcast")
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.SET_AUTO_UPDATE_STATUS))
    }

    private fun handleAutoUpdateStatusAck(data: ByteArray) {
        val status = data.getOrNull(1)?.toInt()?.and(0xFF)
        Log.i(
            TAG,
            "Auto-update status ACK: len=${data.size}" +
                (status?.let { " status=0x${"%02X".format(it)}" } ?: "")
        )
        if (startupControlStage != StartupControlStage.WAIT_AUTO_UPDATE_ACK) {
            Log.d(TAG, "Auto-update status ACK ignored in stage=$startupControlStage")
            return
        }
        markStartupControlComplete("auto-update-ready")
        requestConnectedBroadcastData("startup-control-ready")
    }

    private fun handleDefaultParamResponse(data: ByteArray) {
        val payloadEndExclusive = if (data.size >= 4 && Crc16CcittFalse.validateResponse(data)) {
            data.size - 2
        } else {
            data.size
        }
        if (payloadEndExclusive <= 1) {
            Log.w(TAG, "Default param response too short: len=${data.size}")
            return
        }

        val payload = data.copyOfRange(1, payloadEndExclusive)
        val chunk = AiDexParser.parseDefaultParamChunk(payload)
        if (chunk == null) {
            Log.w(
                TAG,
                "Default param response parse failed: raw=${AiDexParser.hexString(data.copyOfRange(0, minOf(data.size, 24)))}"
            )
            clearDefaultParamProbeState()
            if (pendingDefaultParamApplyAfterProbe || defaultParamApplyVerifying || defaultParamProbeUserInitiated) {
                abortDefaultParamApply(
                    reason = "probe-parse-failed",
                    statusMessage = "DP probe failed",
                )
            }
            return
        }

        defaultParamProbeTotalWords = chunk.totalWords
        defaultParamProbeRawBuffer = AiDexParser.appendDefaultParamChunk(defaultParamProbeRawBuffer, chunk)

        val chunkPreview = AiDexParser.hexString(chunk.rawChunk.copyOfRange(0, minOf(chunk.rawChunk.size, 24)))
        Log.i(
            TAG,
            "Default param chunk: lead=0x${"%02X".format(chunk.leadByte)} totalWords=${chunk.totalWords} " +
                "start=${chunk.startIndex} next=${chunk.nextStartIndex} complete=${chunk.isComplete} raw=$chunkPreview"
        )

        if (chunk.isComplete) {
            val hex = AiDexParser.defaultParamRawHex(defaultParamProbeRawBuffer, chunk.totalWords)
            if (hex == null) {
                Log.w(TAG, "Default param probe completed but assembled raw blob is invalid")
                clearDefaultParamProbeState()
                if (pendingDefaultParamApplyAfterProbe || defaultParamApplyVerifying || defaultParamProbeUserInitiated) {
                    abortDefaultParamApply(
                        reason = "probe-invalid-assembly",
                        statusMessage = "DP probe failed",
                    )
                }
                return
            }
            lastDefaultParamRawHex = hex
            val packedVariant = AiDexDefaultParamProvisioning.normalizeCurrentVariants(hex).firstOrNull()
            val packedVersionHex = packedVariant?.versionHex
            Log.i(
                TAG,
                "Default param probe complete: totalWords=${chunk.totalWords} lead=0x${"%02X".format(chunk.leadByte)} " +
                    "rawHex=$hex packedVersion=${packedVersionHex ?: "?"}"
            )
            val diagnostics = AiDexDefaultParamProvisioning.diagnoseCurrentDefaultParam(
                currentRawHex = hex,
                modelName = _modelName,
                firmwareVersion = _firmwareVersion,
            )
            lastDefaultParamDiagnostics = diagnostics
            if (diagnostics.bestComparison != null) {
                Log.i(TAG, "Default param catalog compare: ${diagnostics.summaryLine()}")
            } else {
                Log.w(TAG, "Default param catalog compare unavailable: ${diagnostics.summaryLine()}")
            }
            clearDefaultParamProbeState()
            if (defaultParamApplyVerifying) {
                val statusMessage = if (diagnostics.exactMatch) {
                    "DP apply verified"
                } else {
                    "DP verify diff ${diagnostics.bestComparison?.diffByteCount ?: "?"}"
                }
                if (defaultParamAutoProvisioning && diagnostics.exactMatch) {
                    diagnostics.bestComparison?.let { comparison ->
                        val importedAt = diagnostics.catalog.importedUpdatedAtMs
                        val fingerprint =
                            "${comparison.entry.settingType}|${comparison.entry.aidexVersion}|${comparison.entry.version}|" +
                                "${comparison.entry.settingVersion}|${comparison.entry.source.name}|" +
                                "curr=${comparison.current.versionHex}|cand=${comparison.candidate.versionHex}|importedAt=$importedAt"
                        writeStringPref("defaultParamAutoVerifiedFingerprint", fingerprint)
                    }
                }
                clearDefaultParamApplyState()
                defaultParamProbeUserInitiated = false
                defaultParamAutoProvisioning = false
                showTransientStatus(statusMessage)
                return
            }
            maybeStartDefaultParamApplyAfterProbe(hex, diagnostics)
            return
        }

        requestDefaultParamProbe(chunk.nextStartIndex, "continuation")
    }

    private fun handleSetDefaultParamResponse(data: ByteArray) {
        val state = defaultParamApplyState ?: run {
            val status = data.getOrNull(1)?.toInt()?.and(0xFF)
            Log.i(
                TAG,
                "Default param write ACK without active apply: len=${data.size}" +
                    (status?.let { " status=0x${"%02X".format(it)}" } ?: "")
            )
            return
        }

        handler.removeCallbacks(defaultParamWriteAckWatchdog)

        val status = data.getOrNull(1)?.toInt()?.and(0xFF)
        Log.i(
            TAG,
            "Default param write ACK: chunk=${state.nextChunkIndex + 1}/${state.plan.chunks.size} len=${data.size}" +
                (status?.let { " status=0x${"%02X".format(it)}" } ?: "")
        )

        // Unreachable while maybeStartDefaultParamApplyAfterProbe refuses to start an apply:
        // defaultParamApplyState is assigned in exactly one place, past that gate. A barrier
        // holding, not dead code.
        val ackAccepted = AiDexRuntimePolicy.isDefaultParamAckAccepted(status)
        if (!ackAccepted) {
            // A short ACK carries no status at all, and an unrecognised status is a refusal until a
            // capture proves otherwise: sending the remaining chunks over it would leave the sensor
            // holding half of the old table and half of the new one.
            abortDefaultParamApply(
                reason = "unverified-ack status=${status?.let { "0x${"%02X".format(it)}" } ?: "missing"}",
                statusMessage = "DP apply stopped (unverified ACK)",
            )
            return
        }

        state.nextChunkIndex += 1
        if (state.nextChunkIndex < state.plan.chunks.size) {
            sendNextDefaultParamApplyChunk("ack")
            return
        }

        Log.i(TAG, "Default-param apply ACKed — re-reading 0x31 for verification")
        defaultParamApplyVerifying = true
        clearDefaultParamProbeState()
        requestDefaultParamProbe(0x01, "post-apply-verify")
    }

    private fun handleHistoryRangeResponse(data: ByteArray) {
        if (data.size < 8) return
        if (historyRangeQuietActive() || issuedHistoryGeneration != historyResponseGeneration) {
            Log.i(TAG, "History range (0x22) ignored — stale generation $issuedHistoryGeneration current=$historyResponseGeneration quiet=${historyRangeQuietActive()}")
            return
        }
        if (historyRangeAcceptedThisGeneration) {
            Log.i(TAG, "History range (0x22) ignored — already accepted this generation")
            return
        }
        // Untag in-flight pre-Start 0x23/0x24 until this connection's page requests stamp issued.
        historyResponseGeneration += 1
        historyRangeAcceptedThisGeneration = true
        // data[2..3] = briefStart (0x24), data[4..5] = rawStart (0x23), data[6..7] = newest offset
        val briefStart = u16LE(data, 2)
        val rawStart = u16LE(data, 4)
        val newest = u16LE(data, 6)
        historyNewestOffset = newest
        historyStoredCount = 0
        historyDownloading = true
        historyDownloadStartIndex = rawStart  // snapshot for progress display
        historyPhase = HistoryPhase.DOWNLOADING_CALIBRATED
        // The flag belongs to the download starting here, not to one an earlier page timeout in this
        // connection ended — requestHistoryRange arms the same watchdog for the 0x22 itself, so a
        // slow range answer can leave it set with no page behind it. Not the only guard (a normal
        // end clears it too); this just keeps the flag meaning "the current download timed out".
        historyPageTimedOut = false
        // Every download starts here, so this one clear covers all the ways the last one stopped.
        emptyRawPageRetried = false
        rawPageGapRetried = false
        // A download that stopped without completing — a startNewSensor, an abandoned reset, a
        // page timeout on this link — left rows that are already in the native store behind the
        // persisted cursors; only the full merge still gets them into Room.
        if (pendingRoomHistoryTimestamps.isNotEmpty()) historyRoomBufferDropped = true
        clearPendingRoomHistory("history-range-reset")
        Log.i(TAG, "History range: briefStart=$briefStart, rawStart=$rawStart, newest=$newest")

        val now = System.currentTimeMillis()
        if (postResetRequestedAtMs > 0L) {
            val quarantine = AiDexHistoryPolicy.shouldQuarantinePostResetHistoryRange(
                newestOffsetMinutes = newest,
                resetRequestedAtMs = postResetRequestedAtMs,
                nowMs = now,
            )
            resetDiag(
                stage = "history-range-verification",
                details = "briefStart=$briefStart rawStart=$rawStart newest=$newest quarantine=$quarantine",
                nowMs = now,
            )
            if (quarantine) {
                val nextIndex = (newest + 1).coerceAtLeast(1)
                historyRawNextIndex = nextIndex
                historyBriefNextIndex = nextIndex
                historyDownloadStartIndex = nextIndex
                writeIntPref("historyRawNextIndex", nextIndex)
                writeIntPref("historyBriefNextIndex", nextIndex)
                Log.w(
                    TAG,
                    "Post-reset history quarantine: sensor reported newest=$newest too soon after resetAt=$postResetRequestedAtMs; " +
                        "advancing cursors to $nextIndex and skipping stale ring contents"
                )
                // Released here on the same condition as the branch below, and for the same
                // reason: the barrier is owed to an unpaid activation, nothing else.
                //
                // Returning without it left it armed with no owner: these two range branches are
                // the only clears of a barrier whose debt is paid, and the reset flow itself never
                // clears one (abandonPendingReset leaves it to the erase the sensor confirmed).
                //
                // The quarantine test is `newest > elapsed-since-reset + grace`. On a sensor whose
                // firmware ACKs the 0xF3 without zeroing its ring age both sides grow a minute per
                // minute, so what stays constant is their difference — the ring's age when Reset
                // was pressed. Every later range response quarantined too, and backfill stayed
                // dead for the rest of the sensor's life, across restarts.
                //
                // What keeps the stale ring out is the cursor advance above, which is persisted
                // and survives this clear.
                if (!needsPostResetActivation) {
                    clearPostResetHistoryBarrier("history-range-quarantined-stale-ring")
                }
                onHistoryDownloadComplete()
                return
            }
            // Not while an activation is still owed. This barrier carries the only copy of the
            // reset timestamp, and the branch in [applyParsedSessionStartTime] that refuses a
            // start predating the erase — and with it the clear of [needsPostResetActivation] —
            // has nothing to compare against once it is gone. A ring the 0xF3 really erased
            // reports newest=0, which is never quarantined, so clearing here stripped the
            // reference from exactly the sensor that was wiped and never started: its stale
            // pre-reset start then became authoritative, went into sensorstartmsec and the native
            // store, and took the persisted activation debt with it. Both branches clear it once
            // the 0x20 has provably left the phone — the quarantine one above included, or the
            // barrier outlives its owner and quarantines every range response for good.
            if (!needsPostResetActivation) {
                clearPostResetHistoryBarrier("history-range-plausible-new-session")
            }
        }

        val downloadPlan = AiDexHistoryPolicy.planInitialDownload(
            briefStart = briefStart,
            rawStart = rawStart,
            newest = newest,
            persistedRawNextIndex = historyRawNextIndex,
            persistedBriefNextIndex = historyBriefNextIndex,
            rawCacheEmpty = calibratedGlucoseCache.isEmpty(),
            wearDays = reportedWearDaysOrNull(),
        )
        historyRawNextIndex = downloadPlan.rawNextIndex
        historyBriefNextIndex = downloadPlan.briefNextIndex
        historyDownloadStartIndex = downloadPlan.downloadStartIndex

        if (downloadPlan.action == AiDexHistoryPolicy.InitialAction.COMPLETE_EMPTY) {
            Log.i(TAG, "History range is empty — completing history download immediately")
            onHistoryDownloadComplete()
            return
        }

        // Snapshot liveOffsetCutoff for history dedup.
        // If live F003 readings have been stored this session, only skip the exact
        // live-backed minute already written by the live pipeline. Reconnect history
        // pages can legitimately contain newer missing minutes in the same page.
        if (liveOffsetCutoff == 0 && newest > 0) {
            // No live readings yet this session — set cutoff to newest so we don't
            // skip anything during initial history catch-up.
            // (liveOffsetCutoff stays 0 — storeHistoryEntries guards on > 0)
            Log.d(TAG, "History dedup: no live readings yet, liveOffsetCutoff stays 0 (no filtering)")
        } else if (liveOffsetCutoff > 0) {
            Log.i(TAG, "History dedup: liveOffsetCutoff=$liveOffsetCutoff (only the exact live-backed offset will be skipped)")
        }

        // Do not snap liveOffsetCutoff to newest. An untrusted live sits on wall clock and
        // does not cover that minute; a trusted live already set the cutoff from its offset.
        if (
            AiDexHistoryPolicy.shouldSnapLiveCutoffToNewest(
                liveOffsetCutoff = liveOffsetCutoff,
                lastDirectLiveReadingTimeMs = lastDirectLiveReadingTimeMs,
                newest = newest,
                trustedOffsetWasUsed = liveOffsetCutoff > 0,
            )
        ) {
            liveOffsetCutoff = newest
            Log.i(TAG, "History dedup: snapped liveOffsetCutoff to newest history offset $newest")
        }

        // Update sensorstartmsec from the newest offset.
        // This is critical: SuperGattCallback constructor may have set sensorstartmsec to "now"
        // (via Natives.getSensorStartmsec for a newly-registered sensor), but the sensor has
        // been running for days. ensureSensorStartTime will override if >10min off.
        if (newest > 0) {
            lastOffsetMinutes = newest
            ensureSensorStartTime(now)
        }

        Log.i(TAG, "History download: starting from raw=$historyRawNextIndex, brief=$historyBriefNextIndex (sensor range: $rawStart..$newest)")

        when (downloadPlan.action) {
            AiDexHistoryPolicy.InitialAction.REQUEST_RAW -> {
                scheduleRawHistoryPage(historyRawNextIndex)
            }
            AiDexHistoryPolicy.InitialAction.REQUEST_BRIEF -> {
                Log.i(TAG, "0x23 already up-to-date (rawNext=$historyRawNextIndex > newest=$newest)")
                historyPhase = HistoryPhase.DOWNLOADING_RAW
                scheduleBriefHistoryPage(historyBriefNextIndex)
            }
            AiDexHistoryPolicy.InitialAction.COMPLETE_ALREADY_CAUGHT_UP -> {
                Log.i(TAG, "0x23 and 0x24 already up-to-date (rawNext=$historyRawNextIndex, briefNext=$historyBriefNextIndex, newest=$newest)")
                onHistoryDownloadComplete()
            }
            AiDexHistoryPolicy.InitialAction.COMPLETE_EMPTY -> {
                onHistoryDownloadComplete()
            }
        }
    }

    /**
     * A history page that arrives while no download is active. The page itself is always dropped,
     * for two different reasons. A deliberate stop (reset, startNewSensor, the teardown before a
     * fresh connection) took both the merge cache and the Room batch with it, so processing the
     * page would cache against emptied state and push the persisted index past rows nothing
     * stored. [historyPageWatchdog] clears only the Room batch — calibratedGlucoseCache survives —
     * but its page is just as stale: the download it belonged to is already closed out, and
     * letting it move the in-memory cursors would only fight the restart below, which re-plans
     * from the persisted ones.
     *
     * What stopped the download decides what happens next. A reset, startNewSensor or a fresh
     * connection abandoned it on purpose — stay stopped. [historyPageWatchdog] did not: this page is
     * proof the sensor is still answering, only slower than the page timeout, so the rest of the
     * ring is still fetchable in this connection. Nothing else would ask for it — the no-stream
     * ladder is the only other in-connection caller of the range request and it never arms while
     * F003 keeps flowing. Restart once; the 0x22 answer re-plans from the persisted cursors, so the
     * page dropped here is requested again.
     */
    private fun dropStaleHistoryPage(label: String) {
        val timedOut = historyPageTimedOut
        historyPageTimedOut = false
        if (!timedOut) {
            Log.i(TAG, "$label page ignored — download no longer active")
            return
        }
        if (historyTimeoutRestartUsed || phase != Phase.STREAMING || mBluetoothGatt == null) {
            Log.w(
                TAG,
                "$label page ignored — arrived after the page watchdog gave up " +
                    "(restartUsed=$historyTimeoutRestartUsed phase=$phase)"
            )
            return
        }
        historyTimeoutRestartUsed = true
        Log.w(TAG, "$label page arrived after the page watchdog gave up — dropped, restarting history download once")
        requestHistoryRange()
    }

    private fun handleHistoryRawResponse(data: ByteArray) {
        if (data.size < 4) return
        if (
            historyRangeQuietActive() ||
            issuedHistoryGeneration != historyResponseGeneration ||
            !historyRangeAcceptedThisGeneration
        ) {
            Log.i(TAG, "History raw (0x23) ignored — stale generation")
            return
        }
        if (!historyDownloading) {
            dropStaleHistoryPage("History raw (0x23)")
            return
        }
        handler.removeCallbacks(historyPageWatchdog)  // Response arrived — cancel page timeout
        // data[1] = status, data[2..] = payload. CRC-16 trailer must not become a 2-byte row.
        val payload = Crc16CcittFalse.f002DataPayload(data)
        val entries = AiDexParser.parseHistoryResponse(payload)
        if (entries.isEmpty()) {
            Log.i(TAG, "History raw (0x23): empty page at offset=$historyRawNextIndex newest=$historyNewestOffset retried=$emptyRawPageRetried")
            when (
                AiDexHistoryPolicy.decideEmptyRawPage(
                    rawNextIndex = historyRawNextIndex,
                    briefNextIndex = historyBriefNextIndex,
                    newestOffset = historyNewestOffset,
                    emptyPageRetried = emptyRawPageRetried,
                )
            ) {
                AiDexHistoryPolicy.EmptyRawPageAction.RETRY_RAW -> {
                    // Mid-ring, not the end of it: ask for the page once more before 0x24 runs.
                    // Through the page scheduler, whose watchdog arm stays — never through
                    // requestHistoryRange or the watchdog, which would restart the whole download.
                    emptyRawPageRetried = true
                    scheduleRawHistoryPage(historyRawNextIndex)
                }
                AiDexHistoryPolicy.EmptyRawPageAction.START_BRIEF -> {
                    // After a retry this 0x24 pass stops at the raw cursor (briefCursorCap): minutes
                    // 0x23 has not delivered are neither merged nor stored, and both cursors wait
                    // there for the next connection's 0x23.
                    historyPhase = HistoryPhase.DOWNLOADING_RAW
                    scheduleBriefHistoryPage(historyBriefNextIndex)
                }
                AiDexHistoryPolicy.EmptyRawPageAction.COMPLETE -> onHistoryDownloadComplete()
            }
            return
        }
        if (entries.isNotEmpty()) {
            Log.i(TAG, "History raw (0x23): ${entries.size} entries, offsets ${entries.first().timeOffsetMinutes}..${entries.last().timeOffsetMinutes}")
            onCalibratedHistory?.invoke(entries)

            // Cache 0x23 calibrated glucose by offset using extracted helper.
            // DO NOT store yet — wait for 0x24 to provide raw ADC data,
            // then store BOTH together in a single aidexProcessData call.
            val (cached, skipped) = HistoryMerge.cacheCalibratedEntries(entries, calibratedGlucoseCache)
            Log.i(TAG, "0x23: cached $cached, skipped $skipped (cache size=${calibratedGlucoseCache.size})")
            emptyRawPageRetried = false

            // Stops after the last real value, so a sentinel tail is asked for again next time;
            // the sentinel markers the cache now holds do not count.
            val proposedNext = HistoryMerge.nextRawCursorAfterPage(entries, calibratedGlucoseCache)
            val (nextCursor, gapRetried) = AiDexHistoryPolicy.rawCursorAfterUnalignedPage(
                requestedOffset = historyRawNextIndex,
                firstEntryOffset = entries.first().timeOffsetMinutes,
                proposedNext = proposedNext,
                gapAlreadyRetried = rawPageGapRetried,
            )
            rawPageGapRetried = gapRetried
            if (gapRetried) {
                Log.w(
                    TAG,
                    "History raw (0x23): page starts at ${entries.first().timeOffsetMinutes} " +
                        "after request $historyRawNextIndex — asking again before skipping the hole",
                )
            }
            historyRawNextIndex = nextCursor
            writeIntPref("historyRawNextIndex", historyRawNextIndex)

            // Fetch next page if more data available
            if (historyRawNextIndex <= historyNewestOffset) {
                scheduleRawHistoryPage(historyRawNextIndex)
            } else {
                // 0x23 done — start 0x24 (brief/ADC history)
                Log.i(TAG, "0x23 complete. ${calibratedGlucoseCache.size} entries cached. Starting 0x24...")
                historyPhase = HistoryPhase.DOWNLOADING_RAW
                if (historyBriefNextIndex < AiDexHistoryPolicy.briefCursorCap(historyRawNextIndex, historyNewestOffset)) {
                    scheduleBriefHistoryPage(historyBriefNextIndex)
                } else {
                    onHistoryDownloadComplete()
                }
            }
        }
    }

    private fun handleHistoryBriefResponse(data: ByteArray) {
        if (data.size < 7) return
        if (
            historyRangeQuietActive() ||
            issuedHistoryGeneration != historyResponseGeneration ||
            !historyRangeAcceptedThisGeneration
        ) {
            Log.i(TAG, "History brief (0x24) ignored — stale generation")
            return
        }
        if (!historyDownloading) {
            // Same gate as 0x23: merging against a cache that a reset already emptied yields
            // glucose=0 for every remaining entry, which storeHistoryEntries then drops on the
            // 20..500 range check — history minutes gone with no error and no fallback.
            dropStaleHistoryPage("History brief (0x24)")
            return
        }
        handler.removeCallbacks(historyPageWatchdog)  // Response arrived — cancel page timeout
        val payload = Crc16CcittFalse.f002DataPayload(data)
        val entries = AiDexParser.parseBriefHistoryResponse(payload)
        if (entries.isEmpty()) {
            Log.i(TAG, "History brief (0x24): empty page at offset=$historyBriefNextIndex newest=$historyNewestOffset")
            onHistoryDownloadComplete()
            return
        }
        if (entries.isNotEmpty()) {
            Log.i(TAG, "History brief (0x24): ${entries.size} entries, offsets ${entries.first().timeOffsetMinutes}..${entries.last().timeOffsetMinutes}")
            onAdcHistory?.invoke(entries)

            // Only minutes whose 0x23 half has been fetched: past the raw cursor the merge has no
            // value but the carried-forward fallback, and a minute stored from it is invented.
            val cap = AiDexHistoryPolicy.briefCursorCap(historyRawNextIndex, historyNewestOffset)
            val mergeable = AiDexHistoryPolicy.rowsBelowCap(entries, cap) { it.timeOffsetMinutes }
            // Merge 0x24 raw ADC data with cached 0x23 calibrated glucose using extracted helper.
            val mergeResult = HistoryMerge.mergeHistoryEntries(mergeable, calibratedGlucoseCache, lastCalibratedGlucoseFallback)
            storeHistoryEntries(mergeResult.entries)
            // Persist the last known glucose for the next page
            if (mergeResult.lastKnownGlucose != null) lastCalibratedGlucoseFallback = mergeResult.lastKnownGlucose

            Log.i(TAG, "0x24: merged=${mergeResult.mergedCount} fallback=${mergeResult.fallbackCount} noGlucose=${mergeResult.noGlucoseCount} beyondRaw=${entries.size - mergeable.size} (cache remaining=${calibratedGlucoseCache.size})")

            // Never past the raw cursor, never past newest + 1: the next pass resumes exactly where
            // 0x23 stopped, and rows past newest are not skipped for good.
            historyBriefNextIndex = AiDexHistoryPolicy.nextBriefCursor(entries.last().timeOffsetMinutes, cap)
            writeIntPref("historyBriefNextIndex", historyBriefNextIndex)

            // Fetch next page
            if (historyBriefNextIndex < cap) {
                scheduleBriefHistoryPage(historyBriefNextIndex)
            } else {
                onHistoryDownloadComplete()
            }
        }
    }

    private fun handleCalibrationAck(data: ByteArray) {
        if (data.size < 2) {
            Log.w(TAG, "Calibration ACK too short: ${data.size} bytes")
            // The sensor answered, but not with an outcome. "Calibrating..." would otherwise just
            // expire, which reads exactly like success.
            showTransientStatus(CALIBRATION_NOT_CONFIRMED_STATUS, POST_RESET_OUTCOME_STATUS_MS)
            return
        }
        val statusByte = data[1].toInt() and 0xFF
        Log.i(TAG, "Calibration ACK: status=0x${"%02X".format(statusByte)}")

        if (statusByte == 0x01) {
            // Success — sensor accepted the calibration
            showTransientStatus("Calibration accepted")
            // Auto-refresh calibration records to include the new one
            handler.postDelayed({
                val cmd = commandBuilder.getCalibrationRange()
                if (cmd != null) {
                    Log.i(TAG, "Auto-refreshing calibration records after successful calibration")
                    enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.GET_CALIBRATION_RANGE))
                }
            }, 200L)
        } else {
            showTransientStatus("Calibration rejected (status 0x${"%02X".format(statusByte)})")
        }
    }

    private fun handleCalibrationRangeResponse(data: ByteArray) {
        if (data.size < 6) return
        val startIndex = u16LE(data, 2)
        val endIndex = u16LE(data, 4)
        Log.i(TAG, "Calibration range: start=$startIndex, end=$endIndex")

        calibrationRangeEndIndex = endIndex

        if (endIndex <= 0 || startIndex > endIndex) {
            // The sensor has answered: it holds no calibrations. Without this the routine refresh
            // re-armed on every live reading — "no records cached" is not the same as "not asked" —
            // and re-sent this query roughly once a minute for the whole sensor life.
            calibrationRangeKnownEmpty = true
            Log.i(TAG, "No calibration records available (start=$startIndex, end=$endIndex)")
            return
        }

        // Fetch all calibration records, starting from startIndex, chaining through endIndex
        calibrationRangeKnownEmpty = false
        calibrationDownloading = true
        requestCalibrationPaginated(startIndex, endIndex)
    }

    private fun handleCalibrationResponse(data: ByteArray) {
        if (data.size < 10) return
        // Strip opcode (1 byte) + status (1 byte) from front and CRC-16 (2 bytes) from end
        val crcEnd = if (data.size >= 4) data.size - 2 else data.size
        val payload = data.copyOfRange(2, crcEnd)
        Log.d(TAG, "Calibration payload: ${payload.size} bytes, hex=${AiDexParser.hexString(payload)}")
        val records = AiDexParser.parseCalibrationResponse(payload)
        if (records.isNotEmpty()) {
            Log.i(TAG, "Calibration records: ${records.size} entries")
            onCalibrationRecords?.invoke(records)

            // Merge new records into existing list (dedup by index), then convert
            // native CalibrationRecord → shared CalibrationRecord for UI.
            mergeCalibrationRecords(records)
        }
    }

    /**
     * Merge new native calibration records into the shared calibration record list,
     * deduplicating by index. Converts native → shared type and sorts by index.
     */
    private fun mergeCalibrationRecords(newRecords: List<CalibrationRecord>) {
        val existingByIndex = _calibrationRecords.associateBy { it.index }.toMutableMap()

        for (rec in newRecords) {
            val timestampMs = if (sensorstartmsec > 0L)
                sensorstartmsec + rec.timeOffsetMinutes.toLong() * 60_000L
            else 0L
            val valid = rec.timeOffsetMinutes > 0 &&
                    rec.timeOffsetMinutes.toLong() <= (MAX_OFFSET_DAYS * 24L * 60L) &&
                    rec.referenceGlucoseMgDl in MIN_VALID_GLUCOSE_MGDL..MAX_VALID_GLUCOSE_MGDL
            existingByIndex[rec.index] = SharedCalibrationRecord(
                index = rec.index,
                timeOffsetMinutes = rec.timeOffsetMinutes,
                referenceGlucoseMgDl = rec.referenceGlucoseMgDl,
                cf = rec.calibrationFactor,
                offset = rec.calibrationOffset,
                isValid = valid,
                timestampMs = timestampMs,
            )

            Log.d(TAG, "CALIBRATION: index=${rec.index} glucose=${rec.referenceGlucoseMgDl}mg/dL " +
                    "offset=${rec.timeOffsetMinutes}min cf=${String.format("%.2f", rec.calibrationFactor)} " +
                    "calOffset=${String.format("%.2f", rec.calibrationOffset)} valid=$valid")
        }

        _calibrationRecords = existingByIndex.values.sortedBy { it.index }
        Log.i(TAG, "Stored ${_calibrationRecords.size} calibration records for UI")
    }

    /**
     * Handle AUTO_UPDATE_CALIBRATION — unsolicited calibration push from sensor.
     *
     * The sensor pushes calibration data when its internal calibration state changes.
     * Same payload format as GET_CALIBRATION response: [opcode, status, startIndex_u16LE,
     * N×8-byte calibration records, CRC-16 trailer].
     */
    private fun handleAutoUpdateCalibration(data: ByteArray) {
        if (data.size < 12) return

        // Strip opcode + status (2 bytes) from front, CRC-16 (2 bytes) from end
        val payloadEnd = data.size - 2
        if (payloadEnd <= 2) {
            Log.d(TAG, "AUTO_UPDATE_CALIBRATION: no payload")
            return
        }
        val payload = data.copyOfRange(2, payloadEnd)

        val records = AiDexParser.parseCalibrationResponse(payload)
        if (records.isEmpty()) {
            Log.d(TAG, "AUTO_UPDATE_CALIBRATION: no records parsed from ${payload.size} bytes")
            return
        }

        Log.i(TAG, "AUTO_UPDATE_CALIBRATION: received ${records.size} record(s)")
        onCalibrationRecords?.invoke(records)
        mergeCalibrationRecords(records)
    }

    private fun handleNewSensorAck(data: ByteArray) {
        if (data.size < 2) return
        val statusByte = data[1].toInt() and 0xFF
        Log.i(TAG, "New sensor ACK: status=0x${"%02X".format(statusByte)}")
        val verdict = AiDexRuntimePolicy.setNewSensorAck(statusByte)
        if (verdict == AiDexRuntimePolicy.SetNewSensorAck.ACCEPTED) {
            setNewSensorAttemptInProgress = false
            rearmInitialHistoryAfterSetNewSensorOutcome()
            armFirstValidReadingWait(System.currentTimeMillis(), "new-sensor-ack")
            handler.postDelayed({ readCGMSessionCharacteristics() }, 1_000L)
        } else if (verdict == AiDexRuntimePolicy.SetNewSensorAck.UNCONFIRMED) {
            // No refusal on its own (a field sensor answered 0x01 and started): the local session
            // stays as committed at dispatch, and the session start decides
            // (settleUnconfirmedActivation). Nothing is sent again.
            Log.w(
                TAG,
                "SET_NEW_SENSOR (0x20) answered status=0x${"%02X".format(statusByte)}, not the documented 0x00 — " +
                    "the session start decides"
            )
            setNewSensorAttemptInProgress = false
            setNewSensorUnconfirmedAckAtElapsed = SystemClock.elapsedRealtime()
            rearmInitialHistoryAfterSetNewSensorOutcome()
            armFirstValidReadingWait(System.currentTimeMillis(), "new-sensor-ack-unconfirmed")
            handler.postDelayed({ readCGMSessionCharacteristics() }, UNCONFIRMED_ACTIVATION_SETTLE_MS)
        } else {
            // Debt is cleared by noteActivationWriteDelivered at accepted dispatch, not this ACK.
            // Do not restore needsPostResetActivation: that would retry 0x20 on every reconnect.
            Log.e(
                TAG,
                "SET_NEW_SENSOR (0x20) refused by the sensor " +
                    "(status=0x${"%02X".format(statusByte)}) — the session was NOT started"
            )
            showTransientStatus("Activation refused by the sensor", POST_RESET_OUTCOME_STATUS_MS)
            setNewSensorNackSeen = true
            handler.post { rollbackSetNewSensorLocalSession("sensor-nack") }
        }
    }

    /**
     * Settles a 0x20 whose answer was unconfirmed, from a session start just read ([startMs], 0 for
     * all zeros): a start not older than the 0x20 (less the 2-min slack) confirms it; zeros or an
     * older start the sensor kept, still read [UNCONFIRMED_ACTIVATION_SETTLE_MS] or more after the
     * answer, refuse it, which rolls the local session back as a refusal would have; read sooner,
     * they read again. Returns false when no unconfirmed 0x20 is pending (every ordinary read) or
     * the start confirms it; the caller then handles the read as usual: it accepts a non-zero
     * start, or runs the zero-start decision on zeros. With a verdict pending, every other read is
     * used up here: a refused one's kept start is accepted by the read posted after the rollback,
     * on the handler and in that order, so the rollback cannot undo the accepted start.
     */
    private fun settleUnconfirmedActivation(startMs: Long, source: String): Boolean {
        val ackAt = setNewSensorUnconfirmedAckAtElapsed
        if (ackAt <= 0L) return false
        return when (
            AiDexRuntimePolicy.decideUnconfirmedActivation(
                startMs = startMs,
                dispatchAtMs = setNewSensorDispatchedAtMs,
                ackAtMs = ackAt,
                nowMs = SystemClock.elapsedRealtime(),
                settleMs = UNCONFIRMED_ACTIVATION_SETTLE_MS,
                slackMs = AiDexRuntimePolicy.POST_RESET_START_SLACK_MS,
            )
        ) {
            AiDexRuntimePolicy.UnconfirmedActivation.STARTED -> {
                setNewSensorUnconfirmedAckAtElapsed = 0L
                Log.i(TAG, "$source start time: the sensor reports a new session — the unconfirmed 0x20 took effect")
                false
            }
            AiDexRuntimePolicy.UnconfirmedActivation.READ_AGAIN -> {
                val what = if (startMs > 0L) "still the old session" else "still zero"
                Log.i(TAG, "$source start time $what shortly after an unconfirmed 0x20 — reading again")
                handler.postDelayed({ readCGMSessionCharacteristics() }, UNCONFIRMED_ACTIVATION_SETTLE_MS)
                true
            }
            AiDexRuntimePolicy.UnconfirmedActivation.REFUSED -> {
                setNewSensorUnconfirmedAckAtElapsed = 0L
                val how = if (startMs > 0L) "predates" else "still zero after"
                Log.e(TAG, "$source start time $how an unconfirmed 0x20 — the session was NOT started")
                showTransientStatus("Activation refused by the sensor", POST_RESET_OUTCOME_STATUS_MS)
                // Latched first, as a refusal ACK is: a disconnect wipe that drops the posted
                // rollback still leaves the teardown's consume to roll back. A kept start is read
                // again after the rollback, where the verdict no longer applies and it is accepted.
                setNewSensorNackSeen = true
                handler.post {
                    rollbackSetNewSensorLocalSession("unconfirmed-refused")
                    if (startMs > 0L) readCGMSessionCharacteristics()
                }
                true
            }
        }
    }

    /**
     * Handle CLEAR_STORAGE (0xF3) response. Runs on the handler (dispatchF002Response posts it);
     * the binder peek there may already have claimed the same 0x01.
     * On success, leave the sensor command channel quiet while flash/storage clearing finishes.
     * Success is status `0x01` (see [AiDexRuntimePolicy.COMMAND_ACCEPTED]); `0x00` is a refusal.
     */
    private fun handleClearStorageResponse(data: ByteArray) {
        val status = AiDexRuntimePolicy.clearStorageAckStatus(data)
        if (data.size > AiDexRuntimePolicy.MAX_ACK_LENGTH) {
            // Not an ACK. Newer firmware is reported to answer with key material, so log its
            // shape and a fingerprint only, never the bytes.
            Log.w(
                TAG,
                "CLEAR_STORAGE response is not an ACK: len=${data.size} " +
                    "fp=${AiDexPairKeyVault.fingerprint(data)} — not a confirmation"
            )
        } else {
            Log.i(TAG, "CLEAR_STORAGE response: status=0x${"%02X".format(status)}")
        }
        val pending = pendingResetReconnect
        val stamped = postResetClearStorageAckAtMs > 0L
        // Every frame, whatever its status: this line is the on-device evidence for a refusal.
        resetDiag(
            stage = "f3-ack",
            details = "status=0x${"%02X".format(status)} responseLen=${data.size} pending=$pending stamped=$stamped",
        )
        when (AiDexRuntimePolicy.clearStorageResponseOutcome(pending, stamped, status)) {
            AiDexRuntimePolicy.ClearStorageResponseOutcome.CONFIRM -> {
                claimClearStorageConfirmation("handler")
                // Recheck: a teardown on another thread may have decided "not confirmed" first, or
                // this 0x01 came before the attempt's own write (claimClearStorageConfirmation).
                if (postResetClearStorageAckAtMs == 0L) {
                    Log.w(TAG, "CLEAR_STORAGE 0x01 not credited to this attempt")
                    return
                }
                // The window itself is still up from resetSensor(). Not raised again here: a
                // re-latch landing after a concurrent release would leave it with no owner, and
                // enqueueGattOp would refuse every operation for the life of this instance.
                gattQueue.clear()
                Log.i(
                    TAG,
                    "CLEAR_STORAGE accepted — not sending RESET (0xF0); waiting ${CLEAR_STORAGE_QUIET_WINDOW_MS}ms for clear/reboot"
                )
                scheduleClearStorageQuietWindow("f3-ack", postResetClearStorageAckAtMs)
                commitConfirmedClearStorageIfOwed()
            }
            AiDexRuntimePolicy.ClearStorageResponseOutcome.ABANDON -> {
                Log.e(TAG, "CLEAR_STORAGE failed — not arming extended post-reset warmup")
                abandonPendingReset("clear-storage-failed")
            }
            AiDexRuntimePolicy.ClearStorageResponseOutcome.LATE_ACCEPT -> {
                // The sensor accepted the erase, but this attempt was already given up, so nothing
                // is credited: no debt, no bond removal, no 0x20. The user decides. Its PAIR
                // credential went with the erase all the same, so the next connection pairs fresh.
                resetDiag("f3-ack-after-abandon", "status=0x01")
                markPairKeyResetPending("f3-ack-after-abandon")
                showTransientStatus(
                    "Sensor reported the erase after the reset was cancelled — check the sensor, then press Start",
                    POST_RESET_OUTCOME_STATUS_MS,
                )
            }
            AiDexRuntimePolicy.ClearStorageResponseOutcome.IGNORE -> {
                // A duplicate, or a refusal after this attempt's 0x01: never unwinds a confirmed erase.
                Log.w(TAG, "CLEAR_STORAGE response ignored (pending=$pending stamped=$stamped)")
            }
        }
    }

    /**
     * Handle RESET (0xF0) response.
     * This is a separate hardware-maintenance command. Lifecycle reset uses
     * CLEAR_STORAGE (0xF3) only because chaining 0xF0 can interrupt the clear.
     */
    private fun handleResetResponse(data: ByteArray) {
        val status = AiDexRuntimePolicy.commandAckStatus(data)
        Log.i(TAG, "RESET response: status=0x${"%02X".format(status)}")
    }

    /** Consume the unpair latch. Exactly one of the ACK, a disconnect and a failed write gets it. */
    private fun claimUnpairLatch(): Boolean = synchronized(unpairLock) {
        if (!pendingUnpairDisconnect) return false
        pendingUnpairDisconnect = false
        true
    }

    /**
     * End an unpair the sensor never confirmed. The pairing is still on the sensor, so the PAIR key
     * and the Android bond stay; isUnpaired goes with the latch so that the fallback the caller
     * enters next stamps a timed return to the direct path. Does not disconnect or scan itself.
     */
    private fun abandonUnconfirmedUnpair(reason: String): Boolean {
        // Both flags in one step: an unpairSensor admitted right after must keep its isUnpaired.
        synchronized(unpairLock) {
            if (!pendingUnpairDisconnect) return false
            pendingUnpairDisconnect = false
            isUnpaired = false
        }
        Log.w(TAG, "Unpair not confirmed by the sensor ($reason) — retaining PAIR credential and bond")
        return true
    }

    /**
     * Handle DELETE_BOND (0xF2) response.
     * If pendingUnpairDisconnect is set, perform the deferred bond removal + disconnect.
     */
    private fun handleDeleteBondResponse(data: ByteArray) {
        val status = AiDexRuntimePolicy.commandAckStatus(data)
        Log.i(TAG, "DELETE_BOND response: status=0x${"%02X".format(status)}")
        // A disconnect or a failed write may have given this unpair up already; then the key and
        // the bond it kept stay, whatever this frame says.
        if (!claimUnpairLatch()) return
        if (AiDexRuntimePolicy.shouldClearPersistedPairKey(deleteBondPending = true, responseStatus = status)) {
            isUnpaired = true
            Log.i(TAG, "DELETE_BOND acknowledged — performing confirmed unpair cleanup")
            if (!AiDexPairKeyVault.clearAfterConfirmedUnpair(Applic.app, SerialNumber)) {
                Log.e(TAG, "Confirmed unpair credential cleanup was not fully committed")
            }
            persistedPairKey = null
            pairKeyResetPending = false
            // Remove Android-level bond through the shared helper, so a refused or throwing
            // removeBond() reaches the status line instead of reading as a finished unpair.
            // No gatt parameter on this path, so the same fallback handle unpairSensor() uses:
            // without it a null mBluetoothGatt skips the removal and reports "Unpair incomplete".
            val bondRemoved = removeBondSafely(mBluetoothGatt?.device ?: mActiveBluetoothDevice, "delete-bond-response")
            keyExchange.reset()
            softDisconnect()
            constatstatusstr = if (bondRemoved) "Unpaired — Broadcast Only" else "Unpair incomplete — Broadcast Only"
            // The ACK runs on the handler. Release reads the mode on the IO thread, so the
            // write and that read share this lock.
            stop = false
            val startPostUnpairScan = synchronized(postUnpairScanLock) {
                reconnect.isBroadcastOnlyMode = true
                !postUnpairBroadcastScanSuppressed && !forgotten
            }
            UiRefreshBus.requestStatusRefresh()
            if (startPostUnpairScan) {
                handler.post { startBroadcastScan("post-unpair") }
            } else {
                Log.i(TAG, "post-unpair broadcast scan suppressed — sensor is being removed")
            }
        } else {
            postUnpairBroadcastScanSuppressed = false
            isUnpaired = false
            Log.w(TAG, "DELETE_BOND was rejected or malformed; retaining PAIR credential")
            constatstatusstr = "Unpair failed — key retained"
            UiRefreshBus.requestStatusRefresh()
        }
    }

    // =========================================================================
    // F002 Command Sending
    // =========================================================================

    private fun requestHistoryRange() {
        if (setNewSensorAttemptInProgress) return
        if (historyRangeQuietActive()) {
            pendingInitialHistoryRequest = true
            val remainingMs = (historyRangeQuietUntilElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
            handler.removeCallbacks(delayedInitialHistoryRequest)
            handler.postDelayed(delayedInitialHistoryRequest, remainingMs)
            Log.i(TAG, "History range deferred ${remainingMs}ms — waiting for in-flight 0x22/0x23/0x24 to die")
            return
        }
        val cmd = commandBuilder.getHistoryRange() ?: return
        pendingInitialHistoryRequest = false
        handler.removeCallbacks(delayedInitialHistoryRequest)
        if (historyRangeAcceptedThisGeneration) {
            // A new 0x22 is a new request. The accept-once flag is only for duplicate answers
            // to the in-flight range, not "one successful 0x22 for the whole connection".
            invalidateStaleHistoryResponses("history-range-reissue")
        }
        issuedHistoryGeneration = historyResponseGeneration
        historyDownloading = true
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.GET_HISTORY_RANGE))
        // The 0x22 answer is the only thing that normally clears historyDownloading, and it can be
        // lost, CRC-rejected or never sent at all (dropped in the quiet window, dropped by the op
        // watchdog). A latched flag pins the no-stream ladder to KEEP_WAITING and makes
        // reconnect() refuse every external trigger for the rest of the session, so bound the wait
        // exactly like a history page does — the watchdog clears the flag and persists the cursors.
        handler.removeCallbacks(historyPageWatchdog)
        handler.postDelayed(historyPageWatchdog, HISTORY_PAGE_TIMEOUT_MS)
    }

    private fun requestHistoryPage(opcode: Int, offset: Int, expectedGeneration: Int = historyResponseGeneration) {
        if (setNewSensorAttemptInProgress) return
        if (historyRangeQuietActive()) return
        if (expectedGeneration != historyResponseGeneration) return
        val cmd = when (opcode) {
            AiDexOpcodes.GET_HISTORIES_RAW -> commandBuilder.getHistoriesRaw(offset)
            AiDexOpcodes.GET_HISTORIES -> commandBuilder.getHistories(offset)
            else -> return
        }
        if (cmd != null) {
            issuedHistoryGeneration = expectedGeneration
            enqueueGattOp(GattOp.Write(CHAR_F002, cmd, opcode))
            // Start page timeout — if no response arrives, abort history download
            handler.removeCallbacks(historyPageWatchdog)
            handler.postDelayed(historyPageWatchdog, HISTORY_PAGE_TIMEOUT_MS)
        }
    }

    private fun requestCalibration(index: Int) {
        val cmd = commandBuilder.getCalibration(index) ?: return
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.GET_CALIBRATION))
    }

    /**
     * Fetch calibration records one index at a time from [currentIndex] through [lastIndex],
     * with 100ms delay between requests (matching Android vendor driver's Thread.sleep(100)).
     */
    private fun requestCalibrationPaginated(currentIndex: Int, lastIndex: Int) {
        requestCalibration(currentIndex)

        // The response handler (handleCalibrationResponse) will parse and store records.
        // Chain to next index after a delay. We track progress via calibrationRangeEndIndex.
        // Note: The F002 response dispatch will call handleCalibrationResponse which
        // stores records. We chain the next request after a delay here.
        val nextIndex = currentIndex + 1
        if (nextIndex <= lastIndex) {
            handler.postDelayed({
                requestCalibrationPaginated(nextIndex, lastIndex)
            }, 100L)
        } else {
            // Last request sent — mark download complete after a brief delay
            // to let the response arrive and be processed.
            // The delay runs from the last queued request, not from any answer, so "complete" here
            // only ever means "we asked". Record the attempt: a range whose 0x27 records never
            // parse leaves the cache empty, and an empty cache alone reads as "not asked yet".
            handler.postDelayed({
                calibrationDownloading = false
                calibrationFetchAttempted = true
                Log.i(TAG, "Calibration download complete: ${_calibrationRecords.size} records stored")
            }, 500L)
        }
    }

    // -- Public Command Methods --

    /**
     * Send a calibration reference value to the sensor.
     *
     * @param offsetMinutes time offset in minutes from sensor start
     * @param glucoseMgDl reference blood glucose in mg/dL
     */
    fun sendCalibration(offsetMinutes: Int, glucoseMgDl: Int) {
        val cmd = commandBuilder.setCalibration(offsetMinutes, glucoseMgDl) ?: run {
            Log.e(TAG, "Cannot send calibration — session key not available")
            return
        }
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.SET_CALIBRATION))
    }

    /**
     * Request history backfill (both calibrated and raw).
     */
    fun requestHistoryBackfill() {
        if (historyDownloading) return
        requestHistoryRange()
    }

    // =========================================================================
    // GATT Queue
    // =========================================================================

    private fun enqueueExclusiveClearStorage(cmd: ByteArray) {
        handler.post {
            val queuedBefore = gattQueue.size
            val activeBefore = describeGattOp(currentGattOp)
            val historyDownloadingBefore = historyDownloading
            handler.removeCallbacks(historyPageWatchdog)
            handler.removeCallbacks(delayedInitialHistoryRequest)
            handler.removeCallbacks(delayedStreamingMetadataRequest)
            handler.removeCallbacks(delayedDefaultParamAutoProvisioningRequest)
            handler.removeCallbacks(delayedCalibrationRefreshRequest)
            handler.removeCallbacks(startupControlAckTimeout)
            handler.removeCallbacks(delayedRawHistoryPage)
            handler.removeCallbacks(delayedBriefHistoryPage)
            pendingInitialHistoryRequest = false
            historyDownloading = false
            historyPhase = HistoryPhase.IDLE
            // Deliberate stop, same as startNewSensor: no late page may restart the download on a
            // ring that is about to be erased. The quiet window drops 0x23/0x24 anyway, but it is
            // released on the ACK while the link is still up.
            historyPageTimedOut = false
            // The download ends here whatever the sensor answers: these rows are already in the
            // native store behind the persisted cursors, so only the full merge still gets them
            // into Room (see historyRoomBufferDropped).
            if (pendingRoomHistoryTimestamps.isNotEmpty()) historyRoomBufferDropped = true
            clearPendingRoomHistory("exclusive-f3")
            calibrationDownloading = false
            clearDefaultParamProbeState()
            clearDefaultParamApplyState()

            val droppedUncommitted20 = !setNewSensorWriteDispatchedThisAttempt && (
                (currentGattOp as? GattOp.Write)?.opcode == AiDexOpcodes.SET_NEW_SENSOR ||
                    gattQueue.any { op -> op is GattOp.Write && op.opcode == AiDexOpcodes.SET_NEW_SENSOR }
                )
            gattQueue.clear()
            if (droppedUncommitted20) {
                clearUncommittedSetNewSensorAttempt("exclusive-f3")
            }
            gattQueue.addFirst(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.CLEAR_STORAGE))
            Log.i(TAG, "Queued exclusive CLEAR_STORAGE; cancelled pending F002/history work")
            resetDiag(
                stage = "f3-exclusive-queued",
                details = "queuedBefore=$queuedBefore activeBefore=$activeBefore historyDownloadingBefore=$historyDownloadingBefore",
            )
            if (!gattOpActive) {
                drainGattQueue()
            }
        }
    }

    private fun enqueueGattOp(op: GattOp) {
        handler.post {
            if (clearStorageQuietWindowActive) {
                postResetDroppedGattOps += 1
                Log.i(TAG, "Dropping queued GATT operation during CLEAR_STORAGE quiet window: ${describeGattOp(op)}")
                resetDiag("quiet-window-op-dropped", "op=${describeGattOp(op)}")
                return@post
            }
            if (
                op is GattOp.Write &&
                isHistoryQueryOpcode(op.opcode) &&
                (setNewSensorAttemptInProgress || forgotten || historyRangeQuietActive())
            ) {
                Log.i(
                    TAG,
                    "Dropping late-posted history query after Start/forget/quiet: ${describeGattOp(op)}",
                )
                return@post
            }
            gattQueue.add(op)
            if (!gattOpActive) {
                drainGattQueue()
            }
        }
    }

    // legacy BLE API: required at minSdk 26; the API 33 overloads are not a drop-in replacement
    @Suppress("DEPRECATION")
    private fun drainGattQueue() {
        if (gattOpActive) return
        if (queuePausedForBonding) {
            Log.d(TAG, "GATT queue: paused for bonding")
            return
        }
        if (gattQueue.isEmpty()) return

        // Cancel any prior watchdog before starting a new op
        handler.removeCallbacks(gattOpWatchdog)
        currentGattOp = null

        val next = gattQueue.removeFirst()
        val gatt = mBluetoothGatt
        if (gatt == null) {
            Log.w(TAG, "GATT queue: no active GATT, dropping ${gattQueue.size + 1} ops")
            val droppedSetNewSensor = (next as? GattOp.Write)?.opcode == AiDexOpcodes.SET_NEW_SENSOR ||
                gattQueue.any { op -> op is GattOp.Write && op.opcode == AiDexOpcodes.SET_NEW_SENSOR }
            gattQueue.clear()
            gattOpActive = false
            if (droppedSetNewSensor) {
                clearUncommittedSetNewSensorAttempt("gatt-null")
            }
            return
        }

        when (next) {
            is GattOp.Write -> {
                if (next.opcode == AiDexOpcodes.CLEAR_STORAGE && !pendingResetReconnect) {
                    // Only resetSensor() may put 0xF3 on air, and it holds pendingResetReconnect
                    // for the whole flow. Without that flag nothing credits the ACK, holds the
                    // history barrier or re-activates the sensor, so the erase would be silent
                    // data loss. Also the last line of defence for a queue purge that is still
                    // in flight: abandonPendingReset posts it, this runs first if it was queued.
                    Log.e(TAG, "GATT queue: refusing CLEAR_STORAGE (0xF3) — no reset pending")
                    resetDiag("f3-refused-no-pending-reset")
                    drainGattQueue()
                    return
                }
                val service = gatt.getService(SERVICE_F000)
                val characteristic = service?.getCharacteristic(next.charUuid)
                if (characteristic == null) {
                    Log.w(TAG, "GATT write: characteristic ${next.charUuid} not found, skipping")
                    if (next.opcode == AiDexOpcodes.SET_NEW_SENSOR) {
                        clearUncommittedSetNewSensorAttempt("char-missing")
                    }
                    drainGattQueue()
                    return
                }
                characteristic.value = next.data
                val writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                characteristic.writeType = writeType
                // Only an accepted write can reach the sensor, and this timestamp is what tells the
                // quiet-window timer whether the reset is still recoverable or has to be abandoned.
                // Stamped before the call, on this thread, and undone if the stack refuses the
                // write: the binder may claim the sensor's 0x01 the moment the frame is on air, and a
                // claim is credited only to an attempt whose write has gone out. The quiet-window
                // timer runs on this thread too, so it never sees the stamp of a refused write.
                // Keyed on the opcode and not on CHAR_F002: every F002 write shares that
                // characteristic, so a re-queued 0x22 slipping ahead of the exclusive 0xF3 would
                // stamp the reset as dispatched and print its own frame under f3-write-dispatched —
                // the artefact kept for byte-comparison with the official app — and the quiet-window
                // timer, reading that non-zero stamp, would take the local-disconnect path instead
                // of abandoning a reset that never left the phone.
                val resetWrite = pendingResetReconnect &&
                    clearStorageQuietWindowActive &&
                    next.opcode == AiDexOpcodes.CLEAR_STORAGE
                val firstResetWrite = resetWrite && postResetClearStorageWriteAtMs == 0L
                if (firstResetWrite) postResetClearStorageWriteAtMs = System.currentTimeMillis()
                val ok = gatt.writeCharacteristic(characteristic)
                if (firstResetWrite && !ok) postResetClearStorageWriteAtMs = 0L
                Log.d(TAG, "GATT write [${next.charUuid}]: ok=$ok, queueRemaining=${gattQueue.size}")
                if (resetWrite) {
                    resetDiag(
                        stage = "f3-write-dispatched",
                        details = "ok=$ok writeType=$writeType props=0x${"%02X".format(characteristic.properties)} " +
                            "encryptedLen=${next.data.size} encrypted=${AiDexParser.hexString(next.data)} retry=${next.retryCount}",
                    )
                }
                if (ok && resetWrite && postResetClearStorageAckAtMs == 0L) {
                    scheduleClearStorageQuietWindow(
                        reason = "f3-write-dispatched-awaiting-ack",
                        nowMs = postResetClearStorageWriteAtMs,
                    )
                }

                if (ok && next.opcode == AiDexOpcodes.SET_NEW_SENSOR) {
                    noteSetNewSensorDispatched()
                }

                if (ok && writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                    gattOpActive = false
                    handler.postDelayed({
                        if (!gattOpActive) drainGattQueue()
                    }, NO_RESPONSE_ADVANCE_MS)
                } else {
                    gattOpActive = ok
                    if (ok) {
                        currentGattOp = next
                        handler.postDelayed(gattOpWatchdog, GATT_OP_TIMEOUT_MS)
                    }
                }
                if (!ok) {
                    handleWriteFailure(next, gatt)
                }
            }
            is GattOp.Read -> {
                val service = gatt.getService(next.serviceUuid)
                val characteristic = service?.getCharacteristic(next.charUuid)
                if (characteristic == null) {
                    Log.w(TAG, "GATT read: characteristic ${next.charUuid} not found in service ${next.serviceUuid}, skipping")
                    drainGattQueue()
                    return
                }
                val ok = gatt.readCharacteristic(characteristic)
                Log.d(TAG, "GATT read [${next.charUuid}]: ok=$ok")
                gattOpActive = ok
                if (ok) {
                    currentGattOp = next
                    handler.postDelayed(gattOpWatchdog, GATT_OP_TIMEOUT_MS)
                }
                if (!ok) {
                    handleReadFailure(next, gatt)
                }
            }
        }
    }

    /**
     * A queued write we cannot prove was applied: its callback never arrived, or it arrived with
     * a GATT error status. Unlike [handleWriteFailure] — where `writeCharacteristic()` refused the
     * op locally and nothing went on air — here the sensor may already have executed the command,
     * so only the read-only queries may be re-sent. A state-changing command is dropped with an
     * error instead of being replayed blindly.
     *
     * [callbackReceived] says how the transaction ended, never whether the sensor saw the frame:
     * true means the stack completed it with an error status, false means no callback arrived at
     * all. The status itself does not separate the two either — Android reports the peer's ATT
     * errors and the local link's own failures in the same low byte, and the numbers overlap, so
     * no status value tells "the sensor took the frame" apart from "the stack gave up". Neither
     * outcome licenses re-sending an actuator command: on both, only
     * [AiDexRuntimePolicy.isRetryableF002Query] writes go back on the queue.
     */
    private fun failGattWrite(op: GattOp.Write, reason: String, callbackReceived: Boolean) {
        val action = AiDexRuntimePolicy.decideUnconfirmedWrite(
            opcode = op.opcode,
            retryCount = op.retryCount,
            maxQueryRetries = GATT_OP_WATCHDOG_RETRIES,
            callbackReceived = callbackReceived,
            clearStorageAckStamped = postResetClearStorageAckAtMs > 0L,
            unpairPending = pendingUnpairDisconnect,
        )
        if (action.requeueQuery) {
            op.retryCount++
            Log.w(
                TAG,
                "GATT write unconfirmed ($reason) — re-sending query ${describeGattOp(op)} " +
                    "(attempt ${op.retryCount}/$GATT_OP_WATCHDOG_RETRIES)"
            )
            requeueGattOp(op, "write-unconfirmed")
        } else {
            Log.e(TAG, "GATT write unconfirmed ($reason) — dropping ${describeGattOp(op)} without re-send")
            if (action.calibrationUnconfirmed) {
                // The sensor may or may not have taken it; we refuse to guess and refuse to
                // replay. Say so instead of letting "Calibrating..." expire as if nothing had
                // been sent — the user has to check the sensor before entering it again.
                showTransientStatus(CALIBRATION_NOT_CONFIRMED_STATUS, POST_RESET_OUTCOME_STATUS_MS)
            }
            if (action.abandonReset) {
                // An error status is not proof the erase never went out — a local status can land
                // after the PDU is already on air — but an unconfirmed transaction must not be
                // credited as a completed reset: the dispatch timestamp alone would carry the quiet
                // window into the local disconnect, the bond removal and the auto-0x20. Unwinding
                // to "the reset did not happen" keeps the bond and the sensor's ring, and
                // abandonPendingReset tells the user, who can press Reset again on a live link.
                // A sensor ACK that already arrived outranks the transport status: that erase is
                // confirmed and is not unwound here (abandonPendingReset re-checks under its lock).
                abandonPendingReset("clear-storage-write-status-failed")
            }
            if (action.abandonUnpair && abandonUnconfirmedUnpair("DELETE_BOND write unconfirmed: $reason")) {
                // A write the sensor never acknowledged is ambiguous, the same ambiguity the
                // disconnect path answers: the pairing is still on the sensor, so neither the
                // Android bond nor the PAIR credential is discarded here — only a sensor-
                // acknowledged DELETE_BOND ends a pairing. Both flags are cleared so the latch
                // cannot hand a bond removal to whichever disconnect happens next, and so
                // enterBroadcastOnlyFallback stamps a timed return to the direct path.
                // softDisconnect() before the fallback: its handler wipe would delete the scan the
                // fallback posts.
                keyExchange.reset()
                softDisconnect()
                enterBroadcastOnlyFallback(
                    reason = "unpair-write-unconfirmed",
                    statusText = "Unpair not confirmed — key retained",
                )
            }
        }
        drainGattQueue()
    }

    private fun handleWriteFailure(op: GattOp.Write, gatt: BluetoothGatt) {
        val bondState = gatt.device?.bondState ?: BluetoothDevice.BOND_NONE
        if (bondState == BluetoothDevice.BOND_BONDING) {
            Log.i(TAG, "GATT write failed during BONDING — pausing queue")
            queuePausedForBonding = true
            requeueGattOp(op, "bonding-pause") // Re-queue without incrementing retry
            handler.postDelayed({
                if (queuePausedForBonding) {
                    Log.w(TAG, "Bonding pause timeout (${BONDING_PAUSE_TIMEOUT_MS}ms) — resuming")
                    queuePausedForBonding = false
                    drainGattQueue()
                }
            }, BONDING_PAUSE_TIMEOUT_MS)
            return
        }
        op.retryCount++
        if (op.retryCount >= GATT_OP_MAX_RETRIES) {
            Log.e(TAG, "GATT write failed after ${op.retryCount} retries — dropping and reconnecting")
            if (op.opcode == AiDexOpcodes.SET_NEW_SENSOR) {
                clearUncommittedSetNewSensorAttempt("write-retries-exhausted")
            }
            gattQueue.clear()
            gattOpActive = false
            handler.post { recoverFromBrokenLink("gatt-write-retries-exhausted") }
        } else {
            requeueGattOp(op, "write-retry")
            handler.postDelayed({ drainGattQueue() }, GATT_WRITE_RETRY_DELAY_MS)
        }
    }

    private fun handleReadFailure(op: GattOp.Read, gatt: BluetoothGatt) {
        op.retryCount++
        if (op.retryCount >= GATT_OP_MAX_RETRIES) {
            Log.e(TAG, "GATT read failed after ${op.retryCount} retries — dropping")
            gattOpActive = false
            drainGattQueue()
        } else {
            requeueGattOp(op, "read-retry")
            handler.postDelayed({ drainGattQueue() }, GATT_WRITE_RETRY_DELAY_MS)
        }
    }

    /**
     * Put a refused operation back at the head of the queue — unless the exclusive CLEAR_STORAGE
     * quiet window is up.
     *
     * addFirst() goes around the gate in [enqueueGattOp]: a re-queued operation runs AHEAD of the
     * 0xF3 it was supposed to make way for and reaches a sensor that is meant to be hearing
     * nothing. [gattOpWatchdog] drops a pre-reset operation for the same reason.
     *
     * The CLEAR_STORAGE itself is the one operation the window exists for, so it goes back: its
     * re-queue comes from handleWriteFailure, i.e. a write the local stack refused (ok=false) or
     * paused for bonding, and neither ever left the phone — re-sending it is not a blind actuator
     * re-send. Dropping it here would lose the reset to a busy stack.
     */
    private fun requeueGattOp(op: GattOp, reason: String) {
        val isExclusiveClear = op is GattOp.Write && op.opcode == AiDexOpcodes.CLEAR_STORAGE
        if (pendingResetReconnect && clearStorageQuietWindowActive && !isExclusiveClear) {
            postResetDroppedGattOps += 1
            resetDiag("pre-f3-requeue-dropped", "op=${describeGattOp(op)} reason=$reason")
            Log.w(TAG, "Dropping re-queued ${describeGattOp(op)} ($reason) — CLEAR_STORAGE window is exclusive")
            return
        }
        gattQueue.addFirst(op)
    }

    // =========================================================================
    // Service Discovery Retry + Watchdog
    // =========================================================================

    private var discoveryRetryAttempt = 0

    /**
     * Connections in a row that never reached `onServicesDiscovered`; drives the broadcast
     * fallback. Volatile because it is written from the GATT binder thread and from the handler,
     * and read by the threshold that decides the degraded mode.
     */
    @Volatile private var consecutiveDiscoveryFailures = 0

    private fun scheduleDiscoveryRetries(gatt: BluetoothGatt) {
        discoveryRetryAttempt = 0
        scheduleNextDiscoveryRetry(gatt)
    }

    private fun scheduleNextDiscoveryRetry(gatt: BluetoothGatt) {
        discoveryRetryAttempt++
        if (discoveryRetryAttempt > DISCOVERY_MAX_RETRIES) {
            // All retries exhausted — disconnect and reconnect cleanly
            Log.w(TAG, "Service discovery retries exhausted ($DISCOVERY_MAX_RETRIES). Disconnecting and scheduling reconnect.")
            recoverFromServiceDiscoveryFailure()
            return
        }
        handler.postDelayed({
            if (mBluetoothGatt != null && !servicesReady) {
                Log.i(TAG, "Service discovery retry $discoveryRetryAttempt/$DISCOVERY_MAX_RETRIES")
                mBluetoothGatt?.discoverServices()
                // Schedule next retry (or the final recovery)
                scheduleNextDiscoveryRetry(gatt)
            }
        }, DISCOVERY_RETRY_DELAY_MS)
    }

    /**
     * Called when a connection never became usable: discovery failed after all retries, the
     * expected services were missing, or the MTU callback never arrived.
     *
     * After [DISCOVERY_FAILURE_BROADCAST_FALLBACK_THRESHOLD] of these in a row it switches to
     * broadcasts instead. A wedged or unreachable link is not going to be argued into working, and
     * broadcasts need neither MTU, discovery nor CCCDs — they are the only readings left while the
     * radio conditions last. [enterBroadcastOnlyFallback] stamps the entry and
     * [maybeLeaveBroadcastOnlyFallback] gets the full session back once the air has had
     * [BROADCAST_FALLBACK_RETRY_MS] to clear.
     */
    private fun recoverFromServiceDiscoveryFailure() {
        consecutiveDiscoveryFailures += 1
        Log.w(
            TAG,
            "service discovery failure " +
                "$consecutiveDiscoveryFailures/$DISCOVERY_FAILURE_BROADCAST_FALLBACK_THRESHOLD"
        )
        val toBroadcast = consecutiveDiscoveryFailures >= DISCOVERY_FAILURE_BROADCAST_FALLBACK_THRESHOLD &&
            !stop && !isPaused && !isUnpaired && !reconnect.isBroadcastOnlyMode
        recoverFromBrokenLink("service-discovery-failure", scheduleReconnect = !toBroadcast)
        if (!toBroadcast) return

        Log.w(TAG, "Service discovery failed $consecutiveDiscoveryFailures times in a row — entering broadcast-only fallback")
        enterBroadcastOnlyFallback(
            reason = "discovery-failure-fallback",
            statusText = "Broadcast fallback",
        )
    }

    /**
     * Tear down a link we have given up on and reconnect from a clean slate.
     *
     * Closing is not exclusive to this function — the DISCONNECTED branch, the recovery
     * completions and softDisconnect()/destroy() call close() themselves. What none of them may
     * skip is clearing the phase first: close() nulls mBluetoothGatt and suppresses the
     * DISCONNECTED callback, which is the only other place that clears the phase — so closing
     * without clearing it leaves the phase at its setup/streaming value forever, and
     * connectDevice()'s `phase != Phase.IDLE` guard then refuses the reconnect scheduled below and
     * every later one, including the user's manual reconnect. resetConnectionRuntimeState() also
     * drops the whole pending-callback set (watchdogs, broadcast assist) that belonged to the dead
     * session.
     *
     * [scheduleReconnect] is false for callers that schedule something else instead — the
     * broadcast hand-offs. The teardown above still
     * has to happen there, and it has to happen before anything is posted, because
     * resetConnectionRuntimeState() drops every pending callback.
     */
    private fun recoverFromBrokenLink(reason: String, scheduleReconnect: Boolean = true) {
        Log.w(TAG, "recoverFromBrokenLink($reason): closing GATT")
        constatstatusstr = "Reconnecting"
        connectTime = 0L
        setPhase(Phase.IDLE)
        pendingStaleConnectionRecovery = false
        resetConnectionRuntimeState(reason = reason, resetInvalidSetupCounter = false)
        close()
        if (!scheduleReconnect) return
        val delay = reconnect.nextReconnectDelayMs()
        Log.i(TAG, "Scheduling reconnect after $reason in ${delay}ms")
        handler.postDelayed({ connectDevice(0) }, delay)
    }

    // =========================================================================
    // Data Storage Helpers
    // =========================================================================

    // HistoryStoreEntry is now in data/HistoryMerge.kt for testability

    /**
     * Ensure sensorstartmsec is correct before storing data.
     *
     * Matches the vendor driver's updateStartTimeFromOffset() logic:
     * - If lastOffsetMinutes is available, compute inferredStart = now - offset * 60_000
     * - Override sensorstartmsec if it's 0 OR if it's >10 minutes off from the inferred value
     *   (handles the case where SuperGattCallback constructor set it to "now" via
     *   Natives.getSensorStartmsec for a newly-registered sensor, even though the sensor
     *   has been running for days), or if the backward wall-clock step below just invalidated it —
     *   that step is measured against the newest reading and is usually smaller than the 10-minute
     *   threshold, so it has to bypass it
     * - Never overwrite an authoritative session start with a live-derived offset
     * - If we still have no trustworthy start anchor, leave it unset instead of fabricating "now"
     * - Re-derive even an authoritative anchor once the wall clock steps back behind it
     */
    private fun ensureSensorStartTime(now: Long, trustedOffsetMinutes: Int? = null) {
        // Captured here because the branch below zeroes the floor this is measured against, and
        // the re-derive it asks for lives further down behind a threshold this step rarely clears.
        val clockSteppedBack =
            lastGlucoseTimeMs - now > AiDexHistoryPolicy.OFFSET_TIMESTAMP_FUTURE_SLACK_MS
        // A backward wall-clock step (manual set, NTP correction) leaves every wall-clock anchor
        // stamped ahead of `now`, and no single path downstream survives that: for live rows
        // resolveOffsetBackedTimestampMs falls back to `now` while storeHistoryEntries keeps
        // stamping from the old anchor and its future guard then drops whole pages behind an
        // advancing cursor, and shouldAcceptRealtimeTimestamp rejects every live sample until the
        // clock catches up — silently, because hasRecentLiveData() reads that same future stamp as
        // "data just arrived". The sensor's minute offset is the only monotonic anchor across the
        // step, so drop the floor the old clock built and let the offset re-derive the start below.
        // Threshold is the acceptance slack: anything smaller is ordinary sensor/phone clock skew
        // that the stamping paths already tolerate.
        if (clockSteppedBack) {
            Log.w(
                TAG,
                "Wall clock stepped ${(lastGlucoseTimeMs - now) / 1000}s back behind the last accepted " +
                    "reading — dropping the realtime floor and re-deriving sensorstartmsec from the wire offset"
            )
            lastGlucoseTimeMs = 0L
            hasAuthoritativeSessionStart = false
        }
        if (hasAuthoritativeSessionStart && sensorstartmsec > 0L) {
            updateSensorExpiredFromStart(now)
            return
        }

        val effectiveOffsetMinutes = trustedOffsetMinutes ?: lastOffsetMinutes.takeIf { it > 0 }
        if (effectiveOffsetMinutes != null && effectiveOffsetMinutes > 0) {
            val inferredStart = now - (effectiveOffsetMinutes.toLong() * 60_000L)
            // The step branch above measures against the newest accepted reading, so what it sees
            // is the real step minus that reading's age — at minute cadence that lands under the
            // 10-minute threshold for most of the range it fires in. Without clockSteppedBack the
            // floor and the authoritative flag are gone while the anchor stays in the old clock's
            // coordinates: live rows fall back to `now`, history rows keep stamping from the stale
            // anchor, and the future guard in storeHistoryEntries silently drops the newest ones
            // behind a cursor that was already advanced and persisted.
            if (clockSteppedBack ||
                sensorstartmsec == 0L ||
                kotlin.math.abs(sensorstartmsec - inferredStart) > (10L * 60_000L)
            ) {
                sensorstartmsec = inferredStart
                Log.i(TAG, "Updated sensorstartmsec from offset: ${effectiveOffsetMinutes}min → $inferredStart")
                if (dataptr != 0L) {
                    try {
                        Natives.aidexSetStartTime(dataptr, sensorstartmsec)
                    } catch (_: Throwable) {}
                    // Resolve on the handler after the start write. Sampling the day count
                    // here would let a catalog life land after 0x10 has stored a longer one.
                    runOnHandler { persistResolvedWearDays("offset-start") }
                }
            }
        }

        // Update local expiry state whenever start time is set
        if (sensorstartmsec > 0L) {
            updateSensorExpiredFromStart(now)
        }
    }

    /**
     * Store a batch of history entries to the native C++ layer without triggering
     * live-reading side effects on every row.
     *
     * Matches the vendor driver's storeHistoryRecord() logic:
     * - Requires sensorstartmsec > 0 (timestamp = sensorstartmsec + offset * 60_000)
     * - Filters: [AiDexHistoryPolicy.historyStoreRejection] — invalid entries, offsets out of
     *   range, past the declared wear or the sensor's newest offset, the live-backed minute, ADC
     *   saturation (≥1023), out-of-range glucose, future timestamps. No warmup gate.
     * - Uses a quiet native history path (native persistence only)
     * - Leaves UI/current-reading refresh to the single catch-up update after download completion
     * - Leaves Room merge to a single non-destructive sync after download completion
     */
    private fun storeHistoryEntries(entries: List<HistoryStoreEntry>) {
        // Ensure start time is available before computing timestamps.
        // History download can begin before 0x21 succeeds or before any F003 arrives.
        if (sensorstartmsec <= 0L) {
            ensureSensorStartTime(System.currentTimeMillis())
        }
        if (sensorstartmsec <= 0L) {
            Log.w(TAG, "storeHistoryEntries: sensorstartmsec still not set after ensureSensorStartTime — skipping ${entries.size} entries")
            return
        }
        if (dataptr == 0L) {
            Log.w(TAG, "storeHistoryEntries: dataptr is 0 — cannot store")
            return
        }

        val now = System.currentTimeMillis()
        val reportedWearDays = reportedWearDaysOrNull()
        val wearDurationMinutes = AiDexHistoryPolicy.wearDurationMinutes(reportedWearDays)
        var stored = 0
        var skippedWearDuration = 0
        var newestStoredTimeMs = 0L
        val temperatureRecords = ArrayList<AiDexTemperatureStore.TemperatureRecord>()

        for (entry in entries) {
            // No warmup gate — valid readings during warmup are stored and displayed. The
            // validity and range checks already drop garbage warmup data (e.g. glucose=15 mg/dL).
            val rejection = AiDexHistoryPolicy.historyStoreRejection(
                offsetMinutes = entry.offsetMinutes,
                glucoseMgDl = entry.glucoseMgDl,
                isValid = entry.isValid,
                sensorStartMs = sensorstartmsec,
                nowMs = now,
                wearDays = reportedWearDays,
                historyNewestOffset = historyNewestOffset,
                liveOffsetCutoff = liveOffsetCutoff,
            )
            if (rejection != null) {
                if (rejection == AiDexHistoryPolicy.HistoryStoreRejection.PAST_WEAR) skippedWearDuration++
                continue
            }
            val historicalTimeMs = sensorstartmsec + (entry.offsetMinutes.toLong() * 60_000L)

            // Store via JNI — quiet history path, no per-entry UI wakeups
            try {
                val rawForStore = HistoryMerge.normalizeRawMgDl(entry.rawMgDl) ?: 0f
                Natives.aidexStoreHistoryData(dataptr, historicalTimeMs, entry.glucoseMgDl, rawForStore)
                pendingRoomHistoryTimestamps.add(historicalTimeMs)
                pendingRoomHistoryValues.add(entry.glucoseMgDl)
                pendingRoomHistoryRawValues.add(rawForStore)
                if (AiDexTemperatureStore.isPlausibleSkinTemperatureC(entry.temperatureC)) {
                    temperatureRecords.add(
                        AiDexTemperatureStore.TemperatureRecord(historicalTimeMs, entry.temperatureC)
                    )
                }
                stored++
                historyStoredCount++
                if (historicalTimeMs > newestStoredTimeMs) {
                    newestStoredTimeMs = historicalTimeMs
                }
                if (entry.offsetMinutes > lastHistoryNewestOffset) {
                    lastHistoryNewestOffset = entry.offsetMinutes
                    lastHistoryNewestGlucose = entry.glucoseMgDl
                }
            } catch (t: Throwable) {
                Log.e(TAG, "storeHistoryEntries: aidexProcessData failed: $t")
                break  // Don't keep hammering a broken JNI
            }

        }

        if (skippedWearDuration > 0) {
            val wearLimitLabel = wearDurationMinutes?.let { "${it}min" } ?: "unknown"
            Log.w(
                TAG,
                "storeHistoryEntries: skipped $skippedWearDuration/${entries.size} entries at/after declared wear duration " +
                    "($wearLimitLabel, wearDays=$reportedWearDays)"
            )
        }
        if (stored > 0) {
            noteValidReadingAvailable(newestStoredTimeMs, "valid-history")
            Log.i(TAG, "storeHistoryEntries: stored $stored/${entries.size} entries (total=$historyStoredCount)")
            if (temperatureRecords.isNotEmpty()) {
                Applic.app?.let { context ->
                    AiDexTemperatureStore.appendTemperatureHistory(context, SerialNumber, temperatureRecords)
                }
            }
        }
    }

    /**
     * Called when all history pages (both raw and brief) have been downloaded.
     */
    private fun onHistoryDownloadComplete() {
        handler.removeCallbacks(historyPageWatchdog)  // History done — cancel any pending page timeout
        historyDownloading = false
        historyPhase = HistoryPhase.IDLE
        // This download ended normally, so a page arriving after it is late or duplicate and must
        // stay dropped. Left latched from an earlier timeout in this connection, it sends
        // dropStaleHistoryPage into a full 0x22 restart over a ring already caught up: that burns
        // the one-shot historyTimeoutRestartUsed a real page timeout later in this connection
        // needs, and for the extra round historyDownloading=true pins the no-stream ladder to
        // KEEP_WAITING and makes reconnect() refuse external triggers.
        historyPageTimedOut = false

        // Log any remaining cached 0x23 entries that had no matching 0x24
        if (calibratedGlucoseCache.isNotEmpty()) {
            Log.i(TAG, "History complete: ${calibratedGlucoseCache.size} cached 0x23 entries had no matching 0x24")
            calibratedGlucoseCache.clear()
        }
        lastCalibratedGlucoseFallback = null

        Log.i(TAG, "History download complete. Total entries stored: $historyStoredCount")

        // Ordinary AiDex history import already has the final timestamps/glucose values
        // in Kotlin. Push that batch directly into Room instead of rereading the full
        // native store back into Room again.
        val roomBufferDropped = historyRoomBufferDropped
        historyRoomBufferDropped = false
        // Exception to the direct push: a dropped batch — page watchdog, or a teardown one or more
        // connections ago — was already written to the native store, so a direct flush can only
        // carry the rows that came after it and Room keeps the hole. Rebuild Room from the native
        // store instead — also when this pass stored nothing itself, e.g. the timeout hit the last
        // page and the restart found us caught up. mergeFullSyncForSensor is non-destructive and
        // rate-limited, so paying the debt one download late costs one extra merge.
        if (historyStoredCount > 0 || roomBufferDropped) {
            val storedDirectly = if (roomBufferDropped) {
                false
            } else {
                try {
                    flushPendingRoomHistoryToRoom()
                } catch (t: Throwable) {
                    Log.e(TAG, "Direct Room history flush failed: $t")
                    false
                }
            }
            if (!storedDirectly) {
                try {
                    tk.glucodata.HistorySyncAccess.mergeFullSyncForSensor(SerialNumber)
                } catch (t: Throwable) {
                    Log.e(TAG, "HistorySync.mergeFullSyncForSensor fallback failed: $t")
                    try {
                        tk.glucodata.HistorySyncAccess.syncSensorFromNative(SerialNumber, forceFull = true)
                    } catch (_: Throwable) {}
                }
                clearPendingRoomHistory("merge-fallback")
            }
        }

        // Catch-up broadcast: notify xDrip/Watchdrip of the newest history record
        // so the stream resumes immediately without waiting for the next F003 (~60s).
        // Uses manual pack (not aidexProcessData) to avoid double-writing data
        // that was already stored by storeHistoryEntries().
        if (AiDexHistoryPolicy.shouldEmitCatchUpBroadcast(
                lastHistoryNewestGlucose = lastHistoryNewestGlucose,
                lastHistoryNewestOffset = lastHistoryNewestOffset,
                liveOffsetCutoff = liveOffsetCutoff,
            )
        ) {
            val catchUpTimestamp = sensorstartmsec + (lastHistoryNewestOffset.toLong() * 60_000L)
            val catchUpDisplayGlucose = if (Applic.unit == 1) {
                lastHistoryNewestGlucose / 18.0f
            } else {
                lastHistoryNewestGlucose
            }
            // Publish the catch-up sample without routing it back through shared
            // storage. The history row was already persisted above.
            SuperGattCallback.processExternalCurrentReading(
                SerialNumber,
                catchUpDisplayGlucose,
                0f,
                catchUpTimestamp,
                sensorgen
            )
            Log.i(
                TAG,
                "History catch-up broadcast: glucose=$lastHistoryNewestGlucose offset=$lastHistoryNewestOffset"
            )
        }
        lastHistoryNewestGlucose = 0f
        lastHistoryNewestOffset = 0

        scheduleOptionalStreamingSync("post-history")
    }

    // =========================================================================
    // Phase Management
    // =========================================================================

    private fun setPhase(newPhase: Phase) {
        if (phase == newPhase) return
        phase = newPhase
        phaseStartedAtMs = System.currentTimeMillis()
        when (newPhase) {
            Phase.GATT_CONNECTING -> {
                val connectAttemptTimeoutMs = reconnect.currentConnectAttemptTimeoutMs()
                handler.removeCallbacks(connectAttemptWatchdog)
                handler.postDelayed(connectAttemptWatchdog, connectAttemptTimeoutMs)
                handler.removeCallbacks(setupProgressWatchdog)
            }
            Phase.DISCOVERING_SERVICES,
            Phase.CCCD_CHAIN,
            -> {
                handler.removeCallbacks(connectAttemptWatchdog)
                scheduleSetupProgressWatchdog()
            }
            else -> {
                handler.removeCallbacks(connectAttemptWatchdog)
                handler.removeCallbacks(setupProgressWatchdog)
            }
        }
        Log.i(TAG, "Phase: $newPhase")
        onPhaseChange?.invoke(newPhase)
    }

    private fun applyWearProfileFromModel(modelName: String) {
        // DIS reads arrive on the binder thread. 0x10 is handled on the handler thread.
        // Publishing here inline can snapshot the catalog life and write it after the
        // sensor byte has already stored a longer one.
        runOnHandler {
            val resolved = reportedWearDaysOrNull()
            if (resolved == null) {
                Log.i(TAG, "Wear profile: model=$modelName; wear days unknown")
                return@runOnHandler
            }
            Log.i(
                TAG,
                "Wear profile: model=$modelName days=$resolved sensorReported=$sensorReportedWearDays raw=$_wearDays"
            )
            persistResolvedWearDays("model=$modelName")
        }
    }

    // =========================================================================
    // AiDexDriver Interface Implementation
    // =========================================================================

    fun getLastDefaultParamDiagnosticsSummary(): String? = lastDefaultParamDiagnostics?.summaryLine()

    override fun getDetailedBleStatus(): String {
        val now = System.currentTimeMillis()

        fun connectedWarmupStatus(connectionPart: String): String? {
            return AiDexRuntimePolicy.connectedWarmupStatus(
                connectionPart = connectionPart,
                anchorMs = effectiveWarmupAnchorMs(),
                nowMs = now,
                lastGlucoseTimeMs = lastGlucoseTimeMs,
                warmupDurationMs = WARMUP_DURATION_MS,
                firstValidReadingWaitMaxMs = FIRST_VALID_READING_WAIT_MAX_MS,
                firstValidReadingWaitActive = firstValidReadingAnchorMs > 0L,
            )
        }

        // Transient status (calibration or reset outcome) takes priority over every phase, not
        // just STREAMING: abandonPendingReset produces its message with the link already down and
        // the phase back at IDLE, and the reconnect it schedules needs POST_RESET_RECONNECT_DELAY_MS
        // plus discovery, CCCD and key exchange to reach STREAMING — read only there, the outcome of
        // an actuator command the user pressed for expired unseen. The deadline is still what bounds
        // it: the clear runnable dies with every other handler message on the next disconnect, and a
        // reset or calibration outcome is regularly followed by one. Broadcast-only mode keeps its
        // own status for the setup and STREAMING phases, exactly as the STREAMING branch did before
        // this moved.
        //
        // Who actually reads this, so the priority above is not taken for a promise that the user
        // sees the outcome: getDetailedBleStatus() is called from one place only — AiDexDriver's
        // getManagedUiSnapshot(), which puts it in detailedStatus and subtitleStatus — and that
        // snapshot is built on demand by open-UI surfaces: the Sensors screen (SensorCard), the
        // dashboard status line (resolveDashboardSensorStatus) and the wear sensor screen. Two
        // things push it to the two mobile ones: showTransientStatus sets AiDexDriver.deviceListDirty,
        // which the Sensors screen's poll consumes on its next tick, and UiRefreshBus StatusOnly runs
        // DashboardViewModel.refreshSensorSnapshot(), which rebuilds the dashboard status line from
        // this snapshot. Neither reaches the watch: the dirty flag has one consumer and it is
        // mobile-only, and StatusOnly does not bump UiRefreshBus.revision, which is what the wear
        // screen collects — so the watch shows the message only if its own 60s tick happens to fall
        // inside the window. The notification and AOD lines read constatstatusstr, which this path
        // never writes, and StatusOnly does not redraw them either — only a data refresh does. With
        // no surface refreshing inside the window the message expires unread — the same best
        // effort the postResetActivationDebt KDoc above already admits to.
        // Broadcast-only keeps its own status everywhere except IDLE: softDisconnect() parks the
        // driver there, that is the state resetSensor() refuses in, and the gate would otherwise
        // swallow the refusal it just posted. The setup phases and the STREAMING branch are
        // unchanged.
        if (
            (!reconnect.isBroadcastOnlyMode || phase == Phase.IDLE) &&
            SystemClock.elapsedRealtime() < transientStatusUntilMs
        ) {
            transientStatusMessage?.let { return it }
        }

        return when (phase) {
            Phase.IDLE -> {
                val bondHoldStatus = postResetBondHold?.status
                if (reconnect.isBroadcastOnlyMode && bondHoldStatus != null) {
                    bondHoldStatus
                } else if (reconnect.isBroadcastOnlyMode) {
                    if (broadcastScanActive) "Scanning for broadcasts..."
                    else if (lastBroadcastTime > 0 && (now - lastBroadcastTime) < 5 * 60_000L)
                        "Broadcast Mode — Receiving"
                    else "Broadcast Mode"
                }
                else if (isUnpaired) "Unpaired — tap Pair to reconnect"
                else if (stop) "Paused"
                else constatstatusstr ?: "Disconnected"
            }
            Phase.GATT_CONNECTING -> "Connecting..."
            Phase.DISCOVERING_SERVICES -> {
                val bondState = mBluetoothGatt?.device?.bondState
                    ?: android.bluetooth.BluetoothDevice.BOND_NONE
                when (bondState) {
                    android.bluetooth.BluetoothDevice.BOND_BONDING -> "Bonding..."
                    android.bluetooth.BluetoothDevice.BOND_NONE -> "Pairing..."
                    else -> "Discovering services..."
                }
            }
            Phase.CCCD_CHAIN -> {
                val bondState = mBluetoothGatt?.device?.bondState
                    ?: android.bluetooth.BluetoothDevice.BOND_NONE
                if (cccdChainComplete && keyExchangePendingBond && bondState == android.bluetooth.BluetoothDevice.BOND_BONDING) {
                    "Bonding..."
                } else {
                    "Configuring notifications..."
                }
            }
            Phase.KEY_EXCHANGE -> "Key exchange..."
            Phase.STREAMING -> {
                if (reconnect.isBroadcastOnlyMode) return "Broadcast Mode"

                // History download in progress — show phase and progress
                if (historyDownloading) {
                    val toDownload = historyNewestOffset - historyDownloadStartIndex
                    return when (historyPhase) {
                        HistoryPhase.DOWNLOADING_CALIBRATED -> {
                            val cached = calibratedGlucoseCache.size
                            if (toDownload > 0 && cached > 0)
                                "Fetching history... $cached/$toDownload"
                            else
                                "Fetching history..."
                        }
                        HistoryPhase.DOWNLOADING_RAW -> {
                            if (toDownload > 0 && historyStoredCount > 0)
                                "Storing history... $historyStoredCount/$toDownload"
                            else
                                "Storing history..."
                        }
                        HistoryPhase.IDLE -> "Fetching history..."
                    }
                }

                val connectionPart = if (noDirectLiveBroadcastFallbackMode) {
                    CONNECTED_BROADCAST_FALLBACK_STATUS
                } else {
                    "Connected"
                }
                connectedWarmupStatus(connectionPart)?.let { return it }

                // Normal connected state
                connectionPart
            }
        }
    }

    override val isPaused: Boolean get() = _isPaused || stop

    override val broadcastOnlyConnection: Boolean get() = reconnect.isBroadcastOnlyMode

    override fun isVendorPaired(): Boolean = persistedPairKey != null

    override fun isVendorConnected(): Boolean = phase == Phase.STREAMING && mBluetoothGatt != null

    override fun hasCompletedHandshake(): Boolean = handshakeCompleted

    override fun getCalibrationRecords(): List<SharedCalibrationRecord> =
        _calibrationRecords.sortedByDescending { it.index }

    override fun getBatteryMillivolts(): Int = _batteryMillivolts

    override fun isSensorExpired(): Boolean = _sensorExpired

    override fun getSensorRemainingHours(): Int {
        if (sensorstartmsec <= 0L) return -1
        val elapsedMs = System.currentTimeMillis() - sensorstartmsec
        val wearDays = reportedWearDaysOrNull() ?: return -1
        val totalMs = wearDays.toLong() * 24 * 60 * 60 * 1000
        val remainingMs = totalMs - elapsedMs
        return if (remainingMs <= 0) 0 else (remainingMs / (60 * 60 * 1000)).toInt()
    }

    override fun getSensorReportedWearDays(): Int = reportedWearDaysOrNull() ?: -1

    override fun shouldUseNativeOfficialEndFallback(): Boolean = false

    override fun getSensorAgeHours(): Int {
        if (sensorstartmsec <= 0L) return -1
        val elapsedMs = System.currentTimeMillis() - sensorstartmsec
        return (elapsedMs / (60 * 60 * 1000)).toInt()
    }

    override var vendorFirmwareVersion: String
        get() = _firmwareVersion
        set(value) { _firmwareVersion = value }

    override var vendorHardwareVersion: String
        get() = _hardwareVersion
        set(value) { _hardwareVersion = value }

    override var vendorModelName: String
        get() = _modelName
        set(value) { _modelName = value }

    override fun forgetVendor() = forgetVendor(unbond = true)

    override fun forgetVendor(unbond: Boolean) {
        Log.i(TAG, "forgetVendor: tearing down native driver for $SerialNumber unbond=$unbond")
        // First: a DISCONNECTED already running on the binder thread can post
        // `stop = false; connectDevice(0)` after the handler wipe below, and this flag is what
        // refuses that reconnect.
        forgotten = true
        stop = true
        pendingInvalidSetupRecovery = PendingInvalidSetupRecovery.NONE
        pendingStaleConnectionRecovery = false
        connectAttemptInFlight = false
        postResetBondHold = null
        cancelBroadcastScan()
        teardownOnHandler("forget-vendor")
        keyExchange.reset()
        explicitPairRequested = false
        // Local removal is not proof of sensor-side UNPAIR. Keep the portable credential so
        // re-adding the sensor cannot force another F001 exchange.
        // Notify C++ layer that this sensor is being removed — prevents zombie resurrection
        try { finishSensor() } catch (_: Throwable) {}
        // Capture device reference BEFORE nullifying gatt
        val device = mBluetoothGatt?.device
        // Disconnect and close GATT
        try { mBluetoothGatt?.disconnect() } catch (_: Throwable) {}
        try { mBluetoothGatt?.close() } catch (_: Throwable) {}
        mBluetoothGatt = null
        if (unbond) {
            try {
                if (device?.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                    val removeBond = device.javaClass.getMethod("removeBond")
                    removeBond.invoke(device)
                    setBondValidatedByStreaming(false, "forget-vendor")
                    Log.i(TAG, "forgetVendor: BLE bond removed")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "forgetVendor: removeBond failed: ${t.message}")
            }
        }
        setPhase(Phase.IDLE)
        handlerThread.quitSafely()
        AiDexDriver.deviceListDirty = true
    }

    override fun onTerminalFree() {
        forgotten = true
        postResetBondHold = null
        cancelBroadcastScan()
        teardownOnHandler("terminal-free")
    }

    override fun close() {
        super.close()
        if (forgotten) {
            handlerThread.quitSafely()
        }
    }

    override fun softDisconnect() {
        Log.i(TAG, "softDisconnect: pausing sensor $SerialNumber")
        consecutiveSetupDisconnects = 0
        pendingInvalidSetupRecovery = PendingInvalidSetupRecovery.NONE
        pendingStaleConnectionRecovery = false
        connectAttemptInFlight = false
        _isPaused = true  // Block external reconnection triggers (LossOfSensorAlarm, reconnectall)
        stop = true  // Prevent auto-reconnect from disconnect handler
        noDirectLiveBroadcastFallbackMode = false
        // Pause, broadcast-only, re-pair and a confirmed unpair all come through here: none of them
        // may be connected by a later BOND_NONE.
        postResetBondHold = null
        cancelBroadcastScan()  // Stop active BLE scanner and cancel scheduled scans
        teardownOnHandler("soft-disconnect")
        close()  // SuperGattCallback.close() does disconnect + close + nulls mBluetoothGatt
        setPhase(Phase.IDLE)
        // NOTE: callers set constatstatusstr after calling softDisconnect()
        // (e.g., "Paused", "Unpaired", "Broadcast Only", "Pairing cancelled")
    }

    override fun onBluetoothAdapterUnavailable() {
        pendingStaleConnectionRecovery = false
        connectAttemptInFlight = false
        connectTime = 0L
        resetConnectionRuntimeState(reason = "bluetooth-adapter-off", resetInvalidSetupCounter = false)
        setPhase(Phase.IDLE)
        constatstatusstr = "Bluetooth off"
        UiRefreshBus.requestStatusRefresh()
    }

    override fun manualReconnectNow() {
        Log.i(TAG, "manualReconnectNow: forcing reconnect for $SerialNumber")
        consecutiveSetupDisconnects = 0
        // The user asking for a reconnect is the last line of defence, so it must not be refused
        // by state the previous session left behind: connectDevice() bails unless the phase is
        // IDLE, and a stale failure count would send the very next failure straight to broadcast.
        setPhase(Phase.IDLE)
        consecutiveDiscoveryFailures = 0
        // Same for the two counts that also end in broadcast-only: both carry across connections.
        consecutiveConnectFailures = 0
        consecutiveInvalidSetupRecoveries = 0
        // And the key-exchange failure count, for the same reason but with a longer memory: it is
        // persisted, so a count left from an earlier process would let the very next failure
        // declare the stored credential exhausted - and, on a bonded link, replace the key the
        // user just asked to retry.
        keyExchangeFailures = 0
        noDirectLiveBroadcastFallbackMode = false
        cancelBroadcastScan()
        _isPaused = false   // Clear paused flag — user explicitly wants reconnection
        isUnpaired = false // Clear unpaired flag — user explicitly wants reconnection
        // Must be cleared together with isUnpaired. A 0xF2 whose write was dropped (no link, the
        // CLEAR_STORAGE quiet window, a purged queue) leaves this latched with no timeout, and
        // isUnpaired is the only thing keeping the driver off the air until then. Left set, the
        // first ordinary disconnect of the session the user just asked for takes the deferred
        // branch and removes the Android bond nobody asked to remove.
        pendingUnpairDisconnect = false
        // The user's retry is the way out of the post-reset bond hold; it does not remove the bond
        // a second time, and the settle step must not issue a second connect behind it.
        postResetBondHold = null
        handler.removeCallbacks(postResetSettle)
        reconnect.reset()  // Clears isBroadcastOnlyMode + authFailureCount
        stop = false
        connectDevice(0L)
        AiDexDriver.deviceListDirty = true
    }

    override fun setBroadcastOnlyConnection(enabled: Boolean) {
        Log.i(TAG, "setBroadcastOnlyConnection($enabled) for $SerialNumber")
        consecutiveSetupDisconnects = 0
        reconnect.isBroadcastOnlyMode = enabled
        // Either direction is the user's own choice, not an automatic fallback, so neither keeps a
        // timed return: enabled=true must not spring back out of a mode the user picked, and
        // enabled=false hands the link straight to GATT with no fallback in progress. Cleared for
        // both branches because the stamp does not follow isBroadcastOnlyMode: left standing in
        // the else branch it stays live through manualReconnectNow() and is inherited by any later
        // entry that sets no stamp of its own. Only handleDeleteBondResponse stamps nothing today,
        // and its isUnpaired gate in maybeLeaveBroadcastOnlyFallback already blocks the timed
        // return — this clear is the second lock on that door, not the only one.
        broadcastFallbackEnteredAtElapsed = 0L
        if (enabled) {
            // Disconnect GATT, start broadcast scanning
            softDisconnect()
            constatstatusstr = "Broadcast Only"
            stop = false
            UiRefreshBus.requestStatusRefresh()
            // Start broadcast scan loop
            handler.post { startBroadcastScan("broadcast-mode-enabled") }
        } else {
            // Stop broadcast scanning, resume active GATT connection
            cancelBroadcastScan()
            manualReconnectNow()
        }
    }

    override fun supportsResetAction(): Boolean =
        AiDexRuntimePolicy.supportsLifecycleReset(_firmwareVersion)

    override fun resetSensor(): Boolean {
        // supportsResetAction() keeps the button up while the version is unknown; the send itself
        // waits for a version that decides. 0x10/0x21 give only major.minor, which cannot tell
        // 1.8.0 from 1.8.3. The full DIS revision comes with the first streaming metadata pass only
        // while the version is blank; once 0x10 has set "1.8" nothing re-reads it, so the UNKNOWN
        // arm asks for it.
        when (AiDexRuntimePolicy.lifecycleReset(_firmwareVersion)) {
            AiDexRuntimePolicy.LifecycleReset.REFUSED -> {
                Log.w(TAG, "resetSensor: firmware $_firmwareVersion refuses CLEAR_STORAGE — not sending it")
                showTransientStatus("Reset is not supported on firmware $_firmwareVersion", POST_RESET_OUTCOME_STATUS_MS)
                return false
            }
            AiDexRuntimePolicy.LifecycleReset.UNKNOWN -> {
                Log.w(TAG, "resetSensor: firmware version '${_firmwareVersion}' does not decide — not sending CLEAR_STORAGE")
                if (pendingUnpairDisconnect) {
                    // What the admission below would answer.
                    showTransientStatus("Unpair in progress — reset refused", POST_RESET_OUTCOME_STATUS_MS)
                } else if (phase == Phase.STREAMING && mBluetoothGatt != null && servicesReady) {
                    // Ask for the full revision so that the next press can decide. Only while
                    // streaming, where the metadata pass reads DIS too: during CCCD_CHAIN and
                    // KEY_EXCHANGE the setup writes bypass the GATT queue, and a read on air then
                    // makes them fail.
                    enqueueGattOp(GattOp.Read(CHAR_SOFTWARE_REV, SERVICE_DIS))
                    showTransientStatus(
                        "Reading the sensor's firmware version — press Reset again in a few seconds",
                        POST_RESET_OUTCOME_STATUS_MS,
                    )
                } else if (mBluetoothGatt != null && servicesReady) {
                    showTransientStatus(
                        "Reset unavailable while the sensor is still connecting — press again once it shows Connected",
                        POST_RESET_OUTCOME_STATUS_MS,
                    )
                } else {
                    // The answer the admission below would give, on both of its channels.
                    showTransientStatus("Reset failed — not connected", POST_RESET_OUTCOME_STATUS_MS)
                    constatstatusstr = "Reset failed — not connected"
                    UiRefreshBus.requestStatusRefresh()
                }
                return false
            }
            AiDexRuntimePolicy.LifecycleReset.ALLOWED -> Unit
        }
        Log.i(TAG, "resetSensor: CLEAR_STORAGE (0xF3) only for $SerialNumber")
        // CLEAR_STORAGE is the complete lifecycle-reset command. Do not immediately
        // chain RESET (0xF0), which can interrupt the clear and leave the old history
        // range exposed. Built before the admission: encryption only, nothing is sent.
        val cmd = commandBuilder.clearStorage()
        val resetRequestedAt = System.currentTimeMillis()
        // Admission and latch are one step, under both latches' locks (unpairLock outer, as in
        // unpairSensor; nothing takes them in the other order): a double tap on the IO dispatcher,
        // or a Reset racing an Unpair, admits one of them and never two irreversible commands.
        //
        // In memory only, and nothing else: the activation debt, the history barrier, the cursor
        // zero and markSensorReset wait for the sensor's status 0x01
        // (commitConfirmedClearStorageIfOwed, on the handler). Persisted here, a process that died
        // before the answer came back left a debt the next launch turned into an unrequested 0x20.
        // The latch still goes up now, so a second press is refused and the quiet window keeps
        // other commands off the link; while the window is up enqueueGattOp drops a 0x20 raised in
        // the gap.
        val admission = synchronized(unpairLock) {
            synchronized(resetClaimLock) {
                val decision = AiDexRuntimePolicy.decideResetAdmission(
                    resetInFlight = pendingResetReconnect || clearStorageQuietWindowActive,
                    unpairInFlight = pendingUnpairDisconnect,
                    linkUsable = mBluetoothGatt != null && servicesReady,
                )
                if (decision == AiDexRuntimePolicy.ResetAdmission.ALLOW && cmd != null) {
                    handler.removeCallbacks(clearStorageQuietWindowReconnect)
                    handler.removeCallbacks(postResetDisconnectFallback)
                    postResetClearStorageWriteAtMs = 0L
                    postResetClearStorageAckAtMs = 0L
                    postResetDisconnectRequestedAtMs = 0L
                    postResetDroppedGattOps = 0
                    clearStorageConfirmOwed = false
                    resetAttemptRequestedAtMs = resetRequestedAt
                    clearStorageQuietWindowActive = true
                    pendingResetReconnect = true
                }
                decision
            }
        }
        when (admission) {
            AiDexRuntimePolicy.ResetAdmission.REFUSE_RESET_IN_FLIGHT -> {
                // A second press inside the quiet window would zero the ACK stamp — the only
                // record that the FIRST 0xF3 was confirmed — and put a second CLEAR_STORAGE on the
                // air over an erase still running, which is the blind actuator re-send the policy
                // forbids. Nothing latches forever: the STATE_CONNECTED branch settles the latch on
                // any later connection.
                Log.w(TAG, "resetSensor: a reset is already in progress — refusing re-entry")
                showTransientStatus("Reset already in progress", POST_RESET_OUTCOME_STATUS_MS)
                return false
            }
            AiDexRuntimePolicy.ResetAdmission.REFUSE_UNPAIR_IN_FLIGHT -> {
                // The mirror of the guard in unpairSensor(). An unpair the user already asked for
                // owns the next disconnect, and enqueueExclusiveClearStorage would throw the queued
                // 0xF2 away without failing it.
                Log.w(TAG, "resetSensor: an unpair is already in progress — refusing")
                showTransientStatus("Unpair in progress — reset refused", POST_RESET_OUTCOME_STATUS_MS)
                return false
            }
            AiDexRuntimePolicy.ResetAdmission.REFUSE_NOT_CONNECTED -> {
                // The session key outlives a dropped link, so clearStorage() still builds while
                // disconnected or paused, and the UI reset button has no connected check. Without a
                // link the write dies in drainGattQueue. mBluetoothGatt alone is not "the link is
                // usable": connectGatt() assigns it synchronously, so it is non-null for the whole
                // GATT_CONNECTING phase, where drainGattQueue cannot find SERVICE_F000.
                // servicesReady is the property the dispatch actually needs.
                Log.w(TAG, "resetSensor: no usable GATT link (gatt=${mBluetoothGatt != null} " +
                    "servicesReady=$servicesReady) — refusing (CLEAR_STORAGE would never be sent)")
                // Both channels, because neither covers every state this branch refuses in.
                // getDetailedBleStatus() — what the Sensors card the Reset button lives on renders —
                // reads constatstatusstr only in Phase.IDLE with the sensor neither unpaired nor
                // paused, while the transient outranks every IDLE status, broadcast-only included.
                // constatstatusstr stays as what outlives the transient's deadline and what the
                // notification and AOD lines read — best effort only: the next accepted broadcast
                // or connection overwrites it.
                showTransientStatus("Reset failed — not connected", POST_RESET_OUTCOME_STATUS_MS)
                constatstatusstr = "Reset failed — not connected"
                UiRefreshBus.requestStatusRefresh()
                return false
            }
            AiDexRuntimePolicy.ResetAdmission.ALLOW -> Unit
        }
        if (cmd == null) {
            Log.e(TAG, "resetSensor: session key not available")
            // Reachable only with a live GATT, i.e. in the setup phases — by STREAMING the
            // session key exists — and getDetailedBleStatus() answers those with a hardcoded phase
            // string, so the transient is the only channel this refusal can be seen through.
            showTransientStatus("Reset failed — handshake not complete", POST_RESET_OUTCOME_STATUS_MS)
            constatstatusstr = "Reset failed — handshake not complete"
            UiRefreshBus.requestStatusRefresh()
            return false
        }

        // The window is latched above, before the write is even queued, and enqueueGattOp drops
        // every GATT operation while it is up. Arm its timer now so the paths that can swallow the
        // write without arming it (no GATT, characteristic not found, write retries exhausted)
        // still have an exit; a successful dispatch and the ACK each re-arm it from their own time.
        scheduleClearStorageQuietWindow("reset-requested", resetRequestedAt)
        resetDiag(
            stage = "request",
            details = "historyDownloading=$historyDownloading historyPhase=$historyPhase rawNext=$historyRawNextIndex " +
                "briefNext=$historyBriefNextIndex newest=$historyNewestOffset sessionStartMs=$sensorstartmsec " +
                "lastOffset=$lastOffsetMinutes",
            nowMs = resetRequestedAt,
        )

        enqueueExclusiveClearStorage(cmd)
        return true
    }

    override fun startNewSensor(): Boolean {
        Log.i(TAG, "startNewSensor: activating sensor $SerialNumber")
        if (pendingResetReconnect || clearStorageQuietWindowActive) {
            Log.w(TAG, "startNewSensor: a reset is already in progress — refusing")
            showTransientStatus("Reset already in progress", POST_RESET_OUTCOME_STATUS_MS)
            return false
        }
        if (pendingUnpairDisconnect) {
            Log.w(TAG, "startNewSensor: an unpair is already in progress — refusing")
            showTransientStatus("Unpair in progress — start refused", POST_RESET_OUTCOME_STATUS_MS)
            return false
        }
        val cal = Calendar.getInstance(TimeZone.getDefault())
        val timeZone = aiDexActivationTimeZone(cal, TimeZone.getDefault())
        val cmd = commandBuilder.setNewSensor(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH),
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            second = cal.get(Calendar.SECOND),
            tzQuarters = timeZone.tzQuarters,
            dstQuarters = timeZone.dstQuarters,
        )
        val maySend = AiDexRuntimePolicy.maySendSetNewSensor(
            gattReady = mBluetoothGatt != null,
            servicesReady = servicesReady,
            sessionKeyPresent = cmd != null,
            resetInFlight = pendingResetReconnect || clearStorageQuietWindowActive,
            unpairInFlight = pendingUnpairDisconnect,
        )
        if (!maySend || cmd == null) {
            val reason = when {
                mBluetoothGatt == null || !servicesReady -> "Start failed — not connected"
                cmd == null -> "Start failed — handshake not complete"
                else -> "Start failed — not ready"
            }
            Log.w(
                TAG,
                "startNewSensor: refusing (gatt=${mBluetoothGatt != null} servicesReady=$servicesReady " +
                    "key=${cmd != null}) — SET_NEW_SENSOR would never be sent"
            )
            showTransientStatus(reason, POST_RESET_OUTCOME_STATUS_MS)
            return false
        }
        val queued = try {
            runOnHandlerAndWait {
                queueSetNewSensorWrite(cmd)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startNewSensor: handler queue failed: ${t.message}")
            false
        }
        if (!queued) {
            showTransientStatus("Start failed — not connected", POST_RESET_OUTCOME_STATUS_MS)
        }
        return queued
    }

    private fun hasSetNewSensorInFlight(): Boolean {
        val current = currentGattOp
        if (current is GattOp.Write && current.opcode == AiDexOpcodes.SET_NEW_SENSOR) return true
        return gattQueue.any { op -> op is GattOp.Write && op.opcode == AiDexOpcodes.SET_NEW_SENSOR }
    }

    private fun queueSetNewSensorWrite(cmd: ByteArray): Boolean {
        if (setNewSensorWriteQueuedThisConnection || hasSetNewSensorInFlight()) {
            Log.i(TAG, "startNewSensor: 0x20 already queued this connection — not sending a second")
            return true
        }
        val stillMaySend = AiDexRuntimePolicy.maySendSetNewSensor(
            gattReady = mBluetoothGatt != null,
            servicesReady = servicesReady,
            sessionKeyPresent = true,
            resetInFlight = pendingResetReconnect || clearStorageQuietWindowActive,
            unpairInFlight = pendingUnpairDisconnect,
        )
        if (!stillMaySend) {
            Log.w(TAG, "startNewSensor: link went away before 0x20 could be queued")
            return false
        }
        historyDownloading = false
        historyPhase = HistoryPhase.IDLE
        historyPageTimedOut = false
        pendingInitialHistoryRequest = false
        handler.removeCallbacks(historyPageWatchdog)
        handler.removeCallbacks(delayedInitialHistoryRequest)
        handler.removeCallbacks(delayedRawHistoryPage)
        handler.removeCallbacks(delayedBriefHistoryPage)
        dropQueuedHistoryQueries()
        bumpHistoryResponseGeneration()
        setNewSensorNackSeen = false
        setNewSensorUnconfirmedAckAtElapsed = 0L
        setNewSensorWriteDispatchedThisAttempt = false
        setNewSensorSnapshotValid = false
        setNewSensorAttemptInProgress = true

        gattQueue.add(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.SET_NEW_SENSOR))
        if (mBluetoothGatt == null) {
            gattQueue.clear()
            clearUncommittedSetNewSensorAttempt("gatt-gone-at-enqueue")
            Log.w(TAG, "startNewSensor: GATT gone while queueing 0x20 — not committing")
            return false
        }

        setNewSensorWriteQueuedThisConnection = true
        autoActivationAttemptedThisConnection = true
        Log.i(TAG, "startNewSensor: 0x20 queued (not yet committed)")
        if (!gattOpActive) {
            drainGattQueue()
        }
        return true
    }

    /**
     * Run [block] on the AiDex looper and return its result. On a timeout the caller cancels the
     * block through its own [AiDexHandlerTicket] and throws; if the block has already started —
     * it may have queued the 0x20 — the caller waits for it and reports what it really did, not
     * "Start failed" for a write that goes on air. A second 0x20 is refused by
     * queueSetNewSensorWrite itself, so overlapping calls need no shared counter.
     */
    private fun <T> runOnHandlerAndWait(block: () -> T): T {
        if (Thread.currentThread() === handlerThread) return block()
        val ticket = AiDexHandlerTicket()
        val box = arrayOfNulls<Any>(1)
        val error = arrayOfNulls<Throwable>(1)
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            if (!ticket.tryStart()) return@post
            try {
                box[0] = block()
            } catch (t: Throwable) {
                error[0] = t
            } finally {
                done.countDown()
            }
        }
        if (!done.await(HANDLER_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (ticket.tryCancel()) throw IllegalStateException("startNewSensor handler timeout")
            if (!done.await(HANDLER_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("startNewSensor handler still running")
            }
        }
        error[0]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T
    }

    override fun calibrateSensor(glucoseMgDl: Int): Boolean {
        Log.i(TAG, "calibrateSensor($glucoseMgDl mg/dL) for $SerialNumber")

        // Guard: a streaming link, not only a session key. The key outlives a dropped link, so
        // without the link check a 0x25 is built, dropped in drainGattQueue with no GATT, and
        // "Calibrating..." expires like a success.
        if (!keyExchange.isComplete || !isVendorConnected()) {
            Log.e(
                TAG,
                "calibrateSensor: no streaming link (keyReady=${keyExchange.isComplete} phase=$phase " +
                    "gatt=${mBluetoothGatt != null})"
            )
            showTransientStatus("Calibration failed — not connected", POST_RESET_OUTCOME_STATUS_MS)
            return false
        }

        // Guard: no reset in flight. The quiet window drops every operation in enqueueGattOp, so a
        // 0x25 sent now never leaves the phone while "Calibrating..." reads as if it did.
        if (pendingResetReconnect || clearStorageQuietWindowActive) {
            Log.e(TAG, "calibrateSensor: a sensor reset is in progress — refusing")
            showTransientStatus("Calibration refused — reset in progress", POST_RESET_OUTCOME_STATUS_MS)
            return false
        }

        // Guard: must not be in warmup
        val warmupAnchor = effectiveWarmupAnchorMs()
        if (warmupAnchor > 0L) {
            val warmupEndMs = warmupAnchor + WARMUP_DURATION_MS
            val now = System.currentTimeMillis()
            if (now < warmupEndMs) {
                val remainingSec = ((warmupEndMs - now) / 1000).toInt()
                Log.e(TAG, "calibrateSensor: sensor is warming up (${remainingSec}s remaining)")
                showTransientStatus(
                    "Cannot calibrate — warming up (${remainingSec}s left)",
                    POST_RESET_OUTCOME_STATUS_MS,
                )
                return false
            }
        }

        // Guard: must have current offset
        if (lastOffsetMinutes <= 0) {
            Log.e(TAG, "calibrateSensor: no offset available yet")
            // resetConnectionRuntimeState() zeroes lastOffsetMinutes and the first history range
            // is 65s away, so this window opens on every reconnect — the most reachable of the
            // four refusals and, until now, the most silent.
            showTransientStatus("Cannot calibrate yet — waiting for sensor data", POST_RESET_OUTCOME_STATUS_MS)
            return false
        }

        // Guard: glucose must be in valid range
        if (glucoseMgDl < 30 || glucoseMgDl > 500) {
            Log.e(TAG, "calibrateSensor: glucose $glucoseMgDl out of range (30-500)")
            showTransientStatus("Calibration refused — $glucoseMgDl mg/dL out of range", POST_RESET_OUTCOME_STATUS_MS)
            return false
        }

        sendCalibration(lastOffsetMinutes, glucoseMgDl)
        showTransientStatus("Calibrating...", GATT_OP_TIMEOUT_MS + 5_000L)  // until the ACK or the write watchdog's verdict
        return true
    }

    override fun suppressPostUnpairBroadcastScan() {
        postUnpairBroadcastScanSuppressed = true
    }

    override fun releasePostUnpairBroadcastScanSuppression() {
        // Under the same lock as the ACK's mode write. Clearing the flag here either happens
        // before that read, so the ACK starts the scan, or after it, so this side sees
        // broadcast-only and starts the scan the ACK skipped. Release runs off the handler,
        // and the field is not volatile.
        val startNow = synchronized(postUnpairScanLock) {
            if (!postUnpairBroadcastScanSuppressed) return@synchronized false
            postUnpairBroadcastScanSuppressed = false
            !forgotten && reconnect.isBroadcastOnlyMode
        }
        if (!startNow) return
        handler.post {
            if (forgotten || postUnpairBroadcastScanSuppressed || !broadcastOnlyConnection) return@post
            startBroadcastScan("delete-unbind-cancelled")
        }
    }

    /**
     * A refused unpair did not send DELETE_BOND, so no ACK will start the scan. Drop the
     * suppression. Once a confirmed unpair has already entered broadcast-only, the ACK skipped
     * its scan and [releasePostUnpairBroadcastScanSuppression] still owes it: clearing the flag
     * here would make that release return without starting.
     */
    private fun clearPostUnpairSuppressionUnlessBroadcastOnly() {
        synchronized(postUnpairScanLock) {
            if (!reconnect.isBroadcastOnlyMode) {
                postUnpairBroadcastScanSuppressed = false
            }
        }
    }

    override fun unpairSensor(): Boolean {
        Log.i(TAG, "unpairSensor: sending deleteBond (0xF2) for $SerialNumber")
        consecutiveSetupDisconnects = 0
        // 0xF2 has to be able to reach the sensor before an unpair is announced, and three ways it
        // silently does not: without a link the write dies in drainGattQueue's `gatt == null`
        // branch; a link still connecting has a handle from connectGatt() at once but no service
        // until discovery, so drainGattQueue skips the write (the same trap resetSensor() refuses);
        // and while the CLEAR_STORAGE quiet window is up enqueueGattOp drops every operation and
        // nothing re-queues it. In all three pendingUnpairDisconnect would latch on a command the
        // sensor was never told about. Pushing 0xF2 through the quiet window is not the
        // alternative — the window exists to keep other commands off the link while the erase is
        // in flight — so all three refuse instead.
        val cmd = commandBuilder.deleteBond()
        // Under the lock: the view model calls this from IO threads, and a double tap must not put
        // a second 0xF2 on the air.
        val admission = synchronized(unpairLock) {
            val decision = AiDexRuntimePolicy.decideUnpairAdmission(
                unpairInFlight = pendingUnpairDisconnect,
                resetInFlight = pendingResetReconnect || clearStorageQuietWindowActive,
                linkUsable = mBluetoothGatt != null && servicesReady,
                quietWindowActive = clearStorageQuietWindowActive,
                sessionKeyPresent = cmd != null,
            )
            if (decision == AiDexRuntimePolicy.UnpairAdmission.ALLOW) {
                // Set BEFORE sending — the response handler (or disconnect handler) settles it
                // after the command is delivered.
                isUnpaired = true  // Block reconnection until the sensor confirms, refuses, or the user re-pairs
                pendingUnpairDisconnect = true
            }
            decision
        }
        when (admission) {
            // Already on its way: true, so disconnectAiDexSensor keeps waiting for the outcome.
            AiDexRuntimePolicy.UnpairAdmission.ALREADY_IN_PROGRESS -> {
                Log.w(TAG, "unpairSensor: an unpair is already in progress — not sending a second DELETE_BOND")
                return true
            }
            AiDexRuntimePolicy.UnpairAdmission.REFUSE_RESET_IN_FLIGHT -> {
                clearPostUnpairSuppressionUnlessBroadcastOnly()
                Log.w(TAG, "unpairSensor: a reset is in progress — refusing")
                showTransientStatus("Reset in progress — unpair refused", POST_RESET_OUTCOME_STATUS_MS)
                return false
            }
            AiDexRuntimePolicy.UnpairAdmission.REFUSE_NOT_READY -> {
                clearPostUnpairSuppressionUnlessBroadcastOnly()
                Log.e(TAG, "unpairSensor: no session key, no usable GATT link or CLEAR_STORAGE quiet window active — refusing unconfirmed local cleanup")
                constatstatusstr = "Connect before unpairing — key retained"
                UiRefreshBus.requestStatusRefresh()
                return false
            }
            AiDexRuntimePolicy.UnpairAdmission.ALLOW -> Unit
        }
        if (cmd == null) {
            clearPostUnpairSuppressionUnlessBroadcastOnly()
            return false
        }
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.DELETE_BOND))
        constatstatusstr = "Unpairing..."
        UiRefreshBus.requestStatusRefresh()
        return true
    }

    override fun forgetSavedPairKey() {
        Log.i(TAG, "forgetSavedPairKey: force-clearing stored PAIR credential for $SerialNumber")
        val cleared = AiDexPairKeyVault.clearStoredKey(Applic.app, SerialNumber)
        if (!cleared) Log.e(TAG, "forgetSavedPairKey: credential removal was not fully committed")
        persistedPairKey = null
        savedKeyExhausted = false
        keyExchangeFailures = 0
        pairKeyResetPending = false
        UiRefreshBus.requestStatusRefresh()
    }

    override fun rePairSensor() {
        Log.i(TAG, "rePairSensor: reconnecting without discarding PAIR credential for $SerialNumber")
        consecutiveSetupDisconnects = 0
        // Same reason manualReconnectNow() clears it: a stale count from the session the user is
        // abandoning sends the very first discovery failure of the new pairing straight to
        // broadcast-only. Neither softDisconnect() nor reconnect.reset() below touches it.
        consecutiveDiscoveryFailures = 0
        // Same for the two counts that also end in broadcast-only.
        consecutiveConnectFailures = 0
        consecutiveInvalidSetupRecoveries = 0
        // Same reason as manualReconnectNow(), and before the teardown rather than next to the
        // isUnpaired reset below: softDisconnect() can produce a disconnect callback, and that
        // callback must not take the deferred bond-removal branch on the way to a new pairing.
        pendingUnpairDisconnect = false
        keyExchange.reset()
        // A backup may have been restored since this manager loaded its key.
        AiDexPairKeyVault.load(Applic.app, SerialNumber)?.let { restored ->
            if (persistedPairKey?.contentEquals(restored) != true) {
                Log.i(TAG, "rePairSensor: picked up a restored PAIR credential from storage")
                persistedPairKey = restored
                savedKeyExhausted = false
            }
        }
        // The user asked for a pairing: give it a full set of retries, and let it replace a
        // saved key only if that key has already been proven dead.
        keyExchangeFailures = 0
        explicitPairRequested = true
        softDisconnect()
        constatstatusstr = "Re-pairing..."
        UiRefreshBus.requestStatusRefresh()
        handler.postDelayed({
            _isPaused = false   // Clear paused flag — user explicitly wants re-pair
            isUnpaired = false // Clear unpaired flag — user explicitly wants re-pair
            // A post-reset bond hold published around the pause above must not refuse this connect,
            // and its settle step must not issue a second one behind it.
            postResetBondHold = null
            handler.removeCallbacks(postResetSettle)
            reconnect.reset()  // Clear broadcast-only fallback/auth-failure state before pairing again
            stop = false
            connectDevice(500L)
        }, 1000L)
    }

    override fun sendMaintenanceCommand(opCode: Int): Boolean {
        Log.i(TAG, "sendMaintenanceCommand(0x${"%02X".format(opCode)}) for $SerialNumber")
        when (opCode) {
            AiDexOpcodes.GET_DEFAULT_PARAM -> {
                if (!keyExchange.isComplete) {
                    Log.e(TAG, "sendMaintenanceCommand: session key not available")
                    return false
                }
                beginManualDefaultParamProbe(
                    applyAfterProbe = false,
                    reason = "manual-maintenance-read",
                )
                return true
            }
            AiDexOpcodes.SET_DEFAULT_PARAM -> {
                if (!keyExchange.isComplete) {
                    Log.e(TAG, "sendMaintenanceCommand: session key not available")
                    return false
                }
                beginManualDefaultParamProbe(
                    applyAfterProbe = true,
                    reason = "manual-maintenance-apply",
                )
                return true
            }
        }

        // No generic arm: every state-changing opcode has its own entry with its own latch —
        // startNewSensor (0x20), resetSensor (0xF3), unpairSensor (0xF2), calibrateSensor (0x25) —
        // and a raw enqueue here would put one on the air past all of them.
        Log.w(TAG, "sendMaintenanceCommand: opcode 0x${"%02X".format(opCode)} has no maintenance path — refusing")
        return false
    }

    override val resetCompensationEnabled: Boolean get() = _resetCompensationEnabled

    override fun enableResetCompensation() {
        Log.i(TAG, "enableResetCompensation for $SerialNumber")
        _resetCompensationEnabled = true
    }

    override fun disableResetCompensation() {
        Log.i(TAG, "disableResetCompensation for $SerialNumber")
        _resetCompensationEnabled = false
    }

    override fun getCompensationStatusText(): String {
        return if (_resetCompensationEnabled) "Enabled (native driver)" else ""
    }

    override var viewMode: Int
        get() = _viewModeInternal
        set(value) {
            val normalized = ManagedSensorViewModeStore.sanitize(value)
            _viewModeInternal = normalized
            ManagedSensorViewModeStore.write(Applic.app, SerialNumber, normalized)
            applyViewModeToNative(normalized)
        }

    private fun restorePersistedViewMode(): Int {
        val nativeMode = if (dataptr != 0L) {
            runCatching { Natives.getViewMode(dataptr) }.getOrDefault(0)
        } else {
            0
        }
        return ManagedSensorViewModeStore.read(Applic.app, SerialNumber, nativeMode)
    }

    private fun applyViewModeToNative(mode: Int) {
        if (dataptr == 0L) return
        runCatching { Natives.setViewMode(dataptr, mode) }
            .onFailure { Log.w(TAG, "applyViewModeToNative failed: ${it.message}") }
    }

    // =========================================================================
    // Broadcast Scanning
    // =========================================================================

    /**
     * Runnable that starts a broadcast scan. Posted with delay for periodic scanning.
     */
    private val broadcastScanRunnable = Runnable { startBroadcastScan("scheduled") }

    /**
     * Runnable that stops a broadcast scan after the scan window expires.
     */
    private val broadcastScanStopRunnable = Runnable {
        stopBroadcastScan("timeout", found = false)
    }

    private fun hasRecentLiveData(now: Long = System.currentTimeMillis()): Boolean {
        // A stored reading may legitimately sit ahead of the wall clock by at most the acceptance
        // slack — resolveOffsetBackedTimestampMs caps it there. Further ahead is a backward clock
        // step, and answering "fresh" for the size of that step is what muzzles the assist scan
        // (broadcastAssistRunnable), the no-stream ladder and the external reconnect for a whole
        // hour on an hour-sized step.
        val liveAgeMs = now - lastGlucoseTimeMs
        val recentLive = lastGlucoseTimeMs > 0L &&
            liveAgeMs >= -AiDexHistoryPolicy.OFFSET_TIMESTAMP_FUTURE_SLACK_MS &&
            liveAgeMs < BROADCAST_FALLBACK_LIVE_TIMEOUT_MS
        return recentLive || hasRecentBroadcastData(now)
    }

    private fun broadcastScanAlarmPendingIntent(flags: Int): PendingIntent? {
        val intent = Intent(Applic.app, AiDexScanReceiver::class.java).apply {
            action = AiDexScanReceiver.ACTION_AIDEX_SCAN
            putExtra(AiDexScanReceiver.EXTRA_SERIAL, SerialNumber)
        }
        return PendingIntent.getBroadcast(
            Applic.app,
            SerialNumber.hashCode(),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * True only while THIS instance holds the alarm. The PendingIntent is matched by
     * SerialNumber.hashCode(), so a duplicate manager for the same sensor would otherwise cancel
     * the live one's wake alarm - the only thing that re-arms the scan in doze.
     */
    @Volatile
    private var broadcastScanAlarmArmed = false

    private fun scheduleBroadcastScanAlarm(delayMs: Long) {
        if (delayMs < BROADCAST_SCAN_ALARM_MIN_DELAY_MS) return
        val alarmManager = Applic.app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // Elapsed-realtime, not RTC: this alarm says "in delayMs", so a wall-clock correction
        // landing between arming and firing must not move it — in doze this alarm is the only
        // thing that re-arms the scan.
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        try {
            val pendingIntent = broadcastScanAlarmPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
            broadcastScanAlarmArmed = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set native broadcast scan alarm: ${e.message}")
        }
    }

    private fun cancelBroadcastScanAlarm() {
        if (!broadcastScanAlarmArmed) return
        val alarmManager = Applic.app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            val pendingIntent = broadcastScanAlarmPendingIntent(PendingIntent.FLAG_NO_CREATE) ?: return
            alarmManager.cancel(pendingIntent)
        } catch (_: Throwable) {}
        broadcastScanAlarmArmed = false
    }

    private fun forceStopActiveBroadcastScan(reason: String) {
        handler.removeCallbacks(broadcastScanRunnable)
        handler.removeCallbacks(broadcastScanStopRunnable)
        cancelBroadcastScanAlarm()
        if (broadcastScanActive) {
            try {
                broadcastScanner?.stopScan(broadcastScanCallback)
            } catch (_: Throwable) {}
        }
        broadcastScanActive = false
        broadcastScanStartedAtElapsed = 0L
        releaseBroadcastWakeLock()
        Log.w(TAG, "Force-stopped native broadcast scan ($reason)")
    }

    internal fun recoverAlarmScanIfStale(reason: String): Boolean {
        if (!broadcastScanActive || broadcastScanStartedAtElapsed <= 0L) return false
        val ageMs = SystemClock.elapsedRealtime() - broadcastScanStartedAtElapsed
        val staleAfterMs = currentBroadcastScanWindowMs(broadcastScanContinuousMode) + 2_000L
        if (ageMs <= staleAfterMs) {
            return false
        }
        forceStopActiveBroadcastScan(reason)
        return true
    }

    internal fun handleBroadcastScanAlarm(reason: String) {
        handler.post {
            if (stop) return@post
            if (broadcastScanActive && !recoverAlarmScanIfStale("alarm-$reason")) {
                Log.d(TAG, "Broadcast alarm ignored — scan already active for $SerialNumber")
                return@post
            }
            startBroadcastScan(reason)
        }
    }

    /**
     * A start attempt that never armed the scanner leaves no stop-runnable behind, and the
     * try-block below cancels the alarm before calling startScan(), so a failure exit leaves
     * nothing that can re-enter startBroadcastScan(). In broadcast-only mode the adverts are
     * the sensor's only data source, so every failure exit has to re-arm its own loop.
     *
     * A failed start is counted as a miss: a scanner that stays unavailable then drops
     * phase-lock after PHASE_LOCK_LOSS_MISS_COUNT tries and backs off to the wide
     * reacquisition cadence instead of spinning on the 5s phase-locked catch-up clamp.
     */
    private fun rearmBroadcastScanAfterFailedStart(reason: String, continuous: Boolean) {
        releaseBroadcastWakeLock()
        broadcastScanMisses += 1
        if (broadcastScanMisses >= PHASE_LOCK_LOSS_MISS_COUNT) {
            phaseLockHits = 0
        }
        Log.w(TAG, "Broadcast scan start aborted ($reason) — re-arming (misses=$broadcastScanMisses)")
        if (continuous) {
            scheduleBroadcastScan("failed-start-$reason")
        } else if (shouldContinueAssistScanning()) {
            handler.removeCallbacks(broadcastAssistRunnable)
            handler.postDelayed(broadcastAssistRunnable, BROADCAST_ASSIST_SCAN_DELAY_MS)
        }
    }

    /**
     * Start a BLE scan for broadcast advertisements from this sensor.
     * Used in broadcast-only mode and as a no-direct-live fallback while the
     * GATT session is still connected but F003 never started.
     *
     * Ported from AiDexSensor.kt:startBroadcastScan().
     */
    // BluetoothAdapter.getDefaultAdapter(): no Context reaches this callback; minSdk 26
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun startBroadcastScan(reason: String, continuous: Boolean = shouldContinueBroadcastScanning()) {
        if (forgotten || postUnpairBroadcastScanSuppressed) return
        // In broadcast-only mode shouldContinueBroadcastScanning() is always true, so this loop
        // re-enters once per scan window — the one heartbeat the fallback's return deadline can
        // be checked from without a callback of its own.
        if (maybeLeaveBroadcastOnlyFallback()) return
        if (broadcastScanActive && !recoverAlarmScanIfStale("start-$reason")) return

        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return rearmBroadcastScanAfterFailedStart("no-adapter-$reason", continuous)
        if (!adapter.isEnabled) return rearmBroadcastScanAfterFailedStart("adapter-off-$reason", continuous)

        val scanner = adapter.bluetoothLeScanner
            ?: return rearmBroadcastScanAfterFailedStart("no-scanner-$reason", continuous)
        broadcastScanner = scanner

        if (broadcastScanCallback == null) {
            broadcastScanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (forgotten) return
                    val device = result.device ?: return
                    val address = device.address
                    val scanRecord = result.scanRecord?.bytes ?: return
                    val targetAddress = mActiveDeviceAddress
                    val addressMatches = targetAddress != null && address == targetAddress
                    val scanName = aiDexExtractLocalName(scanRecord)
                        ?: result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                    val advertisedName = scanName ?: device.name
                    val identityMatches = addressMatches ||
                        (scanName?.let { aiDexDeviceNameMatchesSerial(it, SerialNumber) } == true)

                    if (!identityMatches) return

                    // Not during the post-reset bond hold: its exit reads the held device's bond, and
                    // SensorBluetooth routes bond broadcasts by mActiveDeviceAddress.
                    if (
                        reconnect.isBroadcastOnlyMode &&
                        postResetBondHold == null &&
                        targetAddress != null &&
                        !addressMatches
                    ) {
                        val occupied = tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter
                            .addressOccupiedByOtherRealSerial(Applic.app, SerialNumber, address) ||
                            tk.glucodata.SensorBluetooth.aidexLiveAddressOccupiedByOtherSerial(
                                SerialNumber,
                                address,
                            )
                        if (occupied) {
                            Log.w(
                                TAG,
                                "Broadcast-only identity matched via name but $address is occupied by another serial — not rebinding",
                            )
                            return
                        }
                        val persisted = tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter.persistAddress(
                            Applic.app,
                            SerialNumber,
                            address,
                        )
                        if (!persisted) {
                            Log.w(
                                TAG,
                                "Broadcast-only identity matched via name but persist refused $address — not rebinding",
                            )
                            return
                        }
                        Log.i(
                            TAG,
                            "Broadcast-only identity matched via name; rebinding address " +
                                "$targetAddress -> $address (${advertisedName ?: "unknown-name"})"
                        )
                        setDevice(device)
                    }
                    parseScanRecord(scanRecord, result.rssi)
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "Broadcast scan failed: $errorCode")
                    stopBroadcastScan("scan-failed-$errorCode", found = false)
                }
            }
        }

        // Always pin the scan to the AiDEX CGM service UUID (0x181F). An empty
        // filter list — which we used previously in broadcast-only mode — lets
        // Android deprioritise/throttle the scan and routinely caused minute-long
        // dead zones where the chip never surfaced an AiDEX advert even though
        // a UUID-filtered scan started moments later (SensorBluetooth.Scanner21)
        // would catch the same device in seconds. The service UUID is stable
        // across address changes, so this still handles rebond.
        // In a non-broadcast-only session we additionally pin the device address
        // for a tighter offload filter.
        val filters = arrayListOf(
            ScanFilter.Builder().apply {
                setServiceUuid(android.os.ParcelUuid(SERVICE_F000))
                val targetAddr = mActiveDeviceAddress
                if (!reconnect.isBroadcastOnlyMode && targetAddr != null) {
                    setDeviceAddress(targetAddr)
                }
            }.build()
        )

        // Phase-locked tight window: keep the radio actively listening for the brief
        // ~11s slot we open right before the expected advert. LOW_POWER's ~10% duty
        // would leave us listening for only ~1s of that window and let the advert
        // fall into an off-slot. The wide reacquisition window can stay LOW_POWER.
        val scanMode = if (continuous && isPhaseLocked()) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            ScanSettings.SCAN_MODE_LOW_POWER
        }
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        try {
            cancelBroadcastScanAlarm()
            acquireBroadcastWakeLock(currentBroadcastScanWindowMs(continuous) + 2_000L)
            scanner.startScan(filters, settings, broadcastScanCallback)
            broadcastScanActive = true
            broadcastScanContinuousMode = continuous
            broadcastScanStartedAtElapsed = SystemClock.elapsedRealtime()
            handler.removeCallbacks(broadcastScanStopRunnable)
            val windowMs = currentBroadcastScanWindowMs(continuous)
            handler.postDelayed(broadcastScanStopRunnable, windowMs)
            Log.i(TAG, "Broadcast scan started ($reason)")
            BatteryTrace.bump(
                key = "aidex.broadcast_scan.start",
                logEvery = 10L,
                detail = "reason=$reason continuous=$continuous windowMs=$windowMs misses=$broadcastScanMisses"
            )
        } catch (e: Exception) {
            // cancelBroadcastScanAlarm() ran two lines above, before startScan(), so a throwing
            // startScan leaves neither a pending post nor an alarm — the loop dies outright.
            Log.e(TAG, "Broadcast scan start failed: ${e.message}")
            rearmBroadcastScanAfterFailedStart("exception-$reason", continuous)
        }
    }

    /**
     * Stop the active broadcast scan and optionally schedule the next one.
     */
    @SuppressLint("MissingPermission")
    private fun stopBroadcastScan(reason: String, found: Boolean) {
        handler.removeCallbacks(broadcastScanRunnable)
        handler.removeCallbacks(broadcastScanStopRunnable)
        cancelBroadcastScanAlarm()

        val continuousMode = broadcastScanContinuousMode
        if (broadcastScanActive) {
            try {
                broadcastScanner?.stopScan(broadcastScanCallback)
            } catch (_: Throwable) {}
            broadcastScanActive = false
            broadcastScanStartedAtElapsed = 0L
            releaseBroadcastWakeLock()
            Log.d(TAG, "Broadcast scan stopped ($reason, found=$found)")
            BatteryTrace.bump(
                key = "aidex.broadcast_scan.stop",
                logEvery = 10L,
                detail = "reason=$reason found=$found misses=$broadcastScanMisses"
            )
        }

        broadcastScanMisses = if (found) 0 else (broadcastScanMisses + 1)
        // Drop phase-lock if we miss enough times in a row — the cadence estimate is
        // either stale or the device is out of range. Force reacquisition (wide scan).
        if (!found && broadcastScanMisses >= PHASE_LOCK_LOSS_MISS_COUNT) {
            phaseLockHits = 0
        }

        // Schedule next scan if this session is intentionally staying on broadcasts.
        val keepContinuousScanning = shouldContinueBroadcastScanning()
        if ((continuousMode || keepContinuousScanning) && keepContinuousScanning && !stop) {
            scheduleBroadcastScan("post-$reason")
        } else if (!continuousMode && !found && shouldContinueAssistScanning()) {
            broadcastScanContinuousMode = false
            handler.removeCallbacks(broadcastAssistRunnable)
            handler.postDelayed(broadcastAssistRunnable, BROADCAST_ASSIST_SCAN_DELAY_MS)
            Log.i(TAG, "Broadcast assist scan rescheduled after $reason (${firstValidReadingWaitStatus() ?: "waiting"})")
        } else {
            broadcastScanContinuousMode = false
        }
    }

    /**
     * Cancel all broadcast scan scheduling.
     */
    private fun cancelBroadcastScan() {
        handler.removeCallbacks(broadcastScanRunnable)
        handler.removeCallbacks(broadcastScanStopRunnable)
        cancelBroadcastScanAlarm()
        if (broadcastScanActive) {
            try {
                broadcastScanner?.stopScan(broadcastScanCallback)
            } catch (_: Throwable) {}
            broadcastScanActive = false
        }
        broadcastScanStartedAtElapsed = 0L
        releaseBroadcastWakeLock()
        broadcastScanContinuousMode = false
    }

    /**
     * Schedule the next broadcast scan with appropriate delay.
     *
     * When phase-locked (≥1 cadence sample, miss streak below loss threshold) we aim the next
     * scan at `lastFreshBroadcastElapsedMs + observedCadence*(misses+1) - PRE_OPEN`,
     * so we wake briefly *just* before the next expected advert. Otherwise we fall
     * back to the legacy fixed 60s / 15s-retry cadence to reacquire phase.
     */
    private fun scheduleBroadcastScan(reason: String) {
        if (!shouldContinueBroadcastScanning() || stop) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val phaseLocked = isPhaseLocked()
        var delay: Long
        val mode: String

        if (phaseLocked && lastFreshBroadcastElapsedMs > 0L) {
            // Anchor to last *fresh* (non-dup) catch. Dups within the same offset
            // window must NOT shift our notion of when the device's clock ticked.
            val nextExpected = lastFreshBroadcastElapsedMs +
                observedBroadcastCadenceMs * (broadcastScanMisses + 1)
            val openAt = nextExpected - PHASE_LOCKED_PRE_OPEN_MS
            val raw = openAt - nowElapsed
            // If next expected has already passed (we're chasing a missed slot),
            // keep retrying tight — but no faster than every 5s. Both stamps are
            // elapsedRealtime, so raw stays inside one cadence budget by construction and
            // needs no upper clamp — nothing here can be stretched by a clock correction.
            delay = if (raw < 5_000L) 5_000L else raw
            mode = "phase-locked"
        } else {
            delay = BROADCAST_SCAN_INTERVAL_MS
            if (delay > 15_000L && broadcastScanMisses in 1..5) {
                delay = 15_000L
            }
            mode = "reacquire"
        }

        handler.removeCallbacks(broadcastScanRunnable)
        handler.postDelayed(broadcastScanRunnable, delay)
        cancelBroadcastScanAlarm()
        scheduleBroadcastScanAlarm(delay)
        Log.d(
            TAG,
            "Broadcast scan scheduled in ${delay / 1000}s ($reason, misses=$broadcastScanMisses, " +
                "$mode, cadence=${observedBroadcastCadenceMs}ms, hits=$phaseLockHits)"
        )
    }

    private fun isPhaseLocked(): Boolean {
        return phaseLockHits >= 1 &&
            lastFreshBroadcastElapsedMs > 0L &&
            broadcastScanMisses < PHASE_LOCK_LOSS_MISS_COUNT
    }

    private fun currentBroadcastScanWindowMs(continuous: Boolean, now: Long = System.currentTimeMillis()): Long {
        if (!continuous) return BROADCAST_ASSIST_SCAN_WINDOW_MS
        // When phase-locked, a tight window centred on the predicted advert is enough
        // and keeps radio-on time at ≈18% of the cadence (≥82% deep sleep).
        if (isPhaseLocked()) return PHASE_LOCKED_SCAN_WINDOW_MS
        if (reconnect.isBroadcastOnlyMode) {
            // Legacy broadcast-only mode kept a full 30s scan window and was
            // noticeably more reliable on MIUI-style devices than the shorter
            // "healthy" native window.
            return BROADCAST_RECOVERY_SCAN_WINDOW_MS
        }
        return if (broadcastScanMisses > 0 || !hasRecentLiveData(now)) {
            BROADCAST_RECOVERY_SCAN_WINDOW_MS
        } else {
            BROADCAST_SCAN_WINDOW_MS
        }
    }

    private fun acquireBroadcastWakeLock(timeoutMs: Long) {
        if (broadcastWakeLock?.isHeld == true) return
        val powerManager = Applic.app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiDexBleManager:BroadcastScan")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(timeoutMs)
        broadcastWakeLock = wakeLock
    }

    private fun releaseBroadcastWakeLock() {
        try {
            broadcastWakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Throwable) {
        } finally {
            broadcastWakeLock = null
        }
    }

    /**
     * Parse scan record from BLE advertisement to extract manufacturer data.
     * AiDex broadcast format (in Manufacturer Specific Data, type 0xFF): see
     * [AiDexParser.parseBroadcastSample] — offset in bytes 0..1, i8 trend in byte 4, 10-bit mg/dL
     * in bytes 5..6.
     *
     * Ported from AiDexSensor.kt:onScanRecord() + parseBroadcastData().
     */
    private fun parseScanRecord(scanRecord: ByteArray, rssi: Int) {
        var offset = 0
        while (offset < scanRecord.size - 2) {
            val len = scanRecord[offset].toInt() and 0xFF
            if (len == 0) break
            val type = scanRecord[offset + 1].toInt() and 0xFF

            if (type == 0xFF) {  // Manufacturer Specific Data
                if (offset + 3 < scanRecord.size) {
                    val dataLen = len - 3
                    if (dataLen >= 6 && offset + 4 + dataLen <= scanRecord.size) {
                        val data = ByteArray(dataLen)
                        System.arraycopy(scanRecord, offset + 4, data, 0, dataLen)
                        handleBroadcastPayload(
                            payload = data,
                            source = "broadcast",
                            stopActiveScanAfterHandling = true,
                        )
                    }
                }
            }
            offset += len + 1
        }
    }

    private fun logRejectedBroadcastPayload(source: String, payload: ByteArray, reason: String, now: Long) {
        val shouldLogPayload =
            broadcastRejectionLogCount < BROADCAST_REJECTION_INITIAL_LOGS ||
                now - lastBroadcastRejectionLogAtMs >= BROADCAST_REJECTION_LOG_INTERVAL_MS
        if (shouldLogPayload) {
            broadcastRejectionLogCount += 1
            lastBroadcastRejectionLogAtMs = now
            logd(TAG) {
                "$source payload rejected: len=${payload.size} reason=$reason hex=${AiDexParser.hexString(payload)}"
            }
        }
    }

    private fun resolveBroadcastSampleTimestampMs(observedAtMs: Long, offsetMinutes: Int): Long {
        return AiDexHistoryPolicy.resolveOffsetBackedTimestampMs(
            observedAtMs = observedAtMs,
            sensorStartMs = sensorstartmsec,
            offsetMinutes = offsetMinutes,
        )
    }

    private fun maybePromoteFallbackReadingToHistory(now: Long, source: String) {
        noteValidReadingAvailable(now, "valid-$source")
        if (AiDexRuntimePolicy.shouldStartHistoryImmediately(
                pendingInitialHistoryRequest = pendingInitialHistoryRequest,
                historyDownloading = historyDownloading,
            )
        ) {
            pendingInitialHistoryRequest = false
            handler.removeCallbacks(delayedInitialHistoryRequest)
            Log.i(TAG, "First fallback reading arrived from $source — starting history now")
            runOnHandler { requestHistoryRange() }
        }
    }

    /**
     * Parse and handle broadcast glucose payload from either a connected `0x11`
     * reply or an advertisement scan result.
     */
    private fun handleBroadcastPayload(
        payload: ByteArray,
        source: String,
        stopActiveScanAfterHandling: Boolean,
    ) {
        markStartupControlComplete("broadcast-$source")
        val now = System.currentTimeMillis()
        val waitingForFirstDirectLive = waitingForFirstDirectLive()
        val hadRecentLiveDataBeforeBroadcast = hasRecentLiveData(now)
        val parsed = AiDexParser.parseBroadcastSample(payload)
        val sample = parsed.sample
        if (sample == null) {
            logRejectedBroadcastPayload(
                source = source,
                payload = payload,
                reason = parsed.rejectionReason ?: "unknown",
                now = now,
            )
            return
        }

        logi(TAG) {
            "$source sample: offset=${sample.offsetMinutes}min glucose=${sample.glucoseMgDl} mg/dL trend=${sample.trend}"
        }

        // Capture timing of the previous *fresh* (non-duplicate) accepted broadcast
        // before any state is touched. The cadence estimator MUST anchor here, not
        // on `lastBroadcastTime`, because dups bump `lastBroadcastTime` mid-cycle
        // and would otherwise pull the estimator below the device's real cadence.
        val previousFreshBroadcastElapsedMs = lastFreshBroadcastElapsedMs
        val previousOffsetForCadence = lastBroadcastOffsetForCadence

        lastBroadcastGlucose = sample.glucoseMgDl.toFloat()
        lastBroadcastTime = now

        if (
            sample.offsetMinutes.toLong() == lastBroadcastOffsetSeen &&
            sample.glucoseMgDl == lastBroadcastGlucoseSeen &&
            sample.trend == lastBroadcastTrendSeen &&
            (now - lastBroadcastOffsetSeenAtMs) < BROADCAST_DUPLICATE_SUPPRESS_MS
        ) {
            logd(TAG) {
                "$source duplicate suppressed: offset=${sample.offsetMinutes}min glucose=${sample.glucoseMgDl} trend=${sample.trend} " +
                    "ageMs=${now - lastBroadcastOffsetSeenAtMs}"
            }
            if (stopActiveScanAfterHandling) {
                stopBroadcastScan("broadcast-duplicate", found = true)
            }
            return
        }
        lastBroadcastOffsetSeen = sample.offsetMinutes.toLong()
        lastBroadcastGlucoseSeen = sample.glucoseMgDl
        lastBroadcastTrendSeen = sample.trend
        lastBroadcastOffsetSeenAtMs = now

        // Phase-lock: derive per-minute cadence from the gap to the previous *fresh*
        // catch. Using lastFreshBroadcastElapsedMs (not lastBroadcastTime) means dup
        // mid-cycle catches don't shorten the deltaTime — out-of-bounds values from
        // multi-cycle gaps are then correctly rejected by the bounds clamp.
        val nowElapsed = SystemClock.elapsedRealtime()
        if (previousFreshBroadcastElapsedMs > 0L && previousOffsetForCadence > 0) {
            val deltaOffset = sample.offsetMinutes - previousOffsetForCadence
            val deltaTimeMs = nowElapsed - previousFreshBroadcastElapsedMs
            if (deltaOffset in 1..5 && deltaTimeMs > 0L) {
                val perOffsetMs = deltaTimeMs / deltaOffset
                if (perOffsetMs in MIN_BROADCAST_CADENCE_MS..MAX_BROADCAST_CADENCE_MS) {
                    // EWMA: 75% old, 25% new — quick to track, slow to spike on jitter.
                    observedBroadcastCadenceMs =
                        (observedBroadcastCadenceMs * 3 + perOffsetMs) / 4
                    phaseLockHits = (phaseLockHits + 1).coerceAtMost(10)
                }
            }
        }
        lastBroadcastOffsetForCadence = sample.offsetMinutes
        lastFreshBroadcastElapsedMs = nowElapsed

        // Update offset tracking
        lastOffsetMinutes = sample.offsetMinutes
        ensureSensorStartTime(now, sample.offsetMinutes)
        val sampleTimestampMs = resolveBroadcastSampleTimestampMs(now, sample.offsetMinutes)
        val reportedWearDays = reportedWearDaysOrNull()
        if (!AiDexHistoryPolicy.isWithinWearDuration(sample.offsetMinutes, reportedWearDays)) {
            _sensorExpired = true
            Log.w(
                TAG,
                "$source: skipping post-wear broadcast reading offset=${sample.offsetMinutes} wearDays=$reportedWearDays"
            )
            if (stopActiveScanAfterHandling) {
                stopBroadcastScan("broadcast-post-wear", found = true)
            }
            return
        }
        ExchangeTrend.cacheAiDexTrend(SerialNumber, sampleTimestampMs, sample.trend)

        val fallbackActive = AiDexRuntimePolicy.shouldAcceptBroadcastFallback(
            broadcastOnlyMode = reconnect.isBroadcastOnlyMode,
            waitingForFirstDirectLive = waitingForFirstDirectLive,
            hadRecentLiveDataBeforeBroadcast = hadRecentLiveDataBeforeBroadcast,
        )
        if (!fallbackActive) {
            if (stopActiveScanAfterHandling) {
                stopBroadcastScan("broadcast-observed", found = true)
            }
            return
        }
        if (waitingForFirstDirectLive) {
            enableNoDirectLiveBroadcastFallbackMode("valid-$source")
        }
        // Update notification status
        if (reconnect.isBroadcastOnlyMode) {
            constatstatusstr = "Receiving"
        }

        // Broadcast cadence is defined by the sensor's minute offset, not by when our
        // scan happened to catch it. Never drop a new minute just because it arrived
        // only ~40s after the previous one; that is how visible chart gaps were created.
        if (lastBroadcastStoredOffsetMinutes > 0 && sample.offsetMinutes < lastBroadcastStoredOffsetMinutes) {
            logd(TAG) {
                "$source older minute suppressed: offset=${sample.offsetMinutes} storedOffset=$lastBroadcastStoredOffsetMinutes"
            }
            maybePromoteFallbackReadingToHistory(now, source)
            if (stopActiveScanAfterHandling) {
                stopBroadcastScan("broadcast-older-minute", found = true)
            }
            return
        }
        if (sample.offsetMinutes == lastBroadcastStoredOffsetMinutes) {
            maybePromoteFallbackReadingToHistory(now, source)
            if (stopActiveScanAfterHandling) {
                stopBroadcastScan("broadcast-same-minute", found = true)
            }
            return
        }

        // A connected 0x11 sample during live bootstrap is only a temporary bridge
        // until history/direct F003 catches up. Persisting it through the shared
        // current-reading path creates a raw-less AiDex row for that same minute,
        // which can outlive the later authoritative history row. Keep this path
        // ephemeral; true broadcast-only mode still persists broadcasts because it
        // has no connected history/live backfill to rely on.
        if (source == "connected-broadcast" && waitingForFirstDirectLive && !reconnect.isBroadcastOnlyMode) {
            val displayGlucose = if (Applic.unit == 1) {
                sample.glucoseMgDl / 18.0f
            } else {
                sample.glucoseMgDl.toFloat()
            }
            Log.i(
                TAG,
                "Connected broadcast bootstrap bridge: offset=${sample.offsetMinutes} glucose=${sample.glucoseMgDl} mg/dL (ephemeral; persistence deferred to history/direct live)"
            )
            SuperGattCallback.processExternalCurrentReading(
                SerialNumber,
                displayGlucose,
                0f,
                sampleTimestampMs,
                sensorgen
            )
            maybePromoteFallbackReadingToHistory(now, source)
            lastBroadcastStoredTime = now
            lastBroadcastStoredOffsetMinutes = sample.offsetMinutes
            if (stopActiveScanAfterHandling) {
                stopBroadcastScan("broadcast-bridge", found = true)
            }
            return
        }

        // Store via JNI
        if (dataptr != 0L) {
            try {
                val res = Natives.aidexProcessData(
                    dataptr,
                    byteArrayOf(0),
                    sampleTimestampMs,
                    sample.glucoseMgDl.toFloat(),
                    0f,
                    1.0f,
                    // Broadcast samples do carry the sensor's own trend byte — the same
                    // value cached into ExchangeTrend above. Prefer it over a derived rate.
                    sample.trend
                )
                handleGlucoseResult(res, sampleTimestampMs)
                maybePromoteFallbackReadingToHistory(now, source)
                lastBroadcastStoredTime = now
                lastBroadcastStoredOffsetMinutes = sample.offsetMinutes
                maybeRequestHistoryContinuitySyncAfterLive(sampleTimestampMs, source)
            } catch (e: Throwable) {
                Log.e(TAG, "$source: aidexProcessData failed: $e")
                val mgdlPacked = (sample.glucoseMgDl * 10).toLong() and 0xFFFFFFFFL
                handleGlucoseResult(mgdlPacked, sampleTimestampMs)
                maybePromoteFallbackReadingToHistory(now, "$source-fallback")
                lastBroadcastStoredTime = now
                lastBroadcastStoredOffsetMinutes = sample.offsetMinutes
                maybeRequestHistoryContinuitySyncAfterLive(sampleTimestampMs, "$source-fallback")
            }
        } else {
            val mgdlPacked = (sample.glucoseMgDl * 10).toLong() and 0xFFFFFFFFL
            handleGlucoseResult(mgdlPacked, sampleTimestampMs)
            maybePromoteFallbackReadingToHistory(now, "$source-no-dataptr")
            lastBroadcastStoredTime = now
            lastBroadcastStoredOffsetMinutes = sample.offsetMinutes
            maybeRequestHistoryContinuitySyncAfterLive(sampleTimestampMs, "$source-no-dataptr")
        }

        if (stopActiveScanAfterHandling) {
            stopBroadcastScan("broadcast-received", found = true)
        }
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    /**
     * Release all resources. Call when sensor is removed from gattcallbacks list.
     */
    override fun destroy() {
        forgotten = true
        stop = true
        cancelBroadcastScan()
        close()
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
    }

    // broadcastOnlyConnection is implemented as a property (line ~984) via AiDexDriver interface

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun beginStartupControlBootstrap(reason: String) {
        if (phase != Phase.STREAMING) return
        if (
            startupControlStage == StartupControlStage.WAIT_DYNAMIC_ADV_ACK ||
            startupControlStage == StartupControlStage.WAIT_AUTO_UPDATE_ACK ||
            startupControlStage == StartupControlStage.COMPLETE
        ) {
            return
        }

        val cmd = commandBuilder.setDynamicAdvMode(1)
        if (cmd == null) {
            Log.w(TAG, "Streaming startup control unavailable ($reason) — requesting connected broadcast directly")
            startupControlStage = StartupControlStage.FAILED
            requestConnectedBroadcastData("$reason-direct")
            return
        }

        startupControlStage = StartupControlStage.WAIT_DYNAMIC_ADV_ACK
        handler.removeCallbacks(startupControlAckTimeout)
        handler.postDelayed(startupControlAckTimeout, STARTUP_CONTROL_ACK_TIMEOUT_MS)
        Log.i(TAG, "Streaming startup: enabling dynamic adv mode before connected broadcast ($reason)")
        enqueueGattOp(GattOp.Write(CHAR_F002, cmd, AiDexOpcodes.SET_DYNAMIC_ADV_MODE))
    }

    private fun markStartupControlComplete(reason: String) {
        if (
            startupControlStage == StartupControlStage.IDLE ||
            startupControlStage == StartupControlStage.COMPLETE
        ) {
            return
        }
        handler.removeCallbacks(startupControlAckTimeout)
        startupControlStage = StartupControlStage.COMPLETE
        Log.i(TAG, "Streaming startup control complete ($reason)")
    }

    private fun u16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}
