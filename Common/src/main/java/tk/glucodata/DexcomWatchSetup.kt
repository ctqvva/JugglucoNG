package tk.glucodata

import org.json.JSONObject

/**
 * Sets a phone's Dexcom G7/ONE+ up on the watch, so the watch can read it.
 *
 * Managed sensors travel as preferences ([ManagedSensorHandoff]). A Dexcom
 * record lives in native storage, which used to reach the watch over the
 * legacy `/data` mirror; the watch has ignored that since WearSync2 took over
 * its store, so a watch was never able to take a Dexcom over (#862).
 *
 * Nothing secret travels. The phone sends the scan the sensor was set up from
 * (serial and 4-digit pairing code, as stored in the record's header), and the
 * watch replays it through the same native scan entry the QR and manual-code
 * paths use. That builds a record with the same name as the phone's, so both
 * devices and their histories agree on which sensor it is. The watch then
 * pairs on its own Bluetooth slot: a G7 serves a receiver, an app and a watch
 * at once, each with its own key, so the phone's pairing is left alone.
 */
object DexcomWatchSetup {
    private const val LOG_ID = "DexcomWatchSetup"
    private const val KEY = "dexcom"
    private const val KEY_SCAN = "scan"
    private const val KEY_SERIAL = "serial"
    private const val KEY_PHONE_SLOT = "phoneSlot"

    /** Phone: the main sensor's setup for the watch, or null when it is not a Dexcom here. */
    @JvmStatic
    fun phoneSetupFor(sensorId: String?): JSONObject? {
        if (Applic.isWearable || sensorId.isNullOrBlank()) return null
        val callback = dexcomCallbackFor(sensorId) ?: return null
        val dataptr = callback.dataptr.takeIf { it != 0L } ?: return null
        val scan = runCatching { Natives.dexScanPayload(dataptr) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return JSONObject()
            .put(KEY_SCAN, scan)
            .put(KEY_SERIAL, callback.SerialNumber)
            .put(KEY_PHONE_SLOT, DexcomBluetoothSlot.forSensor(callback.SerialNumber))
    }

    /** Phone: whether [sensorId] is a Dexcom whose setup can be sent to the watch. */
    @JvmStatic
    fun canSendToWatch(sensorId: String?): Boolean = phoneSetupFor(sensorId) != null

    /** Adds the Dexcom part to an outgoing handoff payload, if there is one. */
    internal fun attach(root: JSONObject, sensorId: String?) {
        phoneSetupFor(sensorId)?.let { root.put(KEY, it) }
    }

    /** Whether an incoming payload carries a scan to set a Dexcom up from. */
    internal fun carriesSetup(root: JSONObject): Boolean =
        root.optJSONObject(KEY)?.optString(KEY_SCAN, "")?.isNotEmpty() == true

    /** The serial in an incoming payload's Dexcom part, if it has one. */
    internal fun incomingSerial(root: JSONObject): String? =
        root.optJSONObject(KEY)?.optString(KEY_SERIAL, "")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Watch: build the Dexcom record from an incoming payload.
     *
     * Skipped when this watch already has a Dexcom driver for the sensor: the
     * phone refreshes its setup periodically, and rebuilding the driver roster
     * each time would drop a live connection.
     */
    internal fun applyOnWatch(root: JSONObject): Boolean {
        if (!Applic.isWearable) return false
        val part = root.optJSONObject(KEY) ?: return false
        val scan = part.optString(KEY_SCAN, "").takeIf { it.isNotEmpty() } ?: return false
        val serial = part.optString(KEY_SERIAL, "")
        if (serial.isNotEmpty() && dexcomCallbackFor(serial) != null) return true
        val slot = watchSlotFor(part.optInt(KEY_PHONE_SLOT, DexcomBluetoothSlot.DEFAULT))
        val name = runCatching { Natives.addSIscangetName(scan, intArrayOf(-1)) }
            .onFailure { Log.stack(LOG_ID, "addSIscangetName", it) }
            .getOrNull()
        if (name.isNullOrEmpty()) {
            Log.w(LOG_ID, "could not set up Dexcom $serial on this watch")
            return false
        }
        // Before the driver exists: its constructor reads the slot.
        DexcomBluetoothSlot.pin(name, slot)
        if (serial.isNotEmpty() && serial != name) DexcomBluetoothSlot.pin(serial, slot)
        if (runCatching { Natives.getusebluetooth() }.getOrDefault(false)) {
            Applic.updateDevices()
        } else {
            runCatching { Natives.updateUsedSensors() }
        }
        Log.i(LOG_ID, "set up Dexcom $name on this watch, Bluetooth slot $slot")
        return true
    }

    private fun dexcomCallbackFor(sensorId: String): SuperGattCallback? = runCatching {
        SensorBluetooth.mygatts()?.firstOrNull {
            it is DexGattCallback && SensorIdentity.matches(it.SerialNumber, sensorId)
        }
    }.getOrNull()
}

/**
 * The slot the watch pairs on: the watch's own, unless the phone already
 * holds it, in which case the app slot the phone left free. Two devices on
 * one slot keep taking the key from each other.
 */
internal fun watchSlotFor(phoneSlot: Int): Int =
    if (phoneSlot == DexcomBluetoothSlot.SMART_WATCH) DexcomBluetoothSlot.MOBILE_APP
    else DexcomBluetoothSlot.SMART_WATCH
