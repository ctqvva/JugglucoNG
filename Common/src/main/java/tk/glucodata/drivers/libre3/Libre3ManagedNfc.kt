// Libre3ManagedNfc.kt — NFC scan and sensor registration for the Libre 3 driver.
//
// Replaced the proprietary path (NFC.java → libcrl_dp's activation data → native record),
// which is removed. The patch-info read and the A0/A8 command are plain ISO 15693
// transceives through the existing AlgNfcV helper.

package tk.glucodata.drivers.libre3

import android.content.Context
import android.nfc.Tag
import tk.glucodata.AlgNfcV
import tk.glucodata.Applic
import tk.glucodata.CurrentSensorSelection
import tk.glucodata.Libre3NfcSettings
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback

object Libre3ManagedNfc {
    private const val TAG = "Libre3Nfc"

    // Result codes ScanNfcV already renders for Libre 3 scans.
    const val RESULT_PAIRED = 0xFC
    const val RESULT_NO_RESPONSE = 0xFD
    const val RESULT_WRONG_RECEIVER = 0xFB
    const val RESULT_FAILED = 0xFA
    const val RESULT_TERMINATED = 13
    /** Legacy "no LibreView account ID" result (ScanNfcV 0xF8). */
    const val RESULT_NO_ACCOUNT_ID = 0xF8

    /** Serial of the sensor the last successful scan paired, for the success dialog. */
    @Volatile private var lastPaired: String? = null

    @Volatile private var lastActivated = false

    @JvmStatic
    fun lastScanActivated(): Boolean = lastActivated

    @JvmStatic
    fun lastPairedSensorId(): String? = lastPaired

    sealed class Outcome {
        data class Paired(val sensorId: String, val activated: Boolean) : Outcome()
        object NoResponse : Outcome()
        object MissingIdentity : Outcome()
        object NoAccountId : Outcome()
        data class Terminated(val serial: String) : Outcome()
        data class WrongReceiver(val serial: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** Same UID test ScanNfcV uses to tell a Libre 3 tag from a Libre 1/2 one. */
    @JvmStatic
    fun isLibre3Tag(tag: Tag?): Boolean {
        val uid = tag?.id ?: return false
        return uid.size == 8 && uid[6].toInt() != 7
    }

    /**
     * The receiver ID this install puts in A0/A8, or 0 when the LibreView account is the selected
     * source but no account ID is known. That case is refused like the legacy scan (0xF8) instead
     * of silently activating the sensor under an ID the user did not choose.
     */
    @JvmStatic
    fun receiverId(context: Context): Long {
        if (Libre3Persistence.getReceiverSource(context) == Libre3Persistence.RECEIVER_FROM_LIBREVIEW_ACCOUNT) {
            // The value the legacy path has always sent, so sensors it activated keep accepting A8.
            return runCatching { Natives.getlibreAccountIDnumber() }.getOrDefault(0L) and 0xFFFFFFFFL
        }
        return Libre3Persistence.ownReceiverId(context)
    }

    /** Entry point for ScanNfcV: returns {result code, value} like its legacy libre3scan(). */
    @JvmStatic
    fun scanForUi(tag: Tag): IntArray {
        val outcome = runCatching { scan(tag) }
            .onFailure { Log.stack(TAG, "scan", it) }
            .getOrElse { Outcome.Failed(it.javaClass.simpleName) }
        Log.i(TAG, "managed Libre 3 scan: $outcome")
        return when (outcome) {
            is Outcome.Paired -> {
                lastActivated = outcome.activated
                lastPaired = outcome.sensorId
                intArrayOf(RESULT_PAIRED, 1)
            }
            Outcome.NoAccountId -> intArrayOf(RESULT_NO_ACCOUNT_ID, 0)
            Outcome.NoResponse -> intArrayOf(RESULT_NO_RESPONSE, 0)
            Outcome.MissingIdentity -> {
                Applic.Toaster(tk.glucodata.R.string.libre3_managed_identity_missing)
                intArrayOf(RESULT_FAILED, 0)
            }
            is Outcome.Terminated -> intArrayOf(RESULT_TERMINATED, 0)
            is Outcome.WrongReceiver -> intArrayOf(RESULT_WRONG_RECEIVER, 0)
            is Outcome.Failed -> intArrayOf(RESULT_FAILED, 0)
        }
    }

    fun scan(tag: Tag): Outcome {
        val ctx = Applic.app ?: return Outcome.Failed("no context")
        val info = Libre3Nfc.parsePatchInfo(AlgNfcV.wholenfccmd(tag, Libre3Nfc.patchInfoCommand()))
            ?: return Outcome.NoResponse
        Log.i(
            TAG,
            "patch info serial=${info.serial} state=${info.state} fw=${info.firmwareVersion} " +
                "secver=${info.securityVersion} wear=${info.wearDurationMinutes} warmup=${info.warmupMinutes} " +
                "product=${info.productType} gen=${info.puckGeneration}",
        )
        if (info.isTerminated) {
            Libre3Registry.endSensor(ctx, info.serial)
            return Outcome.Terminated(info.serial)
        }
        // A0 starts the sensor's wear clock and A8 moves it to this receiver; neither is worth doing
        // when this phone could not authorize over BLE afterwards.
        if (!runCatching { Libre3Persistence.ensureIdentities(ctx) }.getOrDefault(false)) return Outcome.MissingIdentity

        val override = when (Libre3NfcSettings.getMode()) {
            Libre3NfcSettings.MODE_ACTIVATE_A0 -> Libre3Nfc.CMD_ACTIVATE
            Libre3NfcSettings.MODE_SWITCH_RECEIVER_A8 -> Libre3Nfc.CMD_SWITCH_RECEIVER
            else -> null
        }
        val command = Libre3Nfc.chooseCommand(info, override)
        val receiver = receiverId(ctx)
        if (receiver == 0L) return Outcome.NoAccountId
        val nowSeconds = Math.round(System.currentTimeMillis() / 1000.0)
        val response = AlgNfcV.wholenfccmd(tag, Libre3Nfc.activationCommand(command, nowSeconds, receiver))
        return when (val result = Libre3Nfc.parseActivationResponse(response, nowSeconds)) {
            is Libre3Nfc.ActivationResult.Success -> {
                val known = Libre3Persistence.loadSensor(ctx, info.serial)
                val startMs = pairingStartTimeMs(command, result, known?.startTimeMs) {
                    val legacyPtr = Natives.str2sensorptr(Libre3IdentityAdapter.legacyNativeName(info.serial))
                    if (legacyPtr != 0L) Natives.getSensorStartmsecFromSensorptr(legacyPtr) else 0L
                }
                val record = Libre3SensorRecord(
                    sensorId = info.serial,
                    bleAddress = result.bleAddress,
                    blePin = result.blePin,
                    startTimeMs = startMs,
                    warmupMinutes = info.warmupMinutes.takeIf { it in 1..255 } ?: 60,
                    wearMinutes = info.wearDurationMinutes,
                    receiverId = receiver,
                    firmware = info.firmwareVersion,
                    securityVersion = info.securityVersion,
                    productType = info.productType,
                )
                Libre3Registry.addSensor(ctx, record)
                Outcome.Paired(info.serial, activated = command == Libre3Nfc.CMD_ACTIVATE)
            }
            is Libre3Nfc.ActivationResult.SensorError ->
                if (result.isWrongReceiver) Outcome.WrongReceiver(info.serial)
                else Outcome.Failed("sensor error %02x".format(result.code))
            is Libre3Nfc.ActivationResult.Malformed ->
                if (response == null) Outcome.NoResponse else Outcome.Failed("malformed response (${result.length} B)")
        }
    }

    internal fun pairingStartTimeMs(
        command: Byte,
        result: Libre3Nfc.ActivationResult.Success,
        knownStartMs: Long?,
        legacyStartMs: () -> Long,
    ): Long = when {
        result.reportedActivationTime -> result.activationTimeSeconds * 1000L
        knownStartMs != null && knownStartMs > 0L -> knownStartMs
        command == Libre3Nfc.CMD_ACTIVATE -> result.activationTimeSeconds * 1000L
        // A8 can move a sensor that has already run for days. Scan time is
        // not its activation time. If unknown, wait for the first life count.
        else -> runCatching(legacyStartMs).getOrDefault(0L)
    }
}

object Libre3Registry {
    private const val TAG = "Libre3Registry"

    @JvmStatic
    fun findDriver(sensorId: String): Libre3BleManager? =
        SensorBluetooth.gattcallbacks.firstNotNullOfOrNull { cb ->
            (cb as? Libre3BleManager)?.takeIf { it.matchesManagedSensorId(sensorId) }
        }

    /** NFC says the sensor is shut down: end the managed record and stop its driver. */
    @JvmStatic
    fun endSensor(context: Context, sensorId: String) {
        if (!Libre3Persistence.hasSensor(context, sensorId)) return
        Libre3Persistence.setEnded(context, sensorId, true)
        findDriver(sensorId)?.onSensorTerminated()
        Log.i(TAG, "$sensorId: NFC reports the sensor terminated")
    }

    /** Persist the NFC result and start (or refresh) the driver for it. */
    @JvmStatic
    fun addSensor(context: Context, record: Libre3SensorRecord): String {
        val id = record.sensorId
        val handover = CurrentSensorSelection.captureDriverHandover { isLegacyCallbackName(it, id) }
        // Finish the legacy native record before anything else can rebuild its callback.
        val legacy = finishLegacySensor(id)
        Libre3Persistence.saveSensor(context, record)
        SensorIdentity.invalidateCaches()
        val existing = findDriver(id)
        if (existing != null) {
            finishLegacyHandover(legacy, handover, id)
            existing.onNfcRecordUpdated()
            return id
        }
        val blue = SensorBluetooth.blueone
        if (blue == null) {
            finishLegacyHandover(legacy, handover, id)
            return id
        }
        val callback = Libre3BleManager(id)
        SensorBluetooth.gattcallbacks.add(callback)
        runCatching { Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size) }
        // After the successor is in the list, so the current-sensor role can move to it.
        finishLegacyHandover(legacy, handover, id)
        if (SensorBluetooth.blueone === blue) callback.connectDevice(0)
        return id
    }

    /**
     * The legacy native driver's callbacks for this sensor. Native names the record
     * "E07A-XX<serial>" but a callback carries getSensorName(), the 11-character short name
     * "XX<serial>", so both spellings are matched, plus whatever the callback's name resolves to.
     */
    internal fun isLegacyCallbackName(callbackName: String?, serial: String, resolveFull: (String) -> String? = ::resolveFullName): Boolean {
        val name = callbackName?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        val full = Libre3IdentityAdapter.legacyNativeName(serial)
        if (name.equals(full, ignoreCase = true) || name.equals(Libre3IdentityAdapter.legacyShortName(serial), ignoreCase = true)) return true
        return resolveFull(name)?.equals(full, ignoreCase = true) == true
    }

    private fun resolveFullName(name: String): String? = runCatching { Natives.resolveFullSensorName(name) }.getOrNull()

    /**
     * A sensor moving from the legacy native driver to this one must not keep its old callback:
     * both would connect to the same address with different keys. Finishing the native record
     * keeps its history and stops it from being rebuilt; the legacy driver no
     * longer exists, so it cannot come back.
     */
    private fun finishLegacySensor(serial: String): List<SuperGattCallback> {
        val legacyName = Libre3IdentityAdapter.legacyNativeName(serial)
        val legacy = SensorBluetooth.gattcallbacks.filter { it !is Libre3BleManager && isLegacyCallbackName(it.SerialNumber, serial) }
        val hasNative = runCatching { Natives.str2sensorptr(legacyName) != 0L }.getOrDefault(false)
        if (legacy.isEmpty() && !hasNative) return emptyList()
        Log.i(TAG, "Handing $legacyName from the legacy Libre 3 driver to the Kotlin driver")
        runCatching { Natives.finishSensorById(legacyName) }.onFailure { Log.stack(TAG, "finishSensorById", it) }
        return legacy
    }

    private fun finishLegacyHandover(
        legacy: List<SuperGattCallback>,
        handover: CurrentSensorSelection.DriverHandover?,
        successor: String,
    ) {
        legacy.forEach { cb ->
            Log.i(TAG, "Retiring legacy Libre 3 callback ${cb.SerialNumber} for $successor")
            runCatching { SensorBluetooth.retireCallback(cb) }.onFailure { Log.stack(TAG, "retireCallback", it) }
        }
        if (legacy.isNotEmpty() || handover != null) SensorBluetooth.finishDriverHandover(handover, successor)
    }

    /**
     * A sensor the removed proprietary driver was reading. Its native record still holds what
     * the NFC scan gave it (BLE address, PIN, start time), which is all this driver needs: it
     * authorizes with the downloaded identities, so no rescan is necessary. The legacy record
     * is finished (its history stays), the main-sensor role moves over, and the callback
     * returned here takes the legacy one's place in the roster.
     */
    @JvmStatic
    fun adoptLegacySensor(appContext: Context?, legacyName: String, dataptr: Long): SuperGattCallback {
        val full = runCatching { Natives.resolveFullSensorName(legacyName) }.getOrNull() ?: legacyName
        val serial = legacySerial(full, legacyName)
        val context = appContext ?: Applic.app
        if (context != null && !Libre3Persistence.hasSensor(context, serial)) {
            val record = runCatching { legacyRecord(serial, legacyName, dataptr) }
                .onFailure { Log.stack(TAG, "legacy record $legacyName", it) }.getOrNull()
            if (record != null) {
                Libre3Persistence.saveSensor(context, record)
                Log.i(TAG, "Moved $full from the removed proprietary driver to $serial")
            } else {
                Log.w(TAG, "$full has no BLE address or PIN; it needs one NFC scan")
            }
        }
        val selected = SensorIdentity.resolveMainSensor()?.let { isLegacyCallbackName(it, serial) } == true
        if (dataptr != 0L) runCatching { Natives.freedataptr(dataptr) }
        runCatching { Natives.finishSensorById(Libre3IdentityAdapter.legacyNativeName(serial)) }
            .onFailure { Log.stack(TAG, "finishSensorById", it) }
        SensorIdentity.invalidateCaches()
        if (selected) CurrentSensorSelection.select(serial)
        return Libre3BleManager(serial)
    }

    internal fun legacySerial(fullName: String, callbackName: String): String = when {
        fullName.startsWith(LEGACY_PREFIX, ignoreCase = true) -> fullName.substring(LEGACY_PREFIX.length)
        callbackName.length == 11 && callbackName.startsWith("XX", ignoreCase = true) -> callbackName.substring(2)
        else -> callbackName.takeLast(9)
    }

    private const val LEGACY_PREFIX = "E07A-XX"

    private fun legacyRecord(serial: String, legacyName: String, dataptr: Long): Libre3SensorRecord? {
        if (dataptr == 0L) return null
        val address = Natives.getDeviceAddress(dataptr, false)?.takeIf { it.isNotBlank() } ?: return null
        val sensorPtr = Natives.getsensorptr(dataptr)
        val pin = if (sensorPtr != 0L) Natives.getpin(sensorPtr) else null
        if (pin == null || pin.size != 4) return null
        val start = Natives.getSensorStartmsec(dataptr)
        val end = Natives.getSensorEndTime(dataptr, true)
        val wear = if (start > 0L && end > start) ((end - start) / 60_000L).toInt() else 14 * 24 * 60
        val warmup = runCatching { Natives.getSensorWarmupMinutesByName(legacyName) }.getOrDefault(0).takeIf { it in 1..255 } ?: 60
        return Libre3SensorRecord(
            sensorId = serial,
            bleAddress = address.uppercase(),
            blePin = pin,
            startTimeMs = start,
            warmupMinutes = warmup,
            wearMinutes = wear,
            // What the removed driver sent in A0/A8, so a later Kotlin scan keeps working.
            receiverId = Natives.getlibreAccountIDnumber() and 0xFFFFFFFFL,
            firmware = "",
            securityVersion = 0,
        )
    }

    private val provisioning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val failedDownloads = java.util.concurrent.atomic.AtomicInteger(0)
    private val credentialExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Libre3Credentials").apply { isDaemon = true }
        }
    }

    /** Minutes before each retry of a failed automatic download; the last value repeats. */
    internal val CREDENTIAL_RETRY_MINUTES = longArrayOf(1, 2, 5, 15, 30)

    /**
     * A driver found no connection credentials. Download them in the background (once at a
     * time) and wake every driver that was waiting, so a moved or handed-over sensor connects
     * without anyone opening Settings. A failed download (offline, server down) is retried with
     * backoff while a driver still waits; credentials the user removed are not fetched again.
     */
    @JvmStatic
    fun requestCredentials() {
        val context = Applic.app ?: return
        if (!provisioning.compareAndSet(false, true)) return
        credentialExecutor.execute { downloadCredentials(context) }
    }

    private fun downloadCredentials(context: Context) {
        var retryInMinutes = 0L
        try {
            val installed = runCatching { Libre3Persistence.ensureIdentities(context) }
                .onFailure { Log.w(TAG, "credential download failed: ${it.message}") }
                .getOrDefault(false)
            if (installed) {
                failedDownloads.set(0)
                wakeDriversWaitingForCredentials()
            } else if (waitingForCredentials().isNotEmpty() &&
                !Libre3Persistence.autoProvisionBlocked(context) &&
                Libre3Persistence.effectiveIdentitySource(context) != null
            ) {
                val attempt = failedDownloads.getAndIncrement()
                retryInMinutes = CREDENTIAL_RETRY_MINUTES[minOf(attempt, CREDENTIAL_RETRY_MINUTES.size - 1)]
            } else {
                failedDownloads.set(0)
            }
        } finally {
            if (retryInMinutes > 0L) {
                Log.i(TAG, "credential download retry in $retryInMinutes min")
                credentialExecutor.schedule({ downloadCredentials(context) }, retryInMinutes, java.util.concurrent.TimeUnit.MINUTES)
            } else {
                provisioning.set(false)
            }
        }
    }

    private fun waitingForCredentials(): List<Libre3BleManager> =
        SensorBluetooth.gattcallbacks.filterIsInstance<Libre3BleManager>().filter { it.isWaitingForCredentials() }

    /** Credentials were installed (download, file import, watch handoff): reconnect who waited. */
    @JvmStatic
    fun wakeDriversWaitingForCredentials() {
        waitingForCredentials().forEach { it.softReconnect() }
    }
}
