package tk.glucodata.drivers.sibionics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.NightscoutUploadWake
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot
import tk.glucodata.drivers.ManagedSensorViewModeStore

/**
 * Managed driver for the Sibionics GS3 CN firmware (see [SibionicsGs3Protocol] and
 * [SibionicsGs3Session]). The sensor computes glucose itself, so unlike
 * [SibionicsBleManager] there is no local algorithm, journal or rebuild: records are
 * timestamped from their index, stored, mirrored into the native shell, and the newest
 * one is published live.
 */
@SuppressLint("MissingPermission")
class SibionicsGs3BleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SibionicsConstants.SENSOR_GEN),
    ManagedBluetoothSensorDriver,
    ManagedSensorMaintenanceDriver {

    private enum class Phase {
        IDLE,
        CONNECTING,
        DISCOVERING,
        READING_IDENTITY,
        ENABLING_NOTIFY,
        HANDSHAKE,
        STREAMING,
        ERROR,
    }

    private data class Reading(
        val index: Int,
        val sampleMs: Long,
        val glucoseMgdl: Float,
        val trend: SibionicsProtocol.Trend,
    )

    companion object {
        private const val TAG = "SibionicsGs3"
        private const val RECONNECT_DELAY_MS = 8_000L
        private const val FAILED_RECONNECT_DELAY_MS = 60_000L
        // Connection handling follows SibionicsBleManager rather than the vendor notes: these
        // values carry field fixes the vendor driver does not have.
        private const val CONNECT_CALLBACK_TIMEOUT_MS = 20_000L
        private const val ADVERTISEMENT_RECOVERY_TIMEOUT_MS = 25_000L
        /** Upper bound on CONNECTION_PRIORITY_HIGH; see [armHighPriorityCap]. */
        private const val HIGH_PRIORITY_MAX_MS = 3_000L
        private const val PRIORITY_SETTLE_DELAY_MS = 2_000L

        // Timers from the vendor driver.
        private const val DISCOVERY_RETRY_MS = 3_000L
        private const val DISCOVERY_MAX_RETRIES = 2
        private const val WRITE_CALLBACK_TIMEOUT_MS = 4_000L
        private const val WRITE_RETRY_DELAY_MS = 120L
        private const val WRITE_RETRY_MAX_ATTEMPTS = 25
        private const val STALE_DATA_CHECK_MS = 90_000L
        private const val STALE_DATA_DISCONNECT_MS = 180_000L

        /** max(180 s, 3 x reading interval + 15 s). */
        private val LINK_WATCHDOG_MS = maxOf(180_000L, 3L * SibionicsGs3Protocol.RECORD_INTERVAL_MS + 15_000L)

        // Step deadlines of our own: the vendor relies on its general watchdog alone, which
        // would leave a silent auth attempt pending for minutes before its fallback.
        private const val AUTH_STEP_TIMEOUT_MS = 5_000L
        private const val HANDSHAKE_STEP_TIMEOUT_MS = 15_000L
        private const val IDENTITY_READ_TIMEOUT_MS = 4_000L

        private const val CURRENT_FRESH_MS = 3L * 60L * 1000L
        private const val MAX_FUTURE_DRIFT_MS = 2L * 60L * 1000L

        /** Index anchor tolerance before the session start is re-derived (sensor clock drift). */
        private const val ANCHOR_TOLERANCE_MS = 3L * 60L * 1000L
        private const val NATIVE_STREAM_CAPACITY_MINUTES = 21 * 24 * 60
    }

    @Volatile private var phase = Phase.IDLE
    private val handlerThread = HandlerThread("SibionicsGs3-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private val notificationDispatcher = SibionicsNotificationDispatcher(
        post = { task -> handler.post(task) },
        consume = ::handleIncoming,
    )
    private val advertisementRecovery = SibionicsAdvertisementRecovery(
        context = Applic.getContext(),
        handler = handler,
        onFinished = ::finishAdvertisementRecovery,
    )
    private val mirrorLock = Any()

    @Volatile private var record: SibionicsRegistry.SensorRecord? = null
    @Volatile private var retiredGatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var identityChar: BluetoothGattCharacteristic? = null

    private var session: SibionicsGs3Session? = null
    private var stepTimeoutKey: String = ""
    private var discoveryRetries = 0
    private enum class RecoveryPurpose { CONNECT, IDENTITY }

    private var recoveryPurpose = RecoveryPurpose.CONNECT
    private var connectionPrioritySettled = false
    private var highPriorityCapArmed = false
    @Volatile private var mtu = 23

    /** MAC read from 0x2A25 (this or an earlier connection); persisted with the record. */
    @Volatile private var identityMac: ByteArray? = null
    /** MAC carried by the latest advertisement (0x55AA / 0x5347). */
    @Volatile private var advertisedIdentityMac: ByteArray? = null

    private class PendingWrite(val bytes: ByteArray, val label: String, val sensitive: Boolean)

    private val writeQueue = ArrayDeque<PendingWrite>()
    private var writeInFlight = false
    private var writeAttempts = 0

    @Volatile private var cursor: Int = 0
    @Volatile private var startTimeMs: Long = 0L
    @Volatile private var lastPageMs: Long = 0L
    @Volatile private var lastFrameMs: Long = 0L
    @Volatile private var lastLiveIndex: Int = -1
    @Volatile private var latestReadingTimeMs: Long = 0L
    @Volatile private var latestGlucoseMgdl: Float = Float.NaN
    @Volatile private var latestRateMgdlPerMin: Float = Float.NaN
    @Volatile private var latestTrend: SibionicsProtocol.Trend = SibionicsProtocol.Trend.NOT_DETERMINED
    @Volatile private var nativeShellStartSec: Long = 0L
    @Volatile private var lastFailure: String = ""
    @Volatile private var preserveResumeStateOnRemoval: Boolean = false

    /**
     * Address whose completed service discovery showed it is not a GS3 (no FF30/FF31/FF32).
     * Never reconnected to automatically; only an explicit reconnect clears it.
     */
    @Volatile private var incompatibleAddress: String? = null

    @Volatile private var viewModeValue: Int = ManagedSensorViewModeStore.read(Applic.app, serial, 0)

    override var viewMode: Int
        get() = viewModeValue
        set(value) {
            val normalized = ManagedSensorViewModeStore.sanitize(value)
            viewModeValue = normalized
            ManagedSensorViewModeStore.write(Applic.app, SerialNumber, normalized)
        }

    fun restoreFromPersistence(context: Context) {
        val restored = SibionicsRegistry.findRecord(context, SerialNumber)
        record = restored
        restored?.let { mActiveDeviceAddress = it.address.ifBlank { null } }
        cursor = SibionicsRegistry.loadLastIndex(context, SerialNumber).coerceIn(0, 0xFFFF)
        startTimeMs = SibionicsRegistry.loadStartTimeMs(context, SerialNumber)
        identityMac = SibionicsGs3Identity.parse(SibionicsRegistry.loadGs3IdentityMac(context, SerialNumber))
        val (time, glucose, _) = SibionicsRegistry.loadLastReading(context, SerialNumber)
        latestReadingTimeMs = time
        latestGlucoseMgdl = glucose
        Log.i(
            TAG,
            "restored cursor=$cursor start=$startTimeMs identity=${identityMac?.let(SibionicsGs3Identity::format)} " +
                "address=$mActiveDeviceAddress serial=$SerialNumber",
        )
        constatstatusstr = disconnectedStatus()
    }

    // ---- ManagedBluetoothSensorDriver ----

    override fun getService(): UUID = SibionicsConstants.SERVICE_FF30

    override fun canConnectWithoutDataptr(): Boolean = true

    override fun hasNativeSensorBacking(): Boolean = false

    override fun shouldUseNativeHistorySync(): Boolean = false

    override fun managesLiveRoomStorage(): Boolean = true

    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    override fun isManagedOutsideNativeActiveSet(): Boolean = true

    override fun shouldShowSearchingStatusWhenIdle(): Boolean = true

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        SibionicsConstants.matchesId(SerialNumber, sensorId) ||
            record?.matchesId(sensorId) == true ||
            SibionicsRegistry.findRecord(Applic.app, sensorId)?.matchesId(SerialNumber) == true

    override fun mygetDeviceName(): String =
        SibionicsConstants.stripManagedPrefix(
            record?.displayName?.takeIf { it.isNotBlank() } ?: SerialNumber,
        )

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        if (advertisementRecovery.isActive) return true
        if (isIncompatible(knownBleAddress())) {
            // Handled: nothing to connect to, and nothing for the shared scanner to look for.
            phase = Phase.ERROR
            setStatus(lastFailure)
            return true
        }
        if (phase != Phase.IDLE && phase != Phase.ERROR && mBluetoothGatt != null) {
            Log.d(TAG, "connect coalesced phase=$phase serial=$SerialNumber")
            return true
        }
        if (mActiveBluetoothDevice == null) hydrateBluetoothDeviceFromAddress()
        // Returning false lets the shared scanner look for the sensor by name/address.
        if (mActiveBluetoothDevice == null) {
            phase = Phase.IDLE
            return false
        }
        phase = Phase.CONNECTING
        setStatus(connectingStatus())
        val scheduled = super.connectDevice(delayMillis)
        if (scheduled) {
            armConnectCallbackTimeout(delayMillis)
        } else if (phase == Phase.CONNECTING) {
            phase = Phase.IDLE
        }
        return scheduled
    }

    @Synchronized
    override fun softDisconnect() {
        notificationDispatcher.invalidateSession()
        stop = true
        advertisementRecovery.stop()
        handler.removeCallbacksAndMessages(null)
        resetConnectionState()
        runCatching { mBluetoothGatt?.disconnect() }
        runCatching { mBluetoothGatt?.close() }
        mBluetoothGatt = null
        mActiveBluetoothDevice = null
        phase = Phase.IDLE
        setStatus(disconnectedStatus())
    }

    @Synchronized
    override fun softReconnect() {
        stop = false
        // The user asked to try again: give a previously rejected device another discovery.
        incompatibleAddress = null
        lastFailure = ""
        phase = Phase.IDLE
        connectDevice(0)
    }

    override fun onBluetoothAdapterUnavailable() {
        advertisementRecovery.stop()
        handler.removeCallbacks(reconnectRunnable)
        prepareForReconnect()
        constatstatusstr = Applic.getContext().getString(R.string.status_bluetooth_off)
        UiRefreshBus.requestStatusRefresh()
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        Log.i(TAG, "terminateManagedSensor serial=$SerialNumber wipeData=$wipeData")
        // A plain disconnect keeps the cursor and identity, so re-adding the same sensor
        // resumes where it stopped instead of downloading its whole history again.
        preserveResumeStateOnRemoval = !wipeData
        softDisconnect()
        Applic.app?.let {
            SibionicsRegistry.removeSensor(it, SerialNumber, preserveResumeState = preserveResumeStateOnRemoval)
        }
        UiRefreshBus.requestStatusRefresh()
    }

    override fun removeManagedPersistence(context: Context) {
        SibionicsRegistry.removeSensor(context, SerialNumber, preserveResumeState = preserveResumeStateOnRemoval)
    }

    override fun close() {
        notificationDispatcher.invalidateSession()
        advertisementRecovery.stop()
        handler.removeCallbacksAndMessages(null)
        runCatching { handlerThread.quitSafely() }
        super.close()
    }

    /**
     * Only an exact identity is ours: the bound BLE address, the BLE name recorded at setup,
     * or an advertisement carrying the GS3 identity MAC seen before. No suffix heuristics, so a
     * GS3 never claims another FF30 sensor.
     */
    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val scanned = SibionicsConstants.normalizeBleAddress(address)
        if (isIncompatible(scanned)) return false
        val known = knownBleAddress()
        if (known != null && scanned != null) return known.equals(scanned, ignoreCase = true)
        val name = SibionicsConstants.normalizeBleName(deviceName)
        val bleName = SibionicsConstants.normalizeBleName(
            record?.bleName ?: SibionicsRegistry.findRecord(Applic.app, SerialNumber)?.bleName,
        )
        return name.length >= 6 && name.equals(bleName, ignoreCase = true)
    }

    override fun matchScanResult(result: ScanResult): Boolean {
        if (isIncompatible(result.device?.address)) return false
        val expected = identityMac ?: return false
        val advertised = advertisedMac(result) ?: return false
        return advertised.contentEquals(expected)
    }

    override fun onScanResult(result: ScanResult) {
        super.onScanResult(result)
        advertisedMac(result)?.let { mac ->
            if (advertisedIdentityMac?.contentEquals(mac) != true) {
                Log.i(TAG, "advertised identity ${SibionicsGs3Identity.format(mac)} serial=$SerialNumber")
            }
            advertisedIdentityMac = mac
        }
    }

    private fun advertisedMac(result: ScanResult): ByteArray? {
        val scanRecord = result.scanRecord ?: return null
        return SibionicsGs3Identity.macFromAdvertisement(
            manufacturerData = scanRecord.getManufacturerSpecificData(SibionicsGs3Identity.MANUFACTURER_ID),
            serviceData = scanRecord.getServiceData(ParcelUuid(SibionicsGs3Identity.SERVICE_DATA_UUID)),
        )
    }

    override fun setDeviceAddress(address: String?) {
        super.setDeviceAddress(address)
        val normalized = SibionicsConstants.normalizeBleAddress(address) ?: return
        val ctx = Applic.app ?: return
        val rec = record ?: SibionicsRegistry.findRecord(ctx, SerialNumber) ?: return
        if (rec.address.equals(normalized, ignoreCase = true)) return
        record = SibionicsRegistry.ensureSensorRecord(
            context = ctx,
            rawInput = rec.displayName,
            address = normalized,
            displayName = rec.displayName,
            variant = rec.variant,
            shortCodeOverride = rec.shortCode,
            bleNameOverride = rec.bleName,
        )
    }

    // ---- GATT callbacks ----

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        noteFirstGattCallback("onConnectionStateChange", gatt)
        super.onConnectionStateChange(gatt, status, newState)
        if (isCurrentGatt(gatt)) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> notificationDispatcher.beginSession()
                BluetoothProfile.STATE_DISCONNECTED -> notificationDispatcher.invalidateSession()
            }
        }
        handler.post { handleConnectionStateChange(gatt, status, newState) }
    }

    private fun handleConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (stop) return
        if (gatt === retiredGatt) {
            retiredGatt = null
            runCatching { gatt.close() }
            return
        }
        if (mBluetoothGatt != null && gatt !== mBluetoothGatt) {
            Log.d(TAG, "ignore stale GATT callback state=$newState serial=$SerialNumber")
            runCatching { gatt.close() }
            return
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                advertisementRecovery.stop()
                handler.removeCallbacks(connectCallbackTimeoutRunnable)
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                gatt.device?.address?.let { setDeviceAddress(it) }
                connectTime = System.currentTimeMillis()
                resetConnectionState()
                connectionPrioritySettled = false
                highPriorityCapArmed = false
                lastFrameMs = System.currentTimeMillis()
                armLinkWatchdog()
                phase = Phase.DISCOVERING
                setStatus(connectingStatus())
                runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                handler.postDelayed({ startDiscovery(gatt) }, 250L)
                UiRefreshBus.requestStatusRefresh()
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                val failedDuringConnect = phase == Phase.CONNECTING
                Log.i(TAG, "disconnected status=$status phase=$phase serial=$SerialNumber")
                handler.removeCallbacks(connectCallbackTimeoutRunnable)
                resetConnectionState()
                phase = Phase.IDLE
                runCatching { gatt.close() }
                mBluetoothGatt = null
                mActiveBluetoothDevice = null
                setStatus(lastFailure.ifBlank { disconnectedStatus() })
                if (!stop && !beginAdvertisementRecovery("connect failed status=$status", failedDuringConnect)) {
                    scheduleReconnect("disconnect status=$status")
                }
                UiRefreshBus.requestStatusRefresh()
            }
        }
    }

    private fun startDiscovery(gatt: BluetoothGatt) {
        if (stop || gatt !== mBluetoothGatt || phase != Phase.DISCOVERING) return
        handler.removeCallbacks(discoveryRetryRunnable)
        handler.postDelayed(discoveryRetryRunnable, DISCOVERY_RETRY_MS)
        if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
            Log.w(TAG, "discoverServices refused serial=$SerialNumber")
        }
    }

    /** Re-run discovery up to twice, three seconds apart, then give up on the link. */
    private val discoveryRetryRunnable = Runnable {
        val gatt = mBluetoothGatt ?: return@Runnable
        if (phase != Phase.DISCOVERING) return@Runnable
        if (discoveryRetries < DISCOVERY_MAX_RETRIES) {
            discoveryRetries++
            Log.w(TAG, "service discovery retry $discoveryRetries serial=$SerialNumber")
            startDiscovery(gatt)
        } else {
            Log.w(TAG, "service discovery failed; disconnecting serial=$SerialNumber")
            reconnectNow("service discovery failed")
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (!isCurrentGatt(gatt)) return
        handler.post { handleServicesDiscovered(gatt, status) }
    }

    private fun handleServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (stop || gatt !== mBluetoothGatt || phase != Phase.DISCOVERING) return
        val service = if (status == BluetoothGatt.GATT_SUCCESS) gatt.getService(SibionicsConstants.SERVICE_FF30) else null
        notifyChar = service?.getCharacteristic(SibionicsConstants.CHAR_NOTIFY_FF31)
        writeChar = service?.getCharacteristic(SibionicsConstants.CHAR_WRITE_FF32)
        if (notifyChar == null || writeChar == null) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Discovery completed and the device simply lacks the GS3 GATT layout.
                // Retrying or reconnecting cannot change that.
                rejectIncompatibleDevice(gatt, if (service == null) "FF30 missing" else "FF31/FF32 missing")
            } else {
                // Discovery itself failed; the retry timer re-runs it, then disconnects.
                Log.w(TAG, "service discovery status=$status serial=$SerialNumber")
            }
            return
        }
        handler.removeCallbacks(discoveryRetryRunnable)
        identityChar = gatt.getService(SibionicsGs3Identity.DEVICE_INFORMATION_SERVICE)
            ?.getCharacteristic(SibionicsGs3Identity.SERIAL_NUMBER_STRING)
        val identity = identityChar
        if (identity != null && runCatching { gatt.readCharacteristic(identity) }.getOrDefault(false)) {
            phase = Phase.READING_IDENTITY
            handler.postDelayed(identityReadTimeoutRunnable, IDENTITY_READ_TIMEOUT_MS)
        } else {
            Log.w(TAG, "serial number characteristic unavailable=${identity == null} serial=$SerialNumber")
            enableNotifications(gatt)
        }
    }

    private fun rejectIncompatibleDevice(gatt: BluetoothGatt, reason: String) {
        val address = SibionicsConstants.normalizeBleAddress(gatt.device?.address) ?: knownBleAddress()
        incompatibleAddress = address
        lastFailure = "Not a Sibionics GS3 CN sensor ($reason)"
        Log.w(TAG, "rejecting $address: $reason; not reconnecting serial=$SerialNumber")
        handler.removeCallbacks(reconnectRunnable)
        prepareForReconnect()
        phase = Phase.ERROR
        setStatus(lastFailure)
    }

    private fun isIncompatible(address: String?): Boolean {
        val rejected = incompatibleAddress ?: return false
        return SibionicsConstants.normalizeBleAddress(address)?.equals(rejected, ignoreCase = true) == true
    }

    private val identityReadTimeoutRunnable = Runnable {
        val gatt = mBluetoothGatt ?: return@Runnable
        if (phase == Phase.READING_IDENTITY) {
            Log.w(TAG, "serial number read timed out serial=$SerialNumber")
            enableNotifications(gatt)
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        handleCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        handleCharacteristicRead(gatt, characteristic, value, status)
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (characteristic.uuid != SibionicsGs3Identity.SERIAL_NUMBER_STRING || !isCurrentGatt(gatt)) return
        val snapshot = value.copyOf()
        handler.post {
            if (gatt !== mBluetoothGatt || phase != Phase.READING_IDENTITY) return@post
            handler.removeCallbacks(identityReadTimeoutRunnable)
            val mac = if (status == BluetoothGatt.GATT_SUCCESS) SibionicsGs3Identity.macFromSerialNumber(snapshot) else null
            Log.i(
                TAG,
                "serial number status=$status value=${SibionicsGs3Protocol.toHex(snapshot)} " +
                    "identity=${mac?.let(SibionicsGs3Identity::format)} serial=$SerialNumber",
            )
            if (mac != null) rememberIdentity(mac)
            enableNotifications(gatt)
        }
    }

    // legacy BLE API: required at minSdk 26; the API 33 overloads are not a drop-in replacement
    @Suppress("DEPRECATION")
    private fun enableNotifications(gatt: BluetoothGatt) {
        val ch = notifyChar ?: return reconnectNow("notify characteristic lost")
        phase = Phase.ENABLING_NOTIFY
        gatt.setCharacteristicNotification(ch, true)
        val descriptor = ch.getDescriptor(SibionicsConstants.CCCD)
        if (descriptor == null) {
            startHandshake()
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!runCatching { gatt.writeDescriptor(descriptor) }.getOrDefault(false)) {
            Log.w(TAG, "CCCD write refused; starting handshake anyway serial=$SerialNumber")
            startHandshake()
        } else {
            armStepTimeout("cccd", HANDSHAKE_STEP_TIMEOUT_MS)
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (!isCurrentGatt(gatt)) return
        if (descriptor.characteristic?.uuid != SibionicsConstants.CHAR_NOTIFY_FF31) return
        handler.post {
            if (gatt !== mBluetoothGatt || phase != Phase.ENABLING_NOTIFY) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "CCCD write status=$status serial=$SerialNumber")
            startHandshake()
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        if (!isCurrentGatt(gatt)) return
        if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
        Log.i(TAG, "MTU changed mtu=$mtu status=$status serial=$SerialNumber")
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "write ${characteristic.uuid} status=$status")
        if (!isCurrentGatt(gatt) || characteristic.uuid != SibionicsConstants.CHAR_WRITE_FF32) return
        handler.post {
            // After a callback timeout the link is torn down, so a late callback for that
            // write finds a retired GATT above; this guards the remaining window.
            if (gatt !== mBluetoothGatt || !writeInFlight) return@post
            handler.removeCallbacks(writeCallbackTimeoutRunnable)
            writeInFlight = false
            pumpWrites()
        }
    }

    // legacy BluetoothGattCallback overload: the platform still invokes it below API 33
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!isCurrentGatt(gatt)) return
        if (characteristic.uuid == SibionicsConstants.CHAR_NOTIFY_FF31) {
            notificationDispatcher.dispatch(characteristic.value ?: ByteArray(0))
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (!isCurrentGatt(gatt)) return
        if (characteristic.uuid == SibionicsConstants.CHAR_NOTIFY_FF31) notificationDispatcher.dispatch(value)
    }

    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean {
        val active = mBluetoothGatt
        return gatt !== retiredGatt && (active == null || gatt === active)
    }

    // ---- handshake ----

    private fun startHandshake() {
        if (phase == Phase.HANDSHAKE || phase == Phase.STREAMING) return
        handler.removeCallbacks(stepTimeoutRunnable)
        val mac = identityMac ?: advertisedIdentityMac
        if (mac == null) {
            // The BLE address is never the protocol identity; without 2A25 or an
            // advertisement there is nothing correct to authenticate with.
            lastFailure = "GS3 identity unavailable"
            Log.w(TAG, "no identity from 0x2A25 or advertisement; not handshaking serial=$SerialNumber")
            phase = Phase.ERROR
            setStatus(lastFailure)
            beginIdentityRecovery()
            return
        }
        if (identityMac == null) rememberIdentity(mac)
        lastFailure = ""
        val newSession = SibionicsGs3Session(mac, cursor) { System.currentTimeMillis() }
        session = newSession
        phase = Phase.HANDSHAKE
        setStatus("Authenticating")
        Log.i(TAG, "handshake identity=${SibionicsGs3Identity.format(mac)} cursor=$cursor mtu=$mtu serial=$SerialNumber")
        apply(newSession.start())
    }

    private fun handleIncoming(bytes: ByteArray) {
        val current = session ?: return
        if (stop) return
        lastFrameMs = System.currentTimeMillis()
        armHighPriorityCap()
        apply(current.onNotification(bytes))
    }

    private fun apply(actions: List<SibionicsGs3Session.Action>) {
        val current = session
        for (action in actions) {
            when (action) {
                is SibionicsGs3Session.Action.Send -> enqueueWrite(action)
                is SibionicsGs3Session.Action.Page -> processPage(action.page)
                is SibionicsGs3Session.Action.Note -> Log.i(TAG, "${action.text} serial=$SerialNumber")
                is SibionicsGs3Session.Action.Failed -> {
                    onSessionFailed(action.reason)
                    return
                }
            }
        }
        if (current == null || current !== session) return
        when (current.state) {
            SibionicsGs3Session.State.STREAMING -> {
                handler.removeCallbacks(stepTimeoutRunnable)
                stepTimeoutKey = ""
                if (phase != Phase.STREAMING) {
                    phase = Phase.STREAMING
                    lastPageMs = System.currentTimeMillis()
                    scheduleStaleDataCheck()
                    if (latestReadingTimeMs <= 0L ||
                        System.currentTimeMillis() - latestReadingTimeMs > CURRENT_FRESH_MS
                    ) {
                        setStatus(waitingForDataStatus())
                    }
                    Log.i(TAG, "streaming v3=${current.isV3} cipher=${current.cipher} serial=$SerialNumber")
                }
            }
            // Keyed by the request count: a retry in the same state (IdBind seq 2, the next
            // auth fallback) gets its own deadline; an ignored notification sends nothing
            // and so cannot extend the current one.
            SibionicsGs3Session.State.WAITING_AUTH ->
                armStepTimeout("auth-${current.authAttempt}-${current.sendCount}", AUTH_STEP_TIMEOUT_MS)
            else -> armStepTimeout("${current.state.name}-${current.sendCount}", HANDSHAKE_STEP_TIMEOUT_MS)
        }
    }

    /** Arms the step deadline once per step; ignored notifications must not extend it. */
    private fun armStepTimeout(key: String, delayMs: Long) {
        if (key == stepTimeoutKey) return
        stepTimeoutKey = key
        handler.removeCallbacks(stepTimeoutRunnable)
        handler.postDelayed(stepTimeoutRunnable, delayMs)
    }

    private val stepTimeoutRunnable = Runnable {
        stepTimeoutKey = ""
        val current = session
        if (current == null) {
            if (phase == Phase.ENABLING_NOTIFY) {
                Log.w(TAG, "CCCD write never confirmed; starting handshake serial=$SerialNumber")
                startHandshake()
            }
            return@Runnable
        }
        Log.w(TAG, "step timeout state=${current.state} serial=$SerialNumber")
        apply(current.onStepTimeout())
    }

    private fun onSessionFailed(reason: String) {
        Log.w(TAG, "session failed: $reason serial=$SerialNumber")
        lastFailure = "GS3: $reason"
        phase = Phase.ERROR
        setStatus(lastFailure)
        reconnectNow(reason, FAILED_RECONNECT_DELAY_MS)
    }

    private fun rememberIdentity(mac: ByteArray) {
        if (identityMac?.contentEquals(mac) == true) return
        identityMac = mac.copyOf()
        Applic.app?.let { SibionicsRegistry.saveGs3IdentityMac(it, SerialNumber, SibionicsGs3Identity.format(mac)) }
    }

    private fun beginIdentityRecovery() {
        val address = knownBleAddress()
        prepareForReconnect()
        recoveryPurpose = RecoveryPurpose.IDENTITY
        if (address != null && advertisementRecovery.start(address, ADVERTISEMENT_RECOVERY_TIMEOUT_MS)) {
            Log.i(TAG, "scanning advertisements for the GS3 identity serial=$SerialNumber")
        } else {
            scheduleReconnect("identity unavailable", FAILED_RECONNECT_DELAY_MS)
        }
    }

    /**
     * After Android accepted connectGatt() but the link failed or never called back, scan once
     * for the saved address: some post-boot Bluetooth stacks will not connect to it until its
     * advertisement has been seen again. Same rule and timeout as [SibionicsBleManager].
     */
    private fun beginAdvertisementRecovery(reason: String, failedDuringConnect: Boolean = true): Boolean {
        val address = knownBleAddress()
        if (!SibionicsSessionPolicy.shouldUseAdvertisementRecovery(
                failedDuringConnect = failedDuringConnect,
                isStopped = stop,
                isPaused = false,
                hasKnownAddress = address != null,
                recoveryAlreadyActive = advertisementRecovery.isActive,
            )
        ) {
            return advertisementRecovery.isActive
        }
        handler.removeCallbacks(reconnectRunnable)
        phase = Phase.IDLE
        setStatus(connectingStatus())
        recoveryPurpose = RecoveryPurpose.CONNECT
        Log.i(TAG, "scan for saved sensor after $reason serial=$SerialNumber")
        return advertisementRecovery.start(address!!, ADVERTISEMENT_RECOVERY_TIMEOUT_MS)
    }

    private fun finishAdvertisementRecovery(advertisement: SibionicsAdvertisement?) {
        if (stop) return
        val purpose = recoveryPurpose
        recoveryPurpose = RecoveryPurpose.CONNECT
        if (advertisement == null) {
            Log.w(TAG, "saved sensor advertisement not seen ($purpose) serial=$SerialNumber")
            phase = Phase.IDLE
            scheduleReconnect(
                "advertisement not seen",
                if (purpose == RecoveryPurpose.IDENTITY) FAILED_RECONNECT_DELAY_MS else RECONNECT_DELAY_MS,
            )
            return
        }
        advertisement.identityMac?.let { mac ->
            Log.i(TAG, "identity ${SibionicsGs3Identity.format(mac)} seen in advertisement serial=$SerialNumber")
            advertisedIdentityMac = mac
        }
        setDevice(advertisement.device)
        phase = Phase.IDLE
        if (purpose == RecoveryPurpose.IDENTITY && identityMac == null && advertisedIdentityMac == null) {
            scheduleReconnect("identity not advertised", FAILED_RECONNECT_DELAY_MS)
            return
        }
        Log.i(TAG, "saved sensor advertisement recovered ($purpose) serial=$SerialNumber")
        connectDevice(0)
    }

    // ---- writes ----

    private fun enqueueWrite(send: SibionicsGs3Session.Action.Send) {
        Log.i(TAG, "queue ${send.label} plain=${loggable(send.plain, send.sensitive)} serial=$SerialNumber")
        if (send.wire.size > mtu - 3) {
            // Only a write with response is split by the stack (long write).
            Log.d(TAG, "${send.label} is ${send.wire.size} bytes over mtu=$mtu")
        }
        writeQueue.addLast(PendingWrite(send.wire, send.label, send.sensitive))
        pumpWrites()
    }

    /**
     * Auth frames carry the session key — in PLAIN the wire bytes are the key itself, and the
     * encrypted forms are reversible with constants in this file — so only their header and
     * length are ever logged. Log.i lines are kept in the trace ring and exported with it.
     */
    private fun loggable(bytes: ByteArray, sensitive: Boolean): String =
        if (sensitive) {
            "${SibionicsGs3Protocol.toHex(bytes.copyOf(minOf(2, bytes.size)))} …(${bytes.size} bytes, redacted)"
        } else {
            SibionicsGs3Protocol.toHex(bytes)
        }

    private fun pumpWrites() {
        if (writeInFlight) return
        val pending = writeQueue.peekFirst() ?: return
        val bytes = pending.bytes
        val label = pending.label
        val gatt = mBluetoothGatt
        val ch = writeChar
        if (gatt == null || ch == null) {
            writeQueue.clear()
            return
        }
        if (issueWrite(gatt, ch, bytes)) {
            writeQueue.removeFirst()
            writeAttempts = 0
            writeInFlight = true
            handler.removeCallbacks(writeCallbackTimeoutRunnable)
            handler.postDelayed(writeCallbackTimeoutRunnable, WRITE_CALLBACK_TIMEOUT_MS)
            Log.i(TAG, "write $label bytes=${loggable(bytes, pending.sensitive)}")
            return
        }
        writeAttempts++
        if (writeAttempts >= WRITE_RETRY_MAX_ATTEMPTS) {
            Log.w(TAG, "write $label gave up after $writeAttempts attempts")
            writeQueue.removeFirst()
            writeAttempts = 0
            handler.post(::pumpWrites)
        } else {
            handler.removeCallbacks(writeRetryRunnable)
            handler.postDelayed(writeRetryRunnable, WRITE_RETRY_DELAY_MS)
        }
    }

    private val writeRetryRunnable = Runnable { pumpWrites() }

    /**
     * The stack never confirmed the last write. The vendor just unblocks its queue, but a
     * callback can carry no write identity: a late one for this write would then complete
     * whichever write went out next, releasing commands before the sensor has them. Start
     * over on a fresh connection instead.
     */
    private val writeCallbackTimeoutRunnable = Runnable {
        if (writeInFlight && mBluetoothGatt != null) {
            Log.w(TAG, "write callback timeout; reconnecting serial=$SerialNumber")
            reconnectNow("write callback timeout")
        }
    }

    @Suppress("DEPRECATION")
    private fun issueWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        ch.writeType = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        ch.value = bytes
        return runCatching { gatt.writeCharacteristic(ch) }.getOrDefault(false)
    }

    // ---- data ----

    private fun processPage(page: SibionicsGs3Protocol.Message.GlucosePage) {
        val now = System.currentTimeMillis()
        lastPageMs = now
        Log.i(
            TAG,
            "page cmd=0x%02X start=%d count=%d trailer=%d cursor=%d".format(
                page.cmd, page.startIndex, page.records.size, page.trailer, session?.cursor ?: cursor,
            ),
        )
        session?.cursor?.let { next ->
            if (next != cursor) {
                cursor = next
                Applic.app?.let { SibionicsRegistry.saveLastIndex(it, SerialNumber, next) }
            }
        }
        val plausible = page.records.filter { it.isPlausible }
        if (plausible.size != page.records.size) {
            Log.w(TAG, "dropped ${page.records.size - plausible.size} implausible record(s) serial=$SerialNumber")
        }
        if (plausible.isEmpty()) return
        anchorSession(plausible.minBy { it.ageRecords }, now)
        val readings = plausible.mapNotNull { rec ->
            val sampleMs = startTimeMs + rec.index * SibionicsGs3Protocol.RECORD_INTERVAL_MS
            if (sampleMs <= 0L || sampleMs > now + MAX_FUTURE_DRIFT_MS) null
            else Reading(rec.index, sampleMs, rec.mgdl, rec.trend)
        }.sortedBy { it.index }
        if (readings.isEmpty()) return
        val newest = readings.last()
        val newestRecord = plausible.first { it.index == newest.index }
        val live = newest.takeIf {
            newestRecord.ageRecords == 0 && it.index > lastLiveIndex && abs(now - it.sampleMs) <= CURRENT_FRESH_MS
        }
        val history = if (live != null) readings.dropLast(1) else readings
        if (history.isNotEmpty()) storeHistory(history)
        live?.let(::publishLiveReading)
        UiRefreshBus.requestDataRefresh()
    }

    /**
     * Timestamps come from the record index, so a page fetched twice (the cursor deliberately
     * overlaps) lands on the same minutes. The anchor (time of index 0) is taken from the
     * newest record's age and only moved when the sensor clock has drifted past tolerance.
     */
    private fun anchorSession(reference: SibionicsGs3Protocol.Record, now: Long) {
        val candidate = now -
            reference.ageRecords.toLong() * SibionicsGs3Protocol.RECORD_INTERVAL_MS -
            reference.index.toLong() * SibionicsGs3Protocol.RECORD_INTERVAL_MS
        val previous = startTimeMs
        if (previous > 0L && abs(candidate - previous) <= ANCHOR_TOLERANCE_MS) return
        if (previous > 0L && reference.ageRecords != 0) {
            // Only a current record re-anchors an established session: its time is "now",
            // whereas an older record's time rests on the page's age arithmetic.
            Log.w(TAG, "page implies start=$candidate, keeping start=$previous (ref idx=${reference.index} age=${reference.ageRecords})")
            return
        }
        startTimeMs = candidate
        Applic.app?.let { SibionicsRegistry.saveStartTimeMs(it, SerialNumber, candidate) }
        Log.i(TAG, "session anchor start=$candidate previous=$previous ref idx=${reference.index} age=${reference.ageRecords}")
        // The native window is deliberately left where it is. rebaseDirectStreamWindow clears
        // every stored sample, and the cursor has moved past the pages that wrote them, so they
        // would never come back. The window only sets the origin of minute slots; records keep
        // their absolute times, so later readings still land in the right minutes.
    }

    private fun storeHistory(readings: List<Reading>) {
        val timestamps = LongArray(readings.size) { readings[it].sampleMs }
        val values = FloatArray(readings.size) { readings[it].glucoseMgdl }
        HistorySyncAccess.storeSensorHistoryBatchAsync(SerialNumber, timestamps, values, values.copyOf())
        mirrorIntoNative(readings)
    }

    private fun mirrorIntoNative(readings: List<Reading>): Boolean = synchronized(mirrorLock) {
        val name = SerialNumber ?: return false
        runCatching {
            val startSec = (startTimeMs / 1000L).takeIf { it > 0L }
                ?: (readings.first().sampleMs / 1000L - 3600L).coerceAtLeast(1L)
            if (nativeShellStartSec == 0L) {
                if (Natives.ensureSensorShellWithCapacity(name, startSec, NATIVE_STREAM_CAPACITY_MINUTES) == 0L) {
                    Natives.ensureSensorShell(name, startSec)
                }
                Natives.setSensorManagedFamily(name, ManagedSensorUiFamily.SIBIONICS.nativeCode)
                if (!Natives.hasSensorStreamCapacity(name, NATIVE_STREAM_CAPACITY_MINUTES)) {
                    Log.e(TAG, "native stream capacity unavailable for $name")
                    return@runCatching false
                }
                nativeShellStartSec = startSec
            }
            // Native scales glucose x10 internally; callers pass mg/dL / 10.
            val times = LongArray(readings.size) { readings[it].sampleMs / 1000L }
            val values = FloatArray(readings.size) { readings[it].glucoseMgdl / 10f }
            val raws = FloatArray(readings.size) { readings[it].glucoseMgdl }
            val temperatures = FloatArray(readings.size)
            Natives.addGlucoseStreamBatchWithRawTemp(times, values, raws, temperatures, name) > 0
        }.onFailure {
            Log.stack(TAG, "mirrorIntoNative", it)
        }.getOrDefault(false)
    }

    private fun publishLiveReading(reading: Reading) {
        settleConnectionPriority()
        val previousTime = latestReadingTimeMs
        val previousValue = latestGlucoseMgdl
        latestReadingTimeMs = reading.sampleMs
        latestGlucoseMgdl = reading.glucoseMgdl
        latestTrend = reading.trend
        lastLiveIndex = reading.index
        latestRateMgdlPerMin =
            if (previousTime > 0L && previousValue.isFinite() && reading.sampleMs > previousTime) {
                (reading.glucoseMgdl - previousValue) / ((reading.sampleMs - previousTime) / 60_000f)
            } else {
                Float.NaN
            }
        Applic.app?.let {
            SibionicsRegistry.saveLastReading(it, SerialNumber, reading.sampleMs, reading.glucoseMgdl, reading.glucoseMgdl)
        }
        if (mirrorIntoNative(listOf(reading))) {
            NightscoutUploadWake.afterLiveNativeWrite("sibionics-gs3", reading.sampleMs)
        }
        markLocalReadingAccepted(reading.sampleMs)
        val rate = latestRateMgdlPerMin.takeIf { it.isFinite() } ?: 0f
        HistorySyncAccess.storeCurrentReadingAsync(
            reading.sampleMs,
            reading.glucoseMgdl,
            reading.glucoseMgdl,
            rate,
            SerialNumber,
        )
        val display = toDisplay(reading.glucoseMgdl)
        val displayRate = if (Applic.unit == 1) rate / SibionicsConstants.MGDL_PER_MMOLL else rate
        SuperGattCallback.processExternalCurrentReading(
            SerialNumber,
            // The sensor reports finished values; there is no separate raw lane.
            tk.glucodata.LiveReadingLanes.stock(display, Float.NaN),
            displayRate,
            reading.sampleMs,
            SibionicsConstants.SENSOR_GEN,
        )
        setStatus(connectedStatus())
        Log.i(TAG, "live idx=${reading.index} mgdl=%.1f trend=${reading.trend}".format(reading.glucoseMgdl))
    }

    // ---- timers ----

    private fun scheduleStaleDataCheck() {
        handler.removeCallbacks(staleDataRunnable)
        handler.postDelayed(staleDataRunnable, STALE_DATA_CHECK_MS)
    }

    /** Every 90 s: drop a link silent for over 180 s, otherwise ask for the next page. */
    private val staleDataRunnable = Runnable {
        val current = session ?: return@Runnable
        if (stop || phase != Phase.STREAMING) return@Runnable
        if (System.currentTimeMillis() - lastPageMs > STALE_DATA_DISCONNECT_MS) {
            reconnectNow("no data for over ${STALE_DATA_DISCONNECT_MS / 1000}s")
            return@Runnable
        }
        current.requestNextPage()?.let { apply(listOf(it)) }
        scheduleStaleDataCheck()
    }

    private fun armLinkWatchdog() {
        handler.removeCallbacks(linkWatchdogRunnable)
        handler.postDelayed(linkWatchdogRunnable, LINK_WATCHDOG_MS)
    }

    private val linkWatchdogRunnable = Runnable {
        if (stop || mBluetoothGatt == null) return@Runnable
        if (System.currentTimeMillis() - lastFrameMs >= LINK_WATCHDOG_MS) {
            reconnectNow("link watchdog (phase=$phase)")
        } else {
            armLinkWatchdog()
        }
    }

    /**
     * The vendor waits 90 s x 2^n before calling CONNECTING stale; SibionicsBleManager gives up
     * after 20 s and scans for the advertisement, which is what recovers the post-boot stacks.
     */
    private fun armConnectCallbackTimeout(requestedDelayMs: Long) {
        handler.removeCallbacks(connectCallbackTimeoutRunnable)
        handler.postDelayed(
            connectCallbackTimeoutRunnable,
            SibionicsSessionPolicy.connectCallbackTimeoutDelayMs(
                requestedDelayMs = requestedDelayMs,
                callbackTimeoutMs = CONNECT_CALLBACK_TIMEOUT_MS,
            ),
        )
    }

    private val connectCallbackTimeoutRunnable = Runnable {
        if (!SibionicsSessionPolicy.shouldRecoverSetupTimeout(
                isPending = phase == Phase.CONNECTING,
                isStopped = stop,
                isPaused = false,
            )
        ) {
            return@Runnable
        }
        Log.w(TAG, "connectGatt callback timeout serial=$SerialNumber")
        prepareForReconnect()
        setStatus(connectingStatus())
        if (!beginAdvertisementRecovery("connectGatt callback timeout")) {
            scheduleReconnect("connectGatt callback timeout")
        }
    }

    /**
     * Raised to CONNECTION_PRIORITY_HIGH on connect, relaxed once a live reading lands or at
     * most [HIGH_PRIORITY_MAX_MS] after the sensor first answers. SibionicsBleManager found that
     * holding the 7.5 ms interval through a long transfer drops the link with status 147 about
     * nine seconds in, on every connection.
     */
    private fun armHighPriorityCap() {
        if (connectionPrioritySettled || highPriorityCapArmed) return
        highPriorityCapArmed = true
        handler.postDelayed(highPriorityCapRunnable, HIGH_PRIORITY_MAX_MS)
    }

    private val highPriorityCapRunnable = Runnable {
        if (!stop && mBluetoothGatt != null) settleConnectionPriority()
    }

    private fun settleConnectionPriority() {
        handler.removeCallbacks(highPriorityCapRunnable)
        if (connectionPrioritySettled) return
        connectionPrioritySettled = true
        val connectedGatt = mBluetoothGatt ?: return
        handler.postDelayed({
            if (!stop && mBluetoothGatt === connectedGatt) {
                runCatching { connectedGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
                Log.d(TAG, "settled BLE priority serial=$SerialNumber")
            }
        }, PRIORITY_SETTLE_DELAY_MS)
    }

    private val reconnectRunnable = Runnable {
        if (!stop) {
            prepareForReconnect()
            connectDevice(0)
        }
    }

    private fun scheduleReconnect(reason: String, delayMs: Long = RECONNECT_DELAY_MS) {
        if (stop) return
        Log.i(TAG, "reconnect in ${delayMs}ms: $reason serial=$SerialNumber")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun reconnectNow(reason: String, delayMs: Long = RECONNECT_DELAY_MS) {
        prepareForReconnect()
        scheduleReconnect(reason, delayMs)
    }

    private fun resetConnectionState() {
        session = null
        stepTimeoutKey = ""
        discoveryRetries = 0
        notifyChar = null
        writeChar = null
        identityChar = null
        writeQueue.clear()
        writeInFlight = false
        writeAttempts = 0
        mtu = 23
        handler.removeCallbacks(discoveryRetryRunnable)
        handler.removeCallbacks(identityReadTimeoutRunnable)
        handler.removeCallbacks(stepTimeoutRunnable)
        handler.removeCallbacks(staleDataRunnable)
        handler.removeCallbacks(linkWatchdogRunnable)
        handler.removeCallbacks(writeRetryRunnable)
        handler.removeCallbacks(writeCallbackTimeoutRunnable)
        handler.removeCallbacks(highPriorityCapRunnable)
    }

    @Synchronized
    private fun prepareForReconnect() {
        notificationDispatcher.invalidateSession()
        handler.removeCallbacks(connectCallbackTimeoutRunnable)
        resetConnectionState()
        retiredGatt = mBluetoothGatt
        mActiveBluetoothDevice = null
        phase = Phase.IDLE
        // Also cancels a delayed SuperGattCallback connection task.
        closeGattTransport()
    }

    // ---- maintenance: nothing to offer; the GS3 notes define no reset or calibration ----

    override fun supportsResetAction(): Boolean = false

    // ---- UI ----

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val time = latestReadingTimeMs
        val glucose = latestGlucoseMgdl
        if (time <= 0L || !glucose.isFinite() || glucose <= 0f) return null
        if (abs(System.currentTimeMillis() - time) > maxAgeMillis) return null
        val rate = latestRateMgdlPerMin
        return ManagedSensorCurrentSnapshot(
            timeMillis = time,
            glucoseValue = toDisplay(glucose),
            rawGlucoseValue = Float.NaN,
            calibratedGlucoseValue = Float.NaN,
            rate = if (rate.isFinite()) {
                if (Applic.unit == 1) rate / SibionicsConstants.MGDL_PER_MMOLL else rate
            } else {
                Float.NaN
            },
            sensorGen = SibionicsConstants.SENSOR_GEN,
        )
    }

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot =
        ManagedSensorUiSnapshot(
            serial = SerialNumber,
            displayName = mygetDeviceName(),
            deviceAddress = mActiveDeviceAddress ?: record?.address ?: "Unknown",
            uiFamily = ManagedSensorUiFamily.SIBIONICS,
            connectionStatus = if (phase == Phase.STREAMING) connectedStatus() else constatstatusstr.orEmpty(),
            detailedStatus = getDetailedBleStatus(),
            subtitleStatus = getDetailedBleStatus().ifBlank { constatstatusstr.orEmpty() },
            showConnectionStatusInDetails = true,
            startTimeMs = startTimeMs,
            officialEndMs = if (startTimeMs > 0L) startTimeMs + SibionicsConstants.Variant.GS3_CN.officialLifetimeMs else 0L,
            expectedEndMs = expectedEndMs(),
            isUiEnabled = !stop,
            isActive = SensorIdentity.matches(activeSensorId, SerialNumber),
            rssi = readrssi,
            dataptr = 0L,
            viewMode = viewMode,
            supportsDisplayModes = false,
            supportsManualCalibration = false,
            supportsHardwareReset = false,
            supportsClearCalibration = false,
            sensorDetailTelemetry = detailTelemetry(),
            isVendorPaired = true,
            isVendorConnected = mBluetoothGatt != null && phase == Phase.STREAMING,
            sensorRemainingHours = remainingHours(),
            sensorAgeHours = if (startTimeMs <= 0L) -1 else ((System.currentTimeMillis() - startTimeMs) / 3_600_000L).toInt(),
            batteryPercent = -1,
            // "Sibionics GS3" is the model string the GS3 badge keys on.
            vendorModel = "Sibionics GS3",
        )

    override fun getDetailedBleStatus(): String {
        if (stop) return disconnectedStatus()
        return when (phase) {
            Phase.IDLE -> lastFailure.ifBlank { disconnectedStatus() }
            Phase.CONNECTING -> connectingStatus()
            Phase.DISCOVERING -> "Discovering"
            Phase.READING_IDENTITY -> "Reading identity"
            Phase.ENABLING_NOTIFY -> "Subscribing"
            Phase.HANDSHAKE -> session?.state?.let { handshakeLabel(it) } ?: "Authenticating"
            Phase.STREAMING -> if (latestReadingTimeMs > 0L &&
                System.currentTimeMillis() - latestReadingTimeMs <= CURRENT_FRESH_MS
            ) {
                connectedStatus()
            } else {
                waitingForDataStatus()
            }
            Phase.ERROR -> lastFailure.ifBlank { "Error" }
        }
    }

    private fun handshakeLabel(state: SibionicsGs3Session.State): String = when (state) {
        SibionicsGs3Session.State.WAITING_AUTH -> "Authenticating"
        SibionicsGs3Session.State.WAITING_BIND_IN,
        SibionicsGs3Session.State.WAITING_ID_BIND -> "Binding"
        SibionicsGs3Session.State.WAITING_ACTIVATE -> "Activating"
        SibionicsGs3Session.State.WAITING_TIME -> "Syncing time"
        else -> "Requesting data"
    }

    private fun detailTelemetry(): String = buildString {
        val current = session
        append(if (current?.isV3 == false) "V2" else "V3")
        current?.let {
            append(" • ")
            append(it.cipher.name)
        }
        append(" • idx=")
        append(cursor)
        if (latestTrend != SibionicsProtocol.Trend.NOT_DETERMINED) {
            append(" • ")
            append(latestTrend.name.lowercase())
        }
    }

    private fun expectedEndMs(): Long =
        if (startTimeMs > 0L) startTimeMs + SibionicsConstants.Variant.GS3_CN.expectedLifetimeMs else 0L

    private fun remainingHours(): Int {
        val end = expectedEndMs()
        if (end <= 0L) return -1
        val remaining = end - System.currentTimeMillis()
        return if (remaining <= 0L) 0 else (remaining / 3_600_000L).toInt()
    }

    private fun toDisplay(mgdl: Float): Float =
        if (Applic.unit == 1) mgdl / SibionicsConstants.MGDL_PER_MMOLL else mgdl

    private fun connectedStatus(): String =
        runCatching { Applic.app.getString(R.string.status_connected) }.getOrDefault("Connected")

    private fun disconnectedStatus(): String =
        runCatching { Applic.app.getString(R.string.status_disconnected) }.getOrDefault("Disconnected")

    private fun connectingStatus(): String =
        runCatching { Applic.app.getString(R.string.connecting) }.getOrDefault("Connecting...")

    private fun waitingForDataStatus(): String =
        runCatching { Applic.app.getString(R.string.status_waiting_for_data) }.getOrDefault("Connected, waiting for data...")

    private fun setStatus(status: String) {
        constatstatusstr = status
        UiRefreshBus.requestStatusRefresh()
    }

    private fun knownBleAddress(): String? =
        SibionicsConstants.normalizeBleAddress(mActiveDeviceAddress)
            ?: SibionicsConstants.normalizeBleAddress(record?.address)
            ?: SibionicsConstants.normalizeBleAddress(SibionicsRegistry.findRecord(Applic.app, SerialNumber)?.address)

    // BluetoothAdapter.getDefaultAdapter(): the fallback when no BluetoothManager is reachable; minSdk 26
    @Suppress("DEPRECATION")
    private fun hydrateBluetoothDeviceFromAddress(): Boolean {
        val address = knownBleAddress() ?: return false
        mActiveDeviceAddress = address
        if (mActiveBluetoothDevice?.address?.equals(address, ignoreCase = true) == true) return true
        val adapter = runCatching {
            (Applic.app?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
        }.getOrNull() ?: return false
        mActiveBluetoothDevice = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        return mActiveBluetoothDevice != null
    }
}
