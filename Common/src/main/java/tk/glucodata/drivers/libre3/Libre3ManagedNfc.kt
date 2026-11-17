// Libre3ManagedNfc.kt — NFC scan and sensor registration for the Kotlin Libre 3 driver.
//
// Replaces, for sensors this driver owns, the legacy path NFC.java → startTimeIDsum →
// interpret3NFC2 → native sensor record. The patch-info read and the A0/A8 command are plain
// ISO 15693 transceives through the existing AlgNfcV helper.

package tk.glucodata.drivers.libre3

import android.content.Context
import android.nfc.Tag
import tk.glucodata.AlgNfcV
import tk.glucodata.Applic
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

    @JvmStatic
    fun isEnabled(): Boolean = Applic.app?.let { Libre3Persistence.isEnabled(it) } ?: false

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
                "secver=${info.securityVersion} wear=${info.wearDurationMinutes} warmup=${info.warmupMinutes}",
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
                // A sensor that reported no activation time keeps the start we already know, so a
                // rescan does not look like a new activation (which would reset backfill marks).
                val known = Libre3Persistence.loadSensor(ctx, info.serial)
                val startMs = if (result.reportedActivationTime || known == null || known.startTimeMs <= 0L) {
                    result.activationTimeSeconds * 1000L
                } else known.startTimeMs
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
        // Finish the legacy native record before anything else can rebuild its callback.
        val legacy = finishLegacySensor(id)
        Libre3Persistence.saveSensor(context, record)
        SensorIdentity.invalidateCaches()
        val existing = findDriver(id)
        if (existing != null) {
            retireLegacyCallbacks(legacy, id)
            existing.onNfcRecordUpdated()
            return id
        }
        val blue = SensorBluetooth.blueone
        if (blue == null) {
            retireLegacyCallbacks(legacy, id)
            return id
        }
        val callback = Libre3BleManager(id)
        SensorBluetooth.gattcallbacks.add(callback)
        runCatching { Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size) }
        // After the successor is in the list, so the current-sensor role can move to it.
        retireLegacyCallbacks(legacy, id)
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
     * keeps its history and stops it from being rebuilt; rescanning with this driver disabled
     * brings the legacy path back.
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

    private fun retireLegacyCallbacks(legacy: List<SuperGattCallback>, successor: String) {
        legacy.forEach { cb ->
            Log.i(TAG, "Retiring legacy Libre 3 callback ${cb.SerialNumber} for $successor")
            runCatching { SensorBluetooth.retireCallback(cb, successor) }.onFailure { Log.stack(TAG, "retireCallback", it) }
        }
    }

    /**
     * The inverse hand-over: a legacy NFC scan has just taken this sensor (legacy name
     * [legacyName], e.g. "XX<serial>"). Stop the Kotlin driver for it and drop its pairing, or
     * both drivers would compete for the sensor, and the saved record would bring this one back
     * after a restart. History stays in Room; a later Kotlin scan pairs it again.
     */
    @JvmStatic
    fun retireManagedForLegacyScan(context: Context, legacyName: String?) {
        val name = legacyName?.trim().takeIf { !it.isNullOrEmpty() } ?: return
        val serial = Libre3Persistence.sensorIds(context).firstOrNull { isLegacyCallbackName(name, it) } ?: return
        Log.i(TAG, "Legacy scan of $name takes $serial over from the Kotlin Libre 3 driver")
        val drivers = SensorBluetooth.gattcallbacks.filterIsInstance<Libre3BleManager>().filter { it.matchesManagedSensorId(serial) }
        // Stops the link and finishes the managed native mirror while its record still claims it.
        drivers.forEach { runCatching { it.terminateManagedSensor(wipeData = false) }.onFailure { e -> Log.stack(TAG, "terminate", e) } }
        if (drivers.isEmpty()) runCatching { Natives.finishSensorById(serial) }
        Libre3Persistence.removeSensor(context, serial)
        SensorIdentity.invalidateCaches()
        drivers.forEach { runCatching { SensorBluetooth.retireCallback(it, name) }.onFailure { e -> Log.stack(TAG, "retireCallback", e) } }
    }
}
