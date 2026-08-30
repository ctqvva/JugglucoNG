package tk.glucodata

import android.content.Context
import java.util.Locale

enum class CloneTransport(val code: Int) {
    UNKNOWN(0),
    LOCAL_ICE(1),
    TURN(2);

    companion object {
        fun fromCode(code: Int): CloneTransport = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

internal object CloneSensorKeyCodec {
    fun normalize(sensorId: String?): String? = sensorId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.uppercase(Locale.ROOT)

    fun decode(encoded: String?): Map<String, CloneTransport> = encoded
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val key = normalize(line.substringBefore('|')) ?: return@mapNotNull null
            val transport = CloneTransport.fromCode(line.substringAfter('|', "0").trim().toIntOrNull() ?: 0)
            key to transport
        }
        .toMap()

    fun encode(entries: Map<String, CloneTransport>): String = entries
        .mapNotNull { (key, transport) -> normalize(key)?.let { it to transport } }
        .distinctBy { it.first }
        .sortedBy { it.first }
        .joinToString("\n") { (key, transport) -> "$key|${transport.code}" }

    fun transportFor(encoded: String?, sensorId: String?): CloneTransport? =
        transportForAny(encoded, listOfNotNull(sensorId))

    fun transportForAny(encoded: String?, sensorIds: Iterable<String>): CloneTransport? {
        val stored = decode(encoded)
        return sensorIds.asSequence().mapNotNull(::normalize).mapNotNull(stored::get).firstOrNull()
    }

    fun nonPrimarySensorIds(sensorIds: Iterable<String>, primarySensorId: String?): List<String> {
        return nonPrimarySensorIds(sensorIds, listOfNotNull(primarySensorId))
    }

    fun nonPrimarySensorIds(
        sensorIds: Iterable<String>,
        primarySensorIds: Iterable<String>
    ): List<String> {
        val primaryKeys = primarySensorIds.mapNotNull(::normalize).toSet()
        return sensorIds.mapNotNull(::normalize).filterNot { it in primaryKeys }
    }
}

internal object CloneSensorConnectionCodec {
    fun decode(encoded: String?): Map<String, String> = encoded
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val key = CloneSensorKeyCodec.normalize(line.substringBefore('|'))
                ?: return@mapNotNull null
            val connectionIdentity = line.substringAfter('|', "").trim()
                .takeIf { it.isNotEmpty() && '|' !in it }
                ?: return@mapNotNull null
            key to connectionIdentity
        }
        .toMap()

    fun encode(entries: Map<String, String>): String = entries
        .mapNotNull { (key, connectionIdentity) ->
            CloneSensorKeyCodec.normalize(key)
                ?.let { normalized ->
                    connectionIdentity.trim()
                        .takeIf { it.isNotEmpty() && '|' !in it && '\n' !in it && '\r' !in it }
                        ?.let { normalized to it }
                }
        }
        .distinctBy { it.first }
        .sortedBy { it.first }
        .joinToString("\n") { (key, connectionIdentity) -> "$key|$connectionIdentity" }

    fun connectionForAny(encoded: String?, sensorIds: Iterable<String>): String? {
        val stored = decode(encoded)
        return sensorIds.asSequence().mapNotNull(CloneSensorKeyCodec::normalize)
            .mapNotNull(stored::get)
            .firstOrNull()
    }
}

/** Records which sensor files are being populated by the phone-to-phone clone path. */
object CloneSensorRegistry {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val KEY_SENSOR_IDS = "clone_source_sensor_ids_v1"
    private const val KEY_SENSOR_CONNECTIONS = "clone_source_connection_labels_v1"
    private const val KEY_RECEPTION_ENABLED = "clone_reception_enabled_v1"
    private val lock = Any()

    private fun prefs() = Applic.app?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun candidateKeys(sensorId: String?): Set<String> {
        val raw = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptySet()
        return buildSet {
            CloneSensorKeyCodec.normalize(raw)?.let(::add)
            runCatching { SensorIdentity.canonicalSensorId(raw) }
                .getOrNull()
                ?.let(CloneSensorKeyCodec::normalize)
                ?.let(::add)
            runCatching { SensorIdentity.resolveNativeSensorName(raw) }
                .getOrNull()
                ?.let(CloneSensorKeyCodec::normalize)
                ?.let(::add)
            runCatching { SensorIdentity.resolveRoomStorageSensorId(raw) }
                .getOrNull()
                ?.let(CloneSensorKeyCodec::normalize)
                ?.let(::add)
        }
    }

    private fun registryKey(sensorId: String?): String? =
        runCatching { SensorIdentity.resolveRoomStorageSensorId(sensorId) }
            .getOrNull()
            ?.let(CloneSensorKeyCodec::normalize)
            ?: CloneSensorKeyCodec.normalize(sensorId)

    @JvmStatic
    fun markCloneSensor(sensorId: String?, transportCode: Int, connectionIdentity: String?): Boolean {
        val key = registryKey(sensorId) ?: return false
        val aliases = candidateKeys(sensorId) + key
        val transport = CloneTransport.fromCode(transportCode)
        synchronized(lock) {
            val preferences = prefs() ?: return false
            if (!preferences.getBoolean(KEY_RECEPTION_ENABLED, true)) return false
            val current = CloneSensorKeyCodec.decode(preferences.getString(KEY_SENSOR_IDS, null))
            val effectiveTransport = if (transport == CloneTransport.UNKNOWN) {
                aliases.asSequence().mapNotNull(current::get).firstOrNull() ?: CloneTransport.UNKNOWN
            } else {
                transport
            }
            val updated = current.filterKeys { it !in aliases } + (key to effectiveTransport)
            if (updated != current) {
                preferences.edit()
                    .putString(KEY_SENSOR_IDS, CloneSensorKeyCodec.encode(updated))
                    .apply()
            }
            val stableConnectionIdentity = connectionIdentity?.trim()?.takeIf { it.isNotEmpty() }
            if (stableConnectionIdentity != null) {
                val currentConnections = CloneSensorConnectionCodec.decode(
                    preferences.getString(KEY_SENSOR_CONNECTIONS, null)
                )
                val updatedConnections = currentConnections.filterKeys { it !in aliases } +
                    (key to stableConnectionIdentity)
                if (updatedConnections != currentConnections) {
                    preferences.edit()
                        .putString(
                            KEY_SENSOR_CONNECTIONS,
                            CloneSensorConnectionCodec.encode(updatedConnections),
                        )
                        .apply()
                }
            }
        }
        runCatching { SensorBluetooth.blockLocalCloneConnection(sensorId) }
        return true
    }

    @JvmStatic
    fun markLocalSensor(sensorId: String?) {
        val localKeys = candidateKeys(sensorId)
        if (localKeys.isEmpty()) return
        synchronized(lock) {
            val preferences = prefs() ?: return
            val current = CloneSensorKeyCodec.decode(preferences.getString(KEY_SENSOR_IDS, null))
            val updated = current.filterKeys { it !in localKeys }
            val currentConnections = CloneSensorConnectionCodec.decode(
                preferences.getString(KEY_SENSOR_CONNECTIONS, null)
            )
            val updatedConnections = currentConnections.filterKeys { it !in localKeys }
            if (updated != current || updatedConnections != currentConnections) {
                preferences.edit()
                    .putString(KEY_SENSOR_IDS, CloneSensorKeyCodec.encode(updated))
                    .putString(
                        KEY_SENSOR_CONNECTIONS,
                        CloneSensorConnectionCodec.encode(updatedConnections),
                    )
                    .apply()
            }
        }
    }

    @JvmStatic
    fun isCloneSensor(sensorId: String?): Boolean {
        return transportForSensor(sensorId) != null
    }

    fun hasAnyCloneSensor(): Boolean = synchronized(lock) {
        CloneSensorKeyCodec.decode(prefs()?.getString(KEY_SENSOR_IDS, null)).isNotEmpty()
    }

    /**
     * Local receiver gate. The default keeps existing configured receivers working
     * after an upgrade; once the user turns Clone off, late packets must not be
     * able to repopulate the registry until a local connection is enabled again.
     */
    @JvmStatic
    fun isReceptionEnabled(): Boolean = synchronized(lock) {
        prefs()?.getBoolean(KEY_RECEPTION_ENABLED, true) ?: true
    }

    @JvmStatic
    fun setReceptionEnabled(enabled: Boolean) {
        synchronized(lock) {
            prefs()?.edit()?.putBoolean(KEY_RECEPTION_ENABLED, enabled)?.apply()
        }
    }

    @JvmStatic
    fun deactivateAllCloneSensors() {
        val sensorIds = synchronized(lock) {
            val preferences = prefs()
            val registered = CloneSensorKeyCodec.decode(
                preferences?.getString(KEY_SENSOR_IDS, null)
            ).keys.toList()
            preferences?.edit()
                ?.remove(KEY_SENSOR_IDS)
                ?.remove(KEY_SENSOR_CONNECTIONS)
                ?.apply()
            CloneIobSnapshot.clear()
            registered
        }
        sensorIds.forEach { sensorId ->
            runCatching {
                val sensorPointer = Natives.str2sensorptr(sensorId)
                if (sensorPointer != 0L) Natives.finishfromSensorptr(sensorPointer)
            }
            runCatching { SensorBluetooth.retireCloneSensor(sensorId) }
        }
        runCatching { SensorBluetooth.updateDevices() }
    }

    @JvmStatic
    fun reconcilePrimaryCloneSensor(primarySensorId: String?) {
        val primaryAliases = candidateKeys(primarySensorId)
        if (primaryAliases.isEmpty()) return
        val currentEntries = synchronized(lock) {
            CloneSensorKeyCodec.decode(prefs()?.getString(KEY_SENSOR_IDS, null))
        }
        val retained = currentEntries.filterKeys { storedId ->
            candidateKeys(storedId).any { it in primaryAliases }
        }
        if (retained.isEmpty()) return

        val receiverPrimary = SensorIdentity.resolveMainSensor()
        val receiverWasFollowingClone = currentEntries.keys.any { sensorId ->
            candidateKeys(sensorId).any { it in candidateKeys(receiverPrimary) }
        }
        val retired = currentEntries.keys.filterNot(retained::containsKey)
        synchronized(lock) {
            val preferences = prefs()
            val currentConnections = CloneSensorConnectionCodec.decode(
                preferences?.getString(KEY_SENSOR_CONNECTIONS, null)
            )
            val retainedConnections = currentConnections.filterKeys(retained::containsKey)
            preferences?.edit()
                ?.putString(KEY_SENSOR_IDS, CloneSensorKeyCodec.encode(retained))
                ?.putString(
                    KEY_SENSOR_CONNECTIONS,
                    CloneSensorConnectionCodec.encode(retainedConnections),
                )
                ?.apply()
        }
        retired.forEach { sensorId ->
            runCatching {
                val sensorPointer = Natives.str2sensorptr(sensorId)
                if (sensorPointer != 0L) Natives.finishfromSensorptr(sensorPointer)
            }
            runCatching { SensorBluetooth.retireCloneSensor(sensorId) }
        }
        val primary = primarySensorId?.takeIf { transportForSensor(it) != null } ?: return
        runCatching { SensorBluetooth.blockLocalCloneConnection(primary) }
        if (receiverWasFollowingClone && !SensorIdentity.matches(receiverPrimary, primary)) {
            runCatching { SensorBluetooth.setCurrentSensorSelection(primary) }
            runCatching { MultiSensorSelection.moveToFront(primary) }
        }
    }

    @JvmStatic
    fun transportForSensor(sensorId: String?): CloneTransport? {
        val requested = candidateKeys(sensorId)
        if (requested.isEmpty()) return null
        return CloneSensorKeyCodec.transportForAny(
            prefs()?.getString(KEY_SENSOR_IDS, null),
            requested,
        )
    }

    /** Returns the route selected by the sensor's current ICE connection, not its last imported row. */
    fun liveTransportForSensor(sensorId: String?): CloneTransport? {
        val requested = candidateKeys(sensorId)
        if (requested.isEmpty() || !isCloneSensor(sensorId)) return null
        val connectionIdentity = CloneSensorConnectionCodec.connectionForAny(
            prefs()?.getString(KEY_SENSOR_CONNECTIONS, null),
            requested,
        ) ?: return CloneTransport.UNKNOWN
        return runCatching {
            CloneTransport.fromCode(Natives.getCloneConnectionTransport(connectionIdentity))
        }.getOrDefault(CloneTransport.UNKNOWN)
    }
}
