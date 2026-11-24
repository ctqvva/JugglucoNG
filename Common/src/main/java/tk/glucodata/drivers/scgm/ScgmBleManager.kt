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
    @Volatile private var nextBrokePosition = 0
    @Volatile private var batteryMv = 0
    @Volatile private var firmwareInfo = ""
    @Volatile private var advDeviceCode = ""
    @Volatile private var isReadingHistory = false
    @Volatile private var pendingHistorySamples = 0

    private val stateLock = Any()
    private val readingLock = Any()

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
        val code = readAdvertisedDeviceCode(result)
        if (code != null) advDeviceCode = code
        return true
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

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        if (phase != Phase.IDLE && phase != Phase.ERROR) {
            if (mBluetoothGatt != null || mActiveBluetoothDevice != null) return true
        }
        phase = Phase.CONNECTING
        val scheduled = super.connectDevice(delayMillis)
        if (!scheduled && phase == Phase.CONNECTING && mActiveBluetoothDevice != null) phase = Phase.IDLE
        return scheduled
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        noteFirstGattCallback("onConnectionStateChange", gatt)
        if (!acceptConnectionAttemptCallback(gatt, newState)) {
            if (newState == BluetoothProfile.STATE_CONNECTED) runCatching { gatt.disconnect() }
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
                phase = Phase.DISCOVERING
                runCatching { gatt.requestMtu(ScgmConstants.REQUESTED_MTU) }
                mainHandler.postDelayed({
                    if (!stop && phase == Phase.DISCOVERING && mBluetoothGatt === gatt) {
                        if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
                            phase = Phase.ERROR
                            runCatching { gatt.disconnect() }
                        }
                    }
                }, DISCOVERY_DELAY_MS)
                UiRefreshBus.requestStatusRefresh()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                synchronized(stateLock) {
                    txQueue.clear()
                    txInFlight = false
                }
                writeCharacteristic = null
                notifyCharacteristic = null
                isReadingHistory = false
                pendingHistorySamples = 0
                phase = Phase.IDLE
                runCatching { gatt.close() }
                if (mBluetoothGatt === gatt) mBluetoothGatt = null
                UiRefreshBus.requestStatusRefresh()
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (stop || mBluetoothGatt !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            phase = Phase.ERROR
            runCatching { gatt.disconnect() }
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
            runCatching { gatt.disconnect() }
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
            runCatching { gatt.disconnect() }
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
            runCatching { gatt.disconnect() }
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
            runCatching { gatt.disconnect() }
            return
        }
        mainHandler.post {
            if (stop || mBluetoothGatt !== gatt) return@post
            synchronized(stateLock) {
                txInFlight = false
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
        val frame = txQueue.removeFirstOrNull() ?: return
        txInFlight = true
        characteristic.value = frame
        if (!runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)) {
            txInFlight = false
            Log.w(TAG, "writeCharacteristic returned false")
        }
    }

    private fun handleFrame(data: ByteArray) {
        when (ScgmParser.command(data)) {
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
        if (context != null) {
            ScgmRegistry.setLastPid(context, lastPid)
            ScgmRegistry.setLastTimeMs(context, lastTimeMs)
        }
        publishLive(sample.adc, now)
    }

    private fun handleHistoryInquiry(data: ByteArray) {
        val inquiry = ScgmParser.parseHistoryInquiry(data) ?: return
        if (isReadingHistory) return
        val shouldRead = ScgmParser.shouldRead(inquiry.dataCount, nextBrokePosition, inquiry.latestIndex)
        val start = if (lastPid > 0) lastPid + 1 else maxOf(nextBrokePosition, 1)
        if (shouldRead <= 0 || start > inquiry.latestIndex) return
        isReadingHistory = true
        pendingHistorySamples = 0
        var offset = start
        var remaining = shouldRead
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
        val referenceTime = lastTimeMs.takeIf { it > 0L } ?: now
        val referencePid = lastPid.takeIf { it > 0L }
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
        if (phase != Phase.STREAMING) return false
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
            txInFlight = false
        }
        runCatching { mBluetoothGatt?.disconnect() }
        if (wipeData) Applic.app?.let { ScgmRegistry.clear(it) }
    }

    override fun onBluetoothAdapterUnavailable() {
        phase = Phase.IDLE
        synchronized(stateLock) {
            txQueue.clear()
            txInFlight = false
        }
    }
}
