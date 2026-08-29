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

/** Records which sensor files are being populated by the phone-to-phone clone path. */
object CloneSensorRegistry {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val KEY_SENSOR_IDS = "clone_source_sensor_ids_v1"
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
    fun markCloneSensor(sensorId: String?, transportCode: Int) {
        val key = registryKey(sensorId) ?: return
        val aliases = candidateKeys(sensorId) + key
        val transport = CloneTransport.fromCode(transportCode)
        synchronized(lock) {
            val preferences = prefs() ?: return
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
        }
        runCatching { SensorBluetooth.blockLocalCloneConnection(sensorId) }
    }

    @JvmStatic
    fun markLocalSensor(sensorId: String?) {
        val localKeys = candidateKeys(sensorId)
        if (localKeys.isEmpty()) return
        synchronized(lock) {
            val preferences = prefs() ?: return
            val current = CloneSensorKeyCodec.decode(preferences.getString(KEY_SENSOR_IDS, null))
            val updated = current.filterKeys { it !in localKeys }
            if (updated == current) return
            preferences.edit()
                .putString(KEY_SENSOR_IDS, CloneSensorKeyCodec.encode(updated))
                .apply()
        }
    }

    @JvmStatic
    fun isCloneSensor(sensorId: String?): Boolean {
        return transportForSensor(sensorId) != null
    }

    @JvmStatic
    fun deactivateAllCloneSensors() {
        val sensorIds = synchronized(lock) {
            CloneSensorKeyCodec.decode(prefs()?.getString(KEY_SENSOR_IDS, null)).keys.toList()
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
        val sensorIds = synchronized(lock) {
            CloneSensorKeyCodec.decode(prefs()?.getString(KEY_SENSOR_IDS, null)).keys.toList()
        }
        val primaryAliases = candidateKeys(primarySensorId)
        CloneSensorKeyCodec.nonPrimarySensorIds(sensorIds, primaryAliases).forEach { sensorId ->
            runCatching {
                val sensorPointer = Natives.str2sensorptr(sensorId)
                if (sensorPointer != 0L) Natives.finishfromSensorptr(sensorPointer)
            }
            runCatching { SensorBluetooth.retireCloneSensor(sensorId) }
        }
        val primary = primarySensorId?.takeIf { transportForSensor(it) != null } ?: return
        runCatching { SensorBluetooth.blockLocalCloneConnection(primary) }
        runCatching { SensorBluetooth.setCurrentSensorSelection(primary) }
        runCatching { MultiSensorSelection.moveToFront(primary) }
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
}
