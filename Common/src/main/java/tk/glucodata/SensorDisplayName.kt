package tk.glucodata

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicLong
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
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

    private val cacheGeneration = AtomicLong(0L)
    private val cacheLock = Any()

    @Volatile
    private var cachedMirrored: Map<String, String>? = null

    @Volatile
    private var cachedMirroredGeneration: Long = -1L

    private val pushLock = Any()

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
     * Short-circuits lazily to keep UI, watch-face, and complication paths cheap:
     * skips managed snapshot construction unless a matching local managed driver is present,
     * and skips callback/mirrored lookups once a live non-MAC name is found.
     */
    @JvmStatic
    @JvmOverloads
    fun resolve(
        sensorId: String?,
        managedSnapshot: ManagedSensorUiSnapshot? = null,
    ): String {
        val raw = sensorId?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val managedName = usableName(managedSnapshot?.displayName)
            ?: if (managedSnapshot == null) localManagedDisplayName(raw) else null
        if (managedName != null && !isMacAddress(managedName)) {
            return managedName
        }
        val callbackName = usableName(localCallbackDeviceName(raw))
        if (callbackName != null && !isMacAddress(callbackName)) {
            return callbackName
        }
        val mirrored = if (Applic.isWearable) mirroredNames() else emptyMap()
        return resolveName(
            sensorId = raw,
            managedDisplayName = managedName,
            callbackDisplayName = callbackName,
            mirroredNames = mirrored,
        )
    }

    /**
     * Pure resolution logic, separated from Android/JNI lookups for unit testing.
     *
     * Precedence:
     * 1. Local managed driver display name, excluding raw MAC fallbacks (Anytime/MQ/iCan
     *    fall back to `mygetDeviceName()` -> MAC before the first scan).
     * 2. Local Bluetooth callback device name, excluding raw MAC fallbacks.
     * 3. Phone-mirrored display name (exact, 16<->11 native alias, [SensorIdentity.matches],
     *    or unambiguous 6-char watch native alias).
     * 4. Local MAC fallback (managed or callback), only when no mirrored name exists.
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
        val usableManaged = usableName(managedDisplayName)
        if (usableManaged != null && !isMacAddress(usableManaged)) {
            return usableManaged
        }
        val usableCallback = usableName(callbackDisplayName)
        if (usableCallback != null && !isMacAddress(usableCallback)) {
            return usableCallback
        }
        lookupMirrored(raw, mirroredNames, matchesId)?.let { return it }
        if (usableManaged != null) {
            return usableManaged
        }
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
        // Exact / case-insensitive match first.
        mirroredNames.entries
            .firstOrNull { (key, _) -> key.equals(target, ignoreCase = true) }
            ?.value
            ?.let(::usableName)
            ?.let { return it }
        // Strong alias / SensorIdentity match (16<->11 native alias or managed identity).
        mirroredNames.entries
            .firstOrNull { (key, _) -> matchesId(key, target) }
            ?.value
            ?.let(::usableName)
            ?.let { return it }
        // 6-char watch native alias (S6 from activeSensors on an 11-char record): only match
        // when unambiguous across the mirrored map so two sensors cannot be confused.
        val shortMatches = mirroredNames.entries.filter { (key, _) -> shortAliasMatches(key, target) }
        if (shortMatches.size == 1) {
            return usableName(shortMatches[0].value)
        }
        return null
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
     * Matches a 6-char watch native listing (`S6`, `name+5` of an 11-char record) against
     * an 11-char or 16-char native key.
     */
    internal fun shortAliasMatches(left: String, right: String): Boolean {
        val a = left.trim()
        val b = right.trim()
        if (a.length == 6 && (b.length == 11 || b.length == 16)) {
            return b.endsWith(a, ignoreCase = true)
        }
        if (b.length == 6 && (a.length == 11 || a.length == 16)) {
            return a.endsWith(b, ignoreCase = true)
        }
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
     * Phone: asynchronously pushes updated display names to the watch when the effective
     * map changed (e.g. a newly paired BLE sensor just reported its advertised device name).
     *
     * Dispatched off the caller's thread so BLE scan callbacks (main looper) and
     * synchronized GATT handlers never block or hold locks across WearPrefsSync.
     */
    @JvmStatic
    fun pushIfChanged() {
        if (Applic.isWearable) return
        if (!runCatching { Applic.useWearos() }.getOrDefault(false)) return
        val task = Runnable {
            synchronized(pushLock) {
                if (MessageSender.getMessageSender() == null) return@synchronized
                val wire = effectiveWireValue() ?: return@synchronized
                if (wire == lastPushedWire) return@synchronized
                lastPushedWire = wire
                runCatching { WearPrefsSync.push() }
            }
        }
        runCatching { Applic.scheduler.execute(task) }
    }

    /** Called by [WearPrefsSync.apply] after updating [KEY_SENSOR_DISPLAY_NAMES]. */
    @JvmStatic
    fun invalidateCache() {
        synchronized(cacheLock) {
            cacheGeneration.incrementAndGet()
            cachedMirrored = null
            cachedMirroredGeneration = -1L
        }
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

    /**
     * Resolves a local managed driver's display name only for a driver that actually matches
     * [sensorId], avoiding full snapshot allocation for unrelated local managed drivers.
     */
    private fun localManagedDisplayName(sensorId: String): String? = runCatching {
        for (gatt in SensorBluetooth.gattcallbacks) {
            val driver = gatt as? ManagedBluetoothSensorDriver ?: continue
            val serial = gatt.SerialNumber
            val matches = (!serial.isNullOrBlank() && matchesSensorKey(serial, sensorId)) ||
                runCatching { driver.matchesManagedSensorId(sensorId) }.getOrDefault(false)
            if (!matches) continue
            val snapshot = runCatching { driver.getManagedUiSnapshot(sensorId) }.getOrNull() ?: continue
            usableName(snapshot.displayName)?.let { return@runCatching it }
        }
        null
    }.getOrNull()

    private fun localCallbackDeviceName(sensorId: String): String? = runCatching {
        for (gatt in SensorBluetooth.gattcallbacks) {
            val serial = gatt.SerialNumber
            if (!serial.isNullOrBlank() && matchesSensorKey(serial, sensorId)) {
                return@runCatching runCatching { gatt.mygetDeviceName() }.getOrNull()
            }
        }
        null
    }.getOrNull()

    private fun collectPhoneDisplayNames(): Map<String, String> {
        val primary = runCatching { SensorIdentity.resolveMainSensor() }.getOrNull()
        val candidates = runCatching { NotificationMultiSensorSource.candidateSensorIds(primary) }
            .getOrDefault(emptyList())
        val distinct = SensorIdentity.distinctLogicalSensorIds(candidates)
        val out = LinkedHashMap<String, String>()
        distinct.forEach { id ->
            val canonical = runCatching { SensorIdentity.canonicalSensorId(id) }.getOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?: runCatching { SensorIdentity.resolveAppSensorId(id) }.getOrNull()
                    ?.trim()?.takeIf { it.isNotEmpty() }
                ?: id
            val managedName = localManagedDisplayName(canonical)?.takeUnless(::isMacAddress)
            val displayName = managedName
                ?: usableName(localCallbackDeviceName(canonical))?.takeUnless(::isMacAddress)
                ?: return@forEach
            out[canonical] = displayName
        }
        return out
    }

    private fun mirroredNames(): Map<String, String> = loadMirroredNames {
        Applic.app?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Loads and caches the mirrored map from [prefsProvider].
     *
     * If [prefsProvider] returns null (app not yet initialized) or throws (e.g. Direct Boot
     * before credential-encrypted storage is unlocked), returns an empty map *without*
     * caching it so subsequent calls retry once storage is ready.
     */
    internal fun loadMirroredNames(prefsProvider: () -> SharedPreferences?): Map<String, String> {
        val generation = cacheGeneration.get()
        val existing = cachedMirrored
        if (existing != null && cachedMirroredGeneration == generation) {
            return existing
        }
        val prefs = runCatching(prefsProvider).getOrNull() ?: return emptyMap()
        val loaded = runCatching {
            decodeStoredMap(prefs.getString(KEY_SENSOR_DISPLAY_NAMES, "").orEmpty())
        }.getOrNull() ?: return emptyMap()
        synchronized(cacheLock) {
            if (cacheGeneration.get() == generation) {
                cachedMirrored = loaded
                cachedMirroredGeneration = generation
            }
        }
        return loaded
    }
}
