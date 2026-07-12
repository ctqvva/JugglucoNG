package tk.glucodata.drivers.ottai

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.security.SecureRandom
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorUiSnapshot
import tk.glucodata.drivers.ManagedSensorUiFamily

@SuppressLint("MissingPermission")
class OttaiBleManager(
    sensorId: String,
    dataptr: Long,
) : SuperGattCallback(sensorId, dataptr, SENSOR_GEN), OttaiDriver {

    companion object {
        private const val TAG = OttaiConstants.TAG
        const val SENSOR_GEN = 0

        private const val RECONNECT_DELAY_MS = 2_000L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val AUTH_TIMEOUT_MS = 8_000L
        private const val LIVE_GLUCOSE_POLL_INTERVAL_SECONDS = 15L
    }

    enum class Phase { IDLE, CONNECTING, DISCOVERING, AUTHENTICATING, STREAMING }

    @Volatile var phase: Phase = Phase.IDLE
        private set

    private val handlerThread = HandlerThread("Ottai-$sensorId").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    var mac: String = sensorId
        private set

    @Volatile var materials: DeviceMaterials = DeviceMaterials(mac = sensorId)
        private set
    @Volatile var record: OttaiSensorRecord = OttaiSensorRecord(sensorId = sensorId, mac = sensorId)
        private set
    @Volatile var sessionKey: String = ""

    private val random = SecureRandom()

    private var charCgmInfo: BluetoothGattCharacteristic? = null
    private var charHistory: BluetoothGattCharacteristic? = null
    private var charNewestGlucose: BluetoothGattCharacteristic? = null
    private var charCommand: BluetoothGattCharacteristic? = null
    private var charDeviceAuthParam: BluetoothGattCharacteristic? = null
    private var charAppAuthParam: BluetoothGattCharacteristic? = null
    private var charAuthSignature: BluetoothGattCharacteristic? = null
    private var charCurrentTime: BluetoothGattCharacteristic? = null
    private var charMaxActiveTime: BluetoothGattCharacteristic? = null
    private var charDestructionTime: BluetoothGattCharacteristic? = null
    private var charDeviceVersion: BluetoothGattCharacteristic? = null

    @Volatile private var serviceDiscoveryHandled = false
    @Volatile private var authComplete = false
    @Volatile private var activeTimeMs: Long = 0L
    @Volatile private var warmupSeconds = OttaiConstants.DEFAULT_WARMUP_SECONDS
    @Volatile private var methodExpression: String = ""
    @Volatile private var coefficientsCsv: String = ""
    @Volatile private var lastGlucoseAtMs: Long = 0L
    @Volatile private var lastGlucoseMgdl: Float = Float.NaN
    @Volatile private var lastRawMgdl: Float = Float.NaN
    @Volatile private var lastRate: Float = Float.NaN
    @Volatile private var sensorStartAtMs: Long = 0L
    private val authKeys: MutableList<ByteArray> = mutableListOf()
    private var authKeyIndex: Int = 0
    private var deviceKeyIndex: Int = 0
    private var deviceTime3: ByteArray = ByteArray(3)
    private var appTime3: ByteArray = ByteArray(3)
    private var appPublicX: ByteArray = ByteArray(32)
    private var appPublicY: ByteArray = ByteArray(32)
    private var devicePublicX: ByteArray = ByteArray(32)
    private var devicePublicY: ByteArray = ByteArray(32)
    private var appPrivateKey: java.security.interfaces.ECPrivateKey? = null
    private var authStepPending = false
    private var authNextWriter: (() -> Unit)? = null

    private val serviceDiscoveryWatchdog = Runnable {
        if (phase == Phase.DISCOVERING && !serviceDiscoveryHandled) {
            Log.e(TAG, "Service discovery timeout for $mac")
            runCatching { mBluetoothGatt?.disconnect() }
        }
    }

    // ---- Restore from persistence ----

    fun restoreFromPersistence(context: Context) {
        record = OttaiRegistry.loadMacRecord(context, mac)
        mac = record.mac
        sessionKey = record.sessionKeyHex
        activeTimeMs = record.activeTimeMs
        warmupSeconds = OttaiConstants.DEFAULT_WARMUP_SECONDS
        methodExpression = record.method
        coefficientsCsv = record.coefficient
        if (record.keyAPlaintext.isNotEmpty()) {
            authKeys.clear()
            authKeys.addAll(OttaiCrypto.splitKeyA(record.keyAPlaintext))
        }
        if (record.provisionalActiveTimeMs > 0L && activeTimeMs == 0L) {
            activeTimeMs = record.provisionalActiveTimeMs
        }
    }

    // ---- BLE lifecycle ----

    override fun getService(): UUID = OttaiConstants.SERVICE_CGM

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        if (phase != Phase.IDLE) return true
        phase = Phase.CONNECTING
        return super.connectDevice(delayMillis)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (stop) return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "Connected to ${gatt.device?.address}")
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                phase = Phase.DISCOVERING
                serviceDiscoveryHandled = false
                handler.postDelayed({
                    if (phase == Phase.DISCOVERING && !serviceDiscoveryHandled) {
                        gatt.discoverServices()
                    }
                }, 250)
                handler.postDelayed(serviceDiscoveryWatchdog, SERVICE_DISCOVERY_TIMEOUT_MS)
                gatt.requestMtu(OttaiConstants.DEFAULT_MTU)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "Disconnected (status=$status)")
                phase = Phase.IDLE
                serviceDiscoveryHandled = false
                authComplete = false
                authStepPending = false
                authNextWriter = null
                clearCharacteristics()
                mActiveBluetoothDevice = null
                try { gatt.close() } catch (_: Throwable) {}
                mBluetoothGatt = null
                handler.removeCallbacksAndMessages(null)
                if (!stop) {
                    handler.postDelayed({ connectDevice(0) }, RECONNECT_DELAY_MS)
                }
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        handler.removeCallbacks(serviceDiscoveryWatchdog)
        if (serviceDiscoveryHandled) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "Service discovery failed status=$status")
            runCatching { gatt.disconnect() }
            return
        }
        serviceDiscoveryHandled = true

        val deviceInfo = gatt.getService(OttaiConstants.SERVICE_DEVICE_INFO)
        val cgmService = gatt.getService(OttaiConstants.SERVICE_CGM)
        val authService = gatt.getService(OttaiConstants.SERVICE_AUTH)
        val destructionService = gatt.getService(OttaiConstants.SERVICE_DESTRUCTION)

        charCgmInfo = deviceInfo?.getCharacteristic(OttaiConstants.CHAR_CGM_INFO)
        charCurrentTime = deviceInfo?.getCharacteristic(OttaiConstants.CHAR_CURRENT_TIME)
        charMaxActiveTime = deviceInfo?.getCharacteristic(OttaiConstants.CHAR_MAX_ACTIVE_TIME)
        charDeviceVersion = deviceInfo?.getCharacteristic(OttaiConstants.CHAR_SOFTWARE_VERSION)

        charNewestGlucose = cgmService?.getCharacteristic(OttaiConstants.CHAR_NEWEST_GLUCOSE)
        charHistory = cgmService?.getCharacteristic(OttaiConstants.CHAR_HISTORY_GLUCOSE)
        charCommand = cgmService?.getCharacteristic(OttaiConstants.CHAR_COMMAND)

        charDeviceAuthParam = authService?.getCharacteristic(OttaiConstants.CHAR_DEVICE_AUTH_PARAM)
        charAppAuthParam = authService?.getCharacteristic(OttaiConstants.CHAR_APP_AUTH_PARAM)
        charAuthSignature = authService?.getCharacteristic(OttaiConstants.CHAR_AUTH_SIGNATURE)

        charDestructionTime = destructionService?.getCharacteristic(OttaiConstants.CHAR_DESTRUCTION_TIME)

        // Enable notifications on key characteristics
        val cgmEnabled = charCgmInfo?.let { enableNotification(gatt, it) } ?: false
        val historyEnabled = charHistory?.let { enableNotification(gatt, it) } ?: false
        val glucoseEnabled = charNewestGlucose?.let { enableNotification(gatt, it) } ?: false

        Log.i(TAG, "Notifications: cgm=$cgmEnabled history=$historyEnabled glucose=$glucoseEnabled")

        phase = Phase.AUTHENTICATING
        beginAuthV2()
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val uuid = characteristic.uuid
        val data = characteristic.value ?: return

        when (uuid) {
            OttaiConstants.CHAR_CGM_INFO -> onCgmInfo(data)
            OttaiConstants.CHAR_HISTORY_GLUCOSE -> onHistoryGlucose(data)
            OttaiConstants.CHAR_NEWEST_GLUCOSE -> onNewestGlucose(data)
            else -> Log.d(TAG, "Notification from ${uuid}")
        }
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "Read failed for ${characteristic.uuid} status=$status")
            authStepPending = false
            return
        }
        val data = characteristic.value ?: return
        val uuid = characteristic.uuid

        when (uuid) {
            OttaiConstants.CHAR_DEVICE_AUTH_PARAM -> onDeviceAuthParam(data)
            OttaiConstants.CHAR_AUTH_SIGNATURE -> onDeviceSign(data)
            OttaiConstants.CHAR_CURRENT_TIME -> onCurrentTime(data)
            OttaiConstants.CHAR_COMMAND -> onCommandStatus(data)
            OttaiConstants.CHAR_NEWEST_GLUCOSE -> onNewestGlucose(data)
            else -> Log.d(TAG, "Read from ${uuid}")
        }

        if (authStepPending) {
            authStepPending = false
            authNextWriter?.invoke()
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "Write failed for ${characteristic.uuid} status=$status")
            authStepPending = false
            return
        }
        if (authStepPending) {
            authStepPending = false
            authNextWriter?.invoke()
        }
    }

    // ---- Auth V2 flow ----

    private fun beginAuthV2() {
        if (authComplete) return
        Log.i(TAG, "Starting auth V2")
        readDeviceAuthParam()
    }

    private fun readDeviceAuthParam() {
        val gatt = mBluetoothGatt ?: return
        val ch = charDeviceAuthParam ?: return
        authStepPending = true
        authNextWriter = { readDeviceSign() }
        handler.postDelayed({
            if (authStepPending) {
                Log.e(TAG, "Auth read timeout")
                runCatching { gatt.disconnect() }
            }
        }, AUTH_TIMEOUT_MS)
        gatt.readCharacteristic(ch)
    }

    private fun onDeviceAuthParam(data: ByteArray) {
        val (idx, t3, pub) = OttaiBleAuth.parseDeviceAuthParam(data)
        deviceKeyIndex = idx
        deviceTime3 = t3
        devicePublicX = pub.first
        devicePublicY = pub.second
    }

    private fun readDeviceSign() {
        val gatt = mBluetoothGatt ?: return
        val ch = charAuthSignature ?: return
        authStepPending = true
        authNextWriter = { readCurrentTime() }
        gatt.readCharacteristic(ch)
    }

    private fun onDeviceSign(data: ByteArray) {
        val key = authKeys.getOrNull(deviceKeyIndex) ?: return
        val macBytes = macToBytes(mac)
        val expected = OttaiBleAuth.buildDeviceSign(key, macBytes, devicePublicX, devicePublicY, deviceTime3)
        if (!data.contentEquals(expected)) {
            Log.e(TAG, "Device signature verification failed")
            runCatching { mBluetoothGatt?.disconnect() }
            return
        }
        Log.i(TAG, "Device signature verified")
    }

    private fun readCurrentTime() {
        val gatt = mBluetoothGatt ?: return
        val ch = charCurrentTime ?: run { writeAppAuthParam(); return }
        authStepPending = true
        authNextWriter = { writeAppAuthParam() }
        gatt.readCharacteristic(ch)
    }

    private fun onCurrentTime(data: ByteArray) {
        appTime3 = OttaiBleAuth.buildAppTime3(data)
    }

    private fun writeAppAuthParam() {
        val gatt = mBluetoothGatt ?: return
        val ch = charAppAuthParam ?: return
        authKeyIndex = random.nextInt(OttaiConstants.AUTH_KEY_COUNT)
        val pair = OttaiBleAuth.generateKeyPair()
        appPrivateKey = pair.first
        val (px, py) = OttaiBleAuth.publicKeyToBytes(pair.second)
        appPublicX = px
        appPublicY = py

        val param = OttaiBleAuth.buildAppAuthParam(authKeyIndex, appTime3, appPublicX, appPublicY)
        authStepPending = true
        authNextWriter = { writeAppSign() }
        ch.setValue(param)
        gatt.writeCharacteristic(ch)
    }

    private fun writeAppSign() {
        val gatt = mBluetoothGatt ?: return
        val ch = charAuthSignature ?: return
        val key = authKeys.getOrNull(authKeyIndex) ?: return
        val macBytes = macToBytes(mac)
        val sign = OttaiBleAuth.buildAppSign(key, macBytes, appPublicX, appPublicY, appTime3)
        authStepPending = true
        authNextWriter = { deriveSessionAndFinish() }
        ch.setValue(sign)
        gatt.writeCharacteristic(ch)
    }

    private fun deriveSessionAndFinish() {
        authStepPending = false
        val priv = appPrivateKey ?: return
        val sharedX = OttaiBleAuth.computeEcdhSharedSecretX(priv, devicePublicX, devicePublicY)
        sessionKey = OttaiBleAuth.deriveSessionKey(sharedX)
        authComplete = true
        phase = Phase.STREAMING

        OttaiRegistry.updateSessionKey(Applic.app, mac, sessionKey)

        Log.i(TAG, "Auth complete, session key derived")
        postAuthStartStreaming()
    }

    private fun postAuthStartStreaming() {
        readCommandStatus()
        startLiveGlucosePolling()
    }

    // ---- Command status ----

    private fun readCommandStatus() {
        val gatt = mBluetoothGatt ?: return
        val ch = charCommand ?: return
        gatt.readCharacteristic(ch)
    }

    private fun onCommandStatus(data: ByteArray) {
        if (data.isEmpty()) return
        val status = data[0]
        Log.i(TAG, "Command status: $status")
        when (status) {
            OttaiConstants.CMD_STATUS_RUNNING -> Log.i(TAG, "Sensor is running")
            OttaiConstants.CMD_STATUS_EXPIRED -> Log.i(TAG, "Sensor expired")
        }
    }

    // ---- Live glucose polling ----

    private var liveGlucosePollRunnable: Runnable? = null

    private fun startLiveGlucosePolling() {
        handler.removeCallbacks(liveGlucosePollRunnable ?: return)
        liveGlucosePollRunnable = object : Runnable {
            override fun run() {
                if (phase != Phase.STREAMING || stop) return
                val gatt = mBluetoothGatt ?: return
                val ch = charNewestGlucose
                if (ch != null) {
                    gatt.readCharacteristic(ch)
                }
                handler.postDelayed(this, LIVE_GLUCOSE_POLL_INTERVAL_SECONDS * 1000L)
            }
        }
        handler.post(liveGlucosePollRunnable!!)
    }

    // ---- CGM info handler ----

    private fun onCgmInfo(data: ByteArray) {
        Log.d(TAG, "CGM info: ${data.size} bytes")
    }

    // ---- Newest glucose handler ----

    private fun onNewestGlucose(data: ByteArray) {
        if (sessionKey.isEmpty()) return

        if (OttaiParser.isAllZeroPayload(data)) return

        val decrypted = OttaiCrypto.decryptPayload(data, sessionKey)
        val parsed = OttaiParser.parsePayload(decrypted, activeTimeMs)

        for (rec in parsed.records) {
            val glucose = evaluateGlucose(rec)
            if (glucose.isNaN()) continue

            val sampleMs = rec.monitorTime
            val raw = rec.rawCurrent.toFloat()
            lastGlucoseAtMs = sampleMs
            lastGlucoseMgdl = glucose
            lastRawMgdl = raw

            if (sensorStartAtMs == 0L) {
                sensorStartAtMs = sampleMs - rec.runtime * 1000L
            }

            mirrorReadingIntoNative(sampleMs, glucose, raw, rec.temperature)
            OttaiRegistry.updateLastDataNo(Applic.app, mac, rec.dataNo)
        }
    }

    // ---- History handler ----

    private fun onHistoryGlucose(data: ByteArray) {
        if (sessionKey.isEmpty()) return
        if (OttaiParser.isAllZeroPayload(data)) return

        val decrypted = OttaiCrypto.decryptPayload(data, sessionKey)
        val parsed = OttaiParser.parsePayload(decrypted, activeTimeMs)

        for (rec in parsed.records) {
            val glucose = evaluateGlucose(rec)
            if (glucose.isNaN()) continue

            val sampleMs = rec.monitorTime
            mirrorReadingIntoNative(sampleMs, glucose, rec.rawCurrent.toFloat(), rec.temperature)
        }
    }

    private fun evaluateGlucose(rec: OttaiParser.GlucoseRecord): Float {
        if (!OttaiParser.isWarmupComplete(rec.runtime, warmupSeconds)) return Float.NaN
        return OttaiFormula.evaluate(methodExpression, coefficientsCsv, rec)
    }

    // ---- Mirror to native ----

    private fun mirrorReadingIntoNative(sampleMs: Long, glucoseMgdl: Float, rawMgdl: Float, temperatureC: Float) {
        val name = SerialNumber ?: return
        val sampleSec = sampleMs / 1000L
        if (sampleSec <= 0L || !glucoseMgdl.isFinite() || glucoseMgdl <= 0f) return
        runCatching {
            val startSec = if (sensorStartAtMs > 0L) sensorStartAtMs / 1000L else (sampleSec - 3600L).coerceAtLeast(1L)
            Natives.ensureSensorShell(name, startSec)
            Natives.addGlucoseStreamWithRawTemp(sampleSec, glucoseMgdl / 10f, rawMgdl, temperatureC, name)
            if (dataptr == 0L) {
                dataptr = runCatching { Natives.getdataptr(name) }.getOrDefault(0L)
            }
            Natives.wakebackup()
        }.onFailure { Log.stack(TAG, "mirrorReadingIntoNative", it) }
    }

    // ---- Activation flow ----

    fun requestActivation(activeMs: Long) {
        activeTimeMs = activeMs
        val gatt = mBluetoothGatt ?: return
        val ch = charMaxActiveTime ?: return

        val rtcBytes = OttaiBleAuth.longToLe8(System.currentTimeMillis())
        ch.setValue(rtcBytes)
        gatt.writeCharacteristic(ch)

        handler.postDelayed({
            val destruction = charDestructionTime ?: return@postDelayed
            val retainBytes = OttaiBleAuth.longToLe8(OttaiConstants.DEFAULT_RETAIN_TIME_MS)
            destruction.setValue(retainBytes)
            gatt.writeCharacteristic(destruction)

            handler.postDelayed({
                val cmd = charCommand ?: return@postDelayed
                val activatePayload = byteArrayOf(OttaiConstants.CMD_ACTIVATE)
                val encrypted = OttaiCrypto.encryptActivateCmd(activatePayload, sessionKey)
                cmd.setValue(encrypted)
                gatt.writeCharacteristic(cmd)
            }, 500)
        }, 500)
    }

    // ---- Cleanup ----

    private fun clearCharacteristics() {
        charCgmInfo = null
        charHistory = null
        charNewestGlucose = null
        charCommand = null
        charDeviceAuthParam = null
        charAppAuthParam = null
        charAuthSignature = null
        charCurrentTime = null
        charMaxActiveTime = null
        charDestructionTime = null
        charDeviceVersion = null
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
        super.close()
    }

    // ---- ManagedBluetoothSensorDriver ----

    override fun canConnectWithoutDataptr(): Boolean = true
    override fun managesLiveRoomStorage(): Boolean = true
    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false
    override fun shouldUseNativeHistorySync(): Boolean = false

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        sensorId != null && sensorId.equals(SerialNumber, ignoreCase = true)

    override fun getDetailedBleStatus(): String = when (phase) {
        Phase.IDLE -> "Idle"
        Phase.CONNECTING -> "Connecting"
        Phase.DISCOVERING -> "Discovering services"
        Phase.AUTHENTICATING -> "Authenticating"
        Phase.STREAMING -> if (lastGlucoseAtMs > 0L) "Connected" else "Streaming"
    }

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val atMs = lastGlucoseAtMs
        if (atMs <= 0L || System.currentTimeMillis() - atMs > maxAgeMillis) return null
        return ManagedSensorCurrentSnapshot(
            timeMillis = atMs,
            glucoseValue = lastGlucoseMgdl,
            rawGlucoseValue = lastRawMgdl,
            rate = lastRate,
            sensorGen = SENSOR_GEN,
        )
    }

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot? {
        val serial = SerialNumber ?: return null
        val active = activeSensorId?.takeIf { it.isNotBlank() }
        val detailed = getDetailedBleStatus()
        return ManagedSensorUiSnapshot(
            serial = serial,
            displayName = runCatching { mygetDeviceName() }.getOrDefault(serial),
            deviceAddress = mActiveDeviceAddress ?: "Unknown",
            uiFamily = ManagedSensorUiFamily.GENERIC,
            connectionStatus = detailed,
            detailedStatus = detailed,
            subtitleStatus = detailed,
            showConnectionStatusInDetails = true,
            isUiEnabled = true,
            isActive = active != null && serial.equals(active, ignoreCase = true),
            rssi = readrssi,
            dataptr = dataptr,
            viewMode = viewMode,
            isVendorConnected = mActiveBluetoothDevice != null,
        )
    }

    override fun softDisconnect() {
        handler.removeCallbacksAndMessages(null)
    }

    override fun softReconnect() {
        connectDevice(0)
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        handler.removeCallbacksAndMessages(null)
        if (wipeData) {
            OttaiRegistry.removeSensor(Applic.app, mac)
        }
    }

    override fun removeManagedPersistence(context: Context) {
        OttaiRegistry.removeSensor(context, mac)
    }

    override var viewMode: Int = 0

    // ---- OttaiDriver ----

    override fun isUiEnabled(): Boolean = true

    override fun getStartTimeMs(): Long = sensorStartAtMs

    override fun getOfficialEndMs(): Long =
        if (sensorStartAtMs > 0L) sensorStartAtMs + OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS else 0L

    override fun getExpectedEndMs(): Long = getOfficialEndMs()

    override fun isSensorExpired(): Boolean {
        val end = getOfficialEndMs()
        return end > 0L && System.currentTimeMillis() >= end
    }

    override fun getSensorRemainingHours(): Int {
        val end = getOfficialEndMs()
        if (end <= 0L) return -1
        val remaining = end - System.currentTimeMillis()
        return if (remaining <= 0L) 0 else ((remaining + 30 * 60 * 1000L) / (60L * 60 * 1000L)).toInt()
    }

    override fun getSensorAgeHours(): Int {
        if (sensorStartAtMs <= 0L) return -1
        val age = System.currentTimeMillis() - sensorStartAtMs
        if (age <= 0L) return 0
        return ((age + 30 * 60 * 1000L) / (60L * 60 * 1000L)).toInt()
    }

    override fun getReadingIntervalMinutes(): Int =
        OttaiConstants.CGM_INFO_POLL_INTERVAL_DEFAULT_SECONDS.toInt() / 60

    override val vendorFirmwareVersion: String
        get() = record.deviceVersion
    override val vendorModelName: String
        get() = OttaiConstants.DEFAULT_DISPLAY_NAME
    override val batteryMillivolts: Int
        get() = 0

    // ---- Helpers ----

    private fun macToBytes(mac: String): ByteArray {
        val hex = mac.replace(":", "").uppercase()
        return OttaiCrypto.hexToBytes(hex)
    }
}
