package tk.glucodata.drivers.bioe

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
import tk.glucodata.HistorySyncAccess
import tk.glucodata.LiveReadingLanes
import tk.glucodata.Log
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

@SuppressLint("MissingPermission")
class BioeBleManager(
    sensorId: String,
    dataptr: Long,
) : SuperGattCallback(sensorId, dataptr, BioeConstants.SENSOR_GEN), BioeDriver {

    companion object {
        private const val TAG = "BioeBLE"
        private val CCCD = UUID.fromString(BioeConstants.CCCD_UUID)
        private val FALLBACK_SERVICE = UUID.fromString(BioeConstants.FALLBACK_SERVICE_UUID)
        private val FALLBACK_NOTIFY = UUID.fromString(BioeConstants.FALLBACK_NOTIFY_UUID)
        private const val DISCOVERY_DELAY_MS = 250L
        private const val MAX_LOCAL_PACKAGES = 32
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

    @Volatile private var calibration: BioeCalibration = BioeCalibration(
        BioeConstants.DEFAULT_K,
        BioeConstants.DEFAULT_B,
    )
    @Volatile private var useAtMs = 0L
    @Volatile private var latestFlashIndex = 0
    @Volatile private var totalLocalCount = 0
    @Volatile private var firmwareInfo = ""

    private val stateLock = Any()
    private val readingLock = Any()

    init {
        Applic.app?.let(::applyRegistrySettings)
    }

    fun applyRegistrySettings(context: Context) {
        calibration = BioeRegistry.getCalibration(context)
        useAtMs = BioeRegistry.getUseAtMs(context)
        if (BioeRegistry.getDeviceCode(context).isNotBlank()) {
            latestFlashIndex = BioeRegistry.getLastFlashIndex(context)
        }
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

    override fun getService(): UUID? = null

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val known = mActiveDeviceAddress?.takeIf { it.isNotBlank() }
        if (known != null && address != null && address.equals(known, ignoreCase = true)) return true
        return matchesBioeName(deviceName)
    }

    override fun matchScanResult(result: ScanResult): Boolean {
        val name = result.device?.name ?: result.scanRecord?.deviceName
        return matchesBioeName(name)
    }

    private fun matchesBioeName(name: String?): Boolean =
        name?.trim()?.startsWith(BioeConstants.SCAN_NAME_PREFIX, ignoreCase = true) ?: false

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
                    Applic.app?.let { BioeRegistry.setLastBtAddress(it, address) }
                }
                connectTime = System.currentTimeMillis()
                phase = Phase.DISCOVERING
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
            Log.w(TAG, "Required BIOE characteristics not found (write=$write notify=$notify)")
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
        enqueue(BioeParser.getVersion())
        enqueue(BioeParser.getSysPara())
        requestHistoryBackfill()
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (stop || mBluetoothGatt !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "BIOE write failed status=$status")
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
        synchronized(stateLock) {
            txQueue.addLast(frame)
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
        if (BioeParser.isHeartbeat(data)) return
        when (BioeParser.command(data)) {
            BioeConstants.CMD_GET_DATA -> handleRealtime(data)
            BioeConstants.CMD_INQ_LOCAL_DATA -> handleLocalInquiry(data)
            BioeConstants.CMD_GET_LOCAL_DATA -> handleLocalBatch(data)
            BioeConstants.CMD_INQ_FLASH_DATA -> handleFlashInquiry(data)
            BioeConstants.CMD_GET_FLASH_DATA -> handleFlashBatch(data)
            BioeConstants.CMD_GET_VER -> BioeParser.parseVersion(data)?.let {
                firmwareInfo = "%06X".format(it.hardFirmwareVersion)
            }
            BioeConstants.CMD_GET_SYSPARA -> BioeParser.parseSysPara(data)?.let {
                firmwareInfo = "$firmwareInfo period=${it.samplePeriod} type=${it.dataType} mode=${it.workMode}".trim()
            }
            else -> Unit
        }
    }

    private fun handleRealtime(data: ByteArray) {
        val now = System.currentTimeMillis()
        val sample = BioeParser.parseRealtime(data, now) ?: return
        publishLive(sample.adc, now)
    }

    private fun handleFlashInquiry(data: ByteArray) {
        val inquiry = BioeParser.parseFlashInquiry(data) ?: return
        val count = inquiry.count
        Applic.app?.let { BioeRegistry.setLastFlashCount(it, count) }
        if (count <= 0) {
            enqueue(BioeParser.inquireLocalData())
            return
        }
        val start = if (latestFlashIndex <= 0) {
            (count - BioeConstants.MAX_FLASH_REQUEST).coerceAtLeast(0)
        } else {
            minOf(latestFlashIndex + 1, count - 1)
        }
        if (start >= count) return
        var offset = start
        while (offset < count) {
            val chunk = minOf(BioeConstants.MAX_FLASH_REQUEST, count - offset)
            enqueue(BioeParser.getFlashData(offset, chunk))
            offset += chunk
        }
    }

    private fun handleFlashBatch(data: ByteArray) {
        val batch = BioeParser.parseFlashBatch(data) ?: return
        val context = Applic.app
        val now = System.currentTimeMillis()
        var previous = if (batch.index > 0) BioeConstants.ADC_FALLBACK else null
        val samples = batch.adc.mapIndexed { position, word ->
            val adc = BioeParser.sanitizeHistoryAdc(word, previous)
            previous = adc
            val index = batch.index + position
            BioeParser.flashTimeMs(index, maxOf(latestFlashIndex, batch.index + batch.count - 1), now, useAtMs) to adc
        }
        storeBatch(samples)
        val nextIndex = batch.index + batch.count
        if (nextIndex > latestFlashIndex) {
            latestFlashIndex = nextIndex
            if (context != null) BioeRegistry.setLastFlashIndex(context, nextIndex)
        }
    }

    private fun handleLocalInquiry(data: ByteArray) {
        val inquiry = BioeParser.parseLocalDataInquiry(data) ?: return
        totalLocalCount = inquiry.dataCount
        if (!inquiry.hasPackage || inquiry.packageCount <= 0) return
        for (packageIndex in 1..minOf(inquiry.packageCount, MAX_LOCAL_PACKAGES)) {
            enqueue(BioeParser.getLocalData(packageIndex))
        }
    }

    private fun handleLocalBatch(data: ByteArray) {
        val batch = BioeParser.parseLocalDataBatch(data) ?: return
        val now = System.currentTimeMillis()
        var previous: Int? = null
        val samples = batch.adc.mapIndexed { position, word ->
            val adc = BioeParser.sanitizeHistoryAdc(word, previous)
            previous = adc
            val index = batch.sampleIndex(position)
            BioeParser.sampleTimeMs(index, totalLocalCount, now, useAtMs) to adc
        }
        storeBatch(samples)
    }

    private fun publishLive(adc: Int, now: Long) {
        val mmol = BioeParser.glucoseMmol(adc, calibration)
        if (mmol <= 0.0) return
        val mgdl = (mmol * BioeConstants.MGDL_PER_MMOL).toFloat()
        if (mgdl !in 1f..600f) return
        synchronized(readingLock) {
            if (stop) return
            val sensorId = SerialNumber ?: return
            markLocalReadingAccepted(now)
            HistorySyncAccess.storeCurrentReadingAsync(now, mgdl, 0f, 0f, sensorId)
            VirtualGlucoseSensorBridge.mirrorToNative(
                sensorId,
                listOf(VirtualGlucoseSensorBridge.Reading(now, mgdl)),
                "BioE",
                ManagedSensorUiFamily.BIOE,
            )
            SuperGattCallback.processExternalCurrentReading(
                sensorId,
                LiveReadingLanes.stock(BioeParser.displayValue(mmol, Applic.unit), Float.NaN),
                0f,
                now,
                BioeConstants.SENSOR_GEN,
            )
        }
        UiRefreshBus.requestDataRefresh()
    }

    private fun storeBatch(samples: List<Pair<Long, Int>>) {
        val sensorId = SerialNumber ?: return
        val decoded = samples.mapNotNull { (timestamp, adc) ->
            val mmol = BioeParser.glucoseMmol(adc, calibration)
            if (mmol <= 0.0) null else timestamp to (mmol * BioeConstants.MGDL_PER_MMOL).toFloat()
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
                "BioE history",
                ManagedSensorUiFamily.BIOE,
            )
        }
        UiRefreshBus.requestDataRefresh()
    }

    override fun requestHistoryBackfill(): Boolean {
        if (phase != Phase.STREAMING) return false
        enqueue(BioeParser.inquireFlashData(maxOf(latestFlashIndex - 1, 0)))
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
        if (wipeData) Applic.app?.let { BioeRegistry.clear(it) }
    }

    override fun onBluetoothAdapterUnavailable() {
        phase = Phase.IDLE
        synchronized(stateLock) {
            txQueue.clear()
            txInFlight = false
        }
    }
}
