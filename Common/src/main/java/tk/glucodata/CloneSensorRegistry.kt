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

    @JvmStatic
    fun markCloneSensor(sensorId: String?, transportCode: Int) {
        val key = CloneSensorKeyCodec.normalize(sensorId) ?: return
        val transport = CloneTransport.fromCode(transportCode)
        synchronized(lock) {
            val preferences = prefs() ?: return
            val current = CloneSensorKeyCodec.decode(preferences.getString(KEY_SENSOR_IDS, null))
            val effectiveTransport = if (transport == CloneTransport.UNKNOWN) {
                current[key] ?: CloneTransport.UNKNOWN
            } else {
                transport
            }
            if (current[key] == effectiveTransport) return
            preferences.edit()
                .putString(KEY_SENSOR_IDS, CloneSensorKeyCodec.encode(current + (key to effectiveTransport)))
                .apply()
        }
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
    fun transportForSensor(sensorId: String?): CloneTransport? {
        val requested = candidateKeys(sensorId)
        if (requested.isEmpty()) return null
        val stored = CloneSensorKeyCodec.decode(prefs()?.getString(KEY_SENSOR_IDS, null))
        return requested.asSequence().mapNotNull(stored::get).firstOrNull()
    }
}
