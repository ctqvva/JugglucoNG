package tk.glucodata.drivers.novo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus

@SuppressLint("MissingPermission")
class NovoBleManager(
    sensorId: String,
    dataptr: Long,
) : SuperGattCallback(sensorId, dataptr, NovoConstants.SENSOR_GEN), NovoDriver {

    companion object {
        private const val TAG = "NovoBLE"
        private val CCCD = UUID.fromString(NovoConstants.CCCD_UUID)
        private val CGM_SERVICE = UUID.fromString(NovoConstants.SERVICE_CGM)
        private val CONTROL_POINT = UUID.fromString(NovoConstants.CHAR_CGM_SPECIFIC_OPS_CONTROL)
        private val MEASUREMENT = UUID.fromString(NovoConstants.CHAR_CGM_MEASUREMENT)
        private const val WRITE_RETRY_DELAY_MS = 300L
        private const val MAX_WRITE_RETRIES = 2
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val SCAN_WAIT_TIMEOUT_MS = 30_000L
        private const val MAX_SILENT_CONNECT_ATTEMPTS = 2
        private const val CONNECT_BACKOFF_MS = 60_000L
    }

    enum class Phase { IDLE, CONNECTING, DISCOVERING, SUBSCRIBING, STREAMING, ERROR }

    private data class QueuedCommand(
        val payload: ByteArray,
        val useDefaultPassword: Boolean,
    )

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    override var viewMode: Int = 0

    @Volatile
    var latestMeasurement: NovoMeasurement? = null
        private set

    @Volatile
    var deviceInfo: NovoDeviceInfo? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val txQueue = ArrayDeque<QueuedCommand>()
    @Volatile private var txInFlight = false
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var txCommandCount = 0

    @Volatile private var calibration: NovoCalibration = NovoCalibration(
        NovoConstants.DEFAULT_K,
        NovoConstants.DEFAULT_B,
    )
    @Volatile private var useAtMs = 0L
    @Volatile private var passwordLow: Byte = 0
    @Volatile private var passwordHigh: Byte = 0
    @Volatile private var historyEndMs = 0L
    @Volatile private var firmwareInfo = ""
    @Volatile private var silentConnectAttempts = 0
    @Volatile private var nextConnectAllowedAtMs = 0L
    @Volatile private var connectScheduled = false
    @Volatile private var scanWaitDeadlineMs = 0L

    private val reconnectRunnable = Runnable {
        nextConnectAllowedAtMs = 0L
        if (!stop && mBluetoothGatt == null) connectDevice(0L)
    }

    private val scanTimeoutRunnable = Runnable {
        if (stop || mBluetoothGatt != null || connectScheduled) return@Runnable
        if (phase == Phase.IDLE && System.currentTimeMillis() >= scanWaitDeadlineMs) {
            phase = Phase.ERROR
            scheduleRetry()
            UiRefreshBus.requestStatusRefresh()
        }
    }

    private val stateLock = Any()
    private var pendingRetryFrame: ByteArray? = null
    private var inFlightFrame: ByteArray? = null
    private var writeRetries = 0

    init {
        Applic.app?.let(::applyRegistrySettings)
    }

    fun applyRegistrySettings(context: Context) {
        calibration = NovoRegistry.getCalibration(context)
        useAtMs = NovoRegistry.getUseAtMs(context)
        val password = NovoRegistry.getPasswordBytes(context)
        passwordLow = password.first
        passwordHigh = password.second
    }

    override fun getConnectionStatus(): String = getDetailedBleStatus()

    override fun getDetailedBleStatus(): String = when (phase) {
        Phase.IDLE -> if (mActiveBluetoothDevice == null) "Searching" else "Idle"
        Phase.CONNECTING -> "Connecting"
        Phase.DISCOVERING -> "Discovering services"
        Phase.SUBSCRIBING -> "Subscribing"
        Phase.STREAMING -> "Connected"
        Phase.ERROR -> "Error"
    }

    override fun isConnected(): Boolean = phase == Phase.STREAMING

    override fun getDeviceAddress(): String = mActiveDeviceAddress ?: ""

    override fun getFirmwareInfo(): String = firmwareInfo

    override fun matchesManagedSensorId(sensorId: String?): Boolean {
        if (sensorId.isNullOrBlank()) return false
        return sensorId.equals(SerialNumber, ignoreCase = true)
    }

    override fun getService(): UUID? = CGM_SERVICE

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val known = mActiveDeviceAddress?.takeIf { it.isNotBlank() }
        if (known != null && address != null && address.equals(known, ignoreCase = true)) return true
        return matchesNovoName(deviceName)
    }

    override fun matchScanResult(result: ScanResult): Boolean {
        val name = result.device?.name ?: result.scanRecord?.deviceName
        return matchesNovoName(name)
    }

    private fun matchesNovoName(name: String?): Boolean =
        name?.trim()?.startsWith(NovoConstants.SCAN_NAME_PREFIX, ignoreCase = true) ?: false

    private fun scheduleRetry() {
        nextConnectAllowedAtMs = System.currentTimeMillis() + CONNECT_BACKOFF_MS
        silentConnectAttempts = 0
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, CONNECT_BACKOFF_MS + 500L)
    }

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        if (System.currentTimeMillis() < nextConnectAllowedAtMs) return true
        if (phase != Phase.IDLE && phase != Phase.ERROR) {
            if (mBluetoothGatt != null || connectScheduled) return true
        }
        phase = Phase.CONNECTING
        connectScheduled = true
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        val scheduled = super.connectDevice(delayMillis)
        if (!scheduled) {
            connectScheduled = false
            phase = Phase.IDLE
            scanWaitDeadlineMs = System.currentTimeMillis() + SCAN_WAIT_TIMEOUT_MS
            mainHandler.removeCallbacks(scanTimeoutRunnable)
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_WAIT_TIMEOUT_MS)
        }
        return scheduled
    }

    protected override fun connectionAttemptTimeoutMillis(): Long = CONNECT_TIMEOUT_MS

    protected override fun onConnectionAttemptTimeout() {
        silentConnectAttempts++
        if (silentConnectAttempts >= MAX_SILENT_CONNECT_ATTEMPTS) {
            phase = Phase.ERROR
            mActiveBluetoothDevice = null
            mActiveDeviceAddress = null
            Applic.app?.let { NovoRegistry.setLastBtAddress(it, "") }
            scheduleRetry()
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        noteFirstGattCallback("onConnectionStateChange", gatt)
        if (!acceptConnectionAttemptCallback(gatt, newState)) {
            if (gatt === mBluetoothGatt) {
                runCatching { closeGattTransport() }
            } else {
                if (newState == BluetoothProfile.STATE_CONNECTED) runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
            return
        }
        super.onConnectionStateChange(gatt, status, newState)
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                gatt.device?.address?.let { address ->
                    mActiveDeviceAddress = address
                    Applic.app?.let { NovoRegistry.setLastBtAddress(it, address) }
                }
                connectTime = System.currentTimeMillis()
                silentConnectAttempts = 0
                nextConnectAllowedAtMs = 0L
                connectScheduled = false
                mainHandler.removeCallbacks(scanTimeoutRunnable)
                phase = Phase.DISCOVERING
                mainHandler.postDelayed({
                    if (!stop && phase == Phase.DISCOVERING && mBluetoothGatt === gatt) {
                        if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
                            phase = Phase.ERROR
                            runCatching { closeGattTransport() }
                        }
                    }
                }, NovoConstants.DISCOVERY_DELAY_MS)
                UiRefreshBus.requestStatusRefresh()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                synchronized(stateLock) {
                    txQueue.clear()
                    pendingRetryFrame = null
                    inFlightFrame = null
                    txInFlight = false
                    writeRetries = 0
                }
                writeCharacteristic = null
                notifyCharacteristic = null
                connectScheduled = false
                mainHandler.removeCallbacks(scanTimeoutRunnable)
                phase = Phase.IDLE
                runCatching { closeGattTransport() }
                if (mBluetoothGatt === gatt) mBluetoothGatt = null
                UiRefreshBus.requestStatusRefresh()
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (stop || mBluetoothGatt !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            phase = Phase.ERROR
            runCatching { closeGattTransport() }
            return
        }
        val cgm = gatt.getService(CGM_SERVICE)
        val write = cgm?.getCharacteristic(CONTROL_POINT)
            ?: gatt.services.flatMap { it.characteristics }.firstOrNull {
                it.properties and (
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                    ) != 0
            }
        val notify = cgm?.getCharacteristic(MEASUREMENT)
            ?: gatt.services.flatMap { it.characteristics }.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            }
        if (write == null || notify == null) {
            Log.w(TAG, "Required NOVO characteristics not found (write=$write notify=$notify)")
            phase = Phase.ERROR
            runCatching { closeGattTransport() }
            return
        }
        writeCharacteristic = write
        notifyCharacteristic = notify
        if (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        phase = Phase.SUBSCRIBING
        if (!enableNotification(gatt, notify)) {
            phase = Phase.ERROR
            runCatching { closeGattTransport() }
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (stop || mBluetoothGatt !== gatt) return
        if (descriptor.uuid != CCCD) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            phase = Phase.ERROR
            runCatching { closeGattTransport() }
            return
        }
        if (notifyCharacteristic == null || descriptor.characteristic?.uuid != notifyCharacteristic?.uuid) return
        phase = Phase.STREAMING
        UiRefreshBus.requestStatusRefresh()
        enqueue(NovoParser.inquireFlashData(0), useDefaultPassword = false)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (stop || mBluetoothGatt !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "NOVO write failed status=$status")
            val frame = synchronized(stateLock) { inFlightFrame }
            if (frame != null) onWriteFailed(gatt, frame)
            return
        }
        mainHandler.post {
            if (stop || mBluetoothGatt !== gatt) return@post
            synchronized(stateLock) {
                txInFlight = false
                inFlightFrame = null
                writeRetries = 0
                schedulePumpLocked()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (stop || mBluetoothGatt !== gatt) return
        val data = characteristic.value ?: return
        handlePayload(characteristic.uuid, data)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (stop || mBluetoothGatt !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) return
        val data = characteristic.value ?: return
        handlePayload(characteristic.uuid, data)
    }

    private fun enqueue(payload: ByteArray, useDefaultPassword: Boolean = false) {
        synchronized(stateLock) {
            txQueue.addLast(QueuedCommand(payload, useDefaultPassword))
        }
        mainHandler.post {
            if (stop) return@post
            synchronized(stateLock) { schedulePumpLocked() }
        }
    }

    private fun schedulePumpLocked() {
        if (txInFlight) return
        val delay = if (txCommandCount > 0 && txCommandCount % NovoConstants.TX_PAUSE_EVERY == 0) {
            NovoConstants.TX_PAUSE_MS
        } else {
            NovoConstants.TX_INTERVAL_MS
        }
        mainHandler.postDelayed({
            if (stop) return@postDelayed
            synchronized(stateLock) { pumpLocked() }
        }, delay)
    }

    private fun pumpLocked() {
        if (txInFlight) return
        val gatt = mBluetoothGatt ?: return
        val characteristic = writeCharacteristic ?: return
        val frame = pendingRetryFrame ?: txQueue.removeFirstOrNull()?.let { command ->
            if (command.useDefaultPassword) {
                NovoCrypto.encrypt(command.payload)
            } else {
                NovoCrypto.encrypt(command.payload, passwordLow, passwordHigh)
            }
        } ?: return
        pendingRetryFrame = null
        if (frame.isEmpty()) {
            Log.w(TAG, "encryption returned an empty frame")
            return
        }
        txCommandCount++
        txInFlight = true
        inFlightFrame = frame
        characteristic.value = frame
        if (!runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)) {
            Log.w(TAG, "writeCharacteristic returned false")
            onWriteFailed(gatt, frame)
        }
    }

    private fun onWriteFailed(gatt: BluetoothGatt, frame: ByteArray) {
        val exhausted = synchronized(stateLock) {
            inFlightFrame = null
            txInFlight = false
            if (writeRetries < MAX_WRITE_RETRIES) {
                writeRetries++
                pendingRetryFrame = frame
                false
            } else {
                true
            }
        }
        if (exhausted) {
            resetFailedSession()
            return
        }
        val delay = WRITE_RETRY_DELAY_MS * writeRetries
        mainHandler.postDelayed({
            if (stop || mBluetoothGatt !== gatt) return@postDelayed
            synchronized(stateLock) { pumpLocked() }
        }, delay)
    }

    private fun resetFailedSession() {
        synchronized(stateLock) {
            txQueue.clear()
            pendingRetryFrame = null
            inFlightFrame = null
            txInFlight = false
            writeRetries = 0
        }
        phase = Phase.ERROR
        runCatching { closeGattTransport() }
        scheduleRetry()
        UiRefreshBus.requestStatusRefresh()
    }

    private fun handlePayload(uuid: UUID, data: ByteArray) {
        when (uuid) {
            MEASUREMENT -> NovoParser.parseMeasurement(data)?.let { measurement ->
                latestMeasurement = measurement
                if (measurement.status != NovoConstants.STATUS_OK) {
                    Log.w(TAG, "measurement status=${measurement.status}")
                }
                UiRefreshBus.requestDataRefresh()
            }
            UUID.fromString(NovoConstants.CHAR_CGM_FEATURE) -> NovoParser.parseDeviceInfo(data)?.let {
                deviceInfo = it
                firmwareInfo = it.version
            }
            UUID.fromString(NovoConstants.CHAR_CGM_STATUS) -> NovoParser.parseFlashInquiry(data)?.let {
                handleFlashInquiry(it)
            }
            UUID.fromString(NovoConstants.CHAR_CGM_SESSION_START_TIME) ->
                NovoParser.parseSessionTime(data)?.let { firmwareInfo = "$firmwareInfo session=${it.startTimeU32Le}" }
            else -> NovoParser.parseAck(data)?.let { ack ->
                Log.d(TAG, "ack flag=${ack.flag} opcode=${ack.tmpOpCode} result=${ack.result}")
            }
        }
    }

    private fun handleFlashInquiry(inquiry: NovoFlashInquiry) {
        Applic.app?.let { NovoRegistry.setLastFlashCount(it, inquiry.count) }
        historyEndMs = System.currentTimeMillis()
        val count = inquiry.count
        if (count <= 0) return
        var offset = 0
        while (offset < count) {
            val chunk = minOf(NovoConstants.HISTORY_PAGE_SIZE, count - offset)
            enqueue(NovoParser.getFlashData(offset, chunk), useDefaultPassword = false)
            offset += chunk
        }
    }

    override fun requestHistoryBackfill(): Boolean {
        if (phase != Phase.STREAMING) return false
        enqueue(NovoParser.inquireFlashData(0), useDefaultPassword = false)
        return true
    }

    override fun softDisconnect() {}

    override fun softReconnect() {}

    override fun terminateManagedSensor(wipeData: Boolean) {
        stop = true
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(stateLock) {
            txQueue.clear()
            pendingRetryFrame = null
            inFlightFrame = null
            txInFlight = false
            writeRetries = 0
        }
        runCatching { closeGattTransport() }
        if (wipeData) Applic.app?.let { NovoRegistry.clear(it) }
    }

    override fun onBluetoothAdapterUnavailable() {
        phase = Phase.IDLE
        connectScheduled = false
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        synchronized(stateLock) {
            txQueue.clear()
            pendingRetryFrame = null
            inFlightFrame = null
            txInFlight = false
            writeRetries = 0
        }
        runCatching { closeGattTransport() }
    }
}
