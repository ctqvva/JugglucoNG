package tk.glucodata

import org.json.JSONArray
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
    private const val KEY_ANCHOR = "anchor"
    private const val ANCHOR_SIZE = 6

    /** Phone: the main sensor's setup for the watch, or null when it is not a Dexcom here. */
    @JvmStatic
    fun phoneSetupFor(sensorId: String?): JSONObject? {
        if (Applic.isWearable || sensorId.isNullOrBlank()) return null
        val callback = dexcomCallbackFor(sensorId) ?: return null
        val dataptr = callback.dataptr.takeIf { it != 0L } ?: return null
        val scan = runCatching { Natives.dexScanPayload(dataptr) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        // Every slot is counted from the sensor's start, which only a reading
        // establishes. Until the phone has one the watch would count from the
        // moment it was handed the sensor, and the phone's readings it is then
        // sent would land on other slots than its own. The newest reading and
        // its slot travel too: they pin the grid the copies are placed on.
        val anchor = runCatching { Natives.dexPeerAnchor(dataptr) }.getOrNull()
            ?.takeIf { it.size >= ANCHOR_SIZE && it[0] > 0L } ?: return null
        return JSONObject()
            .put(KEY_SCAN, scan)
            .put(KEY_ANCHOR, JSONArray().apply { anchor.forEach { put(it) } })
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
     * Not rebuilt when this watch already has a Dexcom driver for the sensor:
     * the phone refreshes its setup periodically, and rebuilding the driver
     * roster each time would drop a live connection. The one thing a refresh
     * can change is the phone's slot, so a driver that has ended up on it is
     * moved to the slot the phone left.
     */
    internal fun applyOnWatch(root: JSONObject): Boolean {
        if (!Applic.isWearable) return false
        val part = root.optJSONObject(KEY) ?: return false
        val scan = part.optString(KEY_SCAN, "").takeIf { it.isNotEmpty() } ?: return false
        val serial = part.optString(KEY_SERIAL, "")
        val anchor = part.optJSONArray(KEY_ANCHOR)
            ?.takeIf { it.length() >= ANCHOR_SIZE }
            ?.let { array -> LongArray(ANCHOR_SIZE) { array.optLong(it, 0L) } }
        val phoneSlot = part.optInt(KEY_PHONE_SLOT, DexcomBluetoothSlot.DEFAULT)
        val slot = watchSlotFor(phoneSlot)
        val existing = serial.takeIf { it.isNotEmpty() }?.let { dexcomCallbackFor(it) }
        if (existing != null) {
            if (existing.authSlot == phoneSlot) moveToSlot(existing, slot)
            return true
        }
        if (anchor == null || anchor[0] <= 0L) {
            Log.w(LOG_ID, "Dexcom $serial setup has no sensor start; not set up")
            return false
        }
        val name = runCatching { Natives.addDexcomScanFromPeer(scan, anchor) }
            .onFailure { Log.stack(LOG_ID, "addDexcomScanFromPeer", it) }
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

    /**
     * Pairs an existing driver again on [slot], as the sensor card's slot picker
     * does: the key and bond belong to the old slot, so the next connection
     * pairs from the stored code. Paused around the change, which closes the old
     * session itself.
     */
    private fun moveToSlot(callback: DexGattCallback, slot: Int) {
        val wasPaused = SensorBluetooth.isSensorPaused(callback)
        callback.setPause(true)
        callback.changeAuthSlot(slot)
        if (callback.dataptr != 0L) runCatching { Natives.resetbluetooth(callback.dataptr) }
        if (!wasPaused) {
            callback.setPause(false)
            callback.connectDevice(200)
        }
        Log.i(LOG_ID, "moved Dexcom ${callback.SerialNumber} to Bluetooth slot $slot: the phone is now on the slot it used")
    }

    private fun dexcomCallbackFor(sensorId: String): DexGattCallback? = runCatching {
        SensorBluetooth.mygatts()?.firstOrNull {
            it is DexGattCallback && SensorIdentity.matches(it.SerialNumber, sensorId)
        } as? DexGattCallback
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
