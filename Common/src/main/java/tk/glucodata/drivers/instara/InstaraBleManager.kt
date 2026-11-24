package tk.glucodata.drivers.instara

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.HandlerThread
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.LiveReadingLanes
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

@SuppressLint("MissingPermission")
class InstaraBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), InstaraDriver, InstaraPublishSink {

    companion object {
        const val SENSOR_GEN = 0
        private const val TAG = "InstaraBLE"
        private const val DISCOVERY_DELAY_MS = 250L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val KEEPALIVE_INTERVAL_MS = 25_000L
        private const val KEEPALIVE_IDLE_MS = 20_000L
        private const val MAX_FAILED_ATTEMPTS = 5
    }

    override var viewMode: Int = 0

    private val thread = HandlerThread("InstaraBle").apply { start() }
    private val handler = Handler(thread.looper)
    private val readingLock = Any()
    private val router = InstaraActionRouter(this)
    private val txLock = Any()
    private val txQueue = ArrayDeque<InstaraSession.Action.Send>()
    private var txInFlight = false
    private var session: InstaraSession? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var batteryPercent = -1
    private var connectionStatus = ""
    private var authRejected = false
    private var deviceRejected = false
    private var linkUp = false
    private var paused = false
    private var lastNotifyAt = 0L
    private var failedAttempts = 0
    private val reconnectRunnable = Runnable {
        if (!stop && !paused && !deviceRejected && !authRejected &&
            failedAttempts < MAX_FAILED_ATTEMPTS && mBluetoothGatt == null
        ) {
            connectDevice(0L)
        }
    }
    private val keepaliveRunnable: Runnable = Runnable {
        if (stop || paused || mBluetoothGatt == null) return@Runnable
        if (lastNotifyAt <= 0L ||
            android.os.SystemClock.elapsedRealtime() - lastNotifyAt > KEEPALIVE_IDLE_MS
        ) {
            enqueue(InstaraSession.Action.Send(InstaraProtocol.frame(InstaraProtocol.CMD_BATTERY)))
        }
        handler.postDelayed(keepaliveRunnable, KEEPALIVE_INTERVAL_MS)
    }

    override fun getSerialNumber(): String = SerialNumber
    override fun getDeviceAddress(): String = mActiveDeviceAddress ?: ""
    override fun getConnectionStatus(): String = connectionStatus
    override fun isConnected(): Boolean = linkUp
    override fun getBatteryPercent(): Int = batteryPercent
    fun isAuthRejected(): Boolean = authRejected
    fun isDeviceRejected(): Boolean = deviceRejected
    fun isAuthorized(): Boolean = session?.isAuthorized == true

    override fun getSensorStartMs(): Long =
        Applic.app?.let { InstaraPersistence.getSensorStartMs(it) } ?: 0L

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        sensorId?.trim()?.equals(SerialNumber, ignoreCase = true) == true

    override fun getService(): UUID? = InstaraProtocol.SERVICE_UUID

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val known = mActiveDeviceAddress?.takeIf { it.isNotBlank() }
            ?: Applic.app?.let { InstaraPersistence.getLastBtAddress(it) }
        return known?.equals(address, ignoreCase = true) == true
    }

    override fun matchScanResult(result: ScanResult): Boolean =
        result.scanRecord?.serviceUuids?.any { it.uuid == InstaraProtocol.SERVICE_UUID } == true

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        if (mBluetoothGatt != null) {
            Log.d(TAG, "Skipping duplicate connect")
            return true
        }
        handler.removeCallbacks(reconnectRunnable)
        return super.connectDevice(delayMillis)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        noteFirstGattCallback("onConnectionStateChange", gatt)
        if (!acceptConnectionAttemptCallback(gatt, newState)) {
            if (newState == BluetoothProfile.STATE_CONNECTED) runCatching { gatt.disconnect() }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { gatt.close() }
            return
        }
        super.onConnectionStateChange(gatt, status, newState)
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                linkUp = true
                authRejected = false
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                gatt.device?.address?.let { address ->
                    mActiveDeviceAddress = address
                    Applic.app?.let { InstaraPersistence.setLastBtAddress(it, address) }
                }
                connectionStatus = "Connected"
                handler.removeCallbacks(reconnectRunnable)
                handler.removeCallbacks(keepaliveRunnable)
                handler.postDelayed(keepaliveRunnable, KEEPALIVE_INTERVAL_MS)
                handler.postDelayed({
                    if (!stop && mBluetoothGatt === gatt) {
                        if (!gatt.discoverServices()) {
                            Log.e(TAG, "discoverServices failed")
                            runCatching { gatt.disconnect() }
                        }
                    }
                }, DISCOVERY_DELAY_MS)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                val wasUp = linkUp
                linkUp = false
                connectionStatus = "Disconnected"
                session = null
                writeChar = null
                lastNotifyAt = 0L
                handler.removeCallbacksAndMessages(null)
                synchronized(txLock) {
                    txQueue.clear()
                    txInFlight = false
                }
                runCatching { gatt.close() }
                if (mBluetoothGatt === gatt) mBluetoothGatt = null
                if (!wasUp) {
                    failedAttempts++
                    if (failedAttempts >= MAX_FAILED_ATTEMPTS) deviceRejected = true
                }
                handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
            }
        }
        UiRefreshBus.requestStatusRefresh()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (stop || mBluetoothGatt !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            runCatching { gatt.disconnect() }
            return
        }
        val service = gatt.getService(InstaraProtocol.SERVICE_UUID)
        val notify = service?.getCharacteristic(InstaraProtocol.NOTIFY_UUID)
        val write = service?.getCharacteristic(InstaraProtocol.WRITE_UUID)
        if (notify == null || write == null) {
            deviceRejected = true
            Log.e(TAG, "Instara service or characteristics missing")
            runCatching { gatt.disconnect() }
            return
        }
        writeChar = write
        if (!enableNotification(gatt, notify)) {
            Log.e(TAG, "Failed to enable Instara notifications")
            runCatching { gatt.disconnect() }
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (stop || mBluetoothGatt !== gatt) return
        if (descriptor.uuid != InstaraProtocol.CCCD_UUID) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            runCatching { gatt.disconnect() }
            return
        }
        if (descriptor.characteristic?.uuid == InstaraProtocol.NOTIFY_UUID) {
            val ctx = Applic.app
            val startMs = ctx?.let { InstaraPersistence.getSensorStartMs(it) } ?: 0L
            val intervalMs = 5L * 60L * 1000L
            val cursor = ctx?.let { InstaraPersistence.getLastSequenceId(it) } ?: 0
            session = InstaraSession(SerialNumber, startMs, intervalMs, cursor).also { it.onConnected() }
            connectionStatus = "Waiting for sensor"
            UiRefreshBus.requestStatusRefresh()
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (stop || mBluetoothGatt !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            runCatching { gatt.disconnect() }
            return
        }
        handler.post {
            synchronized(txLock) {
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
        lastNotifyAt = android.os.SystemClock.elapsedRealtime()
        val packet = InstaraProtocol.parse(data)
        if (packet is InstaraProtocol.Packet.SnAuth && packet.ok) failedAttempts = 0
        if (packet == null) {
            Log.d(TAG, "Dropping malformed notification (${data.size} bytes)")
            return
        }
        val current = session ?: return
        handler.post { execute(current.onPacket(packet)) }
    }

    private fun execute(actions: List<InstaraSession.Action>) {
        if (stop) return
        router.route(actions.filterIsInstance<InstaraSession.Action.PublishReading>())
        actions.forEach { action ->
            when (action) {
                is InstaraSession.Action.Send -> {
                    if (action.delayMs > 0L) handler.postDelayed({ enqueue(action) }, action.delayMs)
                    else enqueue(action)
                }
                is InstaraSession.Action.PublishReading -> Unit
                is InstaraSession.Action.PublishBattery -> {
                    batteryPercent = action.percent
                    UiRefreshBus.requestStatusRefresh()
                }
                is InstaraSession.Action.StatusChanged -> {
                    connectionStatus = action.text
                    if (action.text == "SN rejected") authRejected = true
                    UiRefreshBus.requestStatusRefresh()
                }
                InstaraSession.Action.HistoryComplete -> Log.i(TAG, "History download complete")
            }
        }
    }

    private fun enqueue(send: InstaraSession.Action.Send) {
        synchronized(txLock) {
            if (txInFlight) {
                txQueue.addLast(send)
                return
            }
        }
        writeNow(send)
    }

    private fun pumpLocked() {
        val next = txQueue.removeFirstOrNull() ?: return
        writeNow(next)
    }

    private fun writeNow(send: InstaraSession.Action.Send) {
        val gatt = mBluetoothGatt
        val char = writeChar
        if (gatt == null || char == null || stop) return
        synchronized(txLock) { txInFlight = true }
        char.value = send.bytes
        if (!gatt.writeCharacteristic(char)) {
            synchronized(txLock) {
                txQueue.clear()
                txInFlight = false
            }
            Log.e(TAG, "writeCharacteristic failed for cmd ${send.bytes[2].toInt() and 0xFF}")
            // A refused command can leave a half-open handshake; restart it from a clean link.
            runCatching { gatt.disconnect() }
        }
    }

    override fun live(reading: InstaraReading) {
        val mgdl = InstaraSession.mmolToMgdl(reading.raw)
        val timeMs = reading.timestampMs
        synchronized(readingLock) {
            if (stop) return
            markLocalReadingAccepted(timeMs)
            HistorySyncAccess.storeCurrentReadingAsync(timeMs, mgdl, 0f, 0f, SerialNumber)
            mirror(listOf(reading))
            SuperGattCallback.processExternalCurrentReading(
                SerialNumber,
                LiveReadingLanes.stock(
                    if (Applic.unit == 1) mgdl / InstaraSession.MGDL_PER_MMOL else mgdl,
                    Float.NaN,
                ),
                0f,
                timeMs,
                SENSOR_GEN,
            )
        }
        UiRefreshBus.requestDataRefresh()
    }

    override fun history(readings: List<InstaraReading>) {
        synchronized(readingLock) {
            if (stop) return
            val timestamps = LongArray(readings.size) { readings[it].timestampMs }
            val valuesMgdl = FloatArray(readings.size) { InstaraSession.mmolToMgdl(readings[it].raw) }
            HistorySyncAccess.storeSensorHistoryBatchAsync(
                SerialNumber,
                timestamps,
                valuesMgdl,
                FloatArray(readings.size),
            )
            mirror(readings)
            val maxSequence = readings.maxOf { it.sequenceId }
            Applic.app?.let { InstaraPersistence.setLastSequenceId(it, maxSequence) }
        }
        UiRefreshBus.requestDataRefresh()
    }

    private fun mirror(readings: List<InstaraReading>) {
        VirtualGlucoseSensorBridge.mirrorToNative(
            SerialNumber,
            readings.map {
                VirtualGlucoseSensorBridge.Reading(
                    timestampMs = it.timestampMs,
                    glucoseMgdl = InstaraSession.mmolToMgdl(it.raw),
                )
            },
            "Instara",
            ManagedSensorUiFamily.INSTARA,
        )
    }

    override fun softDisconnect() {
        setPause(true)
        paused = true
        resetTransport()
        mBluetoothGatt?.let { runCatching { it.disconnect() } }
        UiRefreshBus.requestStatusRefresh()
    }

    override fun softReconnect() {
        setPause(false)
        paused = false
        authRejected = false
        deviceRejected = false
        failedAttempts = 0
        resetTransport()
        mBluetoothGatt?.let { runCatching { it.disconnect() } }
            ?: connectDevice(0L)
        UiRefreshBus.requestStatusRefresh()
    }

    override fun onBluetoothAdapterUnavailable() {
        linkUp = false
        resetTransport()
    }

    private fun resetTransport() {
        session = null
        writeChar = null
        lastNotifyAt = 0L
        handler.removeCallbacksAndMessages(null)
        synchronized(txLock) {
            txQueue.clear()
            txInFlight = false
        }
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        stop = true
        setPause(true)
        resetTransport()
        synchronized(readingLock) {
            mBluetoothGatt?.let { runCatching { it.close() } }
            mBluetoothGatt = null
            // Finish the native mirror while the persisted record still claims it; otherwise the
            // next refresh recreates it as a generic Libre callback.
            runCatching { Natives.finishSensorById(SerialNumber) }
                .onFailure { Log.stack(TAG, "terminateManagedSensor(finishSensorById)", it) }
            if (wipeData) Applic.app?.let { InstaraPersistence.clear(it) }
        }
        thread.quit()
    }
}
