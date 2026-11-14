// Libre3BleManager.kt — Kotlin-managed BLE driver for FreeStyle Libre 3 / 3 Plus.
//
// Owns the whole sensor path without the legacy Libre 3 native code: GATT, security handshake
// (Libre3Handshake), data-plane crypto (Libre3DataPlaneSession), record decoding, backfill, Room
// storage and live publication. The only native calls are the app-wide managed-sensor services
// every Kotlin driver uses (the generic native mirror that keeps the web server / Nightscout
// uploader fed, and finishing a sensor's shell on removal).
//
// Lifecycle:
//   connect → discover → enable security CCCDs → handshake (cached kAuth, else full with an
//   imported app identity) → enable data CCCDs (legacy order) → stream.
//   Every GATT operation goes through one queue and runs only after the previous one completed.
//   All state lives on this driver's HandlerThread; Binder callbacks only copy bytes and post.

package tk.glucodata.drivers.libre3

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.TreeSet
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.LiveReadingLanes
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorSourceResolver
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

@SuppressLint("MissingPermission")
class Libre3BleManager(sensorId: String) :
    SuperGattCallback(sensorId, 0L, SENSOR_GEN), ManagedBluetoothSensorDriver {

    companion object {
        private const val TAG = "Libre3BLE"

        /** The sensor kind downstream code (xDrip source name, display) uses for Libre 3. */
        const val SENSOR_GEN = SensorSourceResolver.SENSOR_KIND_LIBRE3

        const val DISPLAY_NAME = "FreeStyle Libre 3"
        const val LOG_LABEL = "Libre3"

        private const val RECONNECT_DELAY_MS = 3_000L
        private const val DISCOVERY_DELAY_MS = 250L
        /** Discovery, CCCDs and the handshake together; a link that has not streamed by then is retried. */
        private const val SETUP_TIMEOUT_MS = 60_000L
        /** The sensor sends a realtime frame every minute; five silent minutes means the link is dead. */
        private const val STREAM_WATCHDOG_MS = 5L * 60_000L
        /** The sensor answers each patchControl request on the same characteristic when it is done. */
        private const val CONTROL_RESPONSE_TIMEOUT_MS = 30_000L
        private const val BACKFILL_FLUSH_DELAY_MS = 2_000L
        /** First clinical request on a sensor with no minute history here: the sensor buffers far less. */
        private const val INITIAL_CLINICAL_WINDOW_MINUTES = 6 * 60
        /** Room points already within this distance of a backfilled minute are the same reading. */
        private const val MINUTE_DUPLICATE_WINDOW_MS = 50_000L
        /** A 5-minute history point only fills a hole: skip it when any reading is this close. */
        private const val HISTORY_DUPLICATE_WINDOW_MS = 150_000L
        /** After this many complete rounds of every identity being refused, stop until the user acts. */
        private const val MAX_IDENTITY_ROUNDS = 2
        private const val MGDL_PER_MMOL = 18.0182f
        /** Full Libre 3 Plus lifetime plus headroom; matches the family-specific native mirror. */
        private const val NATIVE_WINDOW_MINUTES = 32 * 24 * 60
        private const val NATIVE_WINDOW_SECONDS = NATIVE_WINDOW_MINUTES * 60L
    }

    enum class Phase { IDLE, CONNECTING, DISCOVERING, SUBSCRIBING, AUTHENTICATING, STARTING_DATA, STREAMING, ENDED, ERROR }

    @Volatile var phase: Phase = Phase.IDLE
        private set

    private val sensorId: String = sensorId

    // ---- thread ----

    private val threadName = "Libre3-$sensorId"
    private var handlerThread = HandlerThread(threadName).also { it.start() }
    private var liveHandler = Handler(handlerThread.looper)
    private var handlerQuit = false

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

    // ---- persisted sensor state ----

    @Volatile private var record: Libre3SensorRecord? = Applic.app?.let { Libre3Persistence.loadSensor(it, sensorId) }
    @Volatile private var ended: Boolean = Applic.app?.let { Libre3Persistence.isEnded(it, sensorId) } ?: false
    private var lastLifeCount: Int = Applic.app?.let { Libre3Persistence.getLastLifeCount(it, sensorId) } ?: 0
    private var lastHistoricLifeCount: Int = Applic.app?.let { Libre3Persistence.getLastHistoricLifeCount(it, sensorId) } ?: 0

    /** Minute life counts received past [lastLifeCount], waiting for the gap before them to close. */
    private val receivedAhead = TreeSet<Int>()
    private var highestClinicalSeen = 0
    private var highestHistoricSeen = 0

    /** Phone time of life count 0, learned from realtime frames; NFC activation time until then. */
    private var anchorStartMs: Long = 0L

    // ---- per-link state (handler thread) ----

    private val characteristics = HashMap<UUID, BluetoothGattCharacteristic>()
    private sealed class GattOp {
        class Notify(val uuid: UUID) : GattOp()
        class Write(val uuid: UUID, val value: ByteArray, val label: String) : GattOp()
        class Run(val block: () -> Unit) : GattOp()
    }
    private val ops = ArrayDeque<GattOp>()
    private var opInFlight: GattOp? = null

    private var credentialRevision = -1L
    private var handshake: Libre3Handshake? = null
    private var handshakeIdentity: Libre3AppIdentity? = null
    private var session: Libre3DataPlaneSession? = null
    private val glucoseAssembler = Libre3Framing.GlucoseAssembler()

    private enum class ControlKind { CLINICAL, HISTORIC }
    private val controlQueue = ArrayDeque<Pair<ControlKind, ByteArray>>()
    private var controlInFlight: ControlKind? = null
    /** Committed history life count the queued historic request asked up to. */
    private var requestedHistoricCommitted = 0

    private val minuteBackfill = ArrayList<VirtualGlucoseSensorBridge.Reading>()
    private val historicBackfill = ArrayList<VirtualGlucoseSensorBridge.Reading>()

    @Volatile private var linkUp = false

    // ---- authorization bookkeeping ----

    /** Index into the installed identities for the next full authorization. */
    private var identityCursor = 0
    private var identityRejections = 0
    @Volatile private var identityRejected = false
    @Volatile private var missingIdentity = false
    /** Connected device lacks the Libre 3 services: retrying the same address cannot help. */
    @Volatile private var deviceRejected = false

    // ---- UI-facing snapshot (any thread) ----

    @Volatile private var lastStatus: Libre3PatchStatus? = null
    @Volatile private var lastReadingMs = 0L
    @Volatile private var lastReadingMgdl = Float.NaN
    @Volatile private var lastRate = Float.NaN
    @Volatile private var lastNote: String = ""

    private val readingLock = Any()

    override var viewMode: Int = 0

    init {
        mActiveDeviceAddress = record?.bleAddress
        mActiveBluetoothDevice = knownDevice()
        sensorstartmsec = record?.startTimeMs ?: 0L
        anchorStartMs = Applic.app?.let { Libre3Persistence.getAnchor(it, sensorId) }?.takeIf { it > 0L }
            ?: record?.startTimeMs ?: 0L
        if (ended) phase = Phase.ENDED
    }

    // ---- ManagedBluetoothSensorDriver ----

    override fun canConnectWithoutDataptr(): Boolean = true
    override fun hasNativeSensorBacking(): Boolean = false
    override fun shouldUseNativeHistorySync(): Boolean = false
    override fun managesLiveRoomStorage(): Boolean = true
    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        Libre3IdentityAdapter.sameSensor(sensorId, this.sensorId)

    fun getSensorId(): String = sensorId

    private fun text(id: Int): String = Applic.app?.getString(id).orEmpty()

    override fun getDetailedBleStatus(): String = when (phase) {
        Phase.IDLE -> when {
            missingIdentity -> text(tk.glucodata.R.string.libre3_status_identity_missing)
            identityRejected -> text(tk.glucodata.R.string.libre3_status_identity_rejected)
            deviceRejected -> lastNote.ifBlank { text(tk.glucodata.R.string.libre3_status_device_rejected) }
            mActiveBluetoothDevice == null -> "Searching"
            else -> "Idle"
        }
        Phase.CONNECTING -> "Connecting"
        Phase.DISCOVERING -> "Discovering services"
        Phase.SUBSCRIBING -> "Subscribing"
        Phase.AUTHENTICATING -> if (handshake?.isFull == true) "Authorizing" else "Re-authorizing"
        Phase.STARTING_DATA -> "Starting data"
        Phase.STREAMING -> if (lastReadingMs > 0L) "Connected" else "Waiting for data"
        Phase.ENDED -> "Sensor ended"
        Phase.ERROR -> lastNote.ifBlank { "Error" }
    }

    fun getConnectionStatus(): String = getDetailedBleStatus()

    fun isConnected(): Boolean = phase == Phase.STREAMING

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val at = lastReadingMs
        val mgdl = lastReadingMgdl
        if (at <= 0L || !mgdl.isFinite()) return null
        if (maxAgeMillis > 0L && System.currentTimeMillis() - at > maxAgeMillis) return null
        val display = if (Applic.unit == 1) mgdl / MGDL_PER_MMOL else mgdl
        return ManagedSensorCurrentSnapshot(at, display, rate = lastRate, sensorGen = SENSOR_GEN)
    }

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot? {
        val rec = record ?: Applic.app?.let { Libre3Persistence.loadSensor(it, sensorId) } ?: return null
        val now = System.currentTimeMillis()
        val start = rec.startTimeMs
        val end = rec.expectedEndMs
        val status = lastStatus
        val attention = status?.attention ?: Libre3PatchStatus.Attention.NONE
        val subtitle = when {
            attention != Libre3PatchStatus.Attention.NONE -> attentionText(status!!)
            lastNote.isNotBlank() && phase == Phase.STREAMING -> lastNote
            else -> ""
        }
        return ManagedSensorUiSnapshot(
            serial = sensorId,
            displayName = DISPLAY_NAME,
            deviceAddress = rec.bleAddress,
            uiFamily = ManagedSensorUiFamily.LIBRE3,
            connectionStatus = getConnectionStatus(),
            detailedStatus = getDetailedBleStatus(),
            subtitleStatus = subtitle,
            startTimeMs = start,
            officialEndMs = end,
            expectedEndMs = end,
            isActive = activeSensorId?.let { Libre3IdentityAdapter.sameSensor(it, sensorId) } ?: false,
            rssi = readrssi,
            viewMode = viewMode,
            isVendorPaired = Applic.app?.let { Libre3Persistence.loadCachedAuth(it, sensorId) } != null,
            isVendorConnected = isConnected(),
            isSensorExpired = ended || (end > 0L && now >= end),
            sensorRemainingHours = if (end > 0L) ((end - now).coerceAtLeast(0L) / 3_600_000L).toInt() else -1,
            sensorAgeHours = if (start > 0L) ((now - start).coerceAtLeast(0L) / 3_600_000L).toInt() else -1,
            vendorFirmware = rec.firmware,
            vendorModel = "Libre 3",
        )
    }

    private fun attentionText(status: Libre3PatchStatus): String = when (status.attention) {
        Libre3PatchStatus.Attention.NONE -> ""
        Libre3PatchStatus.Attention.CHECK_SENSOR -> "Check sensor (error ${status.errorData}, state ${status.patchState})"
        Libre3PatchStatus.Attention.INSERTION_FAILED -> "Sensor failed to start"
        Libre3PatchStatus.Attention.ENDED -> "Sensor ended"
        Libre3PatchStatus.Attention.REPLACE -> "Replace sensor"
        Libre3PatchStatus.Attention.UNKNOWN_ERROR -> "Sensor error ${status.errorData}"
    }

    // ---- identity / scanning ----

    override fun getService(): UUID = Libre3Gatt.DATA_SERVICE

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val rec = record ?: return false
        if (address != null && address.equals(rec.bleAddress, ignoreCase = true)) return true
        return deviceName != null && deviceName.equals(Libre3Nfc.advertisedName(rec.bleAddress), ignoreCase = true)
    }

    private fun knownDevice(): android.bluetooth.BluetoothDevice? {
        val address = record?.bleAddress?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) } ?: return null
        val adapter = (Applic.app?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return null
        return runCatching { adapter.getRemoteDevice(address) }.getOrNull()
    }

    /** NFC found the sensor shut down: stop connecting and show it as ended. */
    fun onSensorTerminated() {
        ended = true
        handler.post {
            handler.removeCallbacks(reconnectRunnable)
            closeGattTransport()
            endLink()
            phase = Phase.ENDED
            UiRefreshBus.requestStatusRefresh()
        }
    }

    fun onCredentialsRemoved() {
        handler.post {
            missingIdentity = true
            dropLink("credentials removed")
        }
    }

    /** NFC refreshed this sensor's address/PIN/start: adopt it and start over with a clean link. */
    fun onNfcRecordUpdated() {
        val ctx = Applic.app ?: return
        record = Libre3Persistence.loadSensor(ctx, sensorId)
        ended = false
        identityRejected = false
        identityRejections = 0
        missingIdentity = false
        deviceRejected = false
        mActiveDeviceAddress = record?.bleAddress
        mActiveBluetoothDevice = knownDevice()
        sensorstartmsec = record?.startTimeMs ?: 0L
        softReconnect()
    }

    // ---- connection ----

    override fun connectionAttemptTimeoutMillis(): Long = 120_000L

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop || ended) return false
        if (identityRejected || missingIdentity || deviceRejected) {
            Log.i(TAG, "$sensorId: not connecting (${getDetailedBleStatus()}); waiting for the user")
            return false
        }
        if (phase != Phase.IDLE && phase != Phase.ERROR) {
            if (mBluetoothGatt != null) {
                Log.d(TAG, "$sensorId: skipping duplicate connect in $phase")
                return true
            }
        }
        if (mActiveBluetoothDevice == null) mActiveBluetoothDevice = knownDevice()
        phase = Phase.CONNECTING
        val scheduled = super.connectDevice(delayMillis)
        if (!scheduled && phase == Phase.CONNECTING) phase = Phase.IDLE
        return scheduled
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        noteFirstGattCallback("onConnectionStateChange", gatt)
        if (!acceptConnectionAttemptCallback(gatt, newState)) {
            if (!stop) {
                if (newState == BluetoothProfile.STATE_CONNECTED) runCatching { gatt.disconnect() }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { gatt.close() }
            }
            return
        }
        super.onConnectionStateChange(gatt, status, newState)
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> handler.post { onLinkConnected(gatt) }
            BluetoothProfile.STATE_DISCONNECTED -> handler.post { onLinkDisconnected(gatt, status) }
        }
    }

    private fun onLinkConnected(gatt: BluetoothGatt) {
        if (stop || mBluetoothGatt !== gatt) return
        Log.i(TAG, "$sensorId: connected to ${gatt.device?.address}")
        linkUp = true
        connectTime = System.currentTimeMillis()
        handler.removeCallbacks(reconnectRunnable)
        armSetupDeadline()
        phase = Phase.DISCOVERING
        UiRefreshBus.requestStatusRefresh()
        handler.postDelayed({
            if (phase == Phase.DISCOVERING && mBluetoothGatt === gatt) {
                val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                if (!started) dropLink("discoverServices failed")
            }
        }, DISCOVERY_DELAY_MS)
    }

    private fun onLinkDisconnected(gatt: BluetoothGatt, status: Int) {
        linkUp = false
        runCatching { gatt.close() }
        if (mBluetoothGatt === gatt) mBluetoothGatt = null
        // The connect mode stays the user's setting: the legacy driver's autoConnect fallback
        // (#728) was measured for that driver only, and this one has no field data yet.
        classifyLinkLossDuringHandshake()
        Log.i(TAG, "$sensorId: disconnected status=$status in $phase")
        noteLossOfSignal(System.currentTimeMillis())
        endLink()
        scheduleReconnect()
    }

    /** A link that drops mid-handshake says something about the credentials it was offered. */
    private fun classifyLinkLossDuringHandshake() {
        val hs = handshake ?: return
        if (hs.stage == Libre3Handshake.Stage.DONE || hs.stage == Libre3Handshake.Stage.FAILED) return
        val ctx = Applic.app ?: return
        if (!hs.isFull) {
            // The sensor would not take the cached kAuth (it also drops after Phase 5 when the
            // pairing was reset by another receiver). Fall back to full authorization next time.
            Log.w(TAG, "$sensorId: cached authorization dropped in ${hs.stage}; next attempt is full")
            Libre3Persistence.clearCachedAuth(ctx, sensorId)
            return
        }
        if (hs.stage == Libre3Handshake.Stage.AWAIT_CERT_ACCEPTED) {
            // RoundWhiteDiscKit and the legacy flow agree: a sensor that does not accept the app
            // certificate drops the link right after CertificateLoadDone.
            noteIdentityRejected(handshakeIdentity?.label ?: "?")
        }
    }

    private fun noteIdentityRejected(label: String) {
        val count = Applic.app?.let { Libre3Persistence.loadIdentitiesForSensor(it, sensorId).size } ?: 0
        identityRejections++
        identityCursor++
        Log.w(TAG, "$sensorId: identity '$label' not accepted ($identityRejections refusals, $count identities)")
        if (count == 0 || identityRejections >= count * MAX_IDENTITY_ROUNDS) {
            identityRejected = true
            lastNote = text(tk.glucodata.R.string.libre3_status_identity_rejected)
            UiRefreshBus.requestStatusRefresh()
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        handler.post {
            if (stop || mBluetoothGatt !== gatt) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) {
                dropLink("service discovery status $status")
                return@post
            }
            characteristics.clear()
            for (serviceUuid in listOf(Libre3Gatt.DATA_SERVICE, Libre3Gatt.SECURITY_SERVICE)) {
                gatt.getService(serviceUuid)?.characteristics?.forEach { characteristics[it.uuid] = it }
            }
            val required = Libre3Gatt.SECURITY_NOTIFY_ORDER + Libre3Gatt.DATA_NOTIFY_ORDER
            val missing = required.filterNot { characteristics.containsKey(it) }
            if (missing.isNotEmpty()) {
                // Deterministic: the same address will be missing them again. Stop until a new NFC
                // scan (which may report a different address) or an explicit user reconnect.
                deviceRejected = true
                dropLink("missing characteristics ${missing.joinToString { it.toString().take(8) }}")
                return@post
            }
            phase = Phase.SUBSCRIBING
            UiRefreshBus.requestStatusRefresh()
            Libre3Gatt.SECURITY_NOTIFY_ORDER.forEach { enqueue(GattOp.Notify(it)) }
            enqueue(GattOp.Run { startHandshake() })
        }
    }

    // ---- GATT op queue (handler thread) ----

    private fun enqueue(op: GattOp) {
        ops.addLast(op)
        pump()
    }

    private fun pump() {
        while (opInFlight == null) {
            val op = ops.removeFirstOrNull() ?: return
            val gatt = mBluetoothGatt
            if (gatt == null || stop) {
                ops.clear()
                return
            }
            when (op) {
                is GattOp.Run -> op.block()
                is GattOp.Notify -> {
                    val ch = characteristics[op.uuid] ?: return dropLink("no characteristic ${op.uuid}")
                    opInFlight = op
                    if (!enableNotification(gatt, ch)) return dropLink("enable notification ${op.uuid} failed")
                }
                is GattOp.Write -> {
                    val ch = characteristics[op.uuid] ?: return dropLink("no characteristic ${op.uuid}")
                    opInFlight = op
                    @Suppress("DEPRECATION")
                    val started = runCatching {
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ch.value = op.value
                        gatt.writeCharacteristic(ch)
                    }.getOrDefault(false)
                    if (!started) return dropLink("write ${op.label} failed to start")
                }
            }
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        handler.post {
            if (stop || mBluetoothGatt !== gatt) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) return@post dropLink("CCCD write status $status")
            if (opInFlight is GattOp.Notify) {
                opInFlight = null
                pump()
            }
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        handler.post {
            if (stop || mBluetoothGatt !== gatt) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) return@post dropLink("write status $status on ${characteristic.uuid}")
            if (opInFlight is GattOp.Write) {
                opInFlight = null
                pump()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        @Suppress("DEPRECATION")
        val value = characteristic.value?.copyOf() ?: return
        dispatchNotification(gatt, characteristic.uuid, value)
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        dispatchNotification(gatt, characteristic.uuid, value.copyOf())
    }

    private fun dispatchNotification(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        handler.post {
            if (stop || mBluetoothGatt !== gatt) return@post
            try {
                onNotification(uuid, value)
            } catch (t: Throwable) {
                Log.stack(TAG, "$sensorId onNotification $uuid", t)
                dropLink("notification handling failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun onNotification(uuid: UUID, value: ByteArray) {
        val ctx = Applic.app ?: return
        if (credentialRevision >= 0 && credentialRevision != Libre3Persistence.credentialRevision(ctx)) {
            missingIdentity = true
            dropLink("credentials changed")
            return
        }
        when (uuid) {
            Libre3Gatt.SEC_COMMAND_RESPONSE -> handshake?.let { perform(it.onCommandResponse(value)) }
            Libre3Gatt.SEC_CERT_DATA -> handshake?.let { perform(it.onCertData(value)) }
            Libre3Gatt.SEC_CHALLENGE_DATA -> handshake?.let { perform(it.onChallengeData(value)) }
            else -> onDataNotification(uuid, value)
        }
    }

    // ---- handshake ----

    private fun startHandshake() {
        val ctx = Applic.app ?: return dropLink("no context")
        val rec = record ?: return failSetup("Scan the sensor with NFC first", stopUntilUser = true)
        val auth = Libre3Persistence.authorizationSnapshot(ctx, sensorId)
        credentialRevision = auth.revision
        val cached = auth.cached
        val identities = auth.identities
        val hs = if (cached != null) {
            handshakeIdentity = identities.firstOrNull { it.label == cached.second }
            Libre3Handshake(Libre3Handshake.Mode.CACHED, rec.blePin, handshakeIdentity, cached.first)
        } else {
            if (identities.isEmpty()) {
                missingIdentity = true
                return failSetup(text(tk.glucodata.R.string.libre3_status_identity_missing), stopUntilUser = true)
            }
            val identity = pickIdentity(ctx, identities)
            handshakeIdentity = identity
            Log.i(TAG, "$sensorId: full authorization with identity '${identity.label}'")
            Libre3Handshake(Libre3Handshake.Mode.FULL, rec.blePin, identity, null)
        }
        handshake = hs
        phase = Phase.AUTHENTICATING
        UiRefreshBus.requestStatusRefresh()
        perform(hs.start())
    }

    /** The identity this sensor paired with first; otherwise rotate through the installed ones. */
    private fun pickIdentity(ctx: Context, identities: List<Libre3AppIdentity>): Libre3AppIdentity {
        if (identityRejections == 0) {
            Libre3Persistence.pairedIdentityLabel(ctx, sensorId)?.let { label ->
                identities.firstOrNull { it.label == label }?.let { return it }
            }
        }
        return identities[Math.floorMod(identityCursor, identities.size)]
    }

    private fun perform(actions: List<Libre3Handshake.Action>) {
        for (action in actions) {
            when (action) {
                is Libre3Handshake.Action.WriteCommand ->
                    enqueue(GattOp.Write(Libre3Gatt.SEC_COMMAND_RESPONSE, byteArrayOf(action.command.toByte()), "cmd %02x".format(action.command)))
                is Libre3Handshake.Action.WriteMessage ->
                    Libre3Framing.fragmentForWrite(action.message).forEachIndexed { i, frag ->
                        enqueue(GattOp.Write(action.characteristic, frag, "msg[$i]"))
                    }
                is Libre3Handshake.Action.Authenticated -> onAuthenticated(action)
                is Libre3Handshake.Action.Failed -> onHandshakeFailed(action)
            }
        }
    }

    private fun onAuthenticated(done: Libre3Handshake.Action.Authenticated) {
        Log.i(TAG, "$sensorId: authorized (${if (done.viaCache) "cached" else "full"})")
        val context = Applic.app ?: return
        if (credentialRevision != Libre3Persistence.credentialRevision(context)) {
            missingIdentity = true
            dropLink("credentials changed during authorization")
            return
        }
        session = Libre3DataPlaneSession(done.session.kEnc, done.session.ivEnc)
        glucoseAssembler.reset()
        identityRejections = 0
        identityRejected = false
        val ctx = Applic.app
        val label = handshakeIdentity?.label
        if (ctx != null && label != null && !done.viaCache) {
            if (!Libre3Persistence.saveCachedAuth(ctx, sensorId, done.kAuth, label, credentialRevision)) {
                missingIdentity = true
                dropLink("credentials removed during authorization")
                return
            }
        }
        phase = Phase.STARTING_DATA
        UiRefreshBus.requestStatusRefresh()
        Libre3Gatt.DATA_NOTIFY_ORDER.forEach { enqueue(GattOp.Notify(it)) }
        enqueue(GattOp.Run { onStreamingReady() })
    }

    private fun onHandshakeFailed(failure: Libre3Handshake.Action.Failed) {
        Log.e(TAG, "$sensorId: handshake failed (${failure.kind}): ${failure.reason}")
        val ctx = Applic.app
        val hs = handshake
        when (failure.kind) {
            Libre3Handshake.FailureKind.CONFIGURATION -> return failSetup(failure.reason, stopUntilUser = true)
            Libre3Handshake.FailureKind.AUTHORIZATION ->
                if (hs != null && !hs.isFull) ctx?.let { Libre3Persistence.clearCachedAuth(it, sensorId) }
                else noteIdentityRejected(handshakeIdentity?.label ?: "?")
            Libre3Handshake.FailureKind.SENSOR_CERTIFICATE -> {
                deviceRejected = true
                lastNote = text(tk.glucodata.R.string.libre3_status_certificate_rejected)
            }
            Libre3Handshake.FailureKind.PROTOCOL -> Unit
        }
        dropLink("handshake ${failure.kind}")
    }

    private fun onStreamingReady() {
        phase = Phase.STREAMING
        clearSetupDeadline()
        armWatchdog()
        lastNote = ""
        UiRefreshBus.requestStatusRefresh()
        Log.i(TAG, "$sensorId: data notifications enabled")
    }

    // ---- data plane ----

    private fun onDataNotification(uuid: UUID, value: ByteArray) {
        val s = session ?: return
        val channel = Libre3Channel.of(uuid) ?: return
        val frame = if (channel == Libre3Channel.GLUCOSE_DATA) glucoseAssembler.feed(value) ?: return else value
        val decrypted = s.decrypt(channel, frame)
        if (decrypted == null) {
            Log.w(TAG, "$sensorId: ${channel.name} frame (${frame.size} B) did not authenticate")
            if (channel == Libre3Channel.GLUCOSE_DATA) glucoseAssembler.reset()
            return
        }
        if (decrypted.descriptorKind != channel.descriptorKind) {
            Log.w(TAG, "$sensorId: ${channel.name} verified under descriptor ${decrypted.descriptorKind}, expected ${channel.descriptorKind}")
        }
        val p = decrypted.plaintext
        when (channel) {
            Libre3Channel.GLUCOSE_DATA -> Libre3RealtimeReading.parse(p)?.let { onRealtime(it) }
                ?: Log.w(TAG, "$sensorId: glucose plaintext ${p.size} B")
            Libre3Channel.PATCH_STATUS -> Libre3PatchStatus.parse(p)?.let { onPatchStatus(it) }
                ?: Log.w(TAG, "$sensorId: status plaintext ${p.size} B")
            Libre3Channel.CLINICAL_DATA -> Libre3ClinicalRecord.parse(p)?.let { onClinical(it) }
            Libre3Channel.HISTORIC_DATA -> Libre3HistoricalPage.parse(p)?.let { onHistoricPage(it) }
            Libre3Channel.PATCH_CONTROL -> onControlResponse()
            Libre3Channel.EVENT_LOG -> Log.i(TAG, "$sensorId: event log up to index ${Libre3EventLog.lastIndex(p)}")
            Libre3Channel.FACTORY_DATA -> Log.d(TAG, "$sensorId: factory data ${p.size} B")
        }
    }

    private fun lifeCountTimeMs(lifeCount: Int): Long {
        val base = anchorStartMs.takeIf { it > 0L } ?: record?.startTimeMs ?: 0L
        return if (base > 0L) base + lifeCount * 60_000L else 0L
    }

    private fun inWarmup(lifeCount: Int): Boolean = lifeCount < (record?.warmupMinutes ?: 60)

    private fun pastWear(lifeCount: Int): Boolean {
        val wear = record?.wearMinutes ?: 0
        return wear > 0 && lifeCount > wear
    }

    private fun onRealtime(r: Libre3RealtimeReading) {
        val now = System.currentTimeMillis()
        armWatchdog()
        // The earliest arrival is closest to the sensor's own minute boundary.
        val anchor = now - r.lifeCount * 60_000L
        if (anchorStartMs <= 0L || anchor < anchorStartMs || anchor - anchorStartMs > 120_000L) {
            anchorStartMs = anchor
            Applic.app?.let { Libre3Persistence.setAnchor(it, sensorId, anchor) }
        }
        r.historicalMgdl?.let { hist ->
            if (r.historicalLifeCount > 0) addHistoric(r.historicalLifeCount, hist)
            if (r.historicalLifeCount == lastHistoricLifeCount + Libre3HistoricalPage.INTERVAL_MINUTES) {
                lastHistoricLifeCount = r.historicalLifeCount
            }
        }
        markMinuteReceived(r.lifeCount)
        val mgdl = r.currentMgdl
        when {
            inWarmup(r.lifeCount) -> {
                lastNote = "Warming up (${(record?.warmupMinutes ?: 60) - r.lifeCount} min left)"
                noteLiveFrameWithoutReading(now)
            }
            mgdl == null -> {
                lastNote = when {
                    r.dataQualityError -> "No reading (data quality)"
                    r.sensorCondition != 0 -> "No reading (sensor condition ${r.sensorCondition})"
                    (r.currentWord and 0x1FFF) in 1 until Libre3Glucose.MIN_DISPLAY_MGDL -> "Below ${Libre3Glucose.MIN_DISPLAY_MGDL} mg/dL"
                    (r.currentWord and 0x1FFF) > Libre3Glucose.MAX_DISPLAY_MGDL -> "Above ${Libre3Glucose.MAX_DISPLAY_MGDL} mg/dL"
                    else -> "No reading"
                }
                noteLiveFrameWithoutReading(now)
                Log.i(TAG, "$sensorId: life ${r.lifeCount} word=%04x: $lastNote".format(r.currentWord))
            }
            else -> {
                // No wear-duration cut-off: like the legacy native driver, a value the sensor still
                // marks valid after its nominal wear time is published; the sensor decides when it stops.
                lastNote = if (pastWear(r.lifeCount)) "Past nominal wear time" else ""
                publishCurrent(now, mgdl.toFloat(), r.rateMgdlPerMinute)
            }
        }
        scheduleBackfillFlush()
        UiRefreshBus.requestStatusRefresh()
    }

    private fun publishCurrent(timeMs: Long, mgdl: Float, rate: Float) {
        synchronized(readingLock) {
            if (stop) return
            lastReadingMs = timeMs
            lastReadingMgdl = mgdl
            lastRate = rate
            markLocalReadingAccepted(timeMs)
            ensureNativeWindow()
            val safeRate = rate.takeIf { it.isFinite() } ?: 0f
            // Include live minutes in the blocking flush before its progress can be persisted.
            minuteBackfill += VirtualGlucoseSensorBridge.Reading(timestampMs = timeMs, glucoseMgdl = mgdl, rate = rate)
            VirtualGlucoseSensorBridge.mirrorToNative(
                sensorId,
                listOf(VirtualGlucoseSensorBridge.Reading(timestampMs = timeMs, glucoseMgdl = mgdl, rate = rate)),
                LOG_LABEL,
                ManagedSensorUiFamily.LIBRE3,
            )
            val display = if (Applic.unit == 1) mgdl / MGDL_PER_MMOL else mgdl
            // Stock lane: the sensor's own value, before this app's optional software calibration.
            SuperGattCallback.processExternalCurrentReading(
                sensorId,
                LiveReadingLanes.stock(display, Float.NaN),
                safeRate,
                timeMs,
                SENSOR_GEN,
                record?.startTimeMs ?: 0L,
            )
        }
        UiRefreshBus.requestDataRefresh()
    }

    private fun onPatchStatus(status: Libre3PatchStatus) {
        lastStatus = status
        Log.i(
            TAG,
            "$sensorId: status state=${status.patchState} error=${status.errorData} life=${status.currentLifeCount} " +
                "event=${status.eventIndex} disconnect=${status.stackDisconnectReason}/${status.appDisconnectReason}",
        )
        // Only a shut-down sensor stops reconnects. Normal end of wear (errorData 5) keeps
        // advertising and is shown, not treated as terminal.
        if (status.isTerminated) markEnded()
        planBackfill(status.currentLifeCount)
        UiRefreshBus.requestStatusRefresh()
    }

    private var nativeWindowChecked = false

    /**
     * The generic mirror opens a native window one minute before the first reading it sees. For a
     * sensor taken over mid-life the backfill that follows is older than that and would be dropped
     * from native exports (web server, Nightscout uploader), so open the window at the sensor's
     * own start first. An existing shell is left as it is.
     */
    private fun ensureNativeWindow() {
        if (nativeWindowChecked) return
        nativeWindowChecked = true
        val startMs = record?.startTimeMs ?: return
        if (startMs <= 0L) return
        runCatching {
            if (Natives.str2sensorptr(sensorId) != 0L) return
            val nowSec = System.currentTimeMillis() / 1000L
            val startSec = maxOf(startMs / 1000L - 60L, nowSec - NATIVE_WINDOW_SECONDS + 60L)
            if (Natives.ensureSensorShellWithCapacity(sensorId, startSec, NATIVE_WINDOW_MINUTES) == 0L) {
                Natives.ensureSensorShell(sensorId, startSec)
            }
        }.onFailure { Log.stack(TAG, "$sensorId ensureNativeWindow", it) }
    }

    private fun markEnded() {
        if (ended) return
        ended = true
        Applic.app?.let { Libre3Persistence.setEnded(it, sensorId, true) }
        Log.i(TAG, "$sensorId: sensor reports end of life; no further reconnects")
    }

    private fun onClinical(rec: Libre3ClinicalRecord) {
        highestClinicalSeen = maxOf(highestClinicalSeen, rec.lifeCount)
        markMinuteReceived(rec.lifeCount)
        val time = lifeCountTimeMs(rec.lifeCount)
        val mgdl = rec.currentMgdl
        if (time > 0L && mgdl != null && !inWarmup(rec.lifeCount)) {
            minuteBackfill += VirtualGlucoseSensorBridge.Reading(timestampMs = time, glucoseMgdl = mgdl.toFloat())
        }
        val histLife = rec.estimatedHistoricalLifeCount
        rec.historicalMgdl?.let { if (histLife > 0) addHistoric(histLife, it) }
        scheduleBackfillFlush()
    }

    private fun onHistoricPage(page: Libre3HistoricalPage) {
        highestHistoricSeen = maxOf(highestHistoricSeen, page.lastLifeCount)
        page.samples().forEach { (life, mgdl) -> addHistoric(life, mgdl) }
        scheduleBackfillFlush()
    }

    private fun addHistoric(lifeCount: Int, mgdl: Int) {
        if (inWarmup(lifeCount)) return
        val time = lifeCountTimeMs(lifeCount)
        if (time <= 0L) return
        historicBackfill += VirtualGlucoseSensorBridge.Reading(timestampMs = time, glucoseMgdl = mgdl.toFloat())
    }

    /** Advances the contiguous minute mark; minutes past a gap wait until the gap closes. */
    private fun markMinuteReceived(lifeCount: Int) {
        if (lifeCount <= lastLifeCount) return
        receivedAhead += lifeCount
        while (receivedAhead.remove(lastLifeCount + 1)) lastLifeCount++
        // On a first connection nothing is contiguous yet; the first clinical backfill settles it.
        while (receivedAhead.size > 2_000) receivedAhead.pollFirst()
    }

    private fun planBackfill(currentLifeCount: Int) {
        if (session == null || controlInFlight != null || controlQueue.isNotEmpty()) return
        if (currentLifeCount <= 0) return
        if (lastLifeCount < currentLifeCount - 1) {
            val from = if (lastLifeCount <= 0) maxOf(1, currentLifeCount - INITIAL_CLINICAL_WINDOW_MINUTES) else lastLifeCount
            controlQueue.addLast(ControlKind.CLINICAL to Libre3ControlCommand.clinicalFrom(from))
            Log.i(TAG, "$sensorId: clinical backfill from life $from (current $currentLifeCount)")
        }
        // Same bound as the legacy driver: history commits about 16-17 minutes behind current.
        val committed = ((currentLifeCount - 16) / 5) * 5
        if (lastHistoricLifeCount < committed) {
            val from = maxOf(lastHistoricLifeCount, 5)
            requestedHistoricCommitted = committed
            controlQueue.addLast(ControlKind.HISTORIC to Libre3ControlCommand.historicalFrom(from))
            Log.i(TAG, "$sensorId: history backfill from life $from (committed $committed)")
        }
        sendNextControl()
    }

    private fun sendNextControl() {
        if (controlInFlight != null) return
        val s = session ?: return
        val (kind, plaintext) = controlQueue.removeFirstOrNull() ?: return
        controlInFlight = kind
        enqueue(GattOp.Write(Libre3Gatt.PATCH_CONTROL, s.encryptControl(plaintext), "control $kind"))
        handler.removeCallbacks(controlTimeoutRunnable)
        handler.postDelayed(controlTimeoutRunnable, CONTROL_RESPONSE_TIMEOUT_MS)
    }

    private val controlTimeoutRunnable = Runnable {
        if (controlInFlight != null) {
            Log.w(TAG, "$sensorId: no response to ${controlInFlight} backfill request")
            // Replies carry no request identity. A new operation on this link could mistake a
            // late completion for its own, so only a fresh authenticated link may retry.
            dropLink("backfill response timeout")
        }
    }

    /** The sensor answers on patchControl once a requested backfill has been delivered. */
    private fun onControlResponse() {
        handler.removeCallbacks(controlTimeoutRunnable)
        // Timeouts tear down the link; only a real completion reaches this method.
        val settled = controlInFlight
        when (settled) {
            // Whatever the sensor did not replay is gone; settle the mark so the request is not repeated.
            ControlKind.CLINICAL -> {
                if (highestClinicalSeen > lastLifeCount) {
                    lastLifeCount = highestClinicalSeen
                    receivedAhead.headSet(lastLifeCount, true).clear()
                    while (receivedAhead.remove(lastLifeCount + 1)) lastLifeCount++
                }
            }
            // A completed request settles the range it covered even when the sensor had no pages
            // for it; otherwise every patch status would ask for the same empty range again.
            ControlKind.HISTORIC ->
                lastHistoricLifeCount = maxOf(lastHistoricLifeCount, highestHistoricSeen, requestedHistoricCommitted)
            null -> Unit
        }
        controlInFlight = null
        flushBackfill()
        sendNextControl()
    }

    private fun persistMarks() {
        Applic.app?.let { Libre3Persistence.setBackfillMarks(it, sensorId, lastLifeCount, lastHistoricLifeCount) }
    }

    private val flushRunnable = Runnable { flushBackfill() }

    private fun scheduleBackfillFlush() {
        handler.removeCallbacks(flushRunnable)
        handler.postDelayed(flushRunnable, BACKFILL_FLUSH_DELAY_MS)
    }

    /** Runs on the driver thread: the Room import blocks. */
    private fun flushBackfill() {
        handler.removeCallbacks(flushRunnable)
        if (minuteBackfill.isEmpty() && historicBackfill.isEmpty()) {
            persistMarks()
            return
        }
        val minutes = ArrayList(minuteBackfill).also { minuteBackfill.clear() }
        val history = ArrayList(historicBackfill).also { historicBackfill.clear() }
        // Marks are persisted only after the readings they cover were handed to Room, so a process
        // death between receiving a backfill and storing it re-requests the range instead of losing it.
        val stored = runCatching { storeBackfill(minutes, history) }
            .onFailure { Log.stack(TAG, "$sensorId flushBackfill", it) }
            .getOrDefault(false)
        if (stored) {
            persistMarks()
        } else if (!stop) {
            minuteBackfill.addAll(0, minutes)
            historicBackfill.addAll(0, history)
            scheduleBackfillFlush()
        }
        UiRefreshBus.requestDataRefresh()
    }

    private fun storeBackfill(
        minutes: List<VirtualGlucoseSensorBridge.Reading>,
        history: List<VirtualGlucoseSensorBridge.Reading>,
    ): Boolean {
        synchronized(readingLock) {
            if (stop) return false
            ensureNativeWindow()
            // Five-minute points that land in a hole (no stored or incoming reading within the
            // duplicate window) are mirrored natively too; the rest would overwrite minute slots.
            val gapFillers = if (history.isEmpty()) emptyList() else {
                val from = history.minOf { it.timestampMs } - HISTORY_DUPLICATE_WINDOW_MS
                val to = history.maxOf { it.timestampMs } + HISTORY_DUPLICATE_WINDOW_MS
                val known = ((HistorySyncAccess.getHistoryTimestampsForSensorOrNull(sensorId, from.coerceAtLeast(1L), to)
                    ?: return false).toList() +
                    minutes.map { it.timestampMs }).sorted()
                history.filter { h -> known.none { kotlin.math.abs(it - h.timestampMs) <= HISTORY_DUPLICATE_WINDOW_MS } }
            }
            if (minutes.isNotEmpty()) {
                VirtualGlucoseSensorBridge.importHistory(
                    sensorId, minutes, "$LOG_LABEL clinical",
                    backfill = true,
                    nearDuplicateWindowMs = MINUTE_DUPLICATE_WINDOW_MS,
                    mirrorToNative = false,
                    failOnStorageError = true,
                    nativeFamily = ManagedSensorUiFamily.LIBRE3,
                )
            }
            if (minutes.isNotEmpty()) {
                VirtualGlucoseSensorBridge.mirrorToNative(sensorId, minutes, "$LOG_LABEL clinical", ManagedSensorUiFamily.LIBRE3)
            }
            if (history.isNotEmpty()) {
                // Only the previously identified holes may be mirrored with smoothed values.
                VirtualGlucoseSensorBridge.importHistory(
                    sensorId, history, "$LOG_LABEL history",
                    backfill = true,
                    nearDuplicateWindowMs = HISTORY_DUPLICATE_WINDOW_MS,
                    mirrorToNative = false,
                    failOnStorageError = true,
                    nativeFamily = ManagedSensorUiFamily.LIBRE3,
                )
                if (gapFillers.isNotEmpty()) {
                    VirtualGlucoseSensorBridge.mirrorToNative(sensorId, gapFillers, "$LOG_LABEL history", ManagedSensorUiFamily.LIBRE3)
                }
            }
        }
        return true
    }

    // ---- deadlines / reconnect ----

    private val setupDeadlineRunnable = Runnable {
        if (phase != Phase.STREAMING && !stop) {
            Log.w(TAG, "$sensorId: setup did not complete in $phase (handshake ${handshake?.stage})")
            classifyLinkLossDuringHandshake()
            dropLink("setup timeout")
        }
    }

    private fun armSetupDeadline() {
        handler.removeCallbacks(setupDeadlineRunnable)
        handler.postDelayed(setupDeadlineRunnable, SETUP_TIMEOUT_MS)
    }

    private fun clearSetupDeadline() = handler.removeCallbacks(setupDeadlineRunnable)

    private val watchdogRunnable = Runnable {
        if (phase == Phase.STREAMING && !stop) dropLink("no glucose frame for ${STREAM_WATCHDOG_MS / 60_000} min")
    }

    private fun armWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, STREAM_WATCHDOG_MS)
    }

    private val reconnectRunnable = Runnable { if (!stop) connectDevice(0) }

    private fun scheduleReconnect() {
        if (stop || ended || identityRejected || missingIdentity || deviceRejected) {
            phase = if (ended) Phase.ENDED else if (identityRejected || missingIdentity || deviceRejected) Phase.IDLE else phase
            UiRefreshBus.requestStatusRefresh()
            return
        }
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun failSetup(note: String, stopUntilUser: Boolean) {
        lastNote = note
        Log.e(TAG, "$sensorId: $note")
        clearSetupDeadline()
        closeGattTransport()
        endLink()
        phase = if (stopUntilUser) Phase.IDLE else Phase.ERROR
        UiRefreshBus.requestStatusRefresh()
        if (!stopUntilUser) scheduleReconnect()
    }

    /** Tear down a link Android will not report the loss of (we close the GATT ourselves). */
    private fun dropLink(reason: String) {
        Log.w(TAG, "$sensorId: dropping link: $reason")
        closeGattTransport()
        endLink()
        scheduleReconnect()
    }

    /** Per-link state goes; persisted marks and the sensor record stay. */
    private fun endLink() {
        linkUp = false
        flushBackfill()
        handler.removeCallbacks(setupDeadlineRunnable)
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(controlTimeoutRunnable)
        ops.clear()
        opInFlight = null
        characteristics.clear()
        handshake = null
        handshakeIdentity = null
        session = null
        glucoseAssembler.reset()
        controlQueue.clear()
        controlInFlight = null
        if (phase != Phase.ENDED) phase = if (ended) Phase.ENDED else Phase.IDLE
        UiRefreshBus.requestStatusRefresh()
    }

    // ---- managed lifecycle ----

    override fun softDisconnect() {
        setPause(true)
        handler.post {
            handler.removeCallbacks(reconnectRunnable)
            closeGattTransport()
            endLink()
        }
    }

    override fun softReconnect() {
        setPause(false)
        handler.post {
            identityRejected = false
            identityRejections = 0
            missingIdentity = false
            deviceRejected = false
            handler.removeCallbacks(reconnectRunnable)
            closeGattTransport()
            endLink()
            if (mActiveBluetoothDevice == null) mActiveBluetoothDevice = knownDevice()
            handler.postDelayed({ if (!stop) connectDevice(0) }, 250L)
        }
    }

    override fun onBluetoothAdapterUnavailable() {
        handler.post {
            handler.removeCallbacks(reconnectRunnable)
            endLink()
        }
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        synchronized(readingLock) {
            setPause(true)
            phase = Phase.IDLE
            runCatching { close() }.onFailure { Log.stack(TAG, "terminateManagedSensor(close)", it) }
            runCatching { Natives.finishSensorById(sensorId) }
                .onFailure { Log.stack(TAG, "terminateManagedSensor(finishSensorById)", it) }
            if (wipeData) Applic.app?.let { Libre3Persistence.removeSensor(it, sensorId) }
        }
    }

    override fun removeManagedPersistence(context: Context) {
        Libre3Persistence.removeSensor(context, sensorId)
    }

    override fun close() {
        linkUp = false
        if (stop) quitHandlerThread() else handler.post { endLink() }
        super.close()
    }
}
