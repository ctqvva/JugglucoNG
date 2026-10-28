// MedtrumBleManager.kt — BLE transport for the Medtrum EasySense patch.
//
// Ported from the vendor app (com.medtrum.healthcareforandroid):
//   BLEBaseCentral: service/characteristic constants, writeCommand chunking,
//                   parseCharacteristicValue reassembly, authorizeRequest
//   SensorInstance:  command builders and the CGM_DATA record
// Cross-checked step by step against the 2026-10-17 HCI capture (SN 0x8ADF6216).
//
// Lifecycle:
//   1) connectDevice()                → GATT connect
//   2) onConnectionStateChange(CONNECTED) → discoverServices
//   3) onServicesDiscovered           → enable CGM_DATA notify + CTRLPT indicate
//   4) onDescriptorWrite (both)       → send AUTH_REQ
//   5) onCharacteristicChanged(CTRLPT)→ auth result → calibrateTime + readState
//   6) onCharacteristicChanged(CGM_DATA) → parse record → publish glucose
//
// Reconnect: a lost link goes straight back to the known address (works with the screen off);
// only repeated failed attempts fall back to a scan by advertised serial (MedtrumReconnectPolicy).
// The HandlerThread lives as long as the driver: transient closes keep it, only a stopped driver
// quits it.

package tk.glucodata.drivers.medtrum

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
import java.io.ByteArrayOutputStream
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.CalibrationAccess
import tk.glucodata.HistorySyncAccess
import tk.glucodata.LiveReadingLanes
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

@SuppressLint("MissingPermission")
class MedtrumBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), MedtrumDriver {

    companion object {
        private const val TAG = "MedtrumBLE"

        /** SuperGattCallback generation tag. */
        const val SENSOR_GEN = 0

        /** CGMS service (app: MEDTRUM_UUID_SERVICE_CGMS = 36866). */
        private val SERVICE_UUID = UUID.fromString("669A9002-0008-968F-E311-6050405558B3")

        /** CGM data characteristic (app: MEDTRUM_UUID_CHARACTERISTIC_CGM_DATA = 37184). */
        private val CHAR_CGM_DATA_UUID = UUID.fromString("669a9140-0008-968f-e311-6050405558b3")

        /** Control point (app: MEDTRUM_UUID_CHARACTERISTIC_CTRLPT = 37121), write + indicate. */
        private val CHAR_CTRL_POINT_UUID = UUID.fromString("669a9101-0008-968f-e311-6050405558b3")

        /** Standard CCCD descriptor. */
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Watchdog: no CGM record for this long means the link is dead. */
        private const val WATCHDOG_MS = 10L * 60L * 1000L

        /** Delay before attempting reconnect after disconnect. */
        private const val RECONNECT_DELAY_MS = 3_000L

        /** Delay before starting service discovery after connection. */
        private const val DISCOVERY_DELAY_MS = 250L

        /**
         * An attempt (direct connectGatt or scan window) that has not brought the link up by now
         * counts as failed. autoConnect=true never times out on its own, and a scan with the
         * screen off never reports, so without this the driver could wait forever.
         */
        private const val ATTEMPT_TIMEOUT_MS = 60_000L

        /**
         * A link that connected but never finished discovery, subscription and AUTH counts as a
         * failed attempt after this long. Without it a stuck step left the phase busy and every
         * later connectDevice() was skipped as a duplicate, so nothing recovered the sensor.
         */
        private const val SETUP_TIMEOUT_MS = 60_000L

        /** Glucose plausibility bounds (mg/dL). */
        private const val GLUCOSE_MIN_MGDL = 1.0
        private const val GLUCOSE_MAX_MGDL = 600.0

        /** Give up after this many failed AUTH rounds — a mistyped serial must not loop. */
        private const val MAX_AUTH_FAILURES = 3

        /**
         * The patch drops an idle link (~13 min observed), and the vendor app keeps it alive by
         * polling readState every couple of seconds. This also refreshes the counters a
         * calibration needs and the battery reading.
         */
        private const val KEEPALIVE_MS = 30_000L

        private const val MGDL_PER_MMOL = 18.0182f

        private val ARCHIVE_EXECUTOR: java.util.concurrent.Executor =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "Medtrum-calibration-archive").apply { isDaemon = true }
            }

        /** The live reading in display units, as a stock lane (calibrated once downstream). */
        internal fun liveLane(glucoseMgdl: Float, unit: Int): LiveReadingLanes =
            LiveReadingLanes.stock(if (unit == 1) glucoseMgdl / MGDL_PER_MMOL else glucoseMgdl, Float.NaN)

        /** Seconds between 2014-01-01 local and the Unix epoch start used by calibrateTime. */
        private val MEDTRUM_EPOCH_MS: Long = run {
            val cal = java.util.Calendar.getInstance()
            cal.set(2014, 0, 1, 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
    }

    // ---- State machine ----

    enum class Phase { IDLE, CONNECTING, DISCOVERING, SUBSCRIBING, AUTH, SYNCING, STREAMING, ERROR }

    @Volatile var phase: Phase = Phase.IDLE
        private set

    // ---- Background thread ----

    private val threadName = "Medtrum-$serial"
    private var handlerThread = HandlerThread(threadName).also { it.start() }
    private var liveHandler = Handler(handlerThread.looper)
    private var handlerQuit = false

    /**
     * Posts after quitSafely() are dropped without an error, which once left every reconnect,
     * discovery and AUTH step silently unrun. A driver that is running again (softReconnect after
     * a stop) gets a fresh thread; a stopped one keeps the dead handler so nothing is resurrected.
     */
    private val handler: Handler
        @Synchronized get() {
            if (handlerQuit && !stop) {
                handlerThread = HandlerThread(threadName).also { it.start() }
                liveHandler = Handler(handlerThread.looper)
                handlerQuit = false
            }
            return liveHandler
        }

    @Synchronized
    private fun quitHandlerThread() {
        liveHandler.removeCallbacksAndMessages(null)
        handlerQuit = true
        runCatching { handlerThread.quitSafely() }
    }

    internal fun isHandlerThreadRunning(): Boolean = !handlerQuit && handlerThread.isAlive

    // ---- BLE handles ----

    private var charCgmData: BluetoothGattCharacteristic? = null
    private var charCtrlPoint: BluetoothGattCharacteristic? = null

    // ---- Session state ----

    /** Serial number parsed from the constructor arg (decimal string → Long). */
    private val serialLong: Long = serial.toLongOrNull() ?: 0L

    @Volatile private var lastCgmAtMs: Long = 0L

    /** The CCCD we are still waiting on; AUTH is sent once both have landed. */
    @Volatile private var pendingCccd: UUID? = null

    /** Outgoing framed PDUs, sent one at a time. */
    private val txQueue = ArrayDeque<ByteArray>()
    @Volatile private var txInFlight = false

    /**
     * Guards the write queue and the CTRLPT reassembly buffer. Notifications and write
     * completions arrive on binder threads while the driver thread queues the next command, and
     * a disconnect can clear both from either side.
     */
    private val stateLock = Any()

    /** Incoming CTRLPT reassembly. */
    private val rxBuffer = ByteArrayOutputStream()
    private var rxExpected = -1

    /** Firmware version from the AUTH reply, for logging only — it is not the byte the
     *  app compares against 186 (that one comes from the advertisement). */
    @Volatile private var deviceVersion: MedtrumProtocol.Version? = null

    /** The auth dialect for the current link; created when both CCCDs are enabled. */
    private var authSession: MedtrumAuthSession? = null

    /** Consecutive failed AUTH rounds; a wrong serial stops after MAX_AUTH_FAILURES. */
    private var authFailureCount = 0

    /** Set once AUTH failed MAX_AUTH_FAILURES times; only a user reconnect clears it. */
    @Volatile private var authGaveUp = false

    /**
     * The patch accepted AUTH on this link. The patch can start pushing CGM_DATA as soon as the
     * notification is enabled, which is before AUTH; a reading is only published once this is set.
     * Cleared with the session on every disconnect.
     */
    @Volatile private var authAccepted = false

    /** Set when the connected device has no Medtrum service; only a user reconnect clears it. */
    @Volatile private var deviceRejected = false

    /** STATE_CONNECTED arrived for the current attempt. Decides link-lost vs attempt-failed. */
    @Volatile private var linkUp = false

    private val reconnectPolicy = MedtrumReconnectPolicy()

    /** Patch counters from the readState record; a calibrate command needs their difference. */
    @Volatile private var runningTime = 0L
    @Volatile private var insertTime = 0L

    /** A fingerstick value waiting for a fresh readState before it can be sent. */
    @Volatile private var pendingCalibrationMgdl: Int? = null

    /** Patch battery from the readState record, millivolts. */
    @Volatile private var batteryMillivolts = 0

    // ---- ManagedBluetoothSensorDriver ----

    override var viewMode: Int = 0

    // ---- MedtrumDriver contract ----

    override fun getSerialNumber(): Long = serialLong

    override fun getDeviceAddress(): String = mActiveDeviceAddress ?: ""

    override fun matchesManagedSensorId(sensorId: String?): Boolean {
        if (sensorId.isNullOrBlank()) return false
        return sensorId == serialLong.toString() || sensorId.equals(SerialNumber, ignoreCase = true)
    }

    override fun getDetailedBleStatus(): String = when (phase) {
        Phase.IDLE -> if (mActiveBluetoothDevice == null) "Searching" else "Idle"
        Phase.CONNECTING -> "Connecting"
        Phase.DISCOVERING -> "Discovering services"
        Phase.SUBSCRIBING -> "Subscribing"
        Phase.AUTH -> "Authenticating"
        Phase.SYNCING -> "Syncing time"
        Phase.STREAMING -> if (lastCgmAtMs > 0L) "Connected" else "Waiting for data"
        Phase.ERROR -> "Error"
    }

    override fun getConnectionStatus(): String = getDetailedBleStatus()

    override fun isConnected(): Boolean =
        phase == Phase.STREAMING || phase == Phase.SYNCING

    override fun getBatteryMillivolts(): Int = batteryMillivolts

    @Volatile private var sensorStartMs = 0L

    private val archiveRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Orders a reading's final writes against [terminateManagedSensor]. */
    private val readingLock = Any()

    override fun getStartTimeMs(): Long {
        if (sensorStartMs > 0L) return sensorStartMs
        // Known from an earlier session, so the card has its dates before the first readState.
        val stored = Applic.app?.let { MedtrumPersistence.getSensorStartMs(it) } ?: 0L
        if (stored > 0L) sensorStartMs = stored
        return sensorStartMs
    }

    /**
     * The transmitter's serial outlives one disposable sensor. When a readState shows a new
     * insertion, the previous electrode's software calibration is filed away, so it does not keep
     * correcting the replacement.
     */
    private fun noteSensorSession(state: MedtrumProtocol.StateRecord) {
        val ctx = Applic.app
        val candidate = MedtrumSensorLife.startFromElapsed(System.currentTimeMillis(), state.elapsedSeconds)
        val key = MedtrumSensorSession.key(state.sensorId, state.insertTime)
        val storedKey = ctx?.let { MedtrumPersistence.getSessionKey(it) }.orEmpty()
        val previousStart = getStartTimeMs()
        if (MedtrumSensorSession.isNewSession(storedKey, key, previousStart, candidate)) {
            val serial = serialLong.toString()
            val archiveId = MedtrumSensorSession.archiveId(serial, previousStart)
            Log.i(TAG, "New Medtrum sensor on $serial (was started ${previousStart}): archiving calibrations as $archiveId")
            // Recorded before the session key below, and kept until the move has happened.
            ctx?.let { MedtrumPersistence.setPendingArchive(it, archiveId, candidate) }
            sensorStartMs = 0L
            ctx?.let { MedtrumPersistence.setSensorStartMs(it, 0L) }
        }
        if (ctx != null && storedKey != key) MedtrumPersistence.setSessionKey(ctx, key)
        recordSensorStart(state.elapsedSeconds)
        flushPendingArchive()
    }

    /**
     * Files the previous sensor's calibrations away, off the BLE callback thread and off the driver
     * thread: a disconnect clears the driver's queue, and the session key is already saved, so
     * nothing would ever ask again. The request stays persisted until the move succeeded and is
     * retried by every later readState.
     */
    private fun flushPendingArchive() {
        val ctx = Applic.app ?: return
        val archiveId = MedtrumPersistence.getPendingArchive(ctx)
        if (archiveId.isBlank()) return
        if (!CalibrationAccess.isRegistered()) {
            // No calibration engine on this device (the watch): there is nothing to move.
            MedtrumPersistence.setPendingArchive(ctx, "")
            return
        }
        val cutoffMs = MedtrumPersistence.getPendingArchiveCutoff(ctx).takeIf { it > 0L } ?: getStartTimeMs()
        if (cutoffMs <= 0L) {
            // Without the replacement's insertion the move could sweep up its own points.
            MedtrumPersistence.setPendingArchive(ctx, "")
            return
        }
        val serial = serialLong.toString()
        // In effect before this readState's records are published; the move below may run much later.
        CalibrationAccess.declareSessionBoundary(serial, archiveId, cutoffMs)
        if (!archiveRunning.compareAndSet(false, true)) return
        ARCHIVE_EXECUTOR.execute {
            try {
                if (CalibrationAccess.archiveSensorCalibrations(serial, archiveId, cutoffMs)) {
                    Applic.app?.let {
                        if (MedtrumPersistence.getPendingArchive(it) == archiveId) {
                            MedtrumPersistence.setPendingArchive(it, "")
                        }
                    }
                } else {
                    Log.w(TAG, "Calibration archive $archiveId not done yet; will retry")
                }
            } catch (t: Throwable) {
                Log.stack(TAG, "archiveSensorCalibrations", t)
            } finally {
                archiveRunning.set(false)
            }
        }
    }

    private fun recordSensorStart(elapsedSeconds: Long) {
        val candidate = MedtrumSensorLife.startFromElapsed(System.currentTimeMillis(), elapsedSeconds)
        if (!MedtrumSensorLife.shouldReplaceStart(getStartTimeMs(), candidate)) return
        sensorStartMs = candidate
        Applic.app?.let { MedtrumPersistence.setSensorStartMs(it, candidate) }
        UiRefreshBus.requestStatusRefresh()
    }

    /** AUTH failed with both dialects MAX_AUTH_FAILURES times: the serial does not fit the patch. */
    fun isAuthRejected(): Boolean = authGaveUp

    /** The connected device has no Medtrum service: it is not this patch. */
    fun isDeviceRejected(): Boolean = deviceRejected

    // ---- Watchdog / reconnect ----

    private val reconnectRunnable = Runnable {
        if (stop) return@Runnable
        Log.i(TAG, "Medtrum scheduled reconnect for $SerialNumber")
        connectDevice(0)
    }

    private val keepAliveRunnable: Runnable = Runnable {
        if (stop || phase != Phase.STREAMING) return@Runnable
        enqueue(MedtrumProtocol.readState())
        handler.postDelayed(keepAliveRunnable, KEEPALIVE_MS)
    }

    private val watchdogRunnable = Runnable {
        if (stop || phase != Phase.STREAMING) return@Runnable
        val elapsed = System.currentTimeMillis() - lastCgmAtMs
        if (elapsed < WATCHDOG_MS) {
            armWatchdog()
            return@Runnable
        }
        Log.w(TAG, "Medtrum watchdog: no CGM record for ${elapsed / 1000}s — reconnecting")
        dropLink("watchdog")
    }

    /** When the pending attempt deadline fires; 0 when none is armed. */
    @Volatile private var attemptDeadlineAtMs = 0L

    private val attemptDeadlineRunnable = Runnable { onAttemptDeadlineExpired() }

    /**
     * The deadline covers the whole attempt, not just the connect: discovery, the CCCD writes and
     * AUTH all have to finish before the link is usable. A streaming link is the only thing that
     * ends it, so a setup that gets stuck in one of those steps is retried instead of leaving the
     * phase busy forever.
     */
    internal fun onAttemptDeadlineExpired() {
        attemptDeadlineAtMs = 0L
        if (stop) return
        if (phase == Phase.STREAMING || phase == Phase.SYNCING) return
        val route = reconnectPolicy.onAttemptFailed()
        Log.w(TAG, "Medtrum attempt did not complete in phase=$phase — next route $route")
        closeGattTransport()
        linkUp = false
        resetSession()
        phase = Phase.IDLE
        applyRoute(route)
        UiRefreshBus.requestStatusRefresh()
        connectDevice(0)
    }

    /**
     * Arm once per connect attempt: SensorBluetooth calls connectDevice again on its own cadence,
     * and re-arming each time would keep pushing the deadline back.
     */
    private fun armAttemptDeadline() {
        if (attemptDeadlineAtMs > System.currentTimeMillis()) return
        armDeadline(ATTEMPT_TIMEOUT_MS)
    }

    /**
     * A fresh, full window for the setup that follows a connection. The connect deadline is
     * already running down, and discovery plus AUTH legitimately outlast it.
     */
    private fun armSetupDeadline() = armDeadline(SETUP_TIMEOUT_MS)

    private fun armDeadline(timeoutMs: Long) {
        attemptDeadlineAtMs = System.currentTimeMillis() + timeoutMs
        handler.removeCallbacks(attemptDeadlineRunnable)
        handler.postDelayed(attemptDeadlineRunnable, timeoutMs)
    }

    /** True while a connect or setup deadline is pending. */
    internal fun isAttemptDeadlineArmed(): Boolean = attemptDeadlineAtMs > 0L

    /** Drop every pending handler task, the attempt deadline included. */
    private fun clearHandlerTasks() {
        attemptDeadlineAtMs = 0L
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Tear down a link that was up and go back to the known address. Used where Android will not
     * report the loss itself (we close the GATT, so no STATE_DISCONNECTED follows).
     */
    private fun dropLink(reason: String) {
        Log.w(TAG, "Medtrum dropping link: $reason")
        clearHandlerTasks()
        linkUp = false
        phase = Phase.IDLE
        closeGattTransport()
        resetSession()
        applyRoute(reconnectPolicy.onLinkLost())
        UiRefreshBus.requestStatusRefresh()
        scheduleReconnect(RECONNECT_DELAY_MS)
    }

    /** Clear per-link state; the next link starts from subscription again. */
    private fun resetSession() {
        charCgmData = null
        charCtrlPoint = null
        pendingCccd = null
        authSession = null
        // A fingerstick waiting for a readState must not be applied to the patch's state after a
        // reconnect: the elapsed time in its calibrate command would then be the new link's.
        pendingCalibrationMgdl = null
        authAccepted = false
        // The queue and the reassembly buffer are touched from callback threads too, so they are
        // cleared under the same lock the commands are queued with.
        synchronized(stateLock) {
            txQueue.clear()
            txInFlight = false
            rxBuffer.reset()
            rxExpected = -1
        }
    }

    /**
     * The device connected but does not carry the CGM service. It is not a Medtrum patch — the
     * EasyTouch pump shares the advertised name — so retrying its address only loops. Stop until
     * the user reconnects, and forget the address so the next attempt scans by advertised serial.
     */
    private fun rejectDevice(reason: String, gatt: BluetoothGatt) {
        Log.e(TAG, "Rejecting device ${gatt.device?.address}: $reason")
        deviceRejected = true
        clearHandlerTasks()
        linkUp = false
        phase = Phase.ERROR
        mActiveBluetoothDevice = null
        mActiveDeviceAddress = null
        Applic.app?.let { MedtrumPersistence.setLastBtAddress(it, "") }
        runCatching { closeGattTransport() }
        resetSession()
        UiRefreshBus.requestStatusRefresh()
    }

    /**
     * DIRECT keeps (or restores from the last address) the device, so SuperGattCallback calls
     * connectGatt on it; SCAN clears it, so SuperGattCallback starts the advert scan instead.
     */
    private fun applyRoute(route: MedtrumReconnectPolicy.Route) {
        when (route) {
            MedtrumReconnectPolicy.Route.SCAN -> mActiveBluetoothDevice = null
            MedtrumReconnectPolicy.Route.DIRECT -> if (mActiveBluetoothDevice == null) {
                mActiveBluetoothDevice = knownDevice()
            }
        }
    }

    private fun knownAddress(): String? {
        mActiveDeviceAddress?.takeIf { it.isNotBlank() }?.let { return it }
        val ctx = Applic.app ?: return null
        return MedtrumPersistence.getLastBtAddress(ctx).takeIf { it.isNotBlank() }
    }

    private fun knownDevice(): android.bluetooth.BluetoothDevice? {
        val address = knownAddress()?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) } ?: return null
        val adapter = (Applic.app?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return null
        return runCatching { adapter.getRemoteDevice(address) }.getOrNull()
    }

    private fun armWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    private fun scheduleReconnect(delayMs: Long) {
        if (stop || authGaveUp || deviceRejected) return
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun cancelReconnect() {
        handler.removeCallbacks(reconnectRunnable)
    }

    // ---- BLE identity ----

    /**
     * Null: a patch does not advertise its service UUID (captured advert = flags + name "MT" +
     * manufacturer data only), so a service filter would match nothing. Returning null makes the
     * scanner run unfiltered, and [matchScanResult] then picks the patch by its advertised serial.
     */
    override fun getService(): UUID? = null

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val knownAddress = mActiveDeviceAddress?.takeIf { it.isNotBlank() }
        if (knownAddress != null && address != null && address.equals(knownAddress, ignoreCase = true)) {
            return true
        }
        val ctx = Applic.app
        if (ctx != null) {
            val persisted = MedtrumPersistence.getLastBtAddress(ctx)
            if (persisted.isNotBlank() && persisted.equals(address, ignoreCase = true)) return true
        }
        // The advertised name ("MT") is shared with the EasyTouch pump, so it is never enough
        // on its own — the serial in the manufacturer data decides, in matchScanResult.
        return false
    }

    override fun matchScanResult(result: ScanResult): Boolean {
        val manufacturerData = result.scanRecord?.getManufacturerSpecificData(MedtrumProtocol.MANUFACTURER_ID)
            ?: return false
        val advertised = MedtrumProtocol.parseAdvertisedSerial(manufacturerData) ?: return false
        if (advertised != serialLong) return false
        Log.d(TAG, "Medtrum advert matched (SN ${MedtrumPersistence.formatSerialHex(advertised)})")
        return true
    }

    // ---- BLE lifecycle ----

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        if (authGaveUp || deviceRejected) {
            Log.i(TAG, "Not connecting: ${if (deviceRejected) "device rejected" else "AUTH gave up"}, waiting for a user reconnect")
            return false
        }
        if (phase != Phase.IDLE && phase != Phase.ERROR) {
            val pending = mBluetoothGatt
            val target = mActiveBluetoothDevice
            val retarget = phase == Phase.CONNECTING && pending != null && target != null &&
                !pending.device?.address.equals(target.address, ignoreCase = true)
            if (retarget) {
                // A scan found the patch at a new address while an attempt on the old one was
                // still pending; that attempt would never complete.
                Log.i(TAG, "Patch moved to ${target?.address} — dropping the pending attempt")
                closeGattTransport()
            } else if (pending != null || target != null) {
                Log.i(TAG, "Skipping duplicate connect: phase=$phase")
                return true
            }
        }
        val scanning = mActiveBluetoothDevice == null
        phase = Phase.CONNECTING
        val scheduled = super.connectDevice(delayMillis)
        if (!scheduled && phase == Phase.CONNECTING) phase = Phase.IDLE
        // Without a device SuperGattCallback starts the advert scan and returns false; that
        // scan window gets the same deadline as a direct attempt.
        if (scheduled || scanning) armAttemptDeadline()
        return scheduled
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        noteFirstGattCallback("onConnectionStateChange", gatt)
        // Ownership is decided before anything shared changes, the superclass included: its
        // local-connection claim would otherwise move to a retired handle. The check runs under the
        // callback monitor, so it also waits for connectGatt()'s handle to be stored, and it refuses
        // a closed handle while the current one is null after a transient close.
        if (!acceptConnectionAttemptCallback(gatt, newState)) {
            if (!stop) retireStaleGatt(gatt, status, newState)
            return
        }
        super.onConnectionStateChange(gatt, status, newState)
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> onLinkConnected(gatt)
            BluetoothProfile.STATE_DISCONNECTED -> onLinkDisconnected(gatt, status)
            else -> if (phase == Phase.CONNECTING && status != BluetoothGatt.GATT_SUCCESS) phase = Phase.IDLE
        }
    }

    private fun retireStaleGatt(gatt: BluetoothGatt, status: Int, newState: Int) {
        Log.d(TAG, "Ignoring state=$newState status=$status from a retired GATT")
        if (newState == BluetoothProfile.STATE_CONNECTED) runCatching { gatt.disconnect() }
        if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { gatt.close() }
    }

    private fun onLinkConnected(gatt: BluetoothGatt) {
        val address = gatt.device?.address
        Log.i(TAG, "Medtrum connected to $address")
        val h = handler
        // Keep a deadline through setup: if discovery, subscription or AUTH never finishes, the
        // phase would stay busy and every later connectDevice() would be skipped as a duplicate.
        armSetupDeadline()
        cancelReconnect()
        linkUp = true
        reconnectPolicy.onConnected()
        mBluetoothGatt = gatt
        mActiveBluetoothDevice = gatt.device
        if (address != null) {
            mActiveDeviceAddress = address
            Applic.app?.let { MedtrumPersistence.setLastBtAddress(it, address) }
        }
        connectTime = System.currentTimeMillis()
        phase = Phase.DISCOVERING
        h.postDelayed({
            if (phase == Phase.DISCOVERING && mBluetoothGatt === gatt) {
                val started = runCatching { gatt.discoverServices() }
                    .onFailure { Log.stack(TAG, "discoverServices", it) }
                    .getOrDefault(false)
                if (!started) dropLink("discoverServices failed")
            }
        }, DISCOVERY_DELAY_MS)
        UiRefreshBus.requestStatusRefresh()
    }

    private fun onLinkDisconnected(gatt: BluetoothGatt, status: Int) {
        val wasUp = linkUp
        linkUp = false
        clearHandlerTasks()
        phase = Phase.IDLE
        resetSession()
        runCatching { gatt.close() }
        mBluetoothGatt = null
        val route = if (wasUp) reconnectPolicy.onLinkLost() else reconnectPolicy.onAttemptFailed()
        applyRoute(route)
        Log.i(TAG, "Medtrum disconnected (status=$status, ${if (wasUp) "link lost" else "attempt failed"}) — next route $route")
        UiRefreshBus.requestStatusRefresh()
        if (!authGaveUp) scheduleReconnect(RECONNECT_DELAY_MS)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (stop) return
        // A discovery result from a GATT we already retired must not install its handles over
        // the live link's.
        if (mBluetoothGatt !== gatt) {
            Log.d(TAG, "Ignoring onServicesDiscovered from a retired GATT")
            runCatching { gatt.close() }
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "Service discovery failed status=$status")
            runCatching { gatt.disconnect() }
            return
        }

        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            rejectDevice("Medtrum service not found on ${gatt.device?.address}", gatt)
            return
        }
        val cgmData = service.getCharacteristic(CHAR_CGM_DATA_UUID)
        val ctrlPoint = service.getCharacteristic(CHAR_CTRL_POINT_UUID)
        if (cgmData == null || ctrlPoint == null) {
            rejectDevice("required characteristics missing: cgmData=$cgmData ctrlPoint=$ctrlPoint", gatt)
            return
        }
        charCgmData = cgmData
        charCtrlPoint = ctrlPoint
        phase = Phase.SUBSCRIBING

        // The app enables both: notifications for the CGM record, indications for responses.
        pendingCccd = CHAR_CGM_DATA_UUID
        Log.i(TAG, "Medtrum services discovered — enabling CGM_DATA notifications")
        if (!enableNotification(gatt, cgmData)) {
            Log.e(TAG, "Failed to enable Medtrum CGM_DATA notifications")
            runCatching { gatt.disconnect() }
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (stop) return
        if (mBluetoothGatt !== gatt) {
            Log.d(TAG, "Ignoring onDescriptorWrite from a retired GATT")
            return
        }
        if (descriptor.uuid != CCCD_UUID) return
        val charUuid = descriptor.characteristic?.uuid ?: return
        if (charUuid != CHAR_CGM_DATA_UUID && charUuid != CHAR_CTRL_POINT_UUID) return

        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "CCCD write failed ($charUuid) status=$status — reconnecting")
            runCatching { gatt.disconnect() }
            return
        }

        if (charUuid == CHAR_CGM_DATA_UUID) {
            pendingCccd = CHAR_CTRL_POINT_UUID
            val ctrlPoint = charCtrlPoint ?: return
            Log.i(TAG, "CGM_DATA subscribed — enabling CTRLPT indications")
            if (!enableIndication(gatt, ctrlPoint)) {
                Log.e(TAG, "Failed to enable Medtrum CTRLPT indications")
                runCatching { gatt.disconnect() }
            }
            return
        }

        if (pendingCccd == CHAR_CTRL_POINT_UUID) {
            pendingCccd = null
            phase = Phase.AUTH
            val storedLegacy = Applic.app?.let { MedtrumPersistence.isLegacyAuth(it) } ?: false
            val session = MedtrumAuthSession(storedLegacy)
            authSession = session
            UiRefreshBus.requestStatusRefresh()
            Log.i(TAG, "Both CCCDs enabled — sending AUTH_REQ")
            sendAuthRequest(session.legacy)
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (stop) return
        // A completion from a retired link must not clear the write in flight on the replacement
        // link or start another one for it.
        if (mBluetoothGatt !== gatt) {
            Log.d(TAG, "Ignoring onCharacteristicWrite from a retired GATT")
            return
        }
        if (characteristic.uuid != CHAR_CTRL_POINT_UUID) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "write failed status=$status — reconnecting")
            runCatching { gatt.disconnect() }
            return
        }
        // The queue is owned by the handler thread; pumping from the binder thread could start
        // two writes at once.
        handler.post {
            if (stop || mBluetoothGatt !== gatt) return@post
            synchronized(stateLock) {
                txInFlight = false
                pumpTxQueueLocked()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (stop) return
        if (mBluetoothGatt !== gatt) {
            Log.d(TAG, "Ignoring notification from a retired GATT")
            return
        }
        val data = characteristic.value ?: return
        when (characteristic.uuid) {
            CHAR_CGM_DATA_UUID -> onCgmData(data)
            CHAR_CTRL_POINT_UUID -> onCtrlPointPdu(data)
            else -> Log.d(TAG, "Notification on unknown characteristic ${characteristic.uuid}")
        }
    }

    // ---- CTRLPT reassembly (mirrors BLEBaseCentral.parseCharacteristicValue) ----

    private fun onCtrlPointPdu(pdu: ByteArray) {
        // Reassemble under the lock, dispatch outside it: a callback that answers here re-enters
        // the driver's synchronized BLE state, and holding this lock across that would invert the
        // lock order against the driver thread.
        val complete = synchronized(stateLock) {
            if (rxExpected < 0) {
                val declared = MedtrumProtocol.declaredLength(pdu)
                if (declared < 0 || pdu.size < 2) return
                val available = minOf(declared, pdu.size - 2)
                rxBuffer.write(pdu, 2, available)
                if (rxBuffer.size() >= declared) takeBufferedResponse() else {
                    rxExpected = declared
                    null
                }
            } else {
                // Continuation PDU: raw bytes, no header.
                rxBuffer.write(pdu, 0, pdu.size)
                if (rxBuffer.size() >= rxExpected) takeBufferedResponse() else null
            }
        } ?: return
        finishResponse(complete)
    }

    private fun takeBufferedResponse(): ByteArray {
        val value = rxBuffer.toByteArray()
        rxBuffer.reset()
        rxExpected = -1
        return value
    }

    private fun finishResponse(value: ByteArray) {
        val response = MedtrumProtocol.parseResponse(value)
        if (response == null) {
            Log.w(TAG, "Unparseable CTRLPT response (${value.size} bytes)")
            return
        }
        when (response.opCode) {
            MedtrumProtocol.OP_AUTH -> onAuthResponse(response)
            MedtrumProtocol.OP_READ_STATE -> onStateResponse(response, value)
            MedtrumProtocol.OP_CALIBRATE -> Log.i(TAG, "calibrate ok=${response.ok} error=${response.errorCode}")
            MedtrumProtocol.OP_CALIBRATE_TIME -> Log.d(TAG, "calibrateTime ok=${response.ok}")
            else -> Log.d(TAG, "response op=0x%02X ok=%s error=%d".format(response.opCode, response.ok, response.errorCode))
        }
    }

    private fun onStateResponse(response: MedtrumProtocol.Response, unframed: ByteArray) {
        if (!response.ok) {
            Log.w(TAG, "readState error=${response.errorCode}")
            return
        }
        val state = MedtrumProtocol.parseStateRecord(unframed) ?: return
        insertTime = state.insertTime
        runningTime = state.runningTime
        batteryMillivolts = state.batteryMillivolts
        noteSensorSession(state)
        Log.d(TAG, "readState: ${state.phase} seq=${state.sequence} battery=${state.batteryMillivolts / 512.0}V elapsed=${state.elapsedSeconds}s nextCal=${state.nextCalibrationSequence}")
        val pending = pendingCalibrationMgdl ?: return
        pendingCalibrationMgdl = null
        sendCalibration(pending)
    }

    // ---- Auth ----

    private fun sendAuthRequest(legacy: Boolean) {
        val role = if (legacy) MedtrumProtocol.ROLE_LEGACY else MedtrumProtocol.ROLE_MODERN
        val key = if (legacy) MedtrumCrypt.legacyKeyGen(serialLong) else MedtrumCrypt.keyGen(serialLong)
        Log.i(TAG, "AUTH_REQ role=$role legacy=$legacy")
        enqueue(MedtrumProtocol.authorize(role, key))
    }

    private fun onAuthResponse(response: MedtrumProtocol.Response) {
        val session = authSession ?: return
        if (response.responseCode != MedtrumProtocol.RESPONSE_AUTH || !response.ok) {
            if (session.onRejected()) {
                Log.w(TAG, "AUTH rejected (code=${response.responseCode} error=${response.errorCode}) — retrying with legacy=${session.legacy}")
                sendAuthRequest(session.legacy)
                return
            }
            authFailureCount++
            if (authFailureCount >= MAX_AUTH_FAILURES) {
                // A mistyped serial fails the key both ways; stop instead of reconnecting forever.
                Log.e(TAG, "AUTH failed $authFailureCount times — giving up. Check the patch serial.")
                authGaveUp = true
                clearHandlerTasks()
                linkUp = false
                closeGattTransport()
                resetSession()
                phase = Phase.ERROR
                UiRefreshBus.requestStatusRefresh()
                return
            }
            Log.e(TAG, "AUTH failed (attempt $authFailureCount) — reconnecting")
            reconnectOnError("auth-failed")
            return
        }
        authFailureCount = 0
        authAccepted = true
        // AUTH accepted: the link is up and usable, so the setup deadline is done. The watchdog
        // takes over once streaming starts.
        attemptDeadlineAtMs = 0L
        handler.removeCallbacks(attemptDeadlineRunnable)
        Applic.app?.let { ctx ->
            if (MedtrumPersistence.isLegacyAuth(ctx) != session.legacy) {
                MedtrumPersistence.setLegacyAuth(ctx, session.legacy)
            }
        }
        val version = MedtrumProtocol.parseAuthVersion(response)
        deviceVersion = version
        Log.i(TAG, "AUTH ok, firmware $version")
        phase = Phase.SYNCING
        UiRefreshBus.requestStatusRefresh()
        // Time sync, then the patch's periodic CGM_DATA drives the rest.
        val seconds = (System.currentTimeMillis() - MEDTRUM_EPOCH_MS) / 1000L
        enqueue(MedtrumProtocol.calibrateTime(seconds))
        enqueue(MedtrumProtocol.readState())
        handler.postDelayed({
            if (phase == Phase.SYNCING) {
                phase = Phase.STREAMING
                armWatchdog()
                handler.removeCallbacks(keepAliveRunnable)
                handler.postDelayed(keepAliveRunnable, KEEPALIVE_MS)
                UiRefreshBus.requestStatusRefresh()
            }
        }, 1_000L)
    }

    // ---- CGM data ----

    private fun onCgmData(data: ByteArray) {
        // The patch can push CGM_DATA as soon as the notification is enabled, before AUTH is
        // accepted. Such a record is not trusted: publishing it would also flip the phase to
        // STREAMING, which setup reads as Connected.
        if (!authAccepted) {
            Log.d(TAG, "Ignoring CGM_DATA before AUTH")
            return
        }
        val record = MedtrumProtocol.parseCgmRecord(data)
        if (record == null) {
            Log.d(TAG, "Short CGM_DATA record (${data.size} bytes)")
            return
        }
        // Any record is liveness, including warm-up ones where glucose is null.
        lastCgmAtMs = System.currentTimeMillis()
        if (phase != Phase.STREAMING) {
            phase = Phase.STREAMING
            handler.removeCallbacks(keepAliveRunnable)
            handler.postDelayed(keepAliveRunnable, KEEPALIVE_MS)
            UiRefreshBus.requestStatusRefresh()
        }
        armWatchdog()

        val glucose = record.glucoseMgdl
        if (glucose == null) {
            Log.d(TAG, "Medtrum ${record.phase} seq=${record.sequence} signal=${record.signal} calibFactor=${record.calibrationFactor} — no glucose yet")
            return
        }
        if (glucose < GLUCOSE_MIN_MGDL || glucose > GLUCOSE_MAX_MGDL) {
            Log.w(TAG, "Medtrum glucose out of range: $glucose mg/dL — skipping")
            return
        }

        Log.i(TAG, "Medtrum glucose: ${glucose} mg/dL (signal=${record.signal} calibFactor=${record.calibrationFactor})")
        publishReading(glucose.toFloat())
    }

    /**
     * The final storage and publication of one reading, serialised with [terminateManagedSensor]
     * by [readingLock]. The entry checks in [onCgmData] cannot cover this: a callback can pass them,
     * block on the bridge's mirror lock, and resume after the sensor was removed, where its native
     * write would revive the shell that removal had just finished and bring the old serial back as
     * a generic Libre callback. The state is checked again under the lock.
     */
    private fun publishReading(mgdl: Float) {
        synchronized(readingLock) {
            if (stop || !authAccepted) return
            val nowMs = System.currentTimeMillis()
            val sensorSerial = serialLong.toString()
            // The reading is real and local: without this the shared "last successful glucose" slot
            // stays 0 and a co-active Libre sensor's freshness checks reconnect this healthy link.
            markLocalReadingAccepted(nowMs)
            HistorySyncAccess.storeCurrentReadingAsync(nowMs, mgdl, 0f, 0f, sensorSerial)
            // The same value Room gets: the patch's own, before this app's calibration.
            VirtualGlucoseSensorBridge.mirrorToNative(
                sensorSerial,
                listOf(VirtualGlucoseSensorBridge.Reading(timestampMs = nowMs, glucoseMgdl = mgdl)),
                "Medtrum",
                ManagedSensorUiFamily.MEDTRUM,
            )
            // Stock lane, as every BLE driver publishes: the patch's own value is before this app's
            // software calibration, which is applied once downstream. The follower bridge used before
            // marked it resolved, so live readings and alerts skipped that calibration.
            SuperGattCallback.processExternalCurrentReading(
                sensorSerial,
                liveLane(mgdl, Applic.unit),
                0f,
                nowMs,
                SENSOR_GEN,
            )
        }
        UiRefreshBus.requestDataRefresh()
    }

    // ---- calibration ----

    override fun supportsManualCalibration(): Boolean = true

    /**
     * Send a fingerstick value. The patch wants the elapsed seconds since insertion, which only
     * a readState answer carries, so ask for one first and send the calibration on its reply —
     * the same order SensorInstance uses (readState, then mNeedCalibrate).
     */
    override fun calibrateSensor(glucoseMgDl: Int): Boolean {
        if (glucoseMgDl <= 0) return false
        if (runningTime == 0L || insertTime == 0L) {
            pendingCalibrationMgdl = glucoseMgDl
            enqueue(MedtrumProtocol.readState())
            return true
        }
        return sendCalibration(glucoseMgDl)
    }

    private fun sendCalibration(glucoseMgDl: Int): Boolean {
        val elapsed = (runningTime - insertTime).coerceAtLeast(0L).toInt()
        Log.i(TAG, "calibrate $glucoseMgDl mg/dL, elapsed ${elapsed}s")
        enqueue(MedtrumProtocol.calibrate(glucoseMgDl, elapsed, 0 /* Fingerstick */))
        return true
    }

    // ---- TX queue ----

    private fun enqueue(commandData: ByteArray) {
        handler.post {
            if (stop) return@post
            synchronized(stateLock) {
                MedtrumProtocol.encode(commandData).forEach { txQueue.addLast(it) }
                pumpTxQueueLocked()
            }
        }
    }

    /** Only called while holding [stateLock]. */
    private fun pumpTxQueueLocked() {
        if (stop || txInFlight) return
        val gatt = mBluetoothGatt ?: return
        val ch = charCtrlPoint ?: return
        val pdu = txQueue.removeFirstOrNull() ?: return
        try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = pdu
            txInFlight = true
            if (!gatt.writeCharacteristic(ch)) {
                txInFlight = false
                Log.w(TAG, "writeCharacteristic returned false — reconnecting")
                runCatching { gatt.disconnect() }
            }
        } catch (t: Throwable) {
            txInFlight = false
            Log.stack(TAG, "pumpTxQueueLocked", t)
        }
    }

    // ---- Error / reconnect ----

    private fun reconnectOnError(reason: String) {
        phase = Phase.ERROR
        UiRefreshBus.requestStatusRefresh()
        Log.w(TAG, "Medtrum reconnecting after error: $reason")
        val gatt = mBluetoothGatt
        if (gatt != null) {
            runCatching { gatt.disconnect() }
        } else {
            scheduleReconnect(RECONNECT_DELAY_MS)
        }
    }

    // ---- ManagedBluetoothSensorDriver lifecycle ----

    /** Pause: drop the link but keep the thread, softReconnect resumes on it. */
    override fun softDisconnect() {
        setPause(true)
        clearHandlerTasks()
        linkUp = false
        phase = Phase.IDLE
        runCatching { closeGattTransport() }.onFailure { Log.stack(TAG, "softDisconnect(close)", it) }
        resetSession()
        UiRefreshBus.requestStatusRefresh()
    }

    override fun softReconnect() {
        setPause(false)
        clearHandlerTasks()
        // A user-driven reconnect gets a fresh budget after a mistyped serial stopped us.
        authFailureCount = 0
        authGaveUp = false
        deviceRejected = false
        linkUp = false
        runCatching { closeGattTransport() }.onFailure { Log.stack(TAG, "softReconnect(close)", it) }
        resetSession()
        phase = Phase.IDLE
        applyRoute(reconnectPolicy.onLinkLost())
        handler.postDelayed({ if (!stop) connectDevice(0) }, 250L)
        UiRefreshBus.requestStatusRefresh()
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        // Waits for a reading that is already being stored, and keeps later ones out (see
        // publishReading): the shell is finished only after the last write that will ever happen.
        synchronized(readingLock) {
            setPause(true)
            phase = Phase.IDLE
            runCatching { close() }.onFailure { Log.stack(TAG, "terminateManagedSensor(close)", it) }
            // Finish the native mirror while the persisted record still claims it: an active shell
            // with no owner comes back as a generic Libre callback (the Ottai removal bug).
            runCatching { Natives.finishSensorById(serialLong.toString()) }
                .onFailure { Log.stack(TAG, "terminateManagedSensor(finishSensorById)", it) }
            if (wipeData) Applic.app?.let { MedtrumPersistence.clear(it) }
        }
    }

    /**
     * SensorBluetooth has already closed the transport, so no STATE_DISCONNECTED follows. Without
     * this the phase stayed where the link died and connectDevice() skipped every later attempt as
     * a duplicate once Bluetooth came back. The thread and the device stay for that reconnect.
     */
    override fun onBluetoothAdapterUnavailable() {
        clearHandlerTasks()
        linkUp = false
        phase = Phase.IDLE
        resetSession()
        applyRoute(reconnectPolicy.onLinkLost())
        UiRefreshBus.requestStatusRefresh()
    }

    override fun removeManagedPersistence(context: Context) {
        MedtrumPersistence.clear(context)
    }

    /**
     * SensorBluetooth also calls this for transient resets (resetdataptr), after which the driver
     * is connected again — so only a stopped driver gives up its thread.
     */
    override fun close() {
        phase = Phase.IDLE
        linkUp = false
        if (stop) quitHandlerThread() else clearHandlerTasks()
        super.close()
        resetSession()
    }
}
