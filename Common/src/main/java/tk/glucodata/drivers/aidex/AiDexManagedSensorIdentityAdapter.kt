package tk.glucodata.drivers.aidex

import android.bluetooth.BluetoothAdapter
import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SensorIdentity
import tk.glucodata.SensorBluetooth
import tk.glucodata.drivers.ManagedSensorIdentityAdapter
import tk.glucodata.drivers.ManagedSensorIdentityRegistry
import tk.glucodata.SuperGattCallback

object AiDexManagedSensorIdentityAdapter : ManagedSensorIdentityAdapter {
    private const val PREFIX = "X-"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_KEY = "aidex_sensors"

    private fun normalized(sensorId: String?): String? =
        sensorId?.trim()?.takeIf { it.isNotEmpty() }

    private fun readPersistedEntries(context: Context): LinkedHashSet<String> {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(PREF_KEY, linkedSetOf())
                ?.toCollection(LinkedHashSet())
                ?: linkedSetOf()
        } catch (_: Throwable) {
            linkedSetOf()
        }
    }

    private fun persistEntries(context: Context, entries: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_KEY, LinkedHashSet(entries))
            .commit()
        SensorIdentity.invalidateCaches()
    }

    fun isManagedSensorId(sensorId: String?): Boolean {
        val normalized = normalized(sensorId) ?: return false
        return normalized.startsWith(PREFIX, ignoreCase = true)
    }

    fun nativeAlias(sensorId: String?): String? {
        val normalized = normalized(sensorId) ?: return null
        return if (isManagedSensorId(normalized) && normalized.length > PREFIX.length) {
            normalized.substring(PREFIX.length)
        } else {
            null
        }
    }

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val normalizedCallbackId = normalized(callbackId) ?: return false
        return normalizedCallbackId.equals(sensorId, ignoreCase = true) ||
            nativeAlias(normalizedCallbackId)?.equals(sensorId, ignoreCase = true) == true
    }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val normalized = normalized(sensorId) ?: return null
        if (isManagedSensorId(normalized)) {
            val alias = nativeAlias(normalized) ?: return null
            return "$PREFIX${alias.uppercase()}"
        }
        SensorBluetooth.mygatts()
            .firstOrNull { callback -> matchesCallbackId(callback.SerialNumber, normalized) }
            ?.SerialNumber
            ?.takeIf { it.isNotBlank() && isManagedSensorId(it) }
            ?.let { return it }
        val context = Applic.app
        if (context != null) {
            persistedSensorIds(context)
                .firstOrNull { matchesCallbackId(it, normalized) }
                ?.let { return it }
        }
        return null
    }

    override fun resolveStableStorageSensorId(sensorId: String?): String? {
        val normalized = normalized(sensorId) ?: return null
        if (isManagedSensorId(normalized)) {
            val alias = nativeAlias(normalized) ?: return null
            return "$PREFIX${alias.uppercase()}"
        }
        return resolveCanonicalSensorId(normalized)
    }

    override fun resolveNativeSensorName(sensorId: String?): String? {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return null
        return canonical.takeIf { isManagedSensorId(it) }
    }

    override fun persistedSensorIds(context: Context): List<String> {
        return readPersistedEntries(context)
            .mapNotNull { entry ->
                parsePersistedEntry(entry).serial.takeIf { it.isNotEmpty() }
            }
            .distinct()
    }

    fun persistedAddress(context: Context, sensorId: String): String? {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return null
        return readPersistedEntries(context).firstNotNullOfOrNull { entry ->
            val parsed = parsePersistedEntry(entry)
            if (parsed.address != null && matchesCallbackId(canonical, parsed.serial)) {
                parsed.address
            } else {
                null
            }
        }
    }

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        if (!isManagedSensorId(sensorId)) {
            return null
        }
        val callback = AiDexNativeFactory.createBleManager(sensorId, dataptr)
        persistedAddress(context, sensorId)?.let { applyPersistedBleAddress(callback, it) }
        return callback
    }

    data class PersistedEntry(
        val serial: String,
        val address: String?,
    )

    fun parsePersistedEntry(entry: String): PersistedEntry {
        val serial = entry.substringBefore('|').trim()
        val address = entry.substringAfter('|', "").trim().takeIf { it.isNotEmpty() }
        return PersistedEntry(serial = serial, address = address)
    }

    fun persistedSerialMatches(entrySerial: String, serial: String): Boolean {
        val want = serial.trim()
        if (want.isEmpty()) return false
        return matchesCallbackId(entrySerial, want) || matchesCallbackId(want, entrySerial)
    }

    fun upsertPersistedAddress(
        entries: Set<String>,
        serial: String,
        address: String,
    ): LinkedHashSet<String> {
        val want = serial.trim()
        val mac = address.trim()
        if (want.isEmpty() || mac.isEmpty()) {
            return LinkedHashSet(entries)
        }
        val matching = entries.map(::parsePersistedEntry).filter { persistedSerialMatches(it.serial, want) }
        if (matching.size == 1 && matching[0].address.equals(mac, ignoreCase = true)) {
            return LinkedHashSet(entries)
        }
        val updated = LinkedHashSet<String>()
        for (entry in entries) {
            val parsed = parsePersistedEntry(entry)
            if (persistedSerialMatches(parsed.serial, want)) continue
            updated.add(entry)
        }
        updated.add("$want|$mac")
        return updated
    }

     /**
      * Persist [serial]→[address] if the MAC is free. Returns false when the address is invalid,
      * when it is occupied by another real SN, or when [serial] spells [address] itself — that is
      * the MAC-as-serial leftover shape the cleanup tears down, so no caller may create it. A
      * no-op that already maps this serial to [address] is success, so the caller may then bind
      * the live GATT to that radio.
      */
    fun persistAddress(context: Context, serial: String, address: String): Boolean {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return false
        }
        val current = readPersistedEntries(context)
        val updated = persistAddressUpdate(current, serial, address) ?: return false
        if (updated != current) {
            persistEntries(context, updated)
        }
        return true
    }

    /**
     * The rows to store once [serial] is bound to [address], or null when the write must be
     * refused: the address already belongs to another real SN, or [serial] spells [address]
     * itself. That second shape is the old MAC-as-serial leftover, which the shared cleanup tears
     * down — so writing it would turn a live sensor into a leftover.
     */
    fun persistAddressUpdate(current: Set<String>, serial: String, address: String): LinkedHashSet<String>? {
        if (AiDexScanIdentity.isMacFallbackSerial(serial, address)) {
            return null
        }
        if (addressOccupiedByOtherRealSerial(current, serial, address)) {
            return null
        }
        return upsertPersistedAddress(current, serial, address)
    }

    /**
     * True when [serial]'s native alias is already a persisted sensor of another driver: storing
     * the AiDex row would make both drivers resolve the same id. [persistedIds] is not guarded,
     * so a driver whose records cannot be read throws instead of reading as "owns nothing".
     */
    fun aliasBelongsToOtherDriver(
        serial: String?,
        adapters: List<ManagedSensorIdentityAdapter>,
        persistedIds: (ManagedSensorIdentityAdapter) -> List<String>,
    ): Boolean = ManagedSensorIdentityRegistry.isPersistedByOtherDriver(this, nativeAlias(serial), adapters, persistedIds)

    fun addressOccupiedByOtherRealSerial(context: Context, serial: String, address: String): Boolean {
        return addressOccupiedByOtherRealSerial(readPersistedEntries(context), serial, address)
    }

    fun addressOccupiedByOtherRealSerial(
        entries: Set<String>,
        serial: String,
        address: String,
    ): Boolean {
        for (entry in entries) {
            val parsed = parsePersistedEntry(entry)
            if (
                AiDexScanIdentity.persistAddressOccupiedByOtherRealSerial(
                    parsed.serial,
                    parsed.address,
                    serial,
                    address,
                )
            ) {
                return true
            }
        }
        return false
    }

    /** The `aidex_sensors` row stored for exactly [sensorId], serial and address as persisted. */
    fun persistedEntryFor(context: Context, sensorId: String?): PersistedEntry? =
        persistedEntryFor(readPersistedEntries(context), sensorId)

    fun persistedEntryFor(entries: Set<String>, sensorId: String?): PersistedEntry? {
        val want = normalized(sensorId) ?: return null
        return entries.map(::parsePersistedEntry).firstOrNull { it.serial.equals(want, ignoreCase = true) }
    }

    /**
      * What the stored row says about [sensorId]: true when its serial spells that row's own MAC
      * (the old MAC-as-serial leftover), false when the row proves it is a real sensor, and null
      * when there is no row or the row carries no address — then only the live callback can tell.
      */
    fun persistedLeftoverVerdict(context: Context, sensorId: String?): Boolean? =
        persistedLeftoverVerdict(readPersistedEntries(context), sensorId)

    fun persistedLeftoverVerdict(entries: Set<String>, sensorId: String?): Boolean? {
        val row = persistedEntryFor(entries, sensorId) ?: return null
        val address = row.address ?: return null
        return AiDexScanIdentity.isMacFallbackSerial(row.serial, address)
    }

    /**
     * The leftover decision shared code makes: the stored row's verdict when it has one, and only
     * otherwise the live check. A live address can be moved by a rebind or a spoofed advert, so it
     * never overrules a row.
     */
    fun leftoverVerdict(persisted: Boolean?, liveCallbackSpellsItsOwnAddress: () -> Boolean): Boolean =
        persisted ?: liveCallbackSpellsItsOwnAddress()

    // BluetoothAdapter.getDefaultAdapter(): no Context reaches this helper; minSdk 26
    @Suppress("DEPRECATION")
    fun applyPersistedBleAddress(callback: SuperGattCallback, address: String): Boolean {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            callback.mActiveDeviceAddress = address
            return false
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            callback.mActiveDeviceAddress = address
            return false
        }
        return try {
            callback.setDevice(adapter.getRemoteDevice(address))
            true
        } catch (_: Throwable) {
            callback.mActiveDeviceAddress = address
            false
        }
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return
        val updated = readPersistedEntries(context).filterNot { entry ->
            val serial = entry.substringBefore('|').trim()
            matchesCallbackId(canonical, serial)
        }.toCollection(LinkedHashSet())
        persistEntries(context, updated)
    }

    override fun resolveNativeHistorySensorNames(sensorId: String?): List<String> {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return emptyList()
        val alias = nativeAlias(canonical)
        return listOfNotNull(alias).distinct()
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        isManagedSensorId(sensorId)

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? =
        if (resolveCanonicalSensorId(sensorId) != null) true else null
}
