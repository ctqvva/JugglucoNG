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
        if (Libre3Persistence.loadIdentities(ctx).isEmpty()) return Outcome.MissingIdentity

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
        val existing = findDriver(id)
        if (existing == null) retireLegacyCallback(id)
        Libre3Persistence.saveSensor(context, record)
        SensorIdentity.invalidateCaches()
        if (existing != null) {
            existing.onNfcRecordUpdated()
            return id
        }
        val blue = SensorBluetooth.blueone ?: return id
        val callback = Libre3BleManager(id)
        SensorBluetooth.gattcallbacks.add(callback)
        runCatching { Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size) }
        if (SensorBluetooth.blueone === blue) callback.connectDevice(0)
        return id
    }

    /**
     * A sensor moving from the legacy native driver to this one must not keep its old callback:
     * both would connect to the same address with different keys. Finishing the native record
     * keeps its history and stops it from being rebuilt; rescanning with this driver disabled
     * brings the legacy path back.
     */
    private fun retireLegacyCallback(serial: String) {
        val legacyName = Libre3IdentityAdapter.legacyNativeName(serial)
        val legacy = SensorBluetooth.gattcallbacks.filter {
            it !is Libre3BleManager && it.SerialNumber.equals(legacyName, ignoreCase = true)
        }
        val hasNative = runCatching { Natives.str2sensorptr(legacyName) != 0L }.getOrDefault(false)
        if (legacy.isEmpty() && !hasNative) return
        Log.i(TAG, "Handing $legacyName from the legacy Libre 3 driver to the Kotlin driver")
        runCatching { Natives.finishSensorById(legacyName) }.onFailure { Log.stack(TAG, "finishSensorById", it) }
        if (legacy.isNotEmpty()) SensorBluetooth.sensorEnded(legacyName)
    }
}
