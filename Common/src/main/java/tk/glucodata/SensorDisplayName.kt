package tk.glucodata

import android.content.Context
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorUiSnapshot
import tk.glucodata.settings.KEY_SENSOR_DISPLAY_NAMES
import tk.glucodata.settings.SettingsRegistry

/**
 * Shared sensor display-name resolution for both phone and watch.
 *
 * The phone knows every sensor's display name from either its managed driver snapshot
 * ([ManagedSensorUiSnapshot.displayName]) or its Bluetooth callback
 * ([SuperGattCallback.mygetDeviceName]). The watch does not hold a local driver or BLE
 * callback for sensors the phone reads on its behalf (cloud followers, non-handed-off BLE
 * sensors, or a Dexcom G7 before the watch has paired it), so the phone mirrors its
 * effective sensor-name map alongside the other display preferences in [WearPrefsSync].
 */
object SensorDisplayName {
    private const val PREFS_NAME = SettingsRegistry.FILE
    private val MANAGED_PREFIX_REGEX = Regex("^[A-Z0-9]{2,6}:")
    private val MAC_ADDRESS_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    @Volatile
    private var cachedMirrored: Map<String, String>? = null

    @Volatile
    private var lastPushedWire: String? = null

    /** Strip leading managed-driver family prefixes like `SIBI:` when falling back to an id. */
    @JvmStatic
    fun stripManagedPrefix(sensorId: String): String =
        sensorId.trim().replace(MANAGED_PREFIX_REGEX, "")

    /** True when [value] is a Bluetooth MAC address placeholder rather than a device name. */
    @JvmStatic
    fun isMacAddress(value: String?): Boolean =
        value != null && MAC_ADDRESS_REGEX.matches(value.trim())

    /** Display name for a managed driver snapshot, matching the phone's sensor card rule. */
    @JvmStatic
    fun fromManagedSnapshot(snapshot: ManagedSensorUiSnapshot): String =
        usableName(snapshot.displayName) ?: snapshot.serial

    /** Display name for a native GATT callback, matching the phone's sensor card rule. */
    @JvmStatic
    fun fromCallback(callback: SuperGattCallback, fallbackSerial: String): String =
        usableName(runCatching { callback.mygetDeviceName() }.getOrNull()) ?: fallbackSerial

    /**
     * Display name for [sensorId] on this device.
     *
     * Prefers a live local driver/callback name, falls back to the phone-mirrored name
     * when the watch does not read the sensor directly (or only has a pre-scan MAC address),
     * and finally falls back to [stripManagedPrefix].
     */
    @JvmStatic
    @JvmOverloads
    fun resolve(
        sensorId: String?,
        managedSnapshot: ManagedSensorUiSnapshot? = null,
    ): String {
        val raw = sensorId?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val managed = managedSnapshot
            ?: runCatching { ManagedSensorRuntime.resolveUiSnapshot(raw, raw) }.getOrNull()
        val callbackName = localCallbackDeviceName(raw)
        val mirrored = if (Applic.isWearable) mirroredNames() else emptyMap()
        return resolveName(
            sensorId = raw,
            managedDisplayName = managed?.displayName,
            callbackDisplayName = callbackName,
            mirroredNames = mirrored,
        )
    }

    /**
     * Pure resolution logic, separated from Android/JNI lookups for unit testing.
     *
     * Precedence:
     * 1. Local managed driver display name (when usable).
     * 2. Local Bluetooth callback device name, excluding raw MAC fallbacks.
     * 3. Phone-mirrored display name (exact, 11-char native alias, or [SensorIdentity.matches]).
     * 4. Local Bluetooth callback MAC fallback (only when no mirrored name exists).
     * 5. Prefix-stripped [sensorId].
     */
    internal fun resolveName(
        sensorId: String,
        managedDisplayName: String?,
        callbackDisplayName: String?,
        mirroredNames: Map<String, String>,
        matchesId: (String, String) -> Boolean = ::matchesSensorKey,
    ): String {
        val raw = sensorId.trim()
        if (raw.isEmpty()) return ""
        usableName(managedDisplayName)?.let { return it }
        val usableCallback = usableName(callbackDisplayName)
        if (usableCallback != null && !isMacAddress(usableCallback)) {
            return usableCallback
        }
        lookupMirrored(raw, mirroredNames, matchesId)?.let { return it }
        if (usableCallback != null) {
            return usableCallback
        }
        return stripManagedPrefix(raw).ifEmpty { raw }
    }

    internal fun lookupMirrored(
        sensorId: String,
        mirroredNames: Map<String, String>,
        matchesId: (String, String) -> Boolean = ::matchesSensorKey,
    ): String? {
        if (mirroredNames.isEmpty()) return null
        val target = sensorId.trim()
        if (target.isEmpty()) return null
        // Exact / case-insensitive match first, then alias / SensorIdentity match.
        mirroredNames.entries
            .firstOrNull { (key, _) -> key.equals(target, ignoreCase = true) }
            ?.value
            ?.let(::usableName)
            ?.let { return it }
        return mirroredNames.entries
            .firstOrNull { (key, _) -> matchesId(key, target) }
            ?.value
            ?.let(::usableName)
    }

    internal fun matchesSensorKey(storedKey: String, sensorId: String): Boolean {
        if (storedKey.equals(sensorId, ignoreCase = true)) return true
        if (nativeAliasMatches(storedKey, sensorId)) return true
        return runCatching { SensorIdentity.matches(storedKey, sensorId) }.getOrDefault(false)
    }

    /**
     * Native records on the phone can use a 16-char full name while the watch holds the
     * 11-char short alias (or vice versa), and native lookup only resolves short -> full.
     */
    internal fun nativeAliasMatches(left: String, right: String): Boolean {
        val a = left.trim()
        val b = right.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.equals(b, ignoreCase = true)) return true
        if (a.length == 16 && b.length == 11 && a.endsWith(b, ignoreCase = true)) return true
        if (b.length == 16 && a.length == 11 && b.endsWith(a, ignoreCase = true)) return true
        return false
    }

    /**
     * Phone: builds the single-line escaped wire payload of active sensors' display names
     * for [WearPrefsSync]. Returns null on Wear (only the phone owns this mirror).
     */
    @JvmStatic
    fun effectiveWireValue(): String? {
        if (Applic.isWearable) return null
        return runCatching {
            val entries = collectPhoneDisplayNames()
            WearPrefsSync.escapeLine(encodeStoredMap(entries))
        }.getOrNull()
    }

    /**
     * Phone: pushes updated display names to the watch when the effective map changed
     * (e.g. a newly paired BLE sensor just reported its advertised device name).
     */
    @JvmStatic
    fun pushIfChanged() {
        if (Applic.isWearable) return
        val wire = effectiveWireValue() ?: return
        if (wire == lastPushedWire) return
        lastPushedWire = wire
        runCatching { WearPrefsSync.push() }
    }

    /** Called by [WearPrefsSync.apply] after updating [KEY_SENSOR_DISPLAY_NAMES]. */
    @JvmStatic
    fun invalidateCache() {
        cachedMirrored = null
    }

    /** Encodes `sensorId -> displayName` as newline-separated `id|name` lines for prefs storage. */
    internal fun encodeStoredMap(entries: Map<String, String>): String =
        entries.entries.mapNotNull { (rawKey, rawName) ->
            val key = usableName(rawKey)?.takeIf { '|' !in it } ?: return@mapNotNull null
            val name = usableName(rawName) ?: return@mapNotNull null
            "$key|$name"
        }.joinToString("\n")

    /** Decodes newline-separated `id|name` lines back into a map, ignoring malformed entries. */
    internal fun decodeStoredMap(stored: String): Map<String, String> {
        if (stored.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        stored.lineSequence().forEach { line ->
            val separator = line.indexOf('|')
            if (separator <= 0 || separator >= line.lastIndex) return@forEach
            val key = usableName(line.substring(0, separator)) ?: return@forEach
            val name = usableName(line.substring(separator + 1)) ?: return@forEach
            out[key] = name
        }
        return out
    }

    private fun usableName(value: String?): String? =
        value?.trim()?.takeIf { SensorIdentity.isUsableSensorId(it) }

    private fun localCallbackDeviceName(sensorId: String): String? = runCatching {
        SensorBluetooth.mygatts()
            .firstOrNull { gatt ->
                val serial = gatt.SerialNumber
                !serial.isNullOrBlank() && SensorIdentity.matches(serial, sensorId)
            }
            ?.let { gatt -> runCatching { gatt.mygetDeviceName() }.getOrNull() }
    }.getOrNull()

    private fun collectPhoneDisplayNames(): Map<String, String> {
        val primary = runCatching { SensorIdentity.resolveMainSensor() }.getOrNull()
        val candidates = ArrayList<String?>()
        runCatching { NotificationMultiSensorSource.candidateSensorIds(primary) }
            .getOrDefault(emptyList())
            .forEach(candidates::add)
        runCatching { SensorBluetooth.mygatts() }
            .getOrDefault(emptyList())
            .forEach { candidates.add(it.SerialNumber) }
        val distinct = SensorIdentity.distinctLogicalSensorIds(candidates)
        val out = LinkedHashMap<String, String>()
        distinct.forEach { id ->
            val canonical = runCatching { SensorIdentity.resolveAppSensorId(id) }.getOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() } ?: id
            val managed = runCatching { ManagedSensorRuntime.resolveUiSnapshot(canonical, canonical) }
                .getOrNull()
            val managedName = usableName(managed?.displayName)
            val callbackName = usableName(localCallbackDeviceName(canonical))
                ?.takeUnless(::isMacAddress)
            val displayName = managedName ?: callbackName ?: return@forEach
            out[canonical] = displayName
        }
        return out
    }

    private fun mirroredNames(): Map<String, String> {
        cachedMirrored?.let { return it }
        val loaded = runCatching {
            val prefs = Applic.app?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?: return@runCatching emptyMap()
            decodeStoredMap(prefs.getString(KEY_SENSOR_DISPLAY_NAMES, "").orEmpty())
        }.getOrDefault(emptyMap())
        cachedMirrored = loaded
        return loaded
    }
}
