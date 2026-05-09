// AnytimeBleManager.kt — BLE manager for Anytime / Yuwell CT3 transmitters.
//
// Lifecycle (CT3, the simplest case):
//
//   1. connectDevice → GATT connect
//   2. STATE_CONNECTED → request MTU 211, then discoverServices
//   3. onServicesDiscovered → find service (legacy 0xFFF0 or proprietary 0x1000)
//                            and enable notifications on the notify char (CCCD)
//   4. onDescriptorWrite:
//        if persisted bound flag is set: write {0x11} reset
//        else if family ∈ CT3_PLUS / CT3_YUWELL / CT3_ULTRASONIC / CT4:
//             write {0x20} transmitterFormal — handles voltage switch
//        else: write {0x05} check
//   5. RX 0x05 (check) → batt + IW + age check → write {0x03,...} setDate
//   6. RX 0x03/0x04 (setDate ack) → write {0x06} init
//   7. RX 0x06 (init ack) → mark bound, write {0x0F} lowPower, schedule pull
//   8. Steady state:
//        TX pushes {0x07, ...} N raw records each cadence → run algorithm
//        Phone may write {0x08, idLo, idHi} to backfill any missed ids
//   9. On disconnect: ReconnectManagement equivalent — exponential backoff
//
// Calibration:
//   - QR scan → AnytimeAlgorithm.decodeQr → store K/R + push {0x0B,K,R} to TX
//   - Fingerstick {0x09, mmolInt, mmolFrac/10}
//
// Reset / unbind:
//   - {0x11} reset_request — answered with bind state; we do not drop GATT
//   - {0x0A} unbind_request — full session teardown

package tk.glucodata.drivers.anytime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus

@SuppressLint("MissingPermission")
class AnytimeBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), AnytimeDriver {

    companion object {
        private const val TAG = AnytimeConstants.TAG

        /** SuperGattCallback generation tag. Same as MQ/iCan/AiDex. */
        const val SENSOR_GEN = 0

        private const val ACTIVE_SESSION_RECONNECT_DELAY_MS = 2_000L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val SERVICE_DISCOVERY_RETRY_DELAY_MS = 1_500L
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 2

        /** No-data watchdog multiplier applied to readingIntervalMinutes. */
        private const val NO_DATA_WATCHDOG_MULTIPLIER = 4L

        /** Watchdog after init — if no push/pull within 3.5× readingInterval, re-pull. */
        private const val PULL_FALLBACK_MULTIPLIER = 3L

        /** Backfill loop: gap between consecutive pulls when records keep arriving. */
        private const val HISTORY_PULL_BATCH_DELAY_MS = 500L

        /** How many empty pull responses in a row count as "caught up". */
        private const val HISTORY_EMPTY_RESPONSES_TO_STOP = 2

        /** Reset → reconnect grace period. */
        private const val RESET_RECONNECT_DELAY_MS = 700L

        /** Check / setDate / init each get a per-frame timeout. */
        private const val PROTOCOL_FRAME_TIMEOUT_MS = 8_000L
    }

    enum class Phase { IDLE, CONNECTING, DISCOVERING, HANDSHAKING, STREAMING }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    private val handlerThread = HandlerThread("Anytime-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    private var primaryService: BluetoothGattService? = null
    private var charNotify: BluetoothGattCharacteristic? = null
    private var charWrite: BluetoothGattCharacteristic? = null
    private var primaryServiceUuid: UUID = AnytimeConstants.SERVICE_PRIMARY

    @Volatile private var familyEntry: AnytimeConstants.FamilyEntry = AnytimeConstants.FAMILY_UNKNOWN
    @Volatile private var profile: AnytimeProfile = AnytimeProfileResolver.resolve()
    @Volatile private var qr: AnytimeQrCalibration? = null
    @Volatile private var voltageFlag: Int = 0
    @Volatile private var transmitterVersion: String = ""

    @Volatile private var lastGlucoseId: Int = -1
    @Volatile private var lastGlucoseAtMs: Long = 0L
    @Volatile private var lastGlucoseMgdlTimes10: Int = 0
    @Volatile private var sensorStartAtMs: Long = 0L
    @Volatile private var warmupStartedAtMs: Long = 0L
    @Volatile private var lastBatteryVolts: Float = 0f
    @Volatile private var lastIwNa: Float = 0f
    @Volatile private var lastIbNa: Float = 0f
    @Volatile private var lastTemperatureC: Float = 0f
    /** Most recent algorithm output — for diagnostics (5 electrode voltages,
     *  IIR-filtered currents, sensitivity coefficient, K_BASE/K_AUTO). */
    @Volatile private var lastAlgorithmResult: AnytimeAlgorithm.Result? = null
    @Volatile private var lastReferenceBgMgdlTimes10: Int = 0
    @Volatile private var lastReferenceBgGlucoseId: Int = 0
    @Volatile private var packetsSinceInit: Int = 0
    @Volatile private var bound: Boolean = false
    @Volatile private var reconnectReason: String = ""
    @Volatile private var serviceDiscoveryHandled: Boolean = false
    @Volatile private var serviceDiscoveryRetryCount: Int = 0
    @Volatile private var pendingFingerstickMgdl: Int = -1
    @Volatile private var pendingKrPush: Boolean = false
    @Volatile private var lastProtocolFrameAtMs: Long = 0L

    // ---- History backfill loop state ----
    @Volatile private var historyBackfillActive: Boolean = false
    @Volatile private var historyEmptyResponsesInARow: Int = 0
    @Volatile private var historyLastPulledId: Int = -1

    override var viewMode: Int = 0

    // ---- Restore from persistence ----

    fun restoreFromPersistence(context: Context) {
        val id = SerialNumber ?: return
        val cachedDeviceName = AnytimeRegistry.loadDeviceName(context, id)
        familyEntry = AnytimeProfileResolver.familyEntry(cachedDeviceName)
        profile = AnytimeProfileResolver.resolve(cachedDeviceName)
        val k = AnytimeRegistry.loadKValue(context, id)
        val r = AnytimeRegistry.loadRValue(context, id)
        val rawQr = AnytimeRegistry.loadQrContent(context, id)
        if (rawQr.isNotBlank()) {
            qr = AnytimeAlgorithm.decodeQr(rawQr) ?: qr ?: synthesiseQr(rawQr, k, r)
        } else if (k > 0f || r > 0f) {
            qr = synthesiseQr("", k, r)
        }
        voltageFlag = AnytimeRegistry.loadVoltageFlag(context, id)
        transmitterVersion = AnytimeRegistry.loadTransmitterVersion(context, id)
        lastGlucoseId = AnytimeRegistry.loadLastGlucoseId(context, id)
        sensorStartAtMs = AnytimeRegistry.loadSensorStartAt(context, id)
        warmupStartedAtMs = AnytimeRegistry.loadWarmupStartedAt(context, id)
        bound = AnytimeRegistry.loadBound(context, id)
    }

    private fun synthesiseQr(raw: String, k: Float, r: Float): AnytimeQrCalibration =
        AnytimeQrCalibration(
            rawQr = raw,
            format = AnytimeQrCalibration.Format.B,
            k = if (k > 0f) k else 0.30f,
            r = if (r > 0f) r else 50f,
            lifeTime = AnytimeConstants.DEFAULT_RATED_LIFETIME_DAYS,
            productMonth = 0,
            productYear = 0,
            electrodeType = "",
            electrodeTecNo = "",
            enzymeTecNo = "",
            membraneTecNo = "",
            marketNo = "",
            serialNo = "",
            sensorNo = "",
            unitOrder = 0,
            voltageFlag = voltageFlag,
            calibrationCount = 0,
        )

    private fun persistAlgorithmState() {
        val ctx = Applic.app ?: return
        val id = SerialNumber ?: return
        qr?.let {
            AnytimeRegistry.saveQrContent(ctx, id, it.rawQr)
            AnytimeRegistry.saveKValue(ctx, id, it.k)
            AnytimeRegistry.saveRValue(ctx, id, it.r)
            AnytimeRegistry.saveLifetimeDays(ctx, id, it.lifeTime)
        }
        AnytimeRegistry.saveVoltageFlag(ctx, id, voltageFlag)
        AnytimeRegistry.saveTransmitterVersion(ctx, id, transmitterVersion)
        AnytimeRegistry.saveBound(ctx, id, bound)
        AnytimeRegistry.saveLastGlucoseId(ctx, id, lastGlucoseId)
        AnytimeRegistry.saveSensorStartAt(ctx, id, sensorStartAtMs)
        AnytimeRegistry.saveWarmupStartedAt(ctx, id, warmupStartedAtMs)
    }

    // ---- Reconnect / watchdog ----

    private val reconnectRunnable = Runnable {
        if (stop) return@Runnable
        Log.i(TAG, "Reconnect requested: $reconnectReason")
        connectDevice(0)
    }

    private val noDataWatchdog = Runnable {
        if (stop || phase != Phase.STREAMING) return@Runnable
        val lastReadingMs = lastGlucoseAtMs
        if (lastReadingMs <= 0L) return@Runnable
        val elapsed = System.currentTimeMillis() - lastReadingMs
        if (elapsed < noDataWatchdogMs()) {
            armNoDataWatchdog()
            return@Runnable
        }
        Log.w(TAG, "No glucose for ${elapsed / 1000}s — forcing reconnect")
        runCatching { mBluetoothGatt?.disconnect() }
        scheduleReconnect("no-data watchdog", ACTIVE_SESSION_RECONNECT_DELAY_MS)
    }

    private val pullFallbackRunnable = Runnable {
        if (stop || phase != Phase.STREAMING) return@Runnable
        // If the transmitter went quiet, ask for the next id explicitly.
        if (lastGlucoseId >= 0) {
            writeFrame(AnytimeFrames.Builders.pullGlucose(lastGlucoseId + 1), "pullGlucose(fallback)")
        }
        armPullFallback()
    }

    private val historyBackfillRunnable = Runnable {
        if (stop || !historyBackfillActive) return@Runnable
        if (phase != Phase.STREAMING) return@Runnable
        val nextId = (lastGlucoseId + 1).coerceAtLeast(0)
        if (nextId >= familyEntry.endNumber) {
            Log.i(TAG, "Backfill complete (lastId=$lastGlucoseId, endNumber=${familyEntry.endNumber})")
            historyBackfillActive = false
            return@Runnable
        }
        historyLastPulledId = nextId
        Log.d(TAG, "Backfill pull next id=$nextId")
        writeFrame(AnytimeFrames.Builders.pullGlucose(nextId), "pullGlucose(backfill)")
    }

    /**
     * Start (or resume) the history backfill loop. Walks `lastGlucoseId+1`
     * upward, batch by batch, until the transmitter starts returning empty
     * frames or we hit the family's `endNumber`.
     */
    private fun startHistoryBackfill(reason: String) {
        if (phase != Phase.STREAMING) return
        if (historyBackfillActive) return
        Log.i(TAG, "Starting history backfill ($reason) from id=${lastGlucoseId + 1}")
        historyBackfillActive = true
        historyEmptyResponsesInARow = 0
        historyLastPulledId = -1
        handler.postDelayed(historyBackfillRunnable, 250L)
    }

    private fun stopHistoryBackfill() {
        historyBackfillActive = false
        handler.removeCallbacks(historyBackfillRunnable)
    }

    private val serviceDiscoveryWatchdog = Runnable {
        if (stop || phase != Phase.DISCOVERING || serviceDiscoveryHandled) return@Runnable
        Log.w(TAG, "Service discovery wedged — reconnecting")
        runCatching { mBluetoothGatt?.disconnect() }
        scheduleReconnect("service discovery timeout", ACTIVE_SESSION_RECONNECT_DELAY_MS)
    }

    private val serviceDiscoveryRetryRunnable: Runnable = Runnable {
        if (stop || serviceDiscoveryHandled) return@Runnable
        if (serviceDiscoveryRetryCount >= MAX_SERVICE_DISCOVERY_RETRIES) return@Runnable
        serviceDiscoveryRetryCount++
        val gatt = mBluetoothGatt ?: return@Runnable
        if (gatt.discoverServices()) {
            handler.postDelayed(serviceDiscoveryRetryRunnable, SERVICE_DISCOVERY_RETRY_DELAY_MS)
        }
    }

    private fun cancelReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        reconnectReason = ""
    }

    private fun scheduleReconnect(reason: String, delayMs: Long = ACTIVE_SESSION_RECONNECT_DELAY_MS) {
        if (stop) return
        reconnectReason = reason
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun noDataWatchdogMs(): Long =
        NO_DATA_WATCHDOG_MULTIPLIER * profile.readingIntervalMinutes * 60L * 1000L

    private fun armNoDataWatchdog() {
        handler.removeCallbacks(noDataWatchdog)
        if (lastGlucoseAtMs > 0L) {
            handler.postDelayed(noDataWatchdog, noDataWatchdogMs())
        }
    }

    private fun armPullFallback() {
        handler.removeCallbacks(pullFallbackRunnable)
        val delayMs = PULL_FALLBACK_MULTIPLIER * profile.readingIntervalMinutes * 60L * 1000L
        handler.postDelayed(pullFallbackRunnable, delayMs)
    }

    // ---- BLE lifecycle ----

    override fun getService(): UUID = primaryServiceUuid

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        if (phase == Phase.CONNECTING || phase == Phase.DISCOVERING ||
            phase == Phase.HANDSHAKING || phase == Phase.STREAMING
        ) {
            val hasGatt = mBluetoothGatt != null
            val hasDevice = mActiveBluetoothDevice != null || !mActiveDeviceAddress.isNullOrBlank()
            if (hasGatt || hasDevice) return true
        }
        phase = Phase.CONNECTING
        val scheduled = super.connectDevice(delayMillis)
        if (!scheduled && phase == Phase.CONNECTING) phase = Phase.IDLE
        return scheduled
    }

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val trimmed = deviceName?.trim().orEmpty()
        val knownAddress = mActiveDeviceAddress?.takeIf { it.isNotBlank() }
        if (knownAddress != null && address != null && address.equals(knownAddress, ignoreCase = true)) return true
        if (!address.isNullOrBlank() &&
            AnytimeConstants.canonicalSensorId(address).equals(SerialNumber, ignoreCase = true)
        ) {
            return true
        }
        if (trimmed.isEmpty()) return false
        val advertisedCanonical = AnytimeConstants.canonicalSensorId(trimmed)
        if (advertisedCanonical.equals(SerialNumber, ignoreCase = true)) return true
        return AnytimeConstants.isAnytimeDevice(trimmed)
    }

    override fun setDeviceAddress(address: String?) {
        super.setDeviceAddress(address)
        val normalized = address?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return
        val ctx = Applic.app ?: return
        val sensorId = SerialNumber ?: return
        AnytimeRegistry.ensureSensorRecord(
            context = ctx,
            sensorId = sensorId,
            address = normalized,
            displayName = familyEntry.family.displayName.ifBlank { AnytimeConstants.DEFAULT_DISPLAY_NAME },
        )
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (stop) return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "Connected to ${gatt.device?.address}")
                cancelReconnect()
                handler.removeCallbacks(noDataWatchdog)
                handler.removeCallbacks(pullFallbackRunnable)
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                connectTime = System.currentTimeMillis()
                serviceDiscoveryHandled = false
                serviceDiscoveryRetryCount = 0
                phase = Phase.DISCOVERING
                runCatching { gatt.requestMtu(AnytimeConstants.DEFAULT_MTU) }
                handler.postDelayed({
                    if (phase == Phase.DISCOVERING && mBluetoothGatt === gatt && !serviceDiscoveryHandled) {
                        if (gatt.discoverServices()) {
                            handler.postDelayed(serviceDiscoveryRetryRunnable, SERVICE_DISCOVERY_RETRY_DELAY_MS)
                        }
                    }
                }, 250)
                handler.postDelayed(serviceDiscoveryWatchdog, SERVICE_DISCOVERY_TIMEOUT_MS)
                UiRefreshBus.requestStatusRefresh()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "Disconnected (status=$status)")
                phase = Phase.IDLE
                primaryService = null
                charNotify = null
                charWrite = null
                serviceDiscoveryHandled = false
                serviceDiscoveryRetryCount = 0
                runCatching { gatt.close() }
                mBluetoothGatt = null
                mActiveBluetoothDevice = null
                handler.removeCallbacks(serviceDiscoveryWatchdog)
                handler.removeCallbacks(serviceDiscoveryRetryRunnable)
                handler.removeCallbacks(noDataWatchdog)
                handler.removeCallbacks(pullFallbackRunnable)
                stopHistoryBackfill()
                if (!stop) scheduleReconnect("GATT disconnect status=$status")
                UiRefreshBus.requestStatusRefresh()
            }
            else -> if (phase == Phase.CONNECTING && status != BluetoothGatt.GATT_SUCCESS) {
                phase = Phase.IDLE
            }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Log.d(TAG, "MTU=$mtu status=$status")
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        handler.removeCallbacks(serviceDiscoveryWatchdog)
        handler.removeCallbacks(serviceDiscoveryRetryRunnable)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "onServicesDiscovered failed status=$status")
            scheduleReconnect("services discovery failed", ACTIVE_SESSION_RECONNECT_DELAY_MS)
            return
        }
        serviceDiscoveryHandled = true
        // Try proprietary 0x1000 first, fall back to legacy 0xFFF0.
        var svc: BluetoothGattService? = gatt.getService(AnytimeConstants.SERVICE_PRIMARY)
        if (svc != null) {
            primaryServiceUuid = AnytimeConstants.SERVICE_PRIMARY
            charNotify = svc.getCharacteristic(AnytimeConstants.CHAR_NOTIFY_PRIMARY)
            charWrite = svc.getCharacteristic(AnytimeConstants.CHAR_WRITE_PRIMARY)
        } else {
            svc = gatt.getService(AnytimeConstants.SERVICE_LEGACY_CT2)
            if (svc != null) {
                primaryServiceUuid = AnytimeConstants.SERVICE_LEGACY_CT2
                charNotify = svc.getCharacteristic(AnytimeConstants.CHAR_NOTIFY_LEGACY)
                charWrite = svc.getCharacteristic(AnytimeConstants.CHAR_WRITE_LEGACY)
            }
        }
        primaryService = svc
        val notify = charNotify
        if (svc == null || notify == null || charWrite == null) {
            Log.e(TAG, "Required Anytime characteristics not found")
            scheduleReconnect("missing characteristics", ACTIVE_SESSION_RECONNECT_DELAY_MS)
            return
        }
        gatt.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(AnytimeConstants.CCCD)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            runCatching { gatt.writeDescriptor(cccd) }
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "onDescriptorWrite status=$status — retry reconnect")
            scheduleReconnect("descriptor write failed", ACTIVE_SESSION_RECONNECT_DELAY_MS)
            return
        }
        phase = Phase.HANDSHAKING

        val cachedName = SerialNumber?.let { AnytimeRegistry.loadDeviceName(Applic.app, it) }.orEmpty()
        familyEntry = AnytimeProfileResolver.familyEntry(cachedName)
        profile = AnytimeProfileResolver.resolve(cachedName)

        when {
            bound -> {
                Log.i(TAG, "Already bound — sending reset to confirm session")
                writeFrame(AnytimeFrames.Builders.reset(), "reset")
            }
            familyEntry.family in setOf(
                AnytimeConstants.Family.CT3_PLUS,
                AnytimeConstants.Family.CT3_YUWELL,
                AnytimeConstants.Family.CT3_ULTRASONIC,
                AnytimeConstants.Family.CT4,
            ) -> {
                Log.i(TAG, "Family ${familyEntry.family} — requesting formal version first")
                writeFrame(AnytimeFrames.Builders.transmitterFormal(), "transmitterFormal")
            }
            else -> {
                writeFrame(AnytimeFrames.Builders.check(), "check")
            }
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (stop) return
        if (characteristic.uuid != charNotify?.uuid) return
        val data = characteristic.value ?: return
        if (data.isEmpty()) return
        lastProtocolFrameAtMs = System.currentTimeMillis()
        val opcode = data[0]
        try {
            dispatch(opcode, data)
        } catch (t: Throwable) {
            Log.stack(TAG, "dispatch op=0x%02X".format(opcode.toInt() and 0xFF), t)
        }
    }

    private fun dispatch(opcode: Byte, data: ByteArray) {
        when (opcode) {
            AnytimeConstants.RX_VERSION -> Log.d(TAG, "RX 0x01 version: ${data.joinToHex()}")
            AnytimeConstants.RX_SET_DATE_ACK_A,
            AnytimeConstants.RX_SET_DATE_ACK_B -> {
                Log.d(TAG, "RX setDate ack")
                writeFrame(AnytimeFrames.Builders.init(), "init")
            }
            AnytimeConstants.RX_CHECK -> handleCheckResponse(data)
            AnytimeConstants.RX_INIT -> handleInitResponse()
            AnytimeConstants.RX_PUSH_GLUCOSE -> handleRawGlucose(data, push = true)
            AnytimeConstants.RX_PULL_GLUCOSE -> handleRawGlucose(data, push = false)
            AnytimeConstants.RX_INPUT_BG_ACK -> handleInputBgAck(data)
            AnytimeConstants.RX_UNBIND_ACK -> handleUnbindAck()
            AnytimeConstants.RX_INPUT_KR_ACK -> handleInputKrAck()
            AnytimeConstants.RX_COMPUTED_GLUCOSE -> handleComputedGlucose(data)
            AnytimeConstants.RX_LOW_POWER_ACK -> Log.d(TAG, "low-power ack")
            AnytimeConstants.RX_RESET -> handleResetResponse(data)
            AnytimeConstants.RX_MODIFY_VOLTAGE -> handleVoltageAck(data)
            AnytimeConstants.RX_TRANSMITTER_FORMAL -> handleFormalVersion(data)
            else -> Log.d(TAG, "Unhandled opcode 0x%02X len=%d".format(opcode.toInt() and 0xFF, data.size))
        }
    }

    // ---- Handshake handlers ----

    private fun handleFormalVersion(data: ByteArray) {
        val version = AnytimeFrames.parseFormalVersion(data)
        if (version.isNotEmpty()) {
            transmitterVersion = version
            Log.i(TAG, "Transmitter version: $version")
            persistAlgorithmState()
        }
        // Voltage switch: if firmware is V1300 or V1400 and we have a QR, switch.
        val needsVoltageSwitch = (version.contains("V1300", ignoreCase = true) ||
            version.contains("V1400", ignoreCase = true))
        if (needsVoltageSwitch && qr != null) {
            voltageFlag = qr?.voltageFlag ?: voltageFlag
            writeFrame(AnytimeFrames.Builders.modifyVoltage(voltageFlag), "modifyVoltage($voltageFlag)")
        } else {
            writeFrame(AnytimeFrames.Builders.check(), "check")
        }
    }

    private fun handleVoltageAck(data: ByteArray) {
        val echoed = if (data.size >= 2) data[1].toInt() and 0xFF else -1
        Log.i(TAG, "Voltage switch ack: $echoed")
        writeFrame(AnytimeFrames.Builders.check(), "check(post-voltage)")
    }

    private fun handleCheckResponse(data: ByteArray) {
        val status = AnytimeFrames.parseCheckResponse(data, profile.lowBatteryVolts)
        lastBatteryVolts = status.batteryVolts
        if (!status.isHealthy) {
            Log.w(TAG, "Check failed: ${status.failure} (battery=${status.batteryVolts}V iw=${status.workingElectrodeCurrentNa}nA)")
            if (status.failure == AnytimeCheckStatus.CheckFailure.LOW_BATTERY) {
                runCatching { mBluetoothGatt?.disconnect() }
            }
            return
        }
        Log.i(TAG, "Check OK (battery=${status.batteryVolts}V iw=${status.workingElectrodeCurrentNa}nA age=${status.sensorAgeReadings})")
        writeFrame(AnytimeFrames.Builders.setDate(), "setDate")
    }

    private fun handleInitResponse() {
        Log.i(TAG, "Init OK — entering streaming")
        bound = true
        phase = Phase.STREAMING
        if (sensorStartAtMs == 0L) {
            val now = System.currentTimeMillis()
            sensorStartAtMs = now
            sensorstartmsec = now
            warmupStartedAtMs = now
        }
        ensureNativeSensorShell()
        persistAlgorithmState()
        if (pendingKrPush) {
            pendingKrPush = false
            qr?.let { writeFrame(AnytimeFrames.Builders.inputKR(it.k, it.r), "inputKR(deferred)") }
        }
        writeFrame(AnytimeFrames.Builders.lowPower(), "lowPower")
        armPullFallback()
        armNoDataWatchdog()
        // Pull any history we missed since last connect (or from id 0 on a
        // fresh pair if persisted state has lastGlucoseId == -1, which falls
        // through to "ask for id 0").
        startHistoryBackfill("post-init")
        UiRefreshBus.requestStatusRefresh()
    }

    private fun handleResetResponse(data: ByteArray) {
        val parsed = AnytimeFrames.parseResetResponse(data)
        if (parsed == null) {
            Log.w(TAG, "Bad reset response")
            scheduleReconnect("reset response malformed", ACTIVE_SESSION_RECONNECT_DELAY_MS)
            return
        }
        Log.i(TAG, "Reset response: bound=${parsed.isBound}")
        bound = parsed.isBound
        persistAlgorithmState()
        if (parsed.isBound) {
            // Sensor confirms session is alive — proceed to streaming and pull
            // any history we missed while disconnected.
            phase = Phase.STREAMING
            armPullFallback()
            armNoDataWatchdog()
            startHistoryBackfill("post-reset(reconnect)")
            UiRefreshBus.requestStatusRefresh()
        } else {
            // Sensor lost binding — fall through to fresh handshake.
            writeFrame(AnytimeFrames.Builders.check(), "check(post-reset)")
        }
    }

    private fun handleInputBgAck(data: ByteArray) {
        Log.i(TAG, "Fingerstick BG accepted: ${data.joinToHex()}")
        if (pendingFingerstickMgdl > 0 && lastGlucoseId >= 0) {
            lastReferenceBgMgdlTimes10 = pendingFingerstickMgdl * 10
            lastReferenceBgGlucoseId = lastGlucoseId
        }
        pendingFingerstickMgdl = -1
    }

    private fun handleUnbindAck() {
        Log.i(TAG, "Unbind ack received — closing GATT")
        bound = false
        persistAlgorithmState()
        runCatching { mBluetoothGatt?.disconnect() }
    }

    private fun handleInputKrAck() {
        Log.i(TAG, "K/R upload ack")
    }

    // ---- Glucose pipeline ----

    private fun handleRawGlucose(data: ByteArray, push: Boolean) {
        val records = AnytimeFrames.parseRawRecords(data)
        val context = Applic.app
        val intervalMs = profile.readingIntervalMinutes * 60L * 1000L
        if (records.isEmpty()) {
            // Empty pull response — transmitter has nothing more to give.
            Log.d(TAG, "Empty raw frame (pull caught-up): ${data.joinToHex()}")
            if (historyBackfillActive) {
                historyEmptyResponsesInARow++
                if (historyEmptyResponsesInARow >= HISTORY_EMPTY_RESPONSES_TO_STOP) {
                    Log.i(TAG, "Backfill caught up at id=$lastGlucoseId after $historyEmptyResponsesInARow empty responses")
                    stopHistoryBackfill()
                } else {
                    handler.postDelayed(historyBackfillRunnable, HISTORY_PULL_BATCH_DELAY_MS)
                }
            }
            return
        }
        historyEmptyResponsesInARow = 0
        val now = System.currentTimeMillis()
        for (rec in records) {
            packetsSinceInit++
            lastIwNa = rec.iwNa
            lastIbNa = rec.ibNa
            lastTemperatureC = rec.temperatureC
            // Anchor every reading on its absolute glucose id mapped through
            // session start. This gives correct timestamps for both real-time
            // pushes (id at end-of-stream) and backfill pulls (older ids in
            // the middle of the session). Falls back to wall-clock anchoring
            // if we have no session start (e.g. a stub-binding reconnect).
            val sampleMs = if (sensorStartAtMs > 0L) {
                sensorStartAtMs + rec.glucoseId.toLong() * intervalMs
            } else {
                now - rec.indexInPacket * intervalMs
            }
            val result = AnytimeAlgorithm.compute(
                record = rec,
                qr = qr,
                family = familyEntry,
                sensorIdName = SerialNumber.orEmpty(),
                sampleTimeMs = sampleMs,
                lastReferenceBgMgdlTimes10 = lastReferenceBgMgdlTimes10,
                lastReferenceBgGlucoseId = lastReferenceBgGlucoseId,
                sessionPacketsSinceInit = packetsSinceInit,
            )
            commitReading(result, sampleMs, context)
            if (rec.glucoseId > lastGlucoseId) lastGlucoseId = rec.glucoseId
        }
        persistAlgorithmState()
        armNoDataWatchdog()
        armPullFallback()
        // Chain the backfill loop: if this was a non-empty pull response, keep
        // pulling until empty (i.e. caught up to live cadence).
        if (historyBackfillActive) {
            handler.postDelayed(historyBackfillRunnable, HISTORY_PULL_BATCH_DELAY_MS)
        }
        UiRefreshBus.requestStatusRefresh()
    }

    private fun handleComputedGlucose(data: ByteArray) {
        val rec = AnytimeFrames.parseComputedRecord(data) ?: run {
            Log.w(TAG, "Bad computed-glucose frame: ${data.joinToHex()}")
            return
        }
        val intervalMs = profile.readingIntervalMinutes * 60L * 1000L
        val sampleMs = if (sensorStartAtMs > 0L) {
            sensorStartAtMs + rec.glucoseId.toLong() * intervalMs
        } else {
            System.currentTimeMillis()
        }
        val result = AnytimeAlgorithm.fromComputedRecord(rec)
        commitReading(result, sampleMs, Applic.app)
        if (rec.glucoseId > lastGlucoseId) lastGlucoseId = rec.glucoseId
        persistAlgorithmState()
        armNoDataWatchdog()
        armPullFallback()
        UiRefreshBus.requestStatusRefresh()
    }

    private fun commitReading(result: AnytimeAlgorithm.Result, sampleMs: Long, context: Context?) {
        if (sampleMs >= lastGlucoseAtMs) {
            lastGlucoseAtMs = sampleMs
            lastGlucoseMgdlTimes10 = result.mgdlTimes10
            lastAlgorithmResult = result
        }
        Log.i(
            TAG,
            "BG id=%d %s mmol=%.2f mgdl=%.1f Iw=%.2fnA Ib=%.2fnA T=%.1fC trend=%d err=%d".format(
                result.glucoseId, result.source, result.mmol, result.mgdl,
                result.iwNa, result.ibNa, result.temperatureC, result.trend, result.errorCode,
            )
        )
        mirrorReadingIntoNative(sampleMs, result.mgdlTimes10 / 10)
    }

    private fun ensureNativeSensorShell() {
        val canonical = SerialNumber ?: return
        runCatching {
            val startSec = (sensorStartAtMs / 1000L).coerceAtLeast(1L)
            Natives.ensureSensorShell(canonical, startSec)
            if (dataptr == 0L) {
                dataptr = runCatching { Natives.getdataptr(canonical) }.getOrDefault(0L)
            }
        }.onFailure { Log.stack(TAG, "ensureNativeSensorShell", it) }
    }

    private fun mirrorReadingIntoNative(sampleMs: Long, glucoseMgdl: Int) {
        val name = SerialNumber ?: return
        runCatching {
            Natives.addGlucoseStream(sampleMs / 1000L, glucoseMgdl.toFloat(), name)
            Natives.wakebackup()
        }.onFailure { Log.stack(TAG, "mirrorReadingIntoNative", it) }
    }

    // ---- Frame writer ----

    private fun writeFrame(bytes: ByteArray, tag: String) {
        val gatt = mBluetoothGatt ?: return
        val ch = charWrite ?: return
        ch.value = bytes
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = runCatching { gatt.writeCharacteristic(ch) }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "writeCharacteristic($tag) returned false bytes=${bytes.joinToHex()}")
        } else {
            Log.d(TAG, "TX $tag bytes=${bytes.joinToHex()}")
        }
    }

    // ---- AnytimeDriver implementation ----

    override fun setQrCalibration(rawQr: String): Boolean {
        val parsed = AnytimeAlgorithm.decodeQr(rawQr) ?: run {
            Log.w(TAG, "QR decode failed: $rawQr")
            return false
        }
        qr = parsed
        voltageFlag = parsed.voltageFlag
        persistAlgorithmState()
        // If actively streaming, push K/R now; otherwise queue for next init.
        if (phase == Phase.STREAMING) {
            writeFrame(AnytimeFrames.Builders.inputKR(parsed.k, parsed.r), "inputKR")
        } else {
            pendingKrPush = true
        }
        return true
    }

    override fun pushReferenceBg(mgdl: Int): Boolean {
        if (mgdl <= 0) return false
        pendingFingerstickMgdl = mgdl
        return if (phase == Phase.STREAMING) {
            writeFrame(AnytimeFrames.Builders.inputBgMg(mgdl), "inputBg($mgdl)")
            true
        } else {
            Log.w(TAG, "pushReferenceBg($mgdl) deferred — not streaming (phase=$phase)")
            false
        }
    }

    override fun requestTransmitterReset(): Boolean {
        if (phase != Phase.STREAMING && phase != Phase.HANDSHAKING) return false
        writeFrame(AnytimeFrames.Builders.reset(), "reset(user)")
        return true
    }

    override fun requestUnbind(): Boolean {
        writeFrame(AnytimeFrames.Builders.unbind(), "unbind(user)")
        bound = false
        persistAlgorithmState()
        stopHistoryBackfill()
        return true
    }

    override fun requestHistoryBackfill(): Boolean {
        if (phase != Phase.STREAMING) {
            Log.w(TAG, "requestHistoryBackfill ignored — phase=$phase")
            return false
        }
        startHistoryBackfill("user-requested")
        return true
    }

    override fun getCurrentSnapshot(maxAgeMillis: Long): AnytimeCurrentSnapshot? {
        if (lastGlucoseAtMs == 0L) return null
        if (System.currentTimeMillis() - lastGlucoseAtMs > maxAgeMillis) return null
        return AnytimeCurrentSnapshot(
            timeMillis = lastGlucoseAtMs,
            glucoseValue = lastGlucoseMgdlTimes10 / 10f,
            rawValue = lastIwNa,
            rate = Float.NaN,
            sensorGen = SENSOR_GEN,
        )
    }

    override fun getStartTimeMs(): Long = sensorStartAtMs
    override fun getOfficialEndMs(): Long =
        if (sensorStartAtMs <= 0L) 0L else sensorStartAtMs + profile.ratedLifetimeMs()
    override fun getExpectedEndMs(): Long = getOfficialEndMs()
    override fun isSensorExpired(): Boolean {
        val end = getOfficialEndMs()
        return end > 0L && System.currentTimeMillis() > end
    }
    override fun getSensorRemainingHours(): Int {
        val end = getOfficialEndMs()
        if (end <= 0L) return -1
        val ms = end - System.currentTimeMillis()
        if (ms <= 0L) return 0
        return (ms / 3_600_000L).toInt()
    }
    override fun getSensorAgeHours(): Int {
        if (sensorStartAtMs <= 0L) return -1
        return ((System.currentTimeMillis() - sensorStartAtMs) / 3_600_000L).toInt()
    }
    override fun getReadingIntervalMinutes(): Int = profile.readingIntervalMinutes

    override val vendorFirmwareVersion: String get() = transmitterVersion
    override val vendorModelName: String get() = familyEntry.family.displayName

    override val batteryMillivolts: Int get() = (lastBatteryVolts * 1000f).toInt()
    override val batteryPercent: Int
        get() = AnytimeFrames.batteryPercent(lastBatteryVolts, profile.lowBatteryVolts)

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        AnytimeConstants.matchesCanonicalOrKnownNativeAlias(sensorId, SerialNumber)

    override fun hasNativeSensorBacking(): Boolean = true

    override fun getDetailedBleStatus(): String = when (phase) {
        Phase.IDLE -> "Idle"
        Phase.CONNECTING -> "Connecting"
        Phase.DISCOVERING -> "Discovering"
        Phase.HANDSHAKING -> "Handshaking"
        Phase.STREAMING -> if (lastGlucoseAtMs > 0L) {
            val ageMin = ((System.currentTimeMillis() - lastGlucoseAtMs) / 60000L).toInt()
            "Streaming • last reading ${ageMin}m ago"
        } else "Streaming (warming up)"
    }

    /**
     * Format the rich algorithm-internal state for a debug pane. Returns null if
     * we have no readings yet or are running on the linear-fallback path (where
     * none of the diagnostic fields are populated).
     */
    fun getAlgorithmDiagnostics(): String? {
        val r = lastAlgorithmResult ?: return null
        if (r.source == AnytimeAlgorithm.Source.LINEAR) {
            return "Linear fallback · K=${qr?.k ?: 0f} R=${qr?.r ?: 0f}\n" +
                "Iw=${"%.2f".format(r.iwNa)} nA · Ib=${"%.2f".format(r.ibNa)} nA · T=${"%.1f".format(r.temperatureC)}°C"
        }
        val voltagesLine = if (r.weVoltageMv != Int.MIN_VALUE) {
            "WE=${r.weVoltageMv}mV BE=${r.beVoltageMv}mV RE=${r.reVoltageMv}mV CE=${r.ceVoltageMv}mV B=${r.bVoltageMv}mV"
        } else ""
        val iirLine = if (!r.iw30Iir.isNaN() || !r.iw48Iir.isNaN()) {
            "Iw30IIR=${"%.2f".format(r.iw30Iir)} Iw48IIR=${"%.2f".format(r.iw48Iir)}"
        } else ""
        val kLine = if (!r.kBase.isNaN() || !r.kAuto.isNaN()) {
            "K_BASE=${"%.3f".format(r.kBase)} K_AUTO=${"%.3f".format(r.kAuto)} sens=${"%.3f".format(r.sensitivityCoefficient)}"
        } else ""
        return listOf(
            "Vendor algorithm",
            "Iw=${"%.2f".format(r.iwNa)} nA · Ib=${"%.2f".format(r.ibNa)} nA · T=${"%.1f".format(r.temperatureC)}°C",
            voltagesLine,
            iirLine,
            kLine,
            "Trend=${r.trend} Err=${r.errorCode} Warn=${r.warnCode}",
        ).filter { it.isNotBlank() }.joinToString("\n")
    }
}

// ---- Local helpers ----

private fun ByteArray.joinToHex(): String =
    joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
