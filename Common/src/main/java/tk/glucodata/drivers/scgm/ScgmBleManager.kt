package tk.glucodata.drivers.scgm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.LiveReadingLanes
import tk.glucodata.Log
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

@SuppressLint("MissingPermission")
class ScgmBleManager(
    sensorId: String,
    dataptr: Long,
) : SuperGattCallback(sensorId, dataptr, ScgmConstants.SENSOR_GEN), ScgmDriver {

    companion object {
        private const val TAG = "ScgmBLE"
        private val CCCD = UUID.fromString(ScgmConstants.CCCD_UUID)
        private val FALLBACK_SERVICE = UUID.fromString(ScgmConstants.FALLBACK_SERVICE_UUID)
        private val FALLBACK_NOTIFY = UUID.fromString(ScgmConstants.FALLBACK_NOTIFY_UUID)
        private const val DISCOVERY_DELAY_MS = 250L
        private const val WRITE_RETRY_DELAY_MS = 300L
        private const val MAX_WRITE_RETRIES = 2
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val SCAN_WAIT_TIMEOUT_MS = 30_000L
        private const val MAX_SILENT_CONNECT_ATTEMPTS = 2
        private const val CONNECT_BACKOFF_MS = 60_000L
    }

    enum class Phase { IDLE, CONNECTING, DISCOVERING, SUBSCRIBING, STREAMING, ERROR }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    override var viewMode: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val txQueue = ArrayDeque<ByteArray>()
    @Volatile private var txInFlight = false
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile private var calibration: ScgmCalibration = ScgmCalibration(
        ScgmConstants.DEFAULT_K,
        ScgmConstants.DEFAULT_B,
    )
    @Volatile private var useAtMs = 0L
    @Volatile private var bootAtSeconds = 0L
    @Volatile private var lastPid = 0
    @Volatile private var lastTimeMs = 0L
    @Volatile private var liveAnchorPid = 0
    @Volatile private var liveAnchorTimeMs = 0L
    @Volatile private var nextBrokePosition = 0
    @Volatile private var batteryMv = 0
    @Volatile private var firmwareInfo = ""
    @Volatile private var advDeviceCode = ""
    @Volatile private var deviceVerified = false
    @Volatile private var isReadingHistory = false
    @Volatile private var pendingHistorySamples = 0
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
    private val readingLock = Any()
    private var pendingRetryFrame: ByteArray? = null
    private var inFlightFrame: ByteArray? = null
    private var writeRetries = 0

    init {
        Applic.app?.let(::applyRegistrySettings)
    }

    fun applyRegistrySettings(context: Context) {
        calibration = ScgmRegistry.getCalibration(context)
        useAtMs = ScgmRegistry.getUseAtMs(context)
        bootAtSeconds = ScgmRegistry.getBootAtSeconds(context)
        lastPid = ScgmRegistry.getLastPid(context)
        lastTimeMs = ScgmRegistry.getLastTimeMs(context)
        nextBrokePosition = ScgmRegistry.getNextBrokePosition(context)
        firmwareInfo = ScgmRegistry.getFirmware(context)
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

    override fun batteryMillivolts(): Int = batteryMv

    override fun matchesManagedSensorId(sensorId: String?): Boolean {
        if (sensorId.isNullOrBlank()) return false
        return sensorId.equals(SerialNumber, ignoreCase = true)
    }

    override fun getService(): UUID? = null

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val known = mActiveDeviceAddress?.takeIf { it.isNotBlank() }
        if (known != null && address != null && address.equals(known, ignoreCase = true)) return true
        return isScgmName(deviceName)
    }

    override fun matchScanResult(result: ScanResult): Boolean {
        val name = result.device?.name ?: result.scanRecord?.deviceName
        if (!isScgmName(name)) return false
        val code = readAdvertisedDeviceCode(result) ?: return false
        if (!deviceCodeMatches(code)) return false
        advDeviceCode = code
        return true
    }

    private fun deviceCodeMatches(candidate: String): Boolean {
        val expected = expectedDeviceCode()
        if (expected.isEmpty()) return true
        return normalizedDeviceCode(candidate) == normalizedDeviceCode(expected)
    }

    private fun normalizedDeviceCode(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length % 2 == 0 && trimmed.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
            return trimmed.lowercase(Locale.ROOT)
        }
        return ScgmParser.hex(trimmed.toByteArray(Charsets.ISO_8859_1)).lowercase(Locale.ROOT)
    }

    private fun expectedDeviceCode(): String {
        val stored = Applic.app?.let { ScgmRegistry.getDeviceCode(it) }.orEmpty().trim()
        val code = stored.ifEmpty {
            SerialNumber?.trim()?.takeIf {
                it.startsWith(ScgmConstants.SENSOR_ID_PREFIX, ignoreCase = true)
            }?.substring(ScgmConstants.SENSOR_ID_PREFIX.length).orEmpty()
        }
        return code.lowercase(Locale.ROOT)
    }

    private fun isScgmName(name: String?): Boolean =
        name?.trim()?.startsWith(ScgmConstants.SCAN_NAME_PREFIX, ignoreCase = true) ?: false

    private fun readAdvertisedDeviceCode(result: ScanResult): String? {
        val data = result.scanRecord?.manufacturerSpecificData ?: return null
        for (index in 0 until data.size()) {
            val bytes = data.valueAt(index) ?: continue
            if (bytes.isNotEmpty()) return ScgmParser.hex(bytes).lowercase(Locale.ROOT)
        }
        return null
    }

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
        connectScheduled = false
        if (silentConnectAttempts >= MAX_SILENT_CONNECT_ATTEMPTS) {
            phase = Phase.ERROR
            mActiveBluetoothDevice = null
            mActiveDeviceAddress = null
            Applic.app?.let { ScgmRegistry.setLastBtAddress(it, "") }
            scheduleRetry()
        } else {
            phase = Phase.CONNECTING
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
                    Applic.app?.let { ScgmRegistry.setLastBtAddress(it, address) }
                }
                connectTime = System.currentTimeMillis()
                silentConnectAttempts = 0
                nextConnectAllowedAtMs = 0L
                connectScheduled = false
                mainHandler.removeCallbacks(scanTimeoutRunnable)
                deviceVerified = false
                phase = Phase.DISCOVERING
                runCatching { gatt.requestMtu(ScgmConstants.REQUESTED_MTU) }
                mainHandler.postDelayed({
                    if (!stop && phase == Phase.DISCOVERING && mBluetoothGatt === gatt) {
                        if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
                            phase = Phase.ERROR
                            runCatching { closeGattTransport() }
                        }
                    }
                }, DISCOVERY_DELAY_MS)
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
                deviceVerified = false
                isReadingHistory = false
                pendingHistorySamples = 0
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
        var write: BluetoothGattCharacteristic? = null
        var notify: BluetoothGattCharacteristic? = null
        for (service in gatt.services) {
            for (characteristic in service.characteristics) {
                val props = characteristic.properties
                if (write == null && props and (
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                    ) != 0
                ) {
                    write = characteristic
                }
                if (notify == null && props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    notify = characteristic
                }
            }
        }
        if (notify == null) {
            notify = gatt.getService(FALLBACK_SERVICE)?.getCharacteristic(FALLBACK_NOTIFY)
        }
        if (write == null || notify == null) {
            Log.w(TAG, "Required SCGM characteristics not found (write=$write notify=$notify)")
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
        enqueue(ScgmParser.activation())
        enqueue(ScgmParser.terminalInfo())
        enqueue(ScgmParser.currentResult())
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (stop || mBluetoothGatt !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "SCGM write failed status=$status")
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
                pumpLocked()
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
        handleFrame(data)
    }

    private fun enqueue(frame: ByteArray) {
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + ScgmConstants.WRITE_CHUNK_SIZE, frame.size)
            chunks += frame.copyOfRange(offset, end)
            offset = end
        }
        synchronized(stateLock) {
            txQueue.addAll(chunks)
        }
        mainHandler.post {
            if (stop) return@post
            synchronized(stateLock) { pumpLocked() }
        }
    }

    private fun pumpLocked() {
        if (txInFlight) return
        val gatt = mBluetoothGatt ?: return
        val characteristic = writeCharacteristic ?: return
        val frame = pendingRetryFrame ?: txQueue.removeFirstOrNull() ?: return
        pendingRetryFrame = null
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
        isReadingHistory = false
        pendingHistorySamples = 0
        phase = Phase.ERROR
        runCatching { closeGattTransport() }
        scheduleRetry()
        UiRefreshBus.requestStatusRefresh()
    }

    private fun handleFrame(data: ByteArray) {
        val command = ScgmParser.command(data)
        if (command != ScgmConstants.CMD_TERMINAL_INFO && !deviceVerified) return
        when (command) {
            ScgmConstants.CMD_HEARTBEAT -> ScgmParser.parseHeartbeat(data)
            ScgmConstants.CMD_CURRENT_RESULT -> handleRealtime(data)
            ScgmConstants.CMD_QUERY_HISTORY -> handleHistoryInquiry(data)
            ScgmConstants.CMD_HISTORY_PAGE -> handleHistoryPage(data)
            ScgmConstants.CMD_TERMINAL_INFO -> handleTerminalInfo(data)
            else -> Unit
        }
    }

    private fun handleTerminalInfo(data: ByteArray) {
        val info = ScgmParser.parseTerminalInfo(data) ?: return
        val context = Applic.app
        if (!deviceCodeMatches(info.deviceCode)) {
            Log.w(TAG, "SCGM terminal info device code mismatch; rejecting sensor")
            phase = Phase.ERROR
            deviceVerified = false
            runCatching { closeGattTransport() }
            return
        }
        deviceVerified = true
        val now = System.currentTimeMillis()
        if (bootAtSeconds == 0L) {
            bootAtSeconds = ScgmParser.bootAtSeconds(now, info.packageCount)
            if (context != null) ScgmRegistry.setBootAtSeconds(context, bootAtSeconds)
        }
        firmwareInfo = listOf(info.firmware, "runtime=${info.runTimeMinutes}", "packages=${info.packageCount}")
            .filter { it.isNotBlank() }.joinToString(" ")
        if (context != null) ScgmRegistry.setFirmware(context, firmwareInfo)
        if (lastPid == 0 && bootAtSeconds > 0L) {
            lastPid = ScgmParser.lastPidFromBoot(now, bootAtSeconds)
            if (context != null) ScgmRegistry.setLastPid(context, lastPid)
        }
    }

    private fun handleRealtime(data: ByteArray) {
        val now = System.currentTimeMillis()
        val sample = ScgmParser.parseRealtime(data, now) ?: return
        batteryMv = sample.batteryMv
        val context = Applic.app
        if (sample.index < lastPid) {
            enqueue(ScgmParser.queryHistory())
            return
        }
        if (lastPid > 0 && sample.index > lastPid + 1) {
            nextBrokePosition = lastPid + 1
            if (context != null) ScgmRegistry.setNextBrokePosition(context, nextBrokePosition)
            enqueue(ScgmParser.queryHistory())
        }
        lastPid = sample.index
        lastTimeMs = now
        liveAnchorPid = sample.index
        liveAnchorTimeMs = now
        if (context != null) {
            ScgmRegistry.setLastPid(context, lastPid)
            ScgmRegistry.setLastTimeMs(context, lastTimeMs)
        }
        publishLive(sample.adc, now)
    }

    private fun handleHistoryInquiry(data: ByteArray) {
        val inquiry = ScgmParser.parseHistoryInquiry(data) ?: return
        if (isReadingHistory) return
        val start = when {
            nextBrokePosition > 0 -> nextBrokePosition
            lastPid > 0 -> lastPid + 1
            else -> 1
        }
        if (start > inquiry.latestIndex) return
        val gapCount = inquiry.latestIndex - start + 1
        val shouldRead = ScgmParser.shouldRead(inquiry.dataCount, nextBrokePosition, inquiry.latestIndex)
        val remainingCount = if (shouldRead > 0) shouldRead else minOf(gapCount, inquiry.dataCount.coerceAtLeast(1))
        isReadingHistory = true
        pendingHistorySamples = 0
        var offset = start
        var remaining = remainingCount
        while (offset <= inquiry.latestIndex && remaining > 0) {
            val count = minOf(ScgmConstants.HISTORY_PAGE_SIZE, remaining, inquiry.latestIndex - offset + 1)
            enqueue(ScgmParser.historyPage(offset, count))
            pendingHistorySamples += count
            offset += count
            remaining -= count
        }
    }

    private fun handleHistoryPage(data: ByteArray) {
        val page = ScgmParser.parseHistoryPage(data) ?: return
        val now = System.currentTimeMillis()
        val referenceTime = liveAnchorTimeMs.takeIf { it > 0L }
            ?: lastTimeMs.takeIf { it > 0L } ?: now
        val referencePid = liveAnchorPid.takeIf { it > 0 }
            ?: lastPid.takeIf { it > 0 }
            ?: ScgmParser.lastPidFromBoot(now, bootAtSeconds)
        val samples = page.adc.mapIndexed { position, adc ->
            val index = page.startIndex + 1 + position
            ScgmParser.historyTimeMs(index, referencePid, referenceTime) to adc
        }
        pendingHistorySamples = (pendingHistorySamples - samples.size).coerceAtLeast(0)
        storeBatch(samples)
        val lastIndex = page.startIndex + page.count
        if (lastIndex >= nextBrokePosition) {
            nextBrokePosition = lastIndex + 1
            Applic.app?.let { ScgmRegistry.setNextBrokePosition(it, nextBrokePosition) }
        }
        if (pendingHistorySamples == 0) isReadingHistory = false
    }

    private fun publishLive(adc: Int, now: Long) {
        if (!deviceVerified) return
        val mmol = ScgmParser.glucoseMmol(adc, calibration)
        if (mmol <= 0.0) return
        val mgdl = (mmol * ScgmConstants.MGDL_PER_MMOL).toFloat()
        if (mgdl !in 1f..600f) return
        synchronized(readingLock) {
            if (stop) return
            val sensorId = SerialNumber ?: return
            markLocalReadingAccepted(now)
            HistorySyncAccess.storeCurrentReadingAsync(now, mgdl, 0f, 0f, sensorId)
            VirtualGlucoseSensorBridge.mirrorToNative(
                sensorId,
                listOf(VirtualGlucoseSensorBridge.Reading(now, mgdl)),
                "SCGM",
                ManagedSensorUiFamily.SCGM,
            )
            SuperGattCallback.processExternalCurrentReading(
                sensorId,
                LiveReadingLanes.stock(ScgmParser.displayValue(mmol, Applic.unit), Float.NaN),
                0f,
                now,
                ScgmConstants.SENSOR_GEN,
            )
        }
        UiRefreshBus.requestDataRefresh()
    }

    private fun storeBatch(samples: List<Pair<Long, Int>>) {
        if (!deviceVerified) return
        val sensorId = SerialNumber ?: return
        val decoded = samples.mapNotNull { (timestamp, adc) ->
            val mmol = ScgmParser.glucoseMmol(adc, calibration)
            if (mmol <= 0.0) null else timestamp to (mmol * ScgmConstants.MGDL_PER_MMOL).toFloat()
        }
        if (decoded.isEmpty()) return
        synchronized(readingLock) {
            if (stop) return
            val timestamps = LongArray(decoded.size) { decoded[it].first }
            val values = FloatArray(decoded.size) { decoded[it].second }
            HistorySyncAccess.storeSensorHistoryBatchAsync(sensorId, timestamps, values, FloatArray(decoded.size))
            VirtualGlucoseSensorBridge.mirrorToNative(
                sensorId,
                decoded.map { VirtualGlucoseSensorBridge.Reading(it.first, it.second) },
                "SCGM history",
                ManagedSensorUiFamily.SCGM,
            )
        }
        UiRefreshBus.requestDataRefresh()
    }

    override fun requestHistoryBackfill(): Boolean {
        if (phase != Phase.STREAMING || !deviceVerified) return false
        enqueue(ScgmParser.queryHistory())
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
        deviceVerified = false
        isReadingHistory = false
        pendingHistorySamples = 0
        runCatching { closeGattTransport() }
        if (wipeData) Applic.app?.let { ScgmRegistry.clear(it) }
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
        deviceVerified = false
        isReadingHistory = false
        pendingHistorySamples = 0
        runCatching { closeGattTransport() }
    }
}
